package com.caseflow.flow;

/**
 * 组织层级（2026-10-08）：<b>总 → 副总 → 组长 → 员工</b>，固定四层。
 *
 * <p><b>为什么要按职务定层</b>：以前组织树完全靠 {@code parent_id} 决定形状，
 * 结果是「副总」和「总」平级、「组员」自己成了顶层（实测：一个组员挂在最右边，
 * 整棵树看上去像三棵并排的树）。层级是组织身份、不是随手连出来的连线，
 * 所以改成由<b>职务</b>推导层级，{@code parent_id} 只决定「同一层里挂到谁名下」。
 *
 * <p>前端 {@code src/utils/org.js} 是同一套口径的镜像，改规则必须两边一起改。
 *
 * <p><b>职务是自由文本</b>（表单 allow-create），所以用包含匹配而不是等值：
 * 「派出所长」「副所长」「班组长」都能落到正确的层。
 */
public final class OrgRank {

    private OrgRank() {
    }

    /** 第 1 层：总 / 领导 / 所长 */
    public static final int TOP = 1;
    /** 第 2 层：副总 / 副领导 */
    public static final int DEPUTY = 2;
    /** 第 3 层：组长 / 队长 */
    public static final int LEADER = 3;
    /** 第 4 层：员工 / 组员 / 民警 */
    public static final int STAFF = 4;

    /** 层级中文名，供界面直接展示 */
    public static String label(int rank) {
        switch (rank) {
            case TOP:
                return "总/领导";
            case DEPUTY:
                return "副总";
            case LEADER:
                return "组长";
            default:
                return "员工";
        }
    }

    /**
     * 职务 → 层级。
     *
     * <p><b>判定顺序有讲究</b>：先「副」再「组长」，否则「副组长」会被误判成副总层；
     * 「长」放在最后兜底，否则「组长」「队长」会被当成总。
     *
     * <p>空职务按<b>员工层</b>处理：宁可挂到最底下等人来调整，
     * 也不要因为没填职务就把他摆到顶层去。
     */
    public static int of(String title) {
        if (title == null) {
            return STAFF;
        }
        String t = title.trim();
        if (t.isEmpty()) {
            return STAFF;
        }
        if (t.contains("副")) {
            return DEPUTY;
        }
        if (t.contains("组长") || t.contains("队长")) {
            return LEADER;
        }
        if (t.contains("组员") || t.contains("警员") || t.contains("民警")) {
            return STAFF;
        }
        if (t.contains("领导") || t.contains("总") || t.contains("长")) {
            return TOP;
        }
        return STAFF;
    }

    /** 上下级是否合法：父层必须恰好比子层高一级（总-副总-组长-员工，不许跳级） */
    public static boolean isParentOf(int parentRank, int childRank) {
        return parentRank == childRank - 1;
    }
}
