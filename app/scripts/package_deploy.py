"""打包「交付包」：一台全新电脑解压即用，无需任何开发环境。

面向对象：**不懂技术的单位/民警**。目标机上不需要装 Java、不需要装
MySQL 客户端、不需要会敲命令，只需要能双击鼠标。

与另外两个脚本的区别：
  package_source.py    交源码（含 .java/.vue），给要接手继续开发的人
  package_release.py   打成单个 jar，但仍把敏感配置与建表脚本带在里面
  package_deploy.py    交成品（本脚本）——jar 瘦身 + 便携运行时 + 外置配置 + 傻瓜启动器

安全设计（详见 docs/交付防泄露方案.md）：
  1. jar 内剔除 application-dev.yml（H2 控制台开关）与 sql/*.sql（建表脚本明文）
  2. 数据库口令外置到 config/，不进 jar
  3. 交付目录内**不含**任何 .java/.vue/.py/pom.xml/node_modules/tools/数据库文件
  4. 打包后自动删除 release/ 下的 *-source-*.zip，从源头断掉误发可能
  5. 出包后自检 zip 内容，发现源码即报错终止

用法：
    .venv\\Scripts\\python.exe scripts\\package_deploy.py
    .venv\\Scripts\\python.exe scripts\\package_deploy.py --skip-build   # 复用已有 jar
    .venv\\Scripts\\python.exe scripts\\package_deploy.py --no-jre       # 目标机已有 Java 时

产物：release\\caseflow-deploy-<版本>.zip
"""
import argparse
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import BACKEND_DIR, FRONTEND_DIR, PROJECT_DIR, ROOT, TOOLS_DIR  # noqa: E402

JAR = BACKEND_DIR / "target" / "case-flow-backend.jar"
RELEASE_DIR = PROJECT_DIR / "release"
STAGE_DIR = RELEASE_DIR / "_stage"
JRE_SRC = TOOLS_DIR / "jdk8" / "jre"
DOCS = ROOT / "docs"

DEFAULT_VERSION = "1.0.0"

# ---------------------------------------------------------------------------
# jar 内必须剔除的条目
# ---------------------------------------------------------------------------
# application-dev.yml 里 spring.h2.console.enabled=true，
# 一旦被启用可绕过登录界面直连数据库。
# sql/*.sql 是全表结构明文，不主动送；改为放在交付目录的「建库脚本」里，
# 由部署方在数据库里执行一次（见外置配置里的 spring.sql.init.mode: never）。
DROP_ENTRIES = {
    "BOOT-INF/classes/application-dev.yml",
    "BOOT-INF/classes/sql/schema.sql",
    "BOOT-INF/classes/sql/index-mysql.sql",
    "BOOT-INF/classes/sql/",
    "BOOT-INF/classes/sql",
}

# 交付包里绝对不能出现的文件（出包自检用）
FORBIDDEN_SUFFIX = (".java", ".vue", ".py", ".kt", ".gradle", ".map", ".class")
FORBIDDEN_NAME = {
    "pom.xml", "package.json", "package-lock.json", "vite.config.js",
    "settings.xml", "requirements.txt", "docker-compose.yml",
}
FORBIDDEN_PATH_PARTS = ("node_modules", "/.venv/", "/tools/", "/src/main/java",
                        "src/main/java", "/.git/")
FORBIDDEN_FILES = ("-source-1.0.0.zip",)


def version() -> str:
    """从 pom 取版本号；取不到用默认值（失败不该阻断打包）。"""
    try:
        import re
        text = (BACKEND_DIR / "pom.xml").read_text(encoding="utf-8")
        m = re.search(r"<artifactId>case-flow-backend</artifactId>\s*<version>([^<]+)</version>", text)
        if m:
            return m.group(1).replace("-SNAPSHOT", "")
    except OSError:
        pass
    return DEFAULT_VERSION


def slim_jar(src: Path, dst: Path) -> int:
    """重写 jar，剔除敏感条目。返回剔除的条目数。"""
    removed = 0
    with zipfile.ZipFile(src, "r") as zin, \
         zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        for item in zin.infolist():
            if item.filename in DROP_ENTRIES:
                removed += 1
                continue
            zout.writestr(item, zin.read(item.filename))
    return removed


