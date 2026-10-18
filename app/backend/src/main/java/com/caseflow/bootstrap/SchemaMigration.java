package com.caseflow.bootstrap;

import com.caseflow.service.FeedbackDeclare;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
            // 子任务（2026-10-04）：NULL=顶层主任务，非空=挂在该 id 下。
            // 存量任务全部为 NULL，行为不变，无需回填。
            {"case_todo", "parent_id", "BIGINT NULL"},
            // 回答修订痕迹（2026-10-08）：管理层答错可改答，但**不覆盖**首次回答人/时间
            // （answerBy/answeredAt 保持原样），另存三列记录谁在何时改过，责任可追溯。
            // 存量数据全为 NULL = 从未修订，界面不显示修订标记。
            // 【已下线 2026-10-13】以下两列属疑问问答功能，表已无任何代码写入，
            // 保留登记只是为了让老库补齐后与 schema.sql 结构一致（数据留档，不删列）。
            {"case_question", "answer_edited_by", "BIGINT NULL"},
            {"case_question", "answer_edited_by_name", "VARCHAR(64) NULL"},
            {"case_question", "answer_edited_at", "DATETIME NULL"},
            // 通知定位锚点（2026-10-08）：业务侧埋点时告知目标对象ID，
            // 点通知可直达待办详情/子任务，而不只跳案件详情页。
            // 存量通知三列皆 NULL = 降级为案件级定位，行为不变。
            {"case_notification", "anchor_todo_id", "BIGINT NULL"},
            {"case_notification", "anchor_question_id", "BIGINT NULL"},
            {"case_notification", "anchor_subtask_id", "BIGINT NULL"},
            // 办案组别（2026-10-04）：初查组/清案组/不限。
            // org_employee 是指派校验的**权威来源**（账号可没绑员工档案）；
            // sys_user.police_group 是注册时自报 + 审核时确认的组别，
            // 员工档案补全后以 org_employee 为准。两列都可空，NULL 视为「不限」，
            // 存量账号/员工不选组也能继续用，只是不会被组别限制指派。
            {"org_employee", "police_group", "VARCHAR(16) NULL"},
            {"sys_user", "police_group", "VARCHAR(16) NULL"},
            // 上传声明三要素 + 修订痕迹（2026-10-08）：
            // 原先「于 X 在 Y 上传了 Z。」是拼进 content 的一句话，用户要求能单独改
            // 「上传平台 / 上传文件名」，拼在一句里就只能整句重写，故拆成独立列。
            // 存量数据由 backfillFeedbackDeclare() 解析回填（幂等：只补 upload_platform 为 NULL 的行）。
            // 六列全部可空：NULL = 该反馈没有上传声明 / 从未修订，行为与原来一致。
            {"case_todo_feedback", "upload_time", "VARCHAR(32) NULL"},
            {"case_todo_feedback", "upload_platform", "VARCHAR(128) NULL"},
            {"case_todo_feedback", "upload_file", "VARCHAR(255) NULL"},
            {"case_todo_feedback", "edited_by", "BIGINT NULL"},
            {"case_todo_feedback", "edited_by_name", "VARCHAR(64) NULL"},
            {"case_todo_feedback", "edited_at", "DATETIME NULL"},
            // 期限节点自重定义 + 一键重点关注（2026-10-09）：
            //   deadline_label：截止期限叫什么由办案人自己填（受案时间、变更羁押期限时间…），
            //                   不再固定叫「截止期限」；
            //   remind_days：   该节点提前多少天提醒（NULL=不提醒）；
            //   focus：         重点关注标记。NOT NULL DEFAULT 0 —— 存量行一律补 0，
            //                   读取时前端按 0 处理，不额外判空。
            {"case_info", "deadline_label", "VARCHAR(64) NULL"},
            {"case_info", "remind_days", "INT NULL"},
            {"case_info", "focus", "TINYINT NOT NULL DEFAULT 0"},
            // 办案阶段收敛到「案件待办」（2026-10-12）：
            // 原先阶段进度算在 case_plan（阶段→环节→标准任务）上，等于和「案件待办」
            // 并行维护两套任务清单，用户要求删掉那套。现在判据统一落在 case_todo：
            // 加 flow_stage 记录这条待办属于哪个阶段，NULL=存量按案件当前阶段处理。
            {"case_todo", "flow_stage", "VARCHAR(24) NULL"},
            // 案件 Excel 导入（2026-10-14）：源系统 14 个字段里，有 5 个在库里没有落脚点，
            // 一并补列。六列全部可空——存量案件没有这些信息，读取时不判空也不炸：
            //   occur_place    发案地点；
            //   occur_time     发案时间；
            //   undertake_unit 承办单位（分局/大队一级，与「主办人」的员工档案互补）；
            //   accept_time    受理时间；
            //   punish_time    处罚时间；
            //   external_id    源系统主键，导入时原样留存，用于与旧系统对账溯源。
            {"case_info", "occur_place", "VARCHAR(255) NULL"},
            {"case_info", "occur_time", "DATETIME NULL"},
            {"case_info", "undertake_unit", "VARCHAR(128) NULL"},
            {"case_info", "accept_time", "DATETIME NULL"},
            // 案件时间（2026-10-18）：列表「案件时间」列与排序的权威列。
            // 已受案取 accept_time、未受案取报案时间；存量行由 backfillCaseTime 回填。
            {"case_info", "case_time", "DATETIME NULL"},
            {"case_info", "punish_time", "DATETIME NULL"},
            {"case_info", "external_id", "VARCHAR(32) NULL"},
            // 老库的 case_deadline_state 可能建于行政/传讯节点加入之前；逐列补齐。
            {"case_deadline_state", "detention_term", "VARCHAR(8) NULL"},
            {"case_deadline_state", "paused_at", "DATETIME NULL"},
            {"case_deadline_state", "paused_deadline", "DATETIME NULL"},
            {"case_deadline_state", "paused_seconds", "BIGINT NOT NULL DEFAULT 0"},
            {"case_deadline_state", "pretrial_received_at", "DATETIME NULL"},
            {"case_deadline_state", "legal_received_at", "DATETIME NULL"},
            {"case_deadline_state", "pretrial_sent_at", "DATETIME NULL"},
            {"case_deadline_state", "legal_sent_at", "DATETIME NULL"},
            {"case_deadline_state", "submitted_at", "DATETIME NULL"},
            {"case_deadline_state", "last_summons_at", "DATETIME NULL"},
            {"case_deadline_state", "admin_30_doc_at", "DATETIME NULL"},
            {"case_deadline_state", "admin_extended_at", "DATETIME NULL"},
            {"case_deadline_state", "admin_60_doc_at", "DATETIME NULL"},
            {"case_deadline_state", "admin_extended90_at", "DATETIME NULL"},
            {"case_deadline_state", "filing_at", "DATETIME NULL"},
            {"case_deadline_state", "arrest_target_at", "DATETIME NULL"},
            {"case_deadline_state", "admin_check_3_at", "DATETIME NULL"},
            {"case_deadline_state", "admin_check_10_at", "DATETIME NULL"},
            {"case_deadline_state", "admin_check_20_at", "DATETIME NULL"},
            {"case_deadline_state", "updated_at", "DATETIME NULL"}
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
            ensureColumns(conn, existing, null);
            backfillCaseTime(conn);
            migrateLegacyDeadlineReceipts(conn, existing);
            migrateCategory(conn, existing);
            removeCivilType(conn);
            seedCategories(conn, existing);
            backfillFeedbackDeclare(conn, existing);
            retireStdPlanTasks(conn, existing);
            migrateSingleDeadlineToTimeNodes(conn, existing);
        } catch (Exception e) {
            // 不阻断启动：库里可能只有只读权限。缺列的表现是「撤回」功能不可用，
            // 报错信息会明确指出，不会静默损坏数据。
            log.error("[SchemaMigration] 增量建列失败，撤回功能可能不可用：{}。"
                    + "可手动执行：{}", e.getMessage(), describeAll());
        }
    }

    /** 仅添加当前表缺少的列；tableFilter 供旧库回归测试使用。
     *
     * <p>单列失败不中断其余列：老库可能存在个别表缺失（手工建表漏了一张、
     * 或中间版本删过表）。若一遇错就整体抛出，后面几十列连同其余迁移步骤
     * 都会一起跳过，问题会被放大（曾因 case_question 缺表导致 60+ 列未补、
     * 类别字典未种入）。这里逐列兜住，末尾汇总为一条 WARN，既不静默也不扩大故障面。
     */
    void ensureColumns(Connection conn, Set<String> existing, String tableFilter) throws Exception {
        List<String> failed = new ArrayList<>();
        for (String[] c : NEW_COLUMNS) {
            if (tableFilter != null && !tableFilter.equals(c[0])) continue;
            String key = c[0].toLowerCase() + "." + c[1].toLowerCase();
            if (existing.contains(key)) continue;
            String ddl = "ALTER TABLE " + c[0] + " ADD COLUMN " + c[1] + " " + c[2];
            try (Statement st = conn.createStatement()) {
                st.execute(ddl);
                log.info("[SchemaMigration] 已补列：{}", key);
            } catch (Exception e) {
                failed.add(key + "（" + e.getMessage() + "）");
            }
        }
        if (!failed.isEmpty()) {
            log.warn("[SchemaMigration] {} 列未能补齐，通常是这些表不存在，"
                    + "请确认建表脚本已完整执行：{}", failed.size(), failed);
        }
    }

    /**
     * 回填 case_time（2026-10-18）：存量案件没有这一列值时，用「受理时间优先、否则创建时间」补齐。
     *
     * <p>只补 NULL 行，可重复执行。列表的「案件时间」列与默认排序都读这一列，
     * 不回填会让老案件排到最后/显示空白。
     */
    void backfillCaseTime(Connection conn) {
        try {
            if (!hasColumn(conn, "case_info", "case_time")) {
                return;
            }
            try (Statement st = conn.createStatement()) {
                int n = st.executeUpdate(
                        "UPDATE case_info SET case_time = COALESCE(accept_time, created_at) WHERE case_time IS NULL");
                if (n > 0) {
                    log.info("[SchemaMigration] 回填案件时间 {} 条", n);
                }
            }
        } catch (Exception e) {
            log.warn("[SchemaMigration] 回填案件时间失败（不影响启动）：{}", e.getMessage());
        }
    }

    /** 旧版本的 received_at 由移交按钮写入；没有审核回执，不能视为审核通过。 */    void migrateLegacyDeadlineReceipts(Connection conn, Set<String> existing) throws Exception {
        if (!existing.contains("case_deadline_state.pretrial_received_at")
                || !existing.contains("case_deadline_state.legal_received_at")) return;
        try (Statement st = conn.createStatement()) {
            int pretrial = st.executeUpdate("UPDATE case_deadline_state SET pretrial_sent_at = pretrial_received_at, "
                    + "pretrial_received_at = NULL WHERE pretrial_sent_at IS NULL AND pretrial_received_at IS NOT NULL");
            int legal = st.executeUpdate("UPDATE case_deadline_state SET legal_sent_at = legal_received_at, "
                    + "legal_received_at = NULL WHERE legal_sent_at IS NULL AND legal_received_at IS NOT NULL");
            if (pretrial + legal > 0) log.info("[SchemaMigration] 已将 {} 条旧版收件记录改为待确认移交记录", pretrial + legal);
        }
    }

    /**
     * 一次性清理：作废「模板生成的标准任务」（2026-10-12）。
     *
     * <p><b>背景</b>：阶段推进最早做成「阶段→环节→标准任务」三层，建案时会给每个新案件
     * 自动铺 13 项标准任务（接收材料/立案/侦查…）。后来办案阶段统一由
     * <b>案件待办</b>承担，那批任务就变成了第二套并行清单——同一个案子要在一处勾
     * 「接收材料/立案」，又要在另一处勾领导交代的事，两处数字还会对不上。
     * 用户明确要求删掉那套，所以这些行不能再留在计划统计里。
     *
     * <p><b>为什么置 CANCELLED 而不是 DELETE</b>：这是办案留痕，
     * 而且可能已被勾选完成过；直接删行会毁掉"这个案子当初走过哪些步骤"的记录。
     * 置 CANCELLED 后它们自动退出所有统计口径（planStats / 待办口径都跳过），
     * 需要追溯时数据还在库里。
     *
     * <p>幂等：只处理 is_std=1 且尚未 CANCELLED 的行，重复启动不会二次改动。
     */
    private void retireStdPlanTasks(Connection conn, Set<String> existing) {
        if (!existing.contains("case_plan.is_std")) {
            return;
        }
        String sql = "UPDATE case_plan SET status='CANCELLED' WHERE is_std=1 AND status<>'CANCELLED'";
        try (Statement st = conn.createStatement()) {
            int n = st.executeUpdate(sql);
            if (n > 0) {
                log.info("[SchemaMigration] 已作废 {} 条模板生成的标准任务"
                        + "（办案阶段改由案件待办承担，这些行不再计入统计）", n);
            }
        } catch (Exception e) {
            log.warn("[SchemaMigration] 标准任务作废跳过：{}", e.getMessage());
        }
    }

    /**
     * 一次性迁移：把 case_info 上的单个期限搬成一条「重要时间节点」（2026-10-13）。
     *
     * <p><b>背景</b>：原先一份案件只能挂一个期限（{@code deadline_label /
     * deadline / remind_days} 三列），实际办案要盯受案、立案、拘留期限届满、
     * 取保期限届满……所以新增 {@code case_time_node} 承载多节点。
     *
     * <p><b>为什么不删 case_info 三列</b>：列表截止期限、到期提醒、看板逾期、
     * 到期分桶、排序共约 140 处引用它们，且 {@code ORDER BY deadline} 靠索引。
     * 改为「节点表是真源、三列是主节点的派生视图」，由
     * {@code TimeNodeService.syncPrimary} 维护，老逻辑一行不改。
     *
     * <p><b>节点类型怎么定</b>：存量数据的节点名是用户自己填的（如"受案时间"、
     * "变更羁押期限时间"），按名字匹配已知预设名以归类；匹配不上的归为
     * 自定义（{@code node_key=NULL}），保留原名。
     *
     * <p><b>幂等</b>：只处理"本案件在 case_time_node 里一条都没有"的行。
     * 这样用户手动加过节点的案件不会被迁移覆盖，重复启动也不会产生重复行。
     */
    private void migrateSingleDeadlineToTimeNodes(Connection conn, Set<String> existing) {
        if (!existing.contains("case_info.deadline")
                || !existing.contains("case_info.deadline_label")) {
            return;
        }
        String sql = "SELECT id, deadline, deadline_label, remind_days FROM case_info "
                + "WHERE deadline IS NOT NULL AND id NOT IN "
                + "(SELECT DISTINCT case_id FROM case_time_node)";
        String ins = "INSERT INTO case_time_node "
                + "(case_id, node_key, node_name, node_time, remind_days, is_primary, sort) "
                + "VALUES (?,?,?,?,?,1,0)";
        // 用 PreparedStatement 而不是拼字符串：java.sql.Timestamp.toString() 会输出
        // "2026-09-24 18:00:00.0"（带 .0 小数秒），直接拼进 SQL 会被 H2 判为语法错误。
        // 顺带也避免节点名里带单引号时 SQL 被打断。
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql);
             PreparedStatement ps = conn.prepareStatement(ins)) {
            while (rs.next()) {
                Long caseId = rs.getLong("id");
                Timestamp ts = rs.getTimestamp("deadline");
                String label = rs.getString("deadline_label");
                int remind = rs.getInt("remind_days");
                Integer remindDays = rs.wasNull() ? null : remind;
                // 节点名兜底：原数据没填名称就用「截止期限」，避免 node_name 为空
                String name = (label == null || label.trim().isEmpty()) ? "截止期限" : label.trim();
                String key = matchNodeKey(name);
                ps.setLong(1, caseId);
                if (key == null) {
                    ps.setNull(2, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(2, key);
                }
                ps.setString(3, name);
                ps.setTimestamp(4, ts);
                if (remindDays == null) {
                    ps.setNull(5, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(5, remindDays);
                }
                ps.addBatch();
            }
            // executeBatch 返回各批次的更新条数；全为 0 说明没有可迁移的行
            int[] counts = ps.executeBatch();
            int total = 0;
            for (int n : counts) {
                total += n;
            }
            if (total > 0) {
                log.info("[SchemaMigration] 已把 {} 个案件的单个期限迁移为重要时间节点"
                        + "（case_info 三列保留，作为主节点的派生视图）", total);
            }
        } catch (Exception e) {
            log.warn("[SchemaMigration] 单期限迁移为时间节点跳过：{}", e.getMessage());
        }
    }

    /**
     * 按节点名匹配已知预设类型，匹配不上返回 null（自定义节点）。
     *
     * <p>刻意用「包含匹配」而非全等：存量数据里用户常写成"刑事拘留期限届满日"、
     * "取保候审期限届满" 这类带后缀的说法。
     */
    private static String matchNodeKey(String name) {
        if (name == null) {
            return null;
        }
        if (name.contains("受案")) {
            return "ACCEPT";
        }
        if (name.contains("立案")) {
            return "FILING";
        }
        if (name.contains("监视居住") || name.contains("监居")) {
            return "RESIDENCE_END";
        }
        if (name.contains("取保")) {
            return "BAIL_END";
        }
        if (name.contains("补充侦查")) {
            return "SUPP_PROBE";
        }
        if (name.contains("逮捕")) {
            return "ARREST_END";
        }
        if (name.contains("拘留")) {
            return "DETAIN_END";
        }
        if (name.contains("变更羁押期限")) {
            return "CUSTODY_TERM_CHANGE";
        }
        if (name.contains("变更") || name.contains("强制措施")) {
            return "CUSTODY_CHANGE";
        }
        return null;
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

    /**
     * 上传声明回填：从存量 {@code content} 里把「于 X 在 Y 上传了 Z。」拆到三列。
     *
     * <p>幂等：只处理 {@code upload_platform IS NULL} 的行，拆过一次的不会被重复处理。
     * 拆不出声明句的行原样保留（content 仍是全文），读侧还有一次兜底解析，
     * 所以即使这里因为某种原因没跑到，界面上也照样能编辑。
     */
    private void backfillFeedbackDeclare(Connection conn, Set<String> existing) {
        if (!existing.contains("case_todo_feedback.upload_platform")) {
            return;
        }
        // 先把待拆的行读出来（解析在 Java 里做，SQL 侧没有正则）
        List<Object[]> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, content FROM case_todo_feedback WHERE upload_platform IS NULL")) {
            while (rs.next()) {
                // parse 返回 [时间, 平台, 文件, 去掉声明句后的剩余说明]；全 null 表示没有声明句
                String[] d = FeedbackDeclare.parse(rs.getString(2));
                if (d != null) {
                    rows.add(new Object[]{rs.getLong(1), d[0], d[1], d[2], d[3]});
                }
            }
        } catch (Exception e) {
            log.warn("[SchemaMigration] 上传声明回填读取跳过：{}", e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            return;
        }
        // 参数化写入，不拼字符串（文件名里常有引号/括号）
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE case_todo_feedback SET upload_time=?, upload_platform=?, upload_file=?, content=? WHERE id=?")) {
            for (Object[] r : rows) {
                ps.setString(1, (String) r[1]);
                ps.setString(2, (String) r[2]);
                ps.setString(3, (String) r[3]);
                ps.setString(4, (String) r[4]);
                ps.setLong(5, (Long) r[0]);
                ps.addBatch();
            }
            int[] n = ps.executeBatch();
            log.info("[SchemaMigration] 上传声明已拆列：{} 条反馈", n.length);
        } catch (Exception e) {
            log.warn("[SchemaMigration] 上传声明回填写入跳过：{}", e.getMessage());
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
