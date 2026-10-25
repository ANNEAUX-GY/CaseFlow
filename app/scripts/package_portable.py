"""构建「自带 Java 运行环境」的 CaseFlow 便携部署包。

面向完全不懂技术的使用单位：解压 -> 双击【启动.bat】-> 浏览器打开即用。
目标机不需要安装 Java / Maven / Node，试用模式连数据库也不用装。

同时产出一个体积很小的「部署资料包」，只含部署说明与建库语句，便于单独
发给负责建库的同事或打印留存；以及一个「升级补丁包」，只含新 jar 与升级
说明，用于已部署系统的版本更新（内网传输体积最小）。

与 package_deployment.py 的分工：
  - package_deployment.py：轻量包，依赖目标机已装 Java 8，用 PowerShell 启停；
  - package_portable.py  ：便携包，自带 JRE、零依赖，双击 bat 启停（本脚本）。

用法：
    .venv\\Scripts\\python.exe scripts/package_portable.py
    .venv\\Scripts\\python.exe scripts/package_portable.py --skip-build   # 复用已有 jar
    .venv\\Scripts\\python.exe scripts/package_portable.py --out D:\\交付
    .venv\\Scripts\\python.exe scripts/package_portable.py --tutorial <教程.docx>
    .venv\\Scripts\\python.exe scripts/package_portable.py --docs-only    # 只出资料包
    .venv\\Scripts\\python.exe scripts/package_portable.py --patch-only   # 只出升级补丁包
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
UPGRADE_DIR = ROOT / "sql" / "upgrade"   # 结构变更说明 + 升级补列 SQL（gen_upgrade_sql.py 生成）
TUTORIAL_DOCX = PROJECT_DIR / "docs" / "manual-v3" / "部署教程.docx"
TUTORIAL_MD = PROJECT_DIR / "docs" / "manual-v3" / "部署教程.md"

TOP = "CaseFlow-1.0.0"
DOCS_DIR = "部署说明"          # 部署说明与建库语句集中放这里
SQL_DIR = f"{DOCS_DIR}/建库语句"
DOCS_PKG_TOP = "CaseFlow-部署资料"
PATCH_TOP = "CaseFlow-升级补丁"
PORT = 8080


# --------------------------------------------------------------------------
# 包内文本资源。bat 一律按项目约定写 GBK + CRLF；txt 写 UTF-8+BOM（记事本友好）；
# sql / md 写 UTF-8 无 BOM（SQL 带 BOM 可能被客户端当语法错误）。
# --------------------------------------------------------------------------

README_FIRST = f"""\
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

               http://localhost:{PORT}/api/

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
                  适合多人正式使用。需要先建好数据库。

                  第一次选正式模式，程序会自动生成【数据库配置.bat】，
                  用记事本把里面的口令改成你们的，再启动一次即可。
                  （以后更新整包覆盖也不会冲掉这个文件）

--------------------------------------------------------
 想让同事也能打开
--------------------------------------------------------
  1. 双击【放行防火墙.bat】（只需做一次）
  2. 把地址里的 localhost 换成本机内网 IP，例如
         http://192.168.1.100:{PORT}/api/

  本机 IP 查法：开始菜单搜 cmd，打开后敲 ipconfig，看“IPv4 地址”。

--------------------------------------------------------
 怎么停止
--------------------------------------------------------
  直接关掉那个黑色窗口就行。
  如果窗口找不到了，双击【停止.bat】。

--------------------------------------------------------
 {DOCS_DIR}\\ 文件夹里是什么
--------------------------------------------------------
   部署教程.docx          完整的图文部署教程，遇到问题先翻它
   建库语句\\             正式模式建 MySQL 库用的脚本（三步）
       00-建库怎么用（先看我）.txt   先看这个
       1-建库和账号.sql              第 1 步
       2-建表.sql                    第 2 步
       3-建索引.sql                  第 3 步（正式库必做）

   只需要说明和建库脚本的话，另外那个
   【CaseFlow-部署资料】压缩包里就是这些，体积很小。
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

