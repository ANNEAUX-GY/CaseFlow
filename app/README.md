# 案件指派系统（CaseFlow）

面向高强度工作场景的极简案件分派工具：**boss 拿到案件（PDF / Word / Excel 附件或直接手打案件名）→ 指派给员工图谱中的具体人 → 盯到期**。

- 后端：Spring Boot 2.7 + MyBatis-Plus + MySQL（内置 H2 用于零安装演示）
- 前端：Vue 3 + Vite + Element Plus
- 开发脚本：Python（虚拟环境 `.venv`，已被 .gitignore 忽略，**换机器需要重新创建**）

---

## 一、目录结构

```
案件指派demo/
├─ backend/                  Spring Boot 后端
│  └─ src/main/java/com/caseflow/...
├─ frontend/                 Vue3 前端
│  └─ src/{views,components,api,store,utils}
├─ sql/
│  ├─ schema.sql             建表脚本（MySQL 与 H2 通用，唯一数据源）
│  └─ index-mysql.sql        MySQL 专用索引
├─ scripts/                  Python 开发与运维脚本
│  ├─ dev.py                 启动器：拉起后后端/前端、环境自检、依赖修复
│  ├─ package_release.py     打包成品（前端 build + 后端 jar）
│  ├─ start_lan.py           局域网部署（跑 jar，全单位可访问）
│  ├─ open_lan_firewall.py   放行 TCP 8080 入站（需管理员）
│  ├─ setup_toolchain.py     下载便携版 Maven（无需安装）
│  ├─ mysql_local.py         本地 MySQL 8 免安装实例的 init/start/stop/status/setup
│  ├─ init_db.py             MySQL 建库建表 + 默认账号（连外部/正式 MySQL 时用）
│  ├─ gen_org_template.py    生成员工图谱 Excel 模板
│  └─ seed_demo.py           灌入演示数据（走 HTTP API）
├─ .tools/                   免安装工具链（Maven / m2 仓库）
├─ docs/                     设计说明、生产化改造指南与路线图
├─ .env.example              环境变量示例（可复制为 .env 覆盖默认值）
└─ requirements.txt
```

> **注意**：本地 MySQL 实例不在项目目录内，而在 **`D:\caseflow-db\`**（安装目录 `mysql-8.0.28-winx64`、数据目录 `mysql-data`）。
> 原因见下文「六、常见问题」里的非 ASCII 路径问题——项目路径含中文会让 mysqld 启动失败，所以数据库必须放在纯 ASCII 路径下。
> 路径可用环境变量 `CF_MYSQL_ROOT` 覆盖。

---

## 二、最快体验（无需安装 MySQL）

```bash
# 1. 准备 Python 环境（一次性）
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt

