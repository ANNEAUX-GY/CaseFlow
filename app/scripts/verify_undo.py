"""撤回（Ctrl+Z）校验：每一步都走 HTTP 接口，再用直连 SQL 反查数据是否精确还原。

覆盖：撤回新建 / 撤回删除（同 id 还原）/ 撤回指派与改期限 / 撤回本身可再撤回（重做）
      / 「只能撤回该案件最新一步」的安全规则 / 非案件操作不可撤回。

用法：
    .venv\\Scripts\\python.exe scripts/verify_undo.py
"""
import argparse
import json
import sys
import time

import pymysql
import requests

from mysql_local import DB, HOST, PORT

MARK = "撤回校验"
FAILED = False


def db():
    return pymysql.connect(host=HOST, port=PORT, user="caseflow", password="caseflow",
                           database=DB, charset="utf8mb4", autocommit=True)


def one(conn, q, args=None):
    with conn.cursor() as cur:
        cur.execute(q, args or ())
        return cur.fetchone()


def allr(conn, q, args=None):
    with conn.cursor() as cur:
        cur.execute(q, args or ())
        return cur.fetchall()


def check(label, ok, detail=""):
    print(f"  {'[OK]  ' if ok else '[FAIL]'} {label}" + (f"　{detail}" if detail else ""))
    if not ok:
        globals()["FAILED"] = True
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--username", default="boss")
    ap.add_argument("--password", default="admin123")
    args = ap.parse_args()
    api = args.base.rstrip("/") + "/api"

    print("=" * 70)
    print("撤回校验：HTTP 操作 -> 直连 SQL 反查是否精确还原")
    print(f"目标 {api}　数据库 {HOST}:{PORT}/{DB}")
    print("=" * 70)

    r = requests.post(f"{api}/auth/login", json={"username": args.username,
                                                "password": args.password}, timeout=10).json()
    if r.get("code") != 0:
        sys.exit(f"登录失败：{r}")
    H = {"X-Token": r["data"]["token"]}
    print(f"0. 登录成功（账号 {args.username}）")

    conn = db()

    def log_of(case_id, action):
        return one(conn, "SELECT id, action, content, undone, snapshot_before, snapshot_after "
                         "FROM operation_log WHERE target_id=%s AND action=%s "
                         "ORDER BY id DESC LIMIT 1", (case_id, action))

    def latest_log(case_id):
        return one(conn, "SELECT id, action, content, undone FROM operation_log "
                         "WHERE target_id=%s ORDER BY id DESC LIMIT 1", (case_id,))

    def undo(log_id):
        return requests.post(f"{api}/logs/{log_id}/undo", headers=H, timeout=15).json()

    def detail(log_id):
        return requests.get(f"{api}/logs/{log_id}", headers=H, timeout=15).json()

    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM case_info WHERE name LIKE %s", (MARK + "%",))
            cur.execute("DELETE FROM operation_log WHERE target_id IN "
                        "(SELECT id FROM case_info WHERE name LIKE %s)", (MARK + "%",))
        emps = allr(conn, "SELECT id, name FROM org_employee WHERE status=1 ORDER BY id LIMIT 3")
        if len(emps) < 3:
            sys.exit("演示数据不足：至少需要 3 名在职员工，请先跑 scripts/seed_demo.py")
        e1, e2, e3 = emps[0][0], emps[1][0], emps[2][0]
        print(f"   在职员工：{emps[0][1]}({e1}) / {emps[1][1]}({e2}) / {emps[2][1]}({e3})")

        # ================= 1. 撤回「新建」 =================
        print("\n1. 撤回「新建」——应当把案件整个撤掉")
        r = requests.post(f"{api}/cases", json={"name": f"{MARK}-新建", "priority": "NORMAL",
                                               "sourceType": "MANUAL", "caseType": "CRIMINAL"}, headers=H, timeout=10).json()
        check("建案接口成功", r.get("code") == 0, str(r.get("msg") or ""))
        cid = r["data"]["id"]
        create_log = log_of(cid, "CREATE")
        check("留下 CREATE 日志", create_log is not None, f"logId={create_log[0] if create_log else None}")
        sb = create_log[4] or ""
        check("before 快照标记为「不存在」", '"exists":false' in sb.replace(" ", ""), sb[:60])
        check("after 快照带上了案件本体", f'"id":{cid}' in (create_log[5] or "").replace(" ", ""))

        d = detail(create_log[0])
        check("详情接口返回变更明细", d.get("code") == 0 and isinstance(d["data"].get("changes"), list))
        labels = [c["label"] for c in d["data"]["changes"]]
        check("明细里含案件名称/优先级", "案件名称" in labels and "优先级" in labels, str(labels))
        check("列表里标记为可撤回", d["data"].get("undoable") is True, str(d["data"].get("undoHint")))

        r = undo(create_log[0])
        check("撤回接口成功", r.get("code") == 0, str(r.get("msg") or ""))
        check("数据库里案件已消失", one(conn, "SELECT COUNT(*) FROM case_info WHERE id=%s", (cid,))[0] == 0)
        row = one(conn, "SELECT undone, undo_log_id FROM operation_log WHERE id=%s", (create_log[0],))
        check("原日志被标记为已撤回", row[0] == 1 and row[1] is not None, f"undone={row[0]} undoLogId={row[1]}")

        # ================= 2. 撤回「撤回」= 重做 =================
        print("\n2. 再撤回一步（撤回那条撤回）——等价于重做，案件应当以同一个 id 回来")
        undo_log_id = row[1]
        r = undo(undo_log_id)
        check("撤回接口成功", r.get("code") == 0, str(r.get("msg") or ""))
        back = one(conn, "SELECT id, case_no, name FROM case_info WHERE id=%s", (cid,))
        check("案件以原 id 还原", back is not None, f"{back}")
        check("案件编号未变", bool(back) and back[1] == r["data"].get("caseNo", back[1]))

        # ================= 3. 撤回「指派 + 改期限」 =================
        print("\n3. 指派 + 改期限后撤回——期限与承办人应精确回到操作前")
        before_deadline = one(conn, "SELECT deadline FROM case_info WHERE id=%s", (cid,))[0]
        deadline = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() + 5 * 86400))
        r = requests.post(f"{api}/cases/{cid}/assign",
                          json={"ownerId": e1, "memberIds": [e2, e3], "note": "撤回校验",
                                "deadline": deadline, "deadlineTouched": True},
                          headers=H, timeout=10).json()
        check("指派接口成功", r.get("code") == 0, str(r.get("msg") or ""))
        after_dl = one(conn, "SELECT deadline FROM case_info WHERE id=%s", (cid,))[0]
        check("期限已改为新值", str(after_dl) != str(before_deadline), f"{before_deadline} -> {after_dl}")
        active = allr(conn, "SELECT id, employee_id, assign_role FROM case_assignee "
                            "WHERE case_id=%s AND status='ACTIVE' ORDER BY id", (cid,))
        check("3 名承办人已落库", len(active) == 3, f"{active}")

        assign_log = log_of(cid, "ASSIGN")
        d = detail(assign_log[0])
        chg = {c["label"]: (c["before"], c["after"]) for c in d["data"]["changes"]}
        check("明细里能看出期限变化", "截止期限" in chg, str(list(chg.keys())))
        check("明细里能看出主办人", chg.get("主办人", (None, None))[1] == emps[0][1],
              str(chg.get("主办人")))
        check("明细里能看出协办人", "、" in (chg.get("协办人", (None, None))[1] or ""),
              str(chg.get("协办人")))

        r = undo(assign_log[0])
        check("撤回接口成功", r.get("code") == 0, str(r.get("msg") or ""))
        restored_dl = one(conn, "SELECT deadline FROM case_info WHERE id=%s", (cid,))[0]
        check("期限回到操作前的值", str(restored_dl) == str(before_deadline),
              f"{after_dl} -> {restored_dl}（期望 {before_deadline}）")
        left = one(conn, "SELECT COUNT(*) FROM case_assignee WHERE case_id=%s AND status='ACTIVE'",
                   (cid,))[0]
        check("之前没有承办人，撤回后应为 0", left == 0, f"{left} 行 ACTIVE")

        # ================= 4. 安全规则：只能撤回最新一步 =================
        print("\n4. 安全规则——同一案件有更晚操作时，撤回较早那条应被拒绝")
        requests.post(f"{api}/cases/{cid}/assign",
                      json={"ownerId": e1, "memberIds": [], "note": "规则校验"},
                      headers=H, timeout=10)
        first_log = one(conn, "SELECT id FROM operation_log WHERE target_id=%s AND action='ASSIGN' "
                              "ORDER BY id DESC LIMIT 1", (cid,))[0]
        requests.post(f"{api}/cases/{cid}/status", json={"status": "IN_PROGRESS"},
                      headers=H, timeout=10)
        r = undo(first_log)
        check("撤回较早那条被拒绝", r.get("code") != 0, str(r.get("msg") or ""))
        d = detail(first_log)
        check("详情里给出不可撤回的原因", d["data"].get("undoable") is False,
              str(d["data"].get("undoHint")))

        latest = latest_log(cid)
        r = undo(latest[0])
        check("撤回最新那条成功", r.get("code") == 0, f"{latest[1]} -> {str(r.get('msg') or 'ok')}")

        # ================= 5. 撤回「删除」 =================
        print("\n5. 删除案件后撤回——案件、指派关系、附件归属都应原样回来")
        requests.post(f"{api}/cases/{cid}/assign",
                      json={"ownerId": e2, "memberIds": [e3], "note": "删除前留痕",
                            "deadline": deadline, "deadlineTouched": True},
                      headers=H, timeout=10)
        snap_case = one(conn, "SELECT case_no, name, deadline, status FROM case_info WHERE id=%s", (cid,))
        snap_assignees = allr(conn, "SELECT id, employee_id, assign_role, status FROM case_assignee "
                                    "WHERE case_id=%s ORDER BY id", (cid,))
        r = requests.delete(f"{api}/cases/{cid}", headers=H, timeout=10).json()
        check("删除接口成功", r.get("code") == 0, str(r.get("msg") or ""))
        check("案件确实没了", one(conn, "SELECT COUNT(*) FROM case_info WHERE id=%s", (cid,))[0] == 0)
        del_log = log_of(cid, "DELETE")
        check("删除也留了快照", del_log is not None and del_log[4] is not None)
        d = detail(del_log[0])
        check("详情仍能说出案件名称（取自快照）", d["data"].get("caseName") == snap_case[1],
              f"caseName={d['data'].get('caseName')} caseExists={d['data'].get('caseExists')}")

        r = undo(del_log[0])
        check("撤回删除成功", r.get("code") == 0, str(r.get("msg") or ""))
        after = one(conn, "SELECT case_no, name, deadline, status FROM case_info WHERE id=%s", (cid,))
        check("案件以同一个 id 还原", after is not None, f"id={cid}")
        check("字段与删除前完全一致", after == snap_case, f"{snap_case} vs {after}")
        restored = allr(conn, "SELECT id, employee_id, assign_role, status FROM case_assignee "
                              "WHERE case_id=%s ORDER BY id", (cid,))
        check("指派关系（含改派历史）逐行还原", restored == snap_assignees,
              f"{len(snap_assignees)} 行 -> {len(restored)} 行")

        # ================= 6. 非案件操作不可撤回 =================
        print("\n6. 登录等非案件操作不可撤回")
        login_log = one(conn, "SELECT id FROM operation_log WHERE module='AUTH' AND action='LOGIN' "
                              "ORDER BY id DESC LIMIT 1")
        if login_log:
            d = detail(login_log[0])
            check("登录日志标记为不可撤回", d["data"].get("undoable") is False,
                  str(d["data"].get("undoHint")))
            r = undo(login_log[0])
            check("撤回登录被拒绝", r.get("code") != 0, str(r.get("msg") or ""))

        # ================= 7. 工作台接口带齐了字段 =================
        print("\n7. 工作台「最近操作」数据完整性")
        dash = requests.get(f"{api}/cases/dashboard", headers=H, timeout=15).json()
        logs = dash["data"].get("recentLogs") or []
        check("返回了操作记录", len(logs) > 0, f"{len(logs)} 条")
        need = {"actionName", "createdAtText", "undoable", "undone"}
        miss = sorted(need - set(logs[0].keys())) if logs else sorted(need)
        check("每条都带动作名/时间/可否撤回", not miss, f"缺失字段={miss}")
        check("至少一条可撤回", any(l.get("undoable") for l in logs),
              f"可撤回 {sum(1 for l in logs if l.get('undoable'))} 条")

        # ================= 收尾：按 id 逆序撤回，恢复现场 =================
        print("\n8. 收尾：用 Ctrl+Z 的方式连续撤回，把校验产生的内容清干净")
        r = requests.post(f"{api}/logs/undo-latest", headers=H, timeout=15).json()
        check("撤回上一步接口可用", r.get("code") == 0, str(r.get("msg") or ""))

        print("\n" + "=" * 70)
        print("结论：撤回链路每一步都真实落库，且能精确还原" if not FAILED else "存在失败项，见上方 [FAIL]")
        print("=" * 70)
        return 0 if not FAILED else 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
