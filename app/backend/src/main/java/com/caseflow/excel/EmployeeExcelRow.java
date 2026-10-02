package com.caseflow.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 员工图谱 Excel 行模板。
 * 列顺序固定：姓名 / 工号 / 上级工号 / 部门 / 职务 / 手机 / 邮箱
 * 其中「上级工号」是形成 领导-副领导-组长-组员 层级的关键字段，顶层留空。
 */
@Data
public class EmployeeExcelRow {

    @ExcelProperty(value = "姓名", index = 0)
    private String name;

    @ExcelProperty(value = "工号", index = 1)
    private String employeeNo;

    @ExcelProperty(value = "上级工号", index = 2)
    private String parentNo;

    @ExcelProperty(value = "部门", index = 3)
    private String dept;

    @ExcelProperty(value = "职务", index = 4)
    private String title;

    @ExcelProperty(value = "手机", index = 5)
    private String phone;

    @ExcelProperty(value = "邮箱", index = 6)
    private String email;
}