# 2. 一键启动（默认内置 H2 文件库，数据在 data/h2 下，重启不丢）
.venv\Scripts\python.exe scripts/dev.py --seed
#    后端 http://127.0.0.1:8080/api   前端 http://127.0.0.1:5173
#    演示账号：boss / admin123
```

> **环境变量**：本项目「零配置启动」，所有变量都有安全默认值。需要覆盖时
> 参考根目录 `.env.example`（后端 `CF_SERVER_PORT` / `SPRING_PROFILES_ACTIVE` /
> `CASEFLOW_UPLOAD_DIR`，前端 `VITE_BACKEND` / `VITE_BASE`）。日常开发通常无需设置。


`dev.py` 会自动查找本机自带的 JDK 8（`tools\jdk8`），
以及便携版 Maven 与离线依赖仓库，全程无需联网（除非新增第三方依赖）。

### 关键约定：项目没有任何「启动用 .bat」

开发环境的启动、自检、打包、防火墙放行**全部由 `app/scripts/` 下的 Python 脚本承担**，
并已在 `.idea/runConfigurations/` 里配好一一对应的运行配置。

这样做的原因：`.bat` 依赖 cmd 的编码与 `findstr` 语法（中文极易乱码），
而且双击黑窗口的方式无法被 IDE 统一管理进程树 —— 停止时容易留下 Java/Node 孤儿进程占端口。

**你要做的只有一件事：用 PyCharm 打开 `app` 目录，在右上角下拉框里选一个配置，点运行。**

| 运行配置 | 等价命令 | 作用 |
|---|---|---|
| `① 一键启动（后端+前端）` | `dev.py --seed` | 起后端 8080 + 前端 5173，并灌演示数据 |
| `② 只启动后端（8080）` | `dev.py --no-frontend` | 只起后端，调后端逻辑时用 |
| `③ 只启动前端（5173）` | `dev.py --no-backend` | 只起前端，后端已在别处跑着时用 |
| `④ 环境自检` | `dev.py --check` | 逐项检查 JDK/Maven/Node/前端依赖/Python 依赖 |
| `⑤ 修复依赖（Python+前端）` | `dev.py --fix-deps` | 重建 `.venv`、补装前后端依赖 |
| `⑥ 灌入演示数据` | `seed_demo.py` | 单独灌一次演示案件（需后端在跑） |
| `⑦ 数据库一致性体检` | `audit_consistency.py` | 全库数据自洽性只读体检 |
| `⑧ 打包成品（jar）` | `package_release.py` | 前端 build + 后端打 jar（页面内嵌） |
| `⑨ 局域网部署启动` | `start_lan.py` | 跑打包好的 jar，全单位可访问 |
| `⑩ 开放局域网访问（需管理员）` | `open_lan_firewall.py` | 放行 TCP 8080 入站（会弹 UAC） |

推荐顺序：**首次**跑 `④ 环境自检` → 有提示缺依赖就跑 `⑤ 修复依赖` → 然后 `① 一键启动`。

#### 逐项启动（想分开调试时）

前后端要分开单独控制时，用两个配置各起一个：

1. 先跑 `② 只启动后端（8080）`，等日志出现 `Started CaseFlowApplication`；
2. 再跑 `③ 只启动前端（5173）`（可以打两个不同的运行标签页，各自独立启停）；
3. 前端 5173 已把 `/api` 代理到 8080，两个都活着页面才能用。

`③` 只会启动 Vite，**不会**去碰后端、也不会做端口占用检查 —— 这正是「逐项启动」想要的行为。

### 在 PyCharm 里运行

**推荐方式：直接用内置运行配置，不要开 Terminal 敲命令。**

用 PyCharm 打开 `app` 目录（注意是 `app`，不是它的上一级 `CaseFlow开发环境包`），
右上角运行下拉框里已经内置上表那 10 个配置。

三者都固定使用 `app\.venv\Scripts\python.exe` 作为解释器、工作目录固定为 `app\`。

#### 解释器绑定（首次打开必做一次）

`File → Settings → Project: app → Python Interpreter`，
选择 `Add Interpreter → Add Local Interpreter → Existing`，
路径指到 `app\.venv\Scripts\python.exe`。
绑定后 PyCharm 会自动识别 `requests` / `pymysql` / `openpyxl` / `segno`。

工程里已带 `.idea/misc.xml`（声明 `project-jdk-name`）与 `.idea/app.iml`，
正常情况下打开即可直接识别，无需手动添加。

#### 如果运行配置下拉框里没有这几项

`Run → Edit Configurations... → 左侧 + → Python`，按下表填：

| 字段 | 值 |
|---|---|
| Script path | `<项目>\app\scripts\dev.py` |
| Parameters | `--seed` |
| Working directory | `<项目>\app` |
| Python interpreter | `Python 3.13 (app)`（即 `.venv` 里那个） |
| ☑ Emulate terminal in output console | 勾上 |

**为什么必须勾「Emulate terminal」**：`dev.py` 会拉起 Maven 与 Vite 两个子进程并持续输出，
不开这个选项时 PyCharm 会把子进程输出缓冲住，看起来像"卡住不动"。

#### 为什么不能在 PyCharm 的 Terminal 里直接敲命令

`dev.py` 必须以后台常驻方式运行（它最后是一个 `while True` 挂着前后端进程）。
在 Terminal 里跑，一旦点停止按钮，子进程（Java / Node）会被留下变成孤儿进程占住 8080/5173，
下次启动就会报端口被占用。用运行配置则由 PyCharm 统一管理进程树，停止时能连带清理。

#### 常见 PyCharm 侧故障

| 现象 | 原因 | 解决 |
|---|---|---|
| `Cannot find Python interpreter` / 解释器红色 | `.venv` 不存在或未绑定 | 重建 `.venv` 后在 Settings 里重新绑定（见上） |
| 运行报 `No such file or directory: '...\.venv\Scripts\python.exe'` | Working directory 填错（填成了 `app\scripts`） | 改成项目下的 `app` |
| 启动后一直停在"后端启动中" | 已有旧实例占着 8080，且 dev 模式的 H2 文件库只能一个进程打开 | 关掉旧实例；或用 `--kill-existing` 参数 |
| 中文输出乱码 | 控制台编码不是 UTF-8 | 运行配置的 Environment variables 里加 `PYTHONIOENCODING=utf-8` |
| 改了 `.py` 不生效 | `__pycache__` 缓存 | `File → Invalidate Caches` 或删掉 `scripts/__pycache__` |

### 常见问题（终端方式，非 PyCharm）

**报错 `无法加载模块 ".venv"` / `CommandNotFoundException`**

`.venv/` 不会随代码一起分发（在 `.gitignore` 里），换电脑、重装或重新解包后它并不存在。
按上面第 1 步重新创建即可。Windows PowerShell 里路径带反斜杠时必须加 `.\` 前缀：

```powershell
.\venv\Scripts\python.exe scripts/dev.py --seed    # 正确
.venv\Scripts\python.exe scripts/dev.py --seed     # 会报「无法加载模块 .venv」
```

**正确的做法**：不要开 Terminal，直接在 PyCharm 右上角选 `① 一键启动（后端+前端）`。
项目已不再提供任何 `.bat` 启动脚本 —— 那正是「无法加载模块 .venv」这类问题的来源
（cmd / PowerShell 对相对路径与编码的处理方式各不相同）。

**报错 `ObjectNotFound: (.\.venv\Scripts\python.exe:String)`**

先确认解释器**确实在盘上**：

```powershell
Test-Path .\.venv\Scripts\python.exe     # 应返回 True
Get-Item  .\.venv\Scripts\python.exe | Select-Object Length, LastWriteTime
```

如果 `Test-Path` 返回 `True` 却仍报 `ObjectNotFound`，几乎一定是 **PowerShell 的当前目录不对**：
你在 `CaseFlow开发环境包\` 下敲命令，而 `.venv` 在 `CaseFlow开发环境包\app\` 里。先 `cd app` 再执行。

`dev.py` 本身对这类耦合已经做过加固：它只依赖 Python 标准库，
即使 `.venv` 缺失或依赖没装齐，用系统 Python 也能跑起来（内部会自动定位 `tools\` 下的 JDK/Maven/Node）。

**最省事的做法**：不要开 Terminal，用 PyCharm 内置运行配置 `① 一键启动（后端+前端）`（见上一节）。

**前端依赖装不上：`spawnSync ... node.exe EBUSY`**

`npm ci` 会先清空 `node_modules` 再重建，只要重建过程中有进程占用 `tools\node\node.exe`
（例如前端还开着、或编辑器终端占着），就会中断并留下**半残的依赖目录**。
处理办法：关掉正在运行的前端，然后

```bash
cd frontend
npm install --include=optional --no-audit --no-fund
```

`dev.py` 会自动识别这种半残状态并提示重装。

---

## 三、正式使用 MySQL

### 3.1 本地 MySQL（推荐，无需安装/管理员权限）

项目自带一套**免安装版 MySQL 8**，装在 `D:\caseflow-db\` 下，数据目录 `D:\caseflow-db\mysql-data`，只监听 `127.0.0.1:3306`。
不注册 Windows 服务、不需要管理员权限、不写系统目录，卸载 = 删掉 `D:\caseflow-db`。

```bash
# 首次：初始化数据目录 -> 启动 mysqld -> 建库建表建索引 -> 建应用账号
.venv\Scripts\python.exe scripts/mysql_local.py init