def write_conf(out: Path, mysql_port: int, db_name: str, db_user: str) -> None:
    """外置配置：数据库口令在这里，不在 jar 里。

    两处必须写对，否则服务起不来（实测踩过）：
      1. spring.sql.init.mode: never —— jar 内已剔除 schema.sql，
         不关掉启动时建表，Spring 会抛
         「No schema scripts found at location 'classpath:sql/schema.sql'」直接终止启动。
      2. characterEncoding 必须是 Java 字符集名 UTF-8，不能写 MySQL 的 utf8mb4，
         否则 Connector/J 8 直接抛「Unsupported character encoding」。
    """
    out.mkdir(parents=True, exist_ok=True)
    (out / "application-prod.yml").write_text(f"""# ============================================================
#  CaseFlow 配置文件 —— 部署时只需改下面这一行
# ============================================================

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:{mysql_port}/{db_name}?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_general_ci&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true
    username: {db_user}
    # ★★★ 部署时唯一需要修改的一行：改成您自己的数据库口令 ★★★
    password: 请改成你自己的数据库口令

    hikari:
      pool-name: CaseFlowHikari
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 10000

  # jar 内不放建表脚本，所以关闭「启动时自动建表」。
  # 正式库请用交付包里的「建库脚本/schema.sql」提前建好（只需执行一次）。
  sql:
    init:
      mode: never

caseflow:
  upload-dir: ./data/uploads
  token-expire-hours: 12

logging:
  level:
    root: warn
    com.caseflow: info
    # 想看更详细的日志（排查问题时），把上一行 warn 改成 debug
""", encoding="utf-8")


START_BAT = r"""@echo off
chcp 65001 >nul
title CaseFlow 案件指派系统
cd /d "%~dp0"

rem ====================================================================
rem  双击即可启动。不需要装 Java，不需要敲命令。
rem  逻辑：先检查数据库配置填了没 —— 没填就走「试用模式」（免 MySQL）。
rem ====================================================================

if not exist "logs" mkdir logs
if not exist "data\uploads" mkdir data\uploads

set PORT=8080

rem ---------- 1. 探测数据库口令是否已填 ----------
rem application-prod.yml 里 password: 后还是中文提示 => 没填
findstr /C:"请改成你自己的数据库口令" "config\application-prod.yml" >nul 2>&1
if %errorlevel%==0 (
    goto TRIAL
)
goto PROD

rem ====================================================================
:TRIAL
rem ====================================================================
echo.
echo   ============================================
echo     CaseFlow 案件指派系统
echo   ============================================
echo.
echo   检测到还没配置正式数据库，本次先用【试用模式】启动。
echo.
echo     * 不需要安装 MySQL
echo     * 数据存在 data\h2 目录，可正常使用
echo     * 适合：先试用、单机少量使用
echo.
echo   如果这台电脑要正式使用（多人同时用），
echo   请看【部署说明.md】第二节配置 MySQL。
echo.
echo   正在启动，请稍候 30 秒...
echo.

rem 试用模式端口换成 8099，避免和正式服务抢 8080
set PORT=8099
start "CaseFlow 服务" /D "%~dp0" runtime\bin\java.exe -jar case-flow-backend.jar ^
  --spring.config.additional-location=file:./config/ ^
  --spring.profiles.active=trial ^
  --server.port=8099 ^
  --server.address=0.0.0.0 ^
  --caseflow.upload-dir=./data/uploads > logs\console.log 2>&1

call :waitready 8099
goto REPORT

rem ====================================================================
:PROD
rem ====================================================================
echo.
echo   ============================================
echo     CaseFlow 案件指派系统  正在启动
echo   ============================================
echo.
echo   正在启动，请稍候 30 秒...
echo.

rem ---------- 2. 端口占用检查 ----------
netstat -ano | findstr ":%PORT%" | findstr "LISTENING" >nul 2>&1
if %errorlevel%==0 (
    echo   [!] 端口 %PORT% 已被占用。
    echo.
    echo   常见原因：CaseFlow 已经启动过了。
    echo   请双击【停止.bat】后再启动。
    echo.
    pause
    exit /b 1
)

start "CaseFlow 服务" /D "%~dp0" runtime\bin\java.exe -jar case-flow-backend.jar ^
  --spring.config.additional-location=file:./config/ ^
  --spring.profiles.active=prod ^
  --server.port=%PORT% ^
  --server.address=0.0.0.0 ^
  --caseflow.upload-dir=./data/uploads > logs\console.log 2>&1

call :waitready %PORT%

rem ====================================================================
:REPORT
rem ====================================================================
echo.
echo   ============================================
echo     启动成功！
echo   ============================================
echo.
echo   【本机打开这个网址】
echo       http://localhost:%PORT%/api/
echo.
echo   【同事访问】把下面地址发给他们：
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
    echo       http://%%a:%PORT%/api/
)
echo.
echo   【登录账号】
echo       账号  boss
echo       密码  admin123
echo       ^（登录后请立刻到「账号管理」改密码^）
echo.
echo   --------------------------------------------------------
echo   * 这个窗口不要关闭，关掉服务就停了
echo   * 想停止：直接关窗口，或双击【停止.bat】
echo   * 同事打不开：以管理员身份双击【放行防火墙.bat】
echo   * 出问题：看 logs\console.log 最后几行
echo   --------------------------------------------------------
echo.
echo   按任意键关闭窗口并停止服务...
pause >nul

rem 关窗口即停服务（连带上面的 java 窗口）
taskkill /F /IM java.exe >nul 2>&1
exit /b 0

rem ====================================================================
rem  等待端口就绪；超时则提示排查方向
rem ====================================================================
:waitready
set /a tries=0
:loop
timeout /t 2 /nobreak >nul
netstat -ano | findstr ":%~1" | findstr "LISTENING" >nul 2>&1
if %errorlevel%==1 (
    set /a tries+=1
    if %tries% lss 60 goto loop
    echo.
    echo   [!] 启动超时，服务没能起来。
    echo.
    echo   最常见的三个原因：
    echo     1. 数据库口令填错了
    echo        → 记事本打开 config\application-prod.yml 检查 password 那行
    echo     2. MySQL 没启动
    echo        → Windows 里搜"服务"→ 找到 MySQL → 右键"启动"
    echo     3. 数据库里还没建表
    echo        → 执行 建库脚本\schema.sql
    echo.
    echo   详细报错在 logs\console.log 最后几行，可以发给技术支持。
    echo.
    pause
    exit /b 1
)
exit /b 0
"""

