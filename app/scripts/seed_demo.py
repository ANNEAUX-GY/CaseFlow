"""演示数据灌入（通过 HTTP API，MySQL 与内置 H2 环境通用）。

用法（先启动后端）：
    .venv\\Scripts\\python.exe scripts/seed_demo.py
    .venv\\Scripts\\python.exe scripts/seed_demo.py --base http://127.0.0.1:8080/api --reset
"""
import argparse
import io
import random
from datetime import datetime, timedelta

import requests

from _common import ensure_dir, DATA_DIR
from gen_org_template import build, DEMO

CASE_NAMES = [
    "2026-XX 涉嫌电信诈骗案", "2026-XX 合同诈骗案（甲方）", "2026-XX 非法集资案",
    "2026-XX 侵犯著作权案", "2026-XX 交通肇事案", "2026-XX 邻里纠纷调解",
    "2026-XX 买卖合同违约纠纷", "2026-XX 网络赌博案", "2026-XX 资产冻结核查",
    "2026-XX 涉众型经济案", "2026-XX 未成年人保护跟进", "2026-XX 行政复议材料复核",
]
PRIORITIES = ["URGENT", "HIGH", "NORMAL", "NORMAL", "LOW"]
SOURCES = ["MANUAL", "PDF", "WORD", "EXCEL"]
# 到期分布：负值=已逾期
DAY_OFFSETS = [-6, -3, -1, 0, 1, 2, 3, 5, 9, 15, None, None]

# 大类+小类（与现行类别字典一致；未立案不设小类）
CASE_KINDS = [
    ("CRIMINAL", "电诈"), ("CRIMINAL", "盗窃类"), ("CRIMINAL", "故意伤害类"),
    ("CRIMINAL", "接触性诈骗"), ("CRIMINAL", "其他"),
    ("ADMINISTRATIVE", "殴打他人"), ("ADMINISTRATIVE", "赌博"),
    ("PRELIMINARY", None),
]


def api(base):
    s = requests.Session()
    s.headers.update({"X-Token": ""})
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--username", default="boss")
    ap.add_argument("--password", default="admin123")
    ap.add_argument("--force", action="store_true", help="已有案件时仍继续灌数（默认跳过）")
    args = ap.parse_args()

    base = args.base.rstrip("/")
    if not base.endswith("/api"):
        base += "/api"

    s = api(base)
    r = s.post(f"{base}/auth/login", json={"username": args.username, "password": args.password})
    r.raise_for_status()
    body = r.json()
    if body.get("code") != 0:
        raise SystemExit("登录失败：" + str(body.get("msg")))
    token = body["data"]["token"]
    s.headers.update({"X-Token": token})
    print("[1/4] 登录成功")

    # 幂等保护：已有案件就跳过，避免每次启动都翻倍
    existing = s.get(f"{base}/cases", params={"page": 1, "size": 1}).json().get("data", {})
    if existing.get("total", 0) > 0 and not args.force:
        print(f"已有 {existing['total']} 条案件，跳过灌数（如需重建请先清空数据，或用 --force 追加）")
        return

    # 2. 生成并导入员工图谱
    ensure_dir(DATA_DIR)
    xlsx = build(str(DATA_DIR / "员工图谱示例.xlsx"))
    with open(xlsx, "rb") as f:
        r = s.post(f"{base}/employees/import", files={"file": ("员工图谱示例.xlsx", f)})
    r.raise_for_status()
    res = r.json()
    print(f"[2/4] 员工图谱导入：{res['data']['success']}/{res['data']['total']} 条成功")

    employees = s.get(f"{base}/employees/search", params={"limit": 200}).json()["data"]
    if not employees:
        raise SystemExit("未获取到员工，请先导入员工图谱")
    owners = [e for e in employees if e.get("title") in ("组员", "组长")]
    print(f"      可用员工 {len(employees)} 人")

    # 3. 创建案件
    random.seed(20260928)
    created = 0
    for i, name in enumerate(CASE_NAMES):
        offset = DAY_OFFSETS[i]
        deadline = None
        if offset is not None:
            deadline = (datetime.now() + timedelta(days=offset)).replace(
                hour=18, minute=0, second=0, microsecond=0).strftime("%Y-%m-%d %H:%M:%S")
        # 大类+小类组合（对齐现行类别字典；未立案无小类）；案件类型强制必选
        ct, cat = CASE_KINDS[i % len(CASE_KINDS)]
        payload = {
            "name": name,
            "sourceType": SOURCES[i % len(SOURCES)],
            "priority": PRIORITIES[i % len(PRIORITIES)],
            "caseType": ct,
            "category": cat,
            "deadline": deadline,
            "description": "演示数据：来源于 %s，请在截止日期前反馈进展。" % SOURCES[i % len(SOURCES)],
        }
        # 前 8 个案件直接指派，后 4 个保持待指派
        if i < 8 and owners:
            payload["ownerId"] = owners[i % len(owners)]["id"]
            helper = owners[(i + 3) % len(owners)]
            payload["memberIds"] = [helper["id"]] if helper["id"] != payload["ownerId"] else []
            payload["assignNote"] = "主办负责整体推进，协办配合材料整理。"
        r = s.post(f"{base}/cases", json=payload)
        if r.status_code >= 400:
            print("      创建失败：", r.text[:200])
            continue
        created += 1
    print(f"[3/4] 案件创建 {created} 条")

    # 4. 状态流转演示：从「已指派」的案件里挑 2 个推进到处理中、1 个推进到已办结。
    #    只动有承办人的案件——否则会造出「无承办人却处理中/已办结」的脏状态，
    #    这类案件之后无论怎么指派状态都不会变（指派状态推进以「有无承办人」为准）。
    cases = s.get(f"{base}/cases", params={"size": 50}).json()["data"]["list"]
    assigned = [c for c in cases if c.get("owner") and c.get("status") == "ASSIGNED"]
    for c in assigned[:2]:
        s.post(f"{base}/cases/{c['id']}/status", json={"status": "IN_PROGRESS"})
    if len(assigned) > 2:
        s.post(f"{base}/cases/{assigned[2]['id']}/status", json={"status": "DONE", "remark": "演示：已办结"})
    print("[4/4] 状态流转演示完成")

    dash = s.get(f"{base}/cases/dashboard").json()["data"]
    print("\n工作台概览：")
    print(f"  案件总数 {dash['totalCase']} | 待指派 {dash['pendingAssign']} | 处理中 {dash['inProgress']}")
    print(f"  已逾期 {dash['overdue']} | 今日到期 {dash['dueToday']} | 7天内 {dash['dueIn7Days']}")
    print("\n完成，浏览器访问前端即可查看效果。")


if __name__ == "__main__":
    main()
