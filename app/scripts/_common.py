"""公共工具：定位工程根目录、读取配置文件。"""
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROJECT_DIR = ROOT.parent
SQL_DIR = ROOT / "sql"
DATA_DIR = ROOT / "data"
BACKEND_DIR = ROOT / "backend"
FRONTEND_DIR = ROOT / "frontend"
# 便携包把工具放在 app 的上一级 tools；开发者也可以继续使用 app/.tools。
_portable_tools = PROJECT_DIR / "tools"
TOOLS_DIR = _portable_tools if _portable_tools.exists() else ROOT / ".tools"

# 让脚本可以直接 import 同级模块
sys.path.insert(0, str(Path(__file__).resolve().parent))


def venv_python() -> str:
    """返回用于拉起子脚本的 Python 解释器路径。

    约定优先用 app/.venv 里的解释器，但 `.venv/` 被 .gitignore 忽略，
    换电脑或重新打包后并不会带过来——所以**不能假定它一定存在**，
    否则 ``dev.py --seed`` 会在 seed 阶段报「无法加载模块 .venv」。
    找不到时退回当前解释器（脚本自身总在一个可用的解释器里运行）。
    """
    for rel in ("Scripts/python.exe", "bin/python"):
        p = ROOT / ".venv" / rel
        if p.exists():
            return str(p)
    return sys.executable


def schema_sql() -> str:
    """读取建表脚本（唯一数据源：sql/schema.sql）。"""
    return (SQL_DIR / "schema.sql").read_text(encoding="utf-8")


def split_statements(sql_text: str):
    """按 ; 切分语句，忽略整行注释。"""
    buf = []
    for line in sql_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("--") or not stripped:
            continue
        buf.append(line)
        if stripped.endswith(";"):
            yield "\n".join(buf).rstrip(";")
            buf = []
    tail = "\n".join(buf).strip()
    if tail:
        yield tail


def ensure_dir(path):
    Path(path).mkdir(parents=True, exist_ok=True)
    return Path(path)
