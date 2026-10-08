# 项目长期记忆：案件指派demo（CaseFlow）

> 调试踩坑备查见 `LESSONS.md`（不注入，只在排查具体问题时翻）。
> 本文件只保留**每次开工都要知道**的事实与硬约定。

## 定位
收案件 → 指派（主办+协办）→ 盯到期 → 办结，含操作追溯+撤回。反馈与排期见 `docs/`。

## 目录与 Git（最易踩）
- 仓库根 `D:\案件指派demo\`（开发环境包），**源码在 `app/`**，工具链在 `tools/`。
  **PyCharm 必须打开 `app/`**（`.idea/vcs.xml` 已把 Git 映射到上一级），
  运行配置在 `app/.idea/runConfigurations/`。
- 虚拟环境在 **`app/.venv`（Python 3.10.11，不是 3.13）**；`IS_MODULE_SDK=false`+
  `SDK_HOME` 直指它，PyCharm 里 SDK 显示几.x **不影响运行**，别改。
- 远程 https://github.com/ANNEAUX-GY/CaseFlow （public，main），push 已实测可用。
- 本机专属排除写 `.git/info/exclude`（**不改被跟踪的 .gitignore，否则与远程漂移**；
  该文件不支持行尾注释，只能整行 `#`）。已排除 `app/backend/src/main/resources/sql/`、`shots/`。
- `app/backend/data/h2/caseflow.mv.db` 是演示库且被后端进程占用，**每次提交都要手动跳过**。
- **push 两种相反处置**（都踩过）：卡住无输出 → 加 `env -u http_proxy -u https_proxy`；
  报 Connection was reset → **去掉** env -u 走代理。仓库级已设 `credential.helper=manager`。
  本地 curl 测 API 一律加 `--noproxy '*'`。
- Windows 建目录链接必须 PowerShell `New-Item -ItemType Junction`（git bash 的 `ln -sfn`
  对目录生成坏链接）。

## 常用命令（均在 `app/` 下）
- 开发：`.venv\Scripts\python.exe scripts\dev.py [--profile mysql] [--seed] [--check]
  [--kill-existing] [--no-frontend] [--no-backend]`（连外部库加 `--skip-db-check`）
- 编译后端（中文路径下 `-f` 会编码损坏，必须这么写），在 `app/backend`：
  `JAVA_HOME=jdk8 tools/maven/bin/mvn.cmd -B -o -Dmaven.repo.local=D:\案件指派demo\tools\m2repo compile`
- 自检：`verify_lan/undo/todo_subtask/assign_status/opinion_*/notification/flow/p0_fields.py`；
  `check_vue_imports.py`（漏导入扫描）；造数 `seed_demo.py --base http://127.0.0.1:8080`
- dev 用 H2 文件库，**同时只能一个后端进程**（报 `Could not open .mv.db` 即重复启动）。
  端口用 `CF_SERVER_PORT`，且必须 `--server.port` 压过本机 `SERVER__PORT`。

## 技术栈与硬约定
- Java **Spring Boot 2.x + JDK8**（`C:\Program Files\Java\jdk1.8.0_172`，JDK25 不兼容）。
  新建后端文件：`import javax.annotation.Resource;`（不是 jakarta）、
  `com.caseflow.security.AuthContext`、JDK8 无 `List.of()` 用 `new ArrayList<>()`。
- 加字段统一走 `bootstrap/SchemaMigration.NEW_COLUMNS`（`schema.sql` 的
  `CREATE TABLE IF NOT EXISTS` 不补列；MySQL `ALTER` 无 `IF EXISTS`）。
- **schema.sql 双副本**：运行时加载 `app/backend/src/main/resources/sql/schema.sql`，
  与根 `sql/schema.sql` 是两份文件，改表必须同步两份。
- **二级索引只能放 `sql/index-mysql.sql`**（`CREATE INDEX IF NOT EXISTS` 是 H2 专属）。
- 前端 Vue3 + Element Plus + Pinia + ECharts 5.5.1。**加图表类型前必须先在 `EChart.vue`
  的 `echarts.use([...])` 注册**，否则静默不渲染、零报错。主题统一 `utils/chart.js`，
  组件 `EChart.vue`（`setOption` 必须 `notMerge=true`）+ `ChartPanel.vue`。
- 公安配色：警蓝 `#1b4a8c` / 深藏蓝 `#12294a` / 警徽金 `#c8a45c`；色板与布局基准
  （`.cf-page` gap16、面板头 44px）全在 `styles/index.css`，**页内别另写**。
