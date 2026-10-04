package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CasePlan;
import com.caseflow.exception.BizException;
import com.caseflow.flow.CaseFlowTemplate;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CasePlanMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 案件流程流转引擎：阶段（Stage）→ 环节（Step）→ 任务（Task）。
 *
 * <p>流程定义全在 {@link CaseFlowTemplate}（声明式），本类只负责执行：
 * 生成阶段任务、计算进度、切换阶段。后续细化取保流程或调整分工时只改模板类即可。
 *
 * <p><b>进度为什么重置</b>：进度不落库，永远按「当前阶段的 DONE 数 / 当前阶段任务总数」
 * 实时算。切换阶段时把旧阶段任务标记取消、按模板生成新阶段任务
 * （新任务状态都是 PENDING）→ 分母换新、分子为 0，进度<strong>天然归零</strong>。
 * 不需要单独写"重置进度"的动作，也就不会出现"忘了重置导致进度虚高"的历史包袱。
 *
 * <p><b>与盯办状态机并存</b>：本引擎只改 {@code flow_stage}，
 * 不碰 {@code investigation_status}（那是盯办审批用的），两者互不干扰。
 */
@Service
public class FlowService {

    @Resource
    private CasePlanMapper planMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private LogService logService;

    // ------------------------------------------------------------------
    // 进度
    // ------------------------------------------------------------------

    /**
     * 计算某案件的阶段进度。
     *
     * @return total该阶段有效任务数 / done已完成数 / percent百分比 / stage阶段 / finished是否已达100%
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
        for (CasePlan t : listTasksOfCase(c)) {
            if (isEffective(t)) {
                total++;
                if (CasePlanStatus.DONE.equals(t.getStatus())) {
                    done++;
                }
            }
        }
        p.setTotal(total);
        p.setDone(done);
        p.setPercent(total == 0 ? 0 : (int) Math.round(done * 100.0 / total));
        p.setFinished(total > 0 && done == total);
        return p;
    }

    /** 案件当前阶段（存量 NULL 归INITIAL） */
    public String stageOf(CaseInfo c) {
        String s = c == null ? null : c.getFlowStage();
        return StringUtils.hasText(s) ? s : CaseFlowTemplate.STAGE_INITIAL;
    }

    /**
     * 案件在某阶段的任务列表。
     *
     * <p><b>存量归位</b>：stage 为 NULL 的历史计划行归入「初查 / 侦查」环节，
     * 让老案件的进度依然可见可算，不会因为升格成三层结构就"丢掉"原有完成率。
     */
    public List<CasePlan> listTasksOfCase(CaseInfo c) {
        String stage = stageOf(c);
        List<CasePlan> all = planMapper.selectList(new LambdaQueryWrapper<CasePlan>()
                .eq(CasePlan::getCaseId, c.getId())
                .orderByAsc(CasePlan::getSort)
                .orderByAsc(CasePlan::getId));
        List<CasePlan> out = new ArrayList<>();
        for (CasePlan p : all) {
            if (isEffective(p)) {
                out.add(p);
            }
        }
        // 存量（stage 为 NULL）只在案件还处于初查时计入；已流转到后续阶段则不参与进度
        for (CasePlan p : out) {
            if (!StringUtils.hasText(p.getStage())) {
                p.setStage(CaseFlowTemplate.STAGE_INITIAL);
                p.setStepKey(StringUtils.hasText(p.getStepKey())
                        ? p.getStepKey() : CaseFlowTemplate.STEP_INVESTIGATE);
            }
        }
        List<CasePlan> inStage = new ArrayList<>();
        for (CasePlan p : out) {
            if (stage.equals(p.getStage())) {
                inStage.add(p);
            }
        }
        return inStage;
    }

    /** 任务是否计入进度：已取消的（CANCELLED）不算，历史阶段的不算 */
    private boolean isEffective(CasePlan p) {
        return p != null && !CasePlanStatus.CANCELLED.equals(p.getStatus());
    }

    // ------------------------------------------------------------------
    // 任务生成
    // ------------------------------------------------------------------

