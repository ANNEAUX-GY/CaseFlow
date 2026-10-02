package com.caseflow.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * EasyExcel 读取监听：把行数据收集到内存（员工图谱规模通常 < 1w 行，足够）。
 */
public class EmployeeExcelListener extends AnalysisEventListener<EmployeeExcelRow> {

    private final List<EmployeeExcelRow> rows = new ArrayList<>();
    private int skippedEmpty = 0;

    @Override
    public void invoke(EmployeeExcelRow row, AnalysisContext context) {
        if (row == null) {
            return;
        }
        String name = row.getName() == null ? null : row.getName().trim();
        if (name == null || name.isEmpty()) {
            skippedEmpty++;
            return;
        }
        row.setName(name);
        row.setEmployeeNo(trim(row.getEmployeeNo()));
        row.setParentNo(trim(row.getParentNo()));
        row.setDept(trim(row.getDept()));
        row.setTitle(trim(row.getTitle()));
        row.setPhone(trim(row.getPhone()));
        row.setEmail(trim(row.getEmail()));
        rows.add(row);
    }

    private String trim(String s) {
        return s == null ? null : s.trim();
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // no-op
    }

    @Override
    public void onException(Exception exception, AnalysisContext context) {
        // 单行解析异常不阻断整份文件
    }

    public List<EmployeeExcelRow> getRows() {
        return rows;
    }

    public int getSkippedEmpty() {
        return skippedEmpty;
    }
}
