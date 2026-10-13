# -*- coding: utf-8 -*-
"""指派 / 建案 一并录入待办 端到端自检（2026-10-13）

覆盖用户本轮需求：「指派功能中的待办创建逻辑要和案件详情中的添加待办一致，
同时在创建案件功能中可以自行决定是否添加待办」。

判据不是"看起来一样"，而是**三条链路产出的数据形态必须完全相同**：
    case_leader_opinion（内容/截止时间/重要性）→ 派生 case_todo（opinion_id/urgency/importance/deadline/flow_stage）
只要有一处绕过意见直接插 case_todo，这条链路就不一致。

另含两条撤回断言：撤回后不留孤儿意见 / 不留意见字段丢失的待办
（2026-10-13 修了 CaseTodoMapper.insertWithId 漏列的老问题）。
"""
import json, urllib.request, urllib.error, time

BASE = "http://127.0.0.1:8080/api"
op = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def login(u, p):
    r = urllib.request.Request(BASE + "/auth/login",
                               data=json.dumps({"username": u, "password": p}).encode(),
                               headers={"Content-Type": "application/json"}, method="POST")
    return json.loads(op.open(r, timeout=10).read())["data"]["token"]


TK = login("boss", "admin123")


def call(method, path, body=None, tk=None):
    d = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=d,
                               headers={"X-Token": tk or TK, "Content-Type": "application/json"},
                               method=method)
    try:
        return 200, json.loads(op.open(r, timeout=20).read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b'{}')


ok = []
bad = []


def chk(name, cond, extra=""):
    (ok if cond else bad).append(name)
    print(("OK   " if cond else "FAIL ") + name + (" | " + str(extra) if extra else ""))


def todos_of(cid):
    _, r = call("GET", "/todos/case/%s" % cid)
    return r.get("data") or []


def opinions_of(cid):
    _, r = call("GET", "/watch/cases/%s/opinions" % cid)
    return r.get("data") or []


MK = "TDS"   # 标记前缀，避免与既有数据混淆

# ==================================================================
# A. 建案时带待办（不指派）
# ==================================================================
print("\n=== A. 建案一并录入待办 ===")
code, res = call("POST", "/cases", {
    "name": MK + "-建案带待办",
    "caseType": "CRIMINAL",
    "priority": "NORMAL",
    "todos": [
        {"content": MK + " 走访三名证人", "deadline": "2026-11-05 18:00:00", "importance": "A"},
        {"content": MK + " 调取银行流水", "deadline": "", "importance": "B"},
        {"content": "", "deadline": "", "importance": "C"},          # 空行：应被跳过
        {"content": MK + " 出具情况说明", "deadline": "2026-12-01 18:00:00", "importance": "C"},
    ],
})
chk("A0 建案返回 200", code == 200, res.get("msg"))
cid1 = res.get("data", {}).get("id") if code == 200 else None
if not cid1:
    print("建案失败，终止")
    raise SystemExit(1)

ops1 = opinions_of(cid1)
tds1 = todos_of(cid1)
chk("A1 空行被跳过，只落 3 条意见", len(ops1) == 3, [o["content"] for o in ops1])
chk("A2 派生 3 条待办", len(tds1) == 3, [t["content"] for t in tds1])
chk("A3 每条待办都带 opinionId（与详情添加待办同源）",
    all(t.get("opinionId") for t in tds1), [t.get("opinionId") for t in tds1])
chk("A4 待办内容与意见一致", sorted(t["content"] for t in tds1) == sorted(o["content"] for o in ops1))
byc = {t["content"]: t for t in tds1}
a1 = byc.get(MK + " 走访三名证人", {})
chk("A5 A 级 → 紧急 + 重点", a1.get("urgency") == "URGENT" and a1.get("importance") == "KEY",
    (a1.get("urgency"), a1.get("importance")))
a2 = byc.get(MK + " 调取银行流水", {})
chk("A6 B 级 → 较急 + 次重点", a2.get("urgency") == "HIGH" and a2.get("importance") == "MEDIUM",
    (a2.get("urgency"), a2.get("importance")))
