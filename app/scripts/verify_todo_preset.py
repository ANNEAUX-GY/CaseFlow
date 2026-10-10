#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
常用待办记忆（2026-10-11）的端到端自检（纯标准库，无需第三方依赖）

覆盖：
1. GET /watch/todo-presets 接口可用、字段齐（content / importance / useCount）
2. 排序按使用次数降序；同一内容不出现两条（去重在应用层）
3. 添加待办后自动进记忆：写几次就是几次
4. 重要性沿用：本次按 A 写的，下次点标签也按 A 加
5. limit 参数生效
6. 移除意见**不回退**记忆——记忆回答的是「你常写什么」，不跟某条具体意见绑定
7. 空内容的行不进记忆（批量接口本来就会跳过空行）
8. 自建的测试意见用完即删，不留残留

用法：.venv/Scripts/python.exe scripts/verify_todo_preset.py
"""
import json
import time
import urllib.request
import urllib.error
import sys

BASE = "http://127.0.0.1:8080/api"
TOKEN = None
PASS = 0
FAIL = 0


def call(path, data=None, method=None, timeout=10):
    """调用接口，返回 (code, msg, data)。code==0 表示成功。

    注意：data 为空 dict 时必须显式给 method='POST'，
    否则会被判成 GET（remove 这类无请求体的 POST 接口会报
    "Request method 'GET' not supported"）。
    """
    url = BASE + path
    has_body = data is not None
    body = json.dumps(data, ensure_ascii=False).encode("utf-8") if has_body else None
    req = urllib.request.Request(url, data=body, method=method or ("POST" if has_body else "GET"))
    req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("X-Token", TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            return {"code": -1, "msg": "HTTP %s" % e.code}
    try:
        return json.loads(raw)
    except ValueError:
        return {"code": 0, "msg": "ok(non-json)", "data": None, "_raw": raw[:200]}


def ck(label, actual, expected):
    global PASS, FAIL
    if actual == expected:
        print("  [通过] %s" % label)
        PASS += 1
    else:
        print("  [失败] %s（期望 %r，实际 %r）" % (label, expected, actual))
        FAIL += 1


def login():
    global TOKEN
    r = call("/auth/login", {"username": "boss", "password": "admin123"})
    TOKEN = (r.get("data") or {}).get("token")
    if not TOKEN:
        print("登录失败：%s" % r.get("msg"))
        sys.exit(1)


def presets(limit=None):
    path = "/watch/todo-presets" + (("?limit=%d" % limit) if limit else "")
    r = call(path)
    return r.get("data") if r.get("code") == 0 else None


def opinion_ids_of(cid):
    """返回 {content: [opinionId, ...]}，清理用"""
    r = call("/watch/cases/%d/opinions" % cid)
    out = {}
    for o in (r.get("data") if r.get("code") == 0 else []) or []:
        out.setdefault(o.get("content"), []).append(o.get("id"))
    return out


def first_case_id():
    r = call("/cases?page=1&size=1")
    d = r.get("data") or {}
    for key in ("list", "records", "items"):
        if isinstance(d.get(key), list) and d[key]:
            return d[key][0].get("id")
    return 1


def main():
    login()
    cid = first_case_id()
    print("=== 准备：用案件 caseId=%s ===" % cid)

    print("")
    print("=== 1. 接口可用与字段 ===")
    base = presets()
    ck("接口返回列表", isinstance(base, list), True)
    if not isinstance(base, list):
        print("取不到常用待办，中止")
        sys.exit(1)
    print("  当前记忆 %d 条（首次查询会按本人历史意见自动回填）" % len(base))
    if base:
        b0 = base[0]
        ck("含 content", bool(b0.get("content")), True)
        ck("含 useCount", isinstance(b0.get("useCount"), int), True)
        ck("useCount 至少为 1", all((x.get("useCount") or 0) >= 1 for x in base), True)

    print("")
    print("=== 2. 排序按次数降序 + 内容不重复 ===")
    counts = [x.get("useCount") or 0 for x in base]
    ck("次数非递增", counts == sorted(counts, reverse=True), True)
    texts = [(x.get("content") or "").strip() for x in base]
    ck("内容无重复项", len(texts) == len(set(texts)), True)

    print("")
    print("=== 3. 添加待办自动进记忆（写几次就是几次） ===")
    marker = "自检-常用待办-%d" % int(time.time())
    before = next((x for x in presets() if (x.get("content") or "").strip() == marker), None)
    ck("测试内容此前不在记忆里", before is None, True)

    for i in range(1, 4):
        r = call("/watch/cases/%s/opinions/batch" % cid,
                 {"items": [{"content": marker, "deadline": "", "importance": "A"}]})
        ck("第 %d 次添加待办" % i, r.get("code"), 0)
        hit = next((x for x in presets() if (x.get("content") or "").strip() == marker), None)
        ck("第 %d 次后记忆里次数 = %d" % (i, i), (hit or {}).get("useCount"), i)

    print("")
    print("=== 4. 重要性沿用（本次按 A 写，标签就记住 A） ===")
    hit = next((x for x in presets() if (x.get("content") or "").strip() == marker), None)
    ck("记忆里记为 A", (hit or {}).get("importance"), "A")

    print("")
    print("=== 5. limit 生效 ===")
    one = presets(1)
    ck("limit=1 最多回 1 条", len(one) <= 1, True)
    if len(presets()) > 1:
        ck("limit=1 确实只回 1 条", len(one), 1)

    print("")
    print("=== 6. 空内容不进记忆 ===")
    n_before = len(presets())
    call("/watch/cases/%s/opinions/batch" % cid,
         {"items": [{"content": "   ", "deadline": "", "importance": "C"}]})
    ck("空行不新增记忆", len(presets()), n_before)

    print("")
    print("=== 7. 移除意见不回退记忆（记忆记的是习惯，不是某条意见） ===")
    ids = opinion_ids_of(cid).get(marker) or []
    ck("测试意见已建出 %d 条" % 3, len(ids), 3)
    for oid in ids:
        call("/watch/opinions/%d/remove" % oid, {})
    hit = next((x for x in presets() if (x.get("content") or "").strip() == marker), None)
    ck("移除后记忆仍在", bool(hit), True)
    ck("移除后次数不回退", (hit or {}).get("useCount"), 3)
    ck("测试意见已清干净", len(opinion_ids_of(cid).get(marker) or []), 0)

    print("")
    print("=== 结果：通过 %d 项，失败 %d 项 ===" % (PASS, FAIL))
    print("（注：记忆条目本身刻意保留——它是「本人常写什么」的长期记录，")
    print("  与某条具体意见无关；本脚本只清掉建出来的测试意见。）")


if __name__ == "__main__":
    main()
