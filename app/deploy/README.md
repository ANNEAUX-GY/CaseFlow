# CaseFlow 应用部署包

这是由 `scripts/package_deployment.py` 生成的可运行包。目标机不需要 Node.js、Maven 或 Python，只需要 Java 8+。部署包支持两种运行 profile：

- `dev`：H2 文件库，零安装，适合单机使用、验收和小规模试运行。
- `mysql`：MySQL 8+，适合多人正式使用。先创建数据库和应用账号，再设置 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER`、`MYSQL_PASSWORD`。

## 启动

在解压目录打开 PowerShell：

```powershell
.\start.ps1 -Profile dev
```

正式环境：

```powershell
$env:MYSQL_HOST = "127.0.0.1"
$env:MYSQL_PORT = "3306"
$env:MYSQL_DB = "case_flow"
$env:MYSQL_USER = "caseflow"
$env:MYSQL_PASSWORD = "change-me"
.\start.ps1 -Profile mysql
```

默认服务地址是 `http://127.0.0.1:8080/api/`。局域网访问可增加 `-BindAddress 0.0.0.0`，并在操作系统防火墙放行对应端口。服务日志在 `logs/caseflow.out.log` 和 `logs/caseflow.err.log`，进程号在 `logs/caseflow.pid`。

```powershell
.\status.ps1
.\stop.ps1
```

## 重要事项

首次登录账号是 `boss / admin123`，部署完成后立即修改密码。正式数据必须备份 MySQL 和 `CASEFLOW_UPLOAD_DIR` 指向的附件目录；不要把 `.env` 或真实密码提交到 Git。`sql/schema.sql` 和 `sql/index-mysql.sql` 是建库材料，`VERSION.txt` 与 `SHA256SUMS.txt` 用于版本和完整性核对。
