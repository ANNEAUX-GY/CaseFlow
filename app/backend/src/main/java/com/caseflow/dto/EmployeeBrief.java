package com.caseflow.dto;

import lombok.Data;

/**
 * 「就地建档」用的最小员工信息。
 *
 * <p>场景：注册时系统里还没有申请人的员工档案，或管理员在账号弹窗里不想跳去员工图谱，
 * 于是由 {@code AuthService.register} / {@code UserService.create} 直接连档带号一起建出来。
 *
 * <p>字段比 {@link EmployeeSaveRequest} 少，因为匿名注册者不该也不能决定工号编排、排序、状态这些东西。
 */
@Data
public class EmployeeBrief {

    /** 姓名，必填 */
    private String name;

    /** 工号，选填（已废弃，改用 policeSystemNo） */
    private String employeeNo;

    /** 警号（警综人员编号），选填 */
    private String policeSystemNo;

    /** 部门（注册页默认带出「所属部门」） */
    private String dept;

    /** 职务：所长 / 副所长 / 队长 / 法制员 / 案管 / 研判员 / 普通民警 / 系统管理员 */
    private String title;

    /** 上级员工 ID，0 或留空 = 顶层；管理员后续可在员工图谱里调整 */
    private Long parentId;

    private String phone;
}