    /**
     * 按模板为案件当前阶段生成标准任务（幂等：已存在的 taskKey 不重复生成）。
     *
     * <p>只在阶段首次进入时调用；民警自建任务不受影响。
     */
    @Transactional(rollbackFor = Exception.class)
    public int generateStageTasks(Long caseId, String stage) {
        CaseInfo c = requireCase(caseId);
        String caseType = c.getCaseType();

        // 先落库 flow_stage，保证后续 listTasksOfCase 能正确归位
        if (!stage.equals(c.getFlowStage())) {
            c.setFlowStage(stage);
            caseMapper.updateById(c);
        }

        List<String> steps = CaseFlowTemplate.stepsOfStage(caseType, stage);
        if (steps.isEmpty()) {
            // 该阶段（如取保）流程后续细化，先不生成任务，等模板补齐后由"补全标准任务"触发
            return 0;
        }

        // 已有 taskKey 集合，避免重复生成（taskKey 为空的是民警自建任务，不参与去重）
        List<String> existing = new ArrayList<>();
        for (CasePlan p : planMapper.selectList(new LambdaQueryWrapper<CasePlan>()
                .eq(CasePlan::getCaseId, caseId)
                .isNotNull(CasePlan::getTaskKey))) {
            existing.add(p.getTaskKey());
        }

        int sort = nextSort(caseId);
        int made = 0;
        for (String step : steps) {
            List<String> titles = CaseFlowTemplate.taskTemplateOf(step);
            for (int i = 0; i < titles.size(); i++) {
                String taskKey = step + "_" + (i + 1);
                if (existing.contains(taskKey)) {
                    continue;
                }
                CasePlan p = new CasePlan();
                p.setCaseId(caseId);
                p.setContent(titles.get(i));
                p.setStatus(CasePlanStatus.PENDING);
                p.setSort(sort++);
                p.setCreatedBy(AuthContext.userId());
                p.setStage(stage);
                p.setStepKey(step);
                p.setTaskKey(taskKey);
                p.setIsStd(1);
                planMapper.insert(p);
                made++;
            }
        }
        return made;
    }

    /** 下一个排序值：现有最大值 + 1 */
    private int nextSort(Long caseId) {
        List<CasePlan> all = planMapper.selectList(new LambdaQueryWrapper<CasePlan>()
                .select(CasePlan::getSort)
                .eq(CasePlan::getCaseId, caseId));
        int max = 0;
        for (CasePlan p : all) {
            if (p.getSort() != null && p.getSort() > max) {
                max = p.getSort();
            }
        }
        return max + 1;
    }

    // ------------------------------------------------------------------
    // 流转
    // ------------------------------------------------------------------

