"""打包成品：前端 build + 后端打 jar（前端页面内嵌进 jar）。

产物：app/backend/target/case-flow-backend.jar
拿到 jar 后只要目标机有 JDK8，`java -jar case-flow-backend.jar` 即可托管运行
（页面地址 http://<IP>:8080/api/）。局域网部署再用 scripts/start_lan.py 收尾。

用法：
    .venv\\Scripts\\python.exe scripts/package_release.py
    .venv\\Scripts\\python.exe scripts/package_release.py --skip-frontend   # 只打后端
    .venv\\Scripts\\python.exe scripts/package_release.py --no-clean        # 增量打包，快

也可以用 PyCharm 运行配置「打包成品」直接跑，无需手敲命令。
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

from _common import BACKEND_DIR, FRONTEND_DIR, ROOT, TOOLS_DIR, venv_python

MAVEN_HOME = TOOLS_DIR / "maven"
REPO = TOOLS_DIR / "m2repo"
NODE_BIN = TOOLS_DIR / "node"
JDK_DIR = TOOLS_DIR / "jdk8"
JAR = BACKEND_DIR / "target" / "case-flow-backend.jar"
DIST = FRONTEND_DIR / "dist"


def find_jdk():
    """与 dev.py 保持同一套规则：优先便携 JDK8。"""
    bundled = JDK_DIR
    if (bundled / "bin" / "javac.exe").exists():
        return bundled
    env_home = os.environ.get("JAVA_HOME")
    if env_home and (Path(env_home) / "bin" / "javac.exe").exists():
        return Path(env_home)
    for base in (Path(r"C:\Program Files\Java"),
                 Path(r"C:\Program Files\Eclipse Adoptium"),
                 Path(r"C:\Program Files\Microsoft")):
        if base.exists():
            for p in base.iterdir():
                if (p / "bin" / "javac.exe").exists() and "1.8" in p.name:
                    return p
    raise SystemExit("未找到 JDK8（打包必须用 JDK8），请检查 tools\\jdk8 是否完整")


def node_exe() -> str:
    bundled = NODE_BIN / "node.exe"
    return str(bundled) if bundled.exists() else "node"


def npm_cmd():
    p = NODE_BIN / "node_modules" / "npm" / "bin" / "npm-cli.js"
    if p.exists():
        return [node_exe(), str(p)]
    for name in ("npm.cmd", "npm"):
        q = NODE_BIN / name
        if q.exists():
            return [str(q)]
    return None


def ensure_frontend_deps() -> bool:
    """前端依赖不可用时自动补装（复用 dev.py 的判定与安装逻辑）。"""
    sys.path.insert(0, str(ROOT / "scripts"))
    try:
        import dev
    except Exception as e:                                  # pragma: no cover
        print(f"! 无法加载 dev.py 复用依赖逻辑：{e}")
        return False
    state = dev.frontend_deps_state()
    if state == "ok":
        return True
    print(f"前端依赖{'缺失' if state == 'missing' else '不完整'}，正在安装 ...")
    return dev.install_frontend_deps()


def run(cmd, cwd, env):
    print("$", " ".join(str(x) for x in cmd))
    return subprocess.run(cmd, cwd=str(cwd), env=env).returncode


def main() -> int:
    ap = argparse.ArgumentParser(description="打包成品（前端 + 后端 -> 单个 jar）")
    ap.add_argument("--skip-frontend", action="store_true", help="跳过前端 build")
    ap.add_argument("--no-clean", action="store_true", help="不做 clean，增量打包")
    args = ap.parse_args()

    jdk = find_jdk()
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + str(NODE_BIN) + os.pathsep + env.get("PATH", "")

    mvn = MAVEN_HOME / "bin" / ("mvn.cmd" if os.name == "nt" else "mvn")
    if not mvn.exists():
        raise SystemExit(f"未找到 Maven：{mvn}")

    print("=" * 52)
    print(" CaseFlow 打包成品")
    print("=" * 52)
    print(f"使用 JDK：{jdk}")

    if not args.skip_frontend:
        print("\n[1/2] 构建前端 ...")
        if not ensure_frontend_deps():
            print("  前端依赖不可用，已中止。可先跑「修复依赖」运行配置。")
            return 1
        npm = npm_cmd()
        if npm is None:
            print("  未找到 npm，已中止")
            return 1
        if run(npm + ["run", "build"], FRONTEND_DIR, env) != 0:
            print("  前端构建失败，已中止")
            return 1
        print(f"  前端产物：{DIST}")
    else:
        print("\n[1/2] 跳过前端构建（--skip-frontend）")
        if not DIST.exists():
            print("  注意：frontend/dist 不存在，打出的 jar 不含页面")

    print("\n[2/2] 打包后端（前端页面会被塞进 jar 的 static/）...")
    goals = ["clean", "package"] if not args.no_clean else ["package"]
    if run([str(mvn), "-B", f"-Dmaven.repo.local={REPO}"] + goals + ["-DskipTests"],
           BACKEND_DIR, env) != 0:
        print("  后端打包失败")
        return 1

    if not JAR.exists():
        print(f"  没找到预期产物：{JAR}")
        return 1

    size_mb = JAR.stat().st_size / 1024 / 1024
    print("\n" + "=" * 52)
    print(f" 完成：{JAR}（{size_mb:.1f} MB）")
    print("=" * 52)
    print(" 运行方式（目标机只需 JDK8）：")
    print(f"   java -jar {JAR.name} --server.port=8080")
    print(" 页面地址：http://<目标机IP>:8080/api/")
    print(" 局域网部署推荐直接用 scripts/start_lan.py（会拉起库、开防火墙提示、打二维码）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
