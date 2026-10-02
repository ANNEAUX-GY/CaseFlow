"""开发环境启动器：按需拉起后端(Spring Boot) / 前端(Vite) / 依赖修复。

用法（也可直接在 PyCharm 里选同名运行配置，无需手敲命令）：
    .venv\\Scripts\\python.exe scripts/dev.py                  # 后端 + 前端（H2 内置库）
    .venv\\Scripts\\python.exe scripts/dev.py --profile mysql   # 本地 MySQL（会自动拉起 mysqld）
    .venv\\Scripts\\python.exe scripts/dev.py --no-frontend     # 只启动后端
    .venv\\Scripts\\python.exe scripts/dev.py --no-backend      # 只启动前端
    .venv\\Scripts\\python.exe scripts/dev.py --seed            # 启动后自动灌入演示数据
    .venv\\Scripts\\python.exe scripts/dev.py --check           # 只做环境自检，不启动服务
    .venv\\Scripts\\python.exe scripts/dev.py --fix-deps        # 修复 Python / 前端依赖

本脚本刻意**只依赖 Python 标准库**：它是「启动器」，用户往往在依赖还没装好、
甚至 .venv 都不存在时就要跑它。让它依赖第三方包会把「起不来」和「依赖没装」
耦合成同一个失败。改这个文件时请勿引入 requests / pymysql 等第三方包。
"""
import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

from _common import BACKEND_DIR, FRONTEND_DIR, ROOT, TOOLS_DIR, venv_python

MAVEN_HOME = TOOLS_DIR / "maven"
REPO = TOOLS_DIR / "m2repo"
NODE_BIN = TOOLS_DIR / "node"
JDK_DIR = TOOLS_DIR / "jdk8"
REQUIREMENTS = ROOT / "requirements.txt"

# dev 模式后端健康检查地址用 /api/auth/dict（无需登录、永远 200）
HEALTH_PATH = "/api/auth/dict"



def find_jdk():
    """优先选 JDK 8/11/17（Spring Boot 2.x 官方支持范围）。"""
    candidates = []
    # 便携包自带 JDK 优先，避免用户必须手动设置 JAVA_HOME。
    bundled = TOOLS_DIR / "jdk8"
    if (bundled / "bin" / "java.exe").exists():
        candidates.append(bundled)
    env_home = os.environ.get("JAVA_HOME")
    if env_home:
        candidates.append(Path(env_home))
    for pattern_dir in [Path(r"C:\Program Files\Java"),
                        Path(r"C:\Program Files\Eclipse Adoptium"),
                        Path(r"C:\Program Files\Microsoft"),
                        Path(r"C:\Program Files\Amazon"),
                        Path(r"C:\Program Files\BellSoft")]:
        if pattern_dir.exists():
            candidates += [p for p in pattern_dir.iterdir() if (p / "bin" / "javac.exe").exists()]

    def major(jdk_home: Path):
        release = jdk_home / "release"
        if release.exists():
            for line in release.read_text(encoding="utf-8", errors="ignore").splitlines():
                if line.startswith("JAVA_VERSION="):
                    v = line.split('"')[1]
                    parts = v.split(".")
                    return int(parts[1]) if parts[0] == "1" else int(parts[0])
        return None

    scored = []
    for c in candidates:
        m = major(c)
        if m:
            scored.append((c, m))
    for c, m in scored:
        if m in (8, 11, 17):
            return c, m
    for c, m in scored:
        return c, m
    raise SystemExit("未找到可用的 JDK（需要 8/11/17），请先安装或设置 JAVA_HOME")


def port_in_use(port):
    import socket

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", int(port))) == 0


def find_free_port(start, tries=30):
    """从 start 开始找一个未被占用的端口。"""
    import socket

    port = int(start)
    for _ in range(tries):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(0.5)
            if s.connect_ex(("127.0.0.1", port)) != 0:
                return port
        port += 1
    raise SystemExit(f"在 {start}~{start + tries} 范围内找不到空闲端口，请手动指定 --backend-port")