- **无障碍基准（使用者 45-50 岁）**：导航项 56px/17px/警徽金激活竖条、顶栏 62px。
- **全项目禁用悬浮提示**（`el-tooltip` 归零、原生 `title` 清零）：会遮挡相邻操作按钮。
  说明一律改成**常驻可见的内联文字 / el-alert 的 `description`**。
  注意多数 `title` 是 `:title`（组件标题栏属性），删了会没标题——
  筛选用 `grep -E '(^|[^-:])title="' | grep -v ':title='`。
- **同排等高**：`cf-panel--fill` + 内容自适应，**仅当父级高度已确定**时可用；
  父级高度由内容决定时用它会成 flex 循环依赖把面板顶爆（实测撑到 1289px）→ 改固定高度。
  flex 子项要固定高度用 `min-height` + `flex-shrink:0`，别写死 `height`。
- 移动端（`utils/device.js`，`MOBILE_MAX_WIDTH=768` 必须与 `@media` 成对改）：
  `initDevice()` 在 `createApp` 前调；侧栏一份实现 `NavPanel.vue`（`variant=aside|drawer`）；
  列表手机端走卡片（`.cf-ccard` / `.cf-ucard`）；`.cf-panel__head` 手机端
  `height:auto;min-height:42px;flex-wrap:wrap`；核对 `scrollWidth-innerWidth` 必须 = 0。
- 跨组件计数/状态必须放 Pinia store（`Layout.vue` 整个会话只挂载一次，局部 ref 不会重取）。
  刷新计数走后端重数，别本地 `-1`。
- 页面/组件已有：`PageFooter.vue`、标语集中 `src/config/slogans.js`。
- 员工图谱 Excel 列固定：姓名/工号/上级工号/部门/职务/手机/邮箱；演示账号 `boss/admin123`；
  自检账号 `e2e_staff` / `e2e_law`（`e2e123456`）。

## 组织层级规则（2026-10-08，重要）
**总 → 副总 → 组长 → 员工 固定四层，层级由「职务」推导，不由 parent_id 连线决定。**
- 后端 `flow/OrgRank.java`，前端镜像 `frontend/src/utils/org.js`。
  **两边必须一起改**，否则界面层级和后端对不上。
- 判定顺序：先「副」→ 再「组长/队长」→ 再「组员/警员」→ 最后「领导/总/长」。
  顺序反了会：「副组长」被当副总、「组长/队长」因含「长」被当总。空职务归员工层。
- 上级选错层**不报错**，兜底到同层合理上级（同部门优先）。理由：让人保存不了比挂错层更糟。
  `resolveParents` 的结果同时回写 `parent_id`、并供 `pathNameMap` 用，保证树与链路同源。
- 部门异常标记：`deptMissing`（没填）/ `deptAlone`（该部门全局只他一人）/ `deptAnomaly`（或）。
  列表=橙标签，树状图=姓名色块改橙 `#d98a0b`，底栏「部门待核 N 人」，另有常驻图例。
- 部门一律**从已有部门下拉选**（`GET /employees/depts`；注册页无令牌走 `/auth/register/depts`），
  三处入口：员工图谱表单、账号管理弹窗、注册页。

## 核心设计：追溯与撤回
- 写操作前后各存完整快照，撤回 = 把 `snapshot_before` 原样写回。
  快照 JSON `{exists, info, assignees, files, suspects, plans}`；
  `insertWithId` / `restore` / `fullUpdate` **三处都要补新列**，撤回精度靠这个。
- 表 `operation_log`（`snapshot_before/after` 为 TEXT、MySQL 不能带 DEFAULT；
  `undone`/`undo_log_id`/`undo_of`）。代码：`CaseSnapshotService` / `OperationLogService` /
  `LogController(/api/logs*)` / `LogService.log`。**新增写操作别漏带 before/after。**
- `restore` 必须全字段 `LambdaUpdateWrapper.set`（`updateById` 跳 null，清空期限撤不回）；
  还原原 id 必须手写 `@Insert insertWithId`（`IdType.AUTO` 时 insert 会省主键列）。
- 撤回规则：仅案件类、必须是该案件最新一条、已撤不可再撤；UNDO 本身记日志（再撤 = 重做）。
  校验 `scripts/verify_undo.py`。

## 全局实时事件流（SSE）
- `LogService.log` 落库后统一广播（**全量埋点唯一汇聚点**）→ `SseHub` → 所有在线 EventSource。
- 端点 `GET /api/events/stream?token=`（**LoginInterceptor：header 优先、query token 兜底**，
  EventSource 带不了 header）。event name = `case-event`。
- 前端 `store/events.js`（`subscribe(fn)`/`connect`，onerror 连败 5 次停连），Layout 里 connect。
  **改后端埋点/广播只动 LogService 一处**；新页面要实时刷新就 `eventStore.subscribe`。

