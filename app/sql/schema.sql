-- =====================================================================
--  案件指派系统 / CaseFlow  数据模型
--  说明：本文件保持 ANSI-ish 写法，可直接在 MySQL 5.7+/8.0 执行，
--        也可被本地 dev 模式的 H2(MySQL 兼容模式) 直接加载，用于一键演示。
--  注意：为兼容双方言，未使用 ENGINE / ON UPDATE CURRENT_TIMESTAMP 等
--        MySQL 专有语法；上线到 MySQL 时可通过 sql/index-mysql.sql 补索引。
-- =====================================================================

-- 1. 登录账号（注册 + 审核 + 角色分级）
--    角色：CHIEF 所长 / DEPUTY_CHIEF 副所长 / LAW_OFFICER 法制员  → 权限全开
--          STAFF 普通民警                                       → 只有基础权限
--          BOSS 系统管理员（首个内置账号，权限视同全开）
--    密码：统一存 BCrypt 哈希（$2a$ 开头）。历史明文密码在登录成功时自动升级为哈希。
--    审核：自行注册的账号 audit_status=0（待审核），审核通过才能登录。
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    username      VARCHAR(64)  NOT NULL UNIQUE COMMENT '登录名',
    password      VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 哈希）',
    display_name  VARCHAR(64)  NOT NULL COMMENT '显示名',
    role          VARCHAR(32)  NOT NULL DEFAULT 'STAFF' COMMENT 'CHIEF/DEPUTY_CHIEF/LAW_OFFICER/STAFF/BOSS',
    employee_id   BIGINT       DEFAULT NULL COMMENT '关联的员工图谱 ID',
    phone         VARCHAR(32)  DEFAULT NULL COMMENT '手机号',
    dept          VARCHAR(128) DEFAULT NULL COMMENT '所属部门',
    apply_role    VARCHAR(32)  DEFAULT NULL COMMENT '注册时申请的角色，审核时可调整',
    audit_status  TINYINT      NOT NULL DEFAULT 1 COMMENT '0待审核 1已通过 2已驳回',
    audit_remark  VARCHAR(255) DEFAULT NULL COMMENT '审核意见/驳回原因',
    audited_by    BIGINT       DEFAULT NULL COMMENT '审核人',
    audited_at    DATETIME     DEFAULT NULL COMMENT '审核时间',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
);

-- 2. 员工图谱（由 Excel 导入形成 领导-副领导-组长-组员 的树）
CREATE TABLE IF NOT EXISTS org_employee (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    name          VARCHAR(64)  NOT NULL COMMENT '姓名',
    employee_no   VARCHAR(64)  DEFAULT NULL COMMENT '工号（导入关系的外键）',
    parent_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '上级 ID，0 表示顶层',
    parent_no     VARCHAR(64)  DEFAULT NULL COMMENT '上级工号（导入时的临时锚点）',
    id_path       VARCHAR(512) NOT NULL DEFAULT '/' COMMENT '祖先链路 /1/3/7/，便于一次查询子树',
    dept          VARCHAR(128) DEFAULT NULL COMMENT '部门/团队',
    title         VARCHAR(64)  DEFAULT NULL COMMENT '职务：领导/副领导/组长/组员',
    phone         VARCHAR(32)  DEFAULT NULL COMMENT '手机号',
    email         VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    level_no      INT          NOT NULL DEFAULT 1 COMMENT '层级深度，1 为顶层领导',
    sort_no       INT          NOT NULL DEFAULT 0 COMMENT '同级排序',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1在职 0离职/停用',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
);

