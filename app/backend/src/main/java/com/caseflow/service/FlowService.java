package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseTodo;
import com.caseflow.exception.BizException;
import com.caseflow.flow.CaseFlowTemplate;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CaseTodoMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
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
    private LogService logService;

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
     * 阶段流转（需领导确认）。
     *
     * <p>动作取自 {@link CaseFlowTemplate#transitionsOf}，这里做严格校验：
     * 不能跳阶段、不能流转到模板未定义的分支。
     *
     * <p><b>事务内两件事</b>：改 flow_stage → 同步强制措施。
     * 待办<b>不删除</b>：旧阶段的待办原样留在库里（带 flow_stage 标记），
     * 只是不再计入新阶段进度——它们是办案痕迹，删掉等于毁掉已完成工作的留痕。
     *
     * <p><b>为什么不强制「必须做完才能转」</b>：现实中存在提前转段
     * （如证据不足变更强制措施）。系统只提示未完成数量，不硬拦；
     * 但完成的案件一定要走到领导点确认这一步，不自动跳。
     *
     * @param action 流转动作：DETAIN刑拘 / BAIL取保 / RELEASE释放 / ARREST逮捕 / PUNISH处罚 / CLOSE解除
     */
    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long caseId, String action) {
        CaseInfo c = requireCase(caseId);
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "阶段流转需领导确认");
        }
        String stage = stageOf(c);
        String caseType = c.getCaseType();

        String targetStage = null;
        for (String[] t : CaseFlowTemplate.transitionsOf(caseType, stage)) {
            if (t[0].equals(action)) {
                targetStage = t[1];
                break;
            }
        }
        if (targetStage == null) {
            throw new BizException("当前阶段不支持「" + CaseFlowTemplate.actionLabel(action) + "」这个流转动作");
        }

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
        } else if ("BAIL".equals(action)) {
            c.setCaseMeasure("BAIL");
        } else if ("RELEASE".equals(action)) {
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