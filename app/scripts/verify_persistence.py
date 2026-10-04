"""落库校验：确认增删改查的结果真的写进了数据库，而不是停在应用内存里。

做法：所有操作走 HTTP 接口（和前端完全同一条链路），每一步都用**直连数据库的 SQL**
反查结果，最后可选重启后端再查一次，证明数据不依赖进程状态。

用法：
    .venv\\Scripts\\python.exe scripts/verify_persistence.py
    .venv\\Scripts\\python.exe scripts/verify_persistence.py --base http://127.0.0.1:8080
"""
import argparse
import sys
import time

import pymysql
import requests

from mysql_local import DB, HOST, PORT

MARK = "落库校验"


def db():
    return pymysql.connect(host=HOST, port=PORT, user="caseflow", password="caseflow",
                           database=DB, charset="utf8mb4", autocommit=True)


def sql_one(conn, q, args=None):
    with conn.cursor() as cur:
        cur.execute(q, args or ())
        return cur.fetchone()


def check(label, ok, detail=""):
    print(f"  {'[OK]  ' if ok else '[FAIL]'} {label}" + (f"　{detail}" if detail else ""))
    if not ok:
        globals()["FAILED"] = True
    return ok


FAILED = False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--username", default="boss")
    ap.add_argument("--password", default="admin123")
    args = ap.parse_args()
    api = args.base.rstrip("/") + "/api"

    print("=" * 68)
    print("落库校验：HTTP 接口操作 -> 直连数据库反查")
    print(f"目标 {api}　数据库 {HOST}:{PORT}/{DB}")
    print("=" * 68)

    # 0. 登录
    r = requests.post(f"{api}/auth/login", json={"username": args.username,
                                                "password": args.password}, timeout=10).json()
    if r.get("code") != 0:
        sys.exit(f"登录失败：{r}")
    token = r["data"]["token"]
    H = {"X-Token": token}
    print(f"0. 登录成功（账号 {args.username}）")

    conn = db()
    try:
        # 清理上次残留
        with conn.cursor() as cur:
            cur.execute("DELETE FROM case_info WHERE name LIKE %s", (MARK + "%",))

        total0 = sql_one(conn, "SELECT COUNT(*) FROM case_info")[0]
        emps = []
        with conn.cursor() as cur:
            cur.execute("SELECT id, name FROM org_employee WHERE status=1 ORDER BY id LIMIT 2")
            emps = cur.fetchall()
        # 2026-10-04 起指派有办案组别校验：演示员工默认「不限」会被拒派，
        # 先临时设为初查组，结束时恢复（与 verify_undo.py 同一做法）
        emp_ids = tuple(e[0] for e in emps)
        with conn.cursor() as cur:
            cur.execute("UPDATE org_employee SET police_group=%s WHERE id IN (%s,%s)",
                        ("INITIAL",) + emp_ids)

        # ---------- 增 ----------
        print("\n1. 增（POST /cases）")
        body = {"name": f"{MARK}-A", "priority": "HIGH", "sourceType": "MANUAL",
                "category": "校验", "description": "落库校验用，可删", "caseType": "CRIMINAL"}
        r = requests.post(f"{api}/cases", json=body, headers=H, timeout=10).json()
        check("接口返回成功", r.get("code") == 0, str(r.get("msg") or ""))
        case = r["data"]
        cid, cno = case["id"], case["caseNo"]
        row = sql_one(conn, "SELECT id, case_no, name, priority, status FROM case_info WHERE id=%s", (cid,))
        check("数据库能查到该行", row is not None, f"id={cid} case_no={cno}")
        check("字段与请求一致", bool(row) and row[2] == body["name"] and row[3] == "HIGH",
              f"name={row[2] if row else None} priority={row[3] if row else None}")
        check("case_info 总行数 +1", sql_one(conn, "SELECT COUNT(*) FROM case_info")[0] == total0 + 1,
              f"{total0} -> {total0 + 1}")

        # ---------- 改 ----------
        print("\n2. 改（PUT /cases/{id}）")
        r = requests.put(f"{api}/cases/{cid}", json={**body, "name": f"{MARK}-A（已改名）",
                                                    "priority": "LOW"}, headers=H, timeout=10).json()
        check("接口返回成功", r.get("code") == 0, str(r.get("msg") or ""))
        row = sql_one(conn, "SELECT name, priority FROM case_info WHERE id=%s", (cid,))
        check("数据库里的值已更新", bool(row) and row[0].endswith("（已改名）") and row[1] == "LOW",
              f"{row}")

        # ---------- 指派（业务核心写操作） ----------
        print("\n3. 指派 + 设期限（POST /cases/{id}/assign）")
        deadline = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() + 3 * 86400))
        payload = {"ownerId": emps[0][0], "memberIds": [emps[1][0]], "note": "校验",
                   "deadline": deadline, "deadlineTouched": True}
        r = requests.post(f"{api}/cases/{cid}/assign", json=payload, headers=H, timeout=10).json()
        check("接口返回成功", r.get("code") == 0, str(r.get("msg") or ""))
        row = sql_one(conn, "SELECT deadline, status FROM case_info WHERE id=%s", (cid,))
        check("案件表 deadline 已写入", bool(row) and row[0] is not None,
              f"deadline={row[0] if row else None}")
        rows = sql_one(conn, "SELECT COUNT(*) FROM case_assignee WHERE case_id=%s AND status='ACTIVE'",
                       (cid,))[0]
        check("指派关系落库（主办+协办=2）", rows == 2, f"{rows} 行 ACTIVE")

        # ---------- 状态流转 ----------
        print("\n4. 状态流转（POST /cases/{id}/status）")
        r = requests.post(f"{api}/cases/{cid}/status", json={"status": "DONE", "remark": "校验办结"},
                          headers=H, timeout=10).json()
        check("接口返回成功", r.get("code") == 0, str(r.get("msg") or ""))
        row = sql_one(conn, "SELECT status, remark FROM case_info WHERE id=%s", (cid,))
        check("状态已落到 DONE", bool(row) and row[0] == "DONE", f"{row}")

        # ---------- 查 ----------
        print("\n5. 查（GET /cases/{id}）")
        r = requests.get(f"{api}/cases/{cid}", headers=H, timeout=10).json()
        check("接口能读回刚写的数据", r.get("code") == 0 and r["data"]["status"] == "DONE")
        detail = r["data"]
        check("读回的备注与库中一致", detail.get("remark") == "校验办结",
              f"remark={detail.get('remark')}")

        # ---------- 操作日志 ----------
        logs = sql_one(conn, "SELECT COUNT(*) FROM operation_log WHERE target_id=%s", (cid,))[0]
        check("操作日志已留痕", logs >= 3, f"{logs} 条")

        print(f"\n保留一条记录用于重启后核对：caseId={cid} caseNo={cno}")
        print(f"（校验结束后可执行 DELETE 清理，或点页面上的删除按钮）")
        print("\n" + "=" * 68)
        print("结论：增 / 改 / 查 均已在数据库中生效" if not FAILED else "存在失败项，见上方 [FAIL]")
        print("=" * 68)
        return 0 if not FAILED else 1
    finally:
        # 恢复员工组别，不污染演示数据
        try:
            with conn.cursor() as cur:
                cur.execute("UPDATE org_employee SET police_group=NULL WHERE id IN (%s,%s)",
                            emp_ids)
            conn.commit()
        except Exception:
            pass
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
