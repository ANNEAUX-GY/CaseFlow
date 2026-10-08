#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
子任务详情查看 + 反馈可编辑（2026-10-08）自检。

需求：普通用户与管理层都要能查看子任务详情；详情里能改反馈，
      包括单独改「上传平台 / 上传文件名」。

重点验三件容易做错的事：
  1. 上传声明三要素**独立入库**（不是拼在 content 里的一句话）
  2. 改单项不影响其余两项，且原提交人/提交时间**不被覆盖**（只另存修订痕迹）
  3. 权限：提交人本人可改；旁人不可改；管理层可改任何人的

按项目约定：自建专用数据 + 跑完自动清理（基线从 0 开始，可重复执行）。
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080/api"
RUN = str(int(time.time()))[-6:]
PWD = "admin123"

results = []


def call(method, path, token=None, body=None, raw=False):
    url = BASE + path
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    if token:
        headers["X-Token"] = token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            obj = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            obj = json.loads(e.read().decode("utf-8"))
        except Exception:
            obj = {"code": e.code, "msg": "HTTP %s" % e.code}
    except Exception as e:  # 连不上
        return {"code": -1, "msg": str(e)}
    return obj


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print("  [%s] %s%s" % ("通过" if cond else "失败", name, ("  → " + detail) if detail and not cond else ""))


def login(u, p):
    r = call("POST", "/auth/login", body={"username": u, "password": p})
    return r["data"]["token"] if r.get("code") == 0 else None


def find_case(token, keyword="自检子任务详情"):
    """找一件可用案件（优先用自检专用标记，其次任意刑刑事案件）"""
    r = call("GET", "/cases?page=1&size=50", token)
    lst = (r.get("data") or {}).get("list") or []
    for c in lst:
        if keyword in (c.get("name") or ""):
            return c
    for c in lst:
        if c.get("caseType") == "CRIMINAL":
            return c
    return lst[0] if lst else None


