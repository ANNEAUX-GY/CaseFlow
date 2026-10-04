package com.caseflow.flow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 案件流程模板：Stage（阶段） → Step（环节） → Task（任务）三层定义。
 *
 * <p><b>设计意图</b>：把「刑事/行政各自的办理流程」从代码逻辑里抽成声明式数据，
 * 这样后续补充取保流程、增加环节或调整工作分工时，<b>只改本类即可</b>，
 * 不需要动流转引擎（{@code FlowService}）与前端。
 *
 * <p><b>三层职责</b>：
 * <ul>
 *   <li><b>Stage 阶段</b>：案件办理的大段落（初查 / 刑拘在办 / 取保及监居 / 已终结），
 *       落在 {@code case_info.flow_stage}；阶段切换即进度重置。</li>
 *   <li><b>Step 环节</b>：阶段内的固定办理环节（接收材料 / 立案 / 侦查 …），
 *       作为任务的归类标签落在 {@code case_plan.step_key}。</li>
 *   <li><b>Task 任务</b>：可勾选的最小工作项，一行 {@code case_plan}。
 *       标准任务带 {@code taskKey}，民警自建任务 {@code taskKey} 为空。</li>
 * </ul>
 *
 * <p><b>与既有状态机的关系</b>：本流程<b>不替换</b> {@code investigation_status}
 * （PENDING_INITIAL/INVESTIGATING/PENDING_APPROVAL/INVESTIGATION_DONE），
 * 那是盯办模块 START→SUBMIT→APPROVE 的审批状态机，两者并存互不干扰：
 * 前者管「走到哪个阶段、进度多少」，后者管「侦查终结要不要领导审批」。
 *
 * <p><b>进度口径</b>：不落库，实时计算 —— 阶段进度 = 该阶段 DONE 任务数 / 该阶段任务总数。
 * 切换阶段时任务集合整体替换 → 进度天然归零，不需要显式"重置"，
 * 也就不会出现"忘记重置导致进度虚高"的历史包袱。
 */
public final class CaseFlowTemplate {

    private CaseFlowTemplate() {
    }

    // ------------------------------------------------------------------
    // 阶段
    // ------------------------------------------------------------------

    /** 初查：接收材料 → 立案 → 侦查 → 判断是否符合刑拘/处罚条件 → 刑拘或批准处罚 */
    public static final String STAGE_INITIAL = "INITIAL";
    /** 刑拘在办：指派给清案民警 → 逮捕；或取保候审、释放 */
    public static final String STAGE_DETAIN = "DETAIN";
    /** 取保及监居：承接刑拘在办分流过来的取保/监居案件（具体流程后续细化） */
    public static final String STAGE_BAIL = "BAIL";
    /** 已终结：案件办理完毕（清案结束 / 释放 / 处罚决定作出） */
    public static final String STAGE_CLOSED = "CLOSED";

    // ------------------------------------------------------------------
    // 环节（Step）
    // ------------------------------------------------------------------

    // —— 刑事·初查 ——
    public static final String STEP_RECEIVE = "RECEIVE";         // 接收刑事材料
    public static final String STEP_CASE_FILL = "CASE_FILL";     // 立案
    public static final String STEP_INVESTIGATE = "INVESTIGATE"; // 侦查
    public static final String STEP_REVIEW = "REVIEW";           // 反馈是否符合刑拘条件
    public static final String STEP_DETAIN = "DETAIN";           // 刑拘
    // —— 刑事·刑拘在办 ——
    public static final String STEP_ASSIGN_CLEAR = "ASSIGN_CLEAR"; // 指派给清案民警
    public static final String STEP_ARREST = "ARREST";             // 逮捕
    // —— 刑事·取保及监居 ——
    public static final String STEP_EXECUTE_BAIL = "EXECUTE_BAIL"; // 执行取保/监居
    // —— 行政·初查 ——
    public static final String STEP_PRESENT = "PRESENT";         // 呈批材料
    public static final String STEP_PUNISH = "PUNISH";           // 批准行政处罚
    // —— 通用 ——
    public static final String STEP_RELEASE = "RELEASE";         // 释放
    public static final String STEP_CLOSE = "CLOSE";             // 清案结束 / 办结