# 日常启动（dev.py --profile mysql 会自动拉起 mysqld，无需手动执行）
.venv\Scripts\python.exe scripts/dev.py --profile mysql --seed
```

安装包来源（如需在别的机器复现）：阿里云镜像 `https://mirrors.aliyun.com/mysql/MySQL-8.0/mysql-8.0.28-winx64.zip`（211MB），
解压到 `D:\caseflow-db\` 后执行 `mysql_local.py init` 即可。

日常运维：

| 命令 | 作用 |
| --- | --- |
| `mysql_local.py start` / `stop` | 启动 / 关闭 mysqld |
| `mysql_local.py status` | 状态 + 每张表的实际行数（核对数据是否真的落库） |
| `mysql_local.py setup` | 只重跑建库建表建索引（幂等） |
| `mysql_local.py env` | 打印后端连接本库所需的环境变量 |
| `mysql_local.py reset` | 清空数据目录重新初始化（**会删数据**） |
| `scripts/verify_persistence.py` | 落库校验：走 HTTP 做增删改查，再直连数据库反查，证明写操作真的进了库 |
| `scripts/verify_undo.py` | 撤回校验：撤回新建 / 撤回删除（同 id 还原）/ 撤回指派与改期限 / 撤回后再撤回，逐步直连 SQL 反查是否精确还原 |

连接信息：

| 用途 | 账号 | 密码 |
| --- | --- | --- |
| 应用连接（`caseflow`，仅 `case_flow.*` 权限） | `caseflow` | `caseflow` |
| 管理登录（仅本机） | `root` | `root` |

命令行客户端：`D:\caseflow-db\mysql-8.0.28-winx64\bin\mysql.exe --defaults-file=D:\caseflow-db\mysql-my.ini -uroot -proot case_flow`

### 3.2 连接已有的外部 MySQL

```bash
# 1. 建库建表（不传 --password 会交互式输入，传 --password "" 表示无密码）
.venv\Scripts\python.exe scripts/init_db.py --host 127.0.0.1 --user root --password 你的密码

