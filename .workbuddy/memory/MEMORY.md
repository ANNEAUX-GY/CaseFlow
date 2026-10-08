# 项目长期记忆：案件指派demo（CaseFlow）

> 调试踩坑与细节备查见 `LESSONS.md`（不注入，排查具体问题时翻）。
> 本文件只保留**每次开工都要知道**的事实与硬约定。

## 定位
收案件 → 指派（主办+协办）→ 盯到期 → 办结，含操作追溯+撤回。反馈与排期见 `docs/`。

## 目录与 Git
- 仓库根 `D:\案件指派demo\`，**源码在 `app/`**，工具链在 `tools/`。
  PyCharm 必须打开 `app/`（`.idea/vcs.xml` 已把 Git 映射到上一级）。
- 虚拟环境 **`app/.venv`（Python 3.10.11，不是 3.13）**；PyCharm SDK 显示几.x 不影响运行，别改。
- 远程 https://github.com/ANNEAUX-GY/CaseFlow （public，main）。push 细节见 LESSONS.md。
- 本机专属排除写 `.git/info/exclude`（**不改被跟踪的 .gitignore**）。已排除
  `app/backend/src/main/resources/sql/`、`shots/`、`ppt/*/preview/`。
- `app/backend/data/h2/caseflow.mv.db` 演示库被后端进程占用，**每次提交手动跳过**。
- Windows 建目录链接必须 PowerShell Junction；给 Windows 写 `.bat`/`.ps1` 必须 **GBK + CRLF**。

## 常用命令（均在 `app/` 下）
- 开发：`.venv\Scripts\python.exe scripts\dev.py [--profile mysql] [--seed] [--check]
  [--kill-existing] [--no-frontend] [--no-backend]`（连外部库加 `--skip-db-check`）
- 自检：`verify_*.py`；`check_vue_imports.py`；造数 `seed_demo.py --base http://127.0.0.1:8080`
- dev 用 H2 文件库，**同时只能一个后端进程**（报 `Could not open .mv.db` 即重复启动）。

## 技术栈与硬约定
- **Spring Boot 2.7 + JDK8**。新建后端文件：`javax.annotation.Resource`（非 jakarta）、
  `com.caseflow.security.AuthContext`、JDK8 无 `List.of()` 用 `new ArrayList<>()`。
- 加字段统一走 `bootstrap/SchemaMigration.NEW_COLUMNS`；**schema.sql 双副本**
  （`app/backend/src/main/resources/sql/` 与 `app/sql/`）必须同步改；
  二级索引只能放 `sql/index-mysql.sql`。
- 前端 Vue3 + Element Plus + Pinia + ECharts 5.5.1。**新图表类型必须先在 `EChart.vue`
  注册**，否则静默不渲染零报错；主题 `utils/chart.js`，组件 `EChart.vue`（notMerge=true）。
- 公安配色：警蓝 `#1b4a8c` / 深藏蓝 `#12294a` / 警徽金 `#c8a45c`；布局基准全在
  `styles/index.css`，页内别另写。
- **全项目禁悬浮提示**（el-tooltip 归零、原生 `title` 清零），说明改常驻内联文字。
- **纯桌面端内网应用（2026-10-08 起不做移动端）**：全部移动端代码已删。
  不写窄屏 media；窄窗口用桌面级断点（现存 4 条 1440/860px 只管 KPI 网格）。
- 跨组件计数/状态必须放 Pinia store；刷新计数走后端重数，别本地 `-1`。
- 勾选子任务 = 弹完整汇报弹窗（不能列表直接勾掉）；样式 `.cf-check` 四态在 `styles/index.css`。

## 核心设计（细节见 LESSONS.md）
- **层级**：总→副总→组长→员工，由职务推导（后端 `flow/OrgRank.java`，前端镜像
  `utils/org.js`，两边必须一起改）。部门一律下拉选已有部门；部门异常标橙。
- **追溯撤回**：写操作前后双快照；撤回=写前快照原样写回；UNDO 本身记日志（再撤=重做）。
  新增写操作别漏带 before/after。
- **SSE**：`LogService.log` 是全量埋点唯一汇聚点 → SseHub → `store/events.js`。
  改埋点只动 LogService 一处。
- **待办是意见的唯一载体**（勿回退）：`case_todo.opinion_id` 派生；`parent_id` 两级。
  主任务权限=管理层，子任务=承办人；`@FullAccessOnly` 在方法前拦截，Service 分层无效。
- **案件类型门控**：5 栏目受门控（含 /case-boards）；状态双写 store+localStorage；
  跨栏目跳转用 `gotoGated`。
- **按类别浏览板块页**（2026-10-08）：选完类型默认落 `/case-boards`，按字典小类
  分卡（案件数=page size=1 取 total，与列表同链路）→ 点卡进 `/cases?category=X`；
  「未分类」走后端 `category=NONE`（IS NULL OR 空串）；OTHER 类型无小类只有全量卡。
- **办案组别**：`org_employee.police_group` 为权威，NULL/非法归一「不限」，前后端口径必须一致。
- **流程流转**：定义唯一入口 `flow/CaseFlowTemplate.java`；进度不落库实时算；
  流转后旧任务 CANCELLED、进度归零。

## 部署与权限
- 访问 `http://<内网IP>:8080/api/`；防火墙放行 8080 用 `scripts/open_lan_firewall.py`；
  本机自查通不能作证「手机能打开」。
- 角色：BOSS/CHIEF/DEPUTY_CHIEF/LAW_OFFICER（全权限）+ STAFF（受限）；
  `@FullAccessOnly` 由 PermissionInterceptor 拦；**前端 getter 名是 `isFullAccess`**。
- 账号：`audit_status` 非 1 不能登录；手机号唯一；停用/删除/重置密码必须 `removeAll`。
- 打包：前端 `base='/api/'`，pom 拷 dist 进 static，必须 clean package，先杀 8080。

## 用户交互偏好
- **所有操作直接执行，不先问**（最高优先级）：改代码、自检、重启、造数、commit
  一律直接做；只在需求有歧义或不可逆破坏操作时提问。
- **完成一个功能模块就立刻 commit**（怕断电）；提交用中文说明改了什么/为什么。
- **push 不自动做**：等用户手动说；有未推送提交时回复里说明「本地已 commit，push 等你吩咐」。
- 执行中途卡住（SIGTERM/超时）立刻 commit 进度当断点，然后继续跑，不等用户回复。
- 用户说「像 X 一样」= 交互形式照搬 X；修 UI 需求前先确认用户看到的动作序列。
- 反馈「排版乱了」优先怀疑：常驻说明文字、flex 主行、循环依赖高度。
