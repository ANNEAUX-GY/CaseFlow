package com.caseflow.dto;

import lombok.Data;

/**
 * 案件类别（小类）保存请求：caseType 所属大类 / name 小类名称 / sort 可选排序。
 */
@Data
public class CategorySaveRequest {
    private String caseType;
    private String name;
    private Integer sort;
}
