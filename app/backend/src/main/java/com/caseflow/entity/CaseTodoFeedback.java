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
 * <p><b>上传声明三要素独立成列（2026-10-08）</b>：原先「于 X 在 Y 上传了 Z。」
 * 是拼进 {@code content} 的一句话。用户要求能改「上传平台 / 上传文件名」，
 * 拼在一句话里就没法单独改其中一项——只能整句重写。
 * 现在 {@code content} 只放落实说明，声明拆成
 * {@code uploadTime/uploadPlatform/uploadFile} 三列，各改各的互不干扰。
 * 存量数据由 {@code SchemaMigration} 回填解析，读侧还有一次兜底解析。
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
    /** 落实说明（不含上传声明句——声明已拆到下面三列） */
    private String content;
    /** 反馈时的状态快照：DONE / IN_PROGRESS / PENDING */
    private String statusAt;
    /** 上传时间（文本，精确到秒）：声明里的「于 ____ 上传了」 */
    private String uploadTime;
    /** 上传平台：声明里的「在 ____ 上传了」 */
    private String uploadPlatform;
    /** 上传文件名：声明里的「上传了 ____」 */
    private String uploadFile;
    private Long creatorId;
    /** 记录人姓名（冗余展示） */
    private String creatorName;
    private LocalDateTime createdAt;

    // ---- 修订痕迹（2026-10-08）：改反馈不覆盖首次提交人，另存谁在何时改过 ----
    private Long editedBy;
    private String editedByName;
    private LocalDateTime editedAt;
}
