# CaseFlow 案件指派系统：部署与开发包

这是可继续开发的应用级源码包，不是只读演示截图。项目由 Spring Boot 后端、Vue 3 前端、MySQL/H2 数据层和 Python 运维脚本组成。

## 1. 目录与包类型

- `app/`：完整源码、SQL、开发脚本、运行配置和技术文档。
- `app/backend/`：Java 8 + Spring Boot 2.7.18 后端。
- `app/frontend/`：Vue 3 + Vite 前端，已提交 `package-lock.json`。
- `app/scripts/`：环境检查、启动、测试、数据库和打包脚本。
- `release/`：本机生成的部署包和源码包，不提交到 Git。

本项目提供两种交付物：

1. **应用部署包**：包含已构建 JAR、启动/停止/状态脚本、数据库脚本、配置示例和校验信息，目标机只需 Java 8+；默认可用 H2 零安装启动，正式使用建议切换 MySQL。
2. **源码开发包**：包含可编辑的前后端源代码和锁定的依赖清单，不包含本机依赖缓存、密钥、构建产物和 Git 历史。

## 2. 新电脑继续开发

### 前置环境

- Windows 10/11 或 Windows Server。
- JDK 8（项目按 Java 8 编译；可用 `JAVA_HOME` 指定）。
- Node.js 18+，推荐 Node 22；npm 随 Node 安装。
- Python 3.10+。
- Maven 3.9+，或者把便携工具链放到源码包上一级的 `tools/` 目录。

### 首次准备

```powershell
cd app
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
\.venv\Scripts\python.exe scripts\dev.py --check
cd frontend
npm ci --include=optional --no-audit --no-fund
cd ..
```

如果本机没有 Maven，可安装 Maven 后设置 `MAVEN_HOME`/`PATH`，或者运行 `scripts/setup_toolchain.py` 下载便携 Maven。`dev.py` 会优先查找源码包上一级的 `tools/jdk8`、`tools/maven`、`tools/node`。

### 启动开发环境

```powershell
cd app
\.venv\Scripts\python.exe scripts\dev.py --seed
```

打开 `http://127.0.0.1:5173`，默认管理员为 `boss / admin123`。后端地址为 `http://127.0.0.1:8080/api`，开发默认使用可持久化 H2 文件库。

修改后端代码后重启后端；修改前端 `src/` 后 Vite 会热更新。正式数据不要使用 H2 演示库，切换到 MySQL profile。

## 3. 应用部署

先在有完整开发工具链的机器上构建：

```powershell
cd app
\.venv\Scripts\python.exe scripts\package_deployment.py
```

生成的 `release/caseflow-deployment-*.zip` 可解压到目标机。进入解压目录后使用：

```powershell
.\start.ps1 -Profile dev
.\status.ps1
.\stop.ps1
```

正式 MySQL 部署前设置 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER`、`MYSQL_PASSWORD`。首次建库执行源码包中的 `app/scripts/init_db.py`，或按部署包内 `sql/schema.sql` 与 `sql/index-mysql.sql` 执行。附件目录通过 `CASEFLOW_UPLOAD_DIR` 指向独立备份目录。

## 4. 验证与升级

```powershell
cd app
\.venv\Scripts\python.exe scripts\dev.py --check
\.venv\Scripts\python.exe scripts\package_release.py
```

升级前备份 MySQL 数据库和附件目录；部署包内的 `VERSION.txt`、`SHA256SUMS.txt` 用于核对交付版本。数据库结构采用幂等建表和启动迁移，正式环境仍应先做数据库备份，再替换 JAR。

## 5. 重要安全事项

- 首次登录后立即修改 `boss` 密码，并删除或禁用不需要的账号。
- 正式环境必须使用 MySQL，不要把含业务数据的 H2 文件库当作多人生产库。
- 密码、数据库连接和真实环境变量只放在目标机，不要提交 `.env`。
- 生产部署建议由 Nginx 或单位网关代理 HTTPS，并限制后端和数据库端口的访问范围。