# 2. 用环境变量指向该库启动，并跳过本地实例探测
set SPRING_PROFILES_ACTIVE=mysql
set MYSQL_HOST=... & set MYSQL_USER=... & set MYSQL_PASSWORD=...
.venv\Scripts\python.exe scripts/dev.py --profile mysql --skip-db-check --seed
```

也可手动启动后端：

```bash
cd backend
mvn -Dmaven.repo.local=../.tools/m2repo -Dspring-boot.run.profiles=mysql spring-boot:run
```

> 数据是否真的写到库里，用 `mysql_local.py status` 看每张表的行数，或直接执行
> `SELECT COUNT(*) FROM case_info;` 核对——不要只看页面。

生产化改造（密码加密、权限、附件对象存储、备份等）见 **《生产化改造指南.md》**。

---

## 四、核心功能

| 功能 | 说明 |
| --- | --- |
| 案件录入 | 支持手打案件名，或上传 PDF / Word / Excel（自动识别来源类型）；填案件编号、是否采取强制措施、截止期限（精确到天 + 自定节点名 + 提前几天提醒） |
| 案件指派 | 指派抽屉内「检索」或「组织树」选人，区分主办 / 协办，可改派（旧记录留痕）；抽屉顶部可直接设定 / 调整 / 清除截止期限（日期精确到天） |
| 一键重点关注 | 案件管理 / 案件盯办 / 待办总览 / 到期提醒 **四个栏目口径完全一致**：列表首列都能直接点星标注（不必打开案件详情）、都有同一个「只看重点」勾选框、重点行左侧都有金色标识。盯办看板卡片与待办总览汇总数字也会跟着筛，不会出现"卡片写 10 件、列表只 1 条"的对不上。筛选与星标都只给管理层（后端 `focus` 接口是 `@FullAccessOnly`） |
| 员工图谱 | Excel 导入（姓名 / 工号 / 上级工号 / 部门 / 职务），自动生成 领导-副领导-组长-组员 层级 |
| 到期提醒 | 已逾期 / 今天到期 / 3 天内 / 7 天内 分桶，逾期行整行标红 |
| 工作台 | 8 个关键指标一眼可见：总数、待指派、处理中、已办结、逾期、今日、3 天、7 天 |
| 操作留痕 | 关键动作全量写入 `operation_log`；每条写操作都留下操作前 / 后的**完整快照**，可点开看变更明细并撤回 |

### 角色与侧栏可见性（2026-10-09）

侧栏只有三套菜单，判据集中在 `src/store/user.js` 的 `navTierOf(role)`（改一处，侧栏与路由守卫同时生效）：

| 分档 | 角色 | 侧栏栏目 |
| --- | --- | --- |
| 普通民警 | `STAFF` | 我的案件 / 我的待办 / 到期提醒 |
| 业务领导 | `CHIEF` 所长、`DEPUTY_CHIEF` 副所长、`LAW_OFFICER` 法制员 | 工作台 / 案件盯办 / 待办总览 / 案件管理 / 到期提醒 |
| 系统管理员 | `BOSS` | 业务领导全部栏目 **+ 员工图谱 / 类别管理 / 账号管理** |

要点：

- **员工图谱、类别管理、账号管理只给系统管理员**：账号、组织架构、案件类别字典属于系统运维，业务领导侧栏不出现这三项；手敲 `/#/org`、`/#/categories`、`/#/users` 也会被路由守卫送回落地页（`systemManageOnly`）。
- 这只是**菜单分档，不是权限分档**：领导对业务的写权限仍与管理员同级（`isFullAccess` 未变），后端接口与角色定义一行没改——「员工图谱」等页面内部的读取接口仍对领导开放（指派选人、案件表单都要用）。
- 「待审核申请数」红点挂在账号管理上，只有系统管理员会去取这个数。
- 回归：`node scripts/cf-e2e-nav-roles.mjs`（三档菜单逐项比对 + 三个地址的越权跳转 + 零控制台错误，测试账号跑完自动清理）。