FIREWALL_BAT = r"""@echo off
chcp 65001 >nul
title CaseFlow - 放行防火墙
cd /d "%~dp0"

echo.
echo   正在尝试放行 8080 端口...
echo.
netsh advfirewall firewall add rule name="CaseFlow 8080" dir=in action=allow protocol=TCP localport=8080 2>nul
netsh advfirewall firewall add rule name="CaseFlow 8080" dir=out action=allow protocol=TCP localport=8080 2>nul

echo.
echo   ============================================
echo     已尝试放行 8080 端口
echo   ============================================
echo.
echo   如果上面提示"请求的操作需要提升"，说明这个窗口不是
echo   以管理员身份运行的。请右键「放行防火墙.bat」
echo   →【以管理员身份运行】，再试一次。
echo.
echo   另外请确认网络类型是"专用网络"或"工作网络"：
echo   设置 → 网络和 Internet → 属性 → 把网络类型改成"专用网络"。
echo.
pause
"""

STOP_BAT = r"""@echo off
chcp 65001 >nul
title CaseFlow - 停止服务
cd /d "%~dp0"

echo.
echo   正在停止 CaseFlow ...

rem 只杀真正 LISTENING 在 8080 的进程（按 LISTENING 过滤，避免误杀其它占用同端口的连接）
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do (
    echo     正在结束进程 %%p
    taskkill /F /PID %%p >nul 2>&1
)

echo.
echo   ============================================
echo     已停止
echo   ============================================
echo.
pause
"""


def lan_hint() -> str:
    """探测本机局域网 IP（仅用于生成提示文字，失败就用占位符）。"""
    try:
        import socket
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "本机IP"


# 部署说明正文放在 docs/交付包-部署说明.md（独立文件，便于单独校对与改版）
DEPLOY_README_SRC = DOCS / "交付包-部署说明.md"


def deploy_readme_text() -> str:
    if DEPLOY_README_SRC.exists():
        return DEPLOY_README_SRC.read_text(encoding="utf-8")
    raise SystemExit(f"找不到部署说明模板：{DEPLOY_README_SRC}")


