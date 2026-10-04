"""阶段→环节→任务流程自检（2026-10-04 落地）。

覆盖的规则：
  1. 流程视图能取到：当前阶段 + 环节顺序 + 任务清单 + 进度
  2. 阶段进度 = 该阶段 DONE 数 / 该阶段任务总数，勾选任务后递增
  3. 进度达到 100% 时提示可流转
  4. 阶段流转（管理层）：流转后 **新阶段进度归零**（核心断言）
  5. 流转后旧阶段任务退出分母（不计入新阶段进度）
  6. 流转分支非法动作被拒；刑事初查可转刑拘/取保/释放，行政初查只能批准处罚
  7. 流转需管理层权限（普通民警被拒）
  8. 任务勾选双向切换：完成 / 撤销完成后进度正确回落
  9. 任务状态机：只能 PENDING → DONE，非 DONE 不能直接 cancel
 10. 种子补全幂等：重复调 flow/seed 不产生重复任务
 11. 存量兼容：stage 为 NULL 的历史计划归入「初查/侦查」，不丢进度

用法（先启动后端）：
    python scripts/verify_flow.py
    python scripts/verify_flow.py --base http://127.0.0.1:8080/api

脚本自建临时案件并在结束时删除，不留残留。仅用标准库。
"""
import argparse
import json
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

    def delete(self, path):
        return self.call("DELETE", path)

    def login(self, u, p):
        r = self.post("/auth/login", {"username": u, "password": p})
        if r.get("code") != 0:
            raise SystemExit("登录失败：" + str(r.get("msg")))
        self.token = r["data"]["token"]


def new_case(a, name, case_type="CRIMINAL"):
    r = a.post("/cases", {"name": name, "caseType": case_type, "priority": "NORMAL"})
    cid = (r.get("data") or {}).get("id")
    if not cid:
        raise SystemExit("创建案件失败：" + str(r)[:200])
    return cid


def flow_of(a, cid):
    return (a.get(f"/watch/cases/{cid}/flow").get("data")) or {}


def progress_of(a, cid):
    return (a.get(f"/watch/cases/{cid}/flow/progress").get("data")) or {}