### 案件表单字段口径（2026-10-09）

建案 / 编辑表单按以下口径收敛，改这块时注意 **旧数据不能被抹掉**：

| 字段 | 口径 |
| --- | --- |
| 案件编号 | 表单标签由「立案登记表」改为**案件编号**，落库列仍是 `case_info.filing_no`（改的是叫法，不是列名） |
| 调解书 | **表单不再出现**（`mediationNo` 列保留，历史数据仍可见、快照仍比对）；`CaseService.save` 干脆不写这一列，避免编辑老案件时把它清空 |
| 强制措施 | 先选「是否采取强制措施」；选「是」再下拉选**拘传 / 取保候审 / 监视居住 / 拘留 / 逮捕**（字典 `CASE_MEASURE`）。`CaseSaveRequest.caseMeasure` 留空表示**不动**，防止建案/编辑把盯办里已登记的措施抹掉；选「否」才显式清空措施与届满日 |
| 时间节点 | 自由文本（`deadline_label`），如「受案时间」「变更羁押期限时间」，超长节点名会在提醒文案里带上（"受案时间：3 天后到期"） |
| 截止期限 | **精确到天**（表单用 `type=date`）；落库统一归一到当天 `23:59:59`，否则当天 00:00 起整天都会被判逾期 |
| 提前提醒 | `remind_days`，下拉预设常用档位；用户上次选的档位记在 `localStorage`（`cf_remind_days_used`）作为下次的默认值。原「+1天 / +3天 / +7天 / 清空」快捷键已全部下线 |

新增措施必须同步三处（都读 `flow/PoliceGroup`，是单一口径）：`PoliceGroup.MEASURES`、`measuresOfModule` / `moduleOfMeasure` 映射、`ApprovalService.defaultDeadline`（措施届满日推算）。漏一处就会出现「措施能选但盯办三个子模块都查不到」这种凭空消失。

回归：`node scripts/cf-e2e-focus-form.mjs`（表单结构 + 落库数据 + 四栏目一键关注 + 逮捕归入「刑拘在办」+ 星标不挡撤回，31 项）。

### 操作追溯与撤回（Ctrl+Z）

工作台的「最近操作」每条都可点击，打开抽屉看这一步到底做了什么：

