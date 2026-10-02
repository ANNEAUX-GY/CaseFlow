package com.caseflow.dto;

import lombok.Data;

/**
 * 案件列表查询条件。
 */
@Data
public class CaseQuery {

    private Integer page = 1;
    private Integer size = 20;

    /** 案件名 / 编号关键词 */
    private String keyword;

    private String status;
    private String priority;
    private String sourceType;
    /** 案件类别（小类 / 案由）精确匹配 */
    private String category;
    /** 案卷类型（大类）：PRELIMINARY / CRIMINAL / ADMINISTRATIVE / CIVIL */
    private String caseType;
    /** 是否有嫌疑人：YES / NO */
    private String hasSuspect;

    /** 盯办子模块：INITIAL 初查 / DETENTION 刑拘在办 / BAIL_RESIDENCE 取保及监居（翻译成强制措施+在办条件） */
    private String module;
    /** 侦查进度：PENDING_INITIAL / INVESTIGATING / PENDING_APPROVAL / INVESTIGATION_DONE */
    private String investigationStatus;

    /** 嫌疑人姓名（模糊） / 身份证号（精确前缀），盯办初查页签用 */
    private String suspectName;
    private String suspectIdCard;

    /** 按承办人过滤 */
    private Long employeeId;

    /** 只看与我相关的案件 */
    private Boolean onlyMine = false;

    /** 到期桶：OVERDUE / TODAY / D3 / D7 / NONE */
    private String dueBucket;

    private String sortField = "created_at";
    private String sortOrder = "desc";
}
