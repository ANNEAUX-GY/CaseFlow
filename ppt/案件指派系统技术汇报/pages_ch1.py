# -*- coding: utf-8 -*-
"""第一章 系统全景（04-08）"""
from gen_deck import *


def p04_pain(prs):
    """04 传统办案管理的四个断点 —— 非对称双栏 窄40:宽60"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    # 左栏：标题 + 引言 + 结论
    rect(s, 0, 0, 420, H, fill=RGBColor(0xF5, 0xF8, 0xFC))
    rect(s, 0, 0, 5, H, fill=BLUE)
    tf = tb(s, 40, 56, 350, 110)
    para(tf, '传统办案\n管理的四个断点', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, line=1.24, first=True)
    rect(s, 40, 186, 84, 3, fill=GOLD)

    tf = tb(s, 40, 216, 344, 240)
    para(tf, '这四个断点不是孤立的。'
            '它们互相放大，最后表现为年底台账对不上、责任人说不清。',
         size=19, color=TEXT2, space_after=0, line=1.62, first=True)

    # 结论卡（藏蓝实心，核心洞察）
    card(s, 40, 452, 344, 170, kind='C')
    tf = tb(s, 64, 476, 296, 130)
    para(tf, '系统要解决的不是「记录」，而是让台账永远等于现场。',
         size=22, color=WHITE, bold=True, space_after=0, line=1.42, first=True)

    # 右栏：四个断点条目
    x = 460
    items = [
        ('01', '意见提了没人跟', '领导在意见栏提了要求，办案人什么时候做的、做完了没有，全靠口头问。', '管不住过程'),
        ('02', '指派靠口头', '谁主办、谁协办、有没有期限，回头查要翻本子。人员一流动就断线。', '查不到责任'),
        ('03', '到期才发现', '期限压在表格里，没有人会主动提醒，等到超期才看到。', '盯不住期限'),
        ('04', '操作无痕', '做错了改不回去，也无法证明「当时是谁改的」，出了问题只能互相推。', '回不了头'),
    ]
    y = 118
    for no, name, desc, tag in items:
        rect(s, x, y, 750, 116, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.07)
        rect(s, x, y, 4, 116, fill=BLUE)
        tf = tb(s, x + 30, y + 22, 60, 50)
        para(tf, no, size=34, color=BLUE2, bold=True, font=F_LATIN,
             space_after=0, first=True)
        rect(s, x + 104, y + 58, 4, 40, fill=GOLD)
        tf = vtb(s, x + 124, y + 10, 486, 96)
        rich(tf, [(name, {'size': 23, 'color': NAVY, 'bold': True}),
                  ('\n' + desc, {'size': 15, 'color': TEXT2})],
             space_after=0, line=1.34, first=True)
        # 右侧标签
        rect(s, x + 620, y + 38, 106, 34, fill=BLUE_BG,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.5)
        tf = tb(s, x + 620, y + 38, 106, 34, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, tag, size=15, color=BLUE, bold=True, align=PP_ALIGN.CENTER,
             space_after=0, first=True)
        y += 132

    footer(s, 4)


def p05_loop(prs):
    """05 系统把这条链路接起来了 —— 巨型数字+洞察，Hero"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    # 左栏标题
    tf = tb(s, PAD, 52, 520, 58)
    para(tf, '系统把这条链路接起来了', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=GOLD)

    # 巨型锚点数字（Hero，L1 主视觉）
    tf = tb(s, PAD, 150, 300, 240)
    para(tf, '5', size=150, color=BLUE, bold=True, font=F_LATIN,
         space_after=0, line=1.0, first=True)
    tf = tb(s, PAD + 132, 254, 240, 60)
    para(tf, '环闭环', size=38, color=NAVY, bold=True, font=F_TITLE,
         space_after=0, first=True)
    tf = tb(s, PAD, 424, 300, 130)
    para(tf, '每一条案件都必须走完这五环，任何一环缺失都会被系统挡住。',
         size=17, color=TEXT2, space_after=0, line=1.58, first=True)

    # 右栏：五环链路图（L1，占 C 区 ≥40%）
    cx, cy, cw = 450, 130, 760
    rect(s, cx, cy, cw, 396, fill=RGBColor(0xF8, 0xFA, 0xFD),
         line=LINE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.04)
    nodes = [
        ('领导提意见', '录入即派生成待办', BLUE),
        ('系统派发', '责任到人、期限明确', BLUE),
        ('民警落实', '汇报状态与说明', BLUE),
        ('层层可追踪', '进度、逾期、操作全留痕', GOLD),
        ('办结归档', '状态锁定、可撤回纠错', GREEN),
    ]
    bw, bh = 640, 62
    sx = cx + 60
    y = cy + 34
    for i, (name, desc, c) in enumerate(nodes):
        rect(s, sx, y, bw, bh, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
        rect(s, sx, y, 6, bh, fill=c)
        # 序号
        rect(s, sx + 26, y + 14, 34, 34, fill=c, shape=MSO_SHAPE.OVAL)
        tf = tb(s, sx + 26, y + 14, 34, 34, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, str(i + 1), size=17, color=WHITE, bold=True, font=F_LATIN,
             align=PP_ALIGN.CENTER, space_after=0, first=True)
        tf = vtb(s, sx + 78, y + 4, 500, 54)
        rich(tf, [(name, {'size': 20, 'color': NAVY, 'bold': True}),
                  ('\n' + desc, {'size': 13, 'color': TEXT2})],
             space_after=0, line=1.22, first=True)
        if i < 4:
            rect(s, sx + 40, y + bh + 2, 3, 12, fill=LINE)
        y += 74

    # 底部洞察条：两行排版，条高 92
    rect(s, cx, 540, cw, 92, fill=NAVY, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
    tf = vtb(s, cx + 30, 540, cw - 60, 92)
    para(tf, '系统的价值在于把「责任 — 动作 — 证据」绑在同一行数据上。',
         size=18, color=WHITE, bold=True, space_after=4, line=1.3, first=True)
    para(tf, '谁提的、谁做的、何时做的、凭什么说完成 —— 全部指向同一条记录。',
         size=18, color=GOLD_L, space_after=0, line=1.3)

    footer(s, 5)


def p06_capability(prs):
    """06 已建成的能力矩阵 —— 左标题+右内容"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    # 左标题栏（窄 30-35%）
    rect(s, 0, 0, 400, H, fill=NAVY)
    tf = tb(s, 48, 120, 320, 120)
    para(tf, '已建成的\n能力矩阵', size=34, color=WHITE, bold=True,
         font=F_TITLE, space_after=0, line=1.24, first=True)
    rect(s, 48, 250, 84, 3, fill=GOLD)
    tf = tb(s, 48, 280, 316, 260)
    para(tf, '12 个业务域已全部闭环。其中案件盯办与待办任务两个域最重，'
             '也是后续维护成本集中所在。',
         size=18, color=RGBColor(0x9F, 0xB4, 0xD0), space_after=0, line=1.62, first=True)

    # 左下锚点数字
    tf = tb(s, 48, 520, 200, 90)
    para(tf, '12', size=72, color=GOLD_L, bold=True, font=F_LATIN,
         space_after=0, line=1.0, first=True)
    tf = tb(s, 132, 556, 240, 30)
    para(tf, '个业务域', size=19, color=WHITE, space_after=0, first=True)

    # 右侧矩阵：4 行 × 3 列分组
    x0, y0 = 440, 112
    cw, chh, gap = 258, 150, 14
    groups = [
        ('收案与指派', ['手工录入 / PDF / Word / Excel 四入口', '主办协办与期限', '办案组别约束', '改派留痕不覆盖历史']),
        ('办案推进', ['案件盯办三子模块', '阶段→环节→任务三层流转', '强制措施与期限推算', '侦查终结审批']),
        ('任务落实', ['意见自动派生成待办', '两级任务与反馈累积', '上传声明可修订', '疑问问答独立通道']),
        ('监督与治理', ['全量操作留痕可撤回', '统一信箱精确跳转', '到期预警八条规则', '组织树与账号审批']),
    ]
    for i, (gname, items) in enumerate(groups):
        x = x0 + (i % 3) * (cw + gap)
        y = y0 + (i // 3) * (chh + gap)
        card(s, x, y, cw, chh, kind='A')
        rect(s, x, y, cw, 52, fill=BLUE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.12)
        rect(s, x, y + 38, cw, 14, fill=BLUE)
        tf = tb(s, x + 20, y, cw - 40, 52, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, gname, size=19, color=WHITE, bold=True, space_after=0, first=True)
        tf = tb(s, x + 20, y + 60, cw - 40, chh - 68)
        for it in items:
            rich(tf, [('· ', {'size': 14, 'color': GOLD, 'bold': True}),
                      (it, {'size': 14, 'color': TEXT2})],
                 space_after=6, line=1.32)

    # 第三行只有 1 张卡 → 补一块「尚缺」提示（诚实的边界）
    x = x0 + 2 * (cw + gap)
    y = y0 + (chh + gap)
    card(s, x, y, cw, chh, kind='A')
    tf = vtb(s, x + 20, y + 14, cw - 40, chh - 28)
    para(tf, '尚未覆盖', size=20, color=NAVY, bold=True, space_after=8, first=True)
    for it in ('定时主动推送（当前打开页面才算）',
               '统计报表导出',
               '附件异地备份'):
        rich(tf, [('· ', {'size': 14, 'color': RED, 'bold': True}),
                  (it, {'size': 14, 'color': TEXT2})],
             space_after=6, line=1.32)

    footer(s, 6)


def p07_roles(prs):
    """07 两类角色，两套视角 —— 非对称双栏 60:40"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 620, 58)
    para(tf, '两类角色，两套视角', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=GOLD)
    tf = tb(s, 760, 62, 450, 26)
    para(tf, '管理层 8 个入口　·　普通民警 3 个入口', size=16, color=TEXT2,
         align=PP_ALIGN.RIGHT, space_after=0, first=True)

    # 宽侧 60%：管理层
    x, y, cw = PAD, 148, 690
    card(s, x, y, cw, 452, kind='A')
    rect(s, x, y, cw, 62, fill=NAVY, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.08)
    rect(s, x, y + 48, cw, 14, fill=NAVY)
    tf = tb(s, x + 28, y, 200, 62, anchor=MSO_ANCHOR.MIDDLE)
    para(tf, '管理层', size=24, color=WHITE, bold=True, font=F_TITLE,
         space_after=0, first=True)
    tf = tb(s, x + 160, y, cw - 188, 62, anchor=MSO_ANCHOR.MIDDLE)
    para(tf, '所长 / 副所长 / 法制员 / 系统管理员', size=14, color=GOLD_L,
         align=PP_ALIGN.RIGHT, space_after=0, first=True)
    mgr = [
        ('工作台', '全局态势、趋势、近期操作'),
        ('案件管理', '全量案件筛选、指派、办理进度'),
        ('案件盯办', '三子模块看板、强制措施、审批'),
        ('待办总览', '跨案件任务完成情况'),
        ('员工图谱', '组织树、办案组别、人员负荷'),
        ('类别管理', '案件小类字典维护'),
        ('账号管理', '注册审核、角色分配、启停用'),
        ('到期提醒', '五档分桶预警'),
    ]
    for i, (nm, ds) in enumerate(mgr):
        col, row = i // 4, i % 4
        xx = x + 28 + col * 330
        yy = y + 90 + row * 86
        rect(s, xx, yy, 300, 74, fill=RGBColor(0xF8, 0xFA, 0xFD),
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
        rect(s, xx, yy, 4, 74, fill=BLUE)
        tf = vtb(s, xx + 20, yy + 5, 276, 66)
        rich(tf, [(nm, {'size': 18, 'color': NAVY, 'bold': True}),
                  ('\n' + ds, {'size': 13, 'color': TEXT2})],
             space_after=0, line=1.28, first=True)

    # 窄侧 40%：民警
    x2, cw2 = 800, 410
    card(s, x2, y, cw2, 452, kind='A')
    rect(s, x2, y, cw2, 62, fill=BLUE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.08)
    rect(s, x2, y + 48, cw2, 14, fill=BLUE)
    tf = tb(s, x2 + 28, y, 200, 62, anchor=MSO_ANCHOR.MIDDLE)
    para(tf, '普通民警', size=24, color=WHITE, bold=True, font=F_TITLE,
         space_after=0, first=True)
    tf = tb(s, x2 + 160, y, cw2 - 188, 62, anchor=MSO_ANCHOR.MIDDLE)
    para(tf, '只看与自己相关的', size=14, color=BLUE_BG, align=PP_ALIGN.RIGHT,
         space_after=0, first=True)
    staff = [('我的案件', '本人名下案件与在办、逾期、临期统计'),
             ('我的待办', '领导意见自动派生的任务，按紧急与重点排序'),
             ('到期提醒', '五档分桶，只看与自己相关的期限风险')]
    yy = y + 90
    for nm, ds in staff:
        rect(s, x2 + 28, yy, cw2 - 56, 74, fill=RGBColor(0xF8, 0xFA, 0xFD),
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
        rect(s, x2 + 28, yy, 4, 74, fill=BLUE)
        tf = vtb(s, x2 + 48, yy + 3, cw2 - 96, 68)
        rich(tf, [(nm, {'size': 18, 'color': NAVY, 'bold': True}),
                  ('\n' + ds, {'size': 13, 'color': TEXT2})],
             space_after=0, line=1.3, first=True)
        yy += 84

    rect(s, x2 + 28, yy + 6, cw2 - 56, 100, fill=NAVY,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.08)
    tf = vtb(s, x2 + 48, yy + 6, cw2 - 100, 100)
    rich(tf, [('民警端只有 3 个入口，是刻意设计的', {'size': 15, 'color': GOLD_L, 'bold': True}),
              ('\n越复杂越没人用 —— 功能都收到这一个入口后面去。',
               {'size': 13, 'color': WHITE})],
         space_after=0, line=1.5, first=True)

    footer(s, 7)


def p08_screenshot(prs):
    """08 工作台实景 —— 全幅图 + 骑线文字"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=NAVY)
    # 图占左侧 72%
    p = pic(s, 'ui-dashboard.png', 0, 96, w=920)
    # 右侧骑线文字
    rect(s, 920, 96, 5, 528, fill=GOLD)
    tf = tb(s, 958, 130, 290, 60)
    para(tf, '系统实景', size=34, color=WHITE, bold=True, font=F_TITLE,
         space_after=0, first=True)
    tf = tb(s, 958, 196, 290, 40)
    para(tf, '管理层工作台', size=19, color=GOLD_L, space_after=0, first=True)
    rect(s, 958, 246, 60, 3, fill=GOLD)
    tf = tb(s, 958, 268, 292, 260)
    for t in ('顶部八个数字是刑事案件口径下的实时计数，按案件类型、'
              '期限、紧急程度可组合筛选；',
              '中部三张图表可点击联动，点某一类案件列表即自动筛到该类；',
              '下方按状态、承办人在手负载分区展示，负载条支持上下滑动查看全部人员。'):
        rich(tf, [('· ', {'size': 16, 'color': GOLD, 'bold': True}),
                  (t, {'size': 16, 'color': WHITE})],
             space_after=10, line=1.5)
    rect(s, 958, 542, 292, 82, fill=RGBColor(0x18, 0x33, 0x59),
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.08)
    tf = tb(s, 978, 556, 252, 56)
    para(tf, '统计口径由后端强制收敛，非管理层只能看到本人名下案件，前端改参数无效。',
         size=14, color=RGBColor(0x9F, 0xB4, 0xD0), space_after=0, line=1.45, first=True)

    footer(s, 8, dark=True)
