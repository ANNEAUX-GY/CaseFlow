package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseTodo;
import com.caseflow.exception.BizException;
import com.caseflow.flow.CaseFlowTemplate;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CaseDeadlineStateMapper;
import com.caseflow.mapper.CaseTodoMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 案件阶段流转引擎。
 *
 * <p><b>阶段任务 = 案件待办</b>（2026-10-12 起）：一个阶段的待办全部办完，
 * 才由领导确认转入下一阶段。阶段定义全在 {@link CaseFlowTemplate}（声明式），
 * 本类只负责执行：算进度、切阶段。
 *
 * <p><b>进度为什么天然归零</b>：进度不落库，永远按「当前阶段的 DONE 待办数 /
 * 当前阶段待办总数」实时算。待办的阶段记在 {@code case_todo.flow_stage}，
 * 切换阶段后新阶段的待办分母从 0 起算，<strong>不需要任何"重置"动作</strong>，
 * 也就不存在"忘了重置导致进度虚高"的历史包袱。
 *
 * <p><b>阶段完成不自动跳</b>：出口（初查→刑拘、刑拘→取保）是人的决定，
 * 必须领导点确认，详见 {@link #transfer}。
 *
 * <p><b>与盯办状态机并存</b>：本引擎只改 {@code flow_stage}，
 * 不碰 {@code investigation_status}（那是盯办审批用的），两者互不干扰。
 */
@Service
public class FlowService {

    @Resource
    private CaseTodoMapper todoMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseDeadlineStateMapper deadlineStateMapper;
    @Resource
    private LogService logService;
    @Resource
    private com.caseflow.mapper.CaseApprovalMapper approvalMapper;
    @Resource
    private com.caseflow.mapper.CaseAssigneeMapper assigneeMapper;

    /**
     * 领导意见服务。
     *
     * <p>退回动作要「提一条意见」才能让民警真的收到一件要办的事，
     * 但 OpinionService → StaffTodoService → FlowService（stampStage）本身已构成一条链，
     * 这里直接注入会成环，故用 {@link Lazy} 延迟到首次调用再取。
     */
    @Resource
    @Lazy
    private OpinionService opinionService;

    // ------------------------------------------------------------------
    // 进度
    // ------------------------------------------------------------------

    /**
     * 计算某案件的阶段进度（判据 = 当前阶段的案件待办）。
     *
     * @return total该阶段待办数 / done已完成数 / percent百分比 / stage阶段 / finished是否已达100%
     */
    public Progress progressOf(Long caseId) {
        Progress p = new Progress();
        p.setTotal(0);
        p.setDone(0);
        p.setPercent(0);

        CaseInfo c = caseId == null ? null : caseMapper.selectById(caseId);
        if (c == null) {
            return p;
        }
        String stage = stageOf(c);
        p.setStage(stage);
        p.setStageLabel(CaseFlowTemplate.stageLabel(stage));

        // 已终结的案件进度恒为 100%，避免"办结了还显示 60%"的观感问题
        if (CaseFlowTemplate.STAGE_CLOSED.equals(stage)) {
            p.setTotal(1);
            p.setDone(1);
            p.setPercent(100);
            p.setFinished(true);
            return p;
        }

        int total = 0;
        int done = 0;
        for (CaseTodo t : listTodosOfStage(c)) {
            total++;
            if (TodoStatus.DONE.equals(t.getStatus())) {
                done++;
            }
        }
        p.setTotal(total);
        p.setDone(done);
        p.setPercent(total == 0 ? 0 : (int) Math.round(done * 100.0 / total));
        // 一条待办都没有时不算"完成"：没活可干的空阶段不该提示"待确认流转"
        p.setFinished(total > 0 && done == total);
        return p;
    }

    /** 案件当前阶段（存量 NULL 归 INITIAL） */
    public String stageOf(CaseInfo c) {
        if (c != null && "ADMINISTRATIVE".equals(c.getCaseType())
                && !CaseFlowTemplate.STAGE_CLOSED.equals(c.getFlowStage())) {
            return CaseFlowTemplate.STAGE_INITIAL;
        }
        String s = c == null ? null : c.getFlowStage();
        return StringUtils.hasText(s) ? s : CaseFlowTemplate.STAGE_INITIAL;
    }

    /**
     * 案件在<b>当前阶段</b>的待办（顶层主任务）。
     *
     * <p><b>存量归位</b>：{@code flow_stage} 为 NULL 的历史待办归入案件当前阶段，
     * 这样老案件的进度依然算得出来，不会因为新增列就"清零重来"。
     * 只取顶层主任务：子任务是主任务的细节，与待办面板列表口径一致，
     * 混进分母会让"共 N 项"与界面显示的数字对不上。
     */
    public List<CaseTodo> listTodosOfStage(CaseInfo c) {
        String stage = stageOf(c);
        List<CaseTodo> out = new ArrayList<>();
        for (CaseTodo t : todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getCaseId, c.getId())
                .isNull(CaseTodo::getParentId)
                .orderByAsc(CaseTodo::getSort)
                .orderByAsc(CaseTodo::getId))) {
            if (TodoStatus.CANCELLED.equals(t.getStatus())) {
                continue;
            }
            String ts = t.getFlowStage();
            if (!StringUtils.hasText(ts)) {
                ts = stage;   // 存量按当前阶段归位
            }
            if (stage.equals(ts)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 给一条待办盖上「当前阶段」章。
     *
     * <p>派生待办（{@code StaffTodoService}）、指派时批量建待办
     * （{@code TodoService.addAll}）都调这里——只要漏了这一步，
     * 新待办就会在流转后仍算进旧阶段，界面出现"进度怎么又满了"的怪事。
     */
    public void stampStage(CaseTodo t) {
        if (t == null || t.getCaseId() == null) {
            return;
        }
        CaseInfo c = caseMapper.selectById(t.getCaseId());
        if (c != null) {
            t.setFlowStage(stageOf(c));
        }
    }

    // ------------------------------------------------------------------
    // 流转
    // ------------------------------------------------------------------

    /**
     * 阶段流转（变更案件状态）。
     *
     * <p><b>2026-10-30 起分两类</b>：
     * <ul>
     *   <li>领导/案管（isFullAccess）→ 直接流转（原逻辑）；</li>
     *   <li>承办人 → 不直接落地，而是生成一条 {@code FLOW} 审批申请，
     *       等领导/案管审批通过后，由 {@link #reviewTransfer} 调落地逻辑。</li>
     * </ul>
     *
     * <p>动作取自 {@link CaseFlowTemplate#transitionsOf}，这里做严格校验：
     * 不能跳阶段、不能流转到模板未定义的分支。
     *
     * <p><b>事务内两件事</b>：改 flow_stage → 同步强制措施。
     * 待办<b>不删除</b>：旧阶段的待办原样留在库里（带 flow_stage 标记），
     * 只是不再计入新阶段进度——它们是办案痕迹，删掉等于毁掉已完成工作的留痕。
     *
     * @param action 流转动作：DETAIN刑拘 / BAIL取保 / RELEASE释放 / ARREST逮捕 / PUNISH处罚 / CLOSE解除
     */
    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long caseId, String action) {
        CaseInfo c = requireCase(caseId);
        String stage = stageOf(c);
        if (!AuthContext.isFullAccess()) {
            // 承办人：转成审批申请，不直接落地
            applyTransfer(c, stage, action, null);
            return;
        }
        doTransfer(c, stage, action);
    }

    /**
     * 承办人申请变更案件状态（2026-10-30）：生成 FLOW 审批申请，等领导/案管审批。
     *
     * <p>权限：本案现职承办人（主办/经办）或管理层；其余角色 403。
     * 同一案件已有待审批的流转申请时不允许重复提交。
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyTransfer(CaseInfo c, String stage, String action, String comment) {
        // 校验：承办人必须是本案现职承办人（管理层已在 transfer 里直接落地，不会走到这）
        if (!AuthContext.isFullAccess()) {
            requireActiveHandler(c);
        }
        String targetStage = resolveTarget(c.getCaseType(), stage, action);
        // 同一案件已有待审批的流转申请时不允许重复提交（避免审批人面对两条冲突申请）
        Long pending = approvalMapper.selectCount(new LambdaQueryWrapper<com.caseflow.entity.CaseApproval>()
                .eq(com.caseflow.entity.CaseApproval::getCaseId, c.getId())
                .eq(com.caseflow.entity.CaseApproval::getApproveType, "FLOW")
                .eq(com.caseflow.entity.CaseApproval::getResult, "PENDING"));
        if (pending != null && pending > 0) {
            throw new BizException("该案件已有一条待审批的状态变更申请，请等待审批后再提交");
        }
        com.caseflow.entity.CaseApproval a = new com.caseflow.entity.CaseApproval();
        a.setCaseId(c.getId());
        a.setApproveType("FLOW");
        a.setResult("PENDING");
        a.setComment(StringUtils.hasText(comment) ? comment.trim() : null);
        a.setFlowAction(action);
        a.setFromStage(stage);
        a.setApplicantId(AuthContext.userId());
        a.setApplicantName(AuthContext.userName());
        a.setAppliedAt(java.time.LocalDateTime.now());
        a.setCreatedAt(a.getAppliedAt());
        approvalMapper.insert(a);
        logService.log("CASE", "FLOW_APPLY", "CASE", c.getId(),
                "申请变更案件状态：" + CaseFlowTemplate.stageLabel(stage)
                        + " → " + CaseFlowTemplate.stageLabel(targetStage)
                        + "（动作：" + CaseFlowTemplate.actionLabel(action) + "，待审批）");
    }

    /**
     * 审批状态变更申请（领导/案管专属，2026-10-30）。
     *
     * <p>同意 → 调 {@link #doTransfer} 落地；退回 → 维持原阶段并留意见。
     * 审批前校验案件当前阶段是否仍是申请时的阶段，防止期间已被别人改过导致串档。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reviewTransfer(Long approvalId, boolean approve, String comment) {
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "审批状态变更申请需所长/副所长/队长/法制员/案管/管理员");
        }
        com.caseflow.entity.CaseApproval a = approvalMapper.selectById(approvalId);
        if (a == null || !"FLOW".equals(a.getApproveType()) || !"PENDING".equals(a.getResult())) {
            throw new BizException("该申请不是待审批的状态变更申请");
        }
        if (!approve && !StringUtils.hasText(comment)) {
            throw new BizException("退回必须填写意见");
        }
        CaseInfo c = requireCase(a.getCaseId());
        String cur = stageOf(c);
        // 防串档：申请时阶段与当前不一致，说明案件已被改过，申请失效
        if (!cur.equals(a.getFromStage())) {
            a.setResult("STALE");
            a.setApproverId(AuthContext.userId());
            a.setApproverName(AuthContext.userName());
            a.setComment("案件阶段已变为「" + CaseFlowTemplate.stageLabel(cur) + "」，本申请自动失效");
            approvalMapper.updateById(a);
            throw new BizException("案件阶段已变更（当前：" + CaseFlowTemplate.stageLabel(cur) + "），该申请已失效，请重新提交");
        }
        a.setResult(approve ? "APPROVED" : "RETURNED");
        a.setApproverId(AuthContext.userId());
        a.setApproverName(AuthContext.userName());
        a.setComment(StringUtils.hasText(comment) ? comment.trim() : "同意");
        approvalMapper.updateById(a);
        if (!approve) {
            logService.log("CASE", "FLOW_RETURN_APPLY", "CASE", a.getCaseId(),
                    "退回状态变更申请：" + CaseFlowTemplate.actionLabel(a.getFlowAction())
                            + "（意见：" + abbrev(comment) + "）");
            return;
        }
        doTransfer(c, cur, a.getFlowAction());
    }

    /** 本案是否已有待审批的状态变更申请 */
    public com.caseflow.entity.CaseApproval pendingFlowOf(Long caseId) {
        List<com.caseflow.entity.CaseApproval> list = approvalMapper.selectList(
                new LambdaQueryWrapper<com.caseflow.entity.CaseApproval>()
                        .eq(com.caseflow.entity.CaseApproval::getCaseId, caseId)
                        .eq(com.caseflow.entity.CaseApproval::getApproveType, "FLOW")
                        .eq(com.caseflow.entity.CaseApproval::getResult, "PENDING")
                        .orderByDesc(com.caseflow.entity.CaseApproval::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 待审批的状态变更申请列表（带案件名与阶段中文名，工作台审批面板直接用） */
    public List<com.caseflow.vo.FlowApprovalVO> pendingFlowApprovalVOs(Long caseId) {
        LambdaQueryWrapper<com.caseflow.entity.CaseApproval> q = new LambdaQueryWrapper<com.caseflow.entity.CaseApproval>()
                .eq(com.caseflow.entity.CaseApproval::getApproveType, "FLOW")
                .eq(com.caseflow.entity.CaseApproval::getResult, "PENDING")
                .orderByAsc(com.caseflow.entity.CaseApproval::getAppliedAt);
        if (caseId != null) {
            q.eq(com.caseflow.entity.CaseApproval::getCaseId, caseId);
        }
        List<com.caseflow.entity.CaseApproval> rows = approvalMapper.selectList(q);
        if (rows.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (com.caseflow.entity.CaseApproval a : rows) {
            ids.add(a.getCaseId());
        }
        java.util.Map<Long, CaseInfo> cases = new java.util.HashMap<>();
        for (CaseInfo c : caseMapper.selectBatchIds(ids)) {
            cases.put(c.getId(), c);
        }
        List<com.caseflow.vo.FlowApprovalVO> list = new java.util.ArrayList<>();
        for (com.caseflow.entity.CaseApproval a : rows) {
            CaseInfo c = cases.get(a.getCaseId());
            if (c == null) {
                continue; // 孤儿申请兜底跳过
            }
            com.caseflow.vo.FlowApprovalVO v = new com.caseflow.vo.FlowApprovalVO();
            v.setId(a.getId());
            v.setCaseId(a.getCaseId());
            v.setCaseNo(c.getCaseNo());
            v.setCaseName(c.getName());
            v.setFromStage(a.getFromStage());
            v.setFromStageName(CaseFlowTemplate.stageLabel(a.getFromStage()));
            v.setFlowAction(a.getFlowAction());
            v.setFlowActionName(CaseFlowTemplate.actionLabel(a.getFlowAction()));
            String target = resolveTarget(c.getCaseType(), a.getFromStage(), a.getFlowAction());
            v.setTargetStage(target);
            v.setTargetStageName(CaseFlowTemplate.stageLabel(target));
            v.setComment(a.getComment());
            v.setApplicantName(a.getApplicantName());
            v.setAppliedAt(a.getAppliedAt());
            list.add(v);
        }
        return list;
    }

    /** 解析流转动作对应的目标阶段（找不到即抛异常，与 transfer 校验一致） */
    private String resolveTarget(String caseType, String stage, String action) {        for (String[] t : CaseFlowTemplate.transitionsOf(caseType, stage)) {
            if (t[0].equals(action)) {
                return t[1];
            }
        }
        throw new BizException("当前阶段不支持「" + CaseFlowTemplate.actionLabel(action) + "」这个流转动作");
    }

    /** 流转申请里，承办人是否有权提交：仅本案现职承办人 */
    private void requireActiveHandler(CaseInfo c) {
        Long empId = AuthContext.get() == null ? null : AuthContext.get().getEmployeeId();
        if (empId == null) {
            throw new BizException(403, "申请变更案件状态需由本案承办人提交，且账号需关联员工档案");
        }
        Long cnt = assigneeMapper.selectCount(new LambdaQueryWrapper<com.caseflow.entity.CaseAssignee>()
                .eq(com.caseflow.entity.CaseAssignee::getCaseId, c.getId())
                .eq(com.caseflow.entity.CaseAssignee::getEmployeeId, empId)
                .eq(com.caseflow.entity.CaseAssignee::getStatus, "ACTIVE"));
        if (cnt == null || cnt == 0) {
            throw new BizException(403, "只有本案现职承办人可以申请变更案件状态");
        }
    }

    /**
     * 阶段流转落地（领导直接操作与审批通过共用）。
     *
     * <p><b>为什么不强制「必须做完才能转」</b>：现实中存在提前转段
     * （如证据不足变更强制措施）。系统只提示未完成数量，不硬拦；
     * 但完成的案件一定要走到领导点确认这一步，不自动跳。
     *
     * @param stage 当前阶段（调用方已算好，避免落地时阶段被并发改动）
     * @param action 流转动作：DETAIN刑拘 / BAIL取保 / RELEASE释放 / ARREST逮捕 / PUNISH处罚 / CLOSE解除
     */
    private void doTransfer(CaseInfo c, String stage, String action) {
        Long caseId = c.getId();
        String caseType = c.getCaseType();
        String targetStage = resolveTarget(caseType, stage, action);

        // ---- 切换阶段（进度随之归零：新阶段暂无待办，分母为 0）----
        c.setFlowStage(targetStage);
        // 流转与强制措施保持同步，盯办模块归类与指派组别校验才能对上：
        //   刑拘 → 登记为 DETENTION（此前只认盯办审批录入，流转建案的措施会漏登记）；
        //   取保 → BAIL；
        //   释放 → 清空措施（否则案件已终结仍会按旧措施归入刑拘/取保模块）。
        // updateById 跳过 null 字段，清空必须显式 set null。
        boolean clearMeasure = false;
        if ("DETAIN".equals(action)) {
            if (!StringUtils.hasText(c.getCaseMeasure()) || "NONE".equals(c.getCaseMeasure())) {
                c.setCaseMeasure("DETENTION");
            }
            if (c.getMeasureDate() == null) c.setMeasureDate(java.time.LocalDateTime.now());
            if (c.getDetainDeadline() == null) c.setDetainDeadline(c.getMeasureDate().plusDays(2).toLocalDate().atTime(23, 59, 59));
        } else if ("BAIL".equals(action)) {
            deadlineStateMapper.deleteById(caseId);
            c.setCaseMeasure("BAIL");
            c.setMeasureDate(java.time.LocalDateTime.now());
            c.setDetainDeadline(c.getMeasureDate().plusYears(1).toLocalDate().atTime(23, 59, 59));
        } else if ("RELEASE".equals(action)) {
            deadlineStateMapper.deleteById(caseId);
            c.setCaseMeasure(null);
            c.setMeasureDate(null);
            c.setDetainDeadline(null);
            clearMeasure = true;
        }
        caseMapper.updateById(c);
        if (clearMeasure) {
            caseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CaseInfo>()
                    .eq(CaseInfo::getId, caseId)
                    .set(CaseInfo::getCaseMeasure, null)
                    .set(CaseInfo::getMeasureDate, null)
                    .set(CaseInfo::getDetainDeadline, null));
        }

        logService.log("CASE", "FLOW_TRANSFER", "CASE", caseId,
                "流程流转：" + CaseFlowTemplate.stageLabel(stage)
                        + " → " + CaseFlowTemplate.stageLabel(targetStage)
                        + "（动作：" + CaseFlowTemplate.actionLabel(action) + "）");
    }

    /**
     * 阶段退回（2026-10-18）：领导看过之后认为本阶段没办到位，不进下一阶段，
     * 把案子打回让承办人继续补办，并可写下退回意见。
     *
     * <p><b>为什么不加「退回」状态字段</b>：待办是意见的唯一载体。退回的真实语义是
     * 「还有一件具体的事没做」。顺着现有的 意见 → 派生待办 链路走，新待办的
     * {@code flow_stage} 会被打成本阶段，阶段进度自然从 100% 掉下来，前端也就不再显示
     * 「待办已办完，确认转入」——不需要第二套状态来表达同一件事，
     * 退回记录也天然留在意见列表里可追溯。
     *
     * @param comment 退回意见（必填）：说清哪里不合格、要补办什么
     */
    @Transactional(rollbackFor = Exception.class)
    public void returnBack(Long caseId, String comment) {
        CaseInfo c = requireCase(caseId);
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "阶段退回需领导确认");
        }
        if (!StringUtils.hasText(comment)) {
            throw new BizException("请填写退回意见，说明需要补办什么");
        }
        String stage = stageOf(c);
        if (CaseFlowTemplate.STAGE_CLOSED.equals(stage)) {
            throw new BizException("案件已终结，无法退回");
        }
        // 正文带前缀：民警在「我的待办」里一眼能分辨这是被领导打回的，而不是一条普通新任务
        opinionService.add(caseId, "【阶段退回】" + comment.trim(), null, "A");
        logService.log("CASE", "FLOW_RETURN", "CASE", caseId,
                "阶段退回（" + CaseFlowTemplate.stageLabel(stage) + "）：" + abbrev(comment));
    }

    /** 日志里的意见摘要：超长截断，避免整段退回意见挤占「案件最新一步」。 */
    private String abbrev(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() <= 60 ? t : t.substring(0, 60) + "…";
    }

    /**
     * 供前端渲染的流程视图：当前阶段 + 待办进度 + 可选流转分支。
     */
    public FlowView viewOf(Long caseId) {
        FlowView v = new FlowView();
        CaseInfo c = requireCase(caseId);
        String stage = stageOf(c);
        String caseType = c.getCaseType();

        v.setStage(stage);
        v.setStageLabel(CaseFlowTemplate.stageLabel(stage));
        v.setCaseType(caseType);
        v.setProgress(progressOf(caseId));

        List<TransitionView> transitions = new ArrayList<>();
        for (String[] t : CaseFlowTemplate.transitionsOf(caseType, stage)) {
            TransitionView tv = new TransitionView();
            tv.setAction(t[0]);
            tv.setLabel(CaseFlowTemplate.actionLabel(t[0]));
            tv.setTargetStage(t[1]);
            tv.setTargetStageLabel(CaseFlowTemplate.stageLabel(t[1]));
            transitions.add(tv);
        }
        v.setTransitions(transitions);
        v.setTransferable(!transitions.isEmpty());
        return v;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private CaseInfo requireCase(Long caseId) {
        CaseInfo c = caseId == null ? null : caseMapper.selectById(caseId);
        if (c == null) {
            throw new BizException("案件不存在");
        }
        return c;
    }

    /** 待办状态常量（与 TodoService 保持一致，避免两处字面量漂移） */
    public static final class TodoStatus {
        public static final String DONE = "DONE";
        public static final String CANCELLED = "CANCELLED";

        private TodoStatus() {
        }
    }

    // ---- VO ----

    @lombok.Data
    public static class Progress {
        private int total;
        private int done;
        private int percent;
        private String stage;
        private String stageLabel;
        private boolean finished;
    }

    @lombok.Data
    public static class TransitionView {
        private String action;
        private String label;
        private String targetStage;
        private String targetStageLabel;
    }

    @lombok.Data
    public static class FlowView {
        private String stage;
        private String stageLabel;
        private String caseType;
        private Progress progress;
        private List<TransitionView> transitions;
        private boolean transferable;
    }
}
