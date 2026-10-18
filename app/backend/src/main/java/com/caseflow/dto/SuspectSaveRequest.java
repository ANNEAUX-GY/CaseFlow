package com.caseflow.dto;

import lombok.Data;

import java.time.LocalDateTime;

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

    /**
     * 当前强制措施（2026-10-18）：NONE / DETENTION / ARREST / BAIL / RESIDENCE / SUMMONS。
     *
     * <p>选了具体措施（非 NONE）时，{@code measureStartAt} 与 {@code measureEndAt}
     * 必须同时填写——服务端会校验，前端也会在弹窗里拦住。
     */
    private String measure;

    /** 本次措施开始时间 */
    private LocalDateTime measureStartAt;

    /** 下一节点：措施期满 / 结束时间 */
    private LocalDateTime measureEndAt;

    private String remark;
}
