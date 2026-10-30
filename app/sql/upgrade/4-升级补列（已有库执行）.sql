-- ============================================================
--  CaseFlow 已有库升级补列脚本（幂等，可重复执行）
-- ------------------------------------------------------------
--  生成时间：2026-10-30 19:14
--  来源：backend/.../bootstrap/SchemaMigration.java 的 NEW_COLUMNS（94 列）
-- ------------------------------------------------------------
--  【这段脚本做什么】
--  把旧版本的 case_flow 库补齐到与当前版本一致：缺哪列补哪列。
--  每列先查 information_schema，已存在就跳过，所以**重复执行完全安全**。
--
--  【这段脚本绝不做什么】
--  · 不 DROP、不 TRUNCATE、不改列类型、不改列名、不改表名
--  · 不 UPDATE 任何数据行，不写任何业务值
--  · 不碰 case_flow 以外的库，不碰系统表
--  新增列全部可空或带默认值，老数据行直接通过，不需要任何数据转换。
--
--  【怎么执行】
--  方式一（推荐）：不用执行本文件。直接换 jar 启动，程序会自动补列，
--                  效果与执行本文件完全相同（日志里会逐条打印"已补列"）。
--  方式二（DBA 手工）：先备份，再执行
--        mysql -u caseflow -p case_flow < "4-升级补列（已有库执行）.sql"
--      或把本文件内容整个粘到 Navicat / DBeaver 里跑。
--
--  【执行后怎么确认】
--  1) 本脚本每段执行成功不会有输出，报错会直接显示；
--  2) 抽查：SELECT COUNT(*) FROM case_info; 行数与执行前一致；
--  3) 抽查新列：SELECT case_no, case_time FROM case_info LIMIT 5;
--     老案件 case_time 可能为空（正常，程序首次启动会按受案时间回填）。
-- ============================================================

SET NAMES utf8mb4;


