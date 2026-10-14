"""构建「自带 Java 运行环境」的 CaseFlow 便携部署包。

面向完全不懂技术的使用单位：解压 -> 双击【启动.bat】-> 浏览器打开即用。
目标机不需要安装 Java / Maven / Node，试用模式连数据库也不用装。

与 package_deployment.py 的分工：
  - package_deployment.py：轻量包，依赖目标机已装 Java 8，用 PowerShell 启停；
  - package_portable.py  ：便携包，自带 JRE、零依赖，双击 bat 启停（本脚本）。

用法：
    .venv\\Scripts\\python.exe scripts/package_portable.py
    .venv\\Scripts\\python.exe scripts/package_portable.py --skip-build   # 复用已有 jar
    .venv\\Scripts\\python.exe scripts/package_portable.py --out D:\\交付
    .venv\\Scripts\\python.exe scripts/package_portable.py --tutorial <教程.docx>
"""
import argparse
import hashlib
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime
from pathlib import Path

from _common import BACKEND_DIR, PROJECT_DIR, ROOT, TOOLS_DIR

JAR = BACKEND_DIR / "target" / "case-flow-backend.jar"
JRE = TOOLS_DIR / "jdk8" / "jre"
SCHEMA = ROOT / "sql" / "schema.sql"
SCHEMA_INDEX = ROOT / "sql" / "index-mysql.sql"
DEFAULT_TUTORIAL = PROJECT_DIR / "docs" / "manual-v3" / "部署教程.docx"

# 包内顶层目录名（与既有交付物保持一致）
TOP = "CaseFlow-1.0.0"
PORT = 8080


# --------------------------------------------------------------------------
# 包内文本资源。bat 一律按项目约定写 GBK + CRLF，其余写 UTF-8。
# --------------------------------------------------------------------------

README_FIRST = """\
========================================================
 CaseFlow 案件指派系统 —— 请先看我（30 秒看完）
========================================================

三步就能用：

  第 1 步  把这个文件夹解压到 D 盘，比如  D:\\CaseFlow
           （建议就放 D 盘根目录，路径别带中文和空格）

  第 2 步  双击【启动.bat】，按提示选 1 或 2，
           然后等 30 秒左右，直到窗口里出现启动完成的字样。
           ★ 这个黑窗口不要关，关掉就等于停了服务。★

  第 3 步  打开浏览器，地址栏输入：

               http://localhost:8080/api/

           出现登录页就成功了。

--------------------------------------------------------
 第一次登录的账号
--------------------------------------------------------
   用户名  boss
   密  码  admin123

   ★ 正式使用前，请务必先登录进去把这个密码改掉。★

--------------------------------------------------------
 选 1 还是选 2？
--------------------------------------------------------
   1 = 试用模式   不用装任何数据库，数据存在本机文件夹里。
                  适合先看看效果、单机试运行。

   2 = 正式模式   连接单位自己的 MySQL 数据库。
                  适合多人正式使用。需要先建好数据库，
                  并填好【数据库配置.bat】（见 部署教程）。

--------------------------------------------------------
 想让同事也能打开
--------------------------------------------------------
  1. 双击【放行防火墙.bat】（只需做一次）
  2. 把地址里的 localhost 换成本机内网 IP，例如
         http://192.168.1.100:8080/api/

  本机 IP 查法：开始菜单搜 cmd，打开后敲 ipconfig，看“IPv4 地址”。

--------------------------------------------------------
 怎么停止
--------------------------------------------------------
  直接关掉那个黑色窗口就行。
  如果窗口找不到了，双击【停止.bat】。

  详细图文步骤见同目录下《部署教程.docx》
========================================================
"""

DATA_README = """\
这个文件夹存系统的数据，请不要删除、不要改名。

  h2\\       试用模式的数据文件（相当于一个小型数据库）
  uploads\\  上传的附件、图片等文件

★ 备份要点：把 uploads 整个文件夹复制走。
  如果是正式模式（MySQL），数据在数据库里，
  用 mysqldump 导出即可，附件仍备份这个 uploads 文件夹。
"""

