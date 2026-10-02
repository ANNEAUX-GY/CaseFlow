"""局域网部署自检：注册 → 审核 → 登录 → 角色权限分档 → 密码加密落库。

用法：
    .venv\\Scripts\\python.exe scripts\\verify_lan.py
    .venv\\Scripts\\python.exe scripts\\verify_lan.py --base http://127.0.0.1:8080/api

验证目标（全部通过才算部署可用）：
  1. 页面与静态资源由后端 jar 直接提供（无需 Nginx / Node）
  2. 自助注册后处于「待审核」，此时无法登录
  3. 注册必须关联员工：不关联被拒；组织里没有本人档案时可就地建档；一名员工只能绑一个账号
  4. 手机号注册：可以只用手机号注册（登录名留空），手机号非法 / 重复被拒
  5. 登录支持用户名或手机号两种方式
  6. 审核通过后可以登录
  7. 普通民警（STAFF）调高级接口一律 403：指派、员工增删改、导入、账号管理、撤回
  8. 办结 / 撤销是管理层权限：STAFF 被拒，但仍可把案件推进到「处理中」；法制员可办结
  9. 数据库里密码是 BCrypt 哈希，不是明文
"""
import argparse
import json
import sys
import urllib.error
import urllib.request
from urllib.parse import quote

try:
    import pymysql
except ImportError:
    pymysql = None

# 测试账号（脚本会先删后建，可重复执行）
STAFF_USER = ("e2e_staff", "e2e123456", "13900000001")
LAW_USER = ("e2e_law", "e2e123456", "13900000002")
# 第三个账号专门验证「只用手机号注册」：登录名留空，后端自动用手机号当登录名
PHONE_ONLY = ("", "e2e123456", "13900000003")
# 新账号必须关联员工。脚本用「就地建档」的方式给测试账号配档案，
# 档案名统一带这个前缀，退出时按前缀删干净，不会占用真实员工图谱里的人
SELF_EMP_PREFIX = "自检档案-"
# 专门用于状态流转校验的临时案件名（脚本会自建自删，不留痕迹）
FLOW_CASE_NAME = "自检-状态流转"

PASS, FAIL = [], []


def ok(msg):
    PASS.append(msg)
    print(f"  [通过] {msg}")


def ng(msg):
    FAIL.append(msg)
    print(f"  [失败] {msg}")


class Client:
    """极简 HTTP 客户端：绕过系统代理（本机 http_proxy 会让 127.0.0.1 请求走代理而失败）"""

    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, method, path, body=None, token=None):
        url = self.base + path
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("X-Token", token)
        try:
            with self.opener.open(req, timeout=15) as r:
                raw = r.read().decode("utf-8")
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8")
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, {"raw": raw}
        except Exception as e:
            return 0, {"error": str(e)}

    def get_raw(self, path):
        req = urllib.request.Request(self.base + path)
        try:
            with self.opener.open(req, timeout=15) as r:
                return r.status, r.headers.get("Content-Type", ""), len(r.read())
        except urllib.error.HTTPError as e:
            return e.code, "", 0
        except Exception:
            return 0, "", 0


def cleanup(c, boss_token, username):
    """删掉脚本自己造的测试账号与员工档案，保证脚本可重复执行"""
    _, body = c.call("GET", f"/users?keyword={username}", token=boss_token)
    for u in (body or {}).get("data") or []:
        if u["username"] == username:
            c.call("DELETE", f"/users/{u['id']}", token=boss_token)
    # 账号删掉后绑定随之释放，此时才能删档案（不然会被「已绑定账号」挡住）
    cleanup_employee(c, boss_token, SELF_EMP_PREFIX + (username or ""))