## 通知 / 意见 / 疑问（2026-10 现状）
- **待办是意见的唯一载体**（用户亲手重构，方向比双写更正确，**勿回退**）：
  `case_todo.opinion_id` 派生待办，待办是单一事实源、意见只读回写。
  `case_todo.parent_id` 两级（NULL=主任务）；`case_todo_feedback` 累积反馈（含 `status_at` 快照）。
  `removeAllOfTodo` 必须级联删子任务与反馈，否则 `countsOf` 把孤儿算进分母、**案件进度虚高**。
- 权限分层惯例：**主任务（领导定的清单）→ 仅管理层；子任务（干活的人自己拆的）→ 承办人即可。**
  `@FullAccessOnly` 在方法执行前拦截，分层权限**做在 Service 里没用**，注解要去掉。
- 两条完成规则（佐证材料要求已废止）：主任务至少一条反馈说明才能完成 + 子任务全完成才能完成主任务。
  前后端判定必须**同一口径函数**，否则「前端可点、后端拒收」。
- **提交只能由用户在界面点**（`done()` 不得自动写反馈）；汇报弹窗里用户亲手填的说明
  是主动提交的汇报内容，完成路径要显式 `addFeedback`——两者不是一回事。
- 勾选子任务 = 弹出与主任务相同的完整汇报弹窗，**不能在列表里直接勾掉**
  （`TodoDetailDialog.openCompleteSub`）；按用户实际选的落实状态分派，选「完成」才 `done()`。
- 疑问问答 `case_question`：独立于待办（不派生待办、不进完成规则、不进反馈流）。
  管理层可修订已回答内容（另存 `answer_edited_by/at`，不覆盖首次回答人）。
- 统一信箱 `case_notification`：`LogService.log` 之后接 `NotificationService.onLog` 自动分发。
  **按收件人维度**：普通用户=本案 ACTIVE 承办/协办；管理层=全站所有用户（除自己）。
  白名单必须用**真实 module_action**（`CASE_OPINION_ADD` 而非 `OPINION_CREATE`）。
  通知带 `anchor_todo_id/anchor_question_id/anchor_subtask_id` 精确跳转并高亮；
  目标已删除时不中断跳转。
- 意见收件箱 `case_opinion_read`：已读是**登录人维度**，不能塞意见行。
  未读口径 = `feedback_status` 为空 且 本人无已读记录（与 `welcomeSummary.newOpinionCount` 同口径，
  数字与列表条数不一致必被当 bug 报）。
- 浮窗里长列表一律**默认折叠 + 摘要行**（反馈默认最新 3 条；子任务收成一行摘要）。
- 路由定位链：`?caseId&todoId&questionId&subtaskId → CaseDetailDrawer → CaseTodoPanel
  → TodoDetailDialog.openAt(...)`，目标元素用 `data-cf-qid`/`data-cf-subid`，高亮 ~2.6s。

## 案件类型门控（2026-10-04）
受门控 4 栏目：案件盯办/待办总览/案件管理/到期提醒（工作台、员工图谱、类别、账号**不受**）。
- 状态存 `store/caseType.js` + `localStorage(cf_case_type)` **双写**（只存内存的话 F5 就变相重置，
  与「未手动退出就保持」冲突）。登出时 `reset()`。
- 三类型：刑事 CRIMINAL / 行政 ADMINISTRATIVE / **其他 OTHER**
  （OTHER = `NOT IN(刑事,行政) OR IS NULL`，**不能用 eq 否则漏存量**）。
- 统一注入点 `withCaseType(params)`；门控两道防线：`NavPanel.onSelect` + `router.beforeEach`。
- 跨栏目跳转必须用 `gotoGated(router, path, query)`——裸 `router.push('/cases')` 会被守卫弹回，
  **原 query 的 status/employeeId 一起丢**。
- 给角色加路由白名单时，**门控引导页 `/case-type` 必须一并加**，否则引导链路自己把自己拦死。
- 顶栏「退出」=退出登录；类型退出是独立第二行 `.cf-ctypebar` 的「**退出类型**」，别混用。

## 办案组别 + 负荷详情
`flow/PoliceGroup.java`：INITIAL初查组 / CLEAR清案组 / NONE不限。
约束：初查→初查组或不限；刑拘在办→清案组或不限；其他不限制（取保刻意不限制）。
- **权威来源是 `org_employee.police_group` 不是账号**（账号可没绑员工档案）。
- 推导口径前后端必须一致（`CaseService.moduleOf` vs `AssignDialog.requiredGroup`），
  不一致会「前端不置灰、后端却拒收」。
