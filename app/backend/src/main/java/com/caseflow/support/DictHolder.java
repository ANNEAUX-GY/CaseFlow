package com.caseflow.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一的数据字典：code -> 中文名。
 * 前端无需自行维护枚举，减少前后端不一致。
 */
@Slf4j
@Component
public class DictHolder {

    private static final Map<String, Map<String, String>> DICT = new HashMap<>();

    static {
        DICT.put("SOURCE_TYPE", map("MANUAL", "手工录入", "PDF", "PDF 文书", "WORD", "Word 文档", "EXCEL", "Excel 台账"));
        DICT.put("PRIORITY", map("URGENT", "特急", "HIGH", "紧急", "NORMAL", "普通", "LOW", "低"));
        // 案卷类型（大类）：未立案 / 刑事 / 行政
        DICT.put("CASE_TYPE", map("PRELIMINARY", "未立案", "CRIMINAL", "刑事",
                "ADMINISTRATIVE", "行政"));
        // 侦查进度（案件盯办模块）
        DICT.put("INVESTIGATION_STATUS", map("PENDING_INITIAL", "待初查", "INVESTIGATING", "侦查中",
                "PENDING_APPROVAL", "待审批", "INVESTIGATION_DONE", "侦查终结"));
        // 强制措施（案件盯办模块；2026-10-09 补齐法定五种：拘传 / 取保候审 / 监视居住 / 拘留 / 逮捕）
        // 归类见 PoliceGroup#moduleOfMeasure：拘传→初查；拘留、逮捕→刑拘在办；取保、监居→取保及监居。
        DICT.put("CASE_MEASURE", map("NONE", "无", "SUMMONS", "拘传", "DETENTION", "拘留",
                "ARREST", "逮捕", "BAIL", "取保候审", "RESIDENCE", "监视居住"));
        DICT.put("GENDER", map("MALE", "男", "FEMALE", "女"));
        DICT.put("STATUS", map("PENDING_ASSIGN", "待指派", "ASSIGNED", "已指派", "IN_PROGRESS", "处理中",
                "DONE", "已办结", "CANCELLED", "已撤销"));
        DICT.put("ASSIGN_ROLE", map("OWNER", "主办", "MEMBER", "协办"));
        DICT.put("EMP_STATUS", map("1", "在职", "0", "停用"));
        DICT.put("ROLE", map("CHIEF", "所长", "DEPUTY_CHIEF", "副所长",
                "LAW_OFFICER", "法制员", "STAFF", "普通民警", "BOSS", "系统管理员"));
        DICT.put("AUDIT_STATUS", map("0", "待审核", "1", "已通过", "2", "已驳回"));
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    public static String name(String type, String code) {
        Map<String, String> m = DICT.get(type);
        if (m == null || code == null) {
            return null;
        }
        return m.getOrDefault(code, code);
    }

    public static Map<String, Map<String, String>> all() {
        return DICT;
    }

    /**
     * 计算到期等级：OVERDUE=已逾期 / TODAY=今天到期 / SOON=3天内 / NORMAL=尚有余量 / NONE=未设置期限。
     */
    public static String dueLevel(LocalDateTime deadline, LocalDateTime now) {
        if (deadline == null) {
            return "NONE";
        }
        long days = ChronoUnit.DAYS.between(now.toLocalDate(), deadline.toLocalDate());
        if (deadline.isBefore(now)) {
            return "OVERDUE";
        }
        if (days == 0) {
            return "TODAY";
        }
        if (days <= 3) {
            return "SOON";
        }
        return "NORMAL";
    }

    public static Integer daysLeft(LocalDateTime deadline, LocalDateTime now) {
        if (deadline == null) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(now.toLocalDate(), deadline.toLocalDate());
    }
}
