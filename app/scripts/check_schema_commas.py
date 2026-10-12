"""体检 schema.sql：找出建表语句里漏逗号的行。

背景：sql/schema.sql 曾有一行少了一个逗号，导致 case_question 建表失败。
但 application.yml 里 spring.sql.init.continue-on-error=true 会把异常吞掉，
启动日志看不到任何报错，只有 H2 的 trace 文件（data/h2/*.trace.db）里有记录。

判定规则：每个 CREATE TABLE 块内，**除最后一行外**每一行都必须以逗号结尾。
末尾的 CONSTRAINT / UNIQUE KEY / CHECK 也同样受这条约束。
"""
import re
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "sql/schema.sql"

text = open(PATH, encoding="utf-8").read()
blocks = re.findall(
    r"CREATE TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+(\w+)\s*\((.*?)\n\);", text, re.S
)
print("共检查 %d 张表" % len(blocks))

problems = 0
for name, body in blocks:
    lines = [ln for ln in body.split("\n")]
    # 找出最后一个非空、非注释的有效行 —— 它允许不带逗号
    effective = [
        (i, ln.strip())
        for i, ln in enumerate(lines)
        if ln.strip() and not ln.strip().startswith("--")
    ]
    last_idx = effective[-1][0] if effective else -1

    for i, s in effective:
        if i == last_idx:
            continue  # 表内最后一行，带不带逗号都对
        if s.endswith(",") or s.endswith("("):
            continue
        problems += 1
        line_no = text[: text.index(body) + body.index(ln_orig)].count("\n") + 1 if False else None
        print("  [缺逗号] %-22s | %s" % (name, s[:64]))

if problems == 0:
    print("OK：所有列定义行格式正常")
else:
    print("发现 %d 处缺逗号" % problems)
    sys.exit(1)
