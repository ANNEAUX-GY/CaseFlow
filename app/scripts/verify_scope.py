"""验证「普通民警只见本人案件」的数据范围收敛。只读 + 一次指派，不改结构。"""
import json
import sys
import urllib.request

BASE = "http://127.0.0.1:8080/api"


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Token", token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def login(u, p):
    st, body = call("POST", "/auth/login", {"username": u, "password": p})
    if st != 200 or body.get("code") != 0:
        return None, body
    return body["data"]["token"], body["data"]


# ---------- 1. 管理员 ----------
boss, boss_info = login("boss", "admin123")
assert boss, boss_info
print(f"[管理员] boss 角色={boss_info['role']} fullAccess={boss_info['fullAccess']}")

_, all_page = call("GET", "/cases?page=1&size=200", token=boss)
_, boss_dash = call("GET", "/cases/dashboard", token=boss)
print(f"[管理员] /cases 总数={all_page['data']['total']}  dashboard.totalCase={boss_dash['data']['totalCase']}  openCase={boss_dash['data']['openCase']}")

# 挑一个在办案件，指派给 e2e_staff 账号所绑定的员工。
# 注意：不要硬编码 employeeId——员工 id 会随数据重建而变，
# 写死一个数字会在员工被清理后静默失败（指派报「员工不存在」，后续断言跟着失真）。
staff_emp_id = None
st, staff_info_early = call("POST", "/auth/login", {"username": "e2e_staff", "password": "e2e123456"})
if st == 200 and (staff_info_early.get("data") or {}).get("employeeId"):
    staff_emp_id = staff_info_early["data"]["employeeId"]
if staff_emp_id is None:
    # 退回按姓名查（e2e_staff 绑定的档案名由 verify_lan.py 的 SELF_EMP_PREFIX 决定）
    _, sr = call("GET", "/employees/search?keyword=e2e_staff&limit=50", token=boss)
    for e in (sr.get("data") or []):
        staff_emp_id = e["id"]
        break
assert staff_emp_id, ("找不到 e2e_staff 对应的员工档案。请先跑 scripts/verify_lan.py 生成测试账号"
                      "（它会在自检里注册 e2e_staff 并建立绑定的员工档案）。")
print(f"[管理员] e2e_staff 对应员工 id={staff_emp_id}")

target = None
for c in all_page["data"]["list"]:
    if c["status"] not in ("DONE", "CANCELLED"):
        target = c
        break
assert target, "没有可指派的在办案件"
print(f"[管理员] 选中案件 id={target['id']} 编号={target.get('caseNo')} 名称={target['name']}")

st, r = call("POST", f"/cases/{target['id']}/assign",
             {"caseId": target["id"], "ownerId": staff_emp_id, "memberIds": [], "note": "角色范围自检"}, boss)
print(f"[管理员] 指派给员工 {staff_emp_id} -> {st} {r.get('msg')}")
if st != 200 or r.get("code") != 0:
    sys.exit(f"指派失败，后续断言无法进行：{r.get('msg')}")

# ---------- 2. 普通民警 ----------
staff, staff_info = login("e2e_staff", "e2e123456")
if not staff:
    print("[普通民警] e2e_staff 登录失败：", staff_info)
    sys.exit(1)
print(f"\n[普通民警] e2e_staff 角色={staff_info['role']} employeeId={staff_info['employeeId']} fullAccess={staff_info['fullAccess']}")

# 关键：不带 onlyMine 参数，后端也应强制收敛
st, mine = call("GET", "/cases?page=1&size=200", token=staff)
names = [c["name"] for c in mine["data"]["list"]]
print(f"[普通民警] /cases （未传 onlyMine）总数={mine['data']['total']} -> {names}")

st, sdash = call("GET", "/cases/dashboard", token=staff)
d = sdash["data"]
print(f"[普通民警] /cases/dashboard 总数={d['totalCase']} 在办={d['openCase']} 已逾期={d['overdue']} 警力={d['employeeCount']} 最近操作={len(d.get('recentLogs') or [])} 条")

st, sstats = call("GET", "/cases/stats?days=14", token=staff)
s = sstats["data"]
total_in_chart = sum(x["value"] for x in s["statusDist"])
overdue_age = sum(x["value"] for x in s["overdueAgeDist"])
print(f"[普通民警] /cases/stats 状态分布合计={total_in_chart} 逾期账龄合计={overdue_age}")

st, remind = call("GET", "/cases/reminders?bucket=NONE&limit=100", token=staff)
print(f"[普通民警] /cases/reminders(NONE) 条数={len(remind['data'])}")

# 管理员口径对照
st, bstats = call("GET", "/cases/stats?days=14", token=boss)
print(f"[管理员]   /cases/stats 状态分布合计={sum(x['value'] for x in bstats['data']['statusDist'])}")

# ---------- 3. 越权 ----------
print("\n--- 越权拦截 ---")
for method, path, body, label in [
    ("GET", "/users?page=1&size=10", None, "账号管理"),
    ("GET", "/todos/overview?page=1&size=10", None, "待办总览"),
    ("POST", f"/cases/{target['id']}/status", {"status": "DONE"}, "办结案件"),
    ("DELETE", f"/cases/{target['id']}", None, "删除案件"),
]:
    st, r = call(method, path, body, staff)
    msg = r.get("msg") if isinstance(r, dict) else str(r)[:40]
    print(f"[普通民警] {label:6s} {method:6s} {path:45s} -> {st} {msg}")

# 普通民警能否看到别人的案件（按 employeeId 过滤他人）
st, other = call("GET", "/cases?page=1&size=200&employeeId=1", token=staff)
print(f"[普通民警] 显式查他人(employeeId=1) -> 总数={other['data']['total']}（应为 0）")
