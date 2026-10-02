package com.caseflow.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
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
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime deadline;

    /** 前端用：是否显式提交过 deadline 字段（用于区分「不改」与「清空」） */
    private boolean deadlineTouched;

    /**
     * 指派时一并添加的待办（to do）备注项。
     * null = 不改动既有待办；传数组则按「覆盖式同步」处理（见 TodoService.syncForAssign）：
     * 新清单里没有的**未完成且无佐证**的项会被删除，已完成的项保留留痕。
     */
    private List<String> todos;
}