    // ------------------------------------------------------------------
    // 环节模板（Task）
    // ------------------------------------------------------------------

    /** 环节下挂的标准任务模板 */
    public static final Map<String, List<String>> STEP_TASKS;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // —— 刑事·初查 ——
        m.put(STEP_RECEIVE, Arrays.asList("接收刑事案件材料并登记", "核对材料清单并签字接收"));
        m.put(STEP_CASE_FILL, Arrays.asList("填写立案登记表", "完成立案审批", "制作受案登记表"));
        m.put(STEP_INVESTIGATE, Arrays.asList("开展调查取证", "讯问/询问相关人员", "调取证据材料"));
        m.put(STEP_REVIEW, Arrays.asList("审查是否符合刑拘条件", "形成是否刑拘意见并反馈领导"));
        m.put(STEP_DETAIN, Arrays.asList("办理刑拘手续", "通知家属并送达通知书", "录入强制措施"));
        // —— 刑事·刑拘在办 ——
        m.put(STEP_ASSIGN_CLEAR, Arrays.asList("指派给清案民警", "清案民警接收并登记"));
        m.put(STEP_ARREST, Arrays.asList("提请批准逮捕", "执行逮捕", "讯问并制作讯问笔录"));
        // —— 刑事·取保及监居（后续细化，先给入口）——
        m.put(STEP_EXECUTE_BAIL, Arrays.asList("执行取保候审/监视居住决定", "告知权利义务并送达"));
        // —— 行政·初查 ——
        m.put(STEP_PRESENT, Arrays.asList("制作呈批报告", "补齐案卷材料", "审查是否符合行政处罚条件"));
        m.put(STEP_PUNISH, Arrays.asList("制作行政处罚决定书", "送达当事人并公告", "执行处罚并结案"));
        // —— 通用 ——
        m.put(STEP_RELEASE, Arrays.asList("办理释放手续", "通知相关人员并归档"));
        m.put(STEP_CLOSE, Arrays.asList("整理案卷并归档", "办结登记"));
        STEP_TASKS = Collections.unmodifiableMap(m);
    }

    // ------------------------------------------------------------------
    // 阶段 → 环节顺序
    // ------------------------------------------------------------------

    /** 刑事案件各阶段的环节顺序 */
    public static final Map<String, List<String>> CRIMINAL_STEPS;

    /** 行政案件各阶段的环节顺序（初查出口是「批准行政处罚」而非「刑拘」） */
    public static final Map<String, List<String>> ADMIN_STEPS;

    static {
        Map<String, List<String>> c = new LinkedHashMap<>();
        c.put(STAGE_INITIAL, Arrays.asList(
                STEP_RECEIVE, STEP_CASE_FILL, STEP_INVESTIGATE, STEP_REVIEW, STEP_DETAIN));
        c.put(STAGE_DETAIN, Arrays.asList(STEP_ASSIGN_CLEAR, STEP_ARREST, STEP_CLOSE));
        // 取保阶段后续细化，先只给入口 + 出口两个环节
        c.put(STAGE_BAIL, Arrays.asList(STEP_EXECUTE_BAIL, STEP_CLOSE));
        CRIMINAL_STEPS = Collections.unmodifiableMap(c);

        Map<String, List<String>> a = new LinkedHashMap<>();
        a.put(STAGE_INITIAL, Arrays.asList(
                STEP_RECEIVE, STEP_CASE_FILL, STEP_INVESTIGATE, STEP_PRESENT, STEP_PUNISH));
        a.put(STAGE_DETAIN, new ArrayList<>());           // 行政不走刑拘在办
        a.put(STAGE_BAIL, new ArrayList<>());
        ADMIN_STEPS = Collections.unmodifiableMap(a);
    }

    // ------------------------------------------------------------------
    // 查询方法
    // ------------------------------------------------------------------

    /** 按案件类型取流程定义（刑事/行政，其余按刑事处理） */
    public static Map<String, List<String>> stepsOf(String caseType) {
        return ADMIN_TYPES.contains(caseType) ? ADMIN_STEPS : CRIMINAL_STEPS;
    }

    /** 行政案件类型：行政 + 未立案走行政处罚流程 */
    private static final List<String> ADMIN_TYPES = Arrays.asList("ADMINISTRATIVE");

    /** 某阶段的环节顺序 */
    public static List<String> stepsOfStage(String caseType, String stage) {
        List<String> s = stepsOf(caseType).get(stage);
        return s == null ? new ArrayList<>() : s;
    }

    /** 某环节的标准任务模板（未定义的环节返回空表，表示"环节可自定义、任务由民警录入"） */
    public static List<String> taskTemplateOf(String stepKey) {
        List<String> t = STEP_TASKS.get(stepKey);
        return t == null ? new ArrayList<>() : t;
    }

    /** 阶段中文名 */
    public static String stageLabel(String stage) {
        if (STAGE_INITIAL.equals(stage)) {
            return "初查";
        }
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

    /** 环节中文名 */
    public static String stepLabel(String stepKey) {
        if (stepKey == null) {
            return "侦查";
        }
        switch (stepKey) {
            case STEP_RECEIVE: return "接收材料";
            case STEP_CASE_FILL: return "立案";
            case STEP_INVESTIGATE: return "侦查";
            case STEP_REVIEW: return "是否符合刑拘条件";
            case STEP_DETAIN: return "刑拘";
            case STEP_ASSIGN_CLEAR: return "指派清案民警";
            case STEP_ARREST: return "逮捕";
            case STEP_EXECUTE_BAIL: return "执行取保/监居";
            case STEP_PRESENT: return "呈批材料";
            case STEP_PUNISH: return "批准行政处罚";
            case STEP_RELEASE: return "释放";
            case STEP_CLOSE: return "清案结束";
            default: return "侦查";
        }
    }

    /**
     * 流转出口：某阶段做完后可去往的分支。
     *
     * @return key=流转动作，value={目标阶段, 目标环节}
     */
    public static List<String[]> transitionsOf(String caseType, String stage) {
        List<String[]> out = new ArrayList<>();
        if (STAGE_INITIAL.equals(stage)) {
            if (ADMIN_TYPES.contains(caseType)) {
                // 行政：批准行政处罚后办结
                out.add(new String[]{"PUNISH", STAGE_CLOSED, STEP_CLOSE});
            } else {
                // 刑事初查出口：刑拘 / 取保 / 释放
                out.add(new String[]{"DETAIN", STAGE_DETAIN, STEP_ASSIGN_CLEAR});
                out.add(new String[]{"BAIL", STAGE_BAIL, STEP_EXECUTE_BAIL});
                out.add(new String[]{"RELEASE", STAGE_CLOSED, STEP_CLOSE});
            }
        } else if (STAGE_DETAIN.equals(stage)) {
            // 刑拘在办出口：逮捕结案 / 转取保 / 释放
            out.add(new String[]{"ARREST", STAGE_CLOSED, STEP_CLOSE});
            out.add(new String[]{"BAIL", STAGE_BAIL, STEP_EXECUTE_BAIL});
            out.add(new String[]{"RELEASE", STAGE_CLOSED, STEP_CLOSE});
        } else if (STAGE_BAIL.equals(stage)) {
            // 取保阶段后续细化，先给「解除收案」出口
            out.add(new String[]{"CLOSE", STAGE_CLOSED, STEP_CLOSE});
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
}
