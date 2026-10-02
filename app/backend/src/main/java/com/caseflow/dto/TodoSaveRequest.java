package com.caseflow.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 待办新增 / 编辑参数。
 */
@Data
public class TodoSaveRequest {

    /** 编辑时必填 */
    private Long id;

    @NotBlank(message = "请填写待办内容")
    private String content;

    /** 完成说明（标记完成时可选填） */
    private String remark;
}
