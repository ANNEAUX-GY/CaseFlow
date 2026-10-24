package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseApproval;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseDeadlineState;
import com.caseflow.entity.CasePlan;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseApprovalMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CaseDeadlineStateMapper;
import com.caseflow.mapper.CasePlanMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.DictHolder;
import com.caseflow.support.LogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 案件盯办：侦查进度状态机 + 强制措施登记 + 审批留痕。
 *
 * <p>状态机：待初查 --开始侦查--> 侦查中 --提请审批--> 待审批 --同意--> 侦查终结；
 * 待审批 --退回补侦（须填意见）--> 侦查中。
 * 全部流转带前后快照，可撤回；审批/措施/同意/退回 = 管理层专属。
 */
@Service
public class ApprovalService {

    /**
     * 可登记的强制措施：与建案表单共用 DictHolder.CASE_MEASURE 的取值。
     * 2026-10-09 补齐拘传 / 逮捕（盯办里登记逮捕的案件同样落在「刑拘在办」子模块）。
     */
    private static final List<String> MEASURES = Arrays.asList("NONE", "SUMMONS", "DETENTION", "ARREST",
            "BAIL", "RESIDENCE");
    private static final List<String> ACTIONS = Arrays.asList("START", "SUBMIT", "APPROVE", "REJECT");

    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseDeadlineStateMapper deadlineStateMapper;
    @Resource
    private CasePlanMapper planMapper;
    @Resource
    private CaseApprovalMapper approvalMapper;
    @Resource
    private CaseSnapshotService snapshotService;
    @Resource
    private LogService logService;

    public List<CaseApproval> approvalsOf(Long caseId) {
        return approvalMapper.selectList(new LambdaQueryWrapper<CaseApproval>()
                .eq(CaseApproval::getCaseId, caseId)
                .orderByDesc(CaseApproval::getId));
    }

