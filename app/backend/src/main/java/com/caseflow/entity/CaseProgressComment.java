package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 办理进度批注（类似 Word 批注）：管理层对每条办理进度（{@link OperationLog}）写的旁注。
 *
 * <p>权限：新增 / 编辑 / 删除 = 管理层（管理员 + 领导）；查看 = 所有能看该案件详情的人。
 * 展示三要素：批注人（creator_name）、批注时间（created_at）、关联进度（log_id）。
 *
 * <p>刻意不进案件快照体系：批注是"旁注"，撤回某条进度时不连带回滚批注；
 * 批注自身的增删改会埋点进 operation_log 时间线。
 */
@Data
@TableName("case_progress_comment")
public class CaseProgressComment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 案件 ID（冗余，便于按案件校验与清理） */
    private Long caseId;
    /** 关联的办理进度（operation_log.id） */
    private Long logId;
    /** 批注内容 */
    private String content;
    /** 批注人 */
    private Long creatorId;
    /** 批注人姓名（冗余展示） */
    private String creatorName;
    /** 批注时间 */
    private LocalDateTime createdAt;
    /** 最后编辑时间 */
    private LocalDateTime updatedAt;
    /** 最后编辑人姓名（冗余展示） */
    private String updaterName;
}
