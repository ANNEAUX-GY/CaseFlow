"""打包「完整源码包」：供另一台已配好开发环境的新电脑直接接手开发。

与 package_release.py（打成品 jar）不同，本脚本产出的是**可继续开发**的源码包：
源代码 + 配置 + 依赖清单 + 构建/启动脚本 + 环境变量示例 + 说明文档 + 演示数据库。

用法：
    .venv\\Scripts\\python.exe scripts/package_source.py
    .venv\\Scripts\\python.exe scripts/package_source.py --no-data     # 不含演示数据库（交空库）
    .venv\\Scripts\\python.exe scripts/package_source.py --out D:\\out  # 指定输出目录

产物：<项目根>\\release\\caseflow-source-<版本>-<时间戳>.zip

打包范围（源码与配置，均可由新电脑复现）：
  app/           后端源码、前端源码、sql、scripts、docs、.idea 工程配置、.env.example
  tools/         不打包（便携工具链，体积 500M+，新电脑用已装好的 JDK8/Maven/Node）
  README-开发包.txt

排除项（可再生成 / 本地状态，一律不打）：
  frontend/node_modules/  frontend/dist/  backend/target/  app/.venv/
  **/__pycache__/  *.pyc  *.log  *.tmp  .workbuddy/
  backend/data/uploads/   backend/data/h2/*.trace.db
  （以上与 app/.gitignore 保持一致；.env / .env.local 等真实密钥文件也不打）
"""
import argparse
import os
import re
import shutil
import sys
import zipfile
from datetime import datetime
from pathlib import Path

from _common import PROJECT_DIR, ROOT

# ---------------------------------------------------------------------------
# 排除规则（目录/文件名模式，相对 app 根；与 .gitignore 语义一致）
# ---------------------------------------------------------------------------
EXCLUDE_DIRS = {
    "node_modules", "dist", "target", ".venv", ".workbuddy", "__pycache__",
    ".git", ".svn", ".hg", ".idea/shelf", ".idea/httpRequests",
    ".idea/dataSources", ".idea/dictionaries", ".idea/inspectionProfiles",
}

# 文件名模式（用 fnmatch 风格；简单起见用后缀/精确名）
EXCLUDE_NAME_SUFFIX = {".pyc", ".class", ".log", ".tmp", ".trace.db"}
EXCLUDE_NAME_EXACT = {".DS_Store", "Thumbs.db", ".env", ".env.local"}

# 要打的顶层内容
INCLUDE_TOP = {"app", "README-开发包.txt"}


def _rel(root: Path, p: Path) -> str:
    """返回相对 root 的 POSIX 风格路径（zip 内统一用 / 分隔）。"""
    return p.relative_to(root).as_posix()


def _should_exclude(rel_path: str, name: str) -> bool:
    # 目录排除（匹配任何一级的相对路径前缀）
    for d in EXCLUDE_DIRS:
        if rel_path == d or rel_path.startswith(d + "/") or name == d:
            # 对 "app/.venv" 这类：rel_path 顶层就是目录名
            return True
    # 名称精确排除
    if name in EXCLUDE_NAME_EXACT:
        return True
    # 后缀排除
    for suf in EXCLUDE_NAME_SUFFIX:
        if name.endswith(suf):
            return True
    # 环境变量真实值文件（保留 .env.example）
    if name.startswith(".env") and name != ".env.example":
        return True
    return False


def collect_files(root: Path):
    """遍历 root 收集要打包的文件，返回 [(zip内相对路径, 磁盘绝对路径)]。"""
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirpath = Path(dirpath)
        # 剪枝：目录级别的排除
        kept = []
        for d in dirnames:
            rel = _rel(root, dirpath / d)
            if _should_exclude(rel, d):
                continue
            kept.append(d)
        dirnames[:] = kept

        for f in filenames:
            rel = _rel(root, dirpath / f)
            if _should_exclude(rel, f):
                continue
            out.append((rel, str(dirpath / f)))
    return out


