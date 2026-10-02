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
    /** 立案登记表编号（受案号） */
    private String filingNo;
    /** 调解书编号 */
    private String mediationNo;
    /** 强制措施：NONE无 / DETENTION刑拘 / BAIL取保候审 / RESIDENCE监视居住（盯办模块） */
    private String caseMeasure;
    /** 采取强制措施日期（用于推算期限届满日） */
    private LocalDateTime measureDate;
    /** 强制措施期限届满日（刑拘默认+30天，取保+12个月，监居+6个月） */
    private LocalDateTime detainDeadline;
    /** 侦查进度：PENDING_INITIAL待初查/INVESTIGATING侦查中/PENDING_APPROVAL待审批/INVESTIGATION_DONE侦查终结 */
    private String investigationStatus;
    private String description;
    private String priority;
    private LocalDateTime deadline;
    private String status;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
