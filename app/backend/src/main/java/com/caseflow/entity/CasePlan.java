package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 侦查计划项（案件盯办模块）：计划 → 执行 → 预警 的 checklist。
 * 随案件快照一并存档，撤回时可整体还原。
 */
@Data
@TableName("case_plan")
public class CasePlan implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    /** 侦查计划内容（如：调取监控、询问证人、送检鉴定） */
    private String content;
    /** 计划完成时限 */
    private LocalDateTime plannedAt;
    /** PENDING待完成 / DONE已完成 / CANCELLED已取消 */
    private String status;
    private LocalDateTime doneAt;
    /** 完成情况说明 */
    private String doneNote;
    private Integer sort;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
