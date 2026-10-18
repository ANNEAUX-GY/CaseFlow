package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 嫌疑人身份信息视图。
 */
@Data
public class SuspectVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long caseId;
    private String name;
    private String gender;
    private String genderName;
    private String idCard;
    private String phone;
    private String address;
    /** 当前强制措施（2026-10-18） */
    private String measure;
    private String measureName;
    /** 本次措施开始时间 */
    private LocalDateTime measureStartAt;
    /** 下一节点：措施期满 / 结束时间 */
    private LocalDateTime measureEndAt;
    private String remark;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