a3 = byc.get(MK + " 出具情况说明", {})
chk("A7 C 级 → 一般 + 一般", a3.get("urgency") == "NORMAL" and a3.get("importance") == "NORMAL",
    (a3.get("urgency"), a3.get("importance")))
chk("A8 截止时间落到待办",
    str(a1.get("deadline") or "").startswith("2026-11-05"), a1.get("deadline"))
chk("A9 没填截止的不硬塞一个",
    byc.get(MK + " 调取银行流水", {}).get("deadline") in (None, ""),
    byc.get(MK + " 调取银行流水", {}).get("deadline"))
# 阶段章（flow_stage）是内部字段，不在 CaseTodoVO 里暴露；
# 用户能看见的效果是「这些待办被算进当前阶段的进度」，用 flow 接口的 progress 判。
# 注意：/cases/{id} 详情**不填** flowDone/flowTotal（那是列表与盯办接口的字段），
# 详情页看阶段进度走 /watch/cases/{id}/flow。
_, fr = call("GET", "/watch/cases/%s/flow" % cid1)
fv1 = fr.get("data") or {}
pg1 = fv1.get("progress") or {}
chk("A10 待办被计入当前阶段进度（建案带的待办不是游离的）",
    pg1.get("total") == 3, (fv1.get("stage"), pg1))
chk("A11 尚未完成，阶段进度 0/3",
    pg1.get("done") == 0 and pg1.get("finished") is False, pg1)
chk("A12 阶段为初查", fv1.get("stage") == "INITIAL", fv1.get("stage"))

# ==================================================================
# B. 建案不带待办 —— 必须完全不受影响（用户要求"自行决定是否"）
# ==================================================================
print("\n=== B. 建案不带待办 ===")
code, res = call("POST", "/cases", {
    "name": MK + "-建案无待办",
    "caseType": "CRIMINAL",
    "priority": "NORMAL",
    "todos": [],           # 显式空数组
})
cid2 = res.get("data", {}).get("id") if code == 200 else None
chk("B0 建案返回 200", code == 200, res.get("msg"))
chk("B1 空数组不报错也不生成任何待办", len(todos_of(cid2)) == 0 and len(opinions_of(cid2)) == 0,
    (len(todos_of(cid2)), len(opinions_of(cid2))))
code, res = call("POST", "/cases", {
    "name": MK + "-建案不带todos字段",
    "caseType": "CRIMINAL",
    "priority": "NORMAL",
})
cid3 = res.get("data", {}).get("id") if code == 200 else None
chk("B2 字段完全省略也能建案", code == 200, res.get("msg"))
chk("B3 老前端不带该字段时零待办", len(todos_of(cid3)) == 0)

# ==================================================================
# C. 指派时带待办 —— 与详情添加待办必须是同一形态
# ==================================================================
print("\n=== C. 指派一并录入待办 ===")
# 取一个真实员工来指派。
# /employees 返回的是**树**（按部门嵌套），顶层是部门不是人；
# /employees/search 才是扁平列表。这里用 search，兼容两种返回。
_, emps = call("GET", "/employees/search?limit=1000")


def flat_emps(data):
    out = []

    def walk(nodes):
        for n in nodes or []:
            if isinstance(n, list):
                walk(n)
                continue
            if n.get("children") is not None and not n.get("name"):
                walk(n.get("children"))
                continue
            if n.get("id") is not None:
                out.append(n)

    walk(data if isinstance(data, list) else (data or {}).get("records") or [])
    return out


target_emp = next((e for e in flat_emps(emps.get("data")) if e.get("id")), None)
chk("C0 找到可用的在职员工", target_emp is not None,
    target_emp and target_emp.get("name"))
if not target_emp:
    raise SystemExit(1)

code, res = call("POST", "/cases/%s/assign" % cid3, {
    "ownerId": target_emp["id"],
    "memberIds": [],
    "note": MK + " 指派",
    "todos": [
        {"content": MK + " 24 小时内提取通话记录", "deadline": "2026-10-20 18:00:00", "importance": "A"},
        {"content": MK + " 上门送达告知书", "deadline": "", "importance": "C"},
    ],
})
chk("C1 指派返回 200", code == 200, res.get("msg"))
ops3 = opinions_of(cid3)
tds3 = todos_of(cid3)
chk("C2 指派后生成 2 条意见", len(ops3) == 2, [o["content"] for o in ops3])
chk("C3 派生 2 条待办且都带 opinionId", len(tds3) == 2 and all(t.get("opinionId") for t in tds3),
    [(t["content"], t.get("opinionId")) for t in tds3])
