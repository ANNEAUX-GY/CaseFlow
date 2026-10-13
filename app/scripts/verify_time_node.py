# -*- coding: utf-8 -*-
"""时间节点 + 建案嫌疑人 端到端自检"""
import json, urllib.request, urllib.error
BASE="http://127.0.0.1:8080/api"
op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def login(u,p):
    r=urllib.request.Request(BASE+"/auth/login",data=json.dumps({"username":u,"password":p}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    return json.loads(op.open(r,timeout=10).read())["data"]["token"]
TK=login("boss","admin123")
def call(method,path,body=None):
    d=json.dumps(body).encode() if body is not None else None
    r=urllib.request.Request(BASE+path,data=d,headers={"X-Token":TK,"Content-Type":"application/json"},method=method)
    try:
        return 200, json.loads(op.open(r,timeout=20).read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b'{}')
ok=[]; bad=[]
def chk(name,cond,extra=""):
    (ok if cond else bad).append(name)
    print(("OK   " if cond else "FAIL ")+name+(" | "+str(extra) if extra else ""))

# 1) 建案：一并录入 2 个嫌疑人 + 3 个节点（不同日期，含自定义节点）
code,res=call("POST","/cases",{
  "name":"TN自检-多节点建案","caseType":"CRIMINAL","priority":"NORMAL",
  "suspects":[
    {"name":"张三","gender":"MALE","idCard":"350100199001011234","phone":"13800000001","address":"某区某路1号"},
    {"name":"李四","gender":"FEMALE","phone":"13800000002"}
  ],
  "timeNodes":[
    {"nodeKey":"ACCEPT","nodeTime":"2026-10-14 23:59:59","remindDays":3},
    {"nodeKey":"DETAIN_END","nodeTime":"2026-11-20 23:59:59","remindDays":5},
    {"nodeKey":None,"nodeName":"移送起诉期限","nodeTime":"2026-12-31 23:59:59","remindDays":7}
  ]
})
chk("建案返回 200", code==200, res.get("msg"))
cid=res.get("data",{}).get("id") if code==200 else None
if not cid:
    print("建案失败，终止"); raise SystemExit(1)
detail=res["data"]
chk("建案后嫌疑人 2 人", len(detail.get("suspects") or [])==2, [s["name"] for s in detail.get("suspects") or []])
chk("建案后节点 3 个", len(detail.get("timeNodes") or [])==3)
nodes=detail.get("timeNodes") or []
names=[n["nodeName"] for n in nodes]
chk("节点名按日期升序", names==["受案时间","刑事拘留期限届满","移送起诉期限"], names)

# 2) 主节点 = 最近未过期节点 = 受案时间（10-14 距今最近）
pri=[n for n in nodes if n["isPrimary"]==1]
chk("主节点唯一", len(pri)==1, [p["nodeName"] for p in pri])
chk("主节点=受案时间", pri and pri[0]["nodeName"]=="受案时间")
chk("案件表期限被派生为受案时间", detail.get("deadlineLabel")=="受案时间", detail.get("deadlineLabel"))
chk("案件表提醒天数被派生", detail.get("remindDays")==3, detail.get("remindDays"))
chk("列表截止期限与主节点一致", (detail.get("deadlineText") or "")[:10]==(pri[0]["nodeTimeText"] or "")[:10] if pri else False,
    detail.get("deadlineText"))

# 3) 新增更近的节点 → 主节点自动切换
code,r2=call("POST","/cases/%s/time-nodes"%cid,
    {"nodeKey":"FILING","nodeTime":"2026-10-15 23:59:59","remindDays":2})
ns=r2.get("data") or []
pri2=[n for n in ns if n["isPrimary"]==1]
chk("新增节点后共 4 个", len(ns)==4)
chk("加了更远的节点后主节点不变(受案更近)", pri2 and pri2[0]["nodeName"]=="受案时间", [p["nodeName"] for p in pri2])
_,d2=call("GET","/cases/%s"%cid)
chk("案件表期限仍是受案时间", d2["data"].get("deadlineLabel")=="受案时间", d2["data"].get("deadlineLabel"))
chk("案件表提醒天数仍是 3", d2["data"].get("remindDays")==3, d2["data"].get("remindDays"))

# 4) 删掉主节点（受案 10-14）→ 回退到剩余最近的立案（10-15）
newid=[n["id"] for n in ns if n["nodeName"]=="受案时间"][0]
code,r3=call("DELETE","/cases/time-nodes/%s"%newid)
ns=r3.get("data") or []
pri3=[n for n in ns if n["isPrimary"]==1]
chk("删除后剩 3 个", len(ns)==3)
chk("主节点回退到剩余最近的立案时间", pri3 and pri3[0]["nodeName"]=="立案时间", [p["nodeName"] for p in pri3])
_,d3=call("GET","/cases/%s"%cid)
chk("案件表期限跟着回退到立案时间", d3["data"].get("deadlineLabel")=="立案时间", d3["data"].get("deadlineLabel"))
chk("案件表提醒天数跟着回退到 2", d3["data"].get("remindDays")==2, d3["data"].get("remindDays"))

# 5) 非法输入：业务校验失败按项目约定是 HTTP 200 + body.code != 0
code,r=call("POST","/cases/%s/time-nodes"%cid,{"nodeKey":None,"nodeName":"","nodeTime":"2026-11-01 23:59:59"})
chk("自定义节点缺名称被拒", code==200 and r.get("code")!=0, r.get("msg"))
code,r=call("POST","/cases/%s/time-nodes"%cid,{"nodeKey":"BOGUS","nodeTime":"2026-11-01 23:59:59"})
chk("未知节点类型被拒", code==200 and r.get("code")!=0, r.get("msg"))
code,r=call("POST","/cases/%s/time-nodes"%cid,{"nodeKey":"FILING"})
chk("缺日期被拒", code==200 and r.get("code")!=0, r.get("msg"))

# 6) 编辑节点
nid=[n["id"] for n in ns if n["nodeName"]=="立案时间"][0]
code,r4=call("PUT","/cases/time-nodes/%s"%nid,
    {"nodeKey":"CUSTODY_CHANGE","nodeTime":"2026-10-16 23:59:59","remindDays":1})
ns=r4.get("data") or []
hit=[n for n in ns if n["id"]==nid]
chk("改预设类型后名称换成标准名", hit and hit[0]["nodeName"]=="变更强制措施时间", hit[0]["nodeName"] if hit else None)
chk("改后日期生效", hit and hit[0]["nodeTimeText"].startswith("2026-10-16"), hit[0]["nodeTimeText"] if hit else None)

# 7) 快照可撤回：删节点 → 撤一步 → 该节点应原样回来
# 注意两点：
# a) 不能用 /logs/undo-latest，那是**全局**最新一步（会撤到别的案件的日志上），
#    必须按日志 ID 撤回本案这一条；
# b) 必须取 id **最大**的那条 —— 本脚本前面已经删过一次节点，
#    日志列表里有多条 TIME_NODE_DELETE，取 [0] 会撤到更早那一步，
#    撤回来的是另一个节点，断言会假失败。
code,_=call("DELETE","/cases/time-nodes/%s"%nid)
_,d4=call("GET","/cases/%s"%cid)
after_del=[n["nodeName"] for n in (d4["data"].get("timeNodes") or [])]
_,lg=call("GET","/logs/case/%s"%cid)
logs=lg.get("data") or []
target=sorted([l for l in logs if l.get("action")=="TIME_NODE_DELETE"], key=lambda x: x["id"])
chk("删节点后有可撤回日志", len(target)>0, [l.get("action") for l in logs[:3]])
if target:
    call("POST","/logs/%s/undo"%target[-1]["id"])
    _,d5=call("GET","/cases/%s"%cid)
    back=[n["nodeName"] for n in (d5["data"].get("timeNodes") or [])]
    chk("撤回后节点原样回来", "变更强制措施时间" in back, back)
    # 撤回恢复的是快照里的 case_info（含期限三列），主节点应是 10-16 那个
    chk("撤回后案件表期限也一起还原",
        d5["data"].get("deadlineLabel")=="变更强制措施时间", d5["data"].get("deadlineLabel"))
    chk("撤回后案件表提醒天数也一起还原",
        d5["data"].get("remindDays")==1, d5["data"].get("remindDays"))

# 8) 清理
code,_=call("DELETE","/cases/%s"%cid)
chk("清理测试案件", code==200, code)

print("\n通过 %d / 失败 %d"%(len(ok),len(bad)))
if bad: print("失败项: "+", ".join(bad))