    /**
     * 侦查进度流转。
     *
     * @param action START 开始侦查 / SUBMIT 提请审批 / APPROVE 同意侦查终结 / REJECT 退回补侦
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseInfo transition(Long caseId, String action, String comment) {
        if (!ACTIONS.contains(action)) {
            throw new BizException("不支持的流转动作");
        }
        CaseInfo c = requireCase(caseId);
        String before = snapshotService.capture(caseId);
        String cur = c.getInvestigationStatus();

        switch (action) {
            case "START":
                // 待初查 -> 侦查中：**仅本案现职承办人（办案人）**。
                // 开始侦查 = 办案人表示"我开始干了"，是工作者的自我认领动作，
                // 不由领导代点（管理层的职责是审批与提意见，见权限划分文档）。
                requireState(cur, "PENDING_INITIAL");
                requireActiveHandler(c);
                if (!hasOwner(caseId)) {
                    throw new BizException("请先指派主办人再开始侦查");
                }
                c.setInvestigationStatus("INVESTIGATING");
                break;
            case "SUBMIT":
                // 刑拘：预审或法制任一方审核通过并接收后才可申请。
                if (!"DETENTION".equals(c.getCaseMeasure())) {
                    requireState(cur, "INVESTIGATING");
                } else if (cur != null && !"PENDING_INITIAL".equals(cur) && !"INVESTIGATING".equals(cur)) {
                    throw new BizException("当前侦查进度不能再次提交审核");
                }
                checkHandlerOrManager(c);
                if ("DETENTION".equals(c.getCaseMeasure())) {
                    CaseDeadlineState state = deadlineStateMapper.selectById(caseId);
                    if (!CaseDeadlineService.acceptedByReviewer(state)) {
                        throw new BizException("刑拘变更为7天或30天案后，预审或法制至少一方审核通过并接收，才能申请已提交审核");
                    }
                    state.setSubmittedAt(LocalDateTime.now());
                    state.setUpdatedAt(state.getSubmittedAt());
                    deadlineStateMapper.updateById(state);
                } else {
                    Long doneCnt = planMapper.selectCount(new LambdaQueryWrapper<CasePlan>()
                            .eq(CasePlan::getCaseId, caseId).eq(CasePlan::getStatus, "DONE"));
                    if (doneCnt == null || doneCnt == 0) {
                        throw new BizException("至少完成 1 条侦查计划后才能提请审批");
                    }
                }
                c.setInvestigationStatus("PENDING_APPROVAL");
                break;
            case "APPROVE":
                // 待审批 -> 侦查终结：管理层
                requireState(cur, "PENDING_APPROVAL");
                requireManager("审批侦查终结");
                c.setInvestigationStatus("INVESTIGATION_DONE");
                insertApproval(caseId, "INVESTIGATION_DONE", "APPROVED", comment);
                break;
            case "REJECT":
                // 待审批 -> 侦查中（退回补侦）：管理层，意见必填
                requireState(cur, "PENDING_APPROVAL");
                requireManager("退回补侦");
                if (!StringUtils.hasText(comment)) {
                    throw new BizException("退回补侦必须填写审批意见");
                }
                c.setInvestigationStatus("INVESTIGATING");
                insertApproval(caseId, "INVESTIGATION_DONE", "RETURNED", comment);
                break;
            default:
                throw new BizException("不支持的流转动作");
        }

        c.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(c);
        logService.log("CASE", "INVESTIGATION", "CASE", caseId,
                "侦查进度：" + nameOf(cur) + " → " + nameOf(c.getInvestigationStatus())
                        + (StringUtils.hasText(comment) ? "（意见：" + abbrev(comment) + "）" : ""),
                before, snapshotService.capture(caseId));
        return c;
    }

    /**
     * 强制措施登记/变更（2026-10-24 改为审批制）。
     *
     * <p>规则（用户口径）：从当前措施转入下一个措施必须经领导批准。
     * <ul>
     *   <li>领导（所长/副所长/法制员/管理员）操作 → 直接生效，写 APPROVED 留痕；</li>
     *   <li>本案承办人（主办/经办）操作 → 只生成 PENDING 申请，<b>案件措施不变</b>，
     *       等领导在「待审批」里同意后才落地；退回则维持原措施。</li>
     * </ul>
     *
     * <p>首次登记（当前无措施）同样适用：承办人建完案要登记拘留，也走审批。
     */
    @Transactional(rollbackFor = Exception.class)
    public com.caseflow.vo.MeasureResultVO measure(Long caseId, String measure, LocalDateTime measureDate,
                                                  LocalDateTime detainDeadline, String comment) {
        if (!MEASURES.contains(measure)) {
            throw new BizException("强制措施类型不合法");
        }
        CaseInfo c = requireCase(caseId);
        // 承办人本人 + 管理层都能发起，其余角色 403
        checkHandlerOrManager(c);
        String measureName = "NONE".equals(measure) ? "无（结束当前措施）"
                : DictHolder.name("CASE_MEASURE", measure);

        if (AuthContext.isFullAccess()) {
            CaseInfo done = applyMeasure(c, measure, measureDate, detainDeadline, null, comment);
            return com.caseflow.vo.MeasureResultVO.applied(done, measure, measureName);
        }
        // 承办人：同一案件已有待审批申请时不允许再提交（避免审批人面对两条冲突申请）
        Long pending = approvalMapper.selectCount(new LambdaQueryWrapper<CaseApproval>()
                .eq(CaseApproval::getCaseId, caseId)
                .eq(CaseApproval::getApproveType, "MEASURE")
                .eq(CaseApproval::getResult, "PENDING"));
        if (pending != null && pending > 0) {
            throw new BizException("该案件已有一条待审批的措施变更申请，请等待领导审批后再提交");
        }
        CaseApproval a = new CaseApproval();
        a.setCaseId(caseId);
        a.setApproveType("MEASURE");
        a.setResult("PENDING");
        a.setComment(StringUtils.hasText(comment) ? comment.trim() : null);
        a.setFromMeasure(c.getCaseMeasure());
        a.setApplyMeasure(measure);
        a.setApplyDate(measureDate);
        a.setApplyDeadline(detainDeadline);
        a.setApplicantId(AuthContext.userId());
        a.setApplicantName(AuthContext.userName());
        a.setAppliedAt(LocalDateTime.now());
        a.setCreatedAt(LocalDateTime.now());
        approvalMapper.insert(a);
        logService.log("CASE", "MEASURE_APPLY", "CASE", caseId,
                "申请变更强制措施：" + measureName + "（待领导审批）");
        return com.caseflow.vo.MeasureResultVO.pending(a.getId(), c, measure, measureName);
    }