-- ------------------------------------------------------------
--  表：operation_log
-- ------------------------------------------------------------
-- operation_log.snapshot_before  操作前快照(JSON)，撤回时回写此状态
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log' AND COLUMN_NAME = 'snapshot_before');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `operation_log` ADD COLUMN `snapshot_before` TEXT NULL COMMENT ''操作前快照(JSON)，撤回时回写此状态''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- operation_log.snapshot_after  操作后快照(JSON)，用于展示变更明细
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log' AND COLUMN_NAME = 'snapshot_after');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `operation_log` ADD COLUMN `snapshot_after` TEXT NULL COMMENT ''操作后快照(JSON)，用于展示变更明细''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- operation_log.undone  1=已被撤回
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log' AND COLUMN_NAME = 'undone');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `operation_log` ADD COLUMN `undone` TINYINT NOT NULL DEFAULT 0 COMMENT ''1=已被撤回''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- operation_log.undo_log_id  撤回本条所产生的新日志 ID
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log' AND COLUMN_NAME = 'undo_log_id');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `operation_log` ADD COLUMN `undo_log_id` BIGINT NULL COMMENT ''撤回本条所产生的新日志 ID''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- operation_log.undo_of  本条若是撤回操作，指向被撤回的日志 ID
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log' AND COLUMN_NAME = 'undo_of');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `operation_log` ADD COLUMN `undo_of` BIGINT NULL COMMENT ''本条若是撤回操作，指向被撤回的日志 ID''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：sys_user
-- ------------------------------------------------------------
-- sys_user.phone  手机号
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'phone');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `sys_user` ADD COLUMN `phone` VARCHAR(32) NULL COMMENT ''手机号''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- sys_user.dept  所属部门
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'dept');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `sys_user` ADD COLUMN `dept` VARCHAR(128) NULL COMMENT ''所属部门''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- sys_user.apply_role  注册时申请的角色，审核时可调整
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'apply_role');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `sys_user` ADD COLUMN `apply_role` VARCHAR(32) NULL COMMENT ''注册时申请的角色，审核时可调整''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- sys_user.audit_status  0待审核 1已通过 2已驳回
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'audit_status');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `sys_user` ADD COLUMN `audit_status` TINYINT NOT NULL DEFAULT 1 COMMENT ''0待审核 1已通过 2已驳回''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- sys_user.audit_remark  审核意见/驳回原因
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'audit_remark');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `sys_user` ADD COLUMN `audit_remark` VARCHAR(255) NULL COMMENT ''审核意见/驳回原因''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- sys_user.audited_by  审核人
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'audited_by');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `sys_user` ADD COLUMN `audited_by` BIGINT NULL COMMENT ''审核人''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- sys_user.audited_at  审核时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'audited_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `sys_user` ADD COLUMN `audited_at` DATETIME NULL COMMENT ''审核时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_info
-- ------------------------------------------------------------
-- case_info.case_type  案卷类型（大类）：PRELIMINARY未立案/CRIMINAL刑事/ADMINISTRATIVE行政
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'case_type');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `case_type` VARCHAR(16) NULL COMMENT ''案卷类型（大类）：PRELIMINARY未立案/CRIMINAL刑事/ADMINISTRATIVE行政''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.filing_no  案件编号（立案登记表编号 / 受案号）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'filing_no');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `filing_no` VARCHAR(64) NULL COMMENT ''案件编号（立案登记表编号 / 受案号）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.mediation_no  调解书编号（已废弃，仅历史数据）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'mediation_no');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `mediation_no` VARCHAR(64) NULL COMMENT ''调解书编号（已废弃，仅历史数据）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.case_measure  强制措施：NONE无/DETENTION拘留/ARREST逮捕/BAIL取保候审/RESIDENCE监视居住/SUMMONS拘传
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'case_measure');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `case_measure` VARCHAR(16) NULL COMMENT ''强制措施：NONE无/DETENTION拘留/ARREST逮捕/BAIL取保候审/RESIDENCE监视居住/SUMMONS拘传''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.measure_date  采取强制措施日期
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'measure_date');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `measure_date` DATETIME NULL COMMENT ''采取强制措施日期''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.detain_deadline  强制措施期限届满日（刑拘+30天/取保+12月/监居+6月）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'detain_deadline');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `detain_deadline` DATETIME NULL COMMENT ''强制措施期限届满日（刑拘+30天/取保+12月/监居+6月）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.investigation_status  侦查进度：PENDING_INITIAL/INVESTIGATING/PENDING_APPROVAL/INVESTIGATION_DONE（盯办审批状态机，与流程阶段并存）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'investigation_status');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `investigation_status` VARCHAR(20) NULL COMMENT ''侦查进度：PENDING_INITIAL/INVESTIGATING/PENDING_APPROVAL/INVESTIGATION_DONE（盯办审批状态机，与流程阶段并存）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_file
-- ------------------------------------------------------------
-- case_file.todo_id  所属待办 ID（佐证材料复用本表）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_file' AND COLUMN_NAME = 'todo_id');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_file` ADD COLUMN `todo_id` BIGINT NULL COMMENT ''所属待办 ID（佐证材料复用本表）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：org_employee
-- ------------------------------------------------------------
-- org_employee.origin  档案来源：SELF_REGISTER 本人注册自建 / NULL 管理员建档
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'org_employee' AND COLUMN_NAME = 'origin');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `org_employee` ADD COLUMN `origin` VARCHAR(16) NULL COMMENT ''档案来源：SELF_REGISTER 本人注册自建 / NULL 管理员建档''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- org_employee.police_system_no  警综人员编号（警综系统为人员生成，盯办人员列表展示）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'org_employee' AND COLUMN_NAME = 'police_system_no');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `org_employee` ADD COLUMN `police_system_no` VARCHAR(64) NULL COMMENT ''警综人员编号（警综系统为人员生成，盯办人员列表展示）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_leader_opinion
-- ------------------------------------------------------------
-- case_leader_opinion.sort_order  拖拽排序位次（1起连续）。NULL=旧数据，读取时按 id 升序回退并惰性初始化
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_leader_opinion' AND COLUMN_NAME = 'sort_order');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_leader_opinion` ADD COLUMN `sort_order` INT NULL COMMENT ''拖拽排序位次（1起连续）。NULL=旧数据，读取时按 id 升序回退并惰性初始化''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_leader_opinion.deadline  意见落实截止时间（可空，如"在某时间前完成"）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_leader_opinion' AND COLUMN_NAME = 'deadline');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_leader_opinion` ADD COLUMN `deadline` DATETIME NULL COMMENT ''意见落实截止时间（可空，如"在某时间前完成"）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_leader_opinion.importance  重要性 A=最重要/B=重要/C=一般；NULL 视为 C（一般）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_leader_opinion' AND COLUMN_NAME = 'importance');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_leader_opinion` ADD COLUMN `importance` VARCHAR(4) NULL COMMENT ''重要性 A=最重要/B=重要/C=一般；NULL 视为 C（一般）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_approval
-- ------------------------------------------------------------
-- case_approval.apply_measure  申请的目标措施（NONE=结束当前措施）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_approval' AND COLUMN_NAME = 'apply_measure');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_approval` ADD COLUMN `apply_measure` VARCHAR(20) NULL COMMENT ''申请的目标措施（NONE=结束当前措施）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_approval.apply_date  申请的措施起算日
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_approval' AND COLUMN_NAME = 'apply_date');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_approval` ADD COLUMN `apply_date` DATETIME NULL COMMENT ''申请的措施起算日''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_approval.apply_deadline  申请的期限届满日
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_approval' AND COLUMN_NAME = 'apply_deadline');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_approval` ADD COLUMN `apply_deadline` DATETIME NULL COMMENT ''申请的期限届满日''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_approval.from_measure  申请前的措施（审批时对比用）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_approval' AND COLUMN_NAME = 'from_measure');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_approval` ADD COLUMN `from_measure` VARCHAR(20) NULL COMMENT ''申请前的措施（审批时对比用）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_approval.applicant_id  申请人
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_approval' AND COLUMN_NAME = 'applicant_id');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_approval` ADD COLUMN `applicant_id` BIGINT NULL COMMENT ''申请人''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_approval.applicant_name  申请人姓名（冗余）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_approval' AND COLUMN_NAME = 'applicant_name');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_approval` ADD COLUMN `applicant_name` VARCHAR(64) NULL COMMENT ''申请人姓名（冗余）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_approval.applied_at  申请时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_approval' AND COLUMN_NAME = 'applied_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_approval` ADD COLUMN `applied_at` DATETIME NULL COMMENT ''申请时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_info
-- ------------------------------------------------------------
-- case_info.flow_stage  流程阶段：INITIAL初查/DETAIN刑拘在办/BAIL取保及监居/CLOSED已终结。NULL=存量案件，读时按 INITIAL 处理
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'flow_stage');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `flow_stage` VARCHAR(24) NULL COMMENT ''流程阶段：INITIAL初查/DETAIN刑拘在办/BAIL取保及监居/CLOSED已终结。NULL=存量案件，读时按 INITIAL 处理''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_plan
-- ------------------------------------------------------------
-- case_plan.stage  所属阶段 INITIAL/DETAIN/BAIL/CLOSED。NULL=存量计划，读时归入侦查环节
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_plan' AND COLUMN_NAME = 'stage');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_plan` ADD COLUMN `stage` VARCHAR(24) NULL COMMENT ''所属阶段 INITIAL/DETAIN/BAIL/CLOSED。NULL=存量计划，读时归入侦查环节''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_plan.step_key  所属环节 RECEIVE/CASE_FILL/INVESTIGATE/... NULL=存量，按侦查展示
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_plan' AND COLUMN_NAME = 'step_key');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_plan` ADD COLUMN `step_key` VARCHAR(32) NULL COMMENT ''所属环节 RECEIVE/CASE_FILL/INVESTIGATE/... NULL=存量，按侦查展示''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_plan.task_key  标准任务标识（模板内唯一）。NULL=民警自建任务
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_plan' AND COLUMN_NAME = 'task_key');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_plan` ADD COLUMN `task_key` VARCHAR(32) NULL COMMENT ''标准任务标识（模板内唯一）。NULL=民警自建任务''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_plan.is_std  1=按流程模板生成的标准任务，0=民警自建（自建任务删除不影响流程定义）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_plan' AND COLUMN_NAME = 'is_std');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_plan` ADD COLUMN `is_std` TINYINT NOT NULL DEFAULT 0 COMMENT ''1=按流程模板生成的标准任务，0=民警自建（自建任务删除不影响流程定义）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_todo
-- ------------------------------------------------------------
-- case_todo.opinion_id  来源领导意见 ID（派生待办时写入，幂等去重用）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo' AND COLUMN_NAME = 'opinion_id');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo` ADD COLUMN `opinion_id` BIGINT NULL COMMENT ''来源领导意见 ID（派生待办时写入，幂等去重用）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_todo.urgency  紧急程度：URGENT紧急/HIGH较急/NORMAL一般；NULL=旧数据按NORMAL
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo' AND COLUMN_NAME = 'urgency');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo` ADD COLUMN `urgency` VARCHAR(8) NULL COMMENT ''紧急程度：URGENT紧急/HIGH较急/NORMAL一般；NULL=旧数据按NORMAL''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_todo.importance  重点程度：KEY重点/MEDIUM次重点/NORMAL一般；NULL=旧数据按 NORMAL
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo' AND COLUMN_NAME = 'importance');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo` ADD COLUMN `importance` VARCHAR(8) NULL COMMENT ''重点程度：KEY重点/MEDIUM次重点/NORMAL一般；NULL=旧数据按 NORMAL''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_todo.dept_source  所属部门/来源（取提意见人所在部门，冗余便于列表展示）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo' AND COLUMN_NAME = 'dept_source');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo` ADD COLUMN `dept_source` VARCHAR(128) NULL COMMENT ''所属部门/来源（取提意见人所在部门，冗余便于列表展示）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_todo.deadline  截止时间（取意见的落实截止时间，可空）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo' AND COLUMN_NAME = 'deadline');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo` ADD COLUMN `deadline` DATETIME NULL COMMENT ''截止时间（取意见的落实截止时间，可空）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_todo.parent_id  父任务 ID：NULL=顶层主任务；非空=子任务（细节工作）。NULL 兼容全部存量数据
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo' AND COLUMN_NAME = 'parent_id');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo` ADD COLUMN `parent_id` BIGINT NULL COMMENT ''父任务 ID：NULL=顶层主任务；非空=子任务（细节工作）。NULL 兼容全部存量数据''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_question
-- ------------------------------------------------------------
-- case_question.answer_edited_by  最后修订回答的账号 ID
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_question' AND COLUMN_NAME = 'answer_edited_by');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_question` ADD COLUMN `answer_edited_by` BIGINT NULL COMMENT ''最后修订回答的账号 ID''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_question.answer_edited_by_name  最后修订回答的姓名（冗余展示）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_question' AND COLUMN_NAME = 'answer_edited_by_name');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_question` ADD COLUMN `answer_edited_by_name` VARCHAR(64) NULL COMMENT ''最后修订回答的姓名（冗余展示）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_question.answer_edited_at  最后修订回答的时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_question' AND COLUMN_NAME = 'answer_edited_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_question` ADD COLUMN `answer_edited_at` DATETIME NULL COMMENT ''最后修订回答的时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_notification
-- ------------------------------------------------------------
-- case_notification.anchor_todo_id  定位锚点-待办 ID（可空）：点通知直达该任务详情
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_notification' AND COLUMN_NAME = 'anchor_todo_id');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_notification` ADD COLUMN `anchor_todo_id` BIGINT NULL COMMENT ''定位锚点-待办 ID（可空）：点通知直达该任务详情''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_notification.anchor_question_id  定位锚点-疑问 ID（已下线，历史数据留档，新数据恒 NULL）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_notification' AND COLUMN_NAME = 'anchor_question_id');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_notification` ADD COLUMN `anchor_question_id` BIGINT NULL COMMENT ''定位锚点-疑问 ID（已下线，历史数据留档，新数据恒 NULL）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_notification.anchor_subtask_id  定位锚点-子任务 ID（可空）：点通知直达该子任务
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_notification' AND COLUMN_NAME = 'anchor_subtask_id');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_notification` ADD COLUMN `anchor_subtask_id` BIGINT NULL COMMENT ''定位锚点-子任务 ID（可空）：点通知直达该子任务''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：org_employee
-- ------------------------------------------------------------
-- org_employee.police_group  办案组别：INITIAL初查组/CLEAR清案组/NONE不限（NULL 视为 NONE 不限）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'org_employee' AND COLUMN_NAME = 'police_group');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `org_employee` ADD COLUMN `police_group` VARCHAR(16) NULL COMMENT ''办案组别：INITIAL初查组/CLEAR清案组/NONE不限（NULL 视为 NONE 不限）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：sys_user
-- ------------------------------------------------------------
-- sys_user.police_group  办案组别：INITIAL初查组/CLEAR清案组/NONE不限；注册时必选，NULL=未指定（不校验组别）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'police_group');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `sys_user` ADD COLUMN `police_group` VARCHAR(16) NULL COMMENT ''办案组别：INITIAL初查组/CLEAR清案组/NONE不限；注册时必选，NULL=未指定（不校验组别）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_todo_feedback
-- ------------------------------------------------------------
-- case_todo_feedback.upload_time  上传时间（文本，精确到秒）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo_feedback' AND COLUMN_NAME = 'upload_time');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo_feedback` ADD COLUMN `upload_time` VARCHAR(32) NULL COMMENT ''上传时间（文本，精确到秒）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_todo_feedback.upload_platform  上传平台名称
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo_feedback' AND COLUMN_NAME = 'upload_platform');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo_feedback` ADD COLUMN `upload_platform` VARCHAR(128) NULL COMMENT ''上传平台名称''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_todo_feedback.upload_file  上传的文件名称
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo_feedback' AND COLUMN_NAME = 'upload_file');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo_feedback` ADD COLUMN `upload_file` VARCHAR(255) NULL COMMENT ''上传的文件名称''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_todo_feedback.edited_by  最近修订人账号 ID
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo_feedback' AND COLUMN_NAME = 'edited_by');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo_feedback` ADD COLUMN `edited_by` BIGINT NULL COMMENT ''最近修订人账号 ID''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_todo_feedback.edited_by_name  最近修订人姓名
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo_feedback' AND COLUMN_NAME = 'edited_by_name');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo_feedback` ADD COLUMN `edited_by_name` VARCHAR(64) NULL COMMENT ''最近修订人姓名''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_todo_feedback.edited_at  最近修订时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo_feedback' AND COLUMN_NAME = 'edited_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo_feedback` ADD COLUMN `edited_at` DATETIME NULL COMMENT ''最近修订时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_info
-- ------------------------------------------------------------
-- case_info.deadline_label  期限节点名称（自己填：受案时间 / 变更羁押期限时间…）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'deadline_label');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `deadline_label` VARCHAR(64) NULL COMMENT ''期限节点名称（自己填：受案时间 / 变更羁押期限时间…）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.remind_days  该节点提前多少天提醒（NULL=不提醒）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'remind_days');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `remind_days` INT NULL COMMENT ''该节点提前多少天提醒（NULL=不提醒）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.focus  重点关注：0否/1是（一键标注，属标记类操作，不在可撤回白名单）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'focus');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `focus` TINYINT NOT NULL DEFAULT 0 COMMENT ''重点关注：0否/1是（一键标注，属标记类操作，不在可撤回白名单）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_todo
-- ------------------------------------------------------------
-- case_todo.flow_stage  办案阶段：NULL=存量按案件当前阶段
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_todo' AND COLUMN_NAME = 'flow_stage');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_todo` ADD COLUMN `flow_stage` VARCHAR(24) NULL COMMENT ''办案阶段：NULL=存量按案件当前阶段''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_info
-- ------------------------------------------------------------
-- case_info.occur_place  发案地点
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'occur_place');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `occur_place` VARCHAR(255) NULL COMMENT ''发案地点''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.occur_time  发案时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'occur_time');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `occur_time` DATETIME NULL COMMENT ''发案时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.undertake_unit  承办单位
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'undertake_unit');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `undertake_unit` VARCHAR(128) NULL COMMENT ''承办单位''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.accept_time  受理时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'accept_time');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `accept_time` DATETIME NULL COMMENT ''受理时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.case_time  案件时间：已受案取受案时间，未受案取报案时间；列表展示与排序统一用这一列
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'case_time');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `case_time` DATETIME NULL COMMENT ''案件时间：已受案取受案时间，未受案取报案时间；列表展示与排序统一用这一列''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_suspect
-- ------------------------------------------------------------
-- case_suspect.measure  当前强制措施：NONE/DETENTION拘留/ARREST逮捕/BAIL取保候审/RESIDENCE监视居住/SUMMONS拘传
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_suspect' AND COLUMN_NAME = 'measure');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_suspect` ADD COLUMN `measure` VARCHAR(16) NULL COMMENT ''当前强制措施：NONE/DETENTION拘留/ARREST逮捕/BAIL取保候审/RESIDENCE监视居住/SUMMONS拘传''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_suspect.measure_start_at  本次措施开始时间（选了措施则必填）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_suspect' AND COLUMN_NAME = 'measure_start_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_suspect` ADD COLUMN `measure_start_at` DATETIME NULL COMMENT ''本次措施开始时间（选了措施则必填）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_suspect.measure_end_at  下一节点：措施期满/结束时间（选了措施则必填）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_suspect' AND COLUMN_NAME = 'measure_end_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_suspect` ADD COLUMN `measure_end_at` DATETIME NULL COMMENT ''下一节点：措施期满/结束时间（选了措施则必填）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_info
-- ------------------------------------------------------------
-- case_info.punish_time  处罚时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'punish_time');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `punish_time` DATETIME NULL COMMENT ''处罚时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.external_id  源系统主键（导入溯源用）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'external_id');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `external_id` VARCHAR(32) NULL COMMENT ''源系统主键（导入溯源用）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_info.brief  简要案情（一段话概括案件基本事实，详情页单独一栏）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_info' AND COLUMN_NAME = 'brief');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_info` ADD COLUMN `brief` TEXT NULL COMMENT ''简要案情（一段话概括案件基本事实，详情页单独一栏）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_deadline_state
-- ------------------------------------------------------------
-- case_deadline_state.detention_term  刑拘 3/7/30 天；暂停另记 paused_at
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'detention_term');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `detention_term` VARCHAR(8) NULL COMMENT ''刑拘 3/7/30 天；暂停另记 paused_at''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.paused_at  精神病鉴定停止计算起点
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'paused_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `paused_at` DATETIME NULL COMMENT ''精神病鉴定停止计算起点''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.paused_deadline  停止前的期限届满日
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'paused_deadline');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `paused_deadline` DATETIME NULL COMMENT ''停止前的期限届满日''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.paused_seconds  累计停止计算秒数
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'paused_seconds');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `paused_seconds` BIGINT NOT NULL DEFAULT 0 COMMENT ''累计停止计算秒数''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.pretrial_received_at  预审审核通过并接收时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'pretrial_received_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `pretrial_received_at` DATETIME NULL COMMENT ''预审审核通过并接收时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.legal_received_at  法制审核通过并接收时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'legal_received_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `legal_received_at` DATETIME NULL COMMENT ''法制审核通过并接收时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.pretrial_sent_at  移交预审时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'pretrial_sent_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `pretrial_sent_at` DATETIME NULL COMMENT ''移交预审时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.legal_sent_at  移交法制时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'legal_sent_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `legal_sent_at` DATETIME NULL COMMENT ''移交法制时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.submitted_at  提交审核时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'submitted_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `submitted_at` DATETIME NULL COMMENT ''提交审核时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.last_summons_at  最近一次传讯时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'last_summons_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `last_summons_at` DATETIME NULL COMMENT ''最近一次传讯时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.admin_30_doc_at  30日延长文书呈报时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'admin_30_doc_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `admin_30_doc_at` DATETIME NULL COMMENT ''30日延长文书呈报时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.admin_extended_at  确认延长至总计60天时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'admin_extended_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `admin_extended_at` DATETIME NULL COMMENT ''确认延长至总计60天时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.admin_60_doc_at  60日延长文书呈报时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'admin_60_doc_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `admin_60_doc_at` DATETIME NULL COMMENT ''60日延长文书呈报时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.admin_extended90_at  第二次确认延长至总计90天时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'admin_extended90_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `admin_extended90_at` DATETIME NULL COMMENT ''第二次确认延长至总计90天时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.filing_at  刑事案件立案时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'filing_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `filing_at` DATETIME NULL COMMENT ''刑事案件立案时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.arrest_target_at  领导确定的拘捕目标时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'arrest_target_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `arrest_target_at` DATETIME NULL COMMENT ''领导确定的拘捕目标时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.admin_check_3_at  第3日内部监督完成时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'admin_check_3_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `admin_check_3_at` DATETIME NULL COMMENT ''第3日内部监督完成时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.admin_check_10_at  第10日内部监督完成时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'admin_check_10_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `admin_check_10_at` DATETIME NULL COMMENT ''第10日内部监督完成时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.admin_check_20_at  第20日内部监督完成时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'admin_check_20_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `admin_check_20_at` DATETIME NULL COMMENT ''第20日内部监督完成时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_deadline_state.updated_at  更新时间
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_deadline_state' AND COLUMN_NAME = 'updated_at');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_deadline_state` ADD COLUMN `updated_at` DATETIME NULL COMMENT ''更新时间''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;


-- ------------------------------------------------------------
--  表：case_approval
-- ------------------------------------------------------------
-- case_approval.flow_action  阶段流转申请的动作（DETAIN/BAIL等，approveType=FLOW时用）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_approval' AND COLUMN_NAME = 'flow_action');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_approval` ADD COLUMN `flow_action` VARCHAR(20) NULL COMMENT ''阶段流转申请的动作（DETAIN/BAIL等，approveType=FLOW时用）''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- case_approval.from_stage  阶段流转申请时的阶段
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_approval' AND COLUMN_NAME = 'from_stage');
SET @ddl := IF(@exists = 0, 'ALTER TABLE `case_approval` ADD COLUMN `from_stage` VARCHAR(24) NULL COMMENT ''阶段流转申请时的阶段''', 'DO 0');
PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;

-- ============================================================
--  补列完成。
--  若程序随后启动，日志里"已补列"应当一条都不出现（因为列已经在）。
-- ============================================================