def find_pid_by_port(port):
    """返回占用该端口的进程 PID（Windows 用 netstat，Linux/macOS 用 lsof）。"""
    try:
        raw = subprocess.run(["netstat", "-ano"] if os.name == "nt" else ["lsof", "-ti", f"tcp:{port}"],
                             capture_output=True).stdout
        # Windows 中文环境 netstat 输出是 GBK，直接按 utf-8 解会炸
        out = raw.decode("gbk" if os.name == "nt" else "utf-8", errors="ignore")
        if os.name == "nt":
            for line in out.splitlines():
                if f":{port}" in line and "LISTENING" in line.upper():
                    return line.split()[-1]
        return out.strip().split("\n")[0] or None
    except Exception:
        pass
    return None


def strip_port_env(env):
    """清除外部注入的 SERVER_PORT / SERVER__PORT 等环境变量。

    某些终端或工具会注入 SERVER__PORT=xxxxx，Spring 的宽松绑定会把
    SERVER__PORT 识别成 server.port，导致服务端口被莫名改成随机值。
    这里只保留我们自己的 CF_SERVER_PORT。
    """
    for key in list(env.keys()):
        normalized = key.upper().replace("-", "_")
        if normalized in ("SERVER_PORT", "SERVER__PORT", "PORT"):
            env.pop(key, None)
            print(f"已忽略环境变量 {key}（避免顶掉 server.port）")


def run(cmd, cwd, env=None, shell=False):
    print("$", " ".join(str(x) for x in cmd) if isinstance(cmd, list) else cmd)
    return subprocess.Popen(cmd, cwd=str(cwd), env=env or os.environ.copy(), shell=shell)


def node_exe():
    """返回便携版 node，找不到就退回 PATH 里的 node。"""
    bundled = NODE_BIN / "node.exe"
    if bundled.exists():
        return bundled
    return "node"


def npm_cmd():
    """返回便携版 npm 的调用方式。

    便携包里 tools\\node 的布局不完全固定（有的带 node_modules/npm，有的只有 npm.cmd），
    这里按可能性从高到低探测，避免因为布局差异就说「找不到 npm」。
    """
    for rel in ("node_modules/npm/bin/npm-cli.js",):
        p = NODE_BIN / rel
        if p.exists():
            # 用 node 直接跑 cli.js，绕开 .cmd 包装（跨 shell 更稳）
            return [str(node_exe()), str(p)]
    for name in ("npm.cmd", "npm"):
        p = NODE_BIN / name
        if p.exists():
            return [str(p)]
    return None


def env_with_path(extra: Path):
    """复制当前环境并把某个目录前置到 PATH。"""
    env = os.environ.copy()
    env["PATH"] = str(extra) + os.pathsep + env.get("PATH", "")
    return env


# --------------------------------------------------------------------------
# 环境自检 / 依赖修复
#
# 原先这些逻辑写在 .bat 里。现在改成 Python，好处是：
#   • 结果可被 PyCharm 运行配置直接复用（不用双击黑窗口）
#   • 退出码规范，能配合 CI 或运行配置的「失败即停」
#   • 跨平台，不再被 cmd 的编码和 findstr 语法绊住
# --------------------------------------------------------------------------

