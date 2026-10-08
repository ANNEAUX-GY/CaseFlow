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

    /** 办案组别 INITIAL初查组/CLEAR清案组/NONE不限 */
    private String policeGroup;
    /** 组别中文名（前端直接展示，省得各端各写映射） */
    private String policeGroupName;
    private String phone;
    private String email;
    private Integer levelNo;
    private Integer sortNo;
    private Integer status;

    /**
     * 组织层级 1总/2副总/3组长/4员工，由职务推导（见 flow/OrgRank）。
     * 前端按它上色与分组，不再靠「在树里的第几层」猜身份。
     */
    private Integer rank;
    /** 层级中文名：总/领导、副总、组长、员工 */
    private String rankLabel;

    /** 没填部门。部门是下拉选的，没选说明这条档案还不完整，界面上要标出来 */
    private Boolean deptMissing = false;
    /** 整个部门只有他一个人（独立部门），多半是自己随手填的，界面上要标出来 */
    private Boolean deptAlone = false;
    /** deptMissing || deptAlone：组织树里用另一种颜色标出，等管理员补正 */
    private Boolean deptAnomaly = false;

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
