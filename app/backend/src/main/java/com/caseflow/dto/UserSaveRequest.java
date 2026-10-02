package com.caseflow.dto;

import lombok.Data;

/**
 * 账号新增/修改请求（管理员操作）。
 * 审核通过时也可复用：{@code role} 为最终确认的角色。
 */
@Data
public class UserSaveRequest {

    private String username;

    /** 仅新建或重置密码时使用，明文，入库前 BCrypt */
    private String password;

    private String displayName;

    private String role;

    /**
     * 关联的员工档案 ID。
     * <ul>
     *   <li>新建账号 / 审核通过：<b>必填</b>，或改为提交 {@link #newEmployee} 现场建档；</li>
     *   <li>修改账号：传了就是改绑（会校验唯一性），留空表示「维持原绑定不动」。</li>
     * </ul>
     */
    private Long employeeId;

    /** 现场新建的员工档案（组织树里还没有这个人时的兜底），与 {@link #employeeId} 二选一 */
    private EmployeeBrief newEmployee;

    private String phone;

    private String dept;

    /** 审核意见 / 驳回原因 */
    private String remark;

    /** 启用状态 1/0 */
    private Integer status;
}
