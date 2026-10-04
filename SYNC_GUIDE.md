# GitHub 同步与 PyCharm 关联操作手册

- 仓库：https://github.com/ANNEAUX-GY/CaseFlow
- 仓库根（Git 根）：`D:\案件指派demo`
- **PyCharm 项目根（要打开的目录）：`D:\案件指派demo\app`**
- 同步完成时间：2026-10-04，本地 HEAD 与远程 `b5d1d38` → `1a54f7b` 一致

---

## 一、为什么项目根不是仓库根

远程仓库是「开发环境包」布局：

```
D:\案件指派demo\        ← Git 仓库根（开发环境包）
├─ app\                ← 源码在这里，PyCharm 要打开的就是它
│  ├─ .idea\           ← 含 vcs.xml（已配好 Git 映射）+ 10 个运行配置
│  ├─ .venv\           ← Python 虚拟环境
│  ├─ backend\ frontend\ scripts\ sql\ docs\
├─ tools\              ← 便携工具链（maven / m2repo / jdk8），已被 git 忽略
└─ README-开发包.txt
```

远程 `app/.idea/vcs.xml` 内容为 `<mapping directory="$PROJECT_DIR$/.." vcs="Git" />`，
即 PyCharm 打开 `app/` 后，会自动把上一级目录识别为 Git 根 —— 这就是版本控制能生效的原因。

---

## 二、PyCharm 操作步骤

### 步骤 1 · 重新打开正确的项目目录

1. 菜单 **文件 File → 关闭项目 Close Project**（或直接 `Ctrl+Alt+F4`）
2. 在欢迎页点击 **打开 Open**，选择：
   ```
   D:\案件指派demo\app          ← 注意是 app，不是上一级
   ```
3. 若弹出「是否信任该目录」，选择 **信任 Trust Project**

> 如果列表里还留着旧的「案件指派demo」条目，右键 → **从项目列表移除 Remove from Recent Projects**，
> 否则容易误开旧的根目录项目。

### 步骤 2 · 确认 Git 已启用

打开后看底部 **「版本控制 Version Control」** 工具窗（或 **视图 View → 工具窗口 → 版本控制**）：

- 应显示 `main` 分支和变更列表，**不再是截图里的「受版本控制状态」空页面**
- 若仍显示空页面，菜单 **Git → 刷新**；再不行 **Git → VCS 操作 → 从版本控制系统添加 `app/..`**

**备用手动入口**（通常不需要）：
**设置 Settings → 版本控制 Version Control** → 顶部下拉应显示 `<Directory Mapping>` 指向 `D:\案件指派demo`；
若为空则点 `+` → Directory Mapping → 填入 `D:\案件指派demo` → 确定。

### 步骤 3 · 确认远程仓库地址

**Git → 远程仓库 Remotes...**（或 **设置 → 版本控制 → 远程仓库**）

应显示：

| 名称 | 地址 |
|---|---|
| `origin` | `https://github.com/ANNEAUX-GY/CaseFlow.git` |

- 若缺失：`+` → Name 填 `origin`、URL 填上面的地址 → OK
- 想验证连通：**Git → Fetch**（`Ctrl+F5`）

### 步骤 4 · 确认分支与提交身份

- **Git → 分支 Branches...**：应见 `main`，其 Remote/Upstream 为 `origin/main`
- **Git → 设置 Commit Settings**（PyCharm 2023+ 在此填；旧版在 **设置 → 版本控制 → Git**）：

| 字段 | 已配置的值 |
|---|---|
| 用户名 Name | `adamin` |
| 邮箱 E-mail | `adamin@users.noreply.github.com` |

> 想让提交显示在 GitHub 贡献图上，把邮箱换成 GitHub 账号绑定的真实邮箱即可。
> 命令行等价写法：`git config --local user.email "你的邮箱"`

### 步骤 5 · 验证提交与推送

1. **Git → 提交 Commit...**（`Ctrl+K`）→ 写提交说明 → 提交
2. **Git → 推送 Push...**（`Ctrl+Shift+K`）

本机凭据已实测可用（测试提交 `1a54f7b` 已成功推到远程），**正常情况下不会再要求登录**。

---

## 三、需要你手动完成的部分

| 事项 | 说明 |
|---|---|
| **身份验证** | 已在本机配置好凭据，正常无需操作。若日后弹出 GitHub 登录窗口：浏览器登录 `ANNEAUX-GY` → 授权 Git Credential Manager；或用 PAT：**GitHub → Settings → Developer settings → Personal access tokens → 勾选 `repo`** |
| **提交邮箱** | 当前用 noreply 邮箱。若希望提交关联到你的 GitHub 账号，改成账号绑定的真实邮箱（GitHub → Settings → Emails） |
| **PyCharm 项目根** | 必须手动重新打开 `D:\案件指派demo\app`。这一步只能在你本机操作 |
| **npm 路径** | 自检提示「未检测到 npm」：node_modules 完整、vite 可执行，**不影响开发**。若要自动装新依赖：PyCharm → **设置 → 版本控制 → 路径 Paths**（或 **设置 → 构建执行器 → 版本控制**）把 npm 所在目录加入 |

---

## 四、同步结果摘要

**已更新（61 个文件）**，含后端 CaseService/ApprovalService/FileService/UserService/StatsService、前端 Layout/NavPanel/Dashboard/CaseDetailDrawer/styles/index.css、sql/schema.sql、scripts/dev.py、start_lan.py、seed_demo.py 等。

**已新增（28 个文件）**，远程新版功能：

- 后端：`TodoController`、`TodoService`、`OpinionService`、`ProgressCommentService`、`EmployeeBindingService`、`CaseTodo`/`CaseLeaderOpinion`/`CaseProgressComment` 实体等
- 前端：`MyCases.vue`、`TodoOverview.vue`、`CaseTodoPanel.vue`、`OpinionPanel.vue`
- 脚本：`package_release.py`、`package_source.py`、`audit_consistency.py`、`verify_scope.py`、`verify_assign_status.py`、`open_lan_firewall.py`
- 文档：`docs/批注与领导意见功能说明.md`、`.env.example`

**本地独有文件（已保留，未提交）**，存于 `D:\案件指派demo\_local-extra\`：

| 文件 | 说明 |
|---|---|
| `scripts/open_lan_firewall.bat` | 远程已用 `.py` 版取代（自动提权、无 GBK 乱码），`.bat` 功能被覆盖 |
| `backend-src/schema.sql` | 旧的重复副本，远程已统一到 `sql/schema.sql` 单一数据源 |
| `data/员工图谱示例.xlsx` | 本地数据文件 |

另有完整备份：`D:\CaseFlow-backup-20261004\local-only\`（确认无误后可删除）。

**环境适配**：`dev.py --check` 五项全部通过（Python 3.13.14 / Maven / Node v22.22.2 / JDK 8 / node_modules 完整）。

---

## 五、常用命令（在 `D:\案件指派demo\app` 下执行）

```bash
# 环境自检
.venv\Scripts\python.exe scripts\dev.py --check

# 一键启动（后端+前端，灌演示数据）
.venv\Scripts\python.exe scripts\dev.py --seed

# Git
git pull          # 拉取
git status        # 查看变更
git push          # 推送
```

> **本机专属的 git 忽略规则写在 `.git/info/exclude`，不要改仓库里的 `.gitignore`** ——
> 改了会导致与远程仓库漂移（别人 pull 会拿到你的本机配置）。