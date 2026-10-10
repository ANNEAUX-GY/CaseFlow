package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 常用待办记忆（2026-10-11）。
 *
 * <p>「添加待办」弹窗底部的「常用待办」：一条 = 某个登录人写过的一句待办内容 + 用了几次。
 * 排序按 {@code use_count} 降序（次数相同则最近用过在前），前端取前 N 条做成可点的标签。
 *
 * <p><b>按人记，不共享</b>：甲天天写的「走访受害人」对乙未必常用，混在一起会变成噪声。
 *
 * <p>去重在应用层做（先按 user_id 查出来比对原文）：content 是 VARCHAR(500)，
 * 在 (user_id, content) 上建唯一索引会撞 H2 / MySQL 对索引键长度的不同限制。
 */
@Data
@TableName("todo_preset")
public class TodoPreset implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 归属登录人（sys_user.id） */
    private Long userId;
    /** 待办内容原文（去首尾空格后存储） */
    private String content;
    /** 最近一次使用时的重要性 A/B/C；点击添加时沿用，null 按 C */
    private String lastImportance;
    /** 累计使用次数 */
    private Integer useCount;
    /** 最近一次使用时间 */
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