def build(skip_build: bool) -> bool:
    if not skip_build:
        print("[1/5] 干净构建（先删旧产物，避免历史残留混进 jar）...")
        # 关键：必须 clean。之前 target/classes/static 里堆了 672 个历次构建的
        # 旧 chunk（643 个是 dist 里早已不存在的），里面含已删除功能的旧代码，
        # 不 clean 就会跟着 jar 一起交付，等于白白泄露历史实现。
        shutil.rmtree(BACKEND_DIR / "target", ignore_errors=True)
        shutil.rmtree(FRONTEND_DIR / "dist", ignore_errors=True)
        r = subprocess.run([sys.executable, str(Path(__file__).parent / "package_release.py")],
                           cwd=str(ROOT))
        if r.returncode != 0:
            print("  构建失败，已中止")
            return False
    elif not JAR.exists():
        print(f"没找到 {JAR}，请先不带 --skip-build 跑一次")
        return False

    if not JAR.exists():
        print(f"没找到 {JAR}")
        return False
    return True


def stage(no_jre: bool, mysql_port: int, db_name: str, db_user: str) -> Path:
    print("[2/5] 组装交付目录 ...")
    if STAGE_DIR.exists():
        shutil.rmtree(STAGE_DIR)
    pkg = STAGE_DIR / f"CaseFlow-{version()}"
    pkg.mkdir(parents=True)

    # jar 瘦身
    slimmed = pkg / "case-flow-backend.jar"
    n = slim_jar(JAR, slimmed)
    print(f"  jar 剔除敏感条目 {n} 项（H2 控制台配置 / 建表脚本）")

    # 便携 JRE
    if no_jre:
        print("  跳过便携 JRE（--no-jre，目标机需自备 Java 8）")
    elif JRE_SRC.exists():
        print("  复制便携 Java 运行时（约 60 MB）...")
        shutil.copytree(JRE_SRC, pkg / "runtime", dirs_exist_ok=True)
    else:
        print(f"  ⚠ 未找到 {JRE_SRC}，未打入 JRE；目标机需自备 Java 8")

    write_conf(pkg / "config", mysql_port, db_name, db_user)
    (pkg / "启动.bat").write_text(START_BAT, encoding="utf-8")
    (pkg / "停止.bat").write_text(STOP_BAT, encoding="utf-8")
    (pkg / "放行防火墙.bat").write_text(FIREWALL_BAT, encoding="utf-8")
    (pkg / "部署说明.md").write_text(deploy_readme_text(), encoding="utf-8")

    # 建表脚本单独放（部署时由 DBA 执行一次，不进 jar）
    sql_out = pkg / "建库脚本"
    sql_out.mkdir()
    for f in ("schema.sql", "index-mysql.sql"):
        src = ROOT / "sql" / f
        if src.exists():
            shutil.copy2(src, sql_out / f)

    # 使用教程（图文版，面向四五十岁用户）
    shots_src = DOCS / "shots"
    if shots_src.exists():
        user_dir = pkg / "使用教程"
        user_dir.mkdir(parents=True, exist_ok=True)
        manual = DOCS / "用户使用手册.md"
        if manual.exists():
            shutil.copy2(manual, user_dir / "使用教程.md")
        shots = list(shots_src.glob("*.png"))
        if shots:
            shutil.copytree(shots_src, user_dir / "shots")
        print(f"  附使用教程 + {len(shots)} 张界面截图")

    # zip 格式不保存空目录 —— 空目录解压后会消失，必须放占位文件，
    # 否则 data/uploads 不存在会导致附件上传失败、logs 不存在导致重定向失败。
    (pkg / "logs").mkdir(parents=True, exist_ok=True)
    (pkg / "logs" / "说明.txt").write_text(
        "这个目录存放运行日志和排错信息。\n"
        "出问题时把 console.log 里的报错发给技术支持。\n", encoding="utf-8")
    (pkg / "data").mkdir(parents=True, exist_ok=True)
    (pkg / "data" / "说明.txt").write_text(
        "这个目录存放业务数据，**必须定期备份**。\n"
        "  uploads\\  案件原始材料等附件\n"
        "  h2\\      仅试用模式才有\n"
        "漏了这里的文件无法重建。\n", encoding="utf-8")
    (pkg / "data" / "uploads").mkdir(parents=True, exist_ok=True)
    (pkg / "data" / "uploads" / "说明.txt").write_text(
        "案件上传的附件会存在这里，请随上级目录一起备份。\n", encoding="utf-8")

    # 零配置试用配置：没有 MySQL 的电脑也能立刻先跑起来看界面
    (pkg / "config" / "application-trial.yml").write_text("""# ============================================================
#  试用模式 —— 不需要安装 MySQL，适合先试用、单机小规模使用
#  数据库就在 data/h2/ 目录，备份时直接复制该目录
#  正式使用（多人同时用、几十个案件以上）请改用 application-prod.yml
# ============================================================
spring:
  datasource:
    driver-class-name: org.h2.Driver
    url: jdbc:h2:file:./data/h2/caseflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1
    username: sa
    password: ''
  h2:
    console:
      enabled: false          # 生产环境一律关闭，防止绕过登录访问数据库
  sql:
    init:
      mode: always
      schema-locations: file:./建库脚本/schema.sql
      continue-on-error: false

caseflow:
  upload-dir: ./data/uploads
  token-expire-hours: 12

logging:
  level:
    root: warn
    com.caseflow: info
""", encoding="utf-8")

    # 便捷：把访问地址直接写在包外层，省得翻文档
    (pkg / "★ 先看我.txt").write_text(
        f"""════════════════════════════════════════════
  CaseFlow 案件指派系统 —— 三步开始使用
════════════════════════════════════════════

第 1 步　双击【启动.bat】，等 30 秒

第 2 步　浏览器打开：http://localhost:8080/api/

第 3 步　用下面的账号登录
            账号　boss
            密码　admin123
            ^ 登录后请立刻到「账号管理」改密码

────────────────────────────────────────────
  让同事也能访问
────────────────────────────────────────────
  把这个地址发给他们：
      http://{lan_hint()}:8080/api/

  打不开的话，以管理员身份双击【放行防火墙.bat】。

────────────────────────────────────────────
  想换台电脑 / 换端口 / 排错
────────────────────────────────────────────
  看【部署说明.md】—— 里面写得很细，不用问我

  遇到问题先看：logs\\console.log 最后几行

────────────────────────────────────────────
  重要
────────────────────────────────────────────
  * 启动后那个黑色窗口不能关，关了服务就停了
  * data 目录必须定期备份，丢了无法重建
  * data\\uploads 是案件材料附件，同样要备份
""", encoding="utf-8")
    return pkg


