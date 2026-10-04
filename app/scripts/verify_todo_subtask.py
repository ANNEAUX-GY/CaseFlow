#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
民警端待办改造的端到端自检：子任务 / 反馈记录 / 两条完成规则

覆盖：
1. 详情接口返回子任务 + 反馈记录
2. 添加子任务（空内容被拒）
3. 规则2：子任务未全完成时主任务不能完成（拒并列出还差哪几项）
4. 规则1：无反馈说明时不能完成
5. 规则1+2 都满足后可以完成；重复完成被拒（幂等保护）
6. 反馈记录累积多条、按时间正序
7. 子任务统计正确
8. 列表只返回主任务（子任务不混入）
9. 空反馈内容被拒
10. 撤销完成（仅管理层）

**自建专用任务 + 跑完清理**，不依赖也不污染存量数据。
（第一版复用存量主任务，结果上一轮跑完留下的子任务/反馈让断言基数全错。）
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080/api"
RUN_ID = "T%04d" % (int(time.time()) % 10000)
TOKEN = None
PASS = 0
FAIL = 0


def call(path, data=None, method=None, timeout=10):
    body = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
    req = urllib.request.Request(BASE + path, data=body,
                                 method=method or ("POST" if data is not None else "GET"))
    req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("X-Token", TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            return {"code": -1, "msg": "HTTP %s" % e.code}


def ck(label, actual, expected):
    global PASS, FAIL
    if actual == expected:
        print("  [通过] %s" % label)
        PASS += 1
    else:
        print("  [失败] %s（期望 %r，实际 %r）" % (label, expected, actual))
        FAIL += 1


def login(u, p):
    global TOKEN
    r = call("/auth/login", {"username": u, "password": p})
    TOKEN = (r.get("data") or {}).get("token")
    return bool(TOKEN)


def find_case_with_todo():
    for i in range(1, 20):
        r = call("/todos/case/%d" % i)
        if r.get("code") == 0 and r.get("data"):
            return i
    return None


def cleanup(cid):
    """删除自检产生的临时主任务（子任务随外键级联）"""
    tops = call("/todos/case/%d" % cid).get("data") or []
    for t in tops:
        if RUN_ID in (t.get("content") or ""):
            call("/todos/%d" % t["id"], {}, method="DELETE")
            print("  已清理临时任务 id=%d" % t["id"])


def run_all(cid, tid):
    print("")
    print("=== 1. 详情接口：返回子任务与反馈结构 ===")
    d = call("/todos/%d/detail" % tid)
    ck("详情接口可调", d.get("code"), 0)
    ck("含 feedbacks 字段", "feedbacks" in (d.get("data") or {}), True)
    ck("含 subtasks 字段", "subtasks" in (d.get("data") or {}), True)
    ck("新任务 parentId 为空", d["data"]["parentId"], None)
    ck("新任务子任务数 = 0", d["data"].get("subtaskTotal"), 0)
    ck("新任务反馈数 = 0", d["data"].get("feedbackCount"), 0)

    print("")
    print("=== 2. 添加子任务 ===")
    r = call("/todos/%d/subtasks" % tid, {"content": RUN_ID + " 打印询问笔录"})
    ck("可加子任务", r.get("code"), 0)
    sub1 = (r.get("data") or {}).get("id")
    ck("子任务 parentId 指向主任务", (r.get("data") or {}).get("parentId"), tid)

    r = call("/todos/%d/subtasks" % tid, {"content": RUN_ID + " 通知被询问人"})
    sub2 = (r.get("data") or {}).get("id")
    ck("可加第二个子任务", bool(sub2), True)

    r = call("/todos/%d/subtasks" % tid, {"content": "   "})
    ck("空子任务内容被拒", r.get("code") != 0, True)

    print("")
    print("=== 3. 规则2：子任务未全完成，主任务不能完成 ===")
    r = call("/todos/%d/done" % tid, {"remark": "想直接完成"})
    ck("被拒绝", r.get("code") != 0, True)
    msg = r.get("msg") or ""
    ck("错误信息说明还差几个", "2 个子任务未完成" in msg, True)
    ck("错误信息列出子任务内容", "打印询问笔录" in msg, True)

    print("")
    print("=== 4. 规则1：子任务完成 + 主任务仍需反馈说明 ===")
    r = call("/todos/%d/subtasks/toggle?done=true" % sub1, {})
    ck("子任务可直接完成（无反馈要求）", r.get("code"), 0)
    r = call("/todos/%d/subtasks/toggle?done=true" % sub2, {})
    ck("第二个子任务完成", r.get("code"), 0)

    d = call("/todos/%d/detail" % tid)["data"]
    ck("子任务统计：总数 2", d.get("subtaskTotal"), 2)
    ck("子任务统计：已完成 2", d.get("subtaskDone"), 2)

    r = call("/todos/%d/done" % tid, {"remark": ""})
    ck("子任务全完成但无反馈说明 → 仍被拒", r.get("code") != 0, True)
    ck("提示需先提交工作反馈", "工作反馈" in (r.get("msg") or ""), True)

    print("")
    print("=== 5. 提交反馈后可以完成 ===")
    r = call("/todos/%d/feedbacks" % tid, {"content": RUN_ID + " 询问笔录已制作并送达"})
    ck("反馈提交成功", r.get("code"), 0)
    r = call("/todos/%d/done" % tid, {"remark": ""})
    ck("满足两条规则后完成成功", r.get("code"), 0)
    ck("状态为已完成", (r.get("data") or {}).get("status"), "DONE")

    print("")
    print("=== 6. 重复完成应被拒（幂等保护）===")
    r = call("/todos/%d/done" % tid, {"remark": "再来一次"})
    ck("重复完成被拒", r.get("code") != 0, True)

    print("")
    print("=== 7. 反馈记录累积多条、时间正序 ===")
    call("/todos/%d/feedbacks" % tid, {"content": RUN_ID + " 第二条补充说明"})
    d = call("/todos/%d/detail" % tid)["data"]
    fbs = d.get("feedbacks") or []
    ck("反馈累积到 2 条", len(fbs), 2)
    times = [f.get("createdAt") or "" for f in fbs]
    ck("按时间正序", times, sorted(times))
    ck("反馈条数字段同步", d.get("feedbackCount"), 2)

    print("")
    print("=== 8. 空反馈内容被拒 ===")
    r = call("/todos/%d/feedbacks" % tid, {"content": "   "})
    ck("空反馈被拒", r.get("code") != 0, True)

    print("")
    print("=== 9. 列表只返回主任务（子任务不混入）===")
    tops = [t for t in call("/todos/case/%d" % cid)["data"] if not t.get("parentId")]
    ids = [t["id"] for t in tops]
    ck("子任务不在顶层列表里", sub1 in ids, False)
    ck("子任务不在顶层列表里（2）", sub2 in ids, False)
    ck("主任务仍在列表里", tid in ids, True)

    print("")
    print("=== 10. 撤销完成（仅管理层）===")
    r = call("/todos/%d/reopen" % tid, {})
    ck("撤销完成成功", r.get("code"), 0)
    ck("状态回到待办", (r.get("data") or {}).get("status"), "PENDING")


def main():
    if not login("boss", "admin123"):
        print("登录失败")
        sys.exit(1)

    print("=== 准备：自建干净的主任务（不依赖存量数据）===")
    cid = find_case_with_todo()
    if cid is None:
        print("  没有带待办的案件，先跑 seed")
        sys.exit(1)

    r = call("/todos/case/%d" % cid, {"content": "【自检】临时主任务-" + RUN_ID})
    if r.get("code") != 0:
        print("  建任务失败：" + str(r.get("msg"))[:150])
        sys.exit(1)
    tid = r["data"]["id"]
    print("  案件 caseId=%d，专用主任务 id=%d（标记 %s）" % (cid, tid, RUN_ID))

    try:
        run_all(cid, tid)
    finally:
        print("")
        print("=== 清理 ===")
        cleanup(cid)
        print("")
        print("=== 结果：通过 %d 项，失败 %d 项 ===" % (PASS, FAIL))
        sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