DB_HELP = """\
========================================================
 建库怎么用（正式模式 / MySQL）
========================================================
只有当你在【启动.bat】里选了 2（正式模式），才需要看这个。

下面三个文件，按顺序执行，一个都别跳：

    1-建库和账号.sql    建数据库 + 建应用账号
    2-建表.sql          建表（22 张）
    3-建索引.sql        建索引  ← 正式库必做，不做数据一多就慢

--------------------------------------------------------
 用什么执行
--------------------------------------------------------
MySQL 命令行、Navicat、DBeaver 都行。
用数据库管理员账号（root 或同等权限）执行前两个；
第 3 个连到 case_flow 库执行即可。

命令行示例（在部署包根目录打开 cmd）：

    mysql -uroot -p < "部署说明\\建库语句\\1-建库和账号.sql"
    mysql -uroot -p case_flow < "部署说明\\建库语句\\2-建表.sql"
    mysql -uroot -p case_flow < "部署说明\\建库语句\\3-建索引.sql"

--------------------------------------------------------
 两件必须注意的事
--------------------------------------------------------
1. 执行 1-建库和账号.sql 之前，先打开它，
   把里面的口令改成你自己的，并记住它。
   ★ 这个口令等下要填进【数据库配置.bat】（第一次启动正式模式时自动生成）。

2. 表不用手工建也基本能用（程序启动时会自动建），
   但 3-建索引.sql 一定要单独执行一遍。

--------------------------------------------------------
 目录里另外两个 4- 开头的文件，新装不用管
--------------------------------------------------------
    4-说明（结构变更）.txt          已有库升级时"这次改了什么"的说明
    4-升级补列（已有库执行）.sql    已有库升级时才用

全新安装（空库）只要上面 1、2、3 三步，两个 4- 文件原样留着即可，
不要执行——库是空的，没有旧字段要补。

--------------------------------------------------------
 建完应该是什么样
--------------------------------------------------------
数据库：case_flow
账号  ：caseflow
表    ：19 张
"""

SETUP_SQL = f"""\
-- =====================================================================
--  CaseFlow 建库脚本   第 1 步 / 共 3 步
--  作用：建立数据库和应用账号
--  用谁执行：数据库管理员账号（root 或同等权限）
--  在哪执行：MySQL 命令行，或 Navicat / DBeaver 的 SQL 窗口
-- =====================================================================

-- ---------------------------------------------------------------------
--  ① 建库
-- ---------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS case_flow
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

-- ---------------------------------------------------------------------
--  ② 建应用账号
--
--  ★★ 下面这行里的口令，请改成你自己的 ★★
--     改完记住它 —— 这个口令还要填进【数据库配置.bat】
--     （第一次以正式模式启动时会自动生成这个文件）
-- ---------------------------------------------------------------------
CREATE USER IF NOT EXISTS 'caseflow'@'%' IDENTIFIED BY '请改成你自己的口令';

-- ---------------------------------------------------------------------
--  ③ 授权
-- ---------------------------------------------------------------------
GRANT ALL PRIVILEGES ON case_flow.* TO 'caseflow'@'%';
FLUSH PRIVILEGES;

-- ---------------------------------------------------------------------
--  执行完本文件，接着执行 2-建表.sql
-- ---------------------------------------------------------------------
"""

SCHEMA_HEADER = """\
-- =====================================================================
--  CaseFlow 建库脚本   第 2 步 / 共 3 步
--  作用：建表（19 张）
--  用谁执行：case_flow 库的管理员账号
--  说明：程序启动时也会自动执行同样内容（幂等，重复跑没关系），
--        这里放一份，方便你核对或在别的工具里手工执行。
-- =====================================================================

"""

