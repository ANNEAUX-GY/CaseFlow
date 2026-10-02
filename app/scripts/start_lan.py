"""局域网部署模式启动：一个 jar 直接对外提供服务。

与 dev.py 的区别：
  dev.py      开发用 —— 跑 Vite 开发服务器 + spring-boot:run，改代码即热更新
  本脚本      上线用 —— 直接跑打好包的 jar（前端已内嵌在 jar 里），全单位可访问

用法：
    .venv\\Scripts\\python.exe scripts\\start_lan.py
    .venv\\Scripts\\python.exe scripts\\start_lan.py --port 8080
    .venv\\Scripts\\python.exe scripts\\start_lan.py --detached     # 后台启动
    .venv\\Scripts\\python.exe scripts\\start_lan.py --stop         # 停止

访问地址：http://<本机局域网IP>:8080/api/
（注意结尾的 /api/ 是后端的 context-path，少了它打不开页面）

如果本机能开、手机或同事的电脑打不开 —— 99% 是 Windows 防火墙没放行 8080。
以管理员身份运行 scripts\\open_lan_firewall.py 放行即可（本脚本启动结束也会自检并提醒）。
"""
import argparse
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

from _common import TOOLS_DIR, venv_python

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "backend" / "target" / "case-flow-backend.jar"
UPLOAD_DIR = ROOT / "data" / "uploads"
LOG_DIR = ROOT / "logs"

# JDK 定位复用 dev.py 的一套规则（便携 tools\jdk8 优先），
# 避免这里硬编码某个安装路径 —— 换台电脑就找不到 JDK 是最常见的部署失败原因。
FIREWALL_SCRIPT = ROOT / "scripts" / "open_lan_firewall.py"



def lan_ips() -> list:
    """列出本机全部可用于局域网访问的 IPv4 地址（不会真的发包）"""
    ips = []
    # 主地址：系统选路的出口地址
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        ips.append(s.getsockname()[0])
    except Exception:
        pass
    finally:
        s.close()
    # 其余地址：按名字解析兜底（多网卡 / 有线+无线同时在线时有用）
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips or ["127.0.0.1"]


def firewall_rule_exists() -> bool:
    """检查是否已放行 8080 入站。

    只读查询，不需要管理员权限；查不到就返回 False，由调用方提示。
    """
    if os.name != "nt":
        return True
    try:
        out = subprocess.run(["netsh", "advfirewall", "firewall", "show", "rule",
                              "name=CaseFlow 8080"],
                             capture_output=True, text=True, encoding="gbk",
                             errors="ignore").stdout
    except Exception:
        return True  # 查不了就别吓唬用户
    return "8080" in out


def warn_firewall(port: int) -> None:
    """没放行端口时给出一步到位的解决办法。

    这是手机 / 同事电脑打不开本系统最常见的原因：Windows 防火墙默认拦掉入站连接，
    而且本机自查（用 127.0.0.1 或自己的局域网 IP 访问）永远是通的，很容易误判成"服务没问题"。
    """
    print("")
    print(f"  [注意] 防火墙还没放行 {port} 端口。")
    print("         症状：本机能打开，但手机 / 同事的电脑打不开。")
    print("         解决办法（需管理员权限，会自动弹 UAC 授权窗口）：")
    print(f"             {FIREWALL_SCRIPT}")
    print("         在 PyCharm 里可直接选运行配置「开放局域网访问」")
    print("         （只放行这一个 TCP 端口，不改动其它设置）")


def show_qr(url: str) -> None:
    """在终端里打出二维码：手机扫一下就打开，省得手输 IP（对四五十岁的人尤其友好）"""
    try:
        import segno
    except ImportError:
        print("")
        print("      想让手机扫码直接打开，先装个纯 Python 的二维码库（可选）：")
        print(r"          .venv\Scripts\python.exe -m pip install segno")
        return
    print("")
    print("      手机扫下面这个码直接打开（手机要连同一个 WiFi）：")
    print("")
    try:
        print(segno.make(url, error="m").terminal(compact=True))
    except Exception:
        print("      (当前终端不支持画二维码，直接用上面的网址也一样)")


def java_exe() -> str:
    """定位 java 可执行文件。

    复用 dev.py 的 JDK 探测（便携 tools\\jdk8 优先），找不到就退回 PATH 里的 java。
    不要在这里写死 C:\\Program Files\\... 之类路径：换台电脑就失效。
    """
    try:
        import dev
        jdk, _version = dev.find_jdk()
        exe = Path(jdk) / "bin" / "java.exe"
        if exe.exists():
            return str(exe)
    except Exception:
        pass
    bundled = TOOLS_DIR / "jdk8" / "bin" / "java.exe"
    if bundled.exists():
        return str(bundled)
    return "java"


