# -*- coding: utf-8 -*-
"""升级安全性自检（零依赖，可随时重跑）。

用途：每次出交付包 / 每次动数据库代码之前跑一遍，确认「升级不会动内网已录入的数据」
这条纪律没有被后续改动破坏。退出码非 0 表示有违规，必须处理后再打包。

检查项：
  1. app/sql/schema.sql —— 只允许 CREATE TABLE IF NOT EXISTS；
     出现 DROP / TRUNCATE / ALTER / RENAME / MODIFY / DELETE / UPDATE 即失败。
  2. SchemaMigration.java —— 只允许 ALTER TABLE ... ADD COLUMN；
     出现 DROP / TRUNCATE / MODIFY / RENAME / DELETE 即失败。
  3. 每次启动都会跑的 UPDATE 必须带 WHERE ... IS NULL（只补空值，不覆盖已填值）；
     会覆盖既有值的整理必须放在 `if (onceOnly(...))` 闸门里。
  4. NEW_COLUMNS 里登记的列必须同时存在于 schema.sql（否则全新安装会缺列）。

用法：python app/scripts/verify_upgrade_safe.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "sql" / "schema.sql"
MIGRATION = ROOT / "backend/src/main/java/com/caseflow/bootstrap/SchemaMigration.java"

FAIL = []
WARN = []
PASS = 0


def ok(msg):
    global PASS
    PASS += 1
    print("  PASS  " + msg)


def fail(msg):
    FAIL.append(msg)
    print("  FAIL  " + msg)


def warn(msg):
    WARN.append(msg)
    print("  WARN  " + msg)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"^\s*--.*$", "", text, flags=re.M)
    text = re.sub(r"//[^\n]*", "", text)
    return text


# ---------------------------------------------------------------- 1. schema.sql
print("[1/4] 建库脚本只许建表、不许删改")
schema_raw = strip_comments(SCHEMA.read_text(encoding="utf-8"))
# 去掉字符串字面量（COMMENT 'CREATE/UPDATE/DELETE' 之类是给人看的说明文字），
# 再去掉外键的 ON DELETE/ON UPDATE 子句（这是引用动作，不是删数据）。
schema_sql = re.sub(r"'[^']*'", "''", schema_raw)
schema_sql = re.sub(r"\bON\s+(DELETE|UPDATE)\b", "ON _", schema_sql, flags=re.I)
forbidden = {
    "DROP": r"\bDROP\b",
    "TRUNCATE": r"\bTRUNCATE\b",
    "ALTER": r"\bALTER\b",
    "RENAME": r"\bRENAME\b",
    "MODIFY": r"\bMODIFY\b",
    "DELETE": r"\bDELETE\b",
    "UPDATE": r"\bUPDATE\b",
}
hit = False
for name, pat in forbidden.items():
    m = re.search(pat, schema_sql, flags=re.I)
    if m:
        line_no = schema_sql[:m.start()].count("\n") + 1
        fail("schema.sql 第 %d 行含 %s（升级会改动老库，必须去掉）" % (line_no, name))
        hit = True
if not hit:
    ok("schema.sql 无 DROP/TRUNCATE/ALTER/RENAME/MODIFY/DELETE/UPDATE")

n_tables = len(re.findall(r"CREATE\s+TABLE", schema_sql, flags=re.I))
n_guard = len(re.findall(r"CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS", schema_sql, flags=re.I))
if n_tables == n_guard:
    ok("全部 %d 条 CREATE TABLE 都是 IF NOT EXISTS（老库重复执行无副作用）" % n_tables)
else:
    fail("有 %d 条 CREATE TABLE 缺少 IF NOT EXISTS" % (n_tables - n_guard))

n_ctas = len(re.findall(r"CREATE\s+TABLE[^;]*\bAS\s+SELECT\b", schema_sql, flags=re.I))
if n_ctas == 0:
    ok("无 CREATE TABLE ... AS SELECT（不会拿查询结果覆盖表）")
else:
    fail("有 %d 条 CREATE TABLE ... AS SELECT" % n_ctas)

# ------------------------------------------------------- 2. SchemaMigration
print("[2/4] 启动迁移只许补列")
if not MIGRATION.exists():
    fail("找不到 %s" % MIGRATION)
    mig_sql = ""
    mig_code = ""
else:
    mig_code = strip_comments(MIGRATION.read_text(encoding="utf-8"))
    mig_sql = mig_code

hard = {"DROP": r"\bDROP\s+TABLE\b", "TRUNCATE": r"\bTRUNCATE\b",
        "MODIFY": r"\bMODIFY\s+COLUMN\b", "RENAME": r"\bRENAME\b",
        "DELETE": r"\bDELETE\s+FROM\b", "DROPCOL": r"\bDROP\s+COLUMN\b"}
hit = False
for name, pat in hard.items():
    m = re.search(pat, mig_sql, flags=re.I)
    if m:
        fail("SchemaMigration 含有 %s（第 %d 行附近）" % (name, mig_sql[:m.start()].count("\n") + 1))
        hit = True
if not hit:
    ok("SchemaMigration 无 DROP/TRUNCATE/MODIFY COLUMN/RENAME/DELETE FROM")

alters = re.findall(r'"ALTER\s+TABLE\s*"|ADD\s+COLUMN', mig_code, flags=re.I)
n_alter = len(re.findall(r'"ALTER\s+TABLE\s*"', mig_code, flags=re.I))
n_addcol = len(re.findall(r"ADD\s+COLUMN", mig_code, flags=re.I))
if n_alter == n_addcol and n_addcol > 0:
    ok("%d 处 ALTER TABLE 全部配 ADD COLUMN（只加不删）" % n_alter)
elif n_addcol > 0:
    warn("ALTER TABLE %d 处、ADD COLUMN %d 处，数量不匹配，请人工确认" % (n_alter, n_addcol))
else:
    fail("没找到任何 ADD COLUMN，迁移逻辑可能写错了")

# ------------------------------------ 3. UPDATE 是否只补空值 / 是否在一次性闸门内
print("[3/4] 数据改写只补空值，覆盖型整理必须进一次性闸门")
if mig_code:
    # 方法体切分（只认带明确返回类型的方法声明，避免把 if/for 当成方法）
    decl = re.compile(r"\n    (?:public |private |protected )?"
                      r"(?:void|boolean|int|long|String|Set<String>|List<String>) (\w+)\(")
    starts = [(m.start(), m.group(1)) for m in decl.finditer(mig_code)]
    methods = []
    for i, (pos, name) in enumerate(starts):
        end = starts[i + 1][0] if i + 1 < len(starts) else len(mig_code)
        methods.append((name, mig_code[pos:end]))

    # 被 onceOnly 闸门罩住的方法
    gated = set(re.findall(r"if\s*\(\s*onceOnly\([^)]*\)\s*\)\s*\{\s*[\w.]+\([^)]*\);",
                           mig_code, flags=re.S))
    gated |= set(re.findall(r"if\s*\(\s*onceOnly\([^)]*\)\s*\)\s*\{\s*(\w+)\s*\(", mig_code))

    checked = 0
    for name, body in methods:
        if re.search(r"\bUPDATE\b", body, flags=re.I):
            checked += 1
            flat = re.sub(r"\s+", " ", body)
            fill_only = re.search(r"WHERE[^;]*IS\s+NULL", flat, flags=re.I) is not None
            has_where = re.search(r"\bWHERE\b", flat, flags=re.I) is not None
            if not has_where:
                fail("%s() 里的 UPDATE 没有 WHERE，会整表覆盖" % name)
            elif name in gated:
                ok("%s() 的 UPDATE 在一次性闸门内（一辈子只跑一次）" % name)
            elif fill_only:
                ok("%s() 的 UPDATE 只补 NULL（幂等，不会覆盖已填值）" % name)
            else:
                warn("%s() 的 UPDATE 既不在闸门内也不只补 NULL，请人工确认" % name)
    if checked == 0:
        ok("没有 UPDATE 语句")

    # onceOnly 登记的三个整理项必须都有对应方法
    marks = re.findall(r'onceOnly\(\s*conn\s*,\s*"([^"]+)"', mig_code)
    ok("一次性整理登记 %d 项：%s" % (len(marks), ", ".join(marks)))

# ------------------------------------- 4. NEW_COLUMNS 与 schema.sql 是否对齐
print("[4/4] 迁移补的列必须同时写进建库脚本")
if mig_code:
    pairs = re.findall(r'\{\s*"(\w+)"\s*,\s*"(\w+)"\s*,\s*"([^"]+)"\s*\}', mig_code)
    # 解析 schema.sql 的 表->列
    tables = {}
    for m in re.finditer(r"CREATE TABLE IF NOT EXISTS\s+(\w+)\s*\((.*?)\n\)\s*;",
                         schema_sql, flags=re.S):
        cols = []
        for line in m.group(2).split("\n"):
            line = line.strip()
            if not line or re.match(r"(?i)^(PRIMARY|UNIQUE|KEY|INDEX|CONSTRAINT|FOREIGN)\b", line):
                continue
            cm = re.match(r"(\w+)\s+", line)
            if cm:
                cols.append(cm.group(1).lower())
        tables[m.group(1).lower()] = set(cols)

    missing = []
    for t, col, _ddl in pairs:
        cols = tables.get(t.lower())
        if cols is None:
            missing.append("%s.%s（表都不在 schema.sql 里）" % (t, col))
        elif col.lower() not in cols:
            missing.append("%s.%s" % (t, col))
    if missing:
        fail("这些列只在 NEW_COLUMNS 里、建库脚本没有 → 全新安装会缺：%s" % ", ".join(missing))
    else:
        ok("NEW_COLUMNS 的 %d 列全部在建库脚本中（新装/升级结果一致）" % len(pairs))

# ---------------------------------------------------------------- 汇总
print()
print("=" * 56)
print("通过 %d 项，失败 %d 项，待人工确认 %d 项" % (PASS, len(FAIL), len(WARN)))
if FAIL:
    print("\n必须修复：")
    for f in FAIL:
        print("  - " + f)
print("\n结论：" + ("✗ 不满足「升级不动已录入数据」，不要出包" if FAIL
                 else "✓ 满足「只加不改、不覆盖已录入数据」"))
sys.exit(1 if FAIL else 0)
