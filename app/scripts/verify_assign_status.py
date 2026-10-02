"""案件状态推进与状态写入约束自检。

覆盖的规则：
  1. 新建案件 = 待指派；指派后 = 已指派
  2. 没有承办人，不允许把案件置为「办理中 / 已办结」
  3. 建案 / 编辑接口不能把状态直接设成办理中或终态（须走状态流转接口）
  4. 编辑案件（表单未提交 status）不得改变现有状态，也不得丢掉承办人
  5. 有承办人的案件改派，不把「办理中」回退
  6. 已办结 / 已撤销的案件不能再指派；非法状态值被拒
  7. 存量数据里不存在「无承办人却处于办理中/已办结」

用法（先启动后端）：
    python scripts/verify_assign_status.py
    python scripts/verify_assign_status.py --base http://127.0.0.1:8080/api

脚本自建临时案件并在结束时删除，不留残留。仅用标准库。
"""
import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

OK, FAIL = [], []


def check(name, cond, detail=""):
    (OK if cond else FAIL).append((name, detail))
    print(("  [OK]   " if cond else "  [FAIL] ") + name + (("  -> " + detail) if detail else ""))


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        if not self.base.endswith("/api"):
            self.base += "/api"
        self.token = None

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data:
            req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("X-Token", self.token)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            try:
                return json.loads(raw)
            except Exception:
                return {"code": e.code, "msg": raw[:200]}

    def get(self, path, params=None):
        if params:
            path += "?" + urllib.parse.urlencode(params)
        return self.call("GET", path)

    def post(self, path, body=None):
        return self.call("POST", path, body or {})

    def put(self, path, body=None):
        return self.call("PUT", path, body or {})

    def delete(self, path):
        return self.call("DELETE", path)

    def login(self, u, p):
        r = self.post("/auth/login", {"username": u, "password": p})
        if r.get("code") != 0:
            raise SystemExit("登录失败：" + str(r.get("msg")))
        self.token = r["data"]["token"]

    def status_of(self, case_id):
        d = self.get(f"/cases/{case_id}")["data"]
        return d["status"], d.get("owner") or {}