LOGS_README = """\
这个文件夹存运行日志。

启动失败、报错、异常的时候，把这里最新的文件发给技术人员，
能很快定位问题。
"""

DB_README = """\
========================================================
 正式模式（MySQL）建库怎么做
========================================================
只有当你在【启动.bat】里选了 2（正式模式），才需要看这个。

需要做三件事：建库、建账号、建索引。

--------------------------------------------------------
 第 1 步  建库
--------------------------------------------------------
用数据库管理工具（Navicat、DBeaver 都行）连上 MySQL，
执行下面两句：

    CREATE DATABASE case_flow
      DEFAULT CHARACTER SET utf8mb4
      DEFAULT COLLATE utf8mb4_general_ci;

--------------------------------------------------------
 第 2 步  建账号
--------------------------------------------------------
    CREATE USER 'caseflow'@'%' IDENTIFIED BY '你设定的口令';
    GRANT ALL PRIVILEGES ON case_flow.* TO 'caseflow'@'%';
    FLUSH PRIVILEGES;

把“你设定的口令”换成你自己的口令，记住它，
等下要填进【数据库配置.bat】。

--------------------------------------------------------
 第 3 步  建索引（正式库必做）
--------------------------------------------------------
连接 case_flow 库，执行本文件夹里的 index-mysql.sql。

    ★ 不做这一步，数据一多系统就会变慢。★

--------------------------------------------------------
 表结构不用手工建
--------------------------------------------------------
表会在服务启动时自动创建（schema.sql 已经打进程序里了）。
schema.sql 也放在这里，供你核对或在别的工具里手工执行。
建完是 19 张表。
"""


def _bat_start() -> str:
    return f"""\
@echo off
chcp 936 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"
title CaseFlow 案件指派系统

echo ============================================================
echo    CaseFlow 案件指派系统  -  启动
echo ============================================================
echo.

set "JAVA_HOME=%~dp0runtime"
if not exist "%JAVA_HOME%\\bin\\java.exe" (
    echo [错误] 找不到自带的 Java 运行环境：runtime\\bin\\java.exe
    echo        请确认压缩包已完整解压，不要只复制部分文件出来。
    echo.
    pause
    exit /b 1
)

if not exist "case-flow-backend.jar" (
    echo [错误] 找不到程序文件 case-flow-backend.jar
    echo        请确认压缩包已完整解压。
    echo.
    pause
    exit /b 1
)

echo 请选择运行模式：
echo.
echo    1 = 试用模式（不用装数据库，先看看效果）
echo    2 = 正式模式（连接单位自己的 MySQL 数据库）
echo.
set /p MODE=请输入 1 或 2 然后回车（直接回车 = 1）：

if "%MODE%"=="2" goto MYSQLMODE

echo.
echo 已选择：试用模式。数据会存在本机的 data 文件夹里。
set "PROFILE=dev"
goto LAUNCH

:MYSQLMODE
echo.
echo 已选择：正式模式。
if exist "数据库配置.bat" (
    call "数据库配置.bat"
) else (
    echo [提示] 没找到【数据库配置.bat】，将使用默认值连接本机 MySQL。
)
set "PROFILE=mysql"
goto LAUNCH

:LAUNCH
echo.
echo ------------------------------------------------------------
echo  正在启动，请稍候 20-40 秒，不要关闭本窗口 ...
echo.
echo  启动完成后，浏览器打开：
echo      http://localhost:{PORT}/api/
echo.
echo  局域网其他电脑访问（把 IP 换成本机地址）：
echo      http://本机IP:{PORT}/api/
echo.
echo  停止服务：按 Ctrl+C，或直接关掉本窗口。
echo ------------------------------------------------------------
echo.

"%JAVA_HOME%\\bin\\java.exe" -Dfile.encoding=UTF-8 -Xmx1024m -jar "case-flow-backend.jar" --spring.profiles.active=%PROFILE% --server.port={PORT}

echo.
echo 服务已停止。
echo.
pause
"""


