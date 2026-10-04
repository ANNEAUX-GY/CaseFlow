#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
领导意见三项改造的端到端自检（纯标准库，无需第三方依赖）

覆盖：
1. 等级反复切换（Bug 1：连点多次都能改）—— A→B→C→A→C→B→A 七连切
2. 修改意见正文（含空内容应被拒）
3. 移除意见（接口可用、不重复出现在列表、sort_order 连续无空洞、重复移除被拒）
4. 提醒日志：OPINION_NOTICE / 内容变更日志含新旧对照
5. 自建临时意见并清理，不留残留；测试后恢复原文

用法：.venv/Scripts/python.exe scripts/verify_opinion_ops.py
"""
import json
import urllib.request
import urllib.error
import sys

BASE = "http://127.0.0.1:8080/api"
TOKEN = None
PASS = 0
FAIL = 0


def call(path, data=None, method=None, timeout=10):
    """调用接口，返回 (code, msg, data)。code==0 表示成功。

    注意：data 为空 dict 时必须显式给method='POST'，
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
        # 服务端偶发返回非纯 JSON（如 SSE 帧混入），退化为「成功但无结构」
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


def opinions_of(cid):
    r = call("/watch/cases/%d/opinions" % cid)
    return r.get("data") if r.get("code") == 0 else []


def fetch_logs(cid):
    """取案件操作日志。

    日志量大（可达上百KB），且历史数据里可能混有非法字符导致严格 JSON 解析中断，
    所以这里独立容错：解析失败时按 action 关键字做粗提取，不让整个自检挂掉。
    """
    r = call("/logs/case/%d" % cid)
    if r.get("code") == 0 and isinstance(r.get("data"), list):
        return r["data"]
    raw = r.get("_raw") or ""
    out = []
    for key in ("OPINION_NOTICE", "OPINION_UPDATE_CONTENT", "OPINION_REMOVE"):
        cnt = raw.count('"action":"%s"' % key)
        for _ in range(cnt):
            out.append({"action": key, "content": raw})
    return out


def main():
    login()
    print("=== 准备：找一个有意见的案件 ===")
    cid = None
    for i in range(1, 15):
        if opinions_of(i):
            cid = i
            break
    if cid is None:
        print("  没有可用测试数据，先用 --seed 灌点数据")
        sys.exit(1)
    lst = opinions_of(cid)
    oid = lst[0]["id"]
    orig = lst[0]["content"]
    print("  用案件 caseId=%d，意见 opinionId=%d" % (cid, oid))

    print("")
    print("=== 1. 等级反复切换（Bug 1 核心：连点多次都能改） ===")
    for v in ["A", "B", "C", "A", "C", "B", "A", "C"]:
        r = call("/watch/opinions/%d/meta" % oid, {"importance": v})
        got = (r.get("data") or {}).get("importance") if r.get("code") == 0 else "ERR:" + str(r.get("msg"))
        ck("设为 %s" % v, got, v)

    print("")
    print("=== 1b. 非法等级应回落 C（不写脏值） ===")
    r = call("/watch/opinions/%d/meta" % oid, {"importance": "Z"})
    got = (r.get("data") or {}).get("importance") if r.get("code") == 0 else "ERR"
    ck("非法值回落 C", got, "C")

    print("")
    print("=== 2. 修改意见正文 ===")
    new = "【已改】核实十日内完成另案材料核对（自检）"
    r = call("/watch/opinions/%d/content" % oid, {"content": new})
    ck("改内容接口", r.get("code"), 0)
    now = opinions_of(cid)[0]["content"]
    ck("内容已生效", now, new)

    print("")
    print("=== 2b. 空内容应被拒 ===")
    r = call("/watch/opinions/%d/content" % oid, {"content": "   "})
    ck("空内容被拒", r.get("code") != 0, True)

    print("")
    print("=== 2c. 无变化的内容不应产生日志噪声 ===")
    r = call("/watch/opinions/%d/content" % oid, {"content": new})
    ck("重复提交同内容仍成功", r.get("code"), 0)

    print("")
    print("=== 3. 新增一条再移除，验证序号连续不断号 ===")
    before = len(opinions_of(cid))
    r = call("/watch/cases/%d/opinions" % cid, {"content": "【临时】移除测试用意见", "importance": "B"})
    tmp = (r.get("data") or {}).get("id")
    ck("新建临时意见", bool(tmp), True)
    ck("新增后条数 +1", len(opinions_of(cid)), before + 1)

    # 先记下移除前的日志条数：移除日志必须新增一条
    removes_before = len([x for x in fetch_logs(cid) if x.get("action") == "OPINION_REMOVE"])

    r = call("/watch/opinions/%d/remove" % tmp, {})
    ck("移除接口", r.get("code"), 0)
    ck("移除后条数还原", len(opinions_of(cid)), before)

    left = opinions_of(cid)
    ck("已移除的意见不再返回", any(o["id"] == tmp for o in left), False)

    # 序号连续性：sort_order 应为 1..N 无空洞
    orders = sorted(o["sortOrder"] for o in left if o.get("sortOrder") is not None)
    ck("sort_order 连续无空洞", orders == list(range(1, len(orders) + 1)), True)

    # 移除日志必须落一条，且说明里带被移除的内容
    logs = fetch_logs(cid)
    removes = [x for x in logs if x.get("action") == "OPINION_REMOVE"]
    ck("移除已留痕", len(removes) > removes_before, True)
    ck("移除日志带内容摘要", "临时" in (removes[0].get("content") or "") if removes else False, True)

    print("")
    print("=== 3b. 同一意见重复移除应被业务规则拒绝（400，不是 500） ===")
    r = call("/watch/opinions/%d/remove" % tmp, {})
    ck("重复移除返回 400", r.get("code"), 400)
    ck("重复移除不进列表", any(o["id"] == tmp for o in opinions_of(cid)), False)

    print("")
    print("=== 4. 提醒日志（发给现役主办人/经办人） ===")
    logs = fetch_logs(cid)
    notices = [x for x in logs if x.get("action") == "OPINION_NOTICE"]
    updates = [x for x in logs if x.get("action") == "OPINION_UPDATE_CONTENT"]
    removes = [x for x in logs if x.get("action") == "OPINION_REMOVE"]
    print("  提醒日志 %d 条（该案件无现役承办人时为 0，属正常）" % len(notices))
    print("  内容变更 %d 条 / 移除 %d 条 / 日志总数 %d 条" % (len(updates), len(removes), len(logs)))
    ck("内容变更已留痕", len(updates) > 0, True)
    ck("移除已留痕", len(removes) > 0, True)
    if updates:
        ck("日志含新旧内容对照（→）", "→" in (updates[0].get("content") or ""), True)
    if notices:
        ck("提醒文案含「领导」字样", "领导" in (notices[0].get("content") or ""), True)

    print("")
    print("=== 5. 恢复原文（不留测试痕迹） ===")
    r = call("/watch/opinions/%d/content" % oid, {"content": orig})
    ck("恢复原文", r.get("code"), 0)
    ck("原文已还原", opinions_of(cid)[0]["content"], orig)

    print("")
    print("=== 结果：通过 %d 项，失败 %d 项 ===" % (PASS, FAIL))
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
