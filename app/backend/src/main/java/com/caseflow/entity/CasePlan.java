package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 侦查计划（工作项）。
 *
 * <p>随案件快照一并存档，撤回时可整体还原。
 *
 * <p><b>2026-10-12 变更</b>：本表曾被升格为「阶段→环节→标准任务」三层结构，
 * 建案时自动铺一批标准任务（接收材料/立案/侦查…）。办案阶段后来统一由
 * <b>案件待办</b>（{@code case_todo}）承担，阶段进度只看待办，
 * 那批模板任务已在启动迁移里置为 CANCELLED（留痕但不再计入统计）。
 * 本表回归本来的角色：民警自排的工作计划与逾期预警。
 *
 * <p>下面 stage / stepKey / taskKey / isStd 四列<b>不再被写入</b>，
 * 仅保留以兼容历史数据；新代码不要依赖它们做业务判断。
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
     * 历史字段（2026-10-12 起不再写入）：所属阶段 INITIAL/DETAIN/BAIL/CLOSED。
     * 仅存量的模板任务行有值，新数据一律为 NULL。
     */
    private String stage;

    /** 历史字段（2026-10-12 起不再写入）：所属环节。 */
    private String stepKey;

    /** 历史字段（2026-10-12 起不再写入）：模板内任务标识。 */
    private String taskKey;

    /** 历史字段（2026-10-12 起不再写入）：1=曾按模板生成的标准任务；0=民警自建或存量 */
    private Integer isStd;
}
