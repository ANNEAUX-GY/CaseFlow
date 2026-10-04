package com.caseflow.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * 增量建列（幂等）。
 *
 * <p>为什么需要它：{@code sql/schema.sql} 全是 {@code CREATE TABLE IF NOT EXISTS}，
 * 对**已存在**的表不会补新列；而 MySQL 的 {@code ALTER TABLE ... ADD COLUMN} 又不支持
 * {@code IF NOT EXISTS}，没法把升级语句写进同一份脚本里（H2 支持、MySQL 不支持）。
 * 于是把「缺哪列补哪列」的判断放到启动时做，两种方言通用，且可重复执行。
 *
 * <p>执行时机：Spring Boot 的 {@code spring.sql.init} 由 {@code InitializingBean} 驱动，
 * 在所有 {@code ApplicationRunner} 之前完成，所以这里运行时表一定已经建好了。
 *
 * <p>维护约定：以后往 {@code schema.sql} 加列时，同步在 {@link #NEW_COLUMNS} 登记一行即可。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaMigration implements ApplicationRunner {

    /** 表名 / 列名 / 列定义（不能含 NOT NULL 且无默认值，否则老数据行过不去） */
    private static final String[][] NEW_COLUMNS = {
            {"operation_log", "snapshot_before", "TEXT NULL"},
            {"operation_log", "snapshot_after", "TEXT NULL"},
            {"operation_log", "undone", "TINYINT NOT NULL DEFAULT 0"},
            {"operation_log", "undo_log_id", "BIGINT NULL"},
            {"operation_log", "undo_of", "BIGINT NULL"},
            // 账号体系：注册 + 审核 + 角色分级。
            // audit_status 必须给默认值 1，否则升级时已存在的账号（如 boss）会变成"待审核"而无法登录。
            {"sys_user", "phone", "VARCHAR(32) NULL"},
            {"sys_user", "dept", "VARCHAR(128) NULL"},
            {"sys_user", "apply_role", "VARCHAR(32) NULL"},
            {"sys_user", "audit_status", "TINYINT NOT NULL DEFAULT 1"},
            {"sys_user", "audit_remark", "VARCHAR(255) NULL"},
            {"sys_user", "audited_by", "BIGINT NULL"},
            {"sys_user", "audited_at", "DATETIME NULL"},
            // P0 反馈改进：案件字段模型扩充（案卷类型 / 立案登记表编号 / 调解书编号）。
            // case_suspect 新表由 schema.sql 的 CREATE TABLE IF NOT EXISTS 每次启动自动补建，无需登记。
            {"case_info", "case_type", "VARCHAR(16) NULL"},
            {"case_info", "filing_no", "VARCHAR(64) NULL"},
            {"case_info", "mediation_no", "VARCHAR(64) NULL"},
            // 案件盯办模块：强制措施 / 侦查进度
            {"case_info", "case_measure", "VARCHAR(16) NULL"},
            {"case_info", "measure_date", "DATETIME NULL"},
            {"case_info", "detain_deadline", "DATETIME NULL"},
            {"case_info", "investigation_status", "VARCHAR(20) NULL"},
            // 案件待办（to do）：佐证材料复用 case_file，用 todo_id 指向所属待办。
            // case_todo 新表由 schema.sql 的 CREATE TABLE IF NOT EXISTS 每次启动自动补建，无需登记。
            {"case_file", "todo_id", "BIGINT NULL"},
            // 账号必须关联员工：档案来源标记。注册时由本人自建的档案记为 SELF_REGISTER，
            // 审核界面据此提示管理员核对，避免别人冒名建档。
            {"org_employee", "origin", "VARCHAR(16) NULL"},
            // 领导意见（2026-10）：拖拽排序位次 / 落实截止时间 / 重要性分级。
            // 三列一律可空，兼容旧数据：
            //   sort_order NULL → 读取时按 id 升序回退，OpinionService 首次访问惰性初始化 1..N；
            //   importance NULL → 展示按 C（一般）；
            //   deadline  NULL → 无截止时间，紧急性判定为「正常」。
            // 紧急性不落库（由 deadline 与当前时间实时算），故无需新增列。
            {"case_leader_opinion", "sort_order", "INT NULL"},
            {"case_leader_opinion", "deadline", "DATETIME NULL"},
            {"case_leader_opinion", "importance", "VARCHAR(4) NULL"},
            // 阶段→环节→任务三层流程（2026-10）：
            // flow_stage 记案件走到哪个阶段（进度随之重置）；
            // case_plan 加 stage/step_key/task_key/is_std 把原来的「侦查计划」升格为「阶段任务」，
            // 存量行四列皆NULL，读时归入「初查/侦查」环节，不影响既有数据可见与统计。
            // is_std 默认 0：存量与民警自建都是 0，只有模板生成的任务是 1。
            {"case_info", "flow_stage", "VARCHAR(24) NULL"},
            {"case_plan", "stage", "VARCHAR(24) NULL"},
            {"case_plan", "step_key", "VARCHAR(32) NULL"},
            {"case_plan", "task_key", "VARCHAR(32) NULL"},
            {"case_plan", "is_std", "TINYINT NOT NULL DEFAULT 0"},
            // 民警端待办（2026-10-04）：补派生与列表展示所需字段。
            // case_todo 表本身在旧库里已存在（远程代码漏了建表语句，本次补进 schema.sql），
            // 这里只补列；新环境由 schema.sql 的 CREATE TABLE IF NOT EXISTS 一次建全。
            // 五列全部可空：urgency/importance 为 NULL 时读取归一为"一般"，
            // deadline 为 NULL 视为无期限，opinion_id 为 NULL 视为历史手工待办。
            {"case_todo", "opinion_id", "BIGINT NULL"},
            {"case_todo", "urgency", "VARCHAR(8) NULL"},
            {"case_todo", "importance", "VARCHAR(8) NULL"},
            {"case_todo", "dept_source", "VARCHAR(128) NULL"},
            {"case_todo", "deadline", "DATETIME NULL"},
            // 办案组别（2026-10-04）：初查组/清案组/不限。
            // org_employee 是指派校验的**权威来源**（账号可没绑员工档案）；
            // sys_user.police_group 是注册时自报 + 审核时确认的组别，
            // 员工档案补全后以 org_employee 为准。两列都可空，NULL 视为「不限」，
            // 存量账号/员工不选组也能继续用，只是不会被组别限制指派。
            {"org_employee", "police_group", "VARCHAR(16) NULL"},
            {"sys_user", "police_group", "VARCHAR(16) NULL"}
    };

    /** 旧数据回填：把历史「案件类型」自由文本里的大类词归位到 case_type（小类位清空）。
     *  幂等：case_type 已有值的行不会被碰到。H2 / MySQL 通用。 */
    private static final String[][] CATEGORY_MIGRATIONS = {
            {"'刑事'", "'CRIMINAL'"},
            {"'行政'", "'ADMINISTRATIVE'"},
            {"'初查'", "'PRELIMINARY'"}
    };

    /** 类别字典种子：首次（表空）时写入默认小类；「未立案」按需求不设小类 */
    private static final String[][] CATEGORY_SEEDS = {
            {"CRIMINAL", "电诈"}, {"CRIMINAL", "接触性诈骗"}, {"CRIMINAL", "故意伤害类"},
            {"CRIMINAL", "盗窃类"}, {"CRIMINAL", "其他"},
            {"ADMINISTRATIVE", "殴打他人"}, {"ADMINISTRATIVE", "财物损毁"}, {"ADMINISTRATIVE", "赌博"},
            {"ADMINISTRATIVE", "吸毒"}, {"ADMINISTRATIVE", "卖淫嫖娼"}, {"ADMINISTRATIVE", "扰乱公共秩序"},
            {"ADMINISTRATIVE", "噪音扰民"}, {"ADMINISTRATIVE", "其他治安"}
    };

    @Resource
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            Set<String> existing = existingColumns(conn);
            for (String[] c : NEW_COLUMNS) {
                String key = c[0].toLowerCase() + "." + c[1].toLowerCase();
                if (existing.contains(key)) {
                    continue;
                }
                String ddl = "ALTER TABLE " + c[0] + " ADD COLUMN " + c[1] + " " + c[2];
                try (Statement st = conn.createStatement()) {
                    st.execute(ddl);
                    log.info("[SchemaMigration] 已补列：{}", key);
                }
            }
            migrateCategory(conn, existing);
            removeCivilType(conn);
            seedCategories(conn, existing);
        } catch (Exception e) {
            // 不阻断启动：库里可能只有只读权限。缺列的表现是「撤回」功能不可用，
            // 报错信息会明确指出，不会静默损坏数据。
            log.error("[SchemaMigration] 增量建列失败，撤回功能可能不可用：{}。"
                    + "可手动执行：{}", e.getMessage(), describeAll());
        }
    }

    /** 分类体系升级回填：旧 category 存的是大类词（如"刑事"），迁到 case_type 后小类位清空 */
    private void migrateCategory(Connection conn, Set<String> existing) {
        if (!existing.contains("case_info.case_type") || !existing.contains("case_info.category")) {
            return;
        }
        for (String[] m : CATEGORY_MIGRATIONS) {
            String sql = "UPDATE case_info SET case_type=" + m[1]
                    + ", category=NULL WHERE case_type IS NULL AND category=" + m[0];
            try (Statement st = conn.createStatement()) {
                int n = st.executeUpdate(sql);
                if (n > 0) {
                    log.info("[SchemaMigration] 旧案件类别「{}」已归位到案卷类型 {}（{} 行）",
                            m[0].replace("'", ""), m[1].replace("'", ""), n);
                }
            } catch (Exception e) {
                log.warn("[SchemaMigration] 案件类别回填跳过：{}", e.getMessage());
            }
        }
    }

    /** 去除民事大类：存量 CIVIL 案件（含小类）清回未分类，交由用户重新归类 */
    private void removeCivilType(Connection conn) {
        if (!hasColumn(conn, "case_info", "case_type")) {
            return;
        }
        String sql = "UPDATE case_info SET case_type=NULL, category=NULL WHERE case_type='CIVIL'";
        try (Statement st = conn.createStatement()) {
            int n = st.executeUpdate(sql);
            if (n > 0) {
                log.info("[SchemaMigration] 民事大类已去除，{} 行案件清回未分类", n);
            }
        } catch (Exception e) {
            log.warn("[SchemaMigration] 民事去除跳过：{}", e.getMessage());
        }
    }

    /** 类别字典种子：仅当 case_category 表为空时写入默认小类（管理权限账户可后续增删改） */
    private void seedCategories(Connection conn, Set<String> existing) {
        if (!existing.contains("case_category.name")) {
            return; // 表未建（旧库第一次启动时 schema.sql 尚未执行到建表，下次启动再种）
        }
        try (Statement st = conn.createStatement()) {
            long count = 0;
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM case_category")) {
                if (rs.next()) {
                    count = rs.getLong(1);
                }
            }
            if (count > 0) {
                return;
            }
            int idx = 0;
            for (String[] s : CATEGORY_SEEDS) {
                idx++;
                st.addBatch("INSERT INTO case_category (case_type, name, sort) VALUES ('"
                        + s[0] + "', '" + s[1] + "', " + idx + ")");
            }
            int[] r = st.executeBatch();
            log.info("[SchemaMigration] 案件类别字典已种入 {} 条默认小类", r.length);
        } catch (Exception e) {
            log.warn("[SchemaMigration] 类别字典种子跳过：{}", e.getMessage());
        }
    }

    /** 一次性拉全库的 表.列 集合，避免逐个表拼大小写（MySQL 在 Windows 上大小写不定） */
    private Set<String> existingColumns(Connection conn) throws Exception {
        Set<String> set = new HashSet<>();
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getColumns(conn.getCatalog(), null, null, null)) {
            while (rs.next()) {
                set.add(rs.getString("TABLE_NAME").toLowerCase() + "." + rs.getString("COLUMN_NAME").toLowerCase());
            }
        }
        return set;
    }

    private boolean hasColumn(Connection conn, String table, String column) {
        try {
            return existingColumns(conn).contains(table.toLowerCase() + "." + column.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    private String describeAll() {
        StringBuilder sb = new StringBuilder();
        for (String[] c : NEW_COLUMNS) {
            sb.append("ALTER TABLE ").append(c[0]).append(" ADD COLUMN ").append(c[1]).append(' ')
                    .append(c[2]).append("; ");
        }
        return sb.toString().trim();
    }
}
