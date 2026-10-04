package com.caseflow.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「新增领导意见」收件箱条目（2026-10-04）。
 *
 * <p>邮件式视图的一条未读意见：意见本体 + 所属案件的关键展示字段
 * （案号/案件名——收件箱里用户要先靠案号认出"这是哪个案子的意见"）。
 * 只读展示，不带任何可写字段。
 */
@Data
public class OpinionInboxVO {

    private Long id;
    /** 所属案件 */
    private Long caseId;
    /** 案号（如 CA-20260930-011），收件箱列表的主识别信息 */
    private String caseNo;
    /** 案件名称 */
    private String caseName;
    /** 意见内容 */
    private String content;
    /** 重要性 A/B/C（null 视为 C） */
    private String importance;
    /** 落实截止时间（可空） */
    private LocalDateTime deadline;
    /** 提出人姓名 */
    private String creatorName;
    /** 提出时间 */
    private LocalDateTime createdAt;
}
