package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 强制措施到期提醒（2026-10-24 用户要求：到期前在工作台总览提示）。
 *
 * <p>措施到期不是自动清除数据：措施一旦登记就是办案事实，到期只代表"该措施不再有效"，
 * 由领导或承办人登记下一个措施才算真正结束。所以这里只给状态，不改库。
 */
@Data
public class MeasureAlertVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long caseId;
    private String caseNo;
    private String caseName;
    /** 案卷类型（CRIMINAL / ADMINISTRATIVE）：前端据此判断拘捕是否必须填执行日期 */
    private String caseType;
    /** 措施代码（DETENTION / ARREST / BAIL / RESIDENCE / SUMMONS） */
    private String measure;
    private String measureName;
    /** 措施起算日 */
    private LocalDateTime measureDate;
    /** 期限届满日（= 下一个节点） */
    private LocalDateTime deadline;
    /** 距届满还有几天：负数表示已过期天数，0 表示今天到期 */
    private long daysLeft;
    /** 是否已过期（到期即视为该措施结束，等待登记下一个措施） */
    private boolean expired;
}
