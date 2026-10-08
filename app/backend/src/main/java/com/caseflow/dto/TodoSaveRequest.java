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

    /** 反馈落实状态：DONE完成 / IN_PROGRESS进行中 / NOT_DONE未完成（提交反馈时用，空=进行中） */
    private String status;

    /**
     * 上传时间（2026-10-08 拆列）：声明里的「于 ____ 上传了」。
     * 原先整个声明句是拼进 content 的一句话，用户要能单独改平台/文件名，
     * 拼在一句里就只能整句重写，故三要素各自独立。
     */
    private String uploadTime;
    /** 上传平台：声明里的「在 ____ 上传了」 */
    private String uploadPlatform;
    /** 上传的文件名称：声明里的「上传了 ____」 */
    private String uploadFile;
}
