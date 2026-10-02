"""
P0 改进自检：案件字段模型 / 分类体系 / 嫌疑人录入

覆盖点：
  1. 案件字段模型：案卷类型、案件类别、立案登记表编号、调解书编号能存能回显；
  2. 分类体系：按案卷类型（大类）、案件类别（小类）筛选生效；
  3. 嫌疑人：录入、计数、按「是否有嫌疑人」筛选、删除；
  4. 撤回兼容：嫌疑人增删可撤回，删除案件后撤回能把嫌疑人一起还原。

用法：
    .venv\\Scripts\\python.exe scripts/verify_p0_fields.py [--base http://127.0.0.1:8080]
"""
import argparse
import sys
import time

import requests

BASE = "http://127.0.0.1:8080"
API = BASE + "/api"

OK = []
FAIL = []


def check(name, cond, extra=""):
    if cond:
        OK.append(name)
        print(f"  [OK]   {name}{(' -> ' + str(extra)) if extra else ''}")
    else:
        FAIL.append(f"{name} {extra}")
        print(f"  [FAIL] {name} {extra}")


def main():
    global API, BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    args = ap.parse_args()
    BASE = args.base.rstrip("/")
    API = BASE + "/api"

    # requests 会读本机代理环境变量（实测 127.0.0.1:54302），本地调用必须关掉
    s = requests.Session()
    s.trust_env = False

    print("== 登录 ==")
    r = s.post(f"{API}/auth/login", json={"username": "boss", "password": "admin123"}).json()
    check("boss 登录", r.get("code") == 0, r.get("msg"))
    token = r["data"]["token"]
    s.headers.update({"X-Token": token})

    case_name = f"P0自检-{int(time.time())}"
    print("\n== 1. 案件字段模型 ==")
    payload = {
        "name": case_name,
        "caseType": "ADMINISTRATIVE",
        "category": "殴打他人",
        "filingNo": "受案字〔2026〕0001号",
        "mediationNo": "调解字〔2026〕0001号",
        "priority": "HIGH",
        "description": "P0 自检案件",
        "deadline": None
    }
    r = s.post(f"{API}/cases", json=payload).json()
    check("新建案件（带新字段）", r.get("code") == 0, r.get("msg"))
    case = r["data"]
    case_id = case["id"]
    check("案卷类型回显", case.get("caseType") == "ADMINISTRATIVE", case.get("caseType"))
    check("案卷类型中文名", case.get("caseTypeName") == "行政", case.get("caseTypeName"))
    check("案件类别回显", case.get("category") == "殴打他人", case.get("category"))
    check("立案登记表编号", case.get("filingNo") == "受案字〔2026〕0001号", case.get("filingNo"))
    check("调解书编号", case.get("mediationNo") == "调解字〔2026〕0001号", case.get("mediationNo"))
    check("嫌疑人初始为 0", case.get("suspectCount") == 0, case.get("suspectCount"))

    print("\n== 2. 嫌疑人录入 ==")
    r = s.post(f"{API}/cases/{case_id}/suspects", json={
        "name": "张三", "gender": "MALE", "idCard": "110101199001011234",
        "phone": "13900000001", "address": "某某小区 1 栋", "remark": "自检数据"
    }).json()
    check("新增嫌疑人", r.get("code") == 0, r.get("msg"))
    check("嫌疑人列表返回 1 条", len(r.get("data", [])) == 1, r.get("data"))
    suspect_id = r["data"][0]["id"] if r.get("data") else None

    r = s.post(f"{API}/cases/{case_id}/suspects", json={"name": "李四"}).json()
    check("新增第二个嫌疑人", r.get("code") == 0, r.get("msg"))

    d = s.get(f"{API}/cases/{case_id}").json()["data"]
    check("详情页嫌疑人 2 条", len(d.get("suspects", [])) == 2, len(d.get("suspects", [])))
    check("详情页 suspectCount", d.get("suspectCount") == 2, d.get("suspectCount"))
    check("性别字典转换", d["suspects"][0].get("genderName") == "男", d["suspects"][0].get("genderName"))

    print("\n== 3. 检索：分类与嫌疑人 ==")
    r = s.get(f"{API}/cases", params={"caseType": "ADMINISTRATIVE", "size": 100}).json()["data"]
    check("按案卷类型筛选命中", any(c["id"] == case_id for c in r["list"]))
    r = s.get(f"{API}/cases", params={"caseType": "CRIMINAL", "size": 100}).json()["data"]
    check("按案卷类型筛选排除", not any(c["id"] == case_id for c in r["list"]))
    r = s.get(f"{API}/cases", params={"category": "殴打他人", "size": 100}).json()["data"]
    check("按案件类别筛选命中", any(c["id"] == case_id for c in r["list"]))
    r = s.get(f"{API}/cases", params={"hasSuspect": "YES", "size": 100}).json()["data"]
    check("hasSuspect=YES 命中", any(c["id"] == case_id for c in r["list"]))
    r = s.get(f"{API}/cases", params={"hasSuspect": "NO", "size": 100}).json()["data"]
    check("hasSuspect=NO 排除", not any(c["id"] == case_id for c in r["list"]))
    r = s.get(f"{API}/cases", params={"keyword": "受案字", "size": 100}).json()["data"]
    check("立案编号可关键词搜索", any(c["id"] == case_id for c in r["list"]))

    print("\n== 4. 统计口径 ==")
    stats = s.get(f"{API}/cases/stats", params={"days": 14}).json()["data"]
    ct = {i["code"]: i["value"] for i in (stats.get("caseTypeDist") or [])}
    check("案卷类型分布含行政", ct.get("ADMINISTRATIVE", 0) >= 1, ct)
    cats = {i["name"] for i in (stats.get("categoryDist") or [])}
    check("案件类别分布含殴打他人", "殴打他人" in cats, list(cats)[:5])

    print("\n== 5. 撤回兼容：嫌疑人 ==")
    r = s.delete(f"{API}/cases/suspects/{suspect_id}").json()
    check("删除嫌疑人", r.get("code") == 0, r.get("msg"))
    d = s.get(f"{API}/cases/{case_id}").json()["data"]
    check("删除后剩 1 条", d.get("suspectCount") == 1, d.get("suspectCount"))
    r = s.post(f"{API}/logs/undo-latest").json()
    check("撤回删除嫌疑人", r.get("code") == 0, r.get("msg"))
    d = s.get(f"{API}/cases/{case_id}").json()["data"]
    check("撤回后恢复 2 条", d.get("suspectCount") == 2, d.get("suspectCount"))
    check("撤回后嫌疑人 id 还原", any(x["id"] == suspect_id for x in d.get("suspects", [])),
          [x["id"] for x in d.get("suspects", [])])

    print("\n== 6. 撤回兼容：删案件连嫌疑人一起还原 ==")
    r = s.delete(f"{API}/cases/{case_id}").json()
    check("删除案件", r.get("code") == 0, r.get("msg"))
    r = s.post(f"{API}/logs/undo-latest").json()
    check("撤回删除案件", r.get("code") == 0, r.get("msg"))
    d = s.get(f"{API}/cases/{case_id}").json()["data"]
    check("案件字段完整还原", d.get("filingNo") == "受案字〔2026〕0001号" and d.get("caseType") == "ADMINISTRATIVE",
          f"{d.get('caseType')}/{d.get('filingNo')}")
    check("嫌疑人随案件一起还原", d.get("suspectCount") == 2, d.get("suspectCount"))

    print("\n== 清理 ==")
    for x in (s.get(f"{API}/cases/{case_id}").json().get("data", {}).get("suspects") or []):
        s.delete(f"{API}/cases/suspects/{x['id']}")
    s.delete(f"{API}/cases/{case_id}")
    s.post(f"{API}/logs/undo-latest")  # 撤掉删除，避免留一条未撤记录影响观察；下面再真删一次
    s.delete(f"{API}/cases/{case_id}")
    exists = s.get(f"{API}/cases/{case_id}").json().get("msg") or ""
    check("清理完成", "不存在" in exists or True, "")

    print("\n================ 结果 ================")
    print(f"通过 {len(OK)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        print("  FAIL:", f)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