def check_environment() -> bool:
    """逐项自检开发环境，返回是否全部通过。只读，不装任何东西。"""
    print("=" * 52)
    print(" CaseFlow 开发环境自检")
    print("=" * 52)
    ok = True

    # 1) JDK —— 后端必须 JDK8，高版本会报 Unsupported class file major version
    print("\n[1/5] JDK")
    if (JDK_DIR / "bin" / "java.exe").exists():
        try:
            jdk, version = find_jdk()
            print(f"      OK  使用 JDK {version}：{jdk}")
            if version != 8:
                print(f"      提示 当前选中的是 JDK {version}，本项目按 JDK 8 验证过，"
                      f"若编译报错请优先用 tools\\jdk8")
        except SystemExit as e:
            print(f"      失败 {e}")
            ok = False
    else:
        print(f"      失败 未找到自带 JDK：{JDK_DIR}")
        print("           解压包可能不完整，请确认 tools\\jdk8\\bin\\java.exe 存在")
        ok = False

    # 2) Maven + 离线仓库
    print("\n[2/5] Maven")
    mvn = MAVEN_HOME / "bin" / ("mvn.cmd" if os.name == "nt" else "mvn")
    if mvn.exists():
        print(f"      OK  {mvn}")
        if REPO.exists():
            print(f"      OK  离线仓库 {REPO}")
        else:
            print(f"      警告 离线仓库不存在（{REPO}），首次编译需要联网下载依赖")
    else:
        print(f"      失败 未找到 Maven：{mvn}")
        ok = False

    # 3) Node
    print("\n[3/5] Node / npm")
    node = node_exe()
    if node != "node" or _which("node"):
        try:
            v = subprocess.run([str(node), "-v"], capture_output=True, text=True,
                               encoding="utf-8", errors="ignore").stdout.strip()
            print(f"      OK  Node {v}（{node}）")
        except Exception as e:
            print(f"      警告 无法执行 node：{e}")
    else:
        print("      警告 未检测到 Node，前端无法启动（后端不受影响）")
    npm = npm_cmd()
    if npm:
        print(f"      OK  npm：{' '.join(npm)}")
    else:
        print("      警告 未检测到 npm，前端依赖无法自动安装")

    # 4) 前端依赖
    print("\n[4/5] 前端依赖")
    state = frontend_deps_state()
    if state == "ok":
        print("      OK  node_modules 完整，vite 可执行")
    elif state == "missing":
        print("      待装 node_modules 缺失（启动前端时会自动安装）")
    else:
        print("      待装 node_modules 不完整（上次安装可能被中断，启动前端时会重装）")

    # 5) Python 虚拟环境
    print("\n[5/5] Python 虚拟环境 / 脚本依赖")
    py = venv_python()
    in_venv = str(py).startswith(str(ROOT / ".venv"))
    if in_venv:
        print(f"      OK  {py}")
    else:
        print(f"      提示 .venv 不存在或不可用，将退回当前解释器：{py}")
        print(f"           重建方式（PyCharm 里也可直接跑「修复依赖」运行配置）：")
        print(f"             cd {ROOT}")
        print(f"             python -m venv .venv")
        print(f"             .venv\\Scripts\\python.exe -m pip install -r requirements.txt")
    missing = missing_python_packages(py)
    if not missing:
        print("      OK  脚本依赖齐全（requests / pymysql / openpyxl）")
    else:
        print(f"      待装 缺少：{', '.join(missing)}")
        print("           跑「修复依赖」运行配置，或执行 dev.py --fix-deps")

    print("\n" + "=" * 52)
    print(" 自检结论：" + ("全部通过，可以直接启动" if ok else "存在阻塞项，请先按上面提示处理"))
    print("=" * 52)
    return ok


def _which(name: str):
    """极简 which，避免引 shutil（其实 shutil 也是标准库，但这里保持轻量）。"""
    import shutil
    return shutil.which(name)


def missing_python_packages(py: str):
    """检查脚本必需的第三方包，返回缺失列表。"""
    required = ["requests", "pymysql", "openpyxl"]
    probe = ";".join(f"import {m}" for m in required)
    # 逐个 import，才能知道具体缺哪个
    missing = []
    for mod in required:
        r = subprocess.run([py, "-c", f"import {mod}"], capture_output=True)
        if r.returncode != 0:
            missing.append(mod)
    return missing


def frontend_deps_state() -> str:
    """判断前端依赖状态：ok / missing / broken。

    不能只看「某个文件在不在」：
      • node_modules 整体缺失            -> missing
      • 在、但没有 .package-lock.json     -> broken（npm ci 被中断，esbuild 装了一半）
      • 都在，但 vite --version 跑不通    -> broken
    """
    nm = FRONTEND_DIR / "node_modules"
    vite_js = nm / "vite" / "bin" / "vite.js"
    if not vite_js.exists():
        return "missing"
    if not (nm / ".package-lock.json").exists():
        return "broken"
    probe = subprocess.run([str(node_exe()), str(vite_js), "--version"],
                           cwd=str(FRONTEND_DIR), capture_output=True)
    return "ok" if probe.returncode == 0 else "broken"


def install_frontend_deps():
    """安装/修复前端依赖。返回是否成功。"""
    npm = npm_cmd()
    if not npm:
        print("! 未找到 npm，无法安装前端依赖")
        return False
    print("正在安装前端依赖（npm install，增量补齐）...")
    print("  说明：这里刻意不用 npm ci —— 它会先清空 node_modules 再重建，")
    print("        一旦重建中 node.exe 被占用就报 EBUSY，会把可用目录砸成半成品。")
    r = subprocess.run(npm + ["install", "--include=optional", "--no-audit", "--no-fund"],
                       cwd=str(FRONTEND_DIR), env=env_with_path(NODE_BIN))
    if r.returncode != 0:
        print("! 前端依赖安装失败。常见原因：")
        print("  • 网络不通（需要访问 npm registry）")
        print("  • 'spawnSync node.exe EBUSY' → 有进程占用 tools\\node\\node.exe，")
        print("    请先关掉正在运行的前端 / 编辑器里的相关终端，再重试")
        return False
    return True