b1 = {t["content"]: t for t in tds3}.get(MK + " 24 小时内提取通话记录", {})
chk("C4 重要程度按 A 映射（与详情一致）",
    b1.get("urgency") == "URGENT" and b1.get("importance") == "KEY",
    (b1.get("urgency"), b1.get("importance")))
chk("C5 截止时间随待办落库", str(b1.get("deadline") or "").startswith("2026-10-20"), b1.get("deadline"))
_, fr3 = call("GET", "/watch/cases/%s/flow" % cid3)
fv3 = fr3.get("data") or {}
chk("C6 待办落在案件当前阶段（计入进度）",
    fv3.get("stage") == "INITIAL" and (fv3.get("progress") or {}).get("total") == 2,
    (fv3.get("stage"), fv3.get("progress")))

# ==================================================================
# D. 再次指派不改既有待办（只追加，不是覆盖）
# ==================================================================
print("\n=== D. 再次指派不删既有待办 ===")
code, res = call("POST", "/cases/%s/assign" % cid3, {
    "ownerId": target_emp["id"],
    "memberIds": [],
    "note": MK + " 改派（不带待办）",
    "todos": [],
})
chk("D1 再次指派返回 200", code == 200, res.get("msg"))
chk("D2 既有待办一条没少（不再按内容猜着删）", len(todos_of(cid3)) == 2,
    [t["content"] for t in todos_of(cid3)])

# ==================================================================
# E. 撤回建案：意见与待办必须成对清掉，不留孤儿
# ==================================================================
print("\n=== E. 撤回建案不留孤儿 ===")
_, lg = call("GET", "/logs/case/%s" % cid1)
logs = [l for l in (lg.get("data") or []) if l.get("action") == "CREATE"]
chk("E0 有建案日志", len(logs) > 0, [l.get("action") for l in (lg.get("data") or [])[:3]])
if logs:
    call("POST", "/logs/%s/undo" % logs[-1]["id"])
    _, r = call("GET", "/cases/%s" % cid1)
    # 撤回是「写回 before 快照」，before 里案件不存在；
    # 查详情因此得到「案件不存在」。项目统一把业务异常放在 body.code 里，
    # HTTP 仍是 200，所以判据是 data 为空 + msg 明确说了不存在。
    chk("E1 案件已回到「不存在」",
        not r.get("data") and "不存在" in (r.get("msg") or ""),
        (r.get("code"), r.get("msg")))
    chk("E2 待办一条不剩（不留孤儿待办）", len(todos_of(cid1)) == 0, [t["content"] for t in todos_of(cid1)])
    chk("E3 意见一条不剩（不留孤儿意见）", len(opinions_of(cid1)) == 0,
        [o["content"] for o in opinions_of(cid1)])

# ==================================================================
# F. 撤回指派：意见与待办一起回来，且字段不丢
# ==================================================================
print("\n=== F. 撤回指派，待办字段不丢 ===")
_, lg = call("GET", "/logs/case/%s" % cid3)
alog = sorted([l for l in (lg.get("data") or [])
               if l.get("action") == "ASSIGN"], key=lambda x: x["id"])
chk("F0 有两次指派日志", len(alog) == 2, [l.get("action") for l in (lg.get("data") or [])[:5]])
if len(alog) == 2:
    call("POST", "/logs/%s/undo" % alog[-1]["id"])
    tds3b = todos_of(cid3)
    chk("F1 撤回第二次指派后待办仍是 2 条", len(tds3b) == 2, [t["content"] for t in tds3b])
    chk("F2 opinionId 没丢（仍指向真实意见）", all(t.get("opinionId") for t in tds3b),
        [t.get("opinionId") for t in tds3b])
    b2 = {t["content"]: t for t in tds3b}.get(MK + " 24 小时内提取通话记录", {})
    chk("F3 撤回后截止时间还在（insertWithId 曾漏列）",
        str(b2.get("deadline") or "").startswith("2026-10-20"), b2.get("deadline"))
    chk("F4 撤回后重要程度还在",
        b2.get("urgency") == "URGENT" and b2.get("importance") == "KEY",
        (b2.get("urgency"), b2.get("importance")))

