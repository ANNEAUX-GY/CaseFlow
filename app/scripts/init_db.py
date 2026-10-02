"""初始化 MySQL：建库 -> 建表 -> 建索引 -> 创建默认管理员。

用法：
    .venv\\Scripts\\python.exe scripts/init_db.py --host 127.0.0.1 --user root --password 123456
    .venv\\Scripts\\python.exe scripts/init_db.py --password 123456 --db case_flow
"""
import argparse
import getpass

import pymysql

from _common import SQL_DIR, schema_sql, split_statements

INDEX_SQL = (SQL_DIR / "index-mysql.sql").read_text(encoding="utf-8")


def run():
    ap = argparse.ArgumentParser(description="初始化 MySQL 数据库")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=3306)
    ap.add_argument("--user", default="root")
    ap.add_argument("--password", default=None,
                    help="不传则交互式输入；传空串表示无密码")
    ap.add_argument("--db", default="case_flow")
    ap.add_argument("--skip-index", action="store_true", help="跳过索引创建")
    args = ap.parse_args()

    # 注意：不能用 `args.password or getpass(...)`，空字符串是「无密码」而不是「没传」
    password = args.password if args.password is not None else getpass.getpass("MySQL 密码：")

    conn = pymysql.connect(host=args.host, port=args.port, user=args.user, password=password,
                           charset="utf8mb4", autocommit=True)
    try:
        with conn.cursor() as cur:
            cur.execute(f"CREATE DATABASE IF NOT EXISTS `{args.db}` "
                        f"DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
            print(f"[1/4] 数据库 `{args.db}` 就绪")
            cur.execute(f"USE `{args.db}`")

            n = 0
            for stmt in split_statements(schema_sql()):
                cur.execute(stmt)
                n += 1
            print(f"[2/4] 建表完成，共执行 {n} 条 DDL")

            if not args.skip_index:
                ok = 0
                for stmt in split_statements(INDEX_SQL):
                    try:
                        cur.execute(stmt)
                        ok += 1
                    except Exception as e:  # 索引重复创建时忽略
                        print(f"      跳过索引：{e}")
                print(f"[3/4] 索引创建完成（{ok} 条）")
            else:
                print("[3/4] 已跳过索引创建")

            cur.execute("SELECT COUNT(*) FROM sys_user")
            count = cur.fetchone()[0]
            if count == 0:
                cur.execute("INSERT INTO sys_user (username, password, display_name, role, status) "
                            "VALUES ('boss', 'admin123', '王总（大队长）', 'BOSS', 1)")
                print("[4/4] 已创建默认账号：boss / admin123")
            else:
                print(f"[4/4] 已存在 {count} 个账号，跳过初始化")
    finally:
        conn.close()
    print("\n完成。启动后端时请指定 MySQL 环境：SPRING_PROFILES_ACTIVE=mysql")


if __name__ == "__main__":
    run()
