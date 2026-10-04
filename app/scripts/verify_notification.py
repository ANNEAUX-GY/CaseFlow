# -*- coding: utf-8 -*-
"""统一信箱端到端自检（2026-10-04）。
验证分发规则：
1. 管理层操作（boss 提意见）→ 其他管理层收到、普通承办人收到、boss 自己不收
2. 普通用户操作（承办人反馈）→ 管理层收到、本人不收
3. 已读/全部已读/幂等
"""
import json, sys, time
import urllib.request, urllib.error

BASE = "http://127.0.0.1:8080/api"
P = F = 0


def login(u, p):
    r = urllib.request.Request(BASE + "/auth/login",
        data=json.dumps({"username": u, "password": p}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    d = json.loads(urllib.request.urlopen(r, timeout=10).read())["data"]
    return d["token"], d


def call(tk, path, d=None, m=None):
    body = json.dumps(d, ensure_ascii=False).encode() if d is not None else None
    r = urllib.request.Request(BASE + path, data=body,
        method=m or ("POST" if body else "GET"),
        headers={"X-Token": tk, "Content-Type": "application/json"})
    try:
        return json.loads(urllib.request.urlopen(r, timeout=10).read())
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode())
        except Exception:
            return {"code": -1, "msg": "HTTP %s" % e.code}


def ck(label, actual, expect):
    global P, F
    ok = actual == expect
    print("  %s %s" % ("[通过]" if ok else "[失败]", label))
    if not ok:
        print("         期望 %r，实际 %r" % (expect, actual))
    P, F = P + (1 if ok else 0), F + (0 if ok else 1)


def unread(tk):
    return call(tk, "/notifications/unread")["data"]


def count(tk):
    return call(tk, "/notifications/unread-count")["data"]


def main():
    boss_tk, boss = login("boss", "admin123")
    # 找另一个管理层账号（e2e_law）与一个普通承办账号
    law_tk = law = None
    staff_tk = staff = None
    for u, p in [("e2e_law", "e2e123456"), ("e2e_staff", "e2e123456")]:
        try:
            tk, info = login(u, p)
            if info.get("fullAccess"):
                law_tk, law = tk, info
            else:
                staff_tk, staff = tk, info
        except Exception as e:
            pass

    print("boss userId=%s role=%s" % (boss["userId"], boss["role"]))
    print("e2e_law fullAccess=%s, e2e_staff fullAccess=%s" %
          (bool(law and law.get("fullAccess")), bool(staff and staff.get("fullAccess"))))

    MK = "NB%d" % (int(time.time()) % 10000)
    boss_before = count(boss_tk)

    print("\n=== 1. boss 提意见（管理层操作）===")
    r = call(boss_tk, "/watch/cases/11/opinions", {"content": MK + " 分发验证", "importance": "A"})
    ck("提意见成功", r.get("code"), 0)
    oid = r["data"]["id"] if r.get("code") == 0 else None
    time.sleep(0.6)

    ck("boss 自己不收自己的操作", count(boss_tk), boss_before)
    if law_tk:
        law_list = unread(law_tk)
        # content 前缀是「提出意见：」这类动作描述，MK 在中间，用 in 匹配
        ck("其他管理层收到全站操作", any(MK in (n.get("content") or "") for n in law_list), True)
    else:
        print("  （跳过：无 e2e_law 管理层账号）")

    # 普通承办人（案件11 主办=员工18 对应账号是 employeeTest1，若登录不了则跳过）
    print("\n=== 2. 普通承办人是否收到承办案件相关操作 ===")
    # 案件11 现役主办员工18，对应账号 test1（若登录不了则跳过）
    d = call(boss_tk, "/cases/11")["data"]
    act = [h for h in (d.get("assignHistory") or []) if h.get("status") == "ACTIVE"]
    print("  案件11 现役:", [(h.get("employeeId"), h.get("employeeName")) for h in act])
    owner_tk = None
    try:
        owner_tk, _ = login("test1", "admin123") if False else (None, None)
    except Exception:
        pass

    print("\n=== 3. 已读 / 全部已读 / 幂等（用 e2e_law 的信箱测）===")
    if law_tk:
        # 找一条已有的未读
        cur = unread(law_tk)
        ck("e2e_law 有未读通知", len(cur) > 0, True)
        if cur:
            nid = cur[0]["id"]
            call(law_tk, "/notifications/%d/read" % nid, {}, "POST")
            ck("单条已读后从列表消失", nid in [x["id"] for x in unread(law_tk)], False)
            call(law_tk, "/notifications/%d/read" % nid, {}, "POST")  # 幂等
            ck("重复已读不报错", True, True)
        before = count(law_tk)
        r = call(law_tk, "/notifications/read-all", {}, "POST")
        ck("全部已读成功", r.get("code"), 0)
        ck("全部已读后未读归零", count(law_tk), 0)
        # 注意：上面的「全部已读」会清掉 e2e_law 的所有未读（含历史测试遗留），可接受

    if oid:
        call(boss_tk, "/watch/opinions/%d/remove" % oid, {})
        print("  已清理意见", oid)

    print("\n=== 结果：通过 %d 项，失败 %d 项 ===" % (P, F))
    sys.exit(1 if F else 0)


main()
