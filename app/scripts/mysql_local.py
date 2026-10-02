"""本地 MySQL 8 全生命周期管理（免安装版，全部落在项目 .tools 下）。

不注册 Windows 服务、不需要管理员权限、不写系统目录；删掉 .tools 即彻底卸载。

用法：
    python scripts/mysql_local.py init      # 首次：初始化数据目录 -> 启动 -> 建库建表建索引建账号
    python scripts/mysql_local.py start     # 启动 mysqld
    python scripts/mysql_local.py stop      # 关闭 mysqld
    python scripts/mysql_local.py status    # 查看运行状态
    python scripts/mysql_local.py setup     # 只重跑建库建表（幂等）
    python scripts/mysql_local.py env       # 打印后端连接本库所需的环境变量
    python scripts/mysql_local.py reset     # 清空数据目录重新初始化（会删数据）
"""
import argparse
import os
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

import pymysql

from _common import BACKEND_DIR, SQL_DIR, TOOLS_DIR, schema_sql, split_statements

# ── 重要：MySQL 实例必须放在纯 ASCII 路径下 ───────────────────────────────
# 本机实测：项目路径含中文（D:\案件指派demo）时，Windows 版 mysqld 会把 argv[0]
# 与选项里的路径在第一个非 ASCII 字符处**截断**——日志里表现为
#   "D:\ (mysqld 8.0.28) starting as process ..."
#   "Failed to set datadir to 'D:\data\'"
# 随即退出。诡异之处在于 --initialize 能过、--verbose --help 也能正常解析出路径，
# 只有正常启动必挂；改命令行传参、改选项文件编码（UTF-8 / GBK）均无效。
# 因此实例整体放在 ASCII 目录，路径可用环境变量 CF_MYSQL_ROOT 覆盖。
MYSQL_ROOT = Path(os.environ.get("CF_MYSQL_ROOT", r"D:\caseflow-db"))
MYSQL_HOME = MYSQL_ROOT / "mysql-8.0.28-winx64"
MYSQL_DATA = MYSQL_ROOT / "mysql-data"
MY_INI = MYSQL_ROOT / "mysql-my.ini"
ZIP = TOOLS_DIR / "mysql-8.0.28-winx64.zip"
HOST = "127.0.0.1"
PORT = 3306
DB = "case_flow"
ROOT_USER, ROOT_PWD = "root", "root"
APP_USER, APP_PWD = "caseflow", "caseflow"

# 与 application-mysql.yml 的默认值保持一致
DEFAULTS = {
    "SPRING_PROFILES_ACTIVE": "mysql",
    "MYSQL_HOST": HOST,
    "MYSQL_PORT": str(PORT),
    "MYSQL_DB": DB,
    "MYSQL_USER": APP_USER,
    "MYSQL_PASSWORD": APP_PWD,
}


# --------------------------------------------------------------------------- #
# 基础工具
# --------------------------------------------------------------------------- #
def exe(name):
    p = MYSQL_HOME / "bin" / (name + (".exe" if os.name == "nt" else ""))
    if not p.exists():
        raise SystemExit(f"未找到 {p}，请先执行 scripts/mysql_local.py init")
    return str(p)


def port_open(port=PORT):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.6)
        return s.connect_ex((HOST, int(port))) == 0


def write_ini():
    """写 my.ini。

    Windows 版 mysqld 默认按**系统 ANSI 代码页**解码选项文件；配合上面的
    「路径必须纯 ASCII」约束，这里同时做两件事：内容保持 ASCII、写入用 GBK
    优先（与系统 ANSI 一致），避免中文注释/路径带来的解码歧义。
    """
    text = f"""[mysqld]
basedir={MYSQL_HOME.as_posix()}
datadir={MYSQL_DATA.as_posix()}
port={PORT}
bind-address={HOST}
character-set-server=utf8mb4
collation-server=utf8mb4_general_ci
# 本地开发实例固定用 native_password，避免旧客户端/JDBC 的公钥交换麻烦
default_authentication_plugin=mysql_native_password
default-time-zone=+08:00
lc-messages-dir={MYSQL_HOME.as_posix()}/share
max_connections=200
innodb_buffer_pool_size=256M
# 注意：不要开 skip-name-resolve——开启后 127.0.0.1 不会被解析成 localhost，
# 会直接报 "Host '127.0.0.1' is not allowed to connect"，root@localhost 连不上。
log-error={MYSQL_DATA.as_posix()}/error.log
pid-file={MYSQL_DATA.as_posix()}/mysqld.pid

[client]
port={PORT}
default-character-set=utf8mb4
"""
    for enc in ("gbk", "utf-8"):
        try:
            MY_INI.write_text(text, encoding=enc)
            return
        except UnicodeEncodeError:
            continue


def mysqld(args, detached=False):
    cmd = [exe("mysqld"), f"--defaults-file={MY_INI}", *args]
    flags = 0
    if detached and os.name == "nt":
        flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    return subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            creationflags=flags)