# ==================================================================
# G. 常用待办记忆：建案 / 指派写的也要被记住
# ==================================================================
print("\n=== G. 常用待办记忆 ===")
# limit 要够大：本库是长期使用的老库，高频短语（useCount 很高）会排在前面，
# 用 20 会被它们挤掉——那不是"没记住"，只是没排进前 20。
_, r = call("GET", "/watch/todo-presets?limit=200")
presets = r.get("data") or []
chk("G1 建案 / 指派写的待办也进了常用待办",
    any(MK in (p.get("content") or "") for p in presets),
    [p.get("content") for p in presets if MK in (p.get("content") or "")])
chk("G2 常用待办记住了重要程度",
    any(MK in (p.get("content") or "") and (p.get("importance") or "C") == "A" for p in presets),
    [(p.get("content"), p.get("importance")) for p in presets if MK in (p.get("content") or "")])

# ==================================================================
# H. 权限：普通民警不能在建案/指派里塞待办
# ==================================================================
print("\n=== H. 普通民警无入口（后端真拦截）===")
try:
    STK = login("e2e_staff", "e2e123456")
    code, res = call("POST", "/cases/%s/assign" % cid3,
                     {"ownerId": target_emp["id"], "todos": [{"content": MK + " 越权待办"}]}, tk=STK)
    chk("H1 民警指派被拒（403）", code == 403, (code, res.get("msg")))
    chk("H2 越权待办没有落库",
        not any(MK + " 越权待办" in t["content"] for t in todos_of(cid3)),
        [t["content"] for t in todos_of(cid3)])
except Exception as e:
    print("  ! 没有 e2e_staff 账号，跳过（先跑 scripts/verify_lan.py 生成）：%s" % e)

# ==================================================================
# 清理
# ==================================================================
print("\n=== 清理 ===")
for c in (cid2, cid3):
    if c:
        code, _ = call("DELETE", "/cases/%s" % c)
        chk("清理案件 %s" % c, code == 200, code)

# 清掉本脚本写进「常用待办」的记忆条目。
#
# 为什么必须清：常用待办是**长期记忆**（设计上刻意不随意见删除而回退），
# 按次数排序、UI 只渲染前 8 条。本脚本每跑一次就给 MK-* 攒 1~2 次，
# 几轮之后会把 cf-e2e-todo-preset.mjs 刚写的标记项挤出前 8，
# 表现为「B1 刚写的那条排进了列表」突然失败——那是测试互相污染，不是产品缺陷。
#
# 注意：本脚本跑的时候后端还开着，**连不上 H2 文件库**（单进程独占），
# 所以这里只提示不自动清；真要清请在**停掉后端后**执行：
#
#     cd app/backend
#     jdk8/bin/java -cp ../../tools/m2repo/com/h2database/h2/2.1.214/h2-2.1.214.jar \
#         org.h2.tools.RunScript -user sa -password "" -showResults \
#         -url "jdbc:h2:file:./data/h2/caseflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1" \
#         -script <(echo "DELETE FROM todo_preset WHERE content LIKE 'TDS%';")
#
# 连接串必须与 application-dev.yml 完全一致，否则 H2 认不出已有的
# INFORMATION_SCHEMA，报 "Schema \"public\" not found"（DATABASE_TO_LOWER 的坑）。
_, r = call("GET", "/watch/todo-presets?limit=200")
mine = [p for p in (r.get("data") or []) if MK in (p.get("content") or "")]
if mine:
    print("  ! 本脚本的记忆条目仍在库里（%d 条），会把 cf-e2e-todo-preset 的前 8 挤掉：%s"
          % (len(mine), [p["content"][:16] for p in mine]))

print("\n通过 %d / 失败 %d" % (len(ok), len(bad)))
if bad:
    print("失败项: " + ", ".join(bad))
    raise SystemExit(1)