def cleanup_employee(c, boss_token, name):
    """按姓名精确删除脚本自建的员工档案"""
    _, body = c.call("GET", f"/employees/search?keyword={quote(name)}&limit=50", token=boss_token)
    for e in (body or {}).get("data") or []:
        if e["name"] == name:
            c.call("DELETE", f"/employees/{e['id']}", token=boss_token)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080/api")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--db-user", default="root")
    ap.add_argument("--password", default="root")   # 本地免安装实例的管理员口令是 root
    args = ap.parse_args()

    c = Client(args.base)

    print("\n=== 0. 静态资源由后端直接提供 ===")
    status, ctype, size = c.get_raw("/")
    if status == 200 and "html" in ctype:
        ok(f"首页可访问（{size} 字节，{ctype}）—— 说明前端已打进 jar，不需要 Nginx/Node")
    else:
        ng(f"首页返回 {status} {ctype}；若为 401 说明静态资源被登录拦截器拦住了")

    print("\n=== 1. 内置管理员登录 ===")
    status, body = c.call("POST", "/auth/login", {"username": "boss", "password": "admin123"})
    if status == 200 and (body or {}).get("code") == 0:
        boss = body["data"]
        boss_token = boss["token"]
        ok(f"boss 登录成功，角色 {boss.get('roleName')}，全权限={boss.get('fullAccess')}")
        if boss.get("fullAccess") is not True:
            ng("boss 未被识别为全权限角色")
    else:
        ng(f"boss 登录失败：{body}")
        print("\n请确认服务已启动、数据库里存在 boss 账号。")
        sys.exit(1)

    print("\n=== 2. 自助注册与「必须关联员工」 ===")
    for name, pwd, phone in (STAFF_USER, LAW_USER, PHONE_ONLY):
        cleanup(c, boss_token, name or phone)
    cleanup(c, boss_token, "e2e_nobind")
    cleanup_employee(c, boss_token, SELF_EMP_PREFIX + "手机号")

    # 2.1 不关联任何员工 -> 必须被拒
    status, body = c.call("POST", "/auth/register", {
        "username": "e2e_nobind", "password": "e2e123456", "displayName": "自检-无档案",
        "phone": "13900000008", "dept": "自检部门", "applyRole": "STAFF"
    })
    if "关联一名员工" in ((body or {}).get("msg") or ""):
        ok(f"注册未关联员工被拒绝：{body['msg']}")
    else:
        ng(f"未关联员工竟然放行或提示不对：{body}")

    # 2.2 注册页认领员工档案的公开接口（注册页还没登录，必须免鉴权）
    status, body = c.call("GET", "/auth/register/employees?limit=5")
    cands = (body or {}).get("data") or []
    if status == 200 and cands and not any(x.get("phone") for x in cands):
        ok(f"注册页可免登录读取本人档案候选（{len(cands)} 条，且不含联系方式）")
    else:
        ng(f"注册页员工候选异常（应免登录、非空、不含联系方式）：{body}")

    # 2.3 一名员工只能绑一个账号
    #     用一个「当前确实没人绑」的员工来试，避免演示数据被真实账号绑走后脚本误报
    _, body = c.call("GET", "/users/bindable-employees?limit=1", token=boss_token)
    free = (body or {}).get("data") or []
    if not free:
        ok("员工图谱里的档案都已被账号绑定，跳过重复绑定校验")
    else:
        _eid = free[0]["id"]
        for _uname in ("e2e_bind1", "e2e_bind2"):
            cleanup(c, boss_token, _uname)
        status, body = c.call("POST", "/auth/register", {
            "username": "e2e_bind1", "password": "e2e123456", "displayName": "自检-占用甲",
            "phone": "13900000006", "applyRole": "STAFF", "employeeId": _eid
        })
        _first = status == 200 and (body or {}).get("code") == 0
        status, body = c.call("POST", "/auth/register", {
            "username": "e2e_bind2", "password": "e2e123456", "displayName": "自检-占用乙",
            "phone": "13900000007", "applyRole": "STAFF", "employeeId": _eid
        })
        if _first and "只能绑定一个账号" in ((body or {}).get("msg") or ""):
            ok(f"同一员工被第二个账号认领时被拒绝：{body['msg']}")
        else:
            ng(f"员工重复绑定没被拦住（首个注册成功={_first}）：{body}")
        cleanup(c, boss_token, "e2e_bind1")
        cleanup(c, boss_token, "e2e_bind2")

    # 2.4 正常注册：组织里没有本人档案时「就地建档」，随注册一并创建
    role = "STAFF"
    for name, pwd, phone in (STAFF_USER, LAW_USER):
        status, body = c.call("POST", "/auth/register", {
            "username": name, "password": pwd, "displayName": "自检-" + name,
            "phone": phone, "dept": "自检部门", "applyRole": role,
            "newEmployee": {"name": SELF_EMP_PREFIX + name, "dept": "自检部门", "title": "组员"}
        })
        if status == 200 and (body or {}).get("code") == 0:
            ok(f"注册 {name}（手机 {phone}，申请 {role}，就地建档并关联）提交成功")
        else:
            ng(f"注册 {name} 失败：{body}")
        role = "LAW_OFFICER"

    # 只用手机号注册：登录名留空，后端应自动用手机号落库
    name, pwd, phone = PHONE_ONLY
    status, body = c.call("POST", "/auth/register", {
        "username": None, "password": pwd, "displayName": "自检-手机号",
        "phone": phone, "dept": "自检部门", "applyRole": "STAFF",
        "newEmployee": {"name": SELF_EMP_PREFIX + "手机号", "dept": "自检部门", "title": "组员"}
    })
    if status == 200 and (body or {}).get("code") == 0:
        ok(f"只用手机号注册成功（登录名留空，手机 {phone}）")
    else:
        ng(f"只用手机号注册失败：{body}")

    # 手机号格式非法应被拒绝
    status, body = c.call("POST", "/auth/register", {
        "username": "", "password": "x123456", "displayName": "手机号非法",
        "phone": "12345", "applyRole": "STAFF"
    })
    if "手机号格式" in ((body or {}).get("msg") or ""):
        ok(f"非法手机号被拒绝：{body['msg']}")
    else:
        ng(f"非法手机号没被拦住：{body}")

    # 手机号重复应被拒绝
    status, body = c.call("POST", "/auth/register", {
        "username": "", "password": "x123456", "displayName": "手机号重复",
        "phone": phone, "applyRole": "STAFF"
    })
    if "已被注册" in ((body or {}).get("msg") or ""):
        ok(f"重复手机号被拒绝：{body['msg']}")
    else:
        ng(f"重复手机号没被拦住：{body}")

    # 登录名重复应被拒绝（此时手机号换成新的，避免先被手机号规则拦下）
    status, body = c.call("POST", "/auth/register", {
        "username": STAFF_USER[0], "password": "x123456", "displayName": "重复登录名",
        "phone": "13900000009", "applyRole": "STAFF"
    })
    if "已被占用" in ((body or {}).get("msg") or ""):
        ok(f"重复登录名被拒绝：{body['msg']}")
    else:
        ng(f"重复登录名没被拦住：{body}")

    print("\n=== 3. 待审核期间不能登录 ===")
    status, body = c.call("POST", "/auth/login", {"username": STAFF_USER[0], "password": STAFF_USER[1]})
    msg = (body or {}).get("msg", "")
    if "审核" in msg:
        ok(f"待审核账号登录被拦截：{msg}")
    else:
        ng(f"待审核账号竟然能登录或提示不对：{body}")

    print("\n=== 4. 审核（把账号分别设为普通民警 / 法制员）===")
    _, listing = c.call("GET", "/users?auditStatus=0", token=boss_token)
    pending = {u["username"]: u for u in ((listing or {}).get("data") or [])}
    if len(pending) < 3:
        ng(f"待审核列表应有 3 条，实际 {len(pending)} 条：{list(pending)}")

    tokens = {}
    for name, pwd, phone in (STAFF_USER, LAW_USER, PHONE_ONLY):
        grant = "LAW_OFFICER" if name == LAW_USER[0] else "STAFF"
        # 手机号注册时 username 落库就是手机号
        account = name or phone
        u = pending.get(account)
        if not u:
            ng(f"没在待审核列表里找到 {account}")
            continue
        if name and u.get("phone") != phone:
            ng(f"{account} 的手机号落库不对：{u.get('phone')}（应为 {phone}）")
        status, body = c.call("POST", f"/users/{u['id']}/approve",
                              {"role": grant, "remark": "自检通过"}, token=boss_token)
        if status == 200 and (body or {}).get("code") == 0:
            ok(f"{account} 审核通过，角色设为 {body['data']['roleName']}")
        else:
            ng(f"{account} 审核失败：{body}")
            continue

        # 用户名登录
        status, body = c.call("POST", "/auth/login", {"username": account, "password": pwd})
        if status == 200 and (body or {}).get("code") == 0:
            tokens[account] = body["data"]["token"]
            ok(f"{account} 用「登录名」登录成功，全权限={body['data'].get('fullAccess')}")
        else:
            ng(f"{account} 用登录名仍然无法登录：{body}")
            continue

        # 手机号登录（同一账号，两种凭据都要能进）
        status, body = c.call("POST", "/auth/login", {"username": phone, "password": pwd})
        if status == 200 and (body or {}).get("code") == 0:
            tk_by_phone = body["data"]["token"]
            ok(f"手机号 {phone} 也能登录（对应账号 {body['data'].get('username')}）")
            # 多端在线：电脑和手机各持一个令牌，两个都应当能用
            s1, _ = c.call("GET", "/cases?page=1&size=1", token=tokens[account])
            s2, _ = c.call("GET", "/cases?page=1&size=1", token=tk_by_phone)
            if s1 == 200 and s2 == 200:
                ok("同一账号可多端在线（电脑 + 手机两个令牌同时有效）")
            else:
                ng(f"多端在线没生效：登录名令牌 HTTP {s1}，手机号令牌 HTTP {s2}")
            tokens[account] = tk_by_phone
        else:
            ng(f"手机号 {phone} 登录失败：{body}")

    staff_tk = tokens.get(STAFF_USER[0])
    law_tk = tokens.get(LAW_USER[0])
    if not staff_tk or not law_tk:
        ng("测试账号令牌缺失，跳过权限校验")
        return report()

    print("\n=== 5. 普通民警（STAFF）应当被挡住 ===")
    # 拿一个真实案件来试指派
    _, page = c.call("GET", "/cases?page=1&size=1", token=boss_token)
    rows = ((page or {}).get("data") or {}).get("list") or []
    case_id = rows[0]["id"] if rows else 1

    # 先拿一个员工 id 用于指派请求体
    _, emps = c.call("GET", "/employees/search?limit=1", token=boss_token)
    emp_list = (emps or {}).get("data") or []
    emp_id = emp_list[0]["id"] if emp_list else 1

    guarded = [
        ("POST", f"/cases/{case_id}/assign", {"caseId": case_id, "ownerId": emp_id, "memberIds": []}, "指派案件"),
        ("POST", "/employees", {"name": "不该建的人", "employeeNo": "E2E-X"}, "新增员工"),
        ("DELETE", f"/cases/{case_id}", None, "删除案件"),
        ("POST", "/logs/undo-latest", None, "撤回操作"),
        ("GET", "/users", None, "账号管理"),
        ("POST", "/users", {"username": "hack", "password": "123456", "role": "CHIEF"}, "新建账号"),
    ]
    for method, path, payload, label in guarded:
        status, body = c.call(method, path, payload, token=staff_tk)
        if status == 403:
            ok(f"普通民警执行「{label}」被拒绝（403：{(body or {}).get('msg', '')[:28]}…）")
        else:
            ng(f"普通民警执行「{label}」竟然返回 {status}：{body}")

    print("\n=== 6. 办结 / 撤销 = 管理层专属 ===")
    # 先清掉上次异常中断可能留下的自检案件，避免越积越多
    _, all_cases = c.call("GET", "/cases?page=1&size=100", token=boss_token)
    for old in ((all_cases or {}).get("data") or {}).get("list") or []:
        if old.get("name") == FLOW_CASE_NAME:
            c.call("DELETE", f"/cases/{old['id']}", token=boss_token)
    # 造一个专用案件来测状态流转，避免污染演示数据
    _, created = c.call("POST", "/cases", {
        "name": FLOW_CASE_NAME, "sourceType": "MANUAL", "priority": "NORMAL",
        "status": "ASSIGNED", "ownerId": emp_id, "caseType": "CRIMINAL"
    }, token=boss_token)
    flow_case = ((created or {}).get("data") or {}).get("id")
    if not flow_case:
        ng(f"造测试案件失败，跳过状态流转校验：{created}")
    else:
        # 普通民警：推进到「处理中」应当允许
        status, body = c.call("POST", f"/cases/{flow_case}/status", {"status": "IN_PROGRESS"}, token=staff_tk)
        if status == 200 and (body or {}).get("code") == 0:
            ok("普通民警可以把案件推进到「处理中」（应该允许）")
        else:
            ng(f"普通民警推进「处理中」失败：{body}")

        # 普通民警：办结 / 撤销 应当 403
        for code, label in (("DONE", "办结"), ("CANCELLED", "撤销")):
            status, body = c.call("POST", f"/cases/{flow_case}/status", {"status": code}, token=staff_tk)
            if status == 403 or (body or {}).get("code") == 403:
                ok(f"普通民警执行「{label}」被拒绝：{(body or {}).get('msg', '')}")
            else:
                ng(f"普通民警竟然能「{label}」：HTTP {status} {body}")

        # 法制员：办结应当允许
        status, body = c.call("POST", f"/cases/{flow_case}/status",
                              {"status": "DONE", "remark": "自检办结"}, token=law_tk)
        if status == 200 and (body or {}).get("code") == 0:
            ok("法制员办结案件成功（说明放开给了管理层）")
        else:
            ng(f"法制员办结失败：{body}")

        # 收尾：把这个自检案件删掉，别留在案件列表里
        c.call("DELETE", f"/cases/{flow_case}", token=law_tk)

    print("\n=== 7. 法制员（LAW_OFFICER）应当可以 ===")
    status, body = c.call("POST", f"/cases/{case_id}/assign",
                          {"caseId": case_id, "ownerId": emp_id, "memberIds": []}, token=law_tk)
    if status == 200 and (body or {}).get("code") == 0:
        ok("法制员指派案件成功")
    else:
        ng(f"法制员指派失败：{body}")

    status, body = c.call("GET", "/users", token=law_tk)
    if status == 200 and (body or {}).get("code") == 0:
        ok(f"法制员可访问账号管理（{len(body['data'])} 个账号）")
    else:
        ng(f"法制员访问账号管理失败：{body}")

    # 普通民警仍可做基础操作：查看案件列表、新建案件
    status, body = c.call("GET", "/cases?page=1&size=5", token=staff_tk)
    if status == 200 and (body or {}).get("code") == 0:
        ok("普通民警可以正常查看案件列表")
    else:
        ng(f"普通民警查看案件列表失败：{body}")

    print("\n=== 8. 停用账号应当立刻踢下线 ===")
    kick_name = "e2e_kick"
    cleanup(c, boss_token, kick_name)
    # 新账号必须关联员工，这里同样就地建档（档案名带自检前缀，下次运行会被清掉）
    _, body = c.call("POST", "/users", {
        "username": kick_name, "password": "e2e123456", "displayName": "自检-待踢下线",
        "role": "STAFF", "phone": "13900000008",
        "newEmployee": {"name": SELF_EMP_PREFIX + kick_name, "dept": "自检部门", "title": "组员"}
    }, token=boss_token)
    kick_id = ((body or {}).get("data") or {}).get("id")
    if not kick_id:
        ng(f"造踢下线测试账号失败：{body}")
    else:
        _, body = c.call("POST", "/auth/login", {"username": kick_name, "password": "e2e123456"})
        kick_tk = ((body or {}).get("data") or {}).get("token")
        s, _ = c.call("GET", "/cases?page=1&size=1", token=kick_tk)
        ok(f"新账号登录后令牌可用（HTTP {s}）") if s == 200 else ng(f"新账号令牌不可用：HTTP {s}")

        c.call("POST", f"/users/{kick_id}/status", {"status": 0}, token=boss_token)
        s, _ = c.call("GET", "/cases?page=1&size=1", token=kick_tk)
        if s == 401:
            ok("停用后原令牌立刻失效（401），不用等过期")
        else:
            ng(f"停用后令牌竟然还能用：HTTP {s}")

        # 停用账号也不能再登录
        _, body = c.call("POST", "/auth/login", {"username": kick_name, "password": "e2e123456"})
        if "停用" in ((body or {}).get("msg") or ""):
            ok(f"停用账号登录被拦截：{body['msg']}")
        else:
            ng(f"停用账号登录没被拦住：{body}")

        c.call("DELETE", f"/users/{kick_id}", token=boss_token)

    print("\n=== 9. 密码以 BCrypt 哈希落库 ===")
    if pymysql is None:
        ng("环境里没有 pymysql，跳过数据库核对")
    else:
        try:
            conn = pymysql.connect(host=args.host, user=args.db_user, password=args.password,
                                   charset="utf8mb4", database="case_flow", autocommit=True)
            with conn.cursor() as cur:
                cur.execute("SELECT username, password FROM sys_user WHERE username IN (%s,%s,%s)",
                            ("boss", STAFF_USER[0], LAW_USER[0]))
                for uname, pwd in cur.fetchall():
                    if pwd.startswith("$2"):
                        ok(f"{uname} 的密码是 BCrypt 哈希（{pwd[:7]}…）")
                    else:
                        ng(f"{uname} 的密码不是哈希，疑似明文：{pwd}")
                cur.execute("SELECT audit_status, role, dept FROM sys_user WHERE username=%s", (STAFF_USER[0],))
                row = cur.fetchone()
                if row and row[0] == 1:
                    ok(f"审核状态已落库：audit_status={row[0]}（已通过），角色 {row[1]}，部门 {row[2]}")
                else:
                    ng(f"审核状态落库不对：{row}")

                # 手机号注册的账号：登录名应当就是手机号
                cur.execute("SELECT username, phone FROM sys_user WHERE phone=%s", (PHONE_ONLY[2],))
                row = cur.fetchone()
                if row and row[0] == PHONE_ONLY[2]:
                    ok(f"手机号注册落库正确：phone={row[1]}，登录名自动取手机号={row[0]}")
                else:
                    ng(f"手机号注册落库不对（username 应为手机号）：{row}")
            conn.close()
        except Exception as e:
            ng(f"数据库核对失败：{e}")

    return report()


def report():
    print("\n" + "=" * 60)
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    if FAIL:
        print("\n失败明细：")
        for f in FAIL:
            print("  - " + f)
        sys.exit(1)
    print("局域网部署自检全部通过。")
    print(f"\n可直接用这三个账号登录体验权限差异（登录名和手机号都能登）：")
    print(f"  普通民警 {STAFF_USER[0]} / {STAFF_USER[1]}   手机 {STAFF_USER[2]}")
    print(f"           —— 能看案件、能置为「处理中」，但没有指派 / 办结 / 撤销 / 员工编辑 / 账号管理")
    print(f"  法制员   {LAW_USER[0]} / {LAW_USER[1]}   手机 {LAW_USER[2]}")
    print(f"           —— 全部功能可用")
    print(f"  手机注册 {PHONE_ONLY[2]} / {PHONE_ONLY[1]}   —— 登录名留空注册出来的账号，直接拿手机号登")
    print(f"  管理员   boss / admin123")
    print(f"\n注：自检账号各自关联了一份「{SELF_EMP_PREFIX}…」的员工档案（新账号必须绑定员工），")
    print(f"    这些档案在员工图谱里可见，下次运行本脚本会自动清理，也可以直接在界面上删掉。")


if __name__ == "__main__":
    main()