def ensure_mysql() -> None:
    """本地免安装 MySQL 没起就拉起来"""
    script = ROOT / "scripts" / "mysql_local.py"
    if not script.exists():
        return
    try:
        out = subprocess.run([sys.executable, str(script), "status"],
                             capture_output=True, text=True, encoding="utf-8").stdout
    except Exception:
        return
    if "运行状态：运行中" in out:
        print("[1/3] MySQL 已在运行")
        return
    print("[1/3] 启动 MySQL ...")
    subprocess.run([sys.executable, str(script), "start"], check=False)


def base_command(port: int) -> list:
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    return [
        java_exe(),
        # Windows 上不加 file.encoding，中文日志和中文文件名会乱码
        "-Dfile.encoding=UTF-8",
        "-Xmx1536m",
        "-jar", str(JAR),
        "--spring.profiles.active=mysql",
        f"--server.port={port}",
        # 绑定 0.0.0.0 才能被局域网其它机器访问
        "--server.address=0.0.0.0",
        # 附件目录必须绝对路径，否则换个启动目录附件就"丢"了
        f"--caseflow.upload-dir={UPLOAD_DIR}",
    ]


def wait_ready(port: int, seconds: int = 90) -> bool:
    import urllib.request
    url = f"http://127.0.0.1:{port}/api/auth/dict"
    for _ in range(seconds):
        try:
            with urllib.request.urlopen(url, timeout=2) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(1)
    return False


def run_foreground(port: int) -> None:
    cmd = base_command(port)
    print("[2/3] 启动后端（Ctrl+C 停止）...")
    print("      " + " ".join(cmd[:5]) + " ...")
    proc = subprocess.Popen(cmd, cwd=str(ROOT))
    try:
        proc.wait()
    except KeyboardInterrupt:
        proc.terminate()


def run_detached(port: int) -> None:
    cmd = base_command(port)
    log = LOG_DIR / "caseflow.out.log"
    print("[2/3] 后台启动后端 ...")
    with open(log, "ab") as f:
        subprocess.Popen(cmd, cwd=str(ROOT), stdout=f, stderr=subprocess.STDOUT,
                         creationflags=getattr(subprocess, "DETACHED_PROCESS", 0)
                         | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0))
    if wait_ready(port):
        print(f"      已就绪（日志：{log}）")
    else:
        print("      ⚠ 90 秒内没起来，去看日志：" + str(log))


def stop(port: int) -> None:
    """按端口找进程并结束（不依赖 jar 名，避免误伤）"""
    try:
        out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True,
                             encoding="utf-8", errors="ignore").stdout
    except Exception as e:
        print("查询端口失败：" + str(e))
        return
    pids = set()
    for line in out.splitlines():
        if "LISTENING" in line and f":{port}" in line:
            parts = line.split()
            if parts and parts[-1].isdigit():
                pids.add(parts[-1])
    if not pids:
        print(f"端口 {port} 上没有在跑的服务")
        return
    for pid in pids:
        subprocess.run(["taskkill", "/PID", pid, "/F"], capture_output=True)
        print(f"已停止 PID {pid}")


def main() -> None:
    ap = argparse.ArgumentParser(description="局域网部署模式启动")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--detached", action="store_true", help="后台运行")
    ap.add_argument("--stop", action="store_true", help="停止服务")
    args = ap.parse_args()

    if args.stop:
        stop(args.port)
        return

    if not JAR.exists():
        raise SystemExit(
            f"没找到 jar：{JAR}\n"
            "请先打包：cd backend && mvn -DskipTests clean package\n"
            "（前端产物会自动打进 jar，所以打包前请先执行 cd frontend && npm run build）")

    ensure_mysql()

    if args.detached:
        run_detached(args.port)
    else:
        run_foreground(args.port)
        return

    print("[3/3] 部署完成")
    print("")
    print(f"      本机访问：http://127.0.0.1:{args.port}/api/")
    ips = lan_ips()
    if len(ips) == 1:
        print(f"      同事访问：http://{ips[0]}:{args.port}/api/")
    else:
        print(f"      同事访问：http://{ips[0]}:{args.port}/api/")
        print("      （本机还有其它网卡地址，多半是 VMware / Hyper-V / WSL 之类的虚拟网卡，可忽略：")
        for ip in ips[1:]:
            print(f"          http://{ip}:{args.port}/api/")
        print("        若对方打不开，换列表里的另一个试；手机扫码用的是第一个）")
    print("")
    print("      提示：地址结尾的 /api/ 不能少，少了打不开页面。")
    print("      提示：把上面地址发给使用者，建议让他们加进浏览器收藏夹。")
    print("      首次登录用内置管理员 boss / admin123，登录后请立刻在「账号管理」里改密码。")

    show_qr(f"http://{ips[0]}:{args.port}/api/")

    if not firewall_rule_exists():
        warn_firewall(args.port)


if __name__ == "__main__":
    main()