def flatten_employees(nodes):
    out = []
    for n in nodes or []:
        out.append(n)
        out.extend(flatten_employees(n.get("children")))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--username", default="boss")
    ap.add_argument("--password", default="admin123")
    args = ap.parse_args()

    a = Api(args.base)
    a.login(args.username, args.password)
    print("[1] 登录成功")

    emps = flatten_employees(a.get("/employees/search", params={"limit": 1000})["data"])
    if not emps:
        raise SystemExit("没有可用员工，请先导入员工图谱")
    emp = emps[0]
    other = emps[1] if len(emps) > 1 else emps[0]
    print(f"    员工：{emp['name']}(id={emp['id']})，改派用：{other['name']}(id={other['id']})")

    created = []
    try:
        print("\n[2] 新建案件：应为「待指派」且无承办人")
        r = a.post("/cases", {"name": "自检-指派状态-主流程", "sourceType": "MANUAL",
                              "priority": "NORMAL", "caseType": "PRELIMINARY"})
        cid = (r.get("data") or {}).get("id")
        if not cid:
            raise SystemExit("创建案件失败：" + str(r)[:200])
        created.append(cid)
        st, owner = a.status_of(cid)
        check("新建后状态 = PENDING_ASSIGN", st == "PENDING_ASSIGN", f"实际 {st}")
        check("新建后无承办人", not owner.get("employeeId"), f"实际 owner={owner.get('employeeId')}")

        print("\n[3] 无承办人时，不允许推进到「办理中 / 已办结」")
        r = a.post(f"/cases/{cid}/status", {"status": "IN_PROGRESS"})
        check("置为「处理中」被拒", r.get("code") != 0, f"返回 {str(r)[:90]}")
        r = a.post(f"/cases/{cid}/status", {"status": "DONE", "remark": "自检"})
        check("置为「已办结」被拒", r.get("code") != 0, f"返回 {str(r)[:90]}")
        st, _ = a.status_of(cid)
        check("被拒后状态未被改动", st == "PENDING_ASSIGN", f"实际 {st}")

        print("\n[4] 建案/编辑接口不得直接设成办理中或终态")
        r = a.post("/cases", {"name": "自检-非法状态", "caseType": "PRELIMINARY", "status": "IN_PROGRESS"})
        check("建案时传 IN_PROGRESS 被拒", r.get("code") != 0, f"返回 {str(r)[:90]}")
        if (r.get("data") or {}).get("id"):
            created.append(r["data"]["id"])
        r = a.put(f"/cases/{cid}", {"name": "自检-指派状态-主流程", "caseType": "PRELIMINARY", "status": "DONE"})
        check("编辑时传 DONE 被拒", r.get("code") != 0, f"返回 {str(r)[:90]}")
        r = a.post("/cases", {"name": "自检-已指派却无人", "caseType": "PRELIMINARY", "status": "ASSIGNED"})
        check("建案时传 ASSIGNED 但未指定承办人被拒", r.get("code") != 0, f"返回 {str(r)[:90]}")
        if (r.get("data") or {}).get("id"):
            created.append(r["data"]["id"])

        print("\n[5] 指派：待指派 -> 已指派")
        a.post(f"/cases/{cid}/assign", {"caseId": cid, "ownerId": emp["id"], "memberIds": []})
        st, owner = a.status_of(cid)
        check("指派后状态 = ASSIGNED", st == "ASSIGNED", f"实际 {st}")
        check("承办人正确", owner.get("employeeId") == emp["id"], f"实际 {owner.get('employeeId')}")

        print("\n[6] 编辑案件（表单不提交 status）不应改变状态，也不丢承办人")
        r = a.put(f"/cases/{cid}", {"name": "自检-指派状态-已改名", "caseType": "PRELIMINARY"})
        check("编辑调用成功", r.get("code") == 0, f"返回 {str(r)[:90]}")
        st, owner = a.status_of(cid)
        check("编辑后状态仍为 ASSIGNED", st == "ASSIGNED", f"实际 {st}")
        check("编辑后承办人仍在", owner.get("employeeId") == emp["id"], f"实际 {owner.get('employeeId')}")

        print("\n[7] 有承办人后，可以推进到「处理中」")
        r = a.post(f"/cases/{cid}/status", {"status": "IN_PROGRESS"})
        st, _ = a.status_of(cid)
        check("置为「处理中」成功", r.get("code") == 0 and st == "IN_PROGRESS", f"返回 {str(r)[:60]} 状态 {st}")

        print("\n[8] 改派不应把「处理中」回退")
        a.post(f"/cases/{cid}/assign", {"caseId": cid, "ownerId": other["id"], "memberIds": []})
        st, owner = a.status_of(cid)
        check("改派后仍为 IN_PROGRESS", st == "IN_PROGRESS", f"实际 {st}")
        check("承办人已更新", owner.get("employeeId") == other["id"], f"实际 {owner.get('employeeId')}")

        print("\n[9] 非法状态值与终态约束")
        r = a.post(f"/cases/{cid}/status", {"status": "FOO"})
        check("非法状态值被拒", r.get("code") != 0, f"返回 {str(r)[:90]}")
        a.post(f"/cases/{cid}/status", {"status": "DONE", "remark": "自检"})
        st, _ = a.status_of(cid)
        check("有承办人时可办结", st == "DONE", f"实际 {st}")
        r = a.post(f"/cases/{cid}/assign", {"caseId": cid, "ownerId": emp["id"], "memberIds": []})
        check("已办结案件再指派被拒", r.get("code") != 0, f"返回 {str(r)[:90]}")

        print("\n[10] 存量体检：无承办人却处于办理中/已办结")
        lst = a.get("/cases", params={"page": 1, "size": 500})["data"]["list"]
        dirty = [c["caseNo"] for c in lst
                 if not (c.get("owner") or {}).get("employeeId")
                 and not (c.get("members") or [])
                 and c["status"] in ("ASSIGNED", "IN_PROGRESS", "DONE")]
        check("无「无承办人却已推进」的案件", not dirty,
              ("发现：" + ", ".join(dirty)) if dirty else "干净")
    finally:
        print("\n[11] 清理自检产生的临时案件")
        for cid in created:
            a.delete(f"/cases/{cid}")
        print(f"    已清理 {len(created)} 条")

    print("\n" + "=" * 52)
    print(f"通过 {len(OK)} 项 / 失败 {len(FAIL)} 项")
    for n, d in FAIL:
        print("  失败：" + n + ("  " + d if d else ""))
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
