package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 案件主表。来源可以是 PDF / Word / Excel 附件，也可以是直接手打的案件名。
 */
@Data
@TableName("case_info")
public class CaseInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseNo;
    private String name;
    private String sourceType;
    private Long sourceFileId;
    /** 案卷类型（大类）：PRELIMINARY 初查 / CRIMINAL 刑事 / ADMINISTRATIVE 行政 / CIVIL 民事 */
    private String caseType;
    /** 案件类别（小类 / 案由），如：殴打他人、诈骗 */
    private String category;
    /** 案件编号（立案登记表编号 / 受案号） */
    private String filingNo;
    /**
     * 调解书编号。
     *
     * <p>2026-10-09 起废弃：建案表单不再填写，{@code CaseSaveRequest} 也不再接收它。
     * 列与字段保留，只为不抹掉历史数据（有值的老案件仍能查看）。
     */
    private String mediationNo;
    /**
     * 强制措施：NONE无 / DETENTION拘留 / ARREST逮捕 / BAIL取保候审 / RESIDENCE监视居住 / SUMMONS拘传。
     * 建案表单与盯办模块共用这一个字段——两个入口写两份值只会互相打架。
     */
    private String caseMeasure;
    /** 采取强制措施日期（用于推算期限届满日） */
    private LocalDateTime measureDate;
    /** 强制措施期限届满日（刑拘默认+30天，取保+12个月，监居+6个月） */
    private LocalDateTime detainDeadline;
    /** 侦查进度：PENDING_INITIAL待初查/INVESTIGATING侦查中/PENDING_APPROVAL待审批/INVESTIGATION_DONE侦查终结 */
    private String investigationStatus;

    /**
     * 流程阶段：INITIAL初查 / DETAIN刑拘在办 / BAIL取保及监居 / CLOSED已终结。
     *
     * <p>与 {@link #investigationStatus} <b>并存不冲突</b>：
     * 后者是盯办模块 START→SUBMIT→APPROVE 的审批状态机（管「侦查终结要不要领导批」），
     * 本字段是办理流程的阶段（管「走到哪一步、进度多少」）。两者各自独立演进。
     *
     * <p>NULL=存量案件，读时按 INITIAL（初查）处理。
     * 详见 {@link com.caseflow.flow.CaseFlowTemplate}。
     */
    private String flowStage;
    private String description;
    private String priority;
    /**
     * 截止期限。按天记录——落库统一归一成「该日 23:59:59」，
     * 否则当天的 00:00 就已经小于此刻，一进当天就被判成「已逾期」。
     */
    private LocalDateTime deadline;
    /**
     * 期限节点名称（自己填）：受案时间、变更羁押期限时间、移送起诉期限……
     * NULL = 沿用默认叫法「截止期限」。
     */
    private String deadlineLabel;
    /**
     * 该节点提前多少天进入提醒期。NULL = 不提醒（只靠分桶清单）。
     */
    private Integer remindDays;
    /**
     * 重点关注：0否 / 1是。列表里一键标注，不用点开案件详情。
     *
     * <p>标记类操作：写操作日志（CASE.FOCUS）但**不进可撤回白名单**——
     * 点一下星就能改回来，没必要占住「案件最新一步」把真正要撤的操作挡在外面。
     */
    private Integer focus;
    private String status;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
