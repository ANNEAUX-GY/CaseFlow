package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 意见已读记录（2026-10-04）。
 *
 * <p>邮件式「新增领导意见」的按人已读标记：同一条意见会被主办/协办多人查看，
 * 「我读过了」是<b>按登录人</b>维度的状态，所以单独建表而不是在意见行上加标志位。
 *
 * <p>未读口径 = 本人承办案件里 {@code feedback_status} 为空【且】本表无
 * {@code (opinion_id, user_id)} 记录。给反馈（feedback）时顺带落一条已读——
 * 能写反馈说明必然读过。
 */
@Data
@TableName("case_opinion_read")
public class CaseOpinionRead implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 领导意见 ID */
    private Long opinionId;
    /** 阅读人（sys_user.id，登录人维度） */
    private Long userId;
    /** 阅读时间 */
    private LocalDateTime readAt;
}
