# 项目长期记忆：案件指派demo（CaseFlow）

## 项目定位
极简案件指派系统：收案件（PDF/Word/Excel/手打）→ 指派（主办+协办）→ 盯到期 → 办结，含操作追溯+撤回。2026-09-30 汇报后收集反馈（原始笔记 Desktop\记录.docx，整理件 Desktop\记录-反馈整理.docx）：字段扩充、多档监督点、转手审批、盯办、嫌疑人录入、网盘对接、AI 笔录、案管工作台等，排期以整理件为准。

## P0 已完成（2026-09-30），排期进度
- **P0 全部落地**：case_type 案卷类型（CASE_TYPE 字典：未立案/刑事/行政/民事）+ filing_no/mediation_no + category=小类（案由）+ 新表 case_suspect（嫌疑人，SUSPECT_ADD/DELETE 可撤回）+ hasSuspect 筛选 + caseTypeDist/categoryDist 统计。自检 scripts\verify_p0_fields.py（32 项）。
- **案件类型强制必选，"未分类"已废除**：save() 统一入口（手动+上传材料建案都过它）校验 caseType 非空，新建/编辑都拦；图表口径（caseTypeDist/dueByType/typeTrend）跳过 null 类型；列表列 null 显示"—"。存量未分类案件（民事去除清回）数据保留、编辑时强制补类型自然清零。**自检造数脚本（verify_lan/undo/persistence）必须带 caseType，否则被 500 拦**。
- **分类交互是级联（用户第二轮纠正过）**：筛选栏与表单都是一个 el-cascader「案件分类」（checkStrictly 允许只选大类），树来自后端 `GET /case-categories/tree`（新表 case_category，种子：刑事=电诈/接触性诈骗/故意伤害类/盗窃类/其他、行政8个不动、未立案无小类；**民事大类已整体去除**，CIVIL 存量清回未分类）。小类由「类别管理」导航(/categories) CRUD，管理权限专属，改后 categoryStore.load(true) 刷新。别再改回两个同级下拉/写死树。
- **按经办人查案**：CaseList 工具栏「经办人姓名」remote 搜索（employeeApi.search）→ query.employeeId → 按 case_assignee ACTIVE 关联查该民警所有状态案件；详情抽屉「办理进度」时间线来自 `GET /logs/case/{caseId}`（module=CASE+targetId 正序）。route.query 现处理 employeeId+employeeName（工作台承办人柱体跳转联动）。
- e2e 坑：Element Plus remote el-select 内部输入框是 `.el-select__input`（无 placeholder），点 wrapper 后直接 keyboard.type，别再 evaluate focus（会把焦点搞丢）。
- **工作台「案件类型分析」栏**：类型分色规范在 chart.js 的 CASE_TYPE_COLORS（红刑事/蓝行政/白未立案+灰边）；stats 接口支持 caseTypes/dueBuckets/priorities 逗号分隔多值组合筛选（维度间AND维间内OR，不传=旧行为）；图例即筛选 chips + 期限/优先级多选 + 三图联动 + 空态提示。白色系列必须 borderColor 描边（barOption 已支持系列级 borderColor）。
- 待做：P1=多档监督点+进度清单+转手审批+盯办+按人查案；P2=附件/网盘+分色折线图+重点标签；P3=AI+案管工作台。
- **坑：schema.sql 双副本**——运行时加载 backend\src\main\resources\sql\schema.sql，与根 sql\schema.sql 是两份文件，改表必须同步两份（曾漂移导致"表不存在"）。
- 快照 JSON 现为 `{exists, info, assignees, files, suspects}`；insertWithId/restore 全字段 set/fullUpdate 三处补新列，撤回精度靠这个，加字段别漏。
- e2e 坑：el-cascader 隐藏 radio 上 dispatchEvent 无效，用 page.mouse 按节点 bounding box 真实点击；checkStrictly 点节点文字即选中。

## 追溯与撤回（核心设计，改动保持一致）
- 写操作前后各存完整快照，撤回=把 snapshot_before 原样写回；快照 JSON `{exists, info, assignees, files}`。表 operation_log（snapshot_before/after 为 TEXT、MySQL 不能带 DEFAULT；undone/undo_log_id/undo_of）。
- 代码：CaseSnapshotService / OperationLogService / LogController(/api/logs*) / LogService.log 带快照重载。CaseService 的 create/update/assign/status/delete 每个写操作必须带 before/after，新增写操作别漏。
- 坑：restore 必须全字段 LambdaUpdateWrapper.set（updateById 跳 null，清空期限撤不回）；还原原 id 必须手写 @Insert insertWithId（IdType.AUTO insert 时省主键列）。撤回规则：仅案件类、必须是该案件最新一条、已撤不可再撤；UNDO 本身记日志，故再撤=重做。校验 scripts\verify_undo.py。

