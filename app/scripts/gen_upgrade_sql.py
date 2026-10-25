# -*- coding: utf-8 -*-
"""从 SchemaMigration.NEW_COLUMNS 生成「已有库升级补列」SQL（幂等，可重复执行）。

为什么要有这个脚本：内网 DBA 有时不放心"程序自己改表"，要一份能先审、能手工跑的
SQL。手工维护这份 SQL 迟早和代码不一致，所以每次出包都从源码现生一份。

产出：
    app/sql/upgrade/4-升级补列（已有库执行）.sql
    app/sql/upgrade/4-说明（结构变更）.txt   （只生成骨架，人话说明在 CHANGELOG 里维护）

用法：
    python scripts/gen_upgrade_sql.py
"""
import io
import os
import re
import sys
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIGRATION = os.path.join(ROOT, 'backend', 'src', 'main', 'java', 'com', 'caseflow',
                         'bootstrap', 'SchemaMigration.java')
SCHEMA = os.path.join(ROOT, 'sql', 'schema.sql')
OUT_DIR = os.path.join(ROOT, 'sql', 'upgrade')
CHANGELOG = os.path.join(OUT_DIR, '结构变更记录.md')

ENTRY_RE = re.compile(r'\{"([a-z_0-9]+)"\s*,\s*"([a-z_0-9]+)"\s*,\s*"([^"]+)"\}')


def parse_new_columns():
    """读 NEW_COLUMNS 三元组：表名 / 列名 / 类型定义"""
    src = io.open(MIGRATION, encoding='utf-8').read()
    start = src.index('NEW_COLUMNS = {')
    end = src.index('};', start)
    body = src[start:end]
    out = []
    for m in ENTRY_RE.finditer(body):
        table, col, ddl = m.group(1), m.group(2), m.group(3).strip()
        if (table, col) not in [(t, c) for t, c, _ in out]:
            out.append((table, col, ddl))
    return out


def parse_index_columns():
    """读 schema.sql 里每张表的列定义，用于拿 COMMENT 与判断列是否真存在"""
    text = io.open(SCHEMA, encoding='utf-8').read()
    tables = {}
    for m in re.finditer(r'CREATE TABLE IF NOT EXISTS\s+([a-z_]+)\s*\((.*?)\n\);', text, re.S):
        name, body = m.group(1), m.group(2)
        cols = {}
        for line in body.splitlines():
            line = line.strip()
            if not line or line.startswith('--'):
                continue
            cm = re.match(r'([a-z_0-9]+)\s+', line)
            if not cm:
                continue
            col = cm.group(1)
            if col.upper() in ('PRIMARY', 'UNIQUE', 'KEY', 'INDEX', 'CONSTRAINT'):
                continue
            comment = ''
            cmt = re.search(r"COMMENT\s+'([^']*)'", line)
            if cmt:
                comment = cmt.group(1)
            cols[col] = comment
        tables[name] = cols
    return tables


def build_sql(entries, schema_cols):
    ts = datetime.now().strftime('%Y-%m-%d %H:%M')
    lines = [
        '-- ============================================================',
        '--  CaseFlow 已有库升级补列脚本（幂等，可重复执行）',
        '-- ------------------------------------------------------------',
        f'--  生成时间：{ts}',
        '--  来源：backend/.../bootstrap/SchemaMigration.java 的 NEW_COLUMNS（%d 列）' % len(entries),
        '-- ------------------------------------------------------------',
        '--  【这段脚本做什么】',
        '--  把旧版本的 case_flow 库补齐到与当前版本一致：缺哪列补哪列。',
        '--  每列先查 information_schema，已存在就跳过，所以**重复执行完全安全**。',
        '--',
        '--  【这段脚本绝不做什么】',
        '--  · 不 DROP、不 TRUNCATE、不改列类型、不改列名、不改表名',
        '--  · 不 UPDATE 任何数据行，不写任何业务值',
        '--  · 不碰 case_flow 以外的库，不碰系统表',
        '--  新增列全部可空或带默认值，老数据行直接通过，不需要任何数据转换。',
        '--',
        '--  【怎么执行】',
        '--  方式一（推荐）：不用执行本文件。直接换 jar 启动，程序会自动补列，',
        '--                  效果与执行本文件完全相同（日志里会逐条打印"已补列"）。',
        '--  方式二（DBA 手工）：先备份，再执行',
        '--        mysql -u caseflow -p case_flow < "4-升级补列（已有库执行）.sql"',
        '--      或把本文件内容整个粘到 Navicat / DBeaver 里跑。',
        '--',
        '--  【执行后怎么确认】',
        '--  1) 本脚本每段执行成功不会有输出，报错会直接显示；',
        '--  2) 抽查：SELECT COUNT(*) FROM case_info; 行数与执行前一致；',
        '--  3) 抽查新列：SELECT case_no, case_time FROM case_info LIMIT 5;',
        '--     老案件 case_time 可能为空（正常，程序首次启动会按受案时间回填）。',
        '-- ============================================================',
        '',
        'SET NAMES utf8mb4;',
        '',
    ]

    cur_table = None
    for table, col, ddl in entries:
        if table != cur_table:
            comment = (schema_cols.get(table, {}) or {}).get('__table__', '')
            lines.append('')
            lines.append('-- ------------------------------------------------------------')
            lines.append('--  表：%s%s' % (table, ('  ' + comment) if comment else ''))
            lines.append('-- ------------------------------------------------------------')
            cur_table = table
        comment = (schema_cols.get(table) or {}).get(col, '')
        # 注意转义层次：先构造"正常 SQL"（COMMENT '文案'），再把整句塞进
        # SET @ddl := IF(..., '<这里>', 'DO 0') 的字符串里，所以要再双写一次单引号。
        # 少写一层就会生成 COMMENT 案卷类型...（引号丢失，MySQL 直接语法错）。
        if comment:
            body = "ALTER TABLE `%s` ADD COLUMN `%s` %s COMMENT '%s'" % (
                table, col, ddl, comment.replace("'", "''"))
        else:
            body = "ALTER TABLE `%s` ADD COLUMN `%s` %s" % (table, col, ddl)
        add_clause = body.replace("'", "''")
        lines += [
            '-- %s.%s%s' % (table, col, ('  ' + comment) if comment else ''),
            "SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS",
            "  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '%s' AND COLUMN_NAME = '%s');" % (table, col),
            "SET @ddl := IF(@exists = 0, '%s', 'DO 0');" % add_clause,
            'PREPARE cf_stmt FROM @ddl; EXECUTE cf_stmt; DEALLOCATE PREPARE cf_stmt;',
            '',
        ]

    lines += [
        '-- ============================================================',
        '--  补列完成。',
        '--  若程序随后启动，日志里"已补列"应当一条都不出现（因为列已经在）。',
        '-- ============================================================',
    ]
    return '\n'.join(lines) + '\n'


