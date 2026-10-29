package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作记录本（2026-10-29）：每个民警记录自己具体做了什么工作，当个人工作记事本用。
 *
 * <p><b>权限</b>：普通民警只能看、只能写自己名下（employee_id 匹配）的记录；
 * 其余角色（所长 / 副所长 / 队长 / 法制员 / 研判员 / 系统管理员）都可检索查看任意民警的记录。
 */
@Data
@TableName("work_log")
public class WorkLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 记录所属民警的员工档案 ID（org_employee.id，权威归属） */
    private Long employeeId;
    /** 记录所属民警姓名（冗余展示，避免每次列表都反查员工表） */
    private String employeeName;
    /** 记录人账号 ID（用于撤回/审计，通常等于本人，但也可能领导代录） */
    private Long creatorId;
    /** 工作内容 */
    private String content;
    /** 关联案件 ID（可空：纯个人记事可不挂案件） */
    private Long caseId;
    /** 关联案件名称（冗余，便于列表直接展示，案件改名后不追溯） */
    private String caseName;
    /** 记录时间 */
    private LocalDateTime createdAt;
    /** 最后修改时间 */
    private LocalDateTime updatedAt;
}
