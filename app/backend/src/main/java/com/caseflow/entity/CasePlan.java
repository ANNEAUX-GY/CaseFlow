package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 阶段任务项（原「侦查计划」，2026-10 升格为流程任务）：
 * 阶段 {@link #stage} → 环节 {@link #stepKey} → 任务（本行）三层结构。
 *
 * <p>随案件快照一并存档，撤回时可整体还原。
 *
 * <p><b>存量兼容</b>：升级前的计划行 stage/step_key/task_key 皆为 NULL、
 * is_std=0，读进度时归入「初查 / 侦查」环节展示，不影响既有数据可见。
 */
@Data
@TableName("case_plan")
public class CasePlan implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    /** 任务内容（如：调取监控、询问证人、送检鉴定） */
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

    /**
     * 所属阶段：INITIAL初查 / DETAIN刑拘在办 / BAIL取保及监居 / CLOSED已终结。
     * NULL=存量计划，读时按 INITIAL 处理。
     */
    private String stage;

    /**
     * 所属环节：RECEIVE接收材料 / CASE_FILL立案 / INVESTIGATE侦查 / DETAIN刑拘 …
     * NULL=存量计划，读时按 {@link com.caseflow.flow.CaseFlowTemplate#STEP_INVESTIGATE} 处理。
     */
    private String stepKey;

    /**
     * 标准任务标识（模板内唯一），仅 {@link #isStd}=1 的行有值。
     * 用于「补充/重置标准任务」时幂等去重，避免重复生成。
     */
    private String taskKey;

    /** 1=按流程模板生成的标准任务；0=民警自建或存量数据 */
    private Integer isStd;
}