    /**
     * 真正的措施落地（原 measure 的主体，抽出来给「领导直接登记」和「审批通过」共用）。
     *
     * @param approvalComment 审批意见，直接登记时为 null
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseInfo applyMeasure(CaseInfo c, String measure, LocalDateTime measureDate,
                                 LocalDateTime detainDeadline, String approvalComment, String applyComment) {
        Long caseId = c.getId();
        String before = snapshotService.capture(caseId);
        boolean sameMeasure = measure.equals(c.getCaseMeasure());
        LocalDateTime base = sameMeasure && measureDate != null && c.getMeasureDate() != null
                && measureDate.toLocalDate().equals(c.getMeasureDate().toLocalDate()) ? c.getMeasureDate()
                : measureDate != null ? measureDate
                : sameMeasure && c.getMeasureDate() != null ? c.getMeasureDate() : LocalDateTime.now();
        boolean newEpisode = !sameMeasure || !base.equals(c.getMeasureDate());
        boolean custodyExecuted = "CRIMINAL".equals(c.getCaseType())
                && ("DETENTION".equals(measure) || "ARREST".equals(measure));
        if (custodyExecuted && newEpisode
                && !com.caseflow.flow.CaseFlowTemplate.STAGE_DETAIN.equals(c.getFlowStage())) {
            com.caseflow.entity.CaseDeadlineState deadlineState = deadlineStateMapper.selectById(caseId);
            if (c.getAcceptTime() == null || deadlineState == null || deadlineState.getFilingAt() == null) {
                throw new BizException("请先登记刑事案件受案时间和立案时间，再登记拘捕执行");
            }
            if (measureDate == null) throw new BizException("执行拘捕须填写实际措施日期");
            if (base.toLocalDate().isBefore(deadlineState.getFilingAt().toLocalDate()))
                throw new BizException("拘捕执行时间不能早于立案时间");
            c.setFlowStage(com.caseflow.flow.CaseFlowTemplate.STAGE_DETAIN);
        }
        // 换措施或重新确定措施起算日时，只清理本期措施记录。
        // 受案、立案及行政延期属于案件本身，不能随措施变更被删除。
        if (newEpisode) {
            deadlineStateMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.caseflow.entity.CaseDeadlineState>()
                    .eq(com.caseflow.entity.CaseDeadlineState::getCaseId, caseId)
                    .set(com.caseflow.entity.CaseDeadlineState::getDetentionTerm, null)
                    .set(com.caseflow.entity.CaseDeadlineState::getPausedAt, null)
                    .set(com.caseflow.entity.CaseDeadlineState::getPausedDeadline, null)
                    .set(com.caseflow.entity.CaseDeadlineState::getPausedSeconds, 0L)
                    .set(com.caseflow.entity.CaseDeadlineState::getPretrialSentAt, null)
                    .set(com.caseflow.entity.CaseDeadlineState::getLegalSentAt, null)
                    .set(com.caseflow.entity.CaseDeadlineState::getPretrialReceivedAt, null)
                    .set(com.caseflow.entity.CaseDeadlineState::getLegalReceivedAt, null)
                    .set(com.caseflow.entity.CaseDeadlineState::getSubmittedAt, null)
                    .set(com.caseflow.entity.CaseDeadlineState::getLastSummonsAt, null));
        }

        c.setCaseMeasure("NONE".equals(measure) ? null : measure);
        c.setMeasureDate("NONE".equals(measure) ? null : base);
        if ("NONE".equals(measure)) {
            c.setDetainDeadline(null);
        } else if (detainDeadline != null) {
            c.setDetainDeadline(detainDeadline);
        } else if (!newEpisode) {
            // 只修改备注等信息时保留已有 7/30 天期限及暂停状态。
        } else {
            c.setDetainDeadline(defaultDeadline(measure, base));
        }
        c.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(c);
        // 「无措施」要把旧措施清空：updateById 会跳过 null 字段，
        // 三个时间/措施字段必须显式 set null，否则登记 NONE 后库里仍是旧措施
        if ("NONE".equals(measure)) {
            caseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CaseInfo>()
                    .eq(CaseInfo::getId, caseId)
                    .set(CaseInfo::getCaseMeasure, null)
                    .set(CaseInfo::getMeasureDate, null)
                    .set(CaseInfo::getDetainDeadline, null));
        }

        insertApproval(caseId, "MEASURE", "APPROVED",
                "登记强制措施：" + DictHolder.name("CASE_MEASURE", measure)
                        + (StringUtils.hasText(applyComment) ? "；" + applyComment : "")
                        + (StringUtils.hasText(approvalComment) ? "；审批意见：" + approvalComment : ""));

        logService.log("CASE", "MEASURE", "CASE", caseId,
                "强制措施：" + (DictHolder.name("CASE_MEASURE", c.getCaseMeasure()) == null ? "无"
                        : DictHolder.name("CASE_MEASURE", c.getCaseMeasure()))
                        + (c.getDetainDeadline() != null ? "，期限至 " + c.getDetainDeadline() : ""),
                before, snapshotService.capture(caseId));
        return c;
    }

    /**
     * 审批措施变更申请（领导专属）：同意则落地措施，退回则维持原措施并留意见。
     *
     * @return 审批后的案件（退回时是未变更的案件）
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseInfo reviewMeasure(Long approvalId, boolean approve, String comment) {
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "审批强制措施变更需所长/副所长/法制员/管理员");
        }
        CaseApproval a = approvalMapper.selectById(approvalId);
        if (a == null) {
            throw new BizException("审批记录不存在");
        }
        if (!"MEASURE".equals(a.getApproveType()) || !"PENDING".equals(a.getResult())) {
            throw new BizException("该申请不是待审批的强制措施变更");
        }
        CaseInfo c = requireCase(a.getCaseId());
        if (!approve && !StringUtils.hasText(comment)) {
            throw new BizException("退回必须填写意见");
        }
        // 期间案件措施可能已被改过：申请基于旧状态，直接落地会覆盖别人的操作
        String cur = c.getCaseMeasure();
        String from = a.getFromMeasure();
        boolean sameFrom = cur == null ? from == null : cur.equals(from);
        if (!sameFrom) {
            a.setResult("STALE");
            a.setComment("案件措施已变为「" + DictHolder.name("CASE_MEASURE", cur) + "」，本申请自动失效");
            a.setApproverId(AuthContext.userId());
            a.setApproverName(AuthContext.userName());
            approvalMapper.updateById(a);
            throw new BizException("案件的强制措施已变更（当前：" + DictHolder.name("CASE_MEASURE", cur)
                    + "），该申请已失效，请重新提交");
        }
        a.setResult(approve ? "APPROVED" : "RETURNED");
        a.setApproverId(AuthContext.userId());
        a.setApproverName(AuthContext.userName());
        a.setComment(StringUtils.hasText(comment) ? comment.trim() : "同意");
        a.setCreatedAt(LocalDateTime.now());
        approvalMapper.updateById(a);
        if (!approve) {
            logService.log("CASE", "MEASURE_RETURN", "CASE", a.getCaseId(),
                    "退回强制措施变更申请：" + DictHolder.name("CASE_MEASURE", a.getApplyMeasure())
                            + "（意见：" + abbrev(comment) + "）");
            return c;
        }
        return applyMeasure(c, a.getApplyMeasure(), a.getApplyDate(), a.getApplyDeadline(),
                a.getComment(), null);
    }

    /** 全局限的待审批措施变更（工作台用）；caseId 不为空时只看该案件 */
    public List<CaseApproval> pendingMeasureApprovals(Long caseId) {
        LambdaQueryWrapper<CaseApproval> q = new LambdaQueryWrapper<CaseApproval>()
                .eq(CaseApproval::getApproveType, "MEASURE")
                .eq(CaseApproval::getResult, "PENDING")
                .orderByAsc(CaseApproval::getAppliedAt);
        if (caseId != null) {
            q.eq(CaseApproval::getCaseId, caseId);
        }
        return approvalMapper.selectList(q);
    }