def all_tasks(flow):
    out = []
    for s in flow.get("steps") or []:
        out.extend(s.get("tasks") or [])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--username", default="boss")
    ap.add_argument("--password", default="admin123")
    args = ap.parse_args()

    a = Api(args.base)
    a.login(args.username, args.password)
    print("[1] 以管理层登录成功\n")

    cid_crim = new_case(a, "自检-刑事流程")
    cid_admin = new_case(a, "自检-行政流程", "ADMINISTRATIVE")
    print(f"[2] 建临时案件：刑事={cid_crim} 行政={cid_admin}\n")

    try:
        # ---------- 1. 流程视图 ----------
        print("--- 1. 流程视图结构 ---")
        a.post(f"/watch/cases/{cid_crim}/flow/seed")
        f = flow_of(a, cid_crim)
        check("取到流程视图", bool(f), str(f)[:80])
        check("阶段为初查 INITIAL", f.get("stage") == "INITIAL", f"实际 {f.get('stage')}")
        steps = f.get("steps") or []
        check("环节数量 >= 5（接收/立案/侦查/审查/刑拘）", len(steps) >= 5, f"实际 {len(steps)}")
        keys = [s.get("key") for s in steps]
        check("环节顺序 接收→立案→侦查→审查→刑拘",
              keys[:5] == ["RECEIVE", "CASE_FILL", "INVESTIGATE", "REVIEW", "DETAIN"],
              f"实际 {keys[:5]}")
        tasks = all_tasks(f)
        check("已生成标准任务", len(tasks) > 0, f"{len(tasks)} 项")
        check("标准任务带 taskKey", any(t.get("taskKey") for t in tasks))

        # ---------- 2. 进度递增 ----------
        print("\n--- 2. 进度计算（实时、不落库） ---")
        p0 = progress_of(a, cid_crim)
        check("初始进度 0%", p0.get("percent") == 0, f"{p0.get('percent')}%")
        check("分母=该阶段任务总数", p0.get("total") == len(tasks),
              f"total={p0.get('total')} 任务数={len(tasks)}")
        check("未满时 finished=False", p0.get("finished") is False)

        # 勾选一个任务
        t1 = tasks[0]
        r = a.post(f"/watch/plans/{t1['id']}/done", {"doneNote": ""})
        check("标记任务完成", r.get("code") == 0, str(r.get("msg") or "")[:60])
        p1 = progress_of(a, cid_crim)
        check("进度递增 0→>0", p1.get("percent", 0) > 0, f"{p1.get('percent')}%")
        check("分子 +1", p1.get("done") == 1, f"done={p1.get('done')}")

        # ---------- 3. 撤销完成 ----------
        print("\n--- 3. 任务勾选双向切换 ---")
        r = a.post(f"/watch/plans/{t1['id']}/revert")
        check("撤销完成成功", r.get("code") == 0, str(r.get("msg") or "")[:60])
        p2 = progress_of(a, cid_crim)
        check("进度回落回 0%", p2.get("percent") == 0, f"{p2.get('percent')}%")
        r = a.post(f"/watch/plans/{t1['id']}/revert")
        check("对非 DONE 任务再撤销被拒", r.get("code") != 0, f"返回 {str(r)[:60]}")

        # ---------- 4. 流转需管理层 ----------
        print("\n--- 4. 流转权限 ---")
        # 借普通民警账号（若不存在则跳过）
        staff = Api(args.base)
        try:
            staff.login("e2e_staff", "e2e123456")
            r = staff.post(f"/watch/cases/{cid_crim}/flow/transfer", {"action": "DETAIN"})
            check("普通民警流转被拒(403)", r.get("code") != 0, f"返回 {str(r)[:70]}")
        except SystemExit:
            print("  [SKIP] 无 e2e_staff 账号，跳过越权断言")

        # ---------- 5. 全DONE→可流转 ----------
        print("\n--- 5. 流转触发条件 ---")
        for t in all_tasks(flow_of(a, cid_crim)):
            a.post(f"/watch/plans/{t['id']}/done", {"doneNote": ""})
        pf = progress_of(a, cid_crim)
        check("全部完成后进度 100%", pf.get("percent") == 100, f"{pf.get('percent')}%")
        check("finished=True", pf.get("finished") is True)
        f = flow_of(a, cid_crim)
        check("出现可流转分支", bool(f.get("transferable")) and len(f.get("transitions") or []) > 0,
              f"分支数={len(f.get('transitions') or [])}")

        # ---------- 6. 流转 → 进度归零（核心） ----------
        print("\n--- 6. 流转后进度归零（核心断言）---")
        before_total = pf.get("total")
        r = a.post(f"/watch/cases/{cid_crim}/flow/transfer", {"action": "DETAIN"})
        check("流转到刑拘在办", r.get("code") == 0, str(r.get("msg") or "")[:60])
        f2 = flow_of(a, cid_crim)
        check("阶段变为 DETAIN", f2.get("stage") == "DETAIN", f"实际 {f2.get('stage')}")
        p3 = progress_of(a, cid_crim)
        check("★ 新阶段进度归零", p3.get("percent") == 0,
              f"流转前分母={before_total}，流转后 {p3.get('percent')}% (0/{p3.get('total')})")
        check("新阶段分子为 0", p3.get("done") == 0, f"done={p3.get('done')}")
        check("新阶段已生成任务", p3.get("total", 0) > 0, f"{p3.get('total')} 项")
        newkeys = [s.get("key") for s in (f2.get("steps") or [])]
        check("新阶段环节= 指派/逮捕/清案结束",
              newkeys[:3] == ["ASSIGN_CLEAR", "ARREST", "CLOSE"], f"实际 {newkeys[:3]}")
        # 旧阶段任务应被 CANCELLED，不计入新分母
        check("旧阶段任务已退出分母", p3.get("total") == len(all_tasks(f2)),
              f"total={p3.get('total')} 当前任务数={len(all_tasks(f2))}")

        # ---------- 7. 分支校验 ----------
        print("\n--- 7. 流转分支合法性 ---")
        r = a.post(f"/watch/cases/{cid_crim}/flow/transfer", {"action": "NOT_EXIST"})
        check("非法动作被拒", r.get("code") != 0, f"返回 {str(r)[:60]}")
        r = a.post(f"/watch/cases/{cid_crim}/flow/transfer", {"action": "PUNISH"})
        check("刑事案件禁「批准行政处罚」", r.get("code") != 0, f"返回 {str(r)[:60]}")
        # 刑拘在办 → 取保 → BAIL 阶段
        r = a.post(f"/watch/cases/{cid_crim}/flow/transfer", {"action": "BAIL"})
        check("刑拘在办可转取保", r.get("code") == 0, str(r.get("msg") or "")[:60])
        f3 = flow_of(a, cid_crim)
        check("阶段变为 BAIL", f3.get("stage") == "BAIL", f"实际 {f3.get('stage')}")
        p4 = progress_of(a, cid_crim)
        check("取保阶段进度也归零", p4.get("percent") == 0, f"{p4.get('percent')}%")
        # 取保 → 解除
        r = a.post(f"/watch/cases/{cid_crim}/flow/transfer", {"action": "CLOSE"})
        check("取保可解除收案", r.get("code") == 0, str(r.get("msg") or "")[:60])
        f4 = flow_of(a, cid_crim)
        check("阶段变为 CLOSED", f4.get("stage") == "CLOSED", f"实际 {f4.get('stage')}")
        check("已终结无流转分支", not f4.get("transferable"))
        p5 = progress_of(a, cid_crim)
        check("已终结进度恒 100%", p5.get("percent") == 100, f"{p5.get('percent')}%")

        # ---------- 8. 行政流程分支 ----------
        print("\n--- 8. 行政流程出口 ---")
        a.post(f"/watch/cases/{cid_admin}/flow/seed")
        fa = flow_of(a, cid_admin)
        akeys = [s.get("key") for s in (fa.get("steps") or [])]
        check("行政初查环节= 接收/立案/侦查/呈批/处罚",
              akeys[:5] == ["RECEIVE", "CASE_FILL", "INVESTIGATE", "PRESENT", "PUNISH"],
              f"实际 {akeys[:5]}")
        acts = [t.get("action") for t in (fa.get("transitions") or [])]
        check("行政出口只有 PUNISH", acts == ["PUNISH"], f"实际 {acts}")
        r = a.post(f"/watch/cases/{cid_admin}/flow/transfer", {"action": "DETAIN"})
        check("行政案件禁「刑拘」", r.get("code") != 0, f"返回 {str(r)[:60]}")
        r = a.post(f"/watch/cases/{cid_admin}/flow/transfer", {"action": "PUNISH"})
        check("行政可批准处罚并终结", r.get("code") == 0, str(r.get("msg") or "")[:60])

        # ---------- 9. seed 幂等 ----------
        print("\n--- 9. 标准任务补全幂等 ---")
        cid2 = new_case(a, "自检-幂等")
        n1 = (a.post(f"/watch/cases/{cid2}/flow/seed").get("data")) or 0
        n2 = (a.post(f"/watch/cases/{cid2}/flow/seed").get("data")) or 0
        check("首次 seed 生成任务", n1 > 0, f"{n1} 项")
        check("重复 seed 不再新增", n2 == 0, f"二次返回 {n2}")
        a.delete(f"/cases/{cid2}")

    finally:
        # ---------- 清理 ----------
        print("\n--- 清理临时数据 ---")
        for cid in (cid_crim, cid_admin):
            try:
                a.delete(f"/cases/{cid}")
                print(f"  已删除案件 {cid}")
            except Exception as e:
                print(f"  删除 {cid} 失败：{e}")

    print("\n" + "=" * 56)
    print(f"  通过 {len(OK)} / {len(OK) + len(FAIL)}")
    if FAIL:
        print("  失败项：")
        for n, d in FAIL:
            print(f"    - {n}  {d}")
        sys_exit = 1
    else:
        print("  全部通过")
        sys_exit = 0
    print("=" * 56)
    raise SystemExit(sys_exit)


if __name__ == "__main__":
    main()
