package com.caseflow.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * EasyExcel 读取监听：把案件行数据收集到内存（与 {@link EmployeeExcelListener} 同一套路）。
 *
 * <p>空行判定用「案件名称 + 案件编号都为空」——案件导入里只有案件名称是业务必填项，
 * 编号可以为空（导入时自动生成），所以任一个有值就不算空行。
 */
public class CaseExcelListener extends AnalysisEventListener<CaseExcelRow> {

    private final List<CaseExcelRow> rows = new ArrayList<>();
    private int skippedEmpty = 0;

    @Override
    public void invoke(CaseExcelRow row, AnalysisContext context) {
        if (row == null) {
            return;
        }
        String name = trim(row.getName());
        String caseNo = trim(row.getCaseNo());
        if ((name == null || name.isEmpty()) && (caseNo == null || caseNo.isEmpty())) {
            skippedEmpty++;
            return;
        }
        row.setName(name);
        row.setCaseNo(caseNo);
        row.setId(trim(row.getId()));
        row.setOccurPlace(trim(row.getOccurPlace()));
        row.setUndertakeUnit(trim(row.getUndertakeUnit()));
        row.setOwnerName(trim(row.getOwnerName()));
        row.setStatusText(trim(row.getStatusText()));
        row.setSuspectIdCard(trim(row.getSuspectIdCard()));
        row.setSuspectName(trim(row.getSuspectName()));
        row.setAcceptTime(trim(row.getAcceptTime()));
        row.setPunishTime(trim(row.getPunishTime()));
        row.setCaseTypeText(trim(row.getCaseTypeText()));
        row.setOccurTime(trim(row.getOccurTime()));
        row.setDescription(trim(row.getDescription()));
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
        // 单行解析异常不阻断整份文件（与员工导入一致）
    }

    public List<CaseExcelRow> getRows() {
        return rows;
    }

    public int getSkippedEmpty() {
        return skippedEmpty;
    }
}
