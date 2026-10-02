package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 案件类别字典（小类 / 案由）：挂在大类（案卷类型）之下，由管理权限账户维护。
 * 前端「案件分类」级联选择器据此构建大类 → 小类树。
 */
@Data
@TableName("case_category")
public class CaseCategory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属大类：CRIMINAL 刑事 / ADMINISTRATIVE 行政 / PRELIMINARY 未立案 */
    private String caseType;
    /** 小类名称（如：电诈、殴打他人） */
    private String name;
    /** 同级排序 */
    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