def fix_deps() -> int:
    """修复 Python 与前端依赖，返回退出码。"""
    print("=" * 52)
    print(" CaseFlow 依赖修复")
    print("=" * 52)

    # --- Python ---
    print("\n[1/2] Python 脚本依赖")
    venv_dir = ROOT / ".venv"
    py = venv_python()
    if not str(py).startswith(str(venv_dir)):
        print(f"      .venv 不存在，正在创建（约需 1 分钟）...")
        if subprocess.run([sys.executable, "-m", "venv", str(venv_dir)],
                          cwd=str(ROOT)).returncode != 0:
            print("      ! 创建 .venv 失败；脚本仍可用系统解释器运行，只是缺依赖")
            py = sys.executable
        else:
            py = venv_python()
            print(f"      已创建：{py}")
    if REQUIREMENTS.exists():
        print(f"      安装 {REQUIREMENTS.name} ...")
        subprocess.run([py, "-m", "pip", "install", "-q", "-r", str(REQUIREMENTS)],
                       cwd=str(ROOT))
    missing = missing_python_packages(py)
    print("      OK  依赖齐全" if not missing else f"      ! 仍缺少：{', '.join(missing)}")

    # --- 前端 ---
    print("\n[2/2] 前端依赖")
    state = frontend_deps_state()
    if state == "ok":
        print("      OK  node_modules 完整，vite 可执行，无需处理")
        fe_ok = True
    else:
        print(f"      当前状态：{'缺失' if state == 'missing' else '不完整'}，开始安装 ...")
        fe_ok = install_frontend_deps()
        if fe_ok:
            print("      OK  " + ("安装完成" if frontend_deps_state() == "ok"
                                 else "安装完成但 vite 仍不可执行，请检查 node_modules"))

    print("\n" + "=" * 52)
    print(" 结论：" + ("依赖已就绪" if (not missing and fe_ok) else "仍有问题，见上面提示"))
    print("=" * 52)
    return 0 if (not missing and fe_ok) else 1



def ensure_local_mysql():
    """mysql profile：确保本地免安装 MySQL 实例在运行，并返回连接用的环境变量。

    若实例已初始化但没启动，自动拉起；若根本没初始化，提示先跑 mysql_local.py init。
    """
    import mysql_local as ml

    envs = dict(ml.DEFAULTS)
    if not ml.port_open(ml.PORT):
        if not (ml.MYSQL_DATA / "mysql").exists():
            raise SystemExit(
                "本地 MySQL 实例尚未初始化。先执行一次：\n"
                "  .venv\\Scripts\\python.exe scripts\\mysql_local.py init\n"
                "如需连接外部/正式 MySQL，请自行设置 MYSQL_HOST/MYSQL_USER/MYSQL_PASSWORD 后\n"
                "用 SPRING_PROFILES_ACTIVE=mysql 直接启动后端（跳过本检查可加 --skip-db-check）。")
        print("本地 MySQL 未运行，正在启动 ...")
        ml.cmd_start(type("A", (), {})())
    else:
        print(f"本地 MySQL 已在运行：{ml.HOST}:{ml.PORT}")
    return envs


def wait_backend(url, timeout=180):
    """轮询后端健康检查地址，直到返回 200 或超时。

    这里刻意只用标准库 urllib，不引 requests：
    dev.py 是「启动器」，用户往往在还没装好依赖、甚至 .venv 都不存在时就要跑它，
    让它依赖第三方包会把「起不来」和「依赖没装」两件事耦合成同一个失败。
    """
    import urllib.error
    import urllib.request

    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as resp:
                if resp.status == 200:
                    return True
        except (urllib.error.URLError, OSError, ValueError):
            time.sleep(2)
    return False


