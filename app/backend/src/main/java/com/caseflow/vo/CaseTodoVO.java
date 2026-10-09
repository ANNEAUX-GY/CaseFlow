package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.caseflow.entity.CaseTodoFeedback;

/**
 * 待办视图：内容 + 完成状态 + 佐证材料（含上传人 / 上传时间）。
 */
@Data
public class CaseTodoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long caseId;
    /** 冗余案件编号 / 名称：待办总览页要跨案件列出，省得前端再逐条查 */
    private String caseNo;
    private String caseName;
    /**
     * 所属案件是否被标为「重点关注」（2026-10-09）。
     * 待办总览按待办列行，但星标打的是背后的案件——不带这个字段前端就得逐行再查案件。
     */
    private Integer caseFocus;
    private String content;
    /** PENDING / DONE */
    private String status;
    private String statusName;
    private Integer sort;
    /** 紧急程度 URGENT紧急/HIGH较急/NORMAL一般（NULL 归一为 NORMAL） */
    private String urgency;
    private String urgencyName;
    /** 重点程度 KEY重点/MEDIUM次重点/NORMAL一般（NULL 归一为 NORMAL） */
    private String importance;
    private String importanceName;
    /** 所属部门/来源（提意见人所在部门） */
    private String deptSource;
    /** 截止时间（取意见的落实截止时间，可空） */
    private LocalDateTime deadline;
    /** 距截止天数：负数=已超期，0=今天到期，null=无期限 */
    private Integer daysLeft;
    /** 源领导意见 ID：点条目可跳到对应意见详情 */
    private Long opinionId;

    // ---- 意见侧信息（意见派生待办才有值，合并面板一次展示待办+意见） ----
    /** 意见重要性 A/B/C */
    private String opinionImportance;
    /** 意见落实截止时间 */
    private LocalDateTime opinionDeadline;
    /** 意见提出人姓名 */
    private String opinionCreatorName;
    /** 意见提出时间 */
    private LocalDateTime opinionCreatedAt;
    /** 意见落实状态：DONE/IN_PROGRESS/NOT_DONE，NULL=待反馈（由待办反馈同步） */
    private String opinionFeedbackStatus;
    /** 紧急程度排序权重（后端算好给前端用，避免各端各写一套映射） */
    private Integer urgencyWeight;
    /** 重点程度排序权重 */
    private Integer importanceWeight;
    private LocalDateTime doneAt;
    private String doneByName;
    private String remark;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- 子任务与反馈（2026-10-04） ----
    /** 父任务 ID：NULL=顶层主任务；非空=子任务 */
    private Long parentId;
    /** 反馈记录条数（列表页用于判断能否勾选，不必为判断而拉全量详情） */
    private Integer feedbackCount;

    /** 本任务的子任务总数（仅主任务有值） */
    private Integer subtaskTotal;
    /** 已完成的子任务数（仅主任务有值） */
    private Integer subtaskDone;
    /** 全部反馈记录（按时间正序；仅详情弹窗接口填充，列表不返回以免过重） */
    private List<CaseTodoFeedback> feedbacks = new ArrayList<>();
    /** 子任务列表（仅详情弹窗接口填充） */
    private List<CaseTodoVO> subtasks = new ArrayList<>();

    /** 佐证材料数量 */
    private Integer evidenceCount;
    /** 佐证材料明细（列表页不填充，仅案件详情 / 总览填充） */
    private List<CaseFileVO> evidence = new ArrayList<>();

    /** 便捷标记：是否已有佐证材料（前端据此提示「需上传佐证」） */
    private Boolean hasEvidence;
}
