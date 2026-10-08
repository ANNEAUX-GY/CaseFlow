# -*- coding: utf-8 -*-
"""疑问回答修订端到端自检（2026-10-08）。
验证：管理层可修订回答；首次回答人/时间不被覆盖；修订痕迹落库；
     普通民警无权修订；未回答的不能「修订」；内容未变不刷痕迹；空内容被拒。
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


def main():
    boss_tk, boss = login("boss", "admin123")
    MK = "AE%d" % (int(time.time()) % 10000)

    print("=== 准备：建一条疑问并回答 ===")
    q = call(boss_tk, "/questions", {"caseId": 11, "todoId": None,
                                     "content": MK + " 修订验证：材料需要盖章吗？"})["data"]
    qid = q["id"]
    r = call(boss_tk, "/questions/%d/answer" % qid, {"content": MK + " 原回答：不用"})
    ck("回答成功", r.get("code"), 0)
    first = r["data"]
    first_by, first_at = first["answerBy"], first["answeredAt"]
    print("     首次回答人=%s 时间=%s" % (first.get("answerByName"), first_at))

    print("\n=== 1. 管理层修订回答 ===")
    r = call(boss_tk, "/questions/%d/answer" % qid, {"content": MK + " 修订后：需要盖章"}, "PUT")
    ck("修订成功", r.get("code"), 0)
    d = r["data"]
    ck("内容已更新", d["answer"], MK + " 修订后：需要盖章")
    ck("首次回答人未被覆盖", d["answerBy"], first_by)
    ck("首次回答时间未被覆盖", d["answeredAt"], first_at)
    ck("修订人已记录", d.get("answerEditedByName"), boss.get("displayName"))
    ck("修订时间已记录", d.get("answerEditedAt") is not None, True)

    print("\n=== 2. 内容未变不刷修订痕迹 ===")
    # 没有单条查询端点，从案件列表里取该条
    def fetch_q(qid_):
        for x in call(boss_tk, "/questions/case/11")["data"]:
            if x["id"] == qid_:
                return x
        return {}
    again = fetch_q(qid)
    r = call(boss_tk, "/questions/%d/answer" % qid, {"content": d["answer"]}, "PUT")
    ck("内容未变仍成功", r.get("code"), 0)
    ck("修订时间未变", r["data"].get("answerEditedAt"), again.get("answerEditedAt"))

    print("\n=== 3. 校验 ===")
    r = call(boss_tk, "/questions/%d/answer" % qid, {"content": "   "}, "PUT")
    ck("空内容被拒", r.get("code") != 0, True)
    r = call(boss_tk, "/questions/%d/answer" % qid, {"content": MK + " 再改一次"}, "PUT")
    ck("可多次修订", r["data"]["answer"], MK + " 再改一次")

    print("\n=== 4. 普通民警无权修订 ===")
    try:
        st_tk, st = login("e2e_staff", "e2e123456")
        r = call(st_tk, "/questions/%d/answer" % qid, {"content": "民警改回答"}, "PUT")
        ck("民警修订被拒(403)", r.get("code"), 403)
    except Exception as e:
        print("  （跳过：e2e_staff 登录失败 %s）" % str(e)[:40])

    print("\n=== 5. 未回答的不能「修订」===")
    q2 = call(boss_tk, "/questions", {"caseId": 11, "todoId": None,
                                      "content": MK + " 未回答的"})["data"]
    r = call(boss_tk, "/questions/%d/answer" % q2["id"], {"content": "直接改"}, "PUT")
    ck("未回答的修订被拒", r.get("code") != 0, True)
    print("     返回：%s" % (r.get("msg") or "")[:40])

    print("\n=== 6. 清理 ===")
    for x in call(boss_tk, "/questions/case/11")["data"]:
        if (x.get("content") or "").startswith(MK):
            call(boss_tk, "/questions/%d" % x["id"], {}, "DELETE")
    print("  已清理标记为 %s 的测试疑问" % MK)

    print("\n=== 结果：通过 %d 项，失败 %d 项 ===" % (P, F))
    sys.exit(1 if F else 0)


main()