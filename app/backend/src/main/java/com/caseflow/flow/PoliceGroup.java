package com.caseflow.flow;

import java.util.Arrays;
import java.util.List;

/**
 * 办案组别（2026-10-04）与指派约束。
 *
 * <p>规则来自业务约定（2026-10-07 修订：不限组放开）：
 * <ul>
 *   <li><b>初查</b>任务 → 只能指派给<b>初查组</b>或<b>不限</b>；</li>
 *   <li><b>刑拘在办</b> → 只能指派给<b>清案组</b>或<b>不限</b>；</li>
 *   <li><b>其他案件</b>（行政/未立案/取保等）→ 不限制组别。</li>
 * </ul>
 *
 * <p><b>为什么"取保及监居"不限制</b>：清案组是为主刑后的清案流程设的，
 * 取保阶段既不是初查也不是清案，限制它会让案件无处可派。
 *
 * <p><b>权威来源是 {@code org_employee.police_group} 而不是账号</b>：
 * 指派选的是「员工」，组别理应挂在员工档案上。账号上的
 * {@code sys_user.police_group} 只是注册时自报、审核时确认的值——
 * 有些账号（比如 boss）根本没有员工档案，用它做校验会漏掉真实约束。
 */
public final class PoliceGroup {

    private PoliceGroup() {
    }

    /** 初查组：只能承接初查任务 */
    public static final String INITIAL = "INITIAL";
    /** 清案组：只能承接刑拘在办 */
    public static final String CLEAR = "CLEAR";
    /** 不限：可承接任何案件（其他案件一律走这里） */
    public static final String NONE = "NONE";

    public static final List<String> ALL = Arrays.asList(INITIAL, CLEAR, NONE);

    /** 组别中文名 */
    public static String label(String g) {
        if (INITIAL.equals(g)) {
            return "初查组";
        }
        if (CLEAR.equals(g)) {
            return "清案组";
        }
        return "不限";
    }

    /**
     * 归一：空值/非法值一律视为 {@link #NONE}（不限）。
     *
     * <p>存量员工没填组别时不能因此指派不出去——那等于上线即瘫痪。
     */
    public static String normalize(String g) {
        if (g == null) {
            return NONE;
        }
        String v = g.trim().toUpperCase();
        return ALL.contains(v) ? v : NONE;
    }

    /**
     * 按案件类型 + 盯办模块得出「该案件要求什么组别」。
     *
     * @param caseType 案卷类型 CRIMINAL / ADMINISTRATIVE / PRELIMINARY
     * @param module盯办子模块 INITIAL初查 / DETENTION刑拘在办 / BAIL_RESIDENCE取保监居；
     *                为空时按「未开始盯办」处理，不限制
     * @return 要求的组别；{@link #NONE} 表示不限制
     */
    public static String requiredOf(String caseType, String module) {
        if (module == null || module.trim().isEmpty()) {
            return NONE;
        }
        String m = module.trim().toUpperCase();
        if ("INITIAL".equals(m)) {
            return INITIAL;
        }
        if ("DETENTION".equals(m)) {
            return CLEAR;
        }
        // 取保及监居 / 行政 / 未立案等一律不限制
        return NONE;
    }

    /**
     * 强制措施 → 盯办子模块。
     *
     * <p>组别校验（{@link #requiredOf}）、案件列表的子模块过滤、承办负荷统计
     * 都依赖同一套「措施 → 模块」映射，收在这里避免各处各写一份后口径漂移。
     *
     * @param measure 强制措施：NONE/空=初查，DETENTION=刑拘在办，BAIL/RESIDENCE=取保监居
     */
    public static String moduleOfMeasure(String measure) {
        if (measure == null || measure.trim().isEmpty() || "NONE".equalsIgnoreCase(measure.trim())) {
            return "INITIAL";
        }
        String m = measure.trim().toUpperCase();
        if ("DETENTION".equals(m)) {
            return "DETENTION";
        }
        if ("BAIL".equals(m) || "RESIDENCE".equals(m)) {
            return "BAIL_RESIDENCE";
        }
        return "INITIAL";
    }

    /**
     * 判断某人能否承接该案件。
     *
     * <p><b>规则（2026-10-07 修订）</b>：
     * <ul>
     *   <li>案件不限组别 → 任何人可接；</li>
     *   <li>要求「初查组/清案组」→ 本组成员可接，<b>「不限」组成员也可接</b>
     *       ——不限 &gt; 其他任何组，机动力量哪里需要去哪里；</li>
     *   <li>唯一仍拦的：初查组 × 清案组互换——两个专业分工不互派。</li>
     * </ul>
     */
    public static boolean canTake(String required, String actual) {
        String need = normalize(required);
        if (NONE.equals(need)) {
            return true;
        }
        String have = normalize(actual);
        if (NONE.equals(have)) {
            return true;   // 不限：可承接任何组别的案件
        }
        return need.equals(have);
    }
}
