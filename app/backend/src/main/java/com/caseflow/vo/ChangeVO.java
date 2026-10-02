package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 一条「变更明细」：某字段从什么变成了什么。前端按行渲染成表格。
 */
@Data
public class ChangeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 字段标识（name / deadline / owner ...） */
    private String field;
    /** 字段中文名 */
    private String label;
    /** 变更前（已格式化为可读文本） */
    private String before;
    /** 变更后（已格式化为可读文本） */
    private String after;
    /** ADD=新增 REMOVE=移除 UPDATE=修改 */
    private String type;
}
