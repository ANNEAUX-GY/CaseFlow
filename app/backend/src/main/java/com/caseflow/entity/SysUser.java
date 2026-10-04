package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录账号。
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** BCrypt 哈希；历史明文密码在登录成功时会被自动升级 */
    private String password;
    private String displayName;
    /** CHIEF 所长 / DEPUTY_CHIEF 副所长 / LAW_OFFICER 法制员 / STAFF 普通民警 / BOSS 系统管理员 */
    private String role;
    private Long employeeId;
    private String phone;
    private String dept;
    /** 注册时申请的角色，审核时可调整 */
    private String applyRole;

    /** 办案组别：注册时必选；NULL=未指定。员工档案补全后以 org_employee 为准 */
    private String policeGroup;
    /** 0 待审核 / 1 已通过 / 2 已驳回 */
    private Integer auditStatus;
    private String auditRemark;
    private Long auditedBy;
    private LocalDateTime auditedAt;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
