"""办案阶段推进自检（2026-10-12 改版）。

覆盖用户定的口径：
  1) **阶段任务 = 案件待办**：建案不再铺第二套标准任务，阶段进度只看本阶段待办；
  2) 阶段待办全部办完 → 由**领导确认**才转入下一阶段（不自动跳）；
  3) 刑事转入路径只有 **初查 → 刑拘在办 → 取保及监居**。

按真实链路走：建案 → 领导提意见（自动派生带阶段标记的待办）→ 逐条办完 →
验证「待确认流转」标记 → 民警无权流转 → 领导转刑拘 → 验证进度归零/强制措施 →
转取保 → 终结 → 清理。
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

    待办的「反馈 / 完成 / 撤销」都是 @PostMapping 且 body 可空，
    调它们必须显式传 method="POST"——否则会按"无 body"发成 GET，
    服务端回「method not supported」，看起来像功能坏了。

    鉴权失败服务端直接抛 HTTP 4xx（不是 code=4xx 的 200 包装），
    urllib 会把它变成异常，这里统一吃掉、按 HTTP 状态码返回，
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


def add_opinion(tk, cid, content):
    """领导提意见 → 自动派生一条带阶段标记的待办，返回待办 id"""
    call("/watch/cases/%d/opinions" % cid, tk, {"content": content, "importance": "C"})
    todos = call("/todos/case/%d" % cid, tk)["data"]
    for t in reversed(todos):
        if t["content"] == content:
            return t["id"]
    return None


def finish_todo(tk, tid, case_id):
    """办完一条待办：先提交反馈说明（完成必须有凭据），再标记完成"""
    call("/todos/%d/feedbacks" % tid, tk,
         {"status": "DONE", "content": "阶段自检：已办结"}, method="POST")
    return call("/todos/%d/done" % tid, tk, {"remark": "已办结"}, method="POST")


def main():
    boss = login("boss", "admin123")

    print("=== A. 建案不再铺标准任务，进度只看案件待办 ===")
    r = call("/cases", boss, {
        "name": "阶段自检-待办判据", "caseType": "CRIMINAL",
        "priority": "NORMAL", "sourceType": "MANUAL", "status": "PENDING_ASSIGN",
    })
    check("A1 建案成功", r.get("code"), 0)
    cid = r["data"]["id"]

    fv = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("A2 阶段为初查", fv["stage"], "INITIAL")
    check("A3 阶段名", fv["stageLabel"], "初查")
    check("A4 建案不铺任务（进度 0/0）",
          [fv["progress"]["total"], fv["progress"]["done"]], [0, 0])
    check("A5 空阶段不算完成", fv["progress"]["finished"], False)
    check("A6 刑事初查唯一出口=刑拘", [t["action"] for t in fv["transitions"]], ["DETAIN"])
    check("A7 无环节层（已删三层结构）", "steps" in fv, False)

    print("=== B. 领导提意见 → 派生待办并计入本阶段 ===")
    t1 = add_opinion(boss, cid, "接收刑事案件材料并登记")
    t2 = add_opinion(boss, cid, "完成立案审批")
    check("B1 派生出两条待办", len(call("/todos/case/%d" % cid, boss)["data"]) >= 2, True)
    fv = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("B2 本阶段待办数=2", fv["progress"]["total"], 2)
    check("B3 未办完不算完成", fv["progress"]["finished"], False)
    check("B4 列表 flowTotal 同口径", find_row(boss, cid).get("flowTotal"), 2)

    print("=== C. 办完一条：进度随之变化 ===")
    finish_todo(boss, t1, cid)
    fv = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("C1 完成数=1", fv["progress"]["done"], 1)
    check("C2 仍未完成", fv["progress"]["finished"], False)
    check("C3 列表 flowDone 同口径", find_row(boss, cid).get("flowDone"), 1)

    print("=== D. 本阶段待办办完：完成但不自动跳 ===")
    finish_todo(boss, t2, cid)
    fv = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("D1 进度 100%", fv["progress"]["finished"], True)
    check("D2 仍停在初查（不自动跳）", fv["stage"], "INITIAL")
    row = find_row(boss, cid)
    check("D3 列表标待确认流转", row.get("flowFinished"), True)
    check("D4 列表阶段名", row.get("flowStageName"), "初查")

    print("=== E. 流转必须领导确认（民警/未登录都不行）===")
    staff = None
    for u, p in (("e2e_staff", "e2e123456"), ("test1", "test1")):
        d = call("/auth/login", body={"username": u, "password": p}).get("data")
        if d and d.get("token"):
            staff = d["token"]
            break
    if staff is None:
        print("  SKIP 找不到可用民警账号（先跑 verify_lan.py 生成 e2e_staff）")
    else:
        r = call("/watch/cases/%d/flow/transfer" % cid, staff, {"action": "DETAIN"})
        check("E1 民警被拒", r.get("code"), 403)
    r = call("/watch/cases/%d/flow/transfer" % cid, None, {"action": "DETAIN"}, method="POST")
    check("E2 未登录被拒", r.get("code"), 401)

    print("=== F. 领导确认流转 → 刑拘在办 ===")
    r = call("/watch/cases/%d/flow/transfer" % cid, boss, {"action": "DETAIN"})
    check("F1 流转成功", r.get("code"), 0)
    fv = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("F2 进入刑拘在办", fv["stage"], "DETAIN")
    check("F3 阶段名", fv["stageLabel"], "刑拘在办")
    check("F4 新阶段进度归零",
          [fv["progress"]["total"], fv["progress"]["done"]], [0, 0])
    check("F5 新阶段不算完成", fv["progress"]["finished"], False)
    check("F6 刑拘阶段出口含取保",
          "BAIL" in [t["action"] for t in fv["transitions"]], True)
    d = call("/cases/%d" % cid, boss)["data"]
    check("F7 强制措施=拘留", d.get("caseMeasure"), "DETENTION")
    check("F8 已终结标记清掉", find_row(boss, cid).get("flowFinished"), False)

    print("=== G. 旧阶段待办留痕、不再计入新阶段进度 ===")
    todos = call("/todos/case/%d" % cid, boss)["data"]
    check("G1 旧待办仍在（是留痕不删）", len(todos) >= 2, True)
    check("G2 旧待办状态=DONE",
          all(t["status"] == "DONE" for t in todos if t["content"] in
              ("接收刑事案件材料并登记", "完成立案审批")), True)

    print("=== H. 刑拘在办：办完新阶段待办 → 转取保 ===")
    t3 = add_opinion(boss, cid, "清案民警接收并登记")
    fv = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("H1 新阶段待办只算新的", fv["progress"]["total"], 1)
    finish_todo(boss, t3, cid)
    check("H2 新阶段办完", call("/watch/cases/%d/flow" % cid, boss)["data"]["progress"]["finished"], True)
    r = call("/watch/cases/%d/flow/transfer" % cid, boss, {"action": "BAIL"})
    check("H3 转取保成功", r.get("code"), 0)
    fv = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("H4 进入取保及监居", fv["stageLabel"], "取保及监居")
    check("H5 进度再归零",
          [fv["progress"]["total"], fv["progress"]["done"]], [0, 0])
    check("H6 强制措施=取保候审",
          call("/cases/%d" % cid, boss)["data"].get("caseMeasure"), "BAIL")

    print("=== I. 取保 → 终结；终结后无出口 ===")
    r = call("/watch/cases/%d/flow/transfer" % cid, boss, {"action": "CLOSE"})
    check("I1 终结成功", r.get("code"), 0)
    fv = call("/watch/cases/%d/flow" % cid, boss)["data"]
    check("I2 已终结", fv["stage"], "CLOSED")
    check("I3 终结后进度 100%", fv["progress"]["percent"], 100)
    check("I4 终结后无出口", fv["transferable"], False)

    print("=== J. 非法流转动作被拒 / 行政出口不变 ===")
    r2 = call("/cases", boss, {
        "name": "阶段自检-行政", "caseType": "ADMINISTRATIVE",
        "priority": "NORMAL", "sourceType": "MANUAL", "status": "PENDING_ASSIGN",
    })
    cid2 = r2["data"]["id"]
    check("J1 行政出口=处罚结案",
          [t["action"] for t in call("/watch/cases/%d/flow" % cid2, boss)["data"]["transitions"]],
          ["PUNISH"])
    check("J2 刑事案不能转 PUNISH",
          call("/watch/cases/%d/flow/transfer" % cid, boss, {"action": "PUNISH"}).get("code") != 0, True)
    check("J3 已终结案不能再流转",
          call("/watch/cases/%d/flow/transfer" % cid, boss, {"action": "CLOSE"}).get("code") != 0, True)

    print("=== K. seed 接口已下线 ===")
    r = call("/watch/cases/%d/flow/seed" % cid2, boss, method="POST")
    # 端点已删除：Spring 返回 404 且响应体不是本项目的 Result 包装，
    # 所以不能只比对 code（解析不到就是 None），判据是"拿不到 code=0 就算下线"
    check("K1 seed 接口已下线", r.get("code") == 0, False)

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
    """在案件盯办列表里找到这一行，验证列表口径（阶段名 / 待确认流转 / 进度）"""
    r = call("/watch/cases?page=1&size=200", tk)["data"]
    for row in r["list"]:
        if row["id"] == cid:
            return row
    return {}


if __name__ == "__main__":
    main()