def version_of() -> str:
    """从 backend/pom.xml 提取项目版本号，失败回退 1.0.0。

    注意不能用第一个 <version>：pom 里 spring-boot-starter-parent 的
    <version>2.7.18</version> 排在前面，必须定位到本项目
    <artifactId>case-flow-backend</artifactId> 之后紧跟的那个 <version>。
    """
    pom = ROOT / "backend" / "pom.xml"
    try:
        text = pom.read_text(encoding="utf-8")
        m = re.search(r"<artifactId>case-flow-backend</artifactId>\s*<version>([^<]+)</version>", text)
        if m:
            return m.group(1).replace("-SNAPSHOT", "")
    except OSError:
        pass
    return "1.0.0"


def main():
    ap = argparse.ArgumentParser(description="打包完整源码包")
    ap.add_argument("--out", default=None, help="输出目录（默认 <项目根>\\release）")
    ap.add_argument("--no-data", action="store_true",
                    help="排除演示数据库（backend/data/h2/caseflow.mv.db），交空库")
    ap.add_argument("--wx", action="store_true",
                    help="微信传输友好：文件名纯 ASCII（caseflow-source-<版本>.zip）+ 顶层包一层目录")
    ap.add_argument("--compress", type=int, default=9,
                    help="压缩级别 0-9（默认 9，最高压缩，微信传输体积更小）")
    args = ap.parse_args()

    out_dir = Path(args.out) if args.out else (PROJECT_DIR / "release")
    out_dir.mkdir(parents=True, exist_ok=True)

    ver = version_of()
    if args.wx:
        # 微信/Windows 传输链路对中文文件名兼容性差，统一用纯 ASCII 短名，
        # 不带时间戳，便于对方覆盖旧包、避免名字越积越长。
        zip_name = f"caseflow-source-{ver}.zip"
        root_prefix = "caseflow-source/"
    else:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        zip_name = f"caseflow-source-{ver}-{stamp}.zip"
        root_prefix = ""

    zip_path = out_dir / zip_name

    # 收集 app 与顶层说明文件
    entries = collect_files(ROOT)
    top_extra = []
    for name in ("README-开发包.txt",):
        p = PROJECT_DIR / name
        if p.exists():
            top_extra.append((name, str(p)))

    if args.no_data:
        entries = [e for e in entries if not e[0].startswith("backend/data/h2/caseflow.mv.db")]

    total = len(entries) + len(top_extra)
    print(f"打包源码 → {zip_path}")
    print(f"  包含 {total} 个文件（含演示数据{'，已排除' if args.no_data else ''}）")
    if root_prefix:
        print(f"  顶层目录：{root_prefix.rstrip('/')}/（解压后是一个干净目录，可直接整目录传输）")

    written = 0
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=args.compress) as zf:
        for rel, abs_path in entries:
            zf.write(abs_path, f"{root_prefix}app/{rel}")
            written += 1
        for rel, abs_path in top_extra:
            zf.write(abs_path, f"{root_prefix}{rel}")
            written += 1

    size_mb = zip_path.stat().st_size / 1024 / 1024
    print(f"  已写入 {written} 个文件，压缩后 {size_mb:.1f} MB")

    # 自校验：列出 zip 内是否有不应出现的内容
    with zipfile.ZipFile(zip_path, "r") as zf:
        names = zf.namelist()
    bad = [n for n in names
           if "/node_modules/" in n or "/dist/" in n or "/target/" in n
           or "/.venv/" in n or "/__pycache__/" in n or n.endswith(".log")
           or ("/.env" in n and ".env.example" not in n)]
    if bad:
        print("  ⚠ 校验发现疑似误打包项：")
        for b in bad[:10]:
            print("    -", b)
    else:
        print("  ✔ 校验通过：无 node_modules/dist/target/.venv/日志/密钥 混入")

    print(f"\n完成：{zip_path}")
    print("新电脑接手：解压后参考包内 README-开发包.txt 或 app/README.md，")
    print("重建 .venv、npm ci、按需初始化数据库后即可用 PyCharm 打开 app 目录开发。")


if __name__ == "__main__":
    main()
