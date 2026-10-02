package com.caseflow.dto;

import lombok.Data;

/**
 * 案件状态流转参数。
 */
@Data
public class StatusRequest {

    private String status;
    private String remark;
}
