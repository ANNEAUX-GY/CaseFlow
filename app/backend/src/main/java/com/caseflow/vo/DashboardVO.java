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

    /** 逾期案件清单（最需要注意的） */
    private List<CaseVO> overdueList;
    /** 7 天内到期清单 */
    private List<CaseVO> dueSoonList;
    /** 待指派清单 */
    private List<CaseVO> pendingList;
    /** 最近操作 */
    private List<LogVO> recentLogs;
}
