package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志视图。列表接口只填基础字段；详情接口额外带上 {@link #changes} 变更明细。
 */
@Data
public class LogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String module;
    private String action;
    /** 动作中文名，如「指派 / 改派」 */
    private String actionName;
    private String targetType;
    private Long targetId;
    private String content;
    private String operatorName;
    private LocalDateTime createdAt;
    /** 已格式化时间，避免前端直接显示 ISO 串 */
    private String createdAtText;

    /** 1 = 本条已被撤回 */
    private Boolean undone;
    /** 是否还能撤回（false 时看 {@link #undoHint} 知道原因） */
    private Boolean undoable;
    /** 不可撤回的原因 */
    private String undoHint;
    /** 撤回本条所产生的新日志 ID */
    private Long undoLogId;
    /** 本条若是撤回操作，指向被撤回的日志 ID */
    private Long undoOf;
    /** 被撤回那条操作的原文，方便在详情里说清「撤回了什么」 */
    private String undoOfContent;

    /** 目标案件的编号与名称（案件已删除时取自快照） */
    private String caseNo;
    private String caseName;
    /** 目标案件当前是否还存在 */
    private Boolean caseExists;

    /** 变更明细（仅详情接口） */
    private List<ChangeVO> changes;
}