## 固定约定
- 风格简约高密度；**侧栏导航顺序（2026-10-04 定，管理层 8 项）**：工作台 → 案件盯办 → 待办总览 → 案件管理 → 到期提醒 → 员工图谱 → 类别管理 → 账号管理（待办总览是新版新增，普通民警分支仍是我的案件/到期提醒两项）。公安配色警蓝#1b4a8c/深藏蓝#12294a/警徽金#c8a45c，色板与布局基准（.cf-page gap16、面板头44px）全在 index.css，页内别另写；同排等高用 cf-panel--fill + 内容自适应（EChart height=100%）。
- 图表 ECharts 5.5.1：主题统一 utils/chart.js（CHART 色板+baseOption），组件 EChart.vue（setOption 必须 notMerge=true）+ ChartPanel.vue；统计接口 GET /api/cases/stats?days=N 返回 9 组口径。
- 无障碍基准（使用者 45-50 岁）：导航项 56px/17px/警徽金激活竖条、顶栏 62px，数值集中 index.css 的 .cf-aside/.cf-header 段。列表类容器必须写死高度（如 .cf-oplog{height:230px}），flex:1+min-height 会循环依赖把面板顶爆。
- 截图核对：puppeteer-core 在 node workspace；脚本 cf-shot/cf-logshot/navshot/lanshot/cdshot/cf-mobile-*(.mjs)。坑：querySelector('.el-drawer'/'.el-dialog') 会选到隐藏的第一个，必须 .find 按内容/可见+标题定位；点行按第一列 caseNo 精确匹配；注入登录态必须同时写 cf_token 和 cf_user；颜色判定用 Pillow Counter 取目标区域色，别只看截图。
- 移动端（utils/device.js，MOBILE_MAX_WIDTH=768 必须与 @media 成对改）：initDevice() 在 createApp 前调；侧栏一份实现 NavPanel.vue（variant=aside|drawer），选择器必须是 .cf-nav .el-menu*；列表手机端走卡片（CaseTable=.cf-ccard、UserManage=.cf-ucard），宽表套 .cf-tscroll 横滑+面板头文字提示；.cf-panel__head 手机端 height:auto;min-height:42px;flex-wrap:wrap（写死会压正文）；弹窗横向溢出先查 min-width 链（el-form-item__content 及子元素 min-width:0;max-width:100%）；iOS 输入 16px、justify-content:safe center、不禁缩放、safe-area；核对 scrollWidth-innerWidth 必须=0，并做桌面/手机 UA 交叉验证。
- 组件坑：跨组件计数/状态必须放 Pinia store（Layout.vue 整个会话只挂载一次，局部 ref 不会重取；待审核数在 store/pending.js，刷新走后端重数别本地-1）；@Resource 按字段名装配，字段名撞 Bean 名抛 BeanNotOfRequiredTypeException。
- dev 用 H2 文件库，同时只能一个后端进程（报 Could not open .mv.db 即重复启动）。给已有库加字段统一走 bootstrap/SchemaMigration.NEW_COLUMNS（schema.sql 的 IF NOT EXISTS 不补列；MySQL ALTER 无 IF EXISTS）。后端端口用 CF_SERVER_PORT，且启动必须 --server.port 命令行压过本机 SERVER__PORT。
- 员工图谱 Excel 列固定：姓名/工号/上级工号/部门/职务/手机/邮箱；演示账号 boss/admin123；自检另有 e2e_staff/e2e_law（密码 e2e123456）。Java 用 Spring Boot 2.x + JDK8（C:\Program Files\Java\jdk1.8.0_172，JDK25 不兼容）。页脚统一 PageFooter.vue，标语集中 src/config/slogans.js。npm install 会 prune 掉 rollup/esbuild 原生包，已用 optionalDependencies 固化。

## 目录结构与版本控制（2026-10-04 起，重要）
- 本项目已纳入 Git：远程 https://github.com/ANNEAUX-GY/CaseFlow （public，main）。**下文所有路径均相对 `D:\案件指派demo\app\`**（源码根），若在别处看到 `frontend\src\...` 就是这个 app 目录。
- **仓库根 ≠ 源码目录**：仓库根是「开发环境包」，源码在 `app/`，工具链在 `tools/`。**PyCharm 必须打开 `app/` 目录**，`app/.idea/vcs.xml` 已把 Git 映射到上一级；10 个运行配置在 `app/.idea/runConfigurations/`（解释器统一 `$PROJECT_DIR$/.venv/Scripts/python.exe`，Working dir `$PROJECT_DIR$`）。
- 远程脚本只认 `tools/maven`、`tools/jdk8`、`tools/node`、`tools/m2repo`；本机已建 junction（`tools/maven`→apache-maven-3.9.9，`tools/jdk8`→C:\Program Files\Java\jdk1.8.0_172）。**Windows 建目录链接必须用 PowerShell `New-Item -ItemType Junction`**，git bash 的 `ln -sfn` 对目录会生成坏链接，中文路径下 cmd `mklink` 也报语法错。
- 虚拟环境在 `app/.venv`（不在仓库根，远程 `_common.venv_python()` 只找 `ROOT/.venv`）。
- 本机专属 git 排除写在 `.git/info/exclude`（**不改被跟踪的 .gitignore，否则与远程漂移**；该文件不支持行尾注释，只能整行 `#`）。身份 adamin/adamin@users.noreply.github.com 为仓库级；已实测 push 成功，本机有可用凭据。
- `app/.venv` 是 **Python 3.10.11**（旧环境，依赖齐全），不是 3.13；运行配置 `IS_MODULE_SDK=false`+`SDK_HOME` 直指它，故 PyCharm 项目 SDK 显示几.x都**不影响运行**，别改。
- 重编译后端必须用 dev.py 同款命令（中文路径下 `-f` 会编码损坏）：`JAVA_HOME=jdk8 tools/maven/bin/mvn.cmd -B -o -Dmaven.repo.local=D:\案件指派demo\tools\m2repo compile`（在 app/backend 下执行）。
- `app/backend/data/h2/caseflow.mv.db` 是演示库且被后端进程占用（git checkout 会Permission denied），**每次提交都要手动跳过它**。
- **ECharts 加图表类型前必须先在 `EChart.vue` 的 `echarts.use([...])` 注册对应 Chart**（已注册 Bar/Line/Pie/Tree/Graph），否则**静默不渲染**——容器 div 在、canvas 无、控制台零报错，极难发现。

## 数据库（本地 MySQL）
- 实例在 D:\caseflow-db\（必须纯 ASCII：mysqld 在中文路径下会截断 datadir，报 Failed to set datadir）；应用账号 caseflow/caseflow、管理 root/root，库名 case_flow。生命周期 mysql_local.py init|start|stop|status|setup|env|reset。
- 坑：JDBC characterEncoding 写 UTF-8（写 utf8mb4 抛 Unsupported encoding）；my.ini 不能开 skip-name-resolve（否则 127.0.0.1 拒连）；Windows mysqld 按 GBK 解码选项文件。