-- 3. 案件主表（PDF / Word / Excel / 手打案件名 统一入口）
--    案卷类型（大类）：PRELIMINARY 未立案 / CRIMINAL 刑事 / ADMINISTRATIVE 行政
--    案件类别（小类）：沿用 category 列存具体案由（如：电诈、殴打他人、盗窃类）
CREATE TABLE IF NOT EXISTS case_info (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    case_no       VARCHAR(64)  NOT NULL UNIQUE COMMENT '案件编号，如 CA-20260928-001（唯一）',
    name          VARCHAR(255) NOT NULL COMMENT '案件名称',
    source_type   VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT '来源：MANUAL/PDF/WORD/EXCEL',
    source_file_id BIGINT      DEFAULT NULL COMMENT '原始案件材料文件 ID',
    case_type     VARCHAR(16)  DEFAULT NULL COMMENT '案卷类型（大类）：PRELIMINARY未立案/CRIMINAL刑事/ADMINISTRATIVE行政',
    category      VARCHAR(64)  DEFAULT NULL COMMENT '案件类别（小类/案由），如：电诈、殴打他人',
    filing_no     VARCHAR(64)  DEFAULT NULL COMMENT '立案登记表编号（受案号）',
    mediation_no  VARCHAR(64)  DEFAULT NULL COMMENT '调解书编号',
    case_measure  VARCHAR(16)  DEFAULT NULL COMMENT '强制措施：NONE无/DETENTION刑拘/BAIL取保候审/RESIDENCE监视居住',
    measure_date  DATETIME     DEFAULT NULL COMMENT '采取强制措施日期',
    detain_deadline DATETIME   DEFAULT NULL COMMENT '强制措施期限届满日（刑拘+30天/取保+12月/监居+6月）',
    investigation_status VARCHAR(20) DEFAULT NULL COMMENT '侦查进度：PENDING_INITIAL/INVESTIGATING/PENDING_APPROVAL/INVESTIGATION_DONE（盯办审批状态机，与流程阶段并存）',
    flow_stage           VARCHAR(24) DEFAULT NULL COMMENT '流程阶段：INITIAL初查/DETAIN刑拘在办/BAIL取保及监居/CLOSED已终结。NULL=存量案件，读时按 INITIAL 处理',
    description   TEXT         COMMENT '备注说明',
    priority      VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'URGENT/HIGH/NORMAL/LOW',
    deadline      DATETIME     DEFAULT NULL COMMENT '截止期限',
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING_ASSIGN' COMMENT 'PENDING_ASSIGN/ASSIGNED/IN_PROGRESS/DONE/CANCELLED',
    remark        VARCHAR(512) DEFAULT NULL COMMENT '处理结论/归档备注',
    created_by    BIGINT       DEFAULT NULL COMMENT '创建人（boss）',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
);

-- 4. 指派关系（一次案件可多人；改派保留历史记录，为后续追溯留痕）
CREATE TABLE IF NOT EXISTS case_assignee (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    case_id       BIGINT       NOT NULL COMMENT '案件 ID',
    employee_id   BIGINT       NOT NULL COMMENT '被指派员工 ID',
    assign_role   VARCHAR(16)  NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER=主办 / MEMBER=协办',
    note          VARCHAR(512) DEFAULT NULL COMMENT '指派要求',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE=现行 / REPLACED=已被改派（历史留痕）',
    assigned_by   BIGINT       DEFAULT NULL COMMENT '指派人',
    assigned_at   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '指派时间',
    closed_at     DATETIME     DEFAULT NULL COMMENT '失效/办结时间'
);

-- 5. 附件（案件原始材料、过程材料）
CREATE TABLE IF NOT EXISTS case_file (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    case_id       BIGINT       DEFAULT NULL COMMENT '案件 ID',
    file_name     VARCHAR(255) NOT NULL COMMENT '原始文件名',
    file_type     VARCHAR(32)  DEFAULT NULL COMMENT '扩展名 pdf/xlsx/docx',
    file_size     BIGINT       DEFAULT 0 COMMENT '字节数',
    storage_path  VARCHAR(512) DEFAULT NULL COMMENT '服务端相对存储路径',
    uploaded_by   BIGINT       DEFAULT NULL COMMENT '上传人',
    uploaded_at   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间'
);

-- 5.1 嫌疑人（案件关联的身份信息；随案件快照一并存档，支持撤回还原）
CREATE TABLE IF NOT EXISTS case_suspect (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    case_id       BIGINT       NOT NULL COMMENT '案件 ID',
    name          VARCHAR(64)  NOT NULL COMMENT '姓名',
    gender        VARCHAR(8)   DEFAULT NULL COMMENT 'MALE男 / FEMALE女',
    id_card       VARCHAR(32)  DEFAULT NULL COMMENT '身份证号',
    phone         VARCHAR(32)  DEFAULT NULL COMMENT '联系电话',
    address       VARCHAR(255) DEFAULT NULL COMMENT '住址',
    remark        VARCHAR(512) DEFAULT NULL COMMENT '备注',
    created_by    BIGINT       DEFAULT NULL COMMENT '录入人',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '录入时间',
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
);

