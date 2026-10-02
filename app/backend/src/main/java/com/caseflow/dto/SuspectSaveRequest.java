package com.caseflow.dto;

import lombok.Data;

/**
 * 嫌疑人录入 / 修改参数。
 */
@Data
public class SuspectSaveRequest {

    private Long id;

    private Long caseId;

    private String name;

    /** MALE 男 / FEMALE 女 */
    private String gender;

    private String idCard;

    private String phone;

    private String address;

    private String remark;
}
