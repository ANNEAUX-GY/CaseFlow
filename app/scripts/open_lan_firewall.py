"""开放局域网访问：给本机防火墙加一条 TCP 8080 入站放行规则。

为什么需要它：Windows 防火墙默认拦掉入站连接，而「本机自己访问」永远是通的，
所以很容易误判成「服务没问题、是别人的手机坏了」。放行这一个端口即可。

**需要管理员权限**（改防火墙规则的必要条件）。脚本会自动尝试提权：
在 Windows 上会弹出 UAC 授权窗口，点「是」即可；若单位统一管控了这台电脑，
可能仍会被策略拦下，此时请联系 IT 代为放行 TCP 8080。

用法（PyCharm 里选同名运行配置也行）：
    .venv\\Scripts\\python.exe scripts/open_lan_firewall.py            # 放行
    .venv\\Scripts\\python.exe scripts/open_lan_firewall.py --remove   # 收回
    .venv\\Scripts\\python.exe scripts/open_lan_firewall.py --status   # 只查看

PyCharm 注意：运行配置里应勾选「Emulate terminal in output console」，
否则提权后的输出可能看不到；也可以右键该配置选
「Run as administrator」以外的常规方式先试一次，UAC 弹窗会自行出现。
"""
import argparse
import ctypes
import os
import subprocess
import sys

RULE_NAME = "CaseFlow 8080"
PORT = 8080


def is_admin() -> bool:
    if os.name != "nt":
        return False
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def elevate() -> None:
    """以管理员身份重新启动自身。

    提权后是新进程、新控制台，PyCharm 里看不到输出属正常现象——
    所以下面刻意在提权版里自动 pause（用 input 等回车），
    否则窗口一闪而过，用户以为没生效。
    """
    script = os.path.abspath(__file__)
    args = sys.argv[1:]
    params = " ".join([f'"{script}"'] + [f'"{a}"' for a in args] + ["--elevated"])
    print("需要管理员权限，正在弹出授权窗口，请点「是」...")
    try:
        rc = ctypes.windll.shell32.ShellExecuteW(
            None, "runas", sys.executable, params, None, 1)
        # >32 表示成功启动
        if int(rc) <= 32:
            print("授权被取消或失败，无法修改防火墙规则。")
            print("可改用：以管理员身份打开 PowerShell 后执行")
            print(f'  netsh advfirewall firewall add rule name="{RULE_NAME}" '
                  f'dir=in action=allow protocol=TCP localport={PORT} profile=any')
    except Exception as e:
        print(f"提权失败：{e}")


def run(cmd, **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="gbk", errors="ignore", **kw)


def rule_exists() -> bool:
    out = run(["netsh", "advfirewall", "firewall", "show", "rule",
               "name=" + RULE_NAME]).stdout
    return str(PORT) in out


def add_rule() -> bool:
    run(["netsh", "advfirewall", "firewall", "delete", "rule", "name=" + RULE_NAME])
    r = run(["netsh", "advfirewall", "firewall", "add", "rule",
             "name=" + RULE_NAME, "dir=in", "action=allow",
             "protocol=TCP", f"localport={PORT}", "profile=any"])
    return r.returncode == 0


def remove_rule() -> bool:
    r = run(["netsh", "advfirewall", "firewall", "delete", "rule", "name=" + RULE_NAME])
    return r.returncode == 0


def lan_ips():
    import socket
    ips = []
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        ips.append(s.getsockname()[0])
    except Exception:
        pass
    finally:
        s.close()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips or ["127.0.0.1"]


def show_network_category():
    r = run(["powershell", "-NoProfile", "-Command",
             "Get-NetConnectionProfile | ForEach-Object { "
             "$_.InterfaceAlias + ' -> ' + $_.NetworkCategory }"])
    for line in (r.stdout or "").splitlines():
        if line.strip():
            print("     " + line.strip())


def main() -> int:
    ap = argparse.ArgumentParser(description="CaseFlow 局域网防火墙放行")
    ap.add_argument("--remove", action="store_true", help="删除放行规则")
    ap.add_argument("--status", action="store_true", help="只查看当前状态")
    ap.add_argument("--elevated", action="store_true", help=argparse.SUPPRESS)
    args = ap.parse_args()

    if os.name != "nt":
        print("仅 Windows 需要此步骤，当前系统无需操作。")
        return 0

    if args.status:
        print(f"规则「{RULE_NAME}」：{'已存在' if rule_exists() else '不存在'}")
        return 0

    if not is_admin():
        elevate()
        # 非提权分支到此结束；提权分支由 --elevated 继续往下走
        if not args.elevated:
            return 0

    print("=" * 58)
    print(f"  CaseFlow 局域网访问 · TCP {PORT} 放行")
    print("=" * 58)

    if args.remove:
        print(f"[1/1] 删除规则「{RULE_NAME}」...")
        print("      [完成] 已收回放行" if remove_rule() else "      [失败] 规则未删除")
        return 0

    print(f"[1/3] 清理旧规则 ...")
    print(f"[2/3] 新增放行规则（TCP {PORT}，所有网络类型）...")
    if add_rule():
        print(f"      [完成] 已放行 TCP {PORT}")
    else:
        print("      [失败] 规则未能添加。")
        print("             这台电脑若由单位统一管控，请联系 IT 代为放行。")

    print("[3/3] 当前网络类型：")
    show_network_category()

    print()
    print("-" * 58)
    print("  本机局域网地址（手机连同一 WiFi 后直接打开）：")
    print("-" * 58)
    for ip in lan_ips():
        print(f"     http://{ip}:{PORT}/api/")
    print()
    print("  注意：地址结尾的 /api/ 不能少，少了打不开页面。")
    print()
    print("-" * 58)
    print("  如果手机还是打不开：")
    print("-" * 58)
    print("  1) 确认手机和电脑连的是同一个 WiFi（不是流量、不是访客网络）")
    print("  2) 若上面网络类型显示 Public，改成 Private（需管理员）：")
    print('     Get-NetConnectionProfile | Where-Object {$_} | '
          'Set-NetConnectionProfile -NetworkCategory Private')
    print(f'  3) 想收回放行： scripts/open_lan_firewall.py --remove')

    if args.elevated:
        # 提权后的窗口是新开的，不加这句会一闪而过
        try:
            input("\n按回车关闭 ...")
        except EOFError:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
