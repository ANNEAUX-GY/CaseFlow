package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 领导意见与落实反馈：管理层（领导）对案件提出多条意见；
 * 办案人（本案 ACTIVE 承办人）对每条意见标记落实情况并记录反馈时间与说明。
 *
 * <p>权限：提出 = 管理层；反馈 = 本案现职承办人；查看 = 所有能看该案件详情的人。
 * 反馈只保留最新一条（反馈历史经 operation_log 时间线可查）。
 */
@Data
@TableName("case_leader_opinion")
public class CaseLeaderOpinion implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 反馈状态：已完成落实 */
    public static final String FB_DONE = "DONE";
    /** 反馈状态：落实中 */
    public static final String FB_IN_PROGRESS = "IN_PROGRESS";
    /** 反馈状态：未完成 */
    public static final String FB_NOT_DONE = "NOT_DONE";

    /** 重要性：A = 最重要 */
    public static final String IMP_A = "A";
    /** 重要性：B = 重要 */
    public static final String IMP_B = "B";
    /** 重要性：C = 一般（默认档，旧数据 NULL 视为此档） */
    public static final String IMP_C = "C";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 案件 ID */
    private Long caseId;
    /** 意见内容 */
    private String content;
    /** 提出人（管理层） */
    private Long creatorId;
    /** 提出人姓名（冗余展示） */
    private String creatorName;
    /** 提出时间 */
    private LocalDateTime createdAt;
    /** 落实反馈：DONE完成 / IN_PROGRESS进行中 / NOT_DONE未完成，NULL=尚未反馈 */
    private String feedbackStatus;
    /** 反馈说明（含结构化上传声明句，见意见回复弹窗） */
    private String feedbackNote;
    /** 反馈人（办案人） */
    private Long feedbackBy;
    /** 反馈人姓名（冗余展示） */
    private String feedbackByName;
    /** 反馈时间 */
    private LocalDateTime feedbackAt;

    /**
     * 拖拽排序位次（1 起连续）。
     *
     * <p>只负责<strong>持久化顺序</strong>，不负责显示编号——列表上的序号由前端按当前
     * 数组下标实时计算（永远 1..N 连续），所以插入、删除、拖拽之后编号自动重算，
     * 结构上不可能出现断号或重复号。
     *
     * <p>旧数据为 NULL，读取时按 id 升序回退，并由 {@code OpinionService} 惰性初始化为 1..N。
     */
    private Integer sortOrder;

    /**
     * 意见落实的截止时间（可空）。
     *
     * <p>用于「在某时间之前完成某事」这类表述。为空时紧急性判定为「正常」。
     * 紧急性（正常/临期/已逾期）由本字段与当前时间<strong>实时计算</strong>，不落库，
     * 因此不存在定时任务刷新导致的状态过期问题。
     */
    private LocalDateTime deadline;

    /**
     * 重要性分级：A=最重要 / B=重要 / C=一般。
     * 旧数据为 NULL，展示时按 {@link #IMP_C}（C 一般）处理。
     */
    private String importance;
}