def audit(pkg: Path) -> list:
    """自检交付目录里有没有不该有的东西。返回问题列表。"""
    bad = []
    for p in pkg.rglob("*"):
        if not p.is_file():
            continue
        rel = "/" + p.relative_to(pkg).as_posix()
        name = p.name
        if name in FORBIDDEN_NAME:
            bad.append(f"{rel}（源码构建文件）")
        elif name.endswith(FORBIDDEN_SUFFIX):
            bad.append(f"{rel}（源码文件）")
        elif any(seg in rel for seg in FORBIDDEN_PATH_PARTS):
            bad.append(f"{rel}（不应交付的目录）")
        elif p.suffix == ".db":
            bad.append(f"{rel}（数据库文件，含演示数据）")
        elif any(f in name for f in FORBIDDEN_FILES):
            bad.append(f"{rel}（源码压缩包）")
    return bad


def make_zip(pkg: Path) -> Path:
    print("[3/5] 压缩 ...")
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    zpath = RELEASE_DIR / f"caseflow-deploy-{version()}-{stamp}.zip"
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for f in sorted(pkg.rglob("*")):
            if f.is_file():
                zf.write(f, str(Path(pkg.name) / f.relative_to(pkg)))
    return zpath


# ---------------------------------------------------------------------------
# 升级补丁包（内网更新专用）
# ---------------------------------------------------------------------------
# 为什么单独出这个包：完整包里有 config/application-prod.yml（口令占位）、
# 空的 data/、建库脚本。操作人一旦「解压全部覆盖」，口令被打回占位、库里被塞进
# 空占位文件，表现就是「升级后连不上库、数据像丢了」。补丁包里只有 jar，
# 从物理上断掉这种误操作。
PATCH_TOP = "CaseFlow-升级补丁"

