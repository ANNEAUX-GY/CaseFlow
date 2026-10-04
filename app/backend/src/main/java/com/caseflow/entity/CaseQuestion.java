package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 疑问问答（2026-10-04）：普通员工办任务遇到不懂的在此提问，管理层回答。
 *
 * <p><b>独立于待办与任务</b>：不派生待办、不影响任何完成规则、不进反馈流，
 * 只是一问一答的沟通记录。todo_id 可空，仅记录提问时所在任务的上下文（展示用）；
 * 删除任务不级联删问答（沟通记录要留档，TODO 外键故意不设）。
 */
@Data
@TableName("case_question")
public class CaseQuestion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    /** 提问时所在的任务 ID（仅上下文备注，无联动） */
    private Long todoId;
    /** 问题内容 */
    private String content;
    /** 管理层的回答；NULL=尚未回答 */
    private String answer;
    private Long answerBy;
    private String answerByName;
    private LocalDateTime answeredAt;
    private Long questionBy;
    private String questionByName;
    private LocalDateTime createdAt;
}
