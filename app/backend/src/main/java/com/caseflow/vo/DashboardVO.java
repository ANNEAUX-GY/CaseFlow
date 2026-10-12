package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 工作台数据。
 */
@Data
public class DashboardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private long totalCase;
    private long pendingAssign;
    private long inProgress;
    private long done;
    /** 在办 = 未办结且未撤销（含待指派 / 已指派 / 处理中） */
    private long openCase;
    private long overdue;
    private long dueToday;
    private long dueIn3Days;
    private long dueIn7Days;
    private long employeeCount;

    /**
     * 重点关注案件数（2026-10-12）。
     *
     * <p>口径 = focus = 1 且**未办结未撤销**。已办结的重点案件不该再占工作台的位置：
     * 工作台是"今天要盯什么"的入口，办结的盯不动了。
     * 前端 KPI「重点案件」与下方「重点案件」清单必须同口径，否则会出现
     * "卡片写 5 件、下面只列出 3 条"的对不上。
     */
    private long focusCase;
    /** 上面这些重点案件里已经逾期的条数（领导最该先看的那部分） */
    private long focusOverdue;

    /** 逾期案件清单（最需要注意的） */
    private List<CaseVO> overdueList;
    /** 7 天内到期清单 */
    private List<CaseVO> dueSoonList;
    /** 待指派清单 */
    private List<CaseVO> pendingList;
    /**
     * 重点案件清单（在办，按紧急程度排：逾期 → 今天到期 → 期限由近到远）。
     *
     * <p>走 page() 复用与案件管理完全一致的筛选与数据范围收敛，不另写一套查询。
     */
    private List<CaseVO> focusList;
    /** 最近操作 */
    private List<LogVO> recentLogs;
}