def read_latest_changelog():
    """取《结构变更记录.md》里最新一节（人写的"本次改了什么、为什么"）"""
    if not os.path.exists(CHANGELOG):
        return ''
    text = io.open(CHANGELOG, encoding='utf-8').read()
    parts = re.split(r'^## ', text, flags=re.M)
    for part in reversed(parts[1:]):
        if part.strip():
            return part.strip()
    return ''


def build_readme(entries):
    ts = datetime.now().strftime('%Y-%m-%d %H:%M')
    by_table = {}
    for table, col, ddl in entries:
        by_table.setdefault(table, []).append((col, ddl))
    lines = [
        '本次升级涉及的结构变更说明',
        '=' * 62,
        '',
        '生成时间：' + ts,
        '',
        '一句话结论',
        '-' * 62,
        '只加字段、只加一张内部台账表；没有删字段、没有改字段类型、没有改表名。',
        '内网已经录入的案件、嫌疑人、意见、待办、附件一个字节都不会动。',
        '',
        '一、本次新增了什么（人话版）',
        '-' * 62,
    ]
    _latest = read_latest_changelog()
    if _latest:
        for _ln in _latest.splitlines():
            lines.append(('  ' + _ln.rstrip()) if _ln.strip() else '')
    else:
        lines.append('  （未维护《结构变更记录.md》）')
    lines += [
        '',
        '二、补列脚本覆盖的全部字段（%d 项，含历史各版本）' % len(entries),
        '-' * 62,
        '  清单是「从最初版本到当前版本」所有后来补的字段：',
        '  对老库执行 = 缺的补上、已有的跳过；对新版本库执行 = 全部跳过。',
        '  它不会凭空加出当前版本里本来没有的东西。',
        '',
    ]
    for table in sorted(by_table):
        lines.append('  %s' % table)
        for col, ddl in by_table[table]:
            lines.append('      + %-22s %s' % (col, ddl))
        lines.append('')
    lines += [
        '三、新增字段会不会让老数据出问题',
        '-' * 62,
        '  不会。所有新增字段都允许为空（或带默认值），老数据行不需要任何转换。',
        '  程序读不到值时按"未填写"处理，界面显示空或"—"，不会报错。',
        '',
        '四、我要怎么升级（两种方式，任选其一）',
        '-' * 62,
        '  方式一（推荐，最省事）：',
        '      1) 停掉正在运行的服务；',
        '      2) 用本包的 case-flow-backend.jar 覆盖原来的同名文件；',
        '      3) 重新启动。程序启动时会自动补齐上面这些字段，日志里会逐条打印',
        '         "[SchemaMigration] 已补列：表名.列名"。',
        '',
        '  方式二（DBA 想先审 SQL）：',
        '      1) 先备份数据库（mysqldump 或整库快照）；',
        '      2) 执行同目录下的《4-升级补列（已有库执行）.sql》；',
        '      3) 再换 jar 启动。程序启动时发现字段都在，不会重复添加。',
        '',
        '五、怎么确认"内网数据没被动"',
        '-' * 62,
        '  1) 升级前后各查一次：SELECT COUNT(*) FROM case_info;',
        '     两个数字必须一样。',
        '  2) 随便打开几件老案件，看名称、嫌疑人、意见、待办是否原样。',
        '  3) 启动日志里不应出现任何 ERROR；出现少量 WARN 属于数据整理提示，',
        '     不影响原有数据。',
        '',
        '六、这次升级绝不会发生的事',
        '-' * 62,
        '  · 不会删表、不会删字段、不会清空任何一张表；',
        '  · 不会改字段类型、不会改表名、不会重建表；',
        '  · 不会把已填的内容改成别的值（回填只补空值，已填的一律不覆盖）；',
        '  · 不会动 case_flow 以外的库，不碰系统表。',
        '',
        '  需要提醒的一点：本次升级的"一次性数据整理"共有 3 项，',
        '  每个数据库只会执行一次，执行完写入内部台账表 schema_migration_mark，',
        '  以后每次启动都会跳过，不会反复改动数据。',
        '',
    ]
    return '\n'.join(lines) + '\n'


