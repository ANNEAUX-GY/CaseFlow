"""构建可交付的 CaseFlow 应用部署包。

部署包面向目标机运行，不包含 Node/Maven/Python 依赖缓存和源码；源码开发包由
package_source.py 生成。默认流程会先构建前端和后端，再把 JAR、脚本、SQL 和配置
示例压缩成一个可校验的 ZIP。
"""
import argparse
import hashlib
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from _common import BACKEND_DIR, PROJECT_DIR, ROOT

JAR = BACKEND_DIR / "target" / "case-flow-backend.jar"
PACKAGE_FILES = (
    (JAR, "app/case-flow-backend.jar"),
    (ROOT / "deploy" / "README.md", "README.md"),
    (ROOT / "deploy" / "start.ps1", "start.ps1"),
    (ROOT / "deploy" / "stop.ps1", "stop.ps1"),
    (ROOT / "deploy" / "status.ps1", "status.ps1"),
    (ROOT / ".env.example", ".env.example"),
    (ROOT / "sql" / "schema.sql", "sql/schema.sql"),
    (ROOT / "sql" / "index-mysql.sql", "sql/index-mysql.sql"),
)


def git_revision() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"], cwd=str(PROJECT_DIR),
            capture_output=True, text=True, encoding="utf-8", check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "uncommitted"


def build_source() -> int:
    script = ROOT / "scripts" / "package_release.py"
    return subprocess.run([sys.executable, str(script)], cwd=str(ROOT)).returncode


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="构建 CaseFlow 应用部署包")
    parser.add_argument("--skip-build", action="store_true", help="复用已有 target/case-flow-backend.jar")
    parser.add_argument("--out", default=None, help="输出目录，默认项目根 release")
    parser.add_argument("--compress", type=int, default=9, choices=range(10), help="ZIP 压缩级别")
    args = parser.parse_args()

    if not args.skip_build:
        print("[1/3] 构建前端和后端 ...")
        if build_source() != 0:
            return 1
    if not JAR.exists():
        print(f"未找到部署 JAR：{JAR}", file=sys.stderr)
        return 1

    out_dir = Path(args.out) if args.out else PROJECT_DIR / "release"
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    package_name = f"caseflow-deployment-{stamp}"
    zip_path = out_dir / f"{package_name}.zip"
    revision = git_revision()

    missing = [src for src, _ in PACKAGE_FILES if not src.exists()]
    if missing:
        print("部署包缺少文件：", file=sys.stderr)
        for path in missing:
            print(f"  - {path}", file=sys.stderr)
        return 1

    print("[2/3] 写入部署包 ...")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=args.compress) as archive:
        for source, target in PACKAGE_FILES:
            archive.write(source, f"{package_name}/{target}")

        version = (
            "CaseFlow application deployment package\n"
            f"build_time_utc={datetime.now(timezone.utc).isoformat()}\n"
            f"git_revision={revision}\n"
            f"jar_sha256={sha256(JAR)}\n"
            "runtime=Java 8+\n"
            "profiles=dev(H2),mysql(MySQL 8+)\n"
        )
        archive.writestr(f"{package_name}/VERSION.txt", version.encode("utf-8"))
        checksums = "\n".join(f"{sha256(source)}  {target}" for source, target in PACKAGE_FILES) + "\n"
        archive.writestr(f"{package_name}/SHA256SUMS.txt", checksums.encode("utf-8"))
        archive.writestr(f"{package_name}/data/uploads/.gitkeep", b"")
        archive.writestr(f"{package_name}/data/h2/.gitkeep", b"")
        archive.writestr(f"{package_name}/logs/.gitkeep", b"")

    print("[3/3] 校验部署包 ...")
    with zipfile.ZipFile(zip_path) as archive:
        names = set(archive.namelist())
        forbidden = ("node_modules/", "target/", ".venv/")
        bad = [name for name in names if any(token in name for token in forbidden)
               or name.endswith("/.env") or "/.env/" in name]
        required = {f"{package_name}/{target}" for _, target in PACKAGE_FILES}
        if bad or not required.issubset(names):
            print("部署包校验失败", file=sys.stderr)
            for name in bad:
                print(f"  禁止内容：{name}", file=sys.stderr)
            return 1

    size_mb = zip_path.stat().st_size / 1024 / 1024
    print(f"完成：{zip_path}（{size_mb:.1f} MB，revision={revision}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