- NULL/非法一律归一为「不限」：存量员工不改也能被指派，上线即瘫痪比放宽更糟。
- `EmployeePicker` **置灰而非隐藏**不匹配的人，并显示「只能由X人员承办」。
- 负荷详情 `GET /cases/staff/{id}/workload`：只返概要不返案情（防横向信息泄露）。

## 案件流程流转（2026-10-04）
**流程定义唯一入口：`app/backend/.../flow/CaseFlowTemplate.java`**（声明式 Stage→Step→Task）。
细化流程**只改这一个类**。引擎 `service/FlowService.java`，前端 `components/FlowPanel.vue`，
文档 `docs/案件流程流转设计.md`。
- `case_info.flow_stage`：INITIAL初查 / DETAIN刑拘在办 / BAIL取保及监居 / CLOSED已终结
- 任务复用 `case_plan`，加 `stage/step_key/task_key/is_std` 四列（**不新建表**）
- **进度不落库**，实时算；流转时事务内「旧任务 CANCELLED→改 stage→生成新任务」→ 天然归零
- 与 `investigation_status`（盯办 START→SUBMIT→APPROVE）并存不冲突
- 流转**需管理层确认**且必须显式选分支；标准任务(is_std=1)管理层可勾，民警自建任务仅承办人
- 自检 `scripts/verify_flow.py` 41 项（核心断言=流转后进度归零）

## 部署与权限（已上线）
- 访问 `http://<内网IP>:8080/api/`（context-path=`/api`，IP 随 WiFi 变别写死）。
  `start_lan.py [--detached]` 打印地址+二维码+防火墙自检；**开放防火墙用
  `scripts/open_lan_firewall.py`**（.bat 版已废弃）。跨网推荐 Tailscale>cpolar。
- 权限：`Roles.java` = BOSS/CHIEF/DEPUTY_CHIEF/LAW_OFFICER（全权限）+ STAFF（受限）。
  `@FullAccessOnly` 标 controller 方法由 `PermissionInterceptor` 拦；办结/撤销=管理层专属。
  **前端 getter 名是 `isFullAccess`（不是 fullAccess，写错按钮静默消失）**。
- 账号：注册 `audit_status` 非 1 不能登录；手机号必填+唯一+`^1[3-9]\d{9}$`，
  登录名可空（空则用手机号）；BCrypt，明文首登自动升级；
  `TokenStore` 多端 5 会话——**停用/删除/重置密码必须 `removeAll`**（拦截器不回查 status）。
- 打包：前端 `base='/api/'`，pom 拷 `frontend/dist` 进 `static`，必须 `clean package`，
  先 `taskkill` 8080；托管模式 LoginInterceptor 必须放行 `/`、`/index.html`、
  `/assets/**`、`/favicon.ico`。给 Windows 写 `.bat` 必须 **GBK + CRLF**。
- 「手机打不开本机能」= 防火墙未放行 8080 入站，**本机自查永远通不能作证**。

## 本地 MySQL
实例在 `D:\caseflow-db\`（**必须纯 ASCII**：mysqld 在中文路径下会截断 datadir）。
应用账号 caseflow/caseflow、管理 root/root，库名 case_flow。
生命周期 `mysql_local.py init|start|stop|status|setup|env|reset`。
坑：JDBC `characterEncoding` 写 UTF-8（utf8mb4 抛 Unsupported encoding）；
`my.ini` 不能开 `skip-name-resolve`（否则 127.0.0.1 拒连）；Windows mysqld 按 GBK 解码选项文件。

## 用户交互偏好
- **所有操作直接执行，不先问**（2026-10-08 明确，最高优先级）：
  改代码、跑自检、重启服务、造数、清理、编译、commit 一律直接做，
  **不要先说「要不要我…」「是否需要…」**。这条覆盖「先讲方案等确认」的习惯。
  只在需求本身有歧义或不可逆破坏操作时才提问。
- **改完一个完整功能模块就立刻 commit**（怕断电，见用户级记忆）。
  触发时机：模块做完 / 验证通过准备换方向 / 即将执行高风险大动作 / 任何自然停靠点。
  提交用 `git commit -F -` + heredoc 写多段中文说明。
- **push 不自动做**（2026-10-08）：等用户下午手动说。本地 commit 照做，
  有未推送提交时在回复里说明「本地已 commit，push 等你吩咐」，别默默推。
- **执行中途卡住（SIGTERM/超时）立刻 commit 当前进度当断点**，标注「断点 N/M」，
  然后**继续跑，不要停下来等用户回复**。后端重启我自己来，不用反复问。
- 用户说「像 X 一样」时，指的是**交互形式照搬 X**，不是「功能等价」。
  修 UI 行为需求前先问：用户在界面上看到的**动作序列**是什么？
- 反馈「排版乱了」时优先怀疑刚插的常驻说明文字、flex 主行、循环依赖高度。