INDEX_HEADER = """\
-- =====================================================================
--  CaseFlow 建库脚本   第 3 步 / 共 3 步
--  作用：建二级索引
--
--  ★ 正式库必做：不做索引，数据一多系统会明显变慢。★
--
--  注意：程序启动时【不会】自动执行本文件，必须手工跑一遍。
-- =====================================================================

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
rem 首次运行：从示例复制出一份真正的配置文件。
rem 以后每次更新整包覆盖，包里只有「示例」，不会动这份改好的配置。
if not exist "数据库配置.bat" (
    if exist "数据库配置.示例.bat" (
        copy /y "数据库配置.示例.bat" "数据库配置.bat" >nul
        echo.
        echo ============================================================
        echo  [首次配置] 已生成【数据库配置.bat】
        echo.
        echo  请用记事本打开它，把数据库口令改成你们的真实口令，
        echo  保存后重新双击【启动.bat】。
        echo ============================================================
        echo.
        pause
        exit /b 1
    )
)
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
rem  数据库配置（示例模板）—— 只有【正式模式】才用得到
rem
rem  这个文件是模板，请不要直接改它。
rem  第一次启动时，【启动.bat】会自动复制出一份【数据库配置.bat】，
rem  改那一份（把口令改成你们自己的），改完保存重新启动即可。
rem
rem  为什么分两份：以后更新时整个包覆盖替换，包里只有这份示例，
rem  不会把你们已经填好的【数据库配置.bat】冲掉。
rem
rem  建库步骤见  部署说明\\建库语句\\00-建库怎么用（先看我）.txt
rem ============================================================

set "MYSQL_HOST=127.0.0.1"
set "MYSQL_PORT=3306"
set "MYSQL_DB=case_flow"
set "MYSQL_USER=caseflow"
set "MYSQL_PASSWORD=请改成你自己的数据库口令"
"""