    /** 待审批列表（带案件名与措施中文名，工作台审批面板直接用这个） */
    public List<com.caseflow.vo.MeasureApprovalVO> pendingMeasureApprovalVOs(Long caseId) {
        List<CaseApproval> rows = pendingMeasureApprovals(caseId);
        if (rows.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (CaseApproval a : rows) {
            ids.add(a.getCaseId());
        }
        java.util.Map<Long, CaseInfo> cases = new java.util.HashMap<>();
        for (CaseInfo c : caseMapper.selectBatchIds(ids)) {
            cases.put(c.getId(), c);
        }
        List<com.caseflow.vo.MeasureApprovalVO> list = new java.util.ArrayList<>();
        for (CaseApproval a : rows) {
            CaseInfo c = cases.get(a.getCaseId());
            // 案件已被删掉的孤儿申请不列出来：审批人对着一行"（案件已删除）"既点不动也批不了。
            // 正常路径由 CaseService.delete 顺手清掉 PENDING，这里只是兜底。
            if (c == null) {
                continue;
            }
            com.caseflow.vo.MeasureApprovalVO v = new com.caseflow.vo.MeasureApprovalVO();
            v.setId(a.getId());
            v.setCaseId(a.getCaseId());
            v.setCaseNo(c.getCaseNo());
            v.setCaseName(c.getName());
            v.setFromMeasure(a.getFromMeasure());
            v.setFromMeasureName(measureNameOf(a.getFromMeasure()));
            v.setApplyMeasure(a.getApplyMeasure());
            v.setApplyMeasureName(measureNameOf(a.getApplyMeasure()));
            v.setApplyDate(a.getApplyDate());
            v.setApplyDeadline(a.getApplyDeadline());
            v.setComment(a.getComment());
            v.setApplicantName(a.getApplicantName());
            v.setAppliedAt(a.getAppliedAt());
            list.add(v);
        }
        return list;
    }

    /** 措施中文名：空/NONE 一律算「无措施」 */
    private String measureNameOf(String code) {
        if (!StringUtils.hasText(code) || "NONE".equalsIgnoreCase(code)) {
            return "无措施";
        }
        String n = DictHolder.name("CASE_MEASURE", code);
        return n == null ? code : n;
    }

    /**
     * 清掉指向已删案件的待审批申请（启动时跑，幂等）。
     *
     * <p>在「删案件顺手清 PENDING」上线之前删掉的案件，申请还留在库里，
     * 工作台会显示一条"待审批"却点不动。已批/已退回的是留痕，不动。
     */
    public int purgeOrphanPending() {
        List<CaseApproval> rows = approvalMapper.selectList(new LambdaQueryWrapper<CaseApproval>()
                .eq(CaseApproval::getResult, "PENDING"));
        if (rows.isEmpty()) {
            return 0;
        }
        java.util.Set<Long> caseIds = new java.util.HashSet<>();
        for (CaseApproval a : rows) {
            caseIds.add(a.getCaseId());
        }
        java.util.Set<Long> alive = new java.util.HashSet<>();
        for (CaseInfo c : caseMapper.selectBatchIds(caseIds)) {
            alive.add(c.getId());
        }
        int n = 0;
        for (CaseApproval a : rows) {
            if (!alive.contains(a.getCaseId())) {
                approvalMapper.deleteById(a.getId());
                n++;
            }
        }
        return n;
    }

    /** 某案件是否已有待审批的措施变更（前端展示"审批中"角标） */
    public CaseApproval pendingOf(Long caseId) {
        List<CaseApproval> list = pendingMeasureApprovals(caseId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 措施期限推算默认值：刑拘先按 3 天登记，待变更羁押期限后选 7/30 天；取保 +12 个月，监居 +6 个月，
     * 逮捕 +2 个月（侦查羁押期限）；拘传只有 12 小时、不成"期限"，故不推算。
     */
    private LocalDateTime defaultDeadline(String measure, LocalDateTime base) {
        switch (measure) {
            case "DETENTION":
                return base.plusDays(2).toLocalDate().atTime(23, 59, 59);
            case "ARREST":
                return base.plusMonths(2);
            case "BAIL":
                return base.plusMonths(12).toLocalDate().atTime(23, 59, 59);
            case "RESIDENCE":
                return base.plusMonths(6).toLocalDate().atTime(23, 59, 59);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------

    private CaseInfo requireCase(Long caseId) {
        if (caseId == null) {
            throw new BizException("缺少案件 ID");
        }
        CaseInfo c = caseMapper.selectById(caseId);
        if (c == null) {
            throw new BizException("案件不存在");
        }
        return c;
    }

    private void requireState(String current, String expected) {
        boolean ok = expected == null
                ? current == null
                : expected.equals(current) || (current == null && "PENDING_INITIAL".equals(expected));
        if (!ok) {
            throw new BizException("当前侦查进度为「" + nameOf(current) + "」，不能执行该操作");
        }
    }

    private void requireManager(String what) {
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "当前角色无权执行：" + what + "，需所长/副所长/法制员/管理员");
        }
    }

    /** 经办人（本案现职承办人）或管理层 */
    private void checkHandlerOrManager(CaseInfo c) {
        if (AuthContext.isFullAccess()) {
            return;
        }
        requireActiveHandler(c);
    }

    /** 仅本案现职承办人（办案人）——开始侦查、提请审批等工作动作专用 */
    private void requireActiveHandler(CaseInfo c) {
        Long empId = AuthContext.get() == null ? null : AuthContext.get().getEmployeeId();
        if (empId == null) {
            throw new BizException(403, "该操作由办案人本人执行，且账号需关联员工档案");
        }
        Long cnt = assigneeMapper.selectCount(new LambdaQueryWrapper<com.caseflow.entity.CaseAssignee>()
                .eq(com.caseflow.entity.CaseAssignee::getCaseId, c.getId())
                .eq(com.caseflow.entity.CaseAssignee::getEmployeeId, empId)
                .eq(com.caseflow.entity.CaseAssignee::getStatus, "ACTIVE"));
        if (cnt == null || cnt == 0) {
            throw new BizException(403, "该操作由办案人本人执行（管理层不代点：开始侦查表示办案人开始工作）");
        }
    }

    @Resource
    private com.caseflow.mapper.CaseAssigneeMapper assigneeMapper;

    private boolean hasOwner(Long caseId) {
        Long cnt = assigneeMapper.selectCount(new LambdaQueryWrapper<com.caseflow.entity.CaseAssignee>()
                .eq(com.caseflow.entity.CaseAssignee::getCaseId, caseId)
                .eq(com.caseflow.entity.CaseAssignee::getAssignRole, "OWNER")
                .eq(com.caseflow.entity.CaseAssignee::getStatus, "ACTIVE"));
        return cnt != null && cnt > 0;
    }

    private void insertApproval(Long caseId, String type, String result, String comment) {
        CaseApproval a = new CaseApproval();
        a.setCaseId(caseId);
        a.setApproveType(type);
        a.setResult(result);
        a.setComment(comment);
        a.setApproverId(AuthContext.userId());
        a.setApproverName(AuthContext.userName());
        a.setCreatedAt(LocalDateTime.now());
        approvalMapper.insert(a);
    }

    private String nameOf(String status) {
        String n = DictHolder.name("INVESTIGATION_STATUS", status);
        return n == null ? "待初查" : n;
    }

    private String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }
}
