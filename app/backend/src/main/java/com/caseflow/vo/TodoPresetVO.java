package com.caseflow.vo;

import lombok.Data;

/**
 * 常用待办提示项（2026-10-11）。
 *
 * <p>只回前端点一下就要用的三个字段：显示文本、点击时沿用的重要性、用了几次
 * （次数用来在界面上排先后，也让人知道"这条我确实常用"）。
 * 不带任何可写字段——本 VO 只读，写入走「添加待办」接口。
 */
@Data
public class TodoPresetVO {

    private Long id;
    /** 待办内容（点击即按这条原文添加） */
    private String content;
    /** 最近一次使用的重要性 A/B/C（null 时前端按 C） */
    private String importance;
    /** 累计使用次数 */
    private Integer useCount;
}