UPGRADE_TXT = """\
========================================================
 CaseFlow 案件指派系统 —— 升级说明（照着做就行）
========================================================

这个补丁包是用来【更新已有系统】的。
如果是要装一套新的，请看完整部署包里的《部署教程》。


包里有什么
--------------------------------------------------------
  case-flow-backend.jar   新的程序本体（就替换这一个文件）
  升级说明.txt            就是本文件
  建库语句\\              一般用不上；只有本说明特别提示时才跑


升级四步
--------------------------------------------------------
  第 1 步  停服
           在您原来的安装目录（比如 D:\\CaseFlow）里，
           双击【停止.bat】，等它显示已停止。

  第 2 步  备份（铁律，不能省）
           ① 数据库：
              - 正式模式（MySQL）：用 mysqldump 导出 case_flow 库，
                或者直接在数据库服务器上做个快照。
              - 试用模式：把安装目录里的 data\\ 文件夹整个复制一份即可。
           ② 附件：把 data\\uploads\\ 文件夹复制一份。

  第 3 步  覆盖
           把本包里的 case-flow-backend.jar 复制到安装目录，
           替换掉旧的同名文件。

           ★ 建议先把旧的改个名留着，比如改成：
                 case-flow-backend.jar.old
             万一新版有问题，改回来重启就恢复了。

  第 4 步  启动并验证
           双击【启动.bat】，等 30 秒左右，
           浏览器打开原来的地址，能正常登录、能打开案件列表，
           就说明升级成功了。


绝对不能覆盖的东西
--------------------------------------------------------
  data\\ 文件夹      所有业务数据和附件都在这里
  runtime\\ 文件夹   自带的 Java 运行环境，升级时不用换

  （如果您是想「整个包解压覆盖」替换，也没问题——
    包里不含 data 里的业务数据，附件也不会动；
    数据库口令只保存在本机的【数据库配置.bat】里，
    包里只带一个「数据库配置.示例.bat」模板，不会把它冲掉。）

  只替换 case-flow-backend.jar 这一个文件，其它一律不动，
  就绝对不会出问题。


数据库要不要手工改？
--------------------------------------------------------
  一般不用。

  程序每次启动时会自己检查并升级表结构：
    有新表      -> 自动建好
    老表加字段  -> 自动补齐

  所以换完 jar 重启，数据库就跟着升级好了，
  您不需要手工执行任何建表语句。


本次到底改了哪些字段？附了说明和 SQL
--------------------------------------------------------
  本次更新如果动过数据库结构（加字段、加表），包里同时提供了两个文件，
  都在  建库语句\  目录下：

    4-说明（结构变更）.txt
        人话说明：本次新增了哪些字段、为什么加、对老数据有什么影响。

    4-升级补列（已有库执行）.sql
        对应的改库语句。两种用法：

        用法一（推荐）：不用管它。换 jar 重启，程序自动补列，
                        效果与执行这个脚本完全一样。

        用法二（DBA 要先审 SQL、不让程序改表）：
            1) 先备份数据库；
            2) 执行  4-升级补列（已有库执行）.sql
               （mysql -u caseflow -p case_flow < 该文件，
                 或整个粘到 Navicat / DBeaver 里跑）
            3) 再换 jar 启动。

        这个脚本是"缺哪列补哪列"：已存在的列自动跳过，重复执行不会报错，
        也不会修改任何一行数据。只加字段，绝不删改。

  唯一例外：如果这次更新带来了新的查询索引，本说明末尾会单独列出，
  那时才需要手工跑一次 建库语句\\3-建索引.sql。
  如果执行时报 Duplicate key name，说明索引早就有了，直接忽略，不是错误。


数据结构会不会被改坏？已经录进去的数据会不会动？
--------------------------------------------------------
  ★ 不会。

  程序对数据库只做两件事：
    ① 建缺失的表      已有的表一律不碰
    ② 补新增的字段    只加不删、不改字段类型、不改表名

  已经录进去的数据（案件、待办、领导意见、嫌疑人、附件、
  账号、组织机构、案件类别……）原样保留，程序不会删除、
  不会清空、也不会覆盖您已经填好的内容。

  另外有一批「旧数据格式整理」（比如把旧字段的内容搬到新字段），
  这类动作每个数据库一辈子只跑一次，跑完就记在
  数据库的 schema_migration_mark 表里，以后启动直接跳过。
  所以第二次、第三次升级都不会再动这些数据。

  唯一会看到变化的地方：如果库里还留着已下线的「民事」分类案件，
  日志里会出现一条提示，但案件本身不会被改动，需要的话由管理员
  在界面上手工调整。


出问题了怎么回退
--------------------------------------------------------
  把第 3 步留着的那份 case-flow-backend.jar.old
  改名回 case-flow-backend.jar，重启即可。

  注意：表结构升级之后不会自动回退（新表、新字段会留在数据库里）。
  旧版程序一般不受影响，但仍建议一并恢复第 2 步的数据库备份。


还搞不定？
--------------------------------------------------------
  联系技术人员时，请把下面三样一起发过去，能省很多时间：
    ① logs\\ 文件夹里最新的日志文件
    ② 报错窗口的截图
    ③ 您刚才做了哪几步

========================================================
"""


BAT_FILES = {
    "启动.bat": _bat_start,
    "停止.bat": _bat_stop,
    "放行防火墙.bat": _bat_firewall,
    # 只发「示例」，真正的 数据库配置.bat 由 启动.bat 首次运行时生成。
    # 这样内网「整个包覆盖替换」时不会把他们改过的口令打回占位（2026-10-25）。
    "数据库配置.示例.bat": _bat_dbconfig,
}

# txt 走 UTF-8 + BOM（记事本一定能正确显示）
TXT_FILES = {
    "★ 先看我.txt": README_FIRST,
    "data/说明.txt": DATA_README,
    "logs/说明.txt": LOGS_README,
}


# --------------------------------------------------------------------------


def sha256(path: Path) -> str:
    d = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            d.update(chunk)
    return d.hexdigest()


