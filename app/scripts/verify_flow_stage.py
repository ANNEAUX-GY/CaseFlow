"""办案流程阶段推进自检（2026-10-12）。

覆盖用户定的两条口径：
  1) 阶段完成后**要领导确认**才转下一阶段（不自动跳）；
  2) 列表显示**阶段名**。

按真实链路走：建案 → 勾完初查全部任务 → 验证"待确认流转"标记 →
普通民警无权流转 → 领导转刑拘 → 验证强制措施/进度归零/新阶段任务已生成 → 清理。
"""
import json
import sys
import urllib.request

BASE = "http://127.0.0.1:8080/api"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

PASS = 0
FAIL = []


def call(path, tk=None, body=None, method=None):
    """method 缺省：有 body 走 POST，没 body 走 GET。

    注意 `/watch/plans/{id}/done` 与 `/revert` 都是 **@PostMapping 且 body 可空**，
    所以调它们必须显式传 method="POST"——否则这里会按"无 body"发成 GET，
    服务端回「Request method 'GET' not supported」，看起来像功能坏了。

    鉴权失败服务端直接抛 HTTP 4xx（不是 code=4xx 的200 包装），
    urllib 会把它变成异常，所以这里统一吃掉异常、按 HTTP 状态码返回，
    让「未登录被拒」这类断言也能写成普通的一行检查。
    """
    req = urllib.request.Request(
        BASE + path,
        json.dumps(body).encode() if body is not None else None,
        method=method or ("POST" if body is not None else "GET"),
    )
    req.add_header("Content-Type", "application/json")
    if tk:
        req.add_header("X-Token", tk)
    try:
        return json.loads(opener.open(req, timeout=20).read())
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read())
        except Exception:
            return {"code": e.code, "msg": "HTTP %d" % e.code, "data": None}


def check(name, got, want):
    global PASS
    if got == want:
        PASS += 1
        print("  OK   " + name)
    else:
        FAIL.append(name)
        print("  FAIL " + name + f"  期望={want!r} 实际={got!r}")


def login(u, p):
    return call("/auth/login", body={"username": u, "password": p})["data"]["token"]


