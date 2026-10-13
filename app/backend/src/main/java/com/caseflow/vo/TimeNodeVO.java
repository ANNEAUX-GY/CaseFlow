package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 案件重要时间节点视图。
 */
@Data
public class TimeNodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long caseId;

    /** 预设节点类型（ACCEPT/FILING/…；OTHER 与 NULL 都表示自定义名称） */
    private String nodeKey;

    /** 预设类型的中文名（nodeKey 为空时为空，前端应回退显示 nodeName） */
    private String nodeKeyName;

    /** 节点名称（展示用：自定义时直接是用户填的值） */
    private String nodeName;

    /** 节点日期，格式 yyyy-MM-dd HH:mm:ss */
    private LocalDateTime nodeTime;

    private String nodeTimeText;

    /** 距今天数：负数=已过，正数=还剩几天；null=未设日期 */
    private Integer daysLeft;

    /** 该节点提前多少天提醒（null/0=不提醒） */
    private Integer remindDays;

    /** 是否主节点（列表「截止期限」显示的那个） */
    private Integer isPrimary;

    private Integer sort;
    private String remark;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}