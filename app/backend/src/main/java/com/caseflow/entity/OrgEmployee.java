package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 员工图谱节点：领导 - 副领导 - 组长 - 组员。
 * 通过 Excel 导入，parent_no -> parent_id 二次回填。
 */
@Data
@TableName("org_employee")
public class OrgEmployee implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String employeeNo;
    private Long parentId;
    private String parentNo;
    private String idPath;
    private String dept;
    private String title;

    /** 办案组别：INITIAL初查组 / CLEAR清案组 / NULL=不限（指派校验的权威来源） */
    private String policeGroup;
    private String phone;
    private String email;
    private Integer levelNo;
    private Integer sortNo;
    private Integer status;
    /** 档案来源：IMPORT 批量导入 / MANUAL 后台新增 / SELF_REGISTER 注册时本人自建 */
    private String origin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