-- 5.2 案件类别字典（小类 / 案由，由管理权限账户维护；前端级联选择器据此构建大类→小类树）
CREATE TABLE IF NOT EXISTS case_category (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    case_type     VARCHAR(16)  NOT NULL COMMENT '所属大类：CRIMINAL/ADMINISTRATIVE/PRELIMINARY',
    name          VARCHAR(64)  NOT NULL COMMENT '小类名称',
    sort          INT          NOT NULL DEFAULT 0 COMMENT '同级排序',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_cat_type_name (case_type, name)
);

-- 5.3 侦查计划项（案件盯办模块：计划→执行→预警 checklist）
CREATE TABLE IF NOT EXISTS case_plan (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    case_id     BIGINT       NOT NULL COMMENT '案件 ID',
    content     VARCHAR(512) NOT NULL COMMENT '侦查计划内容',
    planned_at  DATETIME     DEFAULT NULL COMMENT '计划完成时限',
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待完成/DONE已完成/CANCELLED已取消',
    done_at     DATETIME     DEFAULT NULL COMMENT '完成时间',
    done_note   VARCHAR(512) DEFAULT NULL COMMENT '完成情况说明',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序',
    created_by  BIGINT       DEFAULT NULL COMMENT '录入人',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    stage       VARCHAR(24)  DEFAULT NULL COMMENT '所属阶段 INITIAL/DETAIN/BAIL/CLOSED。NULL=存量计划，读时归入侦查环节',
    step_key    VARCHAR(32)  DEFAULT NULL COMMENT '所属环节 RECEIVE/CASE_FILL/INVESTIGATE/... NULL=存量，按侦查展示',
    task_key    VARCHAR(32)  DEFAULT NULL COMMENT '标准任务标识（模板内唯一）。NULL=民警自建任务',
    is_std      TINYINT      NOT NULL DEFAULT 0 COMMENT '1=按流程模板生成的标准任务，0=民警自建（自建任务删除不影响流程定义）'
);

-- 阶段→环节→任务三层流程（2026-10-04）见 com.caseflow.flow.CaseFlowTemplate。
-- 进度不落库：阶段进度 = 该阶段 DONE 任务数 / 该阶段任务总数，实时算。
-- 切换阶段时任务集合整体替换 → 进度天然归零，无需显式重置。

-- 5.4 审批留痕（案件盯办模块：侦查终结审批 / 强制措施变更确认）
CREATE TABLE IF NOT EXISTS case_approval (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    case_id       BIGINT       NOT NULL COMMENT '案件 ID',
    approve_type  VARCHAR(20)  NOT NULL COMMENT 'INVESTIGATION_DONE侦查终结审批/MEASURE强制措施变更',
    result        VARCHAR(16)  NOT NULL COMMENT 'APPROVED同意/RETURNED退回补侦',
    comment       VARCHAR(512) DEFAULT NULL COMMENT '审批意见',
    approver_id   BIGINT       DEFAULT NULL COMMENT '审批人',
    approver_name VARCHAR(64)  DEFAULT NULL COMMENT '审批人姓名（冗余）',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '审批时间'
);

-- 6. 操作日志（追溯功能的底座，第一版即全量埋点；同时是「撤回上一步」的依据）
--    撤回原理：每条写操作都前后各存一份对象完整快照（JSON），
--    撤回 = 把 snapshot_before 原样回写，再把这次撤回本身也记一条日志（action=UNDO）。
--    注意：TEXT 列在 MySQL 上不能带 DEFAULT，所以只写 NULL。
CREATE TABLE IF NOT EXISTS operation_log (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    module        VARCHAR(32)  DEFAULT NULL COMMENT '模块 CASE/EMPLOYEE/FILE/AUTH',
    action        VARCHAR(64)  DEFAULT NULL COMMENT '动作 CREATE/UPDATE/ASSIGN/STATUS/DELETE/UNDO/LOGIN',
    target_type   VARCHAR(32)  DEFAULT NULL COMMENT '对象类型',
    target_id     BIGINT       DEFAULT NULL COMMENT '对象 ID',
    content       VARCHAR(1024) DEFAULT NULL COMMENT '可读描述',
    operator_id   BIGINT       DEFAULT NULL COMMENT '操作人',
    operator_name VARCHAR(64)  DEFAULT NULL COMMENT '操作人姓名（冗余）',
    snapshot_before TEXT       COMMENT '操作前快照(JSON)，撤回时回写此状态',
    snapshot_after  TEXT       COMMENT '操作后快照(JSON)，用于展示变更明细',
    undone        TINYINT      NOT NULL DEFAULT 0 COMMENT '1=已被撤回',
    undo_log_id   BIGINT       DEFAULT NULL COMMENT '撤回本条所产生的新日志 ID',
    undo_of       BIGINT       DEFAULT NULL COMMENT '本条若是撤回操作，指向被撤回的日志 ID',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间'
);