def main():
    boss = login("boss", "admin123")

    print("=== A. 建案即铺初始阶段任务 ===")
    r = call("/cases", boss, {
        "name": "流程自检-阶段推进", "caseType": "CRIMINAL",
        "priority": "NORMAL", "sourceType": "MANUAL", "status": "PENDING_ASSIGN",
    })
    check("A1 建案成功", r.get("code"), 0)
    cid = r["data"]["id"]

    fv = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("A2 阶段为初查", fv["stage"], "INITIAL")
    check("A3 阶段名", fv["stageLabel"], "初查")
    check("A4 铺了任务", fv["progress"]["total"] > 0, True)
    check("A5 环节数=5", len(fv["steps"]), 5)
    check("A6 出口三条", [t["action"] for t in fv["transitions"]], ["DETAIN", "BAIL", "RELEASE"])
    check("A7 进度未完成", fv["progress"]["finished"], False)

    print("=== B. 勾任务：环节完成度随之变化 ===")
    tasks = [t for s in fv["steps"] for t in s["tasks"]]
    first = tasks[0]
    call("/watch/plans/%d/done" % first["id"], boss, method="POST")
    fv2 = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("B1 完成数+1", fv2["progress"]["done"], 1)
    check("B2 首环节完成数", fv2["steps"][0]["done"], 1)
    check("B3 撤销后归零",
          call("/watch/plans/%d/revert" % first["id"], boss, method="POST")["data"]["status"], "PENDING")
    check("B4 撤销后进度归零", call("/watch/cases/%d/flow" % cid, boss)["data"]["progress"]["done"], 0)

    print("=== C. 未完成时列表不标「待确认流转」 ===")
    row = find_row(boss, cid)
    check("C1 flowFinished=false", row.get("flowFinished"), False)

    print("=== D. 全部勾完：阶段完成但不自动跳 ===")
    for t in tasks:
        call("/watch/plans/%d/done" % t["id"], boss, method="POST")
    fv3 = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("D1 进度 100%", fv3["progress"]["finished"], True)
    check("D2 仍停在初查（不自动跳）", fv3["stage"], "INITIAL")
    row = find_row(boss, cid)
    check("D3 列表标待确认流转", row.get("flowFinished"), True)
    check("D4 列表阶段名", row.get("flowStageName"), "初查")

    print("=== E. 普通民警无权流转（要领导确认）===")
    # 民警账号统一用 e2e_staff（由 verify_lan.py 生成，绑定了员工档案）；
    # 它没有 test1 那个旧口令，登录失败会抛异常中断脚本，所以先探再用。
    staff = None
    for u, p in (("e2e_staff", "e2e123456"), ("test1", "test1")):
        d = call("/auth/login", body={"username": u, "password": p}).get("data")
        if d and d.get("token"):
            staff = d["token"]
            break
    if staff is None:
        print("  SKIP 找不到可用的民警账号（先跑 verify_lan.py 生成 e2e_staff）")
        staff = boss  # 退化成只验未登录那条
    else:
        r = call("/watch/cases/%d/flow/transfer" % cid, staff, {"action": "DETAIN"})
        check("E1 民警被拒", r.get("code"), 403)
    r = call("/watch/cases/%d/flow/transfer" % cid, None, {"action": "DETAIN"}, method="POST")
    check("E2 未登录被拒", r.get("code"), 401)

    print("=== F. 领导确认流转 → 刑拘在办 ===")
    r = call("/watch/cases/%d/flow/transfer" % cid, boss, {"action": "DETAIN"})
    check("F1 流转成功", r.get("code"), 0)
    fv4 = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("F2 进入刑拘在办", fv4["stage"], "DETAIN")
    check("F3 阶段名", fv4["stageLabel"], "刑拘在办")
    check("F4 新阶段任务已生成", fv4["progress"]["total"] > 0, True)
    check("F5 进度归零", fv4["progress"]["done"], 0)
    check("F6 新环节=指派清案民警", fv4["steps"][0]["key"], "ASSIGN_CLEAR")
    d = call("/cases/%d" % cid, boss)["data"]
    check("F7 强制措施=拘留", d.get("caseMeasure"), "DETENTION")
    check("F8 已终结标记清掉", find_row(boss, cid).get("flowFinished"), False)

    print("=== G. 转刑拘后可继续流转到终结 ===")
    r = call("/watch/cases/%d/flow/transfer" % cid, boss, {"action": "ARREST"})
    check("G1 逮捕结案", r.get("code"), 0)
    fv5 = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("G2 已终结", fv5["stage"], "CLOSED")
    check("G3 终结后进度 100%", fv5["progress"]["percent"], 100)
    check("G4 终结后无出口", fv5["transferable"], False)

    print("=== H. 老案件可一键补铺 ===")
    r2 = call("/cases", boss, {
        "name": "流程自检-补铺", "caseType": "ADMINISTRATIVE",
        "priority": "NORMAL", "sourceType": "MANUAL", "status": "PENDING_ASSIGN",
    })
    cid2 = r2["data"]["id"]
    n = call("/watch/cases/%d/flow/seed" % cid2, boss, method="POST")["data"]
    check("H1 幂等：重复 seed 不再新增",
          call("/watch/cases/%d/flow/seed" % cid2, boss, method="POST")["data"], 0)
    check("H2 行政出口=处罚结案",
          [t["action"] for t in call("/watch/cases/%d/flow" % cid2, boss)["data"]["transitions"]],
          ["PUNISH"])

    print("=== 清理 ===")
    for x in (cid, cid2):
        call("/cases/%d" % x, boss, method="DELETE")
    print("  已删除自检案件", cid, cid2)

    print("\n通过 %d 项" % PASS)
    if FAIL:
        print("失败 %d 项：" % len(FAIL))
        for f in FAIL:
            print("  - " + f)
        sys.exit(1)
    print("全部通过")


def find_row(tk, cid):
    """在案件盯办列表里找到这一行，验证列表口径（阶段名 / 待确认流转）"""
    r = call("/watch/cases?page=1&size=200", tk)["data"]
    for row in r["list"]:
        if row["id"] == cid:
            return row
    return {}


if __name__ == "__main__":
    main()