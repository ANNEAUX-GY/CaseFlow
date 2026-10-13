package com.caseflow.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 待办草稿（2026-10-13）。
 *
 * <p><b>为什么抽一个 DTO 而不是直接收 {@code Map}</b>：
 * 「添加待办」这条链路现在有三个入口——案件详情的「添加待办」弹窗、
 * 指派弹窗、建案弹窗。三处都必须收同一组字段
 * （内容 / 截止时间 / 重要程度），字段一散就必然漂移成
 * 「指派能填截止时间、详情不能」之类的不一致。
 * 用一个类把这三个字段钉死，是让三处行为一致最省事也最可靠的做法。
 *
 * <p>后端落库时统一转成 {@code case_leader_opinion}（提意见）再派生
 * {@code case_todo}——待办只有一种产生方式，不存在"手工待办"与
 * "意见派生待办"两套数据形态。
 */
@Data
public class TodoDraftRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待办内容（必填，空行会被跳过） */
    private String content;

    /**
     * 截止时间（选填）。
     * 格式 {@code yyyy-MM-dd HH:mm:ss}；前端按天选后会补 23:59:59。
     * 传空串 = 不设截止时间。
     */
    private String deadline;

    /**
     * 重要程度（选填）：A 最重要 / B 重要 / C 一般。
     * 留空或非法值一律按 C 处理（见 {@code OpinionService#normalizeImportance}）。
     */
    private String importance;
}