- **执行内容**：操作类型（新建案件 / 修改案件 / 指派·改派 / 状态变更 / 删除案件 / 撤回操作）、原文描述、操作人、精确到秒的时间；
- **涉及案件**：案件编号 + 名称，可直接点进案件详情；案件已被删除时也能显示（取自快照）；
- **变更明细**：逐字段列出「变更前 → 变更后」，含主办人 / 协办人 / 附件归属的变化；
- **撤回**：抽屉里点「撤回此操作」，或用面板头部的「撤回上一步」，或在页面上按 <kbd>Ctrl</kbd>+<kbd>Z</kbd>（输入框内不拦截，交还给文本撤销）。

实现方式见 `sql/schema.sql` 的 `operation_log` 注释与 `CaseSnapshotService`：写操作前后各存一份对象完整状态（案件字段 + 指派关系 + 附件归属）到 `snapshot_before / snapshot_after`，**撤回就是把 `snapshot_before` 原样写回**。因此：

| 撤回对象 | 结果 |
| --- | --- |
| 新建案件 | 案件被撤掉（连同指派关系） |
| 删除案件 | 案件以**原来的 id** 回来，字段与改派历史逐行一致 |
| 指派 / 改期限 | 期限与承办人精确回到操作前 |
| 撤回本身 | 也可以再撤回一次，等价于「重做」 |

安全规则（宁可少撤，不可撤错）：只有案件类写操作可撤回；已被撤过的不能重复撤；**必须是该案件上最新的一条**，否则会覆盖它之后的改动——此时界面会直接写明原因（如「之后有新操作」）。

> 注意：本次改造之前产生的历史记录没有状态快照，会显示为不可撤回；改造之后任何一次指派 / 改期限 / 状态流转，那一步立刻就能撤回。

「一键重点关注」（`CASE.FOCUS`）与批注 / 领导意见同类：**写日志但不进撤回白名单**，属于旁注语义。它虽然在时间线上排最后，却不会占掉「最新一步」——标完重点后，上一步「修改案件」照样能撤回。

### 可视化图表（每页都有）

统一由 `src/utils/chart.js` 提供主题（警蓝主色 + 警徽金），所有图表共用同一套配色、网格、坐标轴与提示框；每张图右上角可切换图形类型（柱状 / 折线）、统计维度、时间跨度，并可点击柱体直接联动筛选。

| 页面 | 图表 |
| --- | --- |
| 工作台 | ① 案件趋势折线（新增 / 办结，7/14/30 天）② 到期分布柱状（逾期红 / 临期黄）③ 状态分布柱状 ④ 承办人在手负载堆叠条形（在手 / 其中逾期） |
| 案件管理 | ① 近 14 天趋势 ② 维度分布（可切换状态 / 优先级 / 来源，点柱体即写入上方筛选条件） |
| 到期提醒 | ① 逾期账龄分布（1-3 / 4-7 / 8-14 / 15 天以上）② 未来 7 天每日到期量（含逾期积压） |
| 员工图谱 | ① 部门在手案件负载 ② 个人在手负载 Top10（点姓名跳该员工案件） |

统计口径：办结 / 撤销不计入「在手」与到期分桶；部门负载按「部门 + 案件」去重，避免主办与协办同部门时重复计数。

### 页脚标语区

四个页面底部统一留出空白 + 标语条（藏蓝渐变底、金色压边、盾牌分隔线），登录页底部也有对应标语行。

标语内容集中在 `src/config/slogans.js`，改这一个文件四个页面同时生效：

| 配置 | 作用 |
| --- | --- |
| `SLOGANS` | 标语库（主标语 + 英文副标语），点击页面上的标语可临时切换下一条 |
| `PAGE_HINT` | 按路由配置的一句业务提示（如「建议每日到岗先清空『已逾期』」） |
| `FOOTER_BRAND` | 右下角落款：系统名 / 部门 / 版本号 |

同一天同一页固定显示同一条（按「日期 + 路由」取模），不会随刷新乱跳。

### 页面美化与布局稳定

内容不跳动的几条硬约束（改动样式时请注意保持）：

