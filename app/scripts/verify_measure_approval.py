# -*- coding: utf-8 -*-
"""
强制措施变更审批 + 到期提醒 · 接口级自检（2026-10-24）

覆盖用户口径：
  「从当前措施转入下一个措施要经过领导批准；到期前在工作台总览提示，
    到期即该措施结束，由领导或主办/经办人登记下一个措施。」

断言链：
  1. 承办人（STAFF）提交变更 → 返回 pending，案件措施不变
  2. 同一案件重复提交 → 被拒（不允许两条并行的待审批）
  3. 领导退回（须填意见）→ 措施保持不变
  4. 承办人再提交 → 领导同意 → 措施生效、期限落库
  5. 工作台 measureAlerts 里能看到该案（7 天内到期）
  6. 待审批计数归零
  7. 领导自己登记 → 直接生效（不产生待审批）

用法：python scripts/verify_measure_approval.py
依赖：仅标准库；后端须在 8080 运行。
"""
import json
import sys
import urllib.request
import urllib.error
from datetime import date, timedelta

BASE = 'http://127.0.0.1:8080/api'
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PASS, FAIL = 0, 0


def check(name, cond, extra=''):
    global PASS, FAIL
    if cond:
        PASS += 1
        print('  OK   ' + name)
    else:
        FAIL += 1
        print('  FAIL ' + name + (('\n       ' + str(extra)) if extra else ''))


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['X-Token'] = token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=20) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'ignore')
        try:
            return json.loads(raw)
        except Exception:
            return {'code': e.code, 'msg': raw[:200]}


def login(username, password):
    r = call('POST', '/auth/login', {'username': username, 'password': password})
    if r.get('code') != 0:
        print('登录失败：%s %s' % (username, r.get('msg')))
        sys.exit(2)
    d = r['data']
    return d['token'], d


