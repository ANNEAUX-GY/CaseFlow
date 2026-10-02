"""全库数据一致性体检（只读，不做任何修改）。

以真实项目标准检查：
  一、案件状态 与 承办人 是否自洽（有进展却无人承办 / 有人承办却待指派）
  二、案件一个案件是否只有一个主办
  三、承办人引用完整性（员工是否还存在）
  四、账号与员工绑定是否 1:1、是否存在悬空引用
  五、案件编号唯一性
  六、待办：已完成的是否都有佐证

用法（先启动后端）：
    python scripts/audit_consistency.py
    python scripts/audit_consistency.py --base http://127.0.0.1:8080/api

退出码：0=全部一致，1=发现不一致。
仅用标准库。
"""
import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict

FINDINGS = []


def flag(level, kind, target, detail):
    FINDINGS.append({"level": level, "kind": kind, "target": target, "detail": detail})


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
            with urllib.request.urlopen(req, timeout=60) as r:
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

    def login(self, u, p):
        r = self.call("POST", "/auth/login", {"username": u, "password": p})
        if r.get("code") != 0:
            raise SystemExit("登录失败：" + str(r.get("msg")))
        self.token = r["data"]["token"]


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

    # ---------- 取数 ----------
    page = a.get("/cases", params={"page": 1, "size": 500})["data"]
    cases = page["list"]
    total = page["total"]
    if total > len(cases):
        print(f"    !! 案件总数 {total} 超出单页 {len(cases)} 条，请调大 size 后重跑")
    employees = flatten_employees(a.get("/employees/search", params={"limit": 1000})["data"])
    emp_ids = {e["id"] for e in employees}
    users = a.get("/users")["data"]
    todos = a.get("/todos/overview")["data"] or []
    print(f"[2] 取数完成：案件 {len(cases)} 条、员工 {len(employees)} 人、账号 {len(users)} 个、待办 {len(todos)} 项")

    # ---------- 一、案件状态 vs 承办人 ----------
    print("\n[3] 检查：案件状态 与 承办人 是否自洽")
    progressed = {"ASSIGNED", "IN_PROGRESS", "DONE"}
    for c in cases:
        owner = c.get("owner") or {}
        has_owner = bool(owner.get("employeeId"))
        member_n = len(c.get("members") or [])
        st = c["status"]
        label = f"{c['caseNo']}（{c['name']}）"
        if not has_owner and member_n == 0 and st in progressed:
            flag("高", "状态与承办人不符", label,
                 f"无任何承办人，状态却是「{st}」——应为 PENDING_ASSIGN，或补上承办人")
        if has_owner and st == "PENDING_ASSIGN":
            flag("高", "状态与承办人不符", label,
                 f"已有主办 {owner.get('employeeName')}，状态却是「待指派」——应为 ASSIGNED")

    # ---------- 二、一个案件是否只有一个主办 ----------
    print("[4] 检查：每个案件是否只有一个主办")
    for c in cases:
        owners = []
        if (c.get("owner") or {}).get("employeeId"):
            owners.append(c["owner"])
        owners += [m for m in (c.get("members") or []) if m.get("assignRole") == "OWNER"]
        if len(owners) > 1:
            names = ", ".join(str(o.get("employeeName")) for o in owners)
            flag("高", "主办不唯一", f"{c['caseNo']}（{c['name']}）", f"同时存在 {len(owners)} 个主办：{names}")

    # ---------- 三、承办人引用完整性 ----------
    print("[5] 检查：承办人是否指向真实存在的员工")
    for c in cases:
        people = []
        if c.get("owner"):
            people.append(c["owner"])
        people += list(c.get("members") or [])
        for p in people:
            if p.get("employeeId") and p["employeeId"] not in emp_ids:
                flag("高", "承办人悬空引用", f"{c['caseNo']}（{c['name']}）",
                     f"{p.get('employeeName')}(id={p['employeeId']}) 已不存在于员工表")

    # ---------- 四、账号与员工绑定 ----------
    print("[6] 检查：账号与员工绑定是否 1:1")
    bind_count = defaultdict(list)
    for u in users:
        eid = u.get("employeeId")
        if not eid:
            continue
        if eid not in emp_ids:
            flag("高", "账号绑定悬空", f"{u.get('username')}（{u.get('employeeName') or ''}）",
                 f"绑定的员工 id={eid} 已不存在")
        bind_count[eid].append(u.get("username"))
    for eid, names in bind_count.items():
        if len(names) > 1:
            flag("高", "员工被多账号绑定", f"employeeId={eid}",
                 "同时被以下账号绑定：" + ", ".join(str(n) for n in names))
    unbound = [u.get("username") for u in users if not u.get("employeeId")]
    print(f"    绑定员工账号 {sum(len(v) for v in bind_count.values())} 个；未绑定 {len(unbound)} 个：{', '.join(str(x) for x in unbound) or '无'}")

    # ---------- 五、案件编号唯一 ----------
    print("[7] 检查：案件编号是否唯一")
    seen = defaultdict(list)
    for c in cases:
        seen[c.get("caseNo")].append(c.get("id"))
    for no, ids in seen.items():
        if len(ids) > 1:
            flag("高", "案件编号重复", str(no), f"对应多条案件 id={ids}")

    # ---------- 六、待办佐证 ----------
    print("[8] 检查：已完成待办是否都有佐证")
    for t in todos:
        if t.get("status") == "DONE":
            n = t.get("evidenceCount")
            if n is None or n == 0:
                flag("高", "已完成待办缺佐证",
                     f"待办 {t.get('id')}：{t.get('content') or t.get('title')}",
                     f"状态为已完成，但佐证数为 {n}")

    # ---------- 汇总 ----------
    print("\n" + "=" * 60)
    if not FINDINGS:
        print("体检通过：未发现不一致。")
        return 0
    print(f"发现 {len(FINDINGS)} 处不一致：\n")
    for f in FINDINGS:
        print(f"  [{f['level']}] {f['kind']}")
        print(f"        {f['target']}")
        print(f"        {f['detail']}")
    print("\n" + "=" * 60)
    print(f"合计 {len(FINDINGS)} 处，需逐项确认后修复。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
