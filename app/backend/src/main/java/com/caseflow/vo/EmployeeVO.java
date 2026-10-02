package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 员工树节点（同时承载搜索结果：命中节点打上 matched 标记并保留 path 文本）。
 */
@Data
public class EmployeeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String employeeNo;
    private Long parentId;
    private String dept;
    private String title;
    private String phone;
    private String email;
    private Integer levelNo;
    private Integer sortNo;
    private Integer status;

    /** 档案来源：IMPORT / MANUAL / SELF_REGISTER（注册时本人自建，需管理员核对） */
    private String origin;

    /** "王总 > 李副总 > 张组长 > 小明" */
    private String pathName;

    /** 该员工及其子树当前在手案件数 */
    private Integer activeCaseCount;

    /** 搜索命中标记 */
    private Boolean matched = false;

    private List<EmployeeVO> children = new ArrayList<>();
}