def main():
    boss_tok, boss = login('boss', 'admin123')
    staff_tok, staff = login('test1', '123456')
    check('管理员 + 承办人登录', bool(boss_tok and staff_tok))
    emp_id = staff.get('employeeId')
    check('承办人已绑定员工档案', emp_id is not None, emp_id)

    # ---- 准备：建案 + 指派给承办人 ----
    mk = call('POST', '/cases', {
        'name': '自检-措施审批', 'caseType': 'CRIMINAL', 'status': 'PENDING_ASSIGN',
        'filingNo': 'SELF-MEA-001'
    }, boss_tok)
    case_id = (mk.get('data') or {}).get('id')
    check('建测试案件', bool(case_id), mk)
    call('POST', '/cases/%s/assign' % case_id, {'ownerId': emp_id, 'memberIds': []}, boss_tok)

    start = (date.today() - timedelta(days=1)).isoformat()
    deadline = (date.today() + timedelta(days=3)).isoformat()

    # ---- 1. 承办人提交变更 → 待审批，案件措施不变 ----
    r = call('POST', '/watch/cases/%s/measure' % case_id, {
        'measure': 'BAIL', 'measureDate': start, 'detainDeadline': deadline, 'comment': '案情需要取保'
    }, staff_tok)
    d = r.get('data') or {}
    check('承办人提交措施变更', r.get('code') == 0, r)
    check('返回标识为「待审批」', d.get('pending') is True, d)
    check('返回带审批单 ID', bool(d.get('approvalId')), d)

    detail = call('GET', '/cases/%s' % case_id, None, boss_tok)['data']
    check('案件措施未被改动（提交≠生效）', not detail.get('caseMeasure'), detail.get('caseMeasure'))

    # ---- 2. 重复提交被拒 ----
    r2 = call('POST', '/watch/cases/%s/measure' % case_id, {'measure': 'BAIL'}, staff_tok)
    check('重复提交被拒', r2.get('code') != 0, r2.get('msg'))

    # ---- 3. 领导退回 ----
    pending = call('GET', '/watch/measure-approvals/pending', None, boss_tok)['data']
    mine = [p for p in pending if p.get('caseId') == case_id]
    check('领导看到待审批申请', len(mine) == 1, pending)
    ap = mine[0]
    check('申请内容完整（原措施→新措施/期限/申请人）',
          ap.get('applyMeasure') == 'BAIL' and ap.get('applyDeadline') and ap.get('applicantName'),
          ap)
    r3 = call('POST', '/watch/measure-approvals/%s/review' % ap['id'], {'approve': False}, boss_tok)
    check('退回必须填意见', r3.get('code') != 0, r3.get('msg'))
    r4 = call('POST', '/watch/measure-approvals/%s/review' % ap['id'],
              {'approve': False, 'comment': '证据不足，暂不取保'}, boss_tok)
    check('领导退回成功', r4.get('code') == 0, r4)
    detail = call('GET', '/cases/%s' % case_id, None, boss_tok)['data']
    check('退回后措施仍为空', not detail.get('caseMeasure'), detail.get('caseMeasure'))
    pending = call('GET', '/watch/measure-approvals/pending', None, boss_tok)['data']
    check('退回后不再出现在待审批', not [p for p in pending if p.get('caseId') == case_id], pending)

    # ---- 4. 再提交 → 领导同意 → 措施生效 ----
    r5 = call('POST', '/watch/cases/%s/measure' % case_id, {
        'measure': 'BAIL', 'measureDate': start, 'detainDeadline': deadline, 'comment': '补充材料后重提'
    }, staff_tok)
    check('退回后可重新提交', (r5.get('data') or {}).get('pending') is True, r5)
    ap_id = (r5.get('data') or {}).get('approvalId')
    r6 = call('POST', '/watch/measure-approvals/%s/review' % ap_id,
              {'approve': True, 'comment': '同意取保'}, boss_tok)
    check('领导同意成功', r6.get('code') == 0, r6)
    detail = call('GET', '/cases/%s' % case_id, None, boss_tok)['data']
    check('同意后措施生效为取保', detail.get('caseMeasure') == 'BAIL', detail.get('caseMeasure'))
    check('同意后期限落库', (detail.get('detainDeadlineText') or detail.get('detainDeadline') or '')[:10] == deadline,
          detail.get('detainDeadlineText') or detail.get('detainDeadline'))

    # ---- 5. 工作台到期提醒 ----
    dash = call('GET', '/cases/dashboard', None, boss_tok)['data']
    alerts = [a for a in (dash.get('measureAlerts') or []) if a.get('caseId') == case_id]
    check('工作台措施到期清单含该案', len(alerts) == 1, dash.get('measureAlerts'))
    if alerts:
        a = alerts[0]
        check('剩余天数计算正确', a.get('daysLeft') == 3, a)
        check('未到期标记正确', a.get('expired') is False, a)
        check('措施名中文', a.get('measureName') == '取保候审', a.get('measureName'))
    check('待审批计数已归零', (dash.get('measurePending') or 0) == 0, dash.get('measurePending'))

    # ---- 6. 领导自己登记 → 直接生效 ----
    r7 = call('POST', '/watch/cases/%s/measure' % case_id, {
        'measure': 'RESIDENCE', 'measureDate': date.today().isoformat()
    }, boss_tok)
    check('领导登记直接生效', (r7.get('data') or {}).get('pending') is False, r7)
    pending = call('GET', '/watch/measure-approvals/pending', None, boss_tok)['data']
    check('领导操作不产生待审批', not [p for p in pending if p.get('caseId') == case_id], pending)

    # ---- 清理 ----
    call('DELETE', '/cases/%s' % case_id, None, boss_tok)
    left = call('GET', '/cases/dashboard', None, boss_tok)['data']
    check('清理后工作台不再有该案', not [a for a in (left.get('measureAlerts') or []) if a.get('caseId') == case_id])

    print('\n结果：%d 通过 / %d 失败' % (PASS, FAIL))
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == '__main__':
    main()