def write_text(path: Path, text: str, encoding: str = "utf-8",
               newline: str = "\r\n", bom: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = text.replace("\r\n", "\n").replace("\n", newline)
    payload = data.encode(encoding)
    if bom:
        payload = b"\xef\xbb\xbf" + payload
    path.write_bytes(payload)


def build_source() -> int:
    script = ROOT / "scripts" / "package_release.py"
    return subprocess.run([sys.executable, str(script)], cwd=str(ROOT)).returncode


def fill_docs_dir(target: Path, tutorial: Path) -> None:
    """把「部署说明」内容填进指定目录：教程 + 建库语句（三步）。"""
    sql_dir = target / "建库语句"
    sql_dir.mkdir(parents=True, exist_ok=True)

    if tutorial.exists():
        shutil.copy2(tutorial, target / "部署教程.docx")
    if TUTORIAL_MD.exists():
        write_text(target / "部署教程.md", TUTORIAL_MD.read_text(encoding="utf-8"))

    # 建库语句：三步，序号在文件名里，按顺序做就行
    write_text(sql_dir / "00-建库怎么用（先看我）.txt", DB_HELP, bom=True)
    write_text(sql_dir / "1-建库和账号.sql", SETUP_SQL)
    write_text(sql_dir / "2-建表.sql",
               SCHEMA_HEADER + SCHEMA.read_text(encoding="utf-8"))
    write_text(sql_dir / "3-建索引.sql",
               INDEX_HEADER + SCHEMA_INDEX.read_text(encoding="utf-8"))
    copy_upgrade_files(sql_dir)


def fill_patch_dir(target: Path) -> None:
    """填升级补丁包内容：新 jar + 升级说明 + 建库语句（备用）。

    刻意不放部署教程（几百 KB 且对新装才有意义）与 runtime（每次升级都不用换），
    让补丁包尽可能小，便于内网传输。
    """
    shutil.copy2(JAR, target / "case-flow-backend.jar")
    write_text(target / "升级说明.txt", UPGRADE_TXT, bom=True)

    sql_dir = target / "建库语句"
    sql_dir.mkdir(parents=True, exist_ok=True)
    write_text(sql_dir / "00-建库怎么用（先看我）.txt", DB_HELP, bom=True)
    write_text(sql_dir / "1-建库和账号.sql", SETUP_SQL)
    write_text(sql_dir / "2-建表.sql",
               SCHEMA_HEADER + SCHEMA.read_text(encoding="utf-8"))
    write_text(sql_dir / "3-建索引.sql",
               INDEX_HEADER + SCHEMA_INDEX.read_text(encoding="utf-8"))
    copy_upgrade_files(sql_dir)


def copy_upgrade_files(sql_dir: Path) -> None:
    """把「结构变更说明 + 升级补列 SQL」放进包里。

    用户要求（2026-10-25）：只要动了数据库结构，包内必须同时给出变更说明和
    可执行的建库/改库语句。这两个文件由 gen_upgrade_sql.py 从
    SchemaMigration.NEW_COLUMNS 现生成，保证 SQL 永远和代码一致，不会人手抄错。
    """
    for name in ("4-说明（结构变更）.txt", "4-升级补列（已有库执行）.sql"):
        src = UPGRADE_DIR / name
        if not src.exists():
            raise SystemExit(
                f"缺少 {src}\n"
                f"请先执行：python scripts/gen_upgrade_sql.py（它会从代码生成升级 SQL）")
        shutil.copy2(src, sql_dir / name)


def make_zip(zip_path: Path, top: str, root: Path) -> None:
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for f in sorted(root.rglob("*")):
            if f.is_file():
                z.write(f, f"{top}/{f.relative_to(root).as_posix()}")


def main() -> int:
    ap = argparse.ArgumentParser(description="构建 CaseFlow 便携部署包")
    ap.add_argument("--skip-build", action="store_true", help="复用已有的 target jar")
    ap.add_argument("--out", default=None, help="输出目录，默认项目根 release")
    ap.add_argument("--tutorial", default=None, help="部署教程 .docx 路径")
    ap.add_argument("--keep-staging", action="store_true", help="保留未压缩的 staging 目录")
    ap.add_argument("--docs-only", action="store_true", help="只出小体积资料包，不重打大包")
    ap.add_argument("--patch-only", action="store_true",
                    help="只出升级补丁包（新 jar + 升级说明 + 建库语句）")
    args = ap.parse_args()

    global TUTORIAL_DOCX
    if args.tutorial:
        TUTORIAL_DOCX = Path(args.tutorial)

    print("=" * 60)
    print(" CaseFlow 便携部署包构建")
    print("=" * 60)

    # 结构变更说明 + 升级补列 SQL：每次出包都从代码现生成，杜绝 SQL 与代码不一致
    gen = PROJECT_DIR / "scripts" / "gen_upgrade_sql.py"
    if gen.exists():
        rc = subprocess.run([sys.executable, str(gen)], cwd=str(PROJECT_DIR)).returncode
        if rc != 0:
            print("  升级 SQL 生成失败（自检未通过），已中止出包。", file=sys.stderr)
            return 1

    if not args.skip_build and not args.docs_only:
        print("\n[1/6] 构建前端与后端 ...")
        if build_source() != 0:
            print("  构建失败，已中止。")
            return 1
    else:
        print("\n[1/6] 跳过构建")

    out_dir = Path(args.out) if args.out else PROJECT_DIR / "release"
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    stage_root = out_dir / f"_stage-{stamp}"
    stage = stage_root / TOP
    doc_pkg = out_dir / f"_stage-{stamp}" / DOCS_PKG_TOP

    # ---- 升级补丁包（只出这一个，就返回）----
    if args.patch_only:
        if not JAR.exists():
            print(f"  找不到成品：{JAR}\n  请先跑 package_release.py", file=sys.stderr)
            return 1
        print("\n[2/2] 组装升级补丁包（新 jar + 升级说明 + 建库语句）...")
        patch_dir = stage_root / PATCH_TOP
        patch_dir.mkdir(parents=True, exist_ok=True)
        fill_patch_dir(patch_dir)
        patch_zip = out_dir / f"{PATCH_TOP}-{stamp}.zip"
        make_zip(patch_zip, PATCH_TOP, patch_dir)
        print(f"      {patch_zip.name}（{patch_zip.stat().st_size/1024/1024:.1f} MB）")

        with zipfile.ZipFile(patch_zip) as z:
            names = set(z.namelist())
            must = [
                f"{PATCH_TOP}/case-flow-backend.jar",
                f"{PATCH_TOP}/升级说明.txt",
                f"{PATCH_TOP}/建库语句/3-建索引.sql",
                # 用户要求：改了库结构就必须随包给说明和改库语句，缺了不许出包
                f"{PATCH_TOP}/建库语句/4-说明（结构变更）.txt",
                f"{PATCH_TOP}/建库语句/4-升级补列（已有库执行）.sql",
            ]
            missing = [m for m in must if m not in names]
            if missing:
                print("  校验失败，缺少：", file=sys.stderr)
                for m in missing:
                    print(f"    - {m}", file=sys.stderr)
                return 1
            print(f"      必需文件齐全（共 {len(names)} 项）")

        if not args.keep_staging:
            try:
                shutil.rmtree(stage_root)
            except Exception as exc:                              # noqa: BLE001
                print(f"      [提示] 中间目录未删除：{stage_root}（{exc}）")

        print("\n完成。把这个补丁包发给使用单位，按包内《升级说明.txt》操作即可：")
        print(f"   {patch_zip}")
        return 0

    # ---- 小资料包（增量、便宜，先做）----
    print("\n[2/6] 组装部署资料包（说明 + 建库语句）...")
    doc_pkg.mkdir(parents=True, exist_ok=True)
    fill_docs_dir(doc_pkg, TUTORIAL_DOCX)
    docs_zip = out_dir / f"CaseFlow-部署资料-{stamp}.zip"
    make_zip(docs_zip, DOCS_PKG_TOP, doc_pkg)
    print(f"      {docs_zip.name}（{docs_zip.stat().st_size/1024:.0f} KB）")

    if args.docs_only:
        if not args.keep_staging:
            try:
                shutil.rmtree(stage_root)
            except Exception as exc:                              # noqa: BLE001
                print(f"      [提示] 中间目录未删除：{stage_root}（{exc}）")
        print("\n完成（仅资料包）：", docs_zip)
        return 0

    # ---- 大包 ----
    if not JAR.exists():
        print(f"  找不到成品：{JAR}\n  请先跑 package_release.py", file=sys.stderr)
        return 1
    if not (JRE / "bin" / "java.exe").exists():
        print(f"  找不到自带运行环境：{JRE}\n  请检查 tools\\jdk8 是否完整", file=sys.stderr)
        return 1

    print("\n[3/6] 组装便携部署包 ...")
    stage.mkdir(parents=True, exist_ok=True)
    shutil.copy2(JAR, stage / "case-flow-backend.jar")
    print(f"      程序：case-flow-backend.jar（{JAR.stat().st_size/1024/1024:.1f} MB）")

    print("      运行环境：runtime/（复制中，约 200MB，需要一会儿）...")
    shutil.copytree(JRE, stage / "runtime")

    # 部署说明：教程 + 建库语句集中放一起
    fill_docs_dir(stage / DOCS_DIR, TUTORIAL_DOCX)
    print(f"      {DOCS_DIR}/：部署教程 + 建库语句")

    for rel, text in TXT_FILES.items():
        write_text(stage / rel, text, bom=True)
    for rel, maker in BAT_FILES.items():
        write_text(stage / rel, maker(), encoding="gbk")

    for d in ("data/h2", "data/uploads", "logs"):
        (stage / d).mkdir(parents=True, exist_ok=True)

    print("\n[4/6] 压缩 ...")
    zip_path = out_dir / f"caseflow-portable-1.0.0-{stamp}.zip"
    make_zip(zip_path, TOP, stage)

    print("\n[5/6] 校验 ...")
    with zipfile.ZipFile(zip_path) as z:
        names = set(z.namelist())
        must = [
            f"{TOP}/case-flow-backend.jar",
            f"{TOP}/runtime/bin/java.exe",
            f"{TOP}/启动.bat",
            f"{TOP}/停止.bat",
            f"{TOP}/放行防火墙.bat",
            f"{TOP}/数据库配置.示例.bat",
            f"{TOP}/★ 先看我.txt",
            f"{TOP}/{DOCS_DIR}/部署教程.docx",
            f"{TOP}/{SQL_DIR}/00-建库怎么用（先看我）.txt",
            f"{TOP}/{SQL_DIR}/1-建库和账号.sql",
            f"{TOP}/{SQL_DIR}/2-建表.sql",
            f"{TOP}/{SQL_DIR}/3-建索引.sql",
            f"{TOP}/{SQL_DIR}/4-说明（结构变更）.txt",
            f"{TOP}/{SQL_DIR}/4-升级补列（已有库执行）.sql",
        ]
        missing = [m for m in must if m not in names]
        if missing:
            print("  校验失败，缺少：", file=sys.stderr)
            for m in missing:
                print(f"    - {m}", file=sys.stderr)
            return 1
        print(f"      必需文件齐全（共 {len(names)} 项）")

    if not args.keep_staging:
        try:
            shutil.rmtree(stage_root)
        except Exception as exc:                                  # noqa: BLE001
            print(f"\n      [提示] 中间目录未能自动删除：{stage_root}")
            print(f"             不影响交付物，可稍后手工删除。原因：{exc}")

    print("\n[6/6] 完成")
    print("=" * 60)
    print(f" 便携部署包：{zip_path.name}")
    print(f"              {zip_path.stat().st_size/1024/1024:.1f} MB")
    print(f" 部署资料包：{docs_zip.name}")
    print(f"              {docs_zip.stat().st_size/1024:.0f} KB")
    print("=" * 60)
    print(" 交付给使用单位后，对方只需：")
    print("   1) 解压到 D:\\CaseFlow")
    print("   2) 双击【启动.bat】")
    print(f"   3) 浏览器打开 http://localhost:{PORT}/api/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
