package com.caseflow.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 案件 Excel 行模板（2026-10-14）。
 *
 * <p>列顺序与用户给定的字段清单一一对应，共 14 列：
 * id / 案件编号 / 案件名称 / 发案地点 / 承办单位 / 主办人 / 案件状态 /
 * 嫌疑人身份证号 / 嫌疑人 / 受理时间 / 处罚时间 / 案件类型 / 发案时间 / 简要案情。
 *
 * <p><b>为什么时间列一律用 String 收</b>：源系统导出的时间列格式很杂
 * （"2026-01-15"、"2026/1/5 10:30"、Excel 序列号、甚至 Date.toString() 形态都有），
 * 声明成 LocalDateTime 会让 EasyExcel 在格式不符时整行抛异常。收成 String 后
 * 由 {@code CaseService} 用一组宽松 pattern 逐个试，解析不了记 error、不阻断导入。
 */
@Data
public class CaseExcelRow {

    /** 源系统主键：只做留存对账，不参与本系统业务 */
    @ExcelProperty(value = "id", index = 0)
    private String id;

    /** 案件编号：本系统的 case_no，导入幂等键（已存在则更新） */
    @ExcelProperty(value = "案件编号", index = 1)
    private String caseNo;

    @ExcelProperty(value = "案件名称", index = 2)
    private String name;

    @ExcelProperty(value = "发案地点", index = 3)
    private String occurPlace;

    @ExcelProperty(value = "承办单位", index = 4)
    private String undertakeUnit;

    /** 主办人：按姓名匹配员工档案，命中才建指派关系 */
    @ExcelProperty(value = "主办人", index = 5)
    private String ownerName;

    /** 案件状态：中文宽松映射（见 CaseService#mapStatus），如 已办结/办理中/待指派 */
    @ExcelProperty(value = "案件状态", index = 6)
    private String statusText;

    @ExcelProperty(value = "嫌疑人身份证号", index = 7)
    private String suspectIdCard;

    @ExcelProperty(value = "嫌疑人", index = 8)
    private String suspectName;

    @ExcelProperty(value = "受理时间", index = 9)
    private String acceptTime;

    @ExcelProperty(value = "处罚时间", index = 10)
    private String punishTime;

    /** 案件类型：刑事 / 行政 / 其他（映射到 case_type） */
    @ExcelProperty(value = "案件类型", index = 11)
    private String caseTypeText;

    @ExcelProperty(value = "发案时间", index = 12)
    private String occurTime;

    @ExcelProperty(value = "简要案情", index = 13)
    private String description;
}
