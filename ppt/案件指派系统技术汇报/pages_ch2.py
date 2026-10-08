# -*- coding: utf-8 -*-
"""第二章 技术架构（10-12）"""
from gen_deck import *


def p10_stack(prs):
    """10 技术选型与取舍 —— 左标题+右内容"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    # 左标题栏
    rect(s, 0, 0, 400, H, fill=RGBColor(0xF5, 0xF8, 0xFC))
    rect(s, 0, 0, 5, H, fill=BLUE)
    tf = tb(s, 48, 56, 320, 110)
    para(tf, '技术选型\n与取舍', size=34, color=NAVY, bold=True, font=F_TITLE,
         space_after=0, line=1.24, first=True)
    rect(s, 48, 176, 84, 3, fill=GOLD)
    tf = tb(s, 48, 204, 316, 220)
    para(tf, '不是为了用新而用新。这套选型的唯一标准是：'
             '在公安内网常见环境下能稳定跑起来、能长期维护。',
         size=18, color=TEXT2, space_after=0, line=1.6, first=True)
    card(s, 48, 420, 316, 196, kind='C')
    tf = tb(s, 70, 444, 272, 160)
    para(tf, '选 JDK 8 与 Spring Boot 2，是为了匹配内网已有的国产化中间件与运维习惯；'
             '牺牲新特性，换部署侧的确定性。',
         size=18, color=WHITE, space_after=0, line=1.5, first=True)

    # 右侧四层技术栈
    x, y0, cw = 440, 118, 770
    layers = [
        ('前端', 'Vue 3 + Element Plus + ECharts 5 + Pinia + Vite', '统一 axios 实例注入令牌；路由守卫做权限与口径门控', BLUE),
        ('接口层', 'REST（context-path = /api）· 统一响应体 {code, msg, data}', '全局异常处理；登录 401 与权限 403 分开拦截器', BLUE),
        ('业务层', 'Spring Boot 2 + MyBatis-Plus · 26 处管理层专属标注', '业务规则下沉服务层；全量埋点单点汇聚', BLUE),
        ('数据层', 'H2 文件库（开发） / MySQL 8（部署）· 启动时幂等补列', '单 jar 打包，内嵌 Tomcat，附件存磁盘不入库', GOLD),
    ]
    y = y0
    for nm, tech, note, c in layers:
        rect(s, x, y, cw, 116, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.06)
        rect(s, x, y, 5, 116, fill=c)
        rect(s, x + 26, y + 22, 116, 40, fill=c, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.2)
        tf = tb(s, x + 26, y + 22, 116, 40, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, nm, size=20, color=WHITE, bold=True, align=PP_ALIGN.CENTER,
             space_after=0, first=True)
        tf = vtb(s, x + 162, y + 10, cw - 190, 96)
        rich(tf, [(tech, {'size': 19, 'color': NAVY, 'bold': True, 'font': F_LATIN}),
                  ('\n' + note, {'size': 15, 'color': TEXT2})],
             space_after=0, line=1.3, first=True)
        y += 128

    rect(s, x, 640 - 6, cw, 0.1, fill=WHITE)  # 占位防溢出（不可见）
    # 底部结论条
    rect(s, x, 620, cw, 0, fill=WHITE)
    footer(s, 10)


def p11_arch(prs):
    """11 分层架构与权限双层 —— 非对称双栏，Hero"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 700, 58)
    para(tf, '分层架构与权限双层', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=GOLD)

    # 宽侧 60%：分层架构图（L1 主视觉，占 C 区 ≥55%）
    x, y, cw = PAD, 146, 760
    rect(s, x, y, cw, 428, fill=RGBColor(0xF8, 0xFA, 0xFD), line=LINE,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.04)
    tf = tb(s, x + 24, y + 18, cw - 48, 26)
    para(tf, '请求自上而下穿过五层，横切两处拦截', size=16, color=TEXT2,
         space_after=0, first=True)

    layers = [
        ('前端层', '13 个页面 + 17 个组件 + 6 个状态仓', BLUE, 1.00),
        ('路由守卫', '五道关卡：登录 → 白名单 → 整页权限 → 类型门控', BLUE, 0.92),
        ('接口层', '12 个业务域 · 120 个 REST 接口', BLUE, 0.84),
        ('业务层', '案件 / 盯办 / 流程 / 待办 / 意见 / 问答 / 通知 / 组织', BLUE, 0.76),
        ('数据层', '17 张表 · 统一响应体 · 全局异常处理', GOLD, 0.68),
    ]
    ly = y + 56
    lh = 62
    for nm, ds, c, wp in layers:
        ww = int(cw * wp)
        bx = x + int((cw - ww) / 2)
        rect(s, bx, ly, ww, lh - 10, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
        rect(s, bx, ly, 5, lh - 10, fill=c)
        tf = tb(s, bx + 22, ly, 116, lh - 10, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, nm, size=18, color=NAVY, bold=True, space_after=0, first=True)
        tf = vtb(s, bx + 144, ly, ww - 168, lh - 10)
        para(tf, ds, size=14, color=TEXT2, space_after=0, line=1.3, first=True)
        if nm != '数据层':
            # 下行箭头
            rect(s, x + cw / 2 - 2, ly + lh - 4, 4, 8, fill=LINE)
        ly += lh

    # 横切拦截器（右侧竖条强调）
    rect(s, x + cw - 26, y + 56, 18, lh * 3 - 8, fill=RED,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.2)
    tf = tb(s, x + cw - 130, y + 56 + lh * 3 - 4, 104, 26)
    para(tf, '两处拦截', size=14, color=RED, bold=True,
         align=PP_ALIGN.RIGHT, space_after=0, first=True)

    # 窄侧 40%：权限双层 + 锚点数字
    x2, cw2 = 840, 370
    # 锚点数字（Hero 要求 ≥48px）
    tf = vtb(s, x2, 136, cw2, 96)
    rich(tf, [('2', {'size': 84, 'color': BLUE, 'bold': True, 'font': F_LATIN}),
              (' 层拦截', {'size': 32, 'color': NAVY, 'bold': True, 'font': F_TITLE})],
         space_after=0, line=1.0, first=True)
    tf = vtb(s, x2, 234, cw2, 46)
    para(tf, '前端隐藏按钮只是体验层，真正的拦截在服务端',
         size=14, color=TEXT2, space_after=0, line=1.4, first=True)

    card(s, x2, 292, cw2, 158, head='粗粒度：管理层专属', kind='B', head_h=48)
    tf = vtb(s, x2 + 22, 346, cw2 - 44, 98)
    para(tf, '26 处「管理层专属」标注，覆盖 35 个接口。'
             '由拦截器在方法执行前统一拦住，返回带角色中文名的明确提示。',
         size=14, color=TEXT2, space_after=0, line=1.45, first=True)

    card(s, x2, 462, cw2, 178, head='细粒度：服务层判身份', kind='B', head_h=48)
    tf = vtb(s, x2 + 22, 516, cw2 - 44, 118)
    para(tf, '在服务层判断「是不是本案现职承办人」「是不是这条反馈的提交人」。'
             '因为主任务归管理层、子任务归承办人，这种分层注解表达不了。',
         size=14, color=TEXT2, space_after=0, line=1.45, first=True)

    footer(s, 11)


def p12_snapshot(prs):
    """12 数据安全与留痕机制 —— 上大图+下方卡片"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 700, 58)
    para(tf, '留痕与回退：写操作可撤回', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=GOLD)

    # 上大图：快照机制示意（上 55%）
    x, y, cw = PAD, 146, 1140
    rect(s, x, y, cw, 258, fill=RGBColor(0xF8, 0xFA, 0xFD), line=LINE,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.04)

    steps = [
        ('执行写操作', '新建 / 修改 / 指派 /\n状态流转 / 删除', BLUE),
        ('存修改前快照', '整份对象序列化\n含指派、附件、嫌疑人', BLUE),
        ('执行并落库', '业务事务内完成\n同时写操作日志', BLUE),
        ('存修改后快照', '同一结构再存一份\n便于展示变更对照', BLUE),
        ('撤回 = 原样写回', '把修改前快照直接写回\n不是「反向操作」', GOLD),
    ]
    bw = 208
    gap = (cw - bw * 5) / 4
    for i, (nm, ds, c) in enumerate(steps):
        bx = x + i * (bw + gap)
        rect(s, bx, y + 40, bw, 178, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.08)
        rect(s, bx, y + 40, bw, 5, fill=c)
        tf = vtb(s, bx + 18, y + 56, bw - 36, 152)
        rich(tf, [(nm, {'size': 19, 'color': NAVY, 'bold': True}),
                  ('\n' + ds, {'size': 14, 'color': TEXT2})],
             space_after=0, line=1.32, first=True)
        if i < 4:
            # 箭头
            rect(s, bx + bw + gap / 2 - 12, y + 122, 24, 3, fill=GOLD)
            rect(s, bx + bw + gap / 2 + 7, y + 118, 6, 11, fill=GOLD)

    tf = tb(s, x, 404, cw, 30)
    rich(tf, [('关键收益：', {'size': 16, 'color': NAVY, 'bold': True}),
              ('快照整体序列化案件对象 —— 以后每加一个新字段，自动就获得撤回能力，零额外成本。',
               {'size': 16, 'color': TEXT2})],
         space_after=0, first=True)

    # 下方 3 张要点卡（非等宽，避免横排单调）
    cw2 = (cw - 48) / 3
    cards = [
        ('四条安全规则', BLUE,
         ['仅案件类写操作可撤回（登录、传附件不行）',
          '已撤回的不能重复撤；必须是该案件最新一条',
          '撤回本身也记日志 —— 所以撤回之后还能再撤回（等价重做）']),
        ('为什么不撤回全部', GOLD,
         ['批注与领导意见属「旁注」语义',
          '撤回某条进度时不应连带回滚旁注',
          '旁注要留档，不能随进度一起消失']),
        ('工程实现要点', GREEN,
         ['写操作前后各存一份完整快照',
          '清空字段须显式写「置空」，否则撤不回来',
          '新增列自动进入快照，无需改撤回代码']),
    ]
    for i, (nm, c, items) in enumerate(cards):
        cx = x + i * (cw2 + 24)
        card(s, cx, 424, cw2, 214, kind='A')
        rect(s, cx, 424, cw2, 48, fill=c, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
        rect(s, cx, 424 + 36, cw2, 12, fill=c)
        tf = tb(s, cx + 20, 424, cw2 - 40, 48, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, nm, size=18, color=WHITE, bold=True, space_after=0, first=True)
        # 要点区用顶部对齐（MIDDLE 会把要点顶到卡头那一行）
        tf = tb(s, cx + 20, 478, cw2 - 40, 154)
        for it in items:
            rich(tf, [('· ', {'size': 13, 'color': c, 'bold': True}),
                      (it, {'size': 13, 'color': TEXT2})],
                 space_after=5, line=1.34)

    footer(s, 12)
