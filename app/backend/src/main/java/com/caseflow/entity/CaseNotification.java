package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 统一信箱（2026-10-04）。
 *
 * <p>任何与自己有关的操作变更都归入信箱。**每收件人一条**——已读（read_at）
 * 直接落在本行，无需再挂第二张"已读表"（通知量小，铺开最简）。
 *
 * <p>分发口径（见 NotificationService）：
 * <ul>
 *   <li>普通用户：本人承办案件（ACTIVE 主办/协办）相关的操作；</li>
 *   <li>管理层：全站所有用户的操作（除自己触发的）。</li>
 * </ul>
 */
@Data
@TableName("case_notification")
public class CaseNotification implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 收件人（sys_user.id） */
    private Long userId;
    /** 关联案件（可空） */
    private Long caseId;
    /** 来源操作日志 ID（可空） */
    private Long logId;
    /**
     * 定位锚点-待办 ID（2026-10-08）。
     *
     * <p>让「点通知直达具体内容」：不再只跳案件详情页，而是打开对应任务的详情浮窗。
     * 三者皆 NULL 时降级为案件级定位（存量通知即如此）。
     */
    private Long anchorTodoId;
    /** 定位锚点-疑问 ID：进一步定位到任务详情浮窗内的某条疑问/回答 */
    private Long anchorQuestionId;
    /** 定位锚点-子任务 ID：定位到任务详情浮窗内的某个子任务 */
    private Long anchorSubtaskId;
    /** 通知类型：OPINION/ASSIGN/STATUS/TODO/FILE/OTHER */
    private String type;
    /** 摘要标题 */
    private String title;
    /** 正文 */
    private String content;
    /** NULL=未读；非空=已读时间 */
    private LocalDateTime readAt;
    /** 通知时间 */
    private LocalDateTime createdAt;
}
