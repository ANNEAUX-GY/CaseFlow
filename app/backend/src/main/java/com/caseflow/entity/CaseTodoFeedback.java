package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 待办反馈记录（2026-10-04）：某条待办（主任务或子任务）的每一次反馈留痕。
 *
 * <p><b>为什么不用 case_todo.remark</b>：remark 是单字段覆盖式的，
 * 每次反馈冲掉上一条。而用户要的是「展示该任务的<b>全部</b>反馈记录」，
 * 所以改为累积式——每次提交反馈或变更状态都追加一行。
 *
 * <p>状态快照 {@code statusAt} 记的是反馈当时的值而非当前值：
 * 任务后来从"进行中"变成"已完成"，历史记录仍应显示它当时是进行中。
 *
 * <p>不进快照体系：与 case_todo 一致（待办本身也不可撤回）。
 */
@Data
@TableName("case_todo_feedback")
public class CaseTodoFeedback implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属待办（主任务或子任务） */
    private Long todoId;
    /** 案件 ID（冗余，便于按案件清理与查询） */
    private Long caseId;
    /** 反馈内容 */
    private String content;
    /** 反馈时的状态快照：DONE / IN_PROGRESS / PENDING */
    private String statusAt;
    private Long creatorId;
    /** 记录人姓名（冗余展示） */
    private String creatorName;
    private LocalDateTime createdAt;
}