    /**
     * 阶段流转（需管理层确认）。
     *
     * <p>动作取自 {@link CaseFlowTemplate#transitionsOf}，这里做严格校验：
     * 不能跳阶段、不能流转到模板未定义的分支。
     *
     * <p><b>事务内三件事</b>：旧阶段任务置CANCELLED（从进度里移除）→ 改 flow_stage
     * → 生成新阶段任务（状态PENDING，进度归零）。任一步失败整体回滚，不会留下半截状态。
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
        String targetStep = null;
        for (String[] t : CaseFlowTemplate.transitionsOf(caseType, stage)) {
            if (t[0].equals(action)) {
                targetStage = t[1];
                targetStep = t[2];
                break;
            }
        }
        if (targetStage == null) {
            throw new BizException("当前阶段不支持「" + CaseFlowTemplate.actionLabel(action) + "」这个流转动作");
        }

        // ---- 1. 旧阶段任务退出进度口径 ----
        List<CasePlan> cur = listTasksOfCase(c);
        for (CasePlan p : cur) {
            if (!CasePlanStatus.CANCELLED.equals(p.getStatus())) {
                p.setStatus(CasePlanStatus.CANCELLED);
                planMapper.updateById(p);
            }
        }

        // ---- 2. 切换阶段（进度随即归零）----
        c.setFlowStage(targetStage);
        // 流转与强制措施保持同步，盯办模块归类与指派组别校验才能对上：
        //   刑拘 → 登记为 DETENTION（此前只认盯办审批录入，流转建案的措施会漏登记）；
        //   取保 → BAIL；
        //   释放 → 清空措施（否则案件已终结仍会按旧措施归入刑拘/取保模块）。
        // updateById 跳过 null 字段，清空必须显式 set null。
        boolean clearMeasure = false;
        if (CaseFlowTemplate.STEP_ASSIGN_CLEAR.equals(targetStep)) {
            if (c.getCaseMeasure() == null || c.getCaseMeasure().trim().isEmpty()
                    || "NONE".equals(c.getCaseMeasure())) {
                c.setCaseMeasure("DETENTION");
            }
        } else if (CaseFlowTemplate.STEP_EXECUTE_BAIL.equals(targetStep)) {
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

        // ---- 3. 生成新阶段任务（全新 PENDING，进度从 0% 起算）----
        generateStageTasks(caseId, targetStage);

        logService.log("CASE", "FLOW_TRANSFER", "CASE", caseId,
                "流程流转：" + CaseFlowTemplate.stageLabel(stage)
                        + " → " + CaseFlowTemplate.stageLabel(targetStage)
                        + "（动作：" + CaseFlowTemplate.actionLabel(action) + "）");
    }

    /**
     * 供前端渲染的流程视图：当前阶段 + 环节顺序 + 每环节任务 + 进度 + 可选流转分支。
     */
    public FlowView viewOf(Long caseId) {
        FlowView v = new FlowView();
        CaseInfo c = requireCase(caseId);
        String stage = stageOf(c);
        String caseType = c.getCaseType();

        v.setStage(stage);
        v.setStageLabel(CaseFlowTemplate.stageLabel(stage));
        v.setCaseType(caseType);

        Progress p = progressOf(caseId);
        v.setProgress(p);

        List<CasePlan> tasks = listTasksOfCase(c);
        List<String> steps = CaseFlowTemplate.stepsOfStage(caseType, stage);

        // 按模板环节顺序组装；模板没覆盖的环节（如取保后续）走"自由录入"，
        // 民警自建任务归到一个「其他」分组，保证不丢任务
        List<StepView> stepViews = new ArrayList<>();
        List<CasePlan> matched = new ArrayList<>();
        for (String sk : steps) {
            StepView sv = new StepView();
            sv.setKey(sk);
            sv.setLabel(CaseFlowTemplate.stepLabel(sk));
            List<CasePlan> inStep = new ArrayList<>();
            for (CasePlan t : tasks) {
                if (sk.equals(t.getStepKey())) {
                    inStep.add(t);
                }
            }
            matched.addAll(inStep);
            int done = 0;
            for (CasePlan t : inStep) {
                if (CasePlanStatus.DONE.equals(t.getStatus())) {
                    done++;
                }
            }
            sv.setTasks(inStep);
            sv.setTotal(inStep.size());
            sv.setDone(done);
            stepViews.add(sv);
        }
        // 自建且 step_key 不在模板里的
        List<CasePlan> others = new ArrayList<>();
        for (CasePlan t : tasks) {
            if (!matched.contains(t)) {
                others.add(t);
            }
        }
        if (!others.isEmpty()) {
            StepView sv = new StepView();
            sv.setKey("CUSTOM");
            sv.setLabel("其他事项");
            int done = 0;
            for (CasePlan t : others) {
                if (CasePlanStatus.DONE.equals(t.getStatus())) {
                    done++;
                }
            }
            sv.setTasks(others);
            sv.setTotal(others.size());
            sv.setDone(done);
            stepViews.add(sv);
        }
        v.setSteps(stepViews);

        // 可选流转分支：仅未终结的阶段有
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

    /** 任务状态常量（与 PlanService 保持一致，避免两处字面量漂移） */
    public static final class CasePlanStatus {
        public static final String PENDING = "PENDING";
        public static final String DONE = "DONE";
        public static final String CANCELLED = "CANCELLED";

        private CasePlanStatus() {
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
    public static class StepView {
        private String key;
        private String label;
        private List<CasePlan> tasks;
        private int total;
        private int done;
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
        private List<StepView> steps;
        private List<TransitionView> transitions;
        private boolean transferable;
    }
}