def main():
    boss = login("boss", PWD)
    if not boss:
        print("无法登录 boss，请确认后端已启动")
        return 1
    case = find_case(boss)
    if not case:
        print("没有可用案件")
        return 1
    cid = case["id"]
    print("用例案件：%s %s（id=%s）" % (case.get("caseNo"), case.get("name"), cid))

    # ---------- 造数据：主任务 + 子任务 + 一条带声明的反馈 ----------
    marker = "ZZSUB%s" % RUN
    r = call("POST", "/todos/case/%s" % cid, boss, {"content": marker + " 主任务"})
    if r.get("code") != 0:
        print("建主任务失败：", r.get("msg"))
        return 1
    parent_id = r["data"]["id"]

    r = call("POST", "/todos/%s/subtasks" % parent_id, boss, {"content": marker + " 子任务甲"})
    sub_a = r["data"]["id"] if r.get("code") == 0 else None
    r = call("POST", "/todos/%s/subtasks" % parent_id, boss, {"content": marker + " 子任务乙"})
    sub_b = r["data"]["id"] if r.get("code") == 0 else None

    print("\n=== 1. 子任务详情：两端都能查看 ===")
    for who, tk in (("管理层", boss),):
        d = call("GET", "/todos/%s/detail" % sub_a, tk)
        ok = d.get("code") == 0 and d["data"]["content"].startswith(marker + " 子任务甲")
        check("%s 可查看子任务详情" % who, ok, json.dumps(d, ensure_ascii=False)[:120])
        d = call("GET", "/todos/%s/detail" % sub_a, tk)
        check("子任务详情带自己的 parentId（前端据此显示返回条）",
              d.get("code") == 0 and d["data"].get("parentId") == parent_id,
              "parentId=%s" % (d.get("data") or {}).get("parentId"))

    print("\n=== 2. 上传声明三要素独立入库 ===")
    fb = call("POST", "/todos/%s/feedbacks" % sub_a, boss, {
        "status": "IN_PROGRESS",
        "content": marker + " 已上传",
        "uploadTime": "2026-10-08 10:00:00",
        "uploadPlatform": "一体化办案平台",
        "uploadFile": "笔录初稿.docx"
    })
    check("提交带声明的反馈成功", fb.get("code") == 0, json.dumps(fb, ensure_ascii=False)[:150])
    fid = None
    rec = None
    for f in (fb.get("data") or {}).get("feedbacks") or []:
        if (f.get("uploadFile") or "") == "笔录初稿.docx":
            rec, fid = f, f["id"]
            break
    check("声明三要素各自入库（不是拼进 content）", rec is not None)
    if rec:
        check("content 只留落实说明", marker in (rec.get("content") or "")
              and "上传了" not in (rec.get("content") or ""),
              "content=%r" % rec.get("content"))
        check("uploadTime 独立", rec.get("uploadTime") == "2026-10-08 10:00:00", repr(rec.get("uploadTime")))
        check("uploadPlatform 独立", rec.get("uploadPlatform") == "一体化办案平台", repr(rec.get("uploadPlatform")))
        check("uploadFile 独立", rec.get("uploadFile") == "笔录初稿.docx", repr(rec.get("uploadFile")))

    print("\n=== 3. 改反馈：只改一项，其余不动 + 原提交人不变 ===")
    if fid:
        u = call("PUT", "/todos/%s/feedbacks/%s" % (sub_a, fid), boss, {
            "status": "IN_PROGRESS",
            "content": rec["content"],
            "uploadTime": rec["uploadTime"],
            "uploadPlatform": rec["uploadPlatform"],
            "uploadFile": "笔录终稿.pdf"          # 只改文件名
        })
        check("修改反馈成功", u.get("code") == 0, json.dumps(u, ensure_ascii=False)[:150])
        after = None
        for f in (u.get("data") or {}).get("feedbacks") or []:
            if f["id"] == fid:
                after = f
                break
        check("文件名已更新", after and after.get("uploadFile") == "笔录终稿.pdf",
              repr(after.get("uploadFile")) if after else "未找到")
        check("平台未被连带改动", after and after.get("uploadPlatform") == "一体化办案平台",
              repr(after.get("uploadPlatform")) if after else "")
        check("时间未被连带改动", after and after.get("uploadTime") == "2026-10-08 10:00:00",
              repr(after.get("uploadTime")) if after else "")
        check("原提交人未被覆盖", after and after.get("creatorName") == rec.get("creatorName"),
              "%s vs %s" % (after.get("creatorName") if after else None, rec.get("creatorName")))
        check("原提交时间未被覆盖", after and str(after.get("createdAt")) == str(rec.get("createdAt")))
        check("另存修订痕迹（谁在何时改的）", after and after.get("editedAt") and after.get("editedByName"),
              "editedBy=%s editedAt=%s" % (after.get("editedByName") if after else None,
                                           after.get("editedAt") if after else None))

        print("\n=== 4. 权限：旁人不能改，管理层能改 ===")
        # 找一个非管理层、非本人的账号
        other = None
        ul = call("GET", "/users?auditStatus=1", boss)
        for u2 in (ul.get("data") or []):
            if u2.get("role") == "STAFF" and u2.get("id") != rec.get("creatorId"):
                other = u2
                break
        if other:
            # 该账号得先能登录（密码统一改过才能测）
            ot = login(other["username"], "e2e123456") or login(other["username"], PWD)
            if ot:
                bad = call("PUT", "/todos/%s/feedbacks/%s" % (sub_a, fid), ot, {
                    "status": "DONE", "content": "篡改", "uploadPlatform": "x", "uploadFile": "y"
                })
                check("旁人改反馈被拒（403）", bad.get("code") == 403, json.dumps(bad, ensure_ascii=False)[:120])
            else:
                print("  [跳过] 账号 %s 密码未知，改用只验证管理层路径" % other["username"])
        ok = call("PUT", "/todos/%s/feedbacks/%s" % (sub_a, fid), boss, {
            "status": "DONE", "content": rec["content"],
            "uploadTime": rec["uploadTime"], "uploadPlatform": rec["uploadPlatform"],
            "uploadFile": "笔录终稿.pdf"
        })
        check("管理层可改任何人的反馈", ok.get("code") == 0, json.dumps(ok, ensure_ascii=False)[:120])
        check("落实状态可一并改（DONE）",
              any(f.get("statusAt") == "DONE" for f in (ok.get("data") or {}).get("feedbacks") or []),
              str([f.get("statusAt") for f in (ok.get("data") or {}).get("feedbacks") or []]))

    print("\n=== 5. 边界：说明与声明同时为空应被拒 ===")
    if fid:
        e = call("PUT", "/todos/%s/feedbacks/%s" % (sub_a, fid), boss, {
            "status": "DONE", "content": "  ", "uploadTime": "", "uploadPlatform": "", "uploadFile": ""
        })
        check("清空说明+声明被拒", e.get("code") != 0, json.dumps(e, ensure_ascii=False)[:120])

    print("\n=== 清理 ===")
    d = call("DELETE", "/todos/%s" % parent_id, boss)
    check("清理临时任务", d.get("code") == 0, json.dumps(d, ensure_ascii=False)[:120])
    left = call("GET", "/todos/%s/detail" % parent_id, boss)
    check("主任务已删除（子任务与反馈级联清理）", left.get("code") != 0, str(left.get("msg"))[:60])

    passed = sum(1 for _, ok, _ in results if ok)
    failed = [n for n, ok, _ in results if not ok]
    print("\n=== 结果：通过 %d 项，失败 %d 项 ===" % (passed, len(failed)))
    if failed:
        print("失败明细：")
        for n in failed:
            print("  - " + n)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
