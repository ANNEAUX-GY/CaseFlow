package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志：追溯功能的底座，第一版即全量埋点。
 */
@Data
@TableName("operation_log")
public class OperationLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String module;
    private String action;
    private String targetType;
    private Long targetId;
    private String content;
    private Long operatorId;
    private String operatorName;
    /** 操作前快照(JSON)：撤回时按它原样回写 */
    private String snapshotBefore;
    /** 操作后快照(JSON)：用于展示「变更前 / 变更后」 */
    private String snapshotAfter;
    /** 1 = 本条已被撤回 */
    private Integer undone;
    /** 撤回本条所产生的新日志 ID */
    private Long undoLogId;
    /** 本条若是撤回操作，指向被撤回的那条日志 ID */
    private Long undoOf;
    private LocalDateTime createdAt;
}