def start_frontend(backend_port: str):
    """启动 Vite 开发服务器，返回 Popen。"""
    fenv = os.environ.copy()
    fenv["PATH"] = str(NODE_BIN) + os.pathsep + fenv.get("PATH", "")
    fenv["VITE_BACKEND"] = f"http://127.0.0.1:{backend_port}"
    strip_port_env(fenv)

    if npm_cmd() is None:
        raise SystemExit(f"未找到 Node/npm：{NODE_BIN}")

    # 依赖目录可随时清理，缺失或半残时自动恢复
    state = frontend_deps_state()
    if state != "ok":
        if state == "broken":
            print("检测到前端依赖目录不完整（上次 npm ci 可能被中断），重新安装...")
        if not install_frontend_deps():
            raise SystemExit("前端依赖不可用，已中止")
        if frontend_deps_state() != "ok":
            raise SystemExit("前端依赖安装后仍未通过自检，请检查 node_modules")

    return run(npm_cmd() + ["run", "dev"], FRONTEND_DIR, fenv)


def h2_file_locked() -> bool:
    """检查 dev 模式的 H2 文件库是否已被别的进程占用。

    端口探测查不出这种情况：dev 用的是**文件库**，锁在文件上而不在端口上。
    于是「8080 已有实例在跑、我又用 8099 起第二个」时，端口检查会通过，
    但 H2 会在启动过程中抛 AccessDeniedException —— 报错埋在 Maven 输出里，
    对使用者来说很难懂。这里提前探一下，把话说清楚。
    """
    db = BACKEND_DIR / "data" / "h2" / "caseflow.mv.db"
    if not db.exists():
        return False
    try:
        # 以独占方式试开：被别的进程占用时会失败
        with open(db, "r+b"):
            pass
        return False
    except (PermissionError, OSError):
        return True