| 约束 | 做法 |
| --- | --- |
| 滚动条出现/消失不横移 | `html` 与 `.cf-main` 均设 `scrollbar-gutter: stable` |
| 数据少时表格不塌陷 | `CaseTable` 外层 `.cf-table-wrap` 带 `min-body`（默认 300，传了 `height` 时自动置 0） |
| 图表不跑位 | `EChart` 用 `ResizeObserver` 监听容器宽度变化 + 首帧补一次 resize |
| KPI 不因窗口宽度突然换行 | 列数写死 8 / 4 / 2（按断点），不用 `auto-fit` |
| 切页不重叠 | `Layout` 里 `<transition mode="out-in">` 淡入淡出 |
| 加载期不闪空白 | 三处表格接 `v-loading` |
| 行内元素不挤位 | 案件名、承办人列 `show-overflow-tooltip` + `.cell{white-space:nowrap}` |

美化项：细藏蓝滚动条、面板极淡投影、表格 hover 柔和高亮（逾期/临期行保留自身底色）、表头吸顶、分页条固定下边框、顶栏显示当前日期、侧栏底部固定版本落款、同排卡片等高。

### Excel 员工图谱格式

| 姓名 | 工号 | 上级工号 | 部门 | 职务 | 手机 | 邮箱 |
| --- | --- | --- | --- | --- | --- | --- |
| 王总 | E001 | （空=顶层） | 刑事侦查大队 | 领导 | 13800000001 | wang@example.com |
| 李副总 | E002 | E001 | 刑事侦查大队 | 副领导 | ... | ... |

执行 `.venv\Scripts\python.exe scripts/gen_org_template.py` 可直接生成示例文件；页面「下载导入模板」按钮亦可下载。重复导入按工号幂等更新。

---

## 五、主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录，返回 `X-Token` |
| GET | `/api/employees/tree` | 组织树（keyword 过滤） |
| GET | `/api/employees/search` | 扁平检索（指派时选人） |
| POST | `/api/employees/import` | Excel 导入员工图谱 |
| GET | `/api/employees/template` | 下载导入模板 |
| GET | `/api/cases` | 案件分页（状态/优先级/来源/承办人/到期桶/关键词/`focusOnly=true` 只看重点） |
| POST | `/api/cases` | 新建案件（可同时指派） |
| POST | `/api/cases/{id}/assign` | 指派 / 改派（可同时设定或清除截止期限：传 `deadline` + `deadlineTouched=true`） |
| POST | `/api/cases/{id}/status` | 状态流转 |
| POST | `/api/cases/{id}/focus` | 一键重点关注：`{focus:1}` 标注 / `{focus:0}` 取消（`@FullAccessOnly`） |
| GET | `/api/cases/reminders?bucket=OVERDUE` | 到期提醒清单（`focusOnly=true` 只看重点，口径同 `/cases`） |
| GET | `/api/cases/dashboard` | 工作台聚合数据 |
| GET | `/api/cases/stats?days=14` | 图表统计：趋势 / 状态 / 优先级 / 来源 / 到期分桶 / 逾期账龄 / 未来 7 天 / 部门负载 / 个人负载 |
| POST | `/api/files/upload` | 附件上传（PDF/Word/Excel） |
| GET | `/api/logs` | 操作日志分页（`module` 可选） |
| GET | `/api/logs/recent?limit=20` | 最近操作（工作台面板用，带 `undoable` / `undone` / `actionName` / `createdAtText`） |
| GET | `/api/logs/{id}` | 操作详情，含 `changes`（字段 / 变更前 / 变更后）与不可撤回的原因 `undoHint` |
| POST | `/api/logs/{id}/undo` | 撤回指定操作 |
| POST | `/api/logs/undo-latest` | 撤回上一步（对应 Ctrl+Z） |

---