UPGRADE_TXT = """\
════════════════════════════════════════════
  CaseFlow 升级补丁 —— 只换程序，不动数据
════════════════════════════════════════════

本包里只需要操作一样东西：

    case-flow-backend.jar    ← 程序本体，覆盖旧的就行

其余内容都是备用/排错用的，正常升级**不用动**：
    建库语句\\      新装的机器才用；升级不要执行
    升级说明.txt   就是这份

────────────────────────────────────────────
  四步升级
────────────────────────────────────────────
第 1 步　备份
      正式模式（MySQL）：
          mysqldump -u caseflow -p case_flow > D:\\backup\\升级前.sql
      试用模式（H2 单文件库）：
          把整个 data 目录复制一份（数据库就在 data\\h2 里）
      另外 data\\uploads（案件附件）一起备份

第 2 步　在旧目录里双击【停止.bat】

第 3 步　把本包的 case-flow-backend.jar 覆盖到旧目录
      ⚠ 只覆盖这一个文件
      ⚠ 不要覆盖 config\\    （里面是你们的数据库口令）
      ⚠ 不要覆盖 data\\      （里面是全部业务数据与附件）
      ⚠ 不要覆盖 建库脚本\\  （升级不需要执行任何 SQL）
      ⚠ 试用模式必须原地覆盖，别解压到新目录——
        解压到新目录会建一个空库，看起来像"数据全丢了"

第 4 步　双击【启动.bat】，等 30 秒，打开系统

────────────────────────────────────────────
  你们最关心的两个问题
────────────────────────────────────────────
问：数据结构会不会被改？
答：只会「加」。启动时自动给老表补上新增的列（可空），
    不会删表、不会删列、不会改列类型、不会改表名。

问：已经录进去的数据会不会被改掉？
答：不会。案件、嫌疑人、附件、待办、领导意见、审批记录一条都不动。
    程序只做两类事：
      1) 给空着的单元格补值（已有值绝不覆盖）
      2) 把老的单期限转成「重要时间节点」、把老的落实说明拆成
         时间/平台/操作三列（原来那三列保留，界面照样显示完整原句）
    历史上「会覆盖用户已填值」的整理，已加一次性台账闸门
    （schema_migration_mark）：一个库一辈子只跑一次，之后每次启动直接跳过。

────────────────────────────────────────────
  升级后自检（30 秒）
────────────────────────────────────────────
1. 能登录，案件件数与升级前一致
2. 随便点开一个案件：待办、领导意见、附件都在
3. 打开 logs\\console.log 搜 ERROR：没有就正常
   （看到「[SchemaMigration] 已补列：xxx」属正常，说明新列补上了）

────────────────────────────────────────────
  出问题怎么回退
────────────────────────────────────────────
停服 → 换回旧 jar → 启动即可。
新增的列留着不影响旧版本（旧版本只是不看它们），
多数情况换回 jar 就能跑；要彻底回到升级前，用第 1 步的备份恢复数据库。
"""


def stage_patch() -> Path:
    """组装升级补丁目录：新 jar + 升级说明 + 建库语句（备用）。"""
    print("[2/5] 组装升级补丁目录 ...")
    if STAGE_DIR.exists():
        shutil.rmtree(STAGE_DIR)
    pkg = STAGE_DIR / PATCH_TOP
    pkg.mkdir(parents=True)

    n = slim_jar(JAR, pkg / "case-flow-backend.jar")
    print(f"  jar 剔除敏感条目 {n} 项（H2 控制台配置 / 建表脚本）")
    (pkg / "升级说明.txt").write_text(UPGRADE_TXT, encoding="utf-8")

    sql_out = pkg / "建库语句"
    sql_out.mkdir()
    for f in ("schema.sql", "index-mysql.sql"):
        src = ROOT / "sql" / f
        if src.exists():
            shutil.copy2(src, sql_out / f)
    print("  附建库语句（仅新装/排错用，升级不执行）")
    return pkg


def make_patch_zip(pkg: Path) -> Path:
    print("[3/5] 压缩 ...")
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    zpath = RELEASE_DIR / f"{PATCH_TOP}-{stamp}.zip"
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for f in sorted(pkg.rglob("*")):
            if f.is_file():
                zf.write(f, str(Path(pkg.name) / f.relative_to(pkg)))
    return zpath


def purge_source_zip() -> int:
    """删除 release 下的源码包：从源头断掉误发可能。"""
    n = 0
    for f in RELEASE_DIR.glob("*-source-*.zip"):
        f.unlink()
        print(f"  已删除源码包：{f.name}")
        n += 1
    return n