## 案件盯办模块（2026-09-30 已上线）
- case_info +4 列（case_measure/measure_date/detain_deadline/investigation_status）+ 新表 case_plan/case_approval；快照含 plans 列表（撤回可还原计划）；UNDOABLE 含 PLAN_*/INVESTIGATION/MEASURE。
- 状态机：待初查→(START)→侦查中→(SUBMIT，需≥1条DONE计划)→待审批→(APPROVE/REJECT 管理层，REJECT 意见必填)→侦查终结/回侦查中；措施登记 measure 默认期限刑拘+30天/取保+12月/监居+6月。全留痕可撤回。
- 接口 /watch/*（WatchController）；前端 /watch WatchView + WatchDrawer。三子模块=case_measure 动态视图（INITIAL=无措施+在办）。嫌疑人检索 suspectName/suspectIdCard（apply 参数化）。方案文档 docs\案件盯办模块设计方案.md。

## 全局实时事件流（SSE，2026-09-30）
- LogService.log 落库后统一广播（全量埋点唯一汇聚点）→ SseHub 广播给所有在线 EventSource；端点 GET /api/events/stream?token=（**LoginInterceptor：header 优先、query token 兜底**，EventSource 带不了 header）。event name=case-event。
- 前端 store/events.js（Pinia，subscribe(fn)/connect，onerror 连败 5 次停连）；Layout onMounted connect；订阅点：CaseDetailDrawer 办理进度（800ms 防抖）、Dashboard 最近操作（2s 防抖）。
- **改后端埋点/广播逻辑只动 LogService 一处**；新页面要实时刷新就 eventStore.subscribe。actionName 已改 public。

## 部署与权限（已上线）
- 访问 http://<内网IP>:8080/api/（context-path=/api，IP 随 WiFi 变别写死）；start_lan.py [--detached] 打印地址+二维码+防火墙自检；verify_lan.py 42 项自检；**开放防火墙改用 scripts\open_lan_firewall.py（.py 版自动提权，.bat 版已废弃）**。跨网推荐 Tailscale>cpolar，方案见 docs\跨网访问方案.md。
- 权限：Roles.java = BOSS/CHIEF/DEPUTY_CHIEF/LAW_OFFICER（全权限）+STAFF（受限）；@FullAccessOnly 标 controller 方法由 PermissionInterceptor 拦；办结/撤销=管理层专属（CaseService.MANAGER_ONLY_STATUS）。前端 getter 名是 isFullAccess（不是 fullAccess，写错按钮静默消失）；路由守卫读 localStorage。
- 账号：注册 audit_status 非 1 不能登录；手机号必填+唯一+^1[3-9]\d{9}$，登录名可空（空则用手机号），AuthService 先登录名后手机号（selectList 取首条）；BCrypt，明文首登自动升级；TokenStore 多端 5 会话——停用/删除/重置密码必须 removeAll（拦截器不回查 status）。
- 打包：前端 base='/api/'，pom 拷 frontend/dist 进 static，必须 clean package，先 taskkill 8080；托管模式 LoginInterceptor 必须放行 /、/index.html、/assets/**、/favicon.ico。给 Windows 写 .bat 必须 GBK+CRLF。「手机打不开本机能」=防火墙未放行 8080 入站，本机自查永远通不能作证。

## 常用命令（均在 app/ 目录下执行）
- 开发：.venv\Scripts\python.exe scripts\dev.py [--profile mysql] [--seed] [--check]（连外部库 --skip-db-check）
- 自检：verify_lan.py / verify_undo.py / verify_persistence.py；造数 seed_demo.py --base http://127.0.0.1:8080；建库 init_db.py --password <pwd>
- Git：git pull（已 track origin/main，push 已实测可用；本机专属忽略写 .git/info/exclude）
- 本地 curl 一律加 --noproxy '*'（本机代理会 502 误判）；编译必须 JAVA_HOME 指向 JDK8；生产化清单 docs\生产化改造指南.md
- 本地 curl 一律加 --noproxy '*'（本机代理 127.0.0.1:54302 会 502 误判）
- 编译必须 JAVA_HOME 指向 JDK8；生产化清单 docs\生产化改造指南.md

## Git 提交纪律（2026-10-04 用户明确要求，最高优先级）
- **「每次运行完代码大修改的任务后，立刻 commit + push」** —— 用户明确要求，怕断电丢失。
- 触发时机（见到就提，不用等用户催）：
  1. 改完一个完整功能模块（如「领导意见增强」六项做完）
  2. 编译/实测通过，准备换方向或换模块时
  3. 即将执行可能失败的大动作（重装依赖、清库、批量改名）
  4. 任何自然停靠点
- 规则已写进仓库根 `CONTRIBUTING.md` 第三节「断电防护」+ 第七节「哪些文件不该进库」，对全团队生效。
- 提交前必跑 `git status --porcelain` 确认暂存区；**每次都要手动跳过
  `app/backend/data/h2/caseflow.mv.db`**（后端占用时 checkout 会 Permission denied）。
- 推送失败**不阻塞后续开发**，但必须在回复里说明「还有未推送的提交」。
- 提交用 `git commit -F -` + heredoc 写多段中文说明，别用单行 -m 塞长文本。

## 坑：git push 卡死（2026-10-04 排障，务必记住）
**现象**：push 一直挂 4 分钟无输出，kill 掉。`ls-remote` 却秒回。
**根因**：便携 Git 的**系统级**配置 `PortableGit/versions/1.2.0/etc/gitconfig` 里
`[credential] helper = helper-selector`，它会**弹 GUI 选择框**等凭据 —— 无人值守时永远等不到，
GIT_TRACE 显示它在 `credential-helper-selector get` 一步就耗了 19 秒，随后才转到
`credential-manager` 又弹一次登录框。**不是网络问题**（curl 走代理 0.38s 就通）。

**已修复（仓库级，永久生效）**：
```
git config --local credential.helper ""
git config --local credential.helper manager
```
验证：普通 `git push --dry-run` 现在秒回 "Everything up-to-date"。

**若仍卡，临时用**：`git -c credential.helper= -c credential.helper=manager push`
**不要**去改便携 Git 的 etc/gitconfig（会影响整个 WorkBuddy 环境）。

## 坑：git push 的代理方向（两种都踩过，别记死一个）
环境有 `http_proxy=http://127.0.0.1:61613`（含大小写四种）。
- **直连卡住无输出** → 加 `env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY` 绕过
- **直连报 Connection was reset**（12:45 遇到）→ **去掉 env -u，走代理**，5 秒推完。
  当时 curl 测 info/refs 是 200/0.5s，但 git push 被 reset = 直连被限流，不是配置问题。
判断：报 reset 就去掉 env -u；卡住就加上。**别固定绕过**。
本地 curl 测 API 要加 `--noproxy '*'`（否则 502 误判），但推送不要照抄。

## 已设的 git 传输参数（仓库级，若推送异常可重置）
`http.version=HTTP/1.1`、`http.postBuffer=524288000`、`http.lowSpeedLimit=0`、`http.lowSpeedTime=999999`

## 案件流程流转模块（2026-10-04，commit cd45047/fbe73d8/5b01029/d5361e8/23fad49）
**流程定义唯一入口：`app/backend/src/main/java/com/caseflow/flow/CaseFlowTemplate.java`**
声明式三层 Stage→Step→Task，后续细化取保/加环节/调分工**只改这一个类**，不动引擎与前端。
引擎 `service/FlowService.java`，前端 `components/FlowPanel.vue`，设计文档 `docs/案件流程流转设计.md`。

- 阶段 `case_info.flow_stage`：INITIAL初查/DETAIN刑拘在办/BAIL取保及监居/CLOSED已终结
- 任务复用 `case_plan`，加 stage/step_key/task_key/is_std 四列（**不新建表**）
- **进度不落库**，实时算 =该阶段 DONE/该阶段总数；流转时事务内「旧任务CANCELLED→改stage→生成新任务」
  → 分母换新分子为0，**天然归零，不需要"重置"动作**（不会出现"忘了重置"的历史包袱）
- **与 investigation_status 并存不冲突**：后者是盯办 START→SUBMIT→APPROVE 审批状态机
- 流转**需管理层确认**且必须显式选分支（刑拘/取保/释放后果不同）
- 任务勾选权限**分级**：标准任务(is_std=1)管理层可勾；民警自建任务仍仅承办人（保住原盯办设计边界）
- 自检 `scripts/verify_flow.py` 41 项（核心断言=流转后进度归零），回归 `verify_assign_status.py` 20 项

## 坑：MyBatis-Plus apply() 不能拼排序（2026-10-04 踩到）
`.apply("... ASC, id ASC")` 拼进的是 **WHERE 条件**，生成
`WHERE (case_id=? AND (CASE..END) ASC, id ASC)` → H2 直接 JdbcSQLSyntaxErrorException。
排序必须用 `.last("ORDER BY ...")`。**这个坑在 OpinionService.sortOrder 上犯过一次。**

## 用户交互偏好（2026-10-04 明确）
- **若执行中途卡住（SIGTERM/Stream timeout），立刻 commit 当前进度当断点**，
  提交信息标注「断点 N/M」并列出待完成项，**然后继续跑，不要停下来等用户回复**。
- 后端重启我自己来，不用反复问用户。

## 案件类型统一选择器/ 门控（2026-10-04，commit ea0b0aa/fc5cf9c/bd82851）
受门控 4 栏目：案件盯办/待办总览/案件管理/到期提醒（工作台、员工图谱、类别、账号**不受**）。

- **状态存哪**：`store/caseType.js` Pinia + localStorage(`cf_case_type`) **双写**。
  双写的原因：只在内存的话 F5 刷新就变相重置了，与"未手动退出就保持"冲突。
  登出时 `reset()`——不能让下一个人继承上一个人的类型选择。
- **三个类型**：刑事CRIMINAL / 行政ADMINISTRATIVE / **其他OTHER**。
  OTHER = `NOT IN(刑事,行政) OR IS NULL`（含未立案+历史空值），**不能用 eq 否则漏存量**。
- **统一注入点 `withCaseType(params)`**：所有受门控栏目的请求都必须走它。
  不各页面手写 caseType——漏一个就会出现"列表锁了类型、图表没锁"的错位。
- **门控两道防线**：NavPanel.onSelect（拦el-menu 的 router 自动跳转，避免闪一下再被弹回）
  + router.beforeEach（防手敲地址/F5，守卫里拿不到 Pinia 直接读 localStorage）。
- **跨栏目跳转必须用 `gotoGated(router, path, query)`**（工作台 3 处 + 员工图谱 2 处）。
  裸 `router.push('/cases')` 在未选类型时会被守卫弹回选择器，**原 query 的 status/employeeId 一起丢**。
- 顶栏「退出」退的是**类型选择**，与旁边「退出登录」语义分离，不要混用/删错。
- 提示条 `.cf-gatebar`（index.css）明示"共 N 件 · 需换类型请点右上角退出"。
- WatchView 的 `reset()` **不重置 caseType**——它归门控管，重置筛选不该放开类型。
- from 参数防注入：只接受 `/` 开头 + 只允许 GATED_PATHS 里的栏目，否则 `from=/users` 能跳管理页。

## 民警端待办事项（2026-10-04，commit 3aa0a06/f86c5e0）
**待办由领导意见自动派生**（用户选定方案），不两套人工录入。
- `StaffTodoService.deriveFromOpinion`：OpinionService.add 末尾调用；
  opinion_id 关联 + 幂等（同意见不重复建）；**派生失败不阻断意见保存**（记日志）。
- 映射：意见内容→标题、deadline→deadline、提意见人部门→dept_source；
  意见 importance A/B/C →待办 urgency+importance（A→紧急/重点，B→较急/次重点，C→一般）。
- 补历史：`POST /todos/backfill-from-opinions`（幂等，实测补出 3 条）。
- **紧急程度(手动三档) vs 截止时间(客观约束)刻意分开**：排序只用紧急程度，
  否则待办会随时间自己往前挪。dueSoonCount 用截止时间算（≤3天含已超期）。
- 排序：默认 紧急↓›重点↓›截止↑（无期限沉底）；支持 sortBy 逗号分隔多字段组合。
- 权限：收敛 myVisibleCaseIds()，与案件列表同一套口径。
- 侧栏红点 `store/myTodo.js`（同 pending.js 模式），一次 welcome-summary 喂红点+弹窗。
- 欢迎弹窗：Login 写 sessionStorage 标记 → **Layout 挂载时消费**（Login 已被销毁挂不住弹窗）。
- 坑：format.js 已有意见模块的 `URGENCY_META`（已逾期/临期/正常，按时间算），
  待办的是手动三档 → **新常量必须加 TODO_ 前缀**，两个别混用。

## 坑：Vue 模板 :class 数组里不能写两个对象字面量
`:class="[{ 'is-zero': x }, { 'is-' + t }]"` → `Error parsing JavaScript expression`，
**整页 500，连 Layout 都加载不出来**，浏览器控制台只说
「Failed to fetch dynamically imported module」，看不出是哪个表达式。
正确写法：`:class="['is-' + t, { 'is-zero': x }]"`（字符串+单个对象）。
排查这类问题直接 `curl http://127.0.0.1:5173/src/xxx.vue | grep -i error`，
错误详情在 vite 错误浮层的 HTML 里。

## 办案组别 + 负荷详情（2026-10-04，commit d27e754/9581792/c33acd8/d26605f）
组别 `flow/PoliceGroup.java`：INITIAL初查组 / CLEAR清案组 / NONE不限。
约束：**初查→只能初查组、刑拘在办→只能清案组、其他（含取保监居）不限制**。
取保刻意不限制——它既不是初查也不是清案，限制会让案件无处可派。

- **权威来源是 `org_employee.police_group` 不是账号**：指派选的是员工，
  组别该挂档案上。`sys_user.police_group` 只用于注册强制选+审核确认。
- **推导口径前后端必须一致**：CaseService.moduleOf 按 case_measure 判
  （DETENTION→清案组，其余→不限），前端 AssignDialog.requiredGroup 同规则。
  不一致会出现"前端不置灰、后端却拒收"。也不能用 SysUser 的 group 判——用 org_employee 才对。
- **NULL/非法一律归一为「不限」**：存量员工不改也能被指派，上线即瘫痪比放宽更糟。
- 校验点三处：注册页(民警必选) / 账号管理(管理员可纠正) / 员工图谱(随时改)。
- **EmployeePicker 置灰而非隐藏**：不匹配的人置灰+显示「只能由X人员承办」，
  隐藏会让人以为系统里没别人、以为流程走不通。
- 报错文案带双方组别+姓名，让被拒的人知道该找谁。
- 负荷详情 `StaffWorkloadService` + `GET /cases/staff/{id}/workload`：
  主办/协办/合计+已超期+3天内到期+阶段分布+逐案概要；
  排序主办›逾期›期限近；**只返概要不返案情**（防横向信息泄露）。
- 盯办详情 WatchDrawer 顶上加「主办人与盯办人」区块，名字可点开负荷弹窗。

## 坑：Map<String,Object> 强转必须与 put 时类型一致
负荷详情排序里 `(Long) a.get("daysLeft")` →运行期 `Integer cannot be cast to Long`。
put 进去是 Integer 就必须按 Integer 取。**这类错编译期发现不了，只在有数据时炸。**

## 领导意见：可编辑/可移除 + 实时提醒（2026-10-04，commit 65d95d2/62f692b）
- `OpinionService.updateContent` / `.remove`（仅管理层）；移除是**软删**（content 置空），
  理由：办案人可能已针对该意见传材料/写了反馈，物理删会让记录悬空、日志快照还原不回来。
- `OpinionService.notifyAssignees`：改内容/改设置/移除都给**现役(ACTIVE)主办+协办**留点名提醒，
  只对 ACTIVE 发（历史协办人已转手不该被打扰）；提醒失败只记日志不回滚。
- `listOf` 过滤 `content IS NOT NULL AND <> ''`。
- **`resequence(caseId)`**：remove/reorder 后把可见项 sort_order 重排 1..N。
  不重排会留空洞（实测 1,2,3,5），空洞会让后续拖拽从错误基数起算、越拖越乱。

## 坑：el-radio-group 受控绑定导致「点几次就改不动」（三重叠加，务必记住）
用户报「ABC 点击几次就改不了」，根因是**三层叠加，缺一不可**：
1. 给`:model-value`（受控）而非 v-model → 内部选中态变了但父级数据没变，
   Vue 重渲染**弹回旧值**；再点同一个值 change 不再触发 → 用户感知「改不动」。
2. **不能用 reload() 刷新** —— reload 整体替换 opinions 数组，模板绑的 row 引用失效，
   乐观改的值等于改在旧对象上；且 reload 响应回来会覆盖用户紧接着的下一次点击
   （实测点 C 被拽回 B）。**改为按 id 就地同步字段** + `serial()` 串行链防并发覆盖。
3. 点击实际落在 `<span class="el-radio-button__inner">` 上，
   **用 `@click` 读 `event.target.value` 拿到的是 undefined** → 什么都没发生。
   正确做法：用 `@change` 拿组件内部权威值。
**通用教训：受控组件 + 乐观更新时，不要用 reload() 整体替换数组，按 id 就地同步。**

## 坑：漏导入 Vue 组合式 API —— 编译期不报错，运行时才炸（已加扫描脚本）
`AssignDialog.vue` 缺 computed、`Register.vue` 缺 computed/watch（都是我加功能时漏的）。
症状极迷惑：**编译全绿**，运行时 `ReferenceError: computed is not defined`，
**整个抽屉打不开**，控制台看不出是哪个文件。
排查靠 `pageerror` 事件的 e.stack（能看到 `at setup (xxx.vue:34:23)` 定位文件行号）。
**已新增 `scripts/check_vue_imports.py`** 扫全部前端文件，发版前跑一次。

## 坑：puppeteer 点el-radio-button 要点 inner span 且先scrollIntoView
- 点 `.el-radio-button__inner`（或 input）而不是 label 外层；
- **抽屉很长时第一条意见的坐标可能在视口外**，必须先 `scrollIntoView({block:'center'})`
  再取getBoundingClientRect，否则 mouse.click 落空，表现为「点了没反应」——
  我因此误判过一次产品有 bug（实际是脚本问题）。

## 类型条独立成第二行 + 类型选择页放大（2026-10-04，commit 6122499）
- **两个「退出」不能同行**：顶栏「退出」=退出登录；类型退出原也在顶栏，
  量得 top 23 vs 22 —— 同名按钮相邻，45-50 岁使用者极易误点。
  现拆开：顶栏只留退出登录，新增 `.cf-ctypebar` 独立第二行，
  右端按钮文案改「**退出类型**」消除同名歧义。
- 类型选择页放大：容器 max-width 900→1180、卡片 275×125→**353×171**、
  字号统一 +2px。三卡**必须等高**：靠 desc 的 `flex:1` 而不是 min-height 硬凑
  （原 min-height:38px 在描述长短不一会把某张卡顶矮）。

## 坑：flex 容器里写死 height 会被压扁（与"顶爆"是同一类问题的反向表现）
`.cf-ctypebar` 在 `el-container`（纵向 flex）内，
写 `height: 38px` 被压成 **23.5px**、`min-height` 也变 auto（实测）。
**解法：`min-height` + `flex-shrink: 0`**，别写死 height。
凡新增要固定高度的 flex 子项，都按这个来。

## 坑：caseMeasure 返回 JSON null 而非字符串 "NONE"
后端 `CaseInfo.caseMeasure` 无值时返回 **None（JSON null）**，
不是 "NONE" 字符串。脚本里 `if measure == "NONE"` 会漏判，
要用 `not measure`（或 `not x or x == "NONE"`）。
`CaseService.moduleOf` 里是 `!StringUtils.hasText(m) || "NONE".equals(m)`，两种都覆盖。

## 自检脚本要跟上组别约束（组别功能 2026-10-04上线后的遗留）
`verify_scope` / `verify_assign_status` 原先取任意员工 + 任意案件，
组别校验上线后会被正确拒收（"初查组只能指派给初查组人员"）→
自检失败，但**那是约束在正常工作，不是被测逻辑坏了**。
修法：固定挑「无强制措施」的案件（走初查路径）+ 员工组别设 INITIAL，
`verify_assign_status` 在 finally 里恢复员工原组别不留痕迹。
**以后加任何新的指派/分派约束，都要回头检查这批自检脚本。**

## 待办子任务 + 反馈记录 + 两条完成规则（2026-10-04，commit a317467/24e05bb/312ee87）
用户明确两条规则：**主任务至少一条反馈说明才能完成** + **子任务全完成才能完成主任务**。
**佐证材料要求已废止**（原「必须上传佐证才能勾选」，只删前端入口会让人永远勾不动）。

- `case_todo` + `parent_id BIGINT NULL`（NULL=主任务，非空=子任务），仅两级。
- 新建 `case_todo_feedback` 累积表：remark 是**单字段覆盖式**，满足不了
  「展示全部反馈记录」；含 `status_at` 快照（任务后续变了，历史仍显示当时状态）。
- `listOf` 只取 `parent_id IS NULL`（子任务混进主列表会让「共N项」与序号含义失真），
  子任务统计与反馈条数**一次性聚合**传入 toVO 重载，避免 N+1（待办几十条=几十个SQL）。
- **添加子任务开放给普通用户**（执行细节干活的人最清楚）；主任务增删改/排序仍限管理员。
- 反馈与完成是**两个动作**（先反馈再勾选），浮窗有对应提示文案。
- 详情浮窗 `TodoDetailDialog.vue`：单个 `detail` 接口一次返回
  子任务+反馈+任务本体（**没有 todo 包装层**），分三个接口会闪空态且三次往返。

## 坑：按字符串位置删 HTML 片段会破坏标签配对
删流程图时多删了一个 `</div>` → Vue 报 `Invalid end tag`，**编译期不报错、只在浏览器炸**。
删完必须立刻 `curl localhost:5173/src/xxx.vue | grep -i error` 校验，别攒到最后。

## 坑：自检脚本不能复用存量数据（重要，已踩）
第一版 `verify_todo_subtask.py` 复用现有主任务，上一轮跑完留下的子任务/反馈
让断言基数全错（实际 6 个、断言 2 个，27 项里错 8 项，误以为代码坏了）。
**改为自建专用任务 + 跑完自动清理**（时间戳 RUN_ID 标记，清理按内容前缀匹配），
基线从 0 开始，恒定可重复执行。凡是「会往库里写数据」的自检都必须这么做。

## 体检规则要跟业务变更同步
`audit_consistency.py` 的「已完成待办必须有佐证」在规则废止后必然报 10 处不一致
（改规则→体检全红→容易误判成自己改坏了）。已改为核对当前真正的完成依据：
高=有未完成子任务；低=无反馈说明（历史数据，正常）。**每次改业务规则都要回头看体检脚本。**

## 【重大架构变更】待办成为意见的唯一载体（2026-10-04 用户手改，commit e5ee616）
**用户自己重构了意见模块，方向比我的双写更正确，勿回退**：
- 删`OpinionPanel.vue` / `FlowPanel.vue`，合并为 `TodoDetailDialog.vue` + `FeedbackDialog.vue`
- `case_todo.opinion_id` 派生待办，**待办是单一事实源、意见只读回写**
  （`TodoService.fillOpinionInfo` + 完成/反馈时回写 `opinion.feedback_status`）。
  消灭了「意见一套状态 + 待办一套状态」的双写漂移。
- `removeAllOfTodo` 级联删子任务与反馈 —— 否则 `countsOf` 按 caseId 统计会把
  孤儿算进分母，**案件进度虚高**。
- 分级收紧为仅管理层可改（民警只读）；反馈带 status（落实状态快照）。
- `done()` 规则1 只对主任务生效（`t.getParentId()==null`）——子任务说明由主任务承载。

## 坑：CREATE INDEX IF NOT EXISTS 是 H2 专属，MySQL 8 会让建表段失败
用户修掉了我写在 `schema.sql` 的这个（`init_db` 直接崩）。
**二级索引必须放 `sql/index-mysql.sql`**（`init_db.py` 与 `mysql_local.py` 都会执行它），
`schema.sql` 只留 `CREATE TABLE`。已在 MySQL 8.0.28 实测两条索引都建上。

## 坑：@FullAccessOnly 会把子任务操作挡在门外
`PermissionInterceptor` 在**方法执行前**拦截，所以「主任务仅管理层、子任务承办人即可」
这种分层**做在 Service 里没用**——注解必须一起去掉，权限判断下沉到 Service。

## Element Plus 坑两个（都实测踩过）
1. **el-radio-button 悬停会把选中态文字染成主题色** → 「红底红字」看不清。
   必须写 `:hover:not(.is-active)`，并显式声明 `.is-active .el-radio-button__inner { color:#fff }`。
2. **puppeteer 点el-radio-button**：`scrollIntoView` 后立刻取
   `getBoundingClientRect()` 拿到的是**滚动前的旧坐标**，点击会落到相邻按钮
   （表现为「点 C 无效」）。要么等 500ms 后重取，要么直接 `element.click()` 派发，
   后者最稳、不依赖坐标。**误判过一次产品有 bug。**

## 权限分层惯例（本项目已确立）
主任务（=领导定的清单结构）→ 仅管理层；子任务（=干活的人自己拆的）→ 承办人即可。
增删改都按这个口径，别再一刀切 `@FullAccessOnly`。

## 提交反馈「纯手动」+ 主/子任务一致入口（2026-10-04，commit 2602bd2）
**用户要求：移除任何自动提交/自动流转，提交只能由用户在界面点。**
- 原 `done()` 会在标记完成时**自动**把 remark 写成一条反馈记录 —— 已删。
  那会让反馈列表混进用户没主动提交过的内容，事后分不清哪条是他真写的。
  `syncOpinion()` 保留（完成状态回写意见，是数据一致性不是提交）。
- 核查方式：`grep addFeedback(` 确认 private 版只被 public 版调用、
  public 版只被 Controller 触发 → 系统侧无自动路径。
- 列表页每行加独立「提交反馈」按钮，**与有没有子任务无关**；
  `openSubmit` 只开弹窗不写库，落库等用户点弹窗里的「提交反馈」（emit submit）。
- **主任务详情现聚合子任务的反馈记录**并按时间正序，前端按 todoId 标注「子任务：xxx」来源。

## 坑：前后端规则判定口径必须一致（踩过）
`feedbackCount` 与规则1 判定原先只算**本任务自己**的反馈，
而前端 `canDone` 用的是合并后的条数 → **子任务都提交过反馈了，
前端按钮可点、后端却拒绝**。现统一为 `countFeedbacksWithSub()`（含子任务）。
**凡「前端置灰/可点」与「后端放行/拒绝」共用同一规则，必须抽成同一个口径函数。**

## 坑：脚本改文件时中途报错会留下半成品（踩过）
用 node 脚本批量改文件时中途抛 `TypeError: Assignment to constant variable`，
前半段已写入、后半段没写 → 出现「import 了但模板没挂载」的半成品，
表现为**点击按钮完全无反应但控制台无报错**。
**改完必须 grep 确认关键锚点真的存在**（我那次是 `<FeedbackDialog` 只 match 到 import 一行）。

## 【交互约定】全项目禁用悬浮提示（2026-10-04，commit c08a801）
用户明确：**鼠标悬浮弹出的提示会遮挡相邻操作按钮，影响操作，全都不要。**

- 已清除：8 处 `el-tooltip` + 4 处原生 `title`。`el-tooltip` 全项目归零。
- **替代方案：说明信息改成常驻可见的内联文字**，不靠悬停：
  - 「为什么勾不动」→ 卡片内橙色小标签 `.cf-todo__block`（常驻）
  - PageFooter 标语提示 → `.cf-foot__slogan-tip` 浅色小字
  - el-alert 的长说明 → 拆成 `title` + **`description`**（description 才是正文）
  - 按钮本身已有明确文字的（「提交反馈」「撤回上一步」）→ 直接删 tooltip，不补说明
- 新增组件**不要再用 el-tooltip / title悬浮提示**。要说明就放在界面上。

**关键区分：34 处 `title` 里大部分是 `:title`（el-dialog/el-drawer/ChartPanel 的组件属性），
那是标题栏文字不是悬浮提示，删了会没标题。**
筛选要用 `grep -E '(^|[^-:])title="' | grep -v ':title='` —— 直接 grep `title="` 会误伤。

## 【用户纠正记录】子任务完成必须走完整汇报弹窗（2026-10-04，commit cc20cfa）
**用户原话：「你还是没懂我的意思，我想要的是每个子任务完成后都要像主任务一样的汇报形式」**

- 我此前两轮都没接住：第一轮做了「子任务+反馈记录」，第二轮做了「列表页提交按钮」——
  用户要的其实是**交互形式的一致性**：勾选子任务 = 弹出与主任务相同的完整汇报弹窗
  （落实状态 + 落实说明 + 上传声明），选「完成」提交才真正勾上。**不能在列表里直接勾掉。**
- 教训：用户说「像 X 一样」时，指的是**交互形式照搬 X**，不是"功能等价"。
  修 UI 行为需求前先问自己：用户在界面上看到的动作序列是什么？
- 实现：`TodoDetailDialog.openCompleteSub(parentId, subId)`（先 open 主任务再弹子任务反馈窗）；
  `CaseTodoPanel.toggleSub` 勾选分支改调它。

## 弹窗状态选择的语义（本次确立）
反馈弹窗里 complete 意图 ≠ 强制完成：**按用户在弹窗里实际选的落实状态分派**——
选「完成」才 done()；选「进行中/未完成」只记反馈、不动状态。
否则弹窗里的状态三选一形同虚设（选进行中也被勾成完成）。

## 「完成时填的说明」要进反馈流（与纯手动的边界）
上轮删掉的「自动写反馈」= done 时**替用户凭空造一条**。
而汇报弹窗里用户亲手填的说明是**用户主动提交的汇报内容**，完成路径要显式
addFeedback（先记反馈再 done，顺序也满足规则1校验）。两条不是一回事，别混淆。

## 坑：前端传参字段名与后端 DTO 对不上（踩过，静默失败）
`TodoSaveRequest` 字段是 `content`，我传了 `{ note }` → 后端收 null →
「请填写反馈内容」拒绝 → **上一轮加的提交按钮提交必然失败**。
当时浏览器实测只验了「打开弹窗不自动提交」，没验提交成功路径。
**教训：加提交类功能必须端到端验一次成功路径（填内容→提交→查库），不能只验"没多提交"。**

## 坑：往 flex 主行里插常驻说明会挤乱整卡布局（踩过，commit 55964f3）
「为什么勾不动」提示插在 `.cf-todo__card-main`（flex 行）checkbox 旁、
`max-width:190px` 占死宽度 → 卡片主体被挤到右侧，**整卡排版错乱**（用户截图反馈）。
**教训：卡片主行（checkbox/按钮列）是固定槽位，说明类内容放标题行（flex:0 1 auto + overflow-wrap:anywhere）或 meta 区，别插主行。**

## 详情浮窗折叠约定（2026-10-04，commit 2189f9d）
反馈记录/子任务多时会把弹窗撑满、没法滚动看（用户反馈）。约定：
- **反馈区**：默认最新 3 条（slice(-3)），区头右侧「展开全部 N 条/收起」按钮切换
- **子任务区**：默认收成一行摘要（共 N 个已完成 M 个，有未完成带橙色提醒），点区头展开（箭头转向）
- 打开浮窗时折叠态重置。**以后往浮窗加长列表区一律默认折叠 + 摘要行。**

## 疑问问答模块（2026-10-04，commit f3f3a42）
新表 case_question：员工在任务详情浮窗提问、管理层回答。**独立于待办与任务**
（不派生待办、不进完成规则、不进反馈流——用户明确要求）。
- todo_id 可空仅作上下文备注，**不设外键**：删任务不级联删问答（沟通记录留档）。
- 权限：提问=承办人或管理层；回答=仅管理层。Controller 不加 @FullAccessOnly（权限下沉 Service 惯例）。
- 一问一答，已回答不可再答（更正就再提一条）。
- 前端：浮窗「疑问」区，列表 max-height 240px 滚动，按当前任务 todoId 过滤；
  caseId 从 data.value.caseId 取（TodoDetailDialog 无 props！之前在这里栽过一次 props is not defined）。

## 新建后端文件模板（JDK8/Spring Boot 2.x，别照抄新版本写法）
- `import javax.annotation.Resource;`（**不是 jakarta**）
- `import com.caseflow.security.AuthContext;`（**不是 support 包**）
- JDK8 没有 `List.of()` → 用 `new java.util.ArrayList<>()`

## 坑：路由白名单要包含「引导页」本身（踩过，commit 178b270）
普通民警点「到期提醒」无反应：门控引导跳 /case-type，但 STAFF_PAGES 白名单没它 →
被守卫弹回 /my-cases，形成循环，表现为"点不动"。
**教训：给角色加路由白名单时，门控引导页（/case-type）等中间页必须一并加，
否则引导链路自己把自己拦死。** 用 puppeteer 以普通账号实测菜单点击链路才能发现
（管理员账号永远选过类型，复现不了）。

## 坑：node 脚本批量插入代码「替换未生效」不报错（踩过两次，commit b48f571）
给 load() 插 `await loadQuestions()` 的 node 脚本静默失败（锚点字符串不完全匹配），
**没有任何报错**，结果只有提交路径会加载问答 → 用户看到「打开永远 0 条，发一条才刷出历史」。
**教训：脚本改完必须 grep 确认插入点真的存在**（`grep -n "await loadQuestions()"` 应出现在 load 内）。
同类前科：<FeedbackDialog> 只 import 没挂载、setFeedbackCount 只剩注释——
全是"批量改写后没验证锚点"的同一类错误。**改完 grep，改完 grep，改完 grep。**

## 意见收件箱（2026-10-04，commit dd2b8a4）
欢迎弹窗「新增领导意见」卡片 → 当前页弹 OpinionInboxDialog（邮件式），已读即移除。
- 新表 `case_opinion_read(opinion_id, user_id UNIQUE)`：已读是**登录人维度**，
  不能塞意见行（一条意见多人看）。双副本 schema.sql 都加了。
- **未读口径 = feedback_status 为空 且 本人无已读记录**；welcomeSummary 的
  newOpinionCount 改成同一口径（数字与列表条数必须一致，否则必被当 bug 报）。
  welcomeSummary 从 13 变成"未读"语义，正好贴合卡片提示「待阅读」。
- **StaffTodoService 不可注入 OpinionService**（它被后者注入，反向成环）——
  直接注入 CaseOpinionReadMapper 自查已读 id 再 notIn。
- 交互：点条目=展开+立即标已读（幂等）；「知道了/去处理」才从列表移除
  （"看"和"看完"是两个时刻，读一半就消失会打断阅读）；「全部标为已读」一键清空。
- CaseInfo 的案件名字段是 **name 不是 caseName**（CaseInboxVO 自己的字段才叫 caseName）。
- syncOpinion 既有设计：任务未完成时 DONE 反馈会被视为无效清空意见状态
  ——测「反馈后离开未读」要用 IN_PROGRESS（进行中）反馈。
- `backend/src/main/resources/sql/` 在 .git/info/exclude 本机排除里，
  **根 sql/schema.sql 才进远程**——别人 clone 后缺运行时副本是既有现状，别当新问题。

## 统一信箱（2026-10-04，commit a6cf407）
顶栏铃铛 + 下拉面板，任何与自己有关的操作变更归集。
- **唯一埋点在 LogService.log 之后接 NotificationService.onLog**——全站写操作自动进信箱，
  不用各模块单独埋。失败只记日志不回滚。
- 新表 `case_notification`（每收件人一条，read_at 落行上，无需第二张已读表）。
- **分发按收件人维度**：普通用户=本案 ACTIVE 承办/协办
  （CaseAssignee.employeeId 反查 SysUser.userId）；管理层=全站所有用户（除自己）；
  任何人不收自己触发的。
- **白名单必须用真实 module_action**：全项目 logService.log 枚举出来是
  CASE_OPINION_ADD（不是 OPINION_CREATE）、CASE_TODO_ADD、FILE_UPLOAD 等，
  module 统一是 CASE/FILE/EMPLOYEE/AUTH。凭空想象 action 名必错。
- SSE 定向事件加 kind=notification + userId，前端按当前 userId 过滤实时 +1。
- **坑：无 body 的 POST 接口，自检脚本 call() 会把 method 误判成 GET**（报 500
  "GET not supported"）；axios http.post 无 body 仍正确发 POST，是脚本问题不是产品问题。

## 本项目「信箱/收件箱」类需求的统一套路（已做两个：意见收件箱 + 统一信箱）
- 已读 = 按人，要么独立表（意见，一条多人看），要么每收件人一条（通知，量小铺开）
- 邮件式交互：点开即读、点「知道了/关闭」才移除、全部已读一键清空、transition-group 淡出
- 未读数字与列表条数**必须同口径**（数字≠列表会被当 bug 报）
- 入口统一放顶栏，不跳页
