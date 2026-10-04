package com.caseflow.flow;

import java.util.Arrays;
import java.util.List;

/**
 * 办案组别（2026-10-04）与指派约束。
 *
 * <p>规则来自业务约定：
 * <ul>
 *   <li><b>初查</b>任务 → 只能指派给<b>初查组</b>；</li>
 *   <li><b>刑拘在办</b> → 只能指派给<b>清案组</b>；</li>
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
     * 判断某人能否承接该案件。
     *
     * <p><b>规则刻意分两档</b>：
     * <ul>
     *   <li>要求「初查组」时，清案组与不限组<b>一律不接</b>——业务上就是两个专业分工；</li>
     *   <li>要求「清案组」时同理由；</li>
     *   <li>不限制时任何人可接。</li>
     * </ul>
     */
    public static boolean canTake(String required, String actual) {
        String need = normalize(required);
        if (NONE.equals(need)) {
            return true;
        }
        return need.equals(normalize(actual));
    }
}