def mysql_admin(args):
    return subprocess.run([exe("mysqladmin"), f"--defaults-file={MY_INI}", *args],
                          capture_output=True, text=True, errors="ignore")


def connect(user, password, db=None):
    kw = dict(host=HOST, port=PORT, user=user, password=password,
              charset="utf8mb4", autocommit=True)
    if db:
        kw["database"] = db
    return pymysql.connect(**kw)


def err_tail(n=12):
    log = MYSQL_DATA / "error.log"
    if not log.exists():
        return "(无 error.log)"
    lines = log.read_text(encoding="utf-8", errors="ignore").splitlines()
    return "\n".join("    " + l for l in lines[-n:])


def wait_ready(timeout=90):
    """等 mysqld 可连接，返回版本号；root 密码可能还没设，故两种都试。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for pwd in (ROOT_PWD, ""):
            try:
                with connect(ROOT_USER, pwd) as c, c.cursor() as cur:
                    cur.execute("SELECT VERSION()")
                    return cur.fetchone()[0]
            except Exception:
                continue
        time.sleep(1.5)
    return None


def root_pwd():
    """返回当前可用的 root 密码（已设密码优先，否则空）。"""
    for pwd in (ROOT_PWD, ""):
        try:
            with connect(ROOT_USER, pwd) as c, c.cursor() as cur:
                cur.execute("SELECT 1")
            return pwd
        except Exception:
            continue
    raise SystemExit("无法用 root 连接本地 MySQL，请检查实例状态：\n" + err_tail())


# --------------------------------------------------------------------------- #
# 子命令
# --------------------------------------------------------------------------- #
def cmd_init(args):
    if not MYSQL_HOME.exists():
        if ZIP.exists():
            raise SystemExit(f"请先解压 {ZIP} 到 {TOOLS_DIR}，使 {MYSQL_HOME} 存在")
        raise SystemExit(f"未找到 {MYSQL_HOME}，请先下载并解压 MySQL 免安装包到 {TOOLS_DIR}")

    if not MYSQL_DATA.exists() or not (MYSQL_DATA / "mysql").exists():
        MYSQL_DATA.mkdir(parents=True, exist_ok=True)
        write_ini()
        print(f"[1/4] 初始化数据目录 {MYSQL_DATA}（首次约需 10-30 秒）...")
        p = subprocess.run([exe("mysqld"), f"--defaults-file={MY_INI}", "--initialize-insecure"],
                           capture_output=True, text=True, errors="ignore")
        if p.returncode != 0:
            print(p.stdout[-2000:])
            raise SystemExit("初始化失败，日志末尾：\n" + err_tail())
    else:
        write_ini()
        print(f"[1/4] 数据目录已存在，跳过初始化：{MYSQL_DATA}")

    print("[2/4] 启动 mysqld ...")
    cmd_start(args)

    print("[3/4] 建库 / 建表 / 建索引 / 建账号 ...")
    setup_schema()

    print("[4/4] 完成。后端连接本库所需环境变量：")
    for k, v in DEFAULTS.items():
        print(f"    {k}={v}")
    print("\n下一步：python scripts/dev.py --profile mysql --seed")


def cmd_start(args):
    version_probe = None
    if port_open():
        try:
            version_probe = wait_ready(3)
        except Exception:
            version_probe = None
        if version_probe:
            print(f"mysqld 已在运行（{HOST}:{PORT}）")
            return
        raise SystemExit(f"端口 {PORT} 被其他进程占用，且不是本项目的 MySQL 实例")

    if not (MYSQL_DATA / "mysql").exists():
        raise SystemExit("数据目录未初始化，请先执行：python scripts/mysql_local.py init")

    write_ini()
    mysqld([], detached=True)
    v = wait_ready(90)
    if not v:
        raise SystemExit(f"mysqld 启动失败，日志末尾：\n{err_tail(20)}")
    print(f"mysqld 已启动：{HOST}:{PORT}（MySQL {v}）")


def cmd_stop(args):
    if not port_open():
        print("mysqld 未在运行")
        return
    pwd = root_pwd()
    cred = [f"-u{ROOT_USER}"] + ([f"-p{pwd}"] if pwd else [])
    mysql_admin([*cred, "shutdown"])
    for _ in range(30):
        if not port_open():
            print("mysqld 已关闭")
            return
        time.sleep(1)
    raise SystemExit("关闭超时，可手动结束进程：taskkill /F /IM mysqld.exe")


def cmd_status(args):
    print(f"安装目录：{MYSQL_HOME}  {'存在' if MYSQL_HOME.exists() else '不存在'}")
    print(f"数据目录：{MYSQL_DATA}  {'存在' if (MYSQL_DATA / 'mysql').exists() else '未初始化'}")
    if not port_open():
        print(f"运行状态：未运行（{HOST}:{PORT}）")
        return
    pwd = root_pwd()
    with connect(ROOT_USER, pwd) as c, c.cursor() as cur:
        cur.execute("SELECT VERSION(), @@datadir, @@port")
        ver, datadir, port = cur.fetchone()
        print(f"运行状态：运行中（{HOST}:{port}，MySQL {ver}）")
        print(f"实际数据目录：{datadir}")
        cur.execute("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s", (DB,))
        if not cur.fetchone()[0]:
            print(f"数据库 {DB}：尚未创建")
            return
        cur.execute("USE `%s`" % DB)
        cur.execute("SELECT table_name, table_rows FROM information_schema.TABLES "
                    "WHERE TABLE_SCHEMA=%s ORDER BY table_name", (DB,))
        rows = cur.fetchall()
        print(f"数据库 {DB}：{len(rows)} 张表")
        for t, n in rows:
            cur.execute(f"SELECT COUNT(*) FROM `{t}`")
            print(f"    {t:<16} {cur.fetchone()[0]:>6} 行")


def setup_schema():
    root = root_pwd()
    conn = connect(ROOT_USER, root)
    try:
        with conn.cursor() as cur:
            cur.execute(f"CREATE DATABASE IF NOT EXISTS `{DB}` "
                        f"DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
            print(f"      数据库 `{DB}` 就绪")
            cur.execute(f"USE `{DB}`")

            n = 0
            for stmt in split_statements(schema_sql()):
                cur.execute(stmt)
                n += 1
            print(f"      建表 {n} 条 DDL 执行完毕")

            idx_sql = (SQL_DIR / "index-mysql.sql").read_text(encoding="utf-8")
            ok = 0
            for stmt in split_statements(idx_sql):
                try:
                    cur.execute(stmt)
                    ok += 1
                except Exception as e:
                    if "Duplicate" in str(e):
                        continue
                    print(f"      跳过索引：{e}")
            print(f"      索引 {ok} 条就绪")

            # 应用专用账号：只授予本库权限，避免后端用 root 连库
            for h in ("localhost", "127.0.0.1"):
                cur.execute(f"CREATE USER IF NOT EXISTS '{APP_USER}'@'{h}' "
                            f"IDENTIFIED WITH mysql_native_password BY '{APP_PWD}'")
                cur.execute(f"ALTER USER '{APP_USER}'@'{h}' "
                            f"IDENTIFIED WITH mysql_native_password BY '{APP_PWD}'")
            cur.execute(f"GRANT ALL PRIVILEGES ON `{DB}`.* TO '{APP_USER}'@'localhost'")
            cur.execute(f"GRANT ALL PRIVILEGES ON `{DB}`.* TO '{APP_USER}'@'127.0.0.1'")
            cur.execute("FLUSH PRIVILEGES")
            print(f"      账号 {APP_USER}/{APP_PWD} 已就绪（仅 {DB}.* 权限）")

            cur.execute("SELECT COUNT(*) FROM sys_user")
            if cur.fetchone()[0] == 0:
                cur.execute("INSERT INTO sys_user (username, password, display_name, role, status) "
                            "VALUES ('boss', 'admin123', '王总（大队长）', 'BOSS', 1)")
                print("      默认账号已建：boss / admin123")
            else:
                print("      sys_user 已有数据，跳过默认账号")
    finally:
        conn.close()

    if root == "":
        with connect(ROOT_USER, "") as c, c.cursor() as cur:
            cur.execute(f"ALTER USER '{ROOT_USER}'@'localhost' "
                        f"IDENTIFIED WITH mysql_native_password BY '{ROOT_PWD}'")
        print(f"      root 密码已设为 {ROOT_PWD}（仅本机 127.0.0.1 可连）")


def cmd_setup(args):
    if not port_open():
        raise SystemExit("mysqld 未运行，请先执行：python scripts/mysql_local.py start")
    setup_schema()


def cmd_env(args):
    print("# 在启动后端的终端里设置这些变量（或直接用 scripts/dev.py --profile mysql）")
    for k, v in DEFAULTS.items():
        print(f"set {k}={v}" if os.name == "nt" else f"export {k}={v}")


def cmd_reset(args):
    if port_open():
        cmd_stop(args)
    if not MYSQL_DATA.exists():
        print("数据目录不存在，无需清理")
        return
    confirm = args.yes or input(f"将删除 {MYSQL_DATA} 下所有数据，确认？(yes/N) ")
    if str(confirm).lower() not in ("y", "yes"):
        print("已取消")
        return
    shutil.rmtree(MYSQL_DATA, ignore_errors=True)
    print("数据目录已清空，请重新执行：python scripts/mysql_local.py init")


def main():
    ap = argparse.ArgumentParser(description="本地 MySQL 8 管理（免安装版）")
    ap.add_argument("cmd", choices=["init", "start", "stop", "status", "setup", "env", "reset"])
    ap.add_argument("--yes", action="store_true", help="reset 时跳过确认")
    args = ap.parse_args()
    {"init": cmd_init, "start": cmd_start, "stop": cmd_stop, "status": cmd_status,
     "setup": cmd_setup, "env": cmd_env, "reset": cmd_reset}[args.cmd](args)


if __name__ == "__main__":
    main()