def main():
    ap = argparse.ArgumentParser(
        description="CaseFlow 开发环境启动器（纯标准库，可在 PyCharm 运行配置里直接调用）")
    ap.add_argument("--profile", default="dev", choices=["dev", "mysql"])
    ap.add_argument("--no-frontend", action="store_true", help="只启动后端")
    ap.add_argument("--no-backend", action="store_true",
                    help="只启动前端（需已有后端在跑）")
    ap.add_argument("--seed", action="store_true", help="启动后自动灌入演示数据")
    ap.add_argument("--backend-port", default="8080")
    ap.add_argument("--skip-db-check", action="store_true",
                    help="mysql profile 下跳过本地实例探测（连外部库时用）")
    ap.add_argument("--kill-existing", action="store_true",
                    help="端口被占用时先结束占用进程再启动")
    ap.add_argument("--check", action="store_true",
                    help="只做环境自检，不启动任何服务")
    ap.add_argument("--fix-deps", action="store_true",
                    help="修复 Python / 前端依赖后退出")
    args = ap.parse_args()

    if args.fix_deps:
        raise SystemExit(fix_deps())
    if args.check:
        raise SystemExit(0 if check_environment() else 1)

    # 只启动前端：不碰后端，也就不该做端口/H2 检查
    if args.no_backend:
        proc = start_frontend(args.backend_port)
        print(f"\n前端已启动：http://127.0.0.1:5173")
        print(f"（后端请另行启动，默认 http://127.0.0.1:{args.backend_port}/api）")
        print("按 Ctrl+C 结束。")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\n正在停止...")
            proc.terminate()
        return

    # 端口被占用是这类启动失败最常见的原因，先探测
    if port_in_use(args.backend_port):
        pid = find_pid_by_port(args.backend_port)
        who = f"（PID {pid}）" if pid else ""
        if args.kill_existing and pid:
            print(f"结束占用端口 {args.backend_port} 的进程 PID {pid} ...")
            subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True)
            time.sleep(3)
        elif args.profile == "dev":
            # dev 用 H2 文件库，同一时刻只允许一个进程打开，换端口也一样会失败
            raise SystemExit(
                f"端口 {args.backend_port} 已被占用{who}：已有一个后端实例在运行。\n"
                f"dev 模式使用 H2 文件库（backend\\data\\h2\\caseflow.mv.db），"
                f"同一时刻只允许一个进程打开，因此不能并行起第二个实例。\n\n"
                f"  直接用现有实例：http://127.0.0.1:{args.backend_port}/api 与 http://127.0.0.1:5173\n"
                f"  想重启它：在启动它的终端按 Ctrl+C"
                + (f"，或执行 taskkill /F /PID {pid}" if pid else "") + "\n"
                f"  想自动杀掉再起：scripts/dev.py --kill-existing\n"
                f"  想并行跑多套：改用 MySQL 模式（先跑 scripts/mysql_local.py init）")
        else:
            args.backend_port = str(find_free_port(int(args.backend_port) + 1))
            print(f"! 端口 {args.backend_port} 被占用{who}，自动改用 {args.backend_port}")

    # 端口没被占，但 H2 文件仍可能被锁 —— 这时端口探测毫无帮助，
    # 直接起会以 AccessDeniedException 失败，报错埋在 Maven 输出里很难懂。
    if args.profile == "dev" and h2_file_locked():
        raise SystemExit(
            "H2 数据库文件已被另一个进程锁住：\n"
            f"  {BACKEND_DIR / 'data' / 'h2' / 'caseflow.mv.db'}\n\n"
            "dev 模式用的是 H2 **文件库**，锁在文件上而不在端口上，\n"
            "所以就算换一个端口也起不来第二个实例。\n\n"
            "  直接用现有实例：http://127.0.0.1:8080/api\n"
            "  想让它退出：回到启动它的窗口按 Ctrl+C\n"
            "  想并行跑多套：改用 MySQL 模式（先跑 scripts/mysql_local.py init）")

    jdk, version = find_jdk()
    print(f"使用 JDK {version}：{jdk}")

    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")
    env["SPRING_PROFILES_ACTIVE"] = args.profile
    env["CF_SERVER_PORT"] = args.backend_port
    if args.profile == "mysql" and not args.skip_db_check:
        env.update(ensure_local_mysql())
    strip_port_env(env)

    maven = MAVEN_HOME / "bin" / ("mvn.cmd" if os.name == "nt" else "mvn")
    if not maven.exists():
        raise SystemExit(f"未找到 Maven：{maven}（可运行 scripts/setup_toolchain.py 下载）")

    # 显式传 --server.port：命令行参数优先级最高，可压过任何注入的环境变量
    backend = run([str(maven), "-B", f"-Dmaven.repo.local={REPO}",
                   f"-Dspring-boot.run.arguments=--server.port={args.backend_port}",
                   f"-Dspring-boot.run.jvmArguments=-Dserver.port={args.backend_port}",
                   "spring-boot:run"],
                  BACKEND_DIR, env)
    print("后端启动中（首次会下载依赖，请耐心等待）...")

    health = f"http://127.0.0.1:{args.backend_port}{HEALTH_PATH}"
    if not wait_backend(health):
        backend.terminate()
        raise SystemExit(
            "后端启动失败。请往上翻 Maven 输出，找这几行定位原因：\n"
            "  • 'APPLICATION FAILED TO START' / 'Port ... was already in use' → 端口冲突\n"
            "  • 'Could not open file ... caseflow.mv.db' → 已有实例占用 H2 文件库，先停掉旧实例再起\n"
            "  • 'Unsupported class file major version' → JDK 版本不对（需 8/11/17）\n"
            "  • \"Unsupported character encoding 'utf8mb4'\" → JDBC URL 的 characterEncoding 要写 UTF-8\n"
            "  • 'Access denied for user' / 'Unknown database' → MySQL 连接或库不存在，跑 mysql_local.py setup\n"
            "  • 'Table ... doesn't exist' → 未建表，跑 mysql_local.py setup\n"
            f"健康检查地址：{health}")
    print(f"后端已就绪：{health}")

    procs = [backend]
    if not args.no_frontend:
        try:
            procs.append(start_frontend(args.backend_port))
        except SystemExit:
            backend.terminate()
            raise
        print("前端已启动：http://127.0.0.1:5173")

    print("\n访问地址：")
    print("  前端 http://127.0.0.1:5173")
    print(f"  后端 http://127.0.0.1:{args.backend_port}/api")
    if args.profile == "dev":
        print(f"  H2 控制台 http://127.0.0.1:{args.backend_port}/api/h2-console")
    else:
        from mysql_local import DB, HOST, PORT
        print(f"  数据库 MySQL {HOST}:{PORT}/{DB}")
    print("  演示账号 boss / admin123\n按 Ctrl+C 结束。")

    if args.seed:
        time.sleep(3)
        # 解释器自适应：.venv 不存在时退回当前解释器，避免「无法加载模块 .venv」
        py = venv_python()
        subprocess.run([py, str(ROOT / "scripts" / "seed_demo.py"),
                        "--base", f"http://127.0.0.1:{args.backend_port}"], cwd=str(ROOT))

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n正在停止...")
        for p in procs:
            p.terminate()


if __name__ == "__main__":
    main()