def self_check(sql, entries):
    """生成即校验：把能靠肉眼漏掉的语法/安全问题挡在这里。

    本机没有 MySQL/Docker 可实测，所以用静态检查兜住最容易出错的几类：
    引号配对（COMMENT 文案里带单引号会炸）、段落数是否等于列数、
    语句是否完整、有没有混进删改语句。
    """
    problems = []
    lines = sql.splitlines()

    # 1) 段落数必须等于列数（少一段就少补一列，且不会有任何报错）
    seg = sum(1 for ln in lines if ln.startswith('PREPARE cf_stmt FROM @ddl;'))
    if seg != len(entries):
        problems.append('补列段落数 %d ≠ 列数 %d' % (seg, len(entries)))

    # 2) 每条 SET @ddl 行的单引号必须是偶数（奇数 = 引号丢失或被吞，MySQL 必语法错）
    for i, ln in enumerate(lines, 1):
        if ln.startswith('SET @ddl := IF('):
            if ln.count("'") % 2 != 0:
                problems.append('第 %d 行单引号不成对：%s' % (i, ln[:80]))
            if 'ADD COLUMN' not in ln:
                problems.append('第 %d 行不是 ADD COLUMN' % i)

    # 3) 每段四件套齐全（@exists → @ddl → PREPARE/EXECUTE/DEALLOCATE）
    n_exists = sum(1 for ln in lines if ln.startswith('SET @exists := '))
    n_exec = sum(1 for ln in lines if 'EXECUTE cf_stmt;' in ln)
    if not (n_exists == n_exec == seg):
        problems.append('四件套数量不一致：exists=%d exec=%d prepare=%d' % (n_exists, n_exec, seg))

    # 4) 只许出现 ADD COLUMN，任何删改关键字都算失败
    body = '\n'.join(ln for ln in lines if not ln.lstrip().startswith('--'))
    for bad in ('DROP ', 'TRUNCATE', 'MODIFY COLUMN', 'CHANGE COLUMN', 'RENAME ',
                'DELETE FROM', 'UPDATE ', 'INSERT INTO'):
        if bad in body.upper():
            problems.append('出现禁止语句：%s' % bad.strip())

    return problems


def main():
    try:
        entries = parse_new_columns()
    except ValueError:
        print('[错误] 在 SchemaMigration.java 里找不到 NEW_COLUMNS = {...}')
        return 1
    if not entries:
        print('[错误] NEW_COLUMNS 解析为空，检查格式是否变了')
        return 1

    schema_cols = parse_index_columns()
    os.makedirs(OUT_DIR, exist_ok=True)

    sql = build_sql(entries, schema_cols)
    sql_path = os.path.join(OUT_DIR, '4-升级补列（已有库执行）.sql')
    io.open(sql_path, 'w', encoding='utf-8', newline='\r\n').write(sql)

    readme = build_readme(entries)
    md_path = os.path.join(OUT_DIR, '4-说明（结构变更）.txt')
    io.open(md_path, 'w', encoding='utf-8-sig', newline='\r\n').write(readme)

    problems = self_check(sql, entries)
    if problems:
        print('[自检失败] 生成的 SQL 有问题，不要打包给内网：')
        for p2 in problems[:12]:
            print('   -', p2)
        return 3
    print('自检：段落数/引号配对/四件套/无删改语句 全部通过 ✓')

    missing = [(t, c) for t, c, _ in entries if c not in (schema_cols.get(t) or {})]
    print('已生成：')
    print('  ', sql_path, '(%.1f KB)' % (os.path.getsize(sql_path) / 1024))
    print('  ', md_path, '(%.1f KB)' % (os.path.getsize(md_path) / 1024))
    print('补列条目 %d 个，涉及 %d 张表' % (len(entries), len({t for t, _, _ in entries})))
    if missing:
        print('[警告] 以下列在 schema.sql 里找不到，说明代码与建库脚本不一致：')
        for t, c in missing:
            print('    ', t, c)
        return 2
    print('校验：全部列都能在 schema.sql 里找到 ✓')
    return 0


if __name__ == '__main__':
    sys.exit(main())