## 六、常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| `Web server failed to start. Port 49952 was already in use` | 终端注入了 `SERVER__PORT` 环境变量，Spring 宽松绑定把它当成 `server.port`。`dev.py` 已自动剔除并显式传 `--server.port`；手动启动请加 `-Dspring-boot.run.jvmArguments=-Dserver.port=8080` |
| `spring-boot:run ... Application finished with exit code: 1`（真正原因在这几行**上方**） | Maven 只报笼统的退出码，往上翻找 `APPLICATION FAILED TO START`。常见原因：① 端口被占用；② `Could not open file ...caseflow.mv.db` → **已有一个后端实例在跑**，dev 用的 H2 文件库同一时刻只允许一个进程打开，先停掉旧实例再起 |
| 不确定是谁占着端口 | `dev.py` 会自动探测并打印占用进程的 PID；也可 `netstat -ano \| findstr :8080` 查，再 `taskkill /F /PID <pid>` |
| 启动时报 JDK 版本错误 | Spring Boot 2.x 只支持 JDK 8/11/17（推荐 JDK 8）。`dev.py` 会按「便携 `tools\jdk8` → `JAVA_HOME` → 本机已装 JDK 8」的顺序自动定位；手动启动请先把 `JAVA_HOME` 指向 JDK 8 |
| 演示数据重复 | `seed_demo.py` 检测到已有案件会自动跳过；要追加用 `--force`，要彻底重建需停服后删除 `backend\data\h2` 再启动 |
| 前端 5173 打不开 / 接口 404 | 前端代理目标由 `VITE_BACKEND` 决定，默认 `http://127.0.0.1:8080`；后端换端口时同步设置该变量 |
| 想换端口 | `scripts/dev.py --backend-port 8090`（会同步传给前端代理） |
| 端口被占又不想手动找进程 | `scripts/dev.py --kill-existing`（自动结束占用进程再启动） |
| `Unsupported character encoding 'utf8mb4'` | JDBC URL 的 `characterEncoding` 要写 **Java 字符集名 `UTF-8`**，不能写 MySQL 的 `utf8mb4`。已修正（此前一直用 H2，所以没暴露） |
| `mysqld` 启动即退，日志里 `D:\ (mysqld 8.0.28) starting as process ...` / `Failed to set datadir to 'D:\data\'` | **项目路径含中文导致 mysqld 把参数在第一个非 ASCII 字符处截断**（`--initialize` 能过、`--verbose --help` 也正常，唯独正常启动必挂；改编码、改命令行传参都无效）。把实例放到纯 ASCII 路径即可，本项目默认 `D:\caseflow-db`，可用 `CF_MYSQL_ROOT` 覆盖 |
| 连库报 `Host '127.0.0.1' is not allowed to connect` | my.ini 里开了 `skip-name-resolve`，127.0.0.1 不再被解析成 localhost，`root@localhost` 就匹配不上了。本项目已去掉该选项 |
| `Can't create directory ... (OS errno 2)` | my.ini 被 mysqld 按系统 ANSI（中文机器 = GBK）解码，UTF-8 写的中文路径会变乱码。脚本已按 GBK 写、且路径保持 ASCII |
| 加了个 `@Resource private XxxService logService;` 之后启动报 `BeanNotOfRequiredTypeException: Bean named 'logService' is expected to be of type ...` | `@Resource` **先按字段名找 Bean**，字段名 `logService` 会撞上 `support` 包里的 `LogService`。换个字段名（如 `operationLogService`）或写 `@Resource(name = "...")` |
| 给已有库新增字段后启动报 `Unknown column 'xxx' in 'field list'` | `schema.sql` 全是 `CREATE TABLE IF NOT EXISTS`，对已存在的表不会补列；MySQL 的 `ALTER TABLE ADD COLUMN` 又不支持 `IF NOT EXISTS`。已在启动时由 `bootstrap/SchemaMigration` 幂等补列——以后新增字段，记得在它的 `NEW_COLUMNS` 里登记一行 |

---

## 七、已知边界（第一版）

- 登录为「账号 + 内存令牌」演示实现，未做密码加密与权限细分；
- **撤回是「任何登录用户都能撤」且只做了「案件上最新一条」这一层保护**，没有按人 / 按角色限制，也没有多人并发下的乐观锁（见《生产化改造指南.md》）；
- 改造之前产生的历史操作记录没有状态快照，因此不可撤回；
- 案件追溯、消息通知、统计报表为后续迭代内容（见 `docs` 中的路线图）；
- 附件存储在本地磁盘 `data/uploads`，生产建议换对象存储。

---

## 八、相关文档

| 文档 | 内容 |
| --- | --- |
| `docs/案件指派系统_设计思路与开发路线.docx` | 设计思路、数据模型、后续开发路线 |
| `docs/生产化改造指南.md` | 真正投入生产需要改什么：数据库/认证权限/附件/可观测/部署/备份，按 P0/P1/P2 分级 |
