package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 案件详情视图：主信息 + 承办人 + 附件 + 到期倒计时。
 */
@Data
public class CaseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String caseNo;
    private String name;
    private String sourceType;
    private String sourceTypeName;
    private Long sourceFileId;
    /** 案卷类型（大类） */
    private String caseType;
    private String caseTypeName;
    /** 案件类别（小类 / 案由） */
    private String category;
    /** 立案登记表编号 */
    private String filingNo;
    /** 调解书编号 */
    private String mediationNo;
    private String description;
    private String priority;
    private String priorityName;
    private LocalDateTime deadline;
    private String deadlineText;
    /** 剩余天数：负数表示已逾期 */
    private Integer daysLeft;
    /** OVERDUE / TODAY / SOON / NORMAL / NONE */
    private String dueLevel;
    private String status;
    private String statusName;
    private String remark;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 主办人 */
    private AssigneeVO owner;
    /** 协办人 */
    private List<AssigneeVO> members;
    /** 历史指派记录（含被改派的旧记录） */
    private List<AssigneeVO> assignHistory;
    private List<CaseFileVO> files;

    /** 嫌疑人数量（列表页也要显示，所以单独给一个计数） */
    private Integer suspectCount;
    /** 嫌疑人明细（仅详情返回） */
    private List<SuspectVO> suspects;

    /** 强制措施：NONE / DETENTION / BAIL / RESIDENCE（盯办模块） */
    private String caseMeasure;
    private String caseMeasureName;
    /** 采取强制措施日期 */
    private LocalDateTime measureDate;
    /** 强制措施期限届满日（含倒计时字段） */
    private LocalDateTime detainDeadline;
    private String detainDeadlineText;
    private Integer detainDaysLeft;
    /** 侦查进度：PENDING_INITIAL / INVESTIGATING / PENDING_APPROVAL / INVESTIGATION_DONE */
    private String investigationStatus;
    private String investigationStatusName;

    /** 侦查计划进度（盯办模块）：done/total/pending/overdue
     *  注意 2026-10 起口径已收窄为「当前阶段」任务，与流程面板数字一致 */
    private Integer planDone;
    private Integer planTotal;
    private Integer planOverdue;

    /** 流程阶段：INITIAL初查/DETAIN刑拘在办/BAIL取保及监居/CLOSED已终结（NULL=存量按初查） */
    private String flowStage;
    /** 阶段中文名，便于前端直接展示 */
    private String flowStageName;

    /** 待办（to do）进度：已完成 / 总数（仅详情页填充） */
    private Integer todoDone;
    private Integer todoTotal;
}