def _bat_stop() -> str:
    return """\
@echo off
chcp 936 >nul
title 停止 CaseFlow

echo ============================================================
echo    正在停止 CaseFlow 案件指派系统 ...
echo ============================================================
echo.

taskkill /F /T /FI "WINDOWTITLE eq CaseFlow*" >nul 2>&1

if errorlevel 1 (
    echo 没有找到正在运行的 CaseFlow 窗口。
    echo.
    echo 说明：正常停止只要直接关掉那个黑色启动窗口即可。
    echo       如果窗口已经关了，说明服务本来就已停止。
) else (
    echo 已停止。
)

echo.
pause
"""


def _bat_firewall() -> str:
    return f"""\
@echo off
chcp 936 >nul
title 放行防火墙

echo ============================================================
echo    放行防火墙端口 {PORT}
echo ============================================================
echo.
echo 这一步是为了让局域网里别的电脑也能打开本系统。
echo 只需要做一次，以后不用再点。
echo.
echo 需要管理员权限，如果弹出提示请点“是”。
echo.

net session >nul 2>&1
if errorlevel 1 (
    echo [提示] 当前不是管理员身份，正在尝试提权 ...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

netsh advfirewall firewall delete rule name="CaseFlow" >nul 2>&1
netsh advfirewall firewall add rule name="CaseFlow" dir=in action=allow protocol=TCP localport={PORT}

if errorlevel 1 (
    echo.
    echo [失败] 放行没有成功，请手动联系技术人员。
) else (
    echo.
    echo 已放行端口 {PORT}。
    echo 现在局域网其他电脑可以用  http://本机IP:{PORT}/api/  访问。
)

echo.
pause
"""


def _bat_dbconfig() -> str:
    return """\
@echo off
rem ============================================================
rem  数据库配置 —— 只有【正式模式】才用得到
rem
rem  用记事本打开本文件，把下面几行的值改成你自己的，
rem  改完保存即可。口令那一行务必要改。
rem ============================================================

set "MYSQL_HOST=127.0.0.1"
set "MYSQL_PORT=3306"
set "MYSQL_DB=case_flow"
set "MYSQL_USER=caseflow"
set "MYSQL_PASSWORD=请改成你自己的数据库口令"
"""


BAT_FILES = {
    "启动.bat": _bat_start,
    "停止.bat": _bat_stop,
    "放行防火墙.bat": _bat_firewall,
    "数据库配置.bat": _bat_dbconfig,
}

TEXT_FILES = {
    "★ 先看我.txt": README_FIRST,
    "data/说明.txt": DATA_README,
    "logs/说明.txt": LOGS_README,
    "建库脚本/怎么建库.txt": DB_README,
}


# --------------------------------------------------------------------------


def sha256(path: Path) -> str:
    d = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            d.update(chunk)
    return d.hexdigest()


