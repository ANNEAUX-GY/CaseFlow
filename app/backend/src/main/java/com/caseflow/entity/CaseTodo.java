package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 案件待办（to do）：管理员指派案件时添加的若干备注项，办案人逐项完成。
 *
 * <p>与侦查计划（{@link CasePlan}）的区别：待办强调「完成必须有凭据」——
 * 标记完成前必须先上传至少一份佐证材料（case_file.todo_id 指向本条），
 * 由 {@code TodoService.done} 强制校验。
 */
@Data
@TableName("case_todo")
public class CaseTodo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    /** 待办内容（如：调取银行流水、制作询问笔录） */
    private String content;
    /** PENDING 待办 / DONE 已完成 */
    private String status;
    /** 排序，小的在前 */
    private Integer sort;
    private LocalDateTime doneAt;
    /** 完成人（账号 ID） */
    private Long doneBy;
    /** 完成说明（选填） */
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
