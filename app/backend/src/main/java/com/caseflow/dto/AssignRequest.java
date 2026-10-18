package com.caseflow.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 指派 / 改派参数。
 */
@Data
public class AssignRequest {

    private Long caseId;

    /** 主办人（员工 ID） */
    private Long ownerId;

    /** 协办人列表 */
    private List<Long> memberIds;

    private String note;

    /**
     * 指派时一并设定 / 调整截止期限（选填）。
     * 传 null 表示不改；传空字符串表示清空期限。
     */
    private LocalDateTime deadline;

    /** 前端用：是否显式提交过 deadline 字段（用于区分「不改」与「清空」） */
    private boolean deadlineTouched;

    /**
     * 指派时一并添加的待办（选填）。
     *
     * <p><b>null = 不添加待办</b>（绝大多数指派都不带待办，保持原样不动）；
     * 传数组则逐条落库，路径与案件详情「添加待办」完全一致：
     * 写 {@code case_leader_opinion} 再派生 {@code case_todo}，
     * 因而带截止时间、重要程度、常用待办记忆。
     *
     * <p><b>语义由「覆盖式同步」改为「只追加」</b>（2026-10-13）：
     * 旧实现按内容匹配认领并删除「清单里没有的未完成待办」，
     * 那套逻辑建立在「待办是一份可整体替换的清单」假设上。
     * 既然待办现在是意见派生的（带 opinion_id、有反馈与佐证、有阶段章），
     * 删除一条待办必须走「移除意见 → 级联删派生待办」这条有日志可撤的路，
     * 在指派弹窗里按内容猜着删已经不成立。既有待办要改，去案件详情里操作。
     */
    private List<TodoDraftRequest> todos;
}
