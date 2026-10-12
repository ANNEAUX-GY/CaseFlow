package com.caseflow.flow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 案件阶段模板：案件办理的大段落（初查 / 刑拘在办 / 取保及监居 / 已终结）。
 *
 * <p><b>设计意图</b>：把「刑事/行政各自的办理阶段」从代码逻辑里抽成声明式数据，
 * 后续增加阶段或调整推进路径时，<b>只改本类即可</b>，
 * 不需要动流转引擎（{@code FlowService}）与前端。
 *
 * <p><b>阶段完成判据（2026-10-12 用户定的口径）</b>：
 * 一个阶段的待办全部办完，才由领导确认转入下一阶段。判据落在
 * <b>案件待办 {@code case_todo}</b> 上——待办表加 {@code flow_stage} 列，
 * 领导提意见派生的待办自动记下当时所处的阶段；流转后新阶段的待办从 0 起算，
 * 进度天然归零，不需要任何"重置"动作。
 *
 * <p><b>为什么不再维护「环节→标准任务」三层模板</b>（原版本）：
 * 那套 {@code STEP_*} + {@code STEP_TASKS} 会在建案时自动铺 13 项标准任务，
 * 结果与民警日常用的「案件待办」是<b>两套并行任务清单</b>——
 * 同一个案子要在一处勾「接收材料/立案/侦查」，又要在另一处勾领导交代的事，
 * 用户明确要求删掉（「把办案流程全部删去」）。现在阶段任务 = 案件待办，
 * 只有一份清单，也就不存在两处数字对不上的问题。
 *
 * <p><b>与既有状态机的关系</b>：本流程<b>不替换</b> {@code investigation_status}
 * （PENDING_INITIAL/INVESTIGATING/PENDING_APPROVAL/INVESTIGATION_DONE），
 * 那是盯办模块 START→SUBMIT→APPROVE 的审批状态机，两者并存互不干扰：
 * 前者管「走到哪个阶段、进度多少」，后者管「侦查终结要不要领导审批」。
 */
public final class CaseFlowTemplate {

    private CaseFlowTemplate() {
    }

    // ------------------------------------------------------------------
    // 阶段
    // ------------------------------------------------------------------

    /** 初查：接收材料 → 立案 → 侦查 → 判断是否符合刑拘条件 → 刑拘在办 */
    public static final String STAGE_INITIAL = "INITIAL";
    /** 刑拘在办：清案民警办理 */
    public static final String STAGE_DETAIN = "DETAIN";
    /** 取保及监居：取保候审 / 监视居住执行 */
    public static final String STAGE_BAIL = "BAIL";
    /** 已终结：案件办理完毕 */
    public static final String STAGE_CLOSED = "CLOSED";

    /** 阶段中文名（NULL/未知一律按初查，与 FlowService.stageOf 的归位口径一致） */
    public static String stageLabel(String stage) {
        if (STAGE_DETAIN.equals(stage)) {
            return "刑拘在办";
        }
        if (STAGE_BAIL.equals(stage)) {
            return "取保及监居";
        }
        if (STAGE_CLOSED.equals(stage)) {
            return "已终结";
        }
        return "初查";
    }

    // ------------------------------------------------------------------
    // 流转出口
    // ------------------------------------------------------------------

    /**
     * 流转出口：某阶段的待办办完后可去往的分支。
     *
     * <p><b>刑事主干只有两条</b>（用户明确要求）：初查 → 刑拘在办 → 取保及监居。
     * 初查不再提供「直接取保」「直接释放」——没经过刑拘在办就取保/释放，
     * 强制措施与承办组别会跟实际脱节。
     * 通往「已终结」的出口（逮捕 / 释放 / 解除收案）暂时保留原样，
     * 什么时候细化由领导另行拍板，不在本次改动范围内。
     *
     * @return key=流转动作，value={目标阶段}
     */
    public static List<String[]> transitionsOf(String caseType, String stage) {
        List<String[]> out = new ArrayList<>();
        if (STAGE_INITIAL.equals(stage)) {
            if (ADMIN_TYPES.contains(caseType)) {
                // 行政：批准行政处罚后办结
                out.add(new String[]{"PUNISH", STAGE_CLOSED});
            } else {
                // 刑事初查 → 刑拘在办（唯一出口）
                out.add(new String[]{"DETAIN", STAGE_DETAIN});
            }
        } else if (STAGE_DETAIN.equals(stage)) {
            // 刑拘在办 → 取保及监居（主干）；逮捕 / 释放 通向终结，保留待议
            out.add(new String[]{"BAIL", STAGE_BAIL});
            out.add(new String[]{"ARREST", STAGE_CLOSED});
            out.add(new String[]{"RELEASE", STAGE_CLOSED});
        } else if (STAGE_BAIL.equals(stage)) {
            out.add(new String[]{"CLOSE", STAGE_CLOSED});
        }
        return out;
    }

    /** 流转动作中文名 */
    public static String actionLabel(String action) {
        if (action == null) {
            return "";
        }
        switch (action) {
            case "DETAIN": return "刑拘";
            case "BAIL": return "取保候审";
            case "RELEASE": return "释放";
            case "ARREST": return "逮捕";
            case "PUNISH": return "批准行政处罚";
            case "CLOSE": return "解除收案";
            default: return action;
        }
    }

    /** 行政案件类型：走行政处罚流程，不适用刑事的刑拘/取保主干 */
    private static final List<String> ADMIN_TYPES = Arrays.asList("ADMINISTRATIVE");
}