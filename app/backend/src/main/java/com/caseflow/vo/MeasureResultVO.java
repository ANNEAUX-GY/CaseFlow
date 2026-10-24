package com.caseflow.vo;

import com.caseflow.entity.CaseInfo;
import lombok.Data;

/**
 * 强制措施登记/变更的结果（2026-10-24）。
 *
 * <p>为什么要单独一个返回体：从「当前措施换到下一个措施」现在分两种结局——
 * 领导（全权限）操作直接生效；承办人操作只生成待审批申请，措施**不变**。
 * 前端必须能区分这两种结局，否则会误报"已登记"而实际还在等审批。
 */
@Data
public class MeasureResultVO {

    /** true = 已生成待审批申请，案件措施未变；false = 已直接生效 */
    private boolean pending;

    /** 待审批申请 ID（pending=true 时有值） */
    private Long approvalId;

    /** 变更后（或申请变更）的措施代码 */
    private String measure;

    /** 措施中文名，前端直接提示用 */
    private String measureName;

    /** 提示语：给用户看的一句话 */
    private String message;

    /** 变更后的案件（pending=true 时是原样未改的案件） */
    private CaseInfo caseInfo;

    public static MeasureResultVO applied(CaseInfo c, String measure, String measureName) {
        MeasureResultVO v = new MeasureResultVO();
        v.pending = false;
        v.measure = measure;
        v.measureName = measureName;
        v.caseInfo = c;
        v.message = "已登记强制措施：" + measureName;
        return v;
    }

    public static MeasureResultVO pending(Long approvalId, CaseInfo c, String measure, String measureName) {
        MeasureResultVO v = new MeasureResultVO();
        v.pending = true;
        v.approvalId = approvalId;
        v.measure = measure;
        v.measureName = measureName;
        v.caseInfo = c;
        v.message = "变更申请已提交，待领导批准后生效：" + measureName;
        return v;
    }
}
