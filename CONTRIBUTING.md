# CaseFlow 协作规范（CONTRIBUTING）

> 团队成员 clone 仓库后请先读本文件，统一分支与提交习惯，避免冲突和脏数据。

## 一、分支管理

采用轻量 **GitHub Flow**（本项目是单产品、持续发布，无需复杂 Git Flow）：

| 分支 | 用途 | 规则 |
|---|---|---|
| `main` | 主分支，始终可运行 | 只接受 PR 合并，禁止直接 push |
| `feature/<功能>` | 新功能/新页面 | 从 `main` 拉出，完成后提 PR 合回 |
| `fix/<问题>` | 缺陷修复 | 同上，命名写清修复对象 |
| `hotfix/<问题>` | 线上紧急修复 | 从 `main` 拉出，合回后立即打 tag |

示例：
```
feature/case-comment       # 案件批注功能
fix/h2-lock-check          # 修复 H2 文件锁检测
hotfix/login-403           # 修复登录 403
```

**命名要点**：全小写、连字符分隔、动词/名词短语、可一眼看出内容。

## 二、开发流程（一次完整的功能开发）

```bash
# 1. 同步主分支
git checkout main
git pull origin main

# 2. 拉功能分支
git checkout -b feature/xxx

# 3. 开发 + 频繁小步提交（见提交规范）

# 4. 推送前先拉取 main 合并，避免落后
git fetch origin main
git rebase origin/main      # 或 git merge，团队内统一即可

# 5. 推送并提 PR
git push -u origin feature/xxx
# 到 GitHub 网页开 Pull Request，请求 review
```

## 三、提交规范

采用 **Conventional Commits** 风格，格式：

```
<type>: <简洁描述>

（空行）
<可选：详细说明，多行，说明原因和影响>
```

**type 取值**：

| type | 含义 | 示例 |
|---|---|---|
| `feat` | 新功能 | `feat: 案件进度批注（管理端）` |
| `fix` | 修 bug | `fix: 修复 H2 文件锁未提前探测` |
| `docs` | 文档 | `docs: 补充环境变量说明` |
| `refactor` | 重构（不改行为） | `refactor: 抽取 myVisibleCaseIds` |
| `test` | 测试/自检脚本 | `test: 增加权限越权自检` |
| `chore` | 杂项（构建/配置） | `chore: 升级 Spring Boot 到 2.7.18` |
| `style` | 格式（不影响逻辑） | `style: 统一缩进` |
| `perf` | 性能优化 | `perf: 案件列表查询加索引` |

**规则**：
1. 一个提交只做一件事，别把「改功能」和「顺手改格式」混在一个 commit
2. 描述用祈使句、中文即可，控制在 50 字内
3. 每次提交前自检：`git diff` 看一遍，别把调试代码、临时文件带进去
4. **禁止**提交含密钥/真实账号密码的文件（`application-*.yml` 里密码用环境变量占位）

### 断电防护：改完就提交，别攒着

**大改完成一个功能模块后，立刻 commit + push，不要等全部做完。**

理由：本地 `.git` 只在这台机器上，硬盘故障、误删、断电都会一并丢失；
推到远程才有异地副本。以下情况**必须**先落盘再继续：

- 改完一个完整功能（如「领导意见增强」六项一起做完了）
- 跑通/验证通过，准备换方向或换模块时
- 即将执行可能失败的大动作（重装依赖、清库、批量改名）
- 到了一个自然停靠点（编译通过 / 实测通过 / 提交说明已写好）

推不出去也不阻塞后续开发，但要**在回复里说明还有未推送的提交**，让本人知道存在丢失风险。

**注意**：本机有专属文件不能进库（见第七节），每次提交前先`git status` 确认暂存区干净。

## 四、PR 规范

- 标题同 commit 规范：`feat: ...` / `fix: ...`
- 描述里写清：改了什么、为什么、影响范围、如何验证
- 关联 issue（如有）：`Closes #12`
- 至少 1 人 review 通过才合入 `main`
- 合并用 **Squash and merge**（保持 main 历史干净），或团队约定的 merge 方式

## 五、本项目特有约定（务必遵守）

1. **改建表脚本只改 `app/sql/schema.sql` 一处**（唯一数据源，pom 已映射到 classpath）。
2. **JDK8 强制**：后端必须 JDK 8，高版本报 `Unsupported class file major version`。
3. **H2 文件库单进程独占**：`backend/data/h2/caseflow.mv.db` 同一时刻只能一个后端进程打开。
4. **`dev.py` 保持零第三方依赖**：只许用标准库，别引入 requests/pymysql。
5. **状态流转只走 `/cases/{id}/status`**：不要绕过闸门直接改库。
6. **新增「按人取案件」功能必须复用 `CaseService.myVisibleCaseIds()`**：别另写一套筛选。
7. 改完数据或改状态逻辑，跑一遍 `scripts/audit_consistency.py`（一致性体检）和
   `scripts/verify_assign_status.py`（状态自检）再提交。

## 六、克隆后如何跑起来

```bash
git clone <仓库地址>
cd CaseFlow开发环境包
python -m venv app/.venv
app/.venv/Scripts/python.exe -m pip install -r app/requirements.txt
cd app/frontend && npm ci --include=optional
# 然后用 PyCharm 打开 app 目录，选「① 一键启动」
```
（详见仓库根 `README-开发包.txt` 与 `app/README.md`）

## 七、哪些文件不该进库

**入库的**：只有 `app/.idea/` 下的共享配置（`misc.xml`、`modules.xml`、`app.iml`、
`runConfigurations/` 等），这样别人 clone 后直接就有可用的运行配置。
注意**不要**整体忽略 `.idea`，否则队友拿到手是空的。

**不入库的**：

| 文件 | 原因 | 怎么排除 |
|---|---|---|
| 仓库根 `.idea/` | PyCharm 误开根目录时自动生成，与 `app/.idea` 冲突 | 写进 `.git/info/exclude`（**不要改 `.gitignore`**） |
| `app/.idea/workspace.xml` | 个人窗口布局 | 已在 `app/.idea/.gitignore` |
| `app/backend/data/h2/caseflow.mv.db` | 演示库，运行时会持续变动 | 演示库**有意入库**（队友 clone 即有数据）；本机运行产生的变动**不要提交** |
| `tools/`、`.venv/`、`node_modules/` | 体积大、可重建 | 已在 `.gitignore` |
| 各人自己的 `SYNC_*.md`、草稿 | 个人笔记 | 写进 `.git/info/exclude` |

**为什么强调本机专属走 `.git/info/exclude`**：`.gitignore` 是**被 Git 跟踪**的文件，
在里面加本机专属规则会跟着推给所有队友，造成别人的克隆也带同样的无效规则、
以及与远程的漂移。`.git/info/exclude` 只在本机生效，不随仓库分发。

**每次提交前养成习惯**：

```bash
git status --porcelain     # 看清楚要提的到底是哪些文件
```

若 `git checkout -- app/backend/data/h2/caseflow.mv.db` 报 `Permission denied`，
说明后端正在占用它（正常，H2 独占），直接在 add 时跳过这个文件即可。
