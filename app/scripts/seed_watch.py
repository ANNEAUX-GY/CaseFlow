"""盯办模块演示数据：给若干案件造「措施 + 侦查计划 + 状态流转」进度。

用法（先启动后端）：
    .venv\\Scripts\\python.exe scripts/seed_watch.py --staff-pass <承办人账号密码>
    .venv\\Scripts\\python.exe scripts/seed_watch.py --base http://127.0.0.1:8080 --staff-pass xxx

作用：让盯办页三个子模块（初查 / 刑拘在办 / 取保监居）都有可看的样例，
并覆盖「侦查中 / 待审批 / 侦查终结」三种进度状态。可重复执行（已推进的案件会跳过）。

权限说明（2026-10 起）：
  「开始侦查 / 新增侦查计划 / 提请审批」由办案人本人执行（管理层不代点、不代订计划），
  所以本脚本需要一个**承办人账号**（--staff-user，默认 test1）登录来执行这些动作；
  管理员账号只做管理层专属的动作（登记措施 / 审批）。
  案件的现办人与 --staff-user 不是同一账号时，该案件跳过并提示。
"""
import argparse
from datetime import datetime, timedelta

import requests


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--username", default="boss")
    ap.add_argument("--password", default="admin123")
    ap.add_argument("--staff-user", default="test1", help="承办人账号（执行开始侦查/计划/提请审批）")
    ap.add_argument("--staff-pass", default=None, help="承办人账号密码；不传则跳过需要办案人身份的步骤")
    args = ap.parse_args()

    base = args.base.rstrip("/")
    if not base.endswith("/api"):
        base += "/api"

    s = requests.Session()
    s.headers.update({"X-Token": ""})
    r = s.post(f"{base}/auth/login", json={"username": args.username, "password": args.password})
    r.raise_for_status()
    body = r.json()
    if body.get("code") != 0:
        raise SystemExit("登录失败：" + str(body.get("msg")))
    s.headers.update({"X-Token": body["data"]["token"]})

    # 承办人账号：权限收紧后，开始侦查/计划/提请审批必须以办案人身份执行
    staff = requests.Session()
    staff_ok = False
    if args.staff_pass:
        r = staff.post(f"{base}/auth/login",
                       json={"username": args.staff_user, "password": args.staff_pass})
        if r.ok and r.json().get("code") == 0:
            staff.headers.update({"X-Token": r.json()["data"]["token"]})
            staff_ok = True
        else:
            print(f"! 承办人账号 {args.staff_user} 登录失败，需办案人身份的步骤将被跳过")
    else:
        print("! 未传 --staff-pass，需办案人身份的步骤（开始侦查/计划/提请审批）将被跳过")

    def staff_usernames():
        """员工 id -> 绑定账号用户名（管理员可读）"""
        m = {}
        r = s.get(f"{base}/users", params={"size": 200})
        if r.ok and r.json().get("code") == 0:
            for u in r.json()["data"].get("list", []) or r.json()["data"] or []:
                if u.get("employeeId"):
                    m[u["employeeId"]] = u.get("username")
        return m

    emp2user = staff_usernames() if (staff_ok or args.staff_pass) else {}

    cases = s.get(f"{base}/cases", params={"size": 100}).json()["data"]["list"]

    def pick(case_type, exclude):
        for c in cases:
            if c.get("caseType") == case_type and c.get("id") not in exclude and c.get("owner"):
                return c
        return None

    def api_post(path, payload, label, session=None):
        r = (session or s).post(f"{base}{path}", json=payload)
        if r.status_code >= 400 or r.json().get("code") != 0:
            print(f"      ! {label} 失败：{r.text[:160]}")
            return None
        return r.json().get("data")

    def staff_of(c):
        """案件现主办绑定的账号名；不是 --staff-user 时返回 None（跳过）"""
        owner_emp = (c.get("owner") or {}).get("employeeId")
        u = emp2user.get(owner_emp)
        return u if u == args.staff_user else None

    def add_plans(case_id, contents, done_count):
        for i, content in enumerate(contents):
            planned = (datetime.now() + timedelta(days=3 * (i + 1))).strftime("%Y-%m-%d %H:%M:%S")
            plan = api_post(f"/watch/cases/{case_id}/plans",
                            {"content": content, "plannedAt": planned}, f"计划「{content}」", staff)
            if plan and i < done_count:
                api_post(f"/watch/plans/{plan['id']}/done", {"doneNote": "已完成，材料归档。"}, "计划完成", staff)

    used = set()
    total = 0
    handled = []

    # 1) 刑拘：走完整流程到「侦查终结」——展示审批通过
    c = pick("CRIMINAL", used)
    if c:
        used.add(c["id"])
        handled.append(c["id"])
        print(f"[1/4] 刑拘案 {c['caseNo']} {c['name']} → 侦查终结")
        if staff_ok and staff_of(c):
            api_post(f"/watch/cases/{c['id']}/transition", {"action": "START"}, "开始侦查", staff)
        else:
            print("      ! 跳过开始侦查（主办不是 " + args.staff_user + "）")
        api_post(f"/watch/cases/{c['id']}/measure",
                 {"measure": "DETENTION", "comment": "演示：依法刑事拘留"}, "登记刑拘")
        if staff_ok and staff_of(c):
            add_plans(c["id"], ["调取涉案银行流水", "询问被害人并制作笔录", "委托电子数据鉴定"], 3)
            api_post(f"/watch/cases/{c['id']}/transition", {"action": "SUBMIT"}, "提请审批", staff)
        api_post(f"/watch/cases/{c['id']}/transition",
                 {"action": "APPROVE", "comment": "证据链完整，同意侦查终结"}, "审批同意")
        total += 1

    # 2) 取保：在办，进度「侦查中」，只完成部分计划——展示进度条未满
    c = pick("CRIMINAL", used)
    if c:
        used.add(c["id"])
        handled.append(c["id"])
        print(f"[2/4] 取保案 {c['caseNo']} {c['name']} → 侦查中")
        if staff_ok and staff_of(c):
            api_post(f"/watch/cases/{c['id']}/transition", {"action": "START"}, "开始侦查", staff)
        else:
            print("      ! 跳过开始侦查（主办不是 " + args.staff_user + "）")
        api_post(f"/watch/cases/{c['id']}/measure",
                 {"measure": "BAIL", "comment": "演示：取保候审"}, "登记取保")
        if staff_ok and staff_of(c):
            add_plans(c["id"], ["收集证人证言", "核实嫌疑人社会关系"], 1)
        total += 1

    # 3) 监居：在办，0 条完成计划——展示进度为 0 与期限提醒
    c = pick("CRIMINAL", used)
    if c:
        used.add(c["id"])
        handled.append(c["id"])
        print(f"[3/4] 监居案 {c['caseNo']} {c['name']} → 侦查中")
        if staff_ok and staff_of(c):
            api_post(f"/watch/cases/{c['id']}/transition", {"action": "START"}, "开始侦查", staff)
        else:
            print("      ! 跳过开始侦查（主办不是 " + args.staff_user + "）")
        api_post(f"/watch/cases/{c['id']}/measure",
                 {"measure": "RESIDENCE", "comment": "演示：监视居住"}, "登记监居")
        if staff_ok and staff_of(c):
            add_plans(c["id"], ["调取现场监控录像", "走访社区了解情况"], 0)
        total += 1

    # 4) 未立案初查：无强制措施，展示初查模块
    c = pick("PRELIMINARY", used)
    if c:
        used.add(c["id"])
        handled.append(c["id"])
        print(f"[4/4] 初查案 {c['caseNo']} {c['name']} → 侦查中（无措施）")
        if staff_ok and staff_of(c):
            api_post(f"/watch/cases/{c['id']}/transition", {"action": "START"}, "开始初查", staff)
            add_plans(c["id"], ["核查报案材料", "初步询问当事人"], 1)
        else:
            print("      ! 跳过开始初查（主办不是 " + args.staff_user + "）")
        total += 1

    # 5) 嫌疑人：让初查模块「按姓名 / 身份证号检索」有可用数据
    suspects = [
        ("张伟", "MALE", "110101199003072531", "13800001111", "朝阳区示范路 1 号"),
        ("李娜", "FEMALE", "110101198705124622", "13900002222", "海淀区示例街 2 号"),
    ]
    targets = [handled[0], handled[-1]] if len(handled) >= 2 else handled[:1]
    for i, cid in enumerate(targets):
        name, gender, id_card, phone, addr = suspects[i]
        if s.get(f"{base}/cases/{cid}/suspects").json().get("data"):
            print(f"[5/5] 案件 {cid} 已有嫌疑人，跳过")
            continue
        got = api_post(f"/cases/{cid}/suspects",
                       {"name": name, "gender": gender, "idCard": id_card,
                        "phone": phone, "address": addr, "remark": "演示数据"},
                       f"嫌疑人 {name}")
        if got:
            print(f"[5/5] 案件 {cid} 已录入嫌疑人 {name}")

    board = s.get(f"{base}/watch/board").json().get("data", {})
    print("\n盯办看板：", board)
    print(f"\n完成：{total} 起案件已造进度。")


if __name__ == "__main__":
    main()
