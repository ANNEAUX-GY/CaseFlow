# -*- coding: utf-8 -*-
"""意见收件箱端到端自检（2026-10-04）。
验证：未读列表与欢迎弹窗同口径、点开即已读且从列表消失、幂等、全部已读、
     反馈自动落已读、新提意见重新可见。
用 boss（全权限，可见全部案件）测接口逻辑；权限收敛已由 myVisibleCaseIds 保证。
"""
import json, sys, time
import urllib.request, urllib.error

BASE = "http://127.0.0.1:8080/api"
P = F = 0


def login(u, p):
    r = urllib.request.Request(BASE + "/auth/login",
        data=json.dumps({"username": u, "password": p}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    return json.loads(urllib.request.urlopen(r, timeout=10).read())["data"]["token"]


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
    tk = login("boss", "admin123")
    MK = "IB%d" % (int(time.time()) % 10000)

    # 造一条新意见（未反馈、未读）到案件 11
    r = call(tk, "/watch/cases/11/opinions", {"content": MK + " 收件箱验证意见", "importance": "B"})
    ck("造新意见", r.get("code"), 0)
    oid = r["data"]["id"]

    # 清掉该用户在案件 11 上可能的历史已读干扰：直接读 unread 前先记录基线
    before = call(tk, "/watch/opinions/unread")["data"]
    ids_before = [x["id"] for x in before]
    summary_before = call(tk, "/todos/welcome-summary")["data"]["newOpinionCount"]

    print("=== 1. 未读列表与欢迎弹窗同口径 ===")
    ck("新意见出现在未读列表", oid in ids_before, True)
    cnt_unread = len(ids_before)
    # 欢迎口径 = 未反馈且未读，数值可能不完全等于收件箱条数（收件箱=同一口径，应相等）
    ck("welcomeSummary 与收件箱条数一致", summary_before, cnt_unread)

    print("=== 2. 点开一条 = 已读 = 从列表消失 ===")
    r = call(tk, "/watch/opinions/%d/read" % oid, {})
    ck("标记已读成功", r.get("code"), 0)
    after = call(tk, "/watch/opinions/unread")["data"]
    ck("已读后从列表消失", oid in [x["id"] for x in after], False)
    ck("welcomeSummary 数字同步 -1",
       call(tk, "/todos/welcome-summary")["data"]["newOpinionCount"], summary_before - 1)

    print("=== 3. 幂等 ===")
    r = call(tk, "/watch/opinions/%d/read" % oid, {})
    ck("重复标已读不报错", r.get("code"), 0)

    print("=== 4. 全部已读 ===")
    left = len(call(tk, "/watch/opinions/unread")["data"])
    r = call(tk, "/watch/opinions/read-all", {})
    ck("全部已读成功", r.get("code"), 0)
    ck("返回清掉条数", r.get("data"), left)
    ck("列表清空", call(tk, "/watch/opinions/unread")["data"], [])
    ck("welcomeSummary 归零", call(tk, "/todos/welcome-summary")["data"]["newOpinionCount"], 0)

    print("=== 5. 真实反馈路径（待办反馈→意见回写）后自动离开未读 ===")
    # 生产上反馈走待办（TodoService.addFeedback，管理层可操作）→ syncOpinion 回写
    # opinion.feedback_status → 未读口径里 feedback_status IS NULL 自然排除。
    # 直连意见反馈端点 boss 会 403（只有承办人可反馈，权限正确行为），故走待办路径。
    r = call(tk, "/watch/cases/11/opinions", {"content": MK + " 反馈即已读", "importance": "C"})
    oid2 = r["data"]["id"]
    todos = call(tk, "/todos/case/11")["data"]
    tid2 = None
    for t in todos:
        if t.get("opinionId") == oid2:
            tid2 = t["id"]
            break
    ck("意见已派生待办", tid2 is not None, True)
    if tid2:
        # 用「进行中」反馈：syncOpinion 的既有设计是任务未完成时 DONE 反馈会被
        # 视为无效清空（完成状态以任务实际完成为准），IN_PROGRESS 才算"已反馈"
        r = call(tk, "/todos/%d/feedbacks" % tid2, {"status": "IN_PROGRESS", "content": MK + " 办理中"})
        ck("待办反馈成功", r.get("code"), 0)
        ck("反馈后意见离开未读列表",
           oid2 in [x["id"] for x in call(tk, "/watch/opinions/unread")["data"]], False)

    print("=== 6. 清理测试意见 ===")
    n = 0
    for o in call(tk, "/watch/cases/11/opinions")["data"]:
        if (o.get("content") or "").startswith(MK):
            call(tk, "/watch/opinions/%d/remove" % o["id"], {})
            n += 1
    print("  清理 %d 条（软删）" % n)

    print("\n=== 结果：通过 %d 项，失败 %d 项 ===" % (P, F))
    sys.exit(1 if F else 0)


main()
