package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * Excel 导入结果。
 */
@Data
public class ImportResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private int total;
    private int success;
    private int failed;
    private java.util.List<String> errors;
}