def verify_jar(pkg: Path) -> None:
    """校验交付包内的 jar 确实已剔除敏感项。"""
    print("[4/5] 校验 jar 敏感项 ...")
    jp = pkg / "case-flow-backend.jar"
    with zipfile.ZipFile(jp) as zf:
        names = set(zf.namelist())
    problems = []
    for k in ("application-dev.yml", "sql/schema.sql", "sql/index-mysql.sql"):
        if any(k in n for n in names):
            problems.append(k)
    if problems:
        print("  ✗ jar 内仍存在敏感条目：" + ", ".join(problems))
        raise SystemExit(1)
    # 顺带统计历史残留（dist 之外的旧 chunk）
    n_js = sum(1 for n in names if n.endswith(".js"))
    print(f"  ✔ jar 内无 H2 控制台配置、无建表脚本（前端 js {n_js} 个）")


def main() -> int:
    ap = argparse.ArgumentParser(description="打包交付包（全新电脑解压即用，不含源码）")
    ap.add_argument("--skip-build", action="store_true", help="复用已有 jar，不重新构建")
    ap.add_argument("--no-jre", action="store_true", help="不打入便携 Java（目标机自备 Java 8）")
    ap.add_argument("--port", type=int, default=3306, help="配置里预填的 MySQL 端口")
    ap.add_argument("--db", default="case_flow", help="配置里预填的数据库名")
    ap.add_argument("--db-user", default="caseflow", help="配置里预填的数据库账号")
    ap.add_argument("--keep-stage", action="store_true", help="保留组装目录，便于检查")
    ap.add_argument("--patch-only", action="store_true",
                    help="只出升级补丁包（内网更新用：只有 jar + 升级说明，避免误覆盖 config/data）")
    args = ap.parse_args()

    print("=" * 58)
    if args.patch_only:
        print(" CaseFlow 升级补丁打包（只换程序 · 不含数据与配置）")
    else:
        print(" CaseFlow 交付包打包（全新电脑解压即用 · 不含源码）")
    print("=" * 58)

    if not build(args.skip_build):
        return 1

    if args.patch_only:
        pkg = stage_patch()
        verify_jar(pkg)
        print("[5/5] 自检补丁目录 ...")
        bad = audit(pkg)
        if bad:
            print("  ✗ 发现不应交付的内容：")
            for b in bad[:20]:
                print("    -", b)
            return 1
        print("  ✔ 自检通过：只有 jar / 升级说明 / 建库语句")
        zpath = make_patch_zip(pkg)
        if not args.keep_stage:
            shutil.rmtree(STAGE_DIR, ignore_errors=True)
        size_mb = zpath.stat().st_size / 1024 / 1024
        print("\n" + "=" * 58)
        print(f" 完成：{zpath.name}（{size_mb:.1f} MB）")
        print("=" * 58)
        print(" 内网更新只需传这一个包：")
        print("   停服 → 备份 data 与数据库 → 只覆盖 case-flow-backend.jar → 启动")
        print("   不要覆盖 config\\ 、data\\ 、建库脚本\\（详见包内 升级说明.txt）")
        return 0

    pkg = stage(args.no_jre, args.port, args.db, args.db_user)
    verify_jar(pkg)

    print("[5/5] 自检交付目录 ...")
    bad = audit(pkg)
    if bad:
        print("  ✗ 发现不应交付的内容：")
        for b in bad[:20]:
            print("    -", b)
        return 1
    print("  ✔ 自检通过：无源码 / 无开发环境 / 无数据库文件")

    zpath = make_zip(pkg)
    purged = purge_source_zip()

    if not args.keep_stage:
        shutil.rmtree(STAGE_DIR, ignore_errors=True)

    size_mb = zpath.stat().st_size / 1024 / 1024
    print("\n" + "=" * 58)
    print(f" 完成：{zpath.name}（{size_mb:.1f} MB）")
    print("=" * 58)
    print(" 交付包内含：")
    print("   启动.bat / 停止.bat / 放行防火墙.bat　← 平时只用启动.bat")
    print("   部署说明.md　　　　　　　　　　　　　← 部署的人看这份")
    print("   使用教程/ + 界面截图　　　　　　　　　← 用的人看这份")
    print("   config/（填数据库口令）/ 建库脚本/ / runtime/ / data/ / logs/")
    print()
    print(" 目标机三步：解压 → 填 config 里的数据库口令 → 双击 启动.bat")
    if purged:
        print(f" 另外清理了 {purged} 个源码包，避免误发")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())