def write_text(path: Path, text: str, encoding: str, newline: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = text.replace("\r\n", "\n").replace("\n", newline)
    path.write_bytes(data.encode(encoding))


def build_source() -> int:
    script = ROOT / "scripts" / "package_release.py"
    return subprocess.run([sys.executable, str(script)], cwd=str(ROOT)).returncode


def main() -> int:
    ap = argparse.ArgumentParser(description="构建 CaseFlow 便携部署包（自带 Java）")
    ap.add_argument("--skip-build", action="store_true", help="复用已有的 target jar")
    ap.add_argument("--out", default=None, help="输出目录，默认项目根 release")
    ap.add_argument("--tutorial", default=None, help="要放进包里的部署教程 .docx 路径")
    ap.add_argument("--keep-staging", action="store_true", help="保留未压缩的 staging 目录")
    args = ap.parse_args()

    print("=" * 60)
    print(" CaseFlow 便携部署包构建")
    print("=" * 60)

    if not args.skip_build:
        print("\n[1/5] 构建前端与后端 ...")
        if build_source() != 0:
            print("  构建失败，已中止。")
            return 1
    else:
        print("\n[1/5] 跳过构建（--skip-build）")

    if not JAR.exists():
        print(f"  找不到成品：{JAR}\n  请先跑 package_release.py", file=sys.stderr)
        return 1
    if not (JRE / "bin" / "java.exe").exists():
        print(f"  找不到自带运行环境：{JRE}\n  请检查 tools\\jdk8 是否完整", file=sys.stderr)
        return 1

    tutorial = Path(args.tutorial) if args.tutorial else DEFAULT_TUTORIAL
    if not tutorial.exists():
        print(f"  [警告] 教程文件不存在，包里将不含部署教程：{tutorial}")

    out_dir = Path(args.out) if args.out else PROJECT_DIR / "release"
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    stage = out_dir / f"_stage-{stamp}" / TOP
    zip_path = out_dir / f"caseflow-portable-1.0.0-{stamp}.zip"

    # ---- 组装 ----
    print("\n[2/5] 组装包内容 ...")
    if stage.parent.exists():
        shutil.rmtree(stage.parent)
    stage.mkdir(parents=True)

    shutil.copy2(JAR, stage / "case-flow-backend.jar")
    print(f"      程序：case-flow-backend.jar  ({JAR.stat().st_size/1024/1024:.1f} MB)")

    print("      运行环境：runtime/  (复制中，约 200MB，需要一会儿) ...")
    shutil.copytree(JRE, stage / "runtime")

    write_text(stage / "建库脚本" / "schema.sql", SCHEMA.read_text(encoding="utf-8"),
               "utf-8", "\r\n")
    write_text(stage / "建库脚本" / "index-mysql.sql", SCHEMA_INDEX.read_text(encoding="utf-8"),
               "utf-8", "\r\n")

    for rel, text in TEXT_FILES.items():
        write_text(stage / rel, text, "utf-8", "\r\n")
    for rel, maker in BAT_FILES.items():
        write_text(stage / rel, maker(), "gbk", "\r\n")

    for d in ("data/h2", "data/uploads", "logs"):
        (stage / d).mkdir(parents=True, exist_ok=True)

    if tutorial.exists():
        shutil.copy2(tutorial, stage / "部署教程.docx")
        print(f"      教程：部署教程.docx")

    # ---- 打包 ----
    print("\n[3/5] 压缩 ...")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for f in sorted(stage.rglob("*")):
            if f.is_file():
                z.write(f, f"{TOP}/{f.relative_to(stage).as_posix()}")

    # ---- 校验 ----
    print("\n[4/5] 校验 ...")
    with zipfile.ZipFile(zip_path) as z:
        names = set(z.namelist())
        must = [
            f"{TOP}/case-flow-backend.jar",
            f"{TOP}/runtime/bin/java.exe",
            f"{TOP}/启动.bat",
            f"{TOP}/停止.bat",
            f"{TOP}/放行防火墙.bat",
            f"{TOP}/数据库配置.bat",
            f"{TOP}/★ 先看我.txt",
            f"{TOP}/建库脚本/schema.sql",
            f"{TOP}/建库脚本/index-mysql.sql",
        ]
        missing = [m for m in must if m not in names]
        if missing:
            print("  校验失败，缺少：", file=sys.stderr)
            for m in missing:
                print(f"    - {m}", file=sys.stderr)
            return 1
        print(f"      必需文件齐全（共 {len(names)} 项）")

    if not args.keep_staging:
        # 清理未压缩的中间目录。这一步失败不影响交付物，只提示即可
        # （某些环境的安全策略会拦截批量删除）。
        try:
            shutil.rmtree(stage.parent)
        except Exception as exc:                                  # noqa: BLE001
            print()
            print(f"      [提示] 中间目录未能自动删除：{stage.parent}")
            print(f"             不影响部署包，可稍后手工删除。原因：{exc}")

    # ---- 报告 ----
    size_mb = zip_path.stat().st_size / 1024 / 1024
    print("\n[5/5] 完成")
    print("=" * 60)
    print(f" 部署包：{zip_path}")
    print(f" 大小  ：{size_mb:.1f} MB")
    print(f" 校验  ：{sha256(zip_path)[:16]}...")
    print("=" * 60)
    print(" 交付给使用单位后，对方只需：")
    print("   1) 解压到 D:\\CaseFlow")
    print("   2) 双击【启动.bat】")
    print("   3) 浏览器打开 http://localhost:8080/api/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
