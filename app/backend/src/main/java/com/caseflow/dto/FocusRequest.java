package com.caseflow.dto;

import lombok.Data;

/**
 * 一键重点关注（2026-10-09）的入参。
 *
 * <p>只有 {@code focus} 一个字段：1 / true = 标注为重点，0 / false = 取消。
 * 前端传 int（列表上按当前星标取反），这里兜住 Boolean 与 "0"/"false" 两种写法，
 * 免得脚本或以后别的调用方因为类型不同而静默生效成"总是标重点"。
 */
@Data
public class FocusRequest {

    private Integer focus = 1;
}
