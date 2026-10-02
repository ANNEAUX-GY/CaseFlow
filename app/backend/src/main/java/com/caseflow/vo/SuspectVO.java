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
    private String remark;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