-- 6.1 办理进度批注（类似 Word 批注）：管理层对每条办理进度（operation_log）写的旁注。
--     刻意不进案件快照体系：批注是"旁注"而非案件本体数据，撤回某条进度时不连带回滚批注；
--     批注自身的增删改全部埋点进 operation_log，时间线可见。
CREATE TABLE IF NOT EXISTS case_progress_comment (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    case_id      BIGINT       NOT NULL COMMENT '案件 ID（冗余，便于按案件校验与清理）',
    log_id       BIGINT       NOT NULL COMMENT '关联的办理进度（operation_log.id）',
    content      VARCHAR(512) NOT NULL COMMENT '批注内容',
    creator_id   BIGINT       DEFAULT NULL COMMENT '批注人',
    creator_name VARCHAR(64)  DEFAULT NULL COMMENT '批注人姓名（冗余展示）',
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '批注时间',
    updated_at   DATETIME     DEFAULT NULL COMMENT '最后编辑时间',
    updater_name VARCHAR(64)  DEFAULT NULL COMMENT '最后编辑人姓名（冗余展示）'
);

-- 6.2 领导意见与落实反馈：管理层（领导）对案件提出多条意见；办案人对每条意见
--     标记落实情况（完成/进行中/未完成）并记录反馈时间与说明。
--     同样不进快照体系；反馈只保留最新一条（历史经 operation_log 时间线可查）。
CREATE TABLE IF NOT EXISTS case_leader_opinion (
    id               BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    case_id          BIGINT        NOT NULL COMMENT '案件 ID',
    content          VARCHAR(512)  NOT NULL COMMENT '意见内容',
    creator_id       BIGINT        DEFAULT NULL COMMENT '提出人（管理层）',
    creator_name     VARCHAR(64)   DEFAULT NULL COMMENT '提出人姓名（冗余展示）',
    created_at       DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '提出时间',
    feedback_status  VARCHAR(16)   DEFAULT NULL COMMENT '落实反馈：DONE完成/IN_PROGRESS进行中/NOT_DONE未完成，NULL=尚未反馈',
    feedback_note    VARCHAR(1024) DEFAULT NULL COMMENT '反馈说明（含结构化上传声明句）',
    feedback_by      BIGINT        DEFAULT NULL COMMENT '反馈人（办案人）',
    feedback_by_name VARCHAR(64)   DEFAULT NULL COMMENT '反馈人姓名（冗余展示）',
    feedback_at      DATETIME      DEFAULT NULL COMMENT '反馈时间',
    sort_order       INT           DEFAULT NULL COMMENT '拖拽排序位次（1起连续）。NULL=旧数据，读取时按 id 升序回退并惰性初始化',
    deadline         DATETIME      DEFAULT NULL COMMENT '意见落实截止时间（可空，如"在某时间前完成"）',
    importance       VARCHAR(4)    DEFAULT NULL COMMENT '重要性 A=最重要/B=重要/C=一般；NULL 视为 C（一般）',
    CONSTRAINT ck_leader_opinion_importance CHECK (importance IS NULL OR importance IN ('A','B','C'))
);

-- 领导意见（2026-10 改造）：新增 sort_order / deadline / importance 三列。
-- 均允许 NULL 以兼容旧数据：
--   sort_order=NULL → 读取时按 id 升序回退，首次访问惰性初始化为 1..N；
--   importance=NULL → 展示按 C（一般）；
--   deadline =NULL → 无截止时间，紧急性判定为「正常」。
-- 列表显示序号由前端按当前数组下标实时计算（永远连续、不可能断号），
-- sort_order 只负责持久化顺序，不负责显示编号。
-- 紧急性（正常/临期/已逾期）由 deadline 与当前时间实时算出，不落库。

-- 说明：已有库升级到本版本需要补上面 5 个新列。
-- MySQL 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，无法写进本文件，
-- 因此改由应用启动时的 com.caseflow.bootstrap.SchemaMigration 幂等补齐（H2 / MySQL 通用）。
