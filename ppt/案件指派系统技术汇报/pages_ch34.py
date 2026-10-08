# -*- coding: utf-8 -*-
"""第三章 数据库设计（14-16） / 第四章 接口与路由（18-19）"""
from gen_deck import *


def p14_tables(prs):
    """14 数据结构全景：17 张表 —— 左标题+右内容"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    rect(s, 0, 0, 380, H, fill=NAVY)
    tf = tb(s, 44, 118, 300, 110)
    para(tf, '数据结构全景\n17 张表', size=34, color=WHITE, bold=True,
         font=F_TITLE, space_after=0, line=1.24, first=True)
    rect(s, 44, 240, 84, 3, fill=GOLD)
    tf = tb(s, 44, 268, 300, 200)
    para(tf, '不建独立的「员工绑定关系表」—— 账号直接挂员工编号，'
             '并在服务层保证一人一档案，避免两个人共用档案互相看到对方的案件。',
         size=17, color=RGBColor(0x9F, 0xB4, 0xD0), space_after=0, line=1.6, first=True)
    tf = tb(s, 44, 496, 200, 90)
    para(tf, '17', size=72, color=GOLD_L, bold=True, font=F_LATIN,
         space_after=0, line=1.0, first=True)
    tf = tb(s, 122, 532, 240, 30)
    para(tf, '张业务表', size=19, color=WHITE, space_after=0, first=True)

    # 右侧 4 个数据域分组（非等宽，制造视觉节奏）
    x0, y0 = 420, 108
    groups = [
        ('账号与组织', '2 张', 1.00,
         ['账号表：登录名唯一、密码加盐存储、角色分级、注册需审核',
          '员工表：组织层级、办案组别、档案来源标记（导入/手工/自助注册）'], BLUE),
        ('案件主从', '6 张', 1.00,
         ['案件主表 24 个字段（全库最宽），涵盖类型、状态、期限、流程阶段',
          '承办人（改派留痕）、附件、嫌疑人、类别字典、审批记录'], BLUE),
        ('待办与任务', '3 张', 0.86,
         ['待办表：意见派生、自关联两级任务、紧急与重点双档',
          '反馈表：累积式记录（唯一带级联删除的外键）、上传声明三要素',
          '阶段任务表：环节、任务键、是否标准任务'], BLUE),
        ('意见 · 问答 · 通知 · 日志', '6 张', 1.00,
         ['领导意见（可拖拽排序、A/B/C 分级）、疑问问答（回答可修订）',
          '统一信箱（带定位锚点）、意见已读记录',
          '操作日志（含前后快照）、办理进度批注（不进撤回体系）'], GOLD),
    ]
    y = y0
    for nm, cnt, wp, items, c in groups:
        ww = int(790 * wp)
        hh = 50 + len(items) * 47
        rect(s, x0, y, ww, hh, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.05)
        rect(s, x0, y, 5, hh, fill=c)
        tf = tb(s, x0 + 26, y + 14, 560, 32)
        rich(tf, [(nm, {'size': 21, 'color': NAVY, 'bold': True}),
                  ('　' + cnt, {'size': 17, 'color': GOLD, 'bold': True, 'font': F_LATIN})],
             space_after=0, first=True)
        tf = vtb(s, x0 + 26, y + 48, ww - 52, hh - 58)
        for it in items:
            rich(tf, [('· ', {'size': 15, 'color': c, 'bold': True}),
                      (it, {'size': 15, 'color': TEXT2})],
                 space_after=4, line=1.4)
        y += hh + 10

    footer(s, 14)


def p15_relations(prs):
    """15 核心关系：意见是唯一事实源 —— 非对称双栏，Hero"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 760, 58)
    para(tf, '核心关系：意见是唯一事实源', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=GOLD)

    # 锚点数字（Hero 要求）
    tf = tb(s, 790, 46, 420, 100)
    rich(tf, [('1', {'size': 68, 'color': BLUE, 'bold': True, 'font': F_LATIN}),
              (' 条意见 → ', {'size': 26, 'color': NAVY, 'bold': True, 'font': F_TITLE}),
              ('1', {'size': 68, 'color': GOLD, 'bold': True, 'font': F_LATIN}),
              (' 条待办', {'size': 26, 'color': NAVY, 'bold': True, 'font': F_TITLE})],
         space_after=0, line=1.0, align=PP_ALIGN.RIGHT, first=True)

    # 宽侧关系图（L1 主视觉）
    x, y, cw = PAD, 152, 740
    rect(s, x, y, cw, 372, fill=RGBColor(0xF8, 0xFA, 0xFD), line=LINE,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.04)

    boxes = [
        (0, '领导意见', '录入即派生（同事务）', BLUE, 1.00),
        (1, '主任务', '管理层定清单 · 待办事实源', BLUE, 0.90),
        (2, '子任务', '承办人自己拆 · 两级封顶', BLUE, 0.78),
        (3, '反馈记录', '累积式 · 可修订可撤', GREEN, 0.66),
    ]
    bh = 72
    bw = int(cw * 0.62)
    bx0 = x + int((cw - bw) / 2)
    by = y + 26
    for i, (lvl, nm, ds, c, wp) in enumerate(boxes):
        ww = int(560 * wp)
        xx = x + int((cw - ww) / 2)
        rect(s, xx, by, ww, bh, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.09)
        rect(s, xx, by, 5, bh, fill=c)
        tf = vtb(s, xx + 22, by + 7, ww - 70, 58)
        rich(tf, [(nm, {'size': 19, 'color': NAVY, 'bold': True}),
                  ('\n' + ds, {'size': 13, 'color': TEXT2})],
             space_after=0, line=1.26, first=True)
        # 层级徽标
        rect(s, xx + ww - 46, by + 20, 30, 26, fill=c,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.3)
        tf = tb(s, xx + ww - 46, by + 20, 30, 26, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, 'L%d' % lvl, size=12, color=WHITE, bold=True, font=F_LATIN,
             align=PP_ALIGN.CENTER, space_after=0, first=True)
        if i < 3:
            rect(s, x + cw / 2 - 2, by + bh + 2, 4, 18, fill=GOLD)
            rect(s, x + cw / 2 - 9, by + bh + 16, 18, 4, fill=GOLD)
        by += bh + 24

    # 右侧同步语义（挂在图内右侧）
    syncs = [('意见改正文 → 同步待办副本', GOLD),
             ('待办完成 → 回写意见落实状态', GREEN),
             ('意见移除 → 级联删除待办', RED)]
    sy = y + 40
    for t, c in syncs:
        rect(s, x + cw - 250, sy, 214, 46, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.14)
        rect(s, x + cw - 250, sy, 4, 46, fill=c)
        tf = tb(s, x + cw - 234, sy, 196, 46, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, t, size=13, color=TEXT2, space_after=0, line=1.3, first=True)
        sy += 56

    # 窄侧：为什么这么设计
    x2, cw2 = 800, 410
    card(s, x2, 148, cw2, 200, kind='C')
    tf = tb(s, x2 + 26, 166, cw2 - 52, 166)
    para(tf, '待办是单一事实源，\n意见的落实状态从待办派生。',
         size=19, color=GOLD_L, bold=True, space_after=10, line=1.36, first=True)
    para(tf, '两边各存一份，立刻出现「两处数据对不上」，\n且没有天然的仲裁口径。',
         size=14, color=WHITE, space_after=0, line=1.48)

    card(s, x2, 360, cw2, 172, head='派生放在同一事务里', kind='B', head_h=46)
    tf = tb(s, x2 + 22, 414, cw2 - 44, 110)
    para(tf, '派发失败就整体回滚，绝不出现「有意见但没待办」的漏项。'
             '部门反查之类的软失败在内部已兜底。',
         size=14, color=TEXT2, space_after=0, line=1.5, first=True)

    # 底部判断条
    rect(s, PAD, 546, W - PAD * 2, 76, fill=BLUE_BG,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
    rect(s, PAD, 546, 5, 76, fill=BLUE)
    tf = tb(s, PAD + 28, 546, W - PAD * 2 - 56, 76, anchor=MSO_ANCHOR.MIDDLE)
    rich(tf, [('这是本项目最核心的一次方向修正：', {'size': 19, 'color': NAVY, 'bold': True}),
              ('  原先意见和待办各存一份，实际用起来对不上；现在只留一个事实源，另一边全部由它派生。',
               {'size': 19, 'color': TEXT2})],
         space_after=0, line=1.4, first=True)

    footer(s, 15)


def p16_index(prs):
    """16 索引、约束与平滑升级 —— 巨型数字+洞察"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 760, 58)
    para(tf, '索引、约束与平滑升级', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=GOLD)

    # 左：巨型数字 46 + 说明
    rect(s, PAD, 150, 430, 250, fill=NAVY, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.06)
    tf = tb(s, PAD + 34, 172, 380, 130)
    para(tf, '46', size=104, color=GOLD_L, bold=True, font=F_LATIN,
         space_after=0, line=1.0, first=True)
    tf = tb(s, PAD + 34, 300, 372, 84)
    para(tf, '个历史新增列已登记，启动时自动幂等补齐',
         size=20, color=WHITE, space_after=0, line=1.42, first=True)

    card(s, PAD, 420, 430, 202, kind='A')
    rect(s, PAD, 420, 430, 5, fill=GOLD)
    tf = tb(s, PAD + 26, 444, 380, 164)
    para(tf, '为什么不能靠建表脚本？', size=20, color=NAVY, bold=True,
         space_after=8, first=True)
    para(tf, '建表语句里的「已存在则跳过」对已建好的表不会补新列；'
             '而数据库层面加列又不支持「不存在才加」这种写法，两种数据库行为不一致。'
             '所以判断逻辑放到应用启动时做 —— 一套代码两边通用，且可重复执行。',
         size=15, color=TEXT2, space_after=0, line=1.52)

    # 右上：约束
    x, y, cw = 510, 150, 700
    card(s, x, y, cw, 236, kind='A')
    rect(s, x, y, cw, 46, fill=BLUE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
    rect(s, x, y + 36, cw, 10, fill=BLUE)
    tf = tb(s, x + 24, y, cw - 48, 46, anchor=MSO_ANCHOR.MIDDLE)
    para(tf, '约束只加在真正需要的地方', size=20, color=WHITE, bold=True,
         space_after=0, first=True)
    cons = [
        ('4 处唯一约束', '登录名唯一、案件编号唯一、同类下小类名不重复、同一人对同一条意见只记一条已读', BLUE),
        ('1 处外键', '反馈记录挂任务并级联删除 —— 删任务不留孤儿反馈', GREEN),
        ('1 处取值约束', '意见分级只能是 A / B / C', GOLD),
        ('跨表引用一律不加', '全靠服务层保证，删除与留档更灵活', GRAY),
    ]
    yy = y + 60
    for nm, ds, c in cons:
        rect(s, x + 24, yy + 4, 5, 34, fill=c)
        tf = vtb(s, x + 44, yy, cw - 90, 42)
        rich(tf, [(nm, {'size': 16, 'color': NAVY, 'bold': True}),
                  ('\n' + ds, {'size': 13, 'color': TEXT2})],
             space_after=0, line=1.28, first=True)
        yy += 44

    # 右下：索引
    card(s, x, 402, cw, 214, kind='A')
    rect(s, x, 402, cw, 46, fill=NAVY, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
    rect(s, x, 402 + 36, cw, 10, fill=NAVY)
    tf = tb(s, x + 24, 402, cw - 48, 46, anchor=MSO_ANCHOR.MIDDLE)
    rich(tf, [('13 条索引独立成文件', {'size': 20, 'color': WHITE, 'bold': True}),
              ('　不写进通用建表脚本', {'size': 15, 'color': GOLD_L})],
         space_after=0, first=True)
    tf = tb(s, x + 24, 458, cw - 48, 152)
    for t in ('索引语法两种数据库不通用，混在建表脚本里会让生产环境直接失败；',
              '组织树按上级查下属、按链路取整棵子树 —— 两者都建索引；',
              '承办人关系按「案件 + 是否现行」与「人员 + 是否现行」双向建索引；',
              '操作日志按「对象 + 时间」建索引，时间线因此不扫全表。'):
        rich(tf, [('· ', {'size': 15, 'color': GOLD, 'bold': True}),
                  (t, {'size': 15, 'color': TEXT2})],
             space_after=6, line=1.42)

    footer(s, 16)


def p18_api(prs):
    """18 120 个接口，12 个业务域 —— 左标题+右内容"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    rect(s, 0, 0, 380, H, fill=NAVY)
    tf = tb(s, 44, 118, 300, 110)
    para(tf, '120 个接口\n12 个业务域', size=34, color=WHITE, bold=True,
         font=F_TITLE, space_after=0, line=1.24, first=True)
    rect(s, 44, 240, 84, 3, fill=GOLD)
    tf = tb(s, 44, 268, 300, 200)
    para(tf, '接口数量不等于工作量，但它反映了复杂度的重心分布：'
             '盯办与待办两块最重，也是后续维护成本集中的地方。',
         size=17, color=RGBColor(0x9F, 0xB4, 0xD0), space_after=0, line=1.6, first=True)
    tf = tb(s, 44, 470, 260, 84)
    para(tf, '120', size=64, color=GOLD_L, bold=True, font=F_LATIN,
         space_after=0, line=1.0, first=True)
    tf = tb(s, 44, 546, 280, 30)
    para(tf, '个 REST 接口', size=19, color=WHITE, space_after=0, first=True)

    # 右侧：横条分布图（非等宽条形 + 说明）
    x, y = 420, 130
    cw = 790
    data = [
        ('案件盯办', 29, '三子模块 + 计划 + 流转 + 措施 + 审批 + 批注 + 意见', BLUE),
        ('待办与任务', 21, '民警端 + 主子任务 + 反馈 + 材料 + 管理员总览', BLUE),
        ('案件管理', 15, '增删改查 + 指派 + 状态 + 嫌疑人 + 提醒 + 统计', BLUE),
        ('账号管理', 10, '注册审核 + 角色 + 启停用 + 重置密码', BLUE),
        ('员工图谱', 9, '组织树 + 扁平检索 + 增删改 + 导入导出', BLUE),
        ('登录与注册', 8, '登录注册 + 字典 + 公开候选接口', BLUE),
        ('操作日志', 7, '全部管理层专属，含撤回', GOLD),
        ('疑问问答', 6, '提问回答 + 回答修订 + 增删改', BLUE),
        ('类别 / 信箱 / 附件', 14, '字典维护 + 未读已读 + 上传下载', BLUE),
        ('事件流', 1, '实时推送长连接', GOLD),
    ]
    maxv = 29
    bh = 46
    for i, (nm, v, ds, c) in enumerate(data):
        yy = y + i * (bh + 6)
        tf = vtb(s, x, yy, 172, 30)
        para(tf, nm, size=16, color=NAVY, bold=True, space_after=0, first=True)
        # 条形
        bw = int(330 * v / maxv)
        rect(s, x + 178, yy + 6, bw, 24, fill=c,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.3)
        tf = tb(s, x + 178 + bw + 10, yy + 8, 46, 24)
        para(tf, str(v), size=17, color=c, bold=True, font=F_LATIN,
             space_after=0, first=True)
        tf = tb(s, x + 178, yy + 32, 580, 20)
        para(tf, ds, size=13, color=GRAY, space_after=0, first=True)

    footer(s, 18)


def p19_guard(prs):
    """19 路由守卫与数据可见范围 —— 非对称双栏 60:40"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 760, 58)
    para(tf, '路由守卫与数据可见范围', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=GOLD)

    # 宽侧：五道关卡流程
    x, y, cw = PAD, 146, 730
    rect(s, x, y, cw, 396, fill=RGBColor(0xF8, 0xFA, 0xFD), line=LINE,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.04)
    gates = [
        ('登录校验', '未登录一律回登录页；公开页放行', BLUE),
        ('民警白名单回落', '普通民警访问管理层页面 → 自动回「我的案件」', BLUE),
        ('整页权限', '标注为管理层专属的页面直接回落', BLUE),
        ('案件类型门控', '四个主栏目必须先选案件类型才放行', GOLD),
        ('数据范围收敛', '接口按登录人身份强制收敛，非管理层只看本人名下', RED),
    ]
    gy = y + 24
    for i, (nm, ds, c) in enumerate(gates):
        rect(s, x + 30, gy, cw - 60, 64, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
        rect(s, x + 30, gy, 5, 64, fill=c)
        rect(s, x + 52, gy + 16, 32, 32, fill=c, shape=MSO_SHAPE.OVAL)
        tf = tb(s, x + 52, gy + 16, 32, 32, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, str(i + 1), size=16, color=WHITE, bold=True, font=F_LATIN,
             align=PP_ALIGN.CENTER, space_after=0, first=True)
        tf = vtb(s, x + 100, gy + 6, cw - 170, 52)
        rich(tf, [(nm, {'size': 18, 'color': NAVY, 'bold': True}),
                  ('\n' + ds, {'size': 13, 'color': TEXT2})],
             space_after=0, line=1.26, first=True)
        if i < 4:
            rect(s, x + 66, gy + 64, 4, 10, fill=LINE)
        gy += 74

    # 窄侧：三条设计要点
    x2, cw2 = 820, 390
    card(s, x2, 146, cw2, 190, kind='C')
    tf = tb(s, x2 + 24, 168, cw2 - 48, 150)
    para(tf, '用白名单，不用黑名单', size=21, color=GOLD_L, bold=True,
         space_after=10, first=True)
    para(tf, '以后新增页面时，默认就是管理层专属，不会因为漏配而把不该开的页面开放给民警。',
         size=15, color=WHITE, space_after=0, line=1.5)

    card(s, x2, 352, cw2, 190, head='数据范围由后端强制', kind='B', head_h=46)
    tf = tb(s, x2 + 22, 412, cw2 - 44, 122)
    para(tf, '统计与列表接口在服务端按登录人身份收敛到本人名下案件，'
             '前端改查询参数无效。拦截顺序是「先查登录、再查权限」，'
             '保证任何时候都知道操作人是谁。',
         size=15, color=TEXT2, space_after=0, line=1.5, first=True)

    # 底部提示
    rect(s, PAD, 562, W - PAD * 2, 62, fill=BLUE_BG,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
    rect(s, PAD, 562, 5, 62, fill=GOLD)
    tf = tb(s, PAD + 28, 562, W - PAD * 2 - 56, 62, anchor=MSO_ANCHOR.MIDDLE)
    rich(tf, [('实测踩过的坑：', {'size': 17, 'color': NAVY, 'bold': True}),
              ('  类型选择页本身也必须对民警开放，否则点菜单 → 跳选择页 → 被白名单弹回，'
               '表现为「菜单点不动」。', {'size': 17, 'color': TEXT2})],
         space_after=0, line=1.4, first=True)

    footer(s, 19)
