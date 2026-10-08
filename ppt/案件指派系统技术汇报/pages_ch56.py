# -*- coding: utf-8 -*-
"""第五章 业务操作逻辑（21-23） / 第六章 保障与展望（25-27）"""
from gen_deck import *


def p21_lifecycle(prs):
    """21 一条案件的完整生命周期 —— 上大图+下方卡片，Hero"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 700, 58)
    para(tf, '一条案件的完整生命周期', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=GOLD)

    # 锚点数字（Hero）
    tf = tb(s, 800, 46, 410, 100)
    rich(tf, [('4', {'size': 68, 'color': BLUE, 'bold': True, 'font': F_LATIN}),
              (' 个阶段 · ', {'size': 26, 'color': NAVY, 'bold': True, 'font': F_TITLE}),
              ('全程留痕', {'size': 30, 'color': GOLD, 'bold': True, 'font': F_TITLE})],
         space_after=0, line=1.0, align=PP_ALIGN.RIGHT, first=True)

    # 上大图：生命周期主流程（L1，上 55%）
    x, y, cw = PAD, 140, 1140
    rect(s, x, y, cw, 244, fill=RGBColor(0xF8, 0xFA, 0xFD), line=LINE,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.04)

    stages = [
        ('收案', '四类来源统一入库\n手工 / PDF / Word / 表格', BLUE),
        ('指派', '定主办协办与期限\n办案组别不符会被拦下', BLUE),
        ('定任务', '领导提意见 → 自动派发\n子任务由承办人自己拆', GOLD),
        ('盯办推进', '强制措施 / 阶段流转\n审批与强制措施登记', BLUE),
        ('办结归档', '管理层确认办结\n状态锁定、留痕可查', GREEN),
    ]
    bw = 196
    gap = (cw - bw * 5) / 4
    for i, (nm, ds, c) in enumerate(stages):
        bx = x + i * (bw + gap)
        rect(s, bx, y + 30, bw, 174, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.08)
        rect(s, bx, y + 30, bw, 5, fill=c)
        rect(s, bx + 18, y + 50, 34, 34, fill=c, shape=MSO_SHAPE.OVAL)
        tf = tb(s, bx + 18, y + 50, 34, 34, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, str(i + 1), size=17, color=WHITE, bold=True, font=F_LATIN,
             align=PP_ALIGN.CENTER, space_after=0, first=True)
        tf = vtb(s, bx + 18, y + 90, bw - 36, 108)
        rich(tf, [(nm, {'size': 20, 'color': NAVY, 'bold': True}),
                  ('\n' + ds, {'size': 13, 'color': TEXT2})],
             space_after=0, line=1.32, first=True)
        if i < 4:
            rect(s, bx + bw + gap / 2 - 12, y + 114, 24, 3, fill=GOLD)
            rect(s, bx + bw + gap / 2 + 7, y + 110, 6, 11, fill=GOLD)

    # 下方 3 张要点卡（非等宽）
    cw2 = (cw - 48) / 3
    cards = [
        ('办案人怎么完成任务', BLUE,
         ['勾选任务 → 弹出汇报窗口，选落实状态',
          '写落实说明；上传材料填「平台 + 文件名」',
          '主任务需至少一条反馈说明，子任务全完成才能勾主任务']),
        ('谁可以做什么', GOLD,
         ['定主任务、改内容、删任务、办结撤销 → 管理层',
          '拆子任务、勾子任务、写反馈 → 承办人',
          '改反馈 → 提交人本人或管理层（留修订痕迹）']),
        ('盯办的三个子模块', GREEN,
         ['初查 / 刑拘在办 / 取保监居，由「强制措施」字段表达',
          '侦查终结需审批，退回补侦要填意见',
          '八条预警规则自动算逾期与临期']),
    ]
    for i, (nm, c, items) in enumerate(cards):
        cx = x + i * (cw2 + 24)
        card(s, cx, 394, cw2, 210, kind='A')
        rect(s, cx, 394, cw2, 46, fill=c, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
        rect(s, cx, 394 + 34, cw2, 12, fill=c)
        tf = tb(s, cx + 20, 394, cw2 - 40, 46, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, nm, size=18, color=WHITE, bold=True, space_after=0, first=True)
        tf = tb(s, cx + 20, 448, cw2 - 40, 150)
        for it in items:
            rich(tf, [('· ', {'size': 13, 'color': c, 'bold': True}),
                      (it, {'size': 13, 'color': TEXT2})],
                 space_after=5, line=1.34)

    footer(s, 21)


def p22_decisions(prs):
    """22 九条关键设计决策及其理由 —— 左标题+右内容"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    rect(s, 0, 0, 372, H, fill=RGBColor(0xF5, 0xF8, 0xFC))
    rect(s, 0, 0, 5, H, fill=GOLD)
    tf = tb(s, 40, 50, 300, 110)
    para(tf, '九条关键\n设计决策', size=34, color=NAVY, bold=True, font=F_TITLE,
         space_after=0, line=1.24, first=True)
    rect(s, 40, 168, 84, 3, fill=GOLD)
    tf = tb(s, 40, 196, 296, 200)
    para(tf, '这九条不是过度设计，每一条都对应一个真实踩过的坑 —— '
             '「为什么不那么做」比「做了什么」更重要。',
         size=18, color=TEXT2, space_after=0, line=1.6, first=True)
    card(s, 40, 408, 296, 214, kind='C')
    tf = tb(s, 62, 432, 252, 176)
    para(tf, '共同的判断标准：让人「保存不了」比让人「填错」更糟；'
             '数据缺失可以补，口径不一致要返工。',
         size=18, color=WHITE, bold=True, space_after=0, line=1.48, first=True)

    # 右侧九条
    x = 410
    items = [
        ('类型门控', '四个主栏目必须先选案件类型，统计口径从一开始锁定', BLUE),
        ('办案组别约束', '初查组 / 清案组 / 不限，空值一律按「不限」处理 —— 存量员工不改也能被指派', BLUE),
        ('层级由职务推导', '不用上下级连线决定层级，连线只决定同层挂在谁名下', BLUE),
        ('待办单一事实源', '意见不另存一份，落实状态从待办派生', GOLD),
        ('上传声明独立成列', '时间 / 平台 / 文件名各自独立，不拼成一整句话', GOLD),
        ('快照式撤回', '写操作前后各存一份完整快照，撤回等于原样写回', GOLD),
        ('注解粗粒度 + 服务细粒度', '26 处管理层专属标注做门禁；是否本人承办下沉服务层判断', GREEN),
        ('反馈累积不覆盖', '每次反馈都留一条记录，历史记录保留当时的状态快照', GREEN),
        ('异常值一律归一', '空值、非法值、旧数据兜底为「不限」—— 宁可宽松，不可瘫痪', GREEN),
    ]
    y = 116
    for i, (nm, ds, c) in enumerate(items):
        rect(s, x, y, 800, 54, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
        rect(s, x + 16, y + 12, 30, 30, fill=c, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.3)
        tf = tb(s, x + 16, y + 12, 30, 30, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, str(i + 1), size=15, color=WHITE, bold=True, font=F_LATIN,
             align=PP_ALIGN.CENTER, space_after=0, first=True)
        tf = vtb(s, x + 60, y, 258, 54)
        para(tf, nm, size=18, color=NAVY, bold=True, space_after=0, first=True)
        rect(s, x + 322, y + 14, 3, 26, fill=c)
        tf = vtb(s, x + 340, y, 448, 54)
        para(tf, ds, size=14, color=TEXT2, space_after=0, first=True)
        y += 60

    footer(s, 22)


def p23_notify(prs):
    """23 通知与实时机制 —— 非对称双栏 窄40:宽60"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 700, 58)
    para(tf, '通知与实时同步机制', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=GOLD)

    # 窄侧：三个要点
    x2, cw2 = PAD, 400
    card(s, x2, 146, cw2, 154, kind='C')
    tf = tb(s, x2 + 24, 168, cw2 - 48, 118)
    para(tf, '埋点只有一个汇聚点', size=21, color=GOLD_L, bold=True,
         space_after=10, first=True)
    para(tf, '所有写操作在日志落库后统一广播。任何入口的写操作都逃不过，'
             '不存在「漏埋」的可能。',
         size=15, color=WHITE, space_after=0, line=1.5)

    card(s, x2, 316, cw2, 152, head='分发按收件人维度', kind='B', head_h=44)
    tf = tb(s, x2 + 22, 374, cw2 - 44, 88)
    rich(tf, [('民警', {'size': 16, 'color': BLUE, 'bold': True}),
              (' 只收本人承办案件相关的；', {'size': 15, 'color': TEXT2})],
         space_after=5, line=1.45, first=True)
    rich(tf, [('管理层', {'size': 16, 'color': BLUE, 'bold': True}),
              (' 收全站所有其他人的操作（他们要总览全局）；', {'size': 15, 'color': TEXT2})],
         space_after=5, line=1.45)
    rich(tf, [('任何人', {'size': 16, 'color': BLUE, 'bold': True}),
              (' 不收自己触发的操作。', {'size': 15, 'color': TEXT2})],
         space_after=0, line=1.45)

    card(s, x2, 484, cw2, 138, head='白名单降噪', kind='B', head_h=44)
    tf = tb(s, x2 + 22, 540, cw2 - 44, 76)
    para(tf, '只通知有业务含义的变更（约 30 类动作）；'
             '拖拽排序、排序重排、撤回这类内部噪声一律不发。',
         size=15, color=TEXT2, space_after=0, line=1.45, first=True)

    # 宽侧：SSE 链路图（L1）
    x, cw = 500, 710
    rect(s, x, 146, cw, 300, fill=RGBColor(0xF8, 0xFA, 0xFD), line=LINE,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.04)
    tf = tb(s, x + 24, 164, cw - 48, 26)
    para(tf, '一次写操作引发的连锁', size=16, color=TEXT2, space_after=0, first=True)

    chain = [
        ('任意写操作', '新建 / 修改 / 指派 / 流转', BLUE),
        ('操作日志落库', '全量埋点的唯一汇聚点', BLUE),
        ('实时广播', '全站在线页面即时刷新', GOLD),
        ('派生信箱通知', '按收件人维度分发', GOLD),
    ]
    by = 200
    for i, (nm, ds, c) in enumerate(chain):
        rect(s, x + 40, by, cw - 80, 58, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
        rect(s, x + 40, by, 5, 58, fill=c)
        tf = vtb(s, x + 66, by + 3, cw - 140, 52)
        rich(tf, [(nm, {'size': 17, 'color': NAVY, 'bold': True}),
                  ('\n' + ds, {'size': 13, 'color': TEXT2})],
             space_after=0, line=1.26, first=True)
        if i < 3:
            rect(s, x + cw / 2 - 2, by + 58, 4, 8, fill=GOLD)
        by += 68

    # 精确跳转说明
    card(s, x, 462, cw, 160, head='点信件直达具体内容', kind='A')
    rect(s, x, 462, cw, 46, fill=BLUE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
    rect(s, x, 462 + 36, cw, 10, fill=BLUE)
    tf = tb(s, x + 24, 462, cw - 48, 46, anchor=MSO_ANCHOR.MIDDLE)
    para(tf, '点信件直达具体内容，并短暂高亮', size=20, color=WHITE, bold=True,
         space_after=0, first=True)
    tf = tb(s, x + 24, 524, cw - 48, 90)
    for t in ('日志本身只记录到案件一级，无法事后反查具体是哪条任务、哪条疑问；',
              '所以埋点时由业务方法显式把目标传下来，落在通知记录里；',
              '点开信件 → 打开任务详情浮窗 → 滚动定位 → 高亮 2.6 秒后淡出。'):
        rich(tf, [('· ', {'size': 15, 'color': GOLD, 'bold': True}),
                  (t, {'size': 15, 'color': TEXT2})],
             space_after=5, line=1.42)

    footer(s, 23)


def p25_quality(prs):
    """25 质量保障不是靠人工抽查 —— 巨型数字+洞察"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 760, 58)
    para(tf, '质量保障不是靠人工抽查', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=GOLD)

    # 左：巨型数字
    rect(s, PAD, 152, 400, 236, fill=NAVY, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.06)
    tf = tb(s, PAD + 32, 170, 340, 126)
    para(tf, '14', size=104, color=GOLD_L, bold=True, font=F_LATIN,
         space_after=0, line=1.0, first=True)
    tf = tb(s, PAD + 32, 292, 344, 84)
    para(tf, '个自动化自检脚本\n每次改动都跑回归', size=20, color=WHITE,
         space_after=0, line=1.42, first=True)

    card(s, PAD, 402, 400, 216, kind='A')
    rect(s, PAD, 402, 400, 5, fill=GOLD)
    tf = tb(s, PAD + 24, 422, 352, 84)
    para(tf, '为什么必须落成脚本？', size=19, color=NAVY, bold=True,
         space_after=0, first=True)
    tf = tb(s, PAD + 24, 462, 352, 146)
    para(tf, '「统计口径对不对」这类问题，靠人眼看页面极易漏。'
             '比如完成规则从「必须有佐证材料」改成「必须有反馈说明」，'
             '全库体检若不跟着改规则，就会误报十几处不一致。',
         size=14, color=TEXT2, space_after=0, line=1.5, first=True)

    # 右：脚本覆盖矩阵
    x, y, cw = 510, 140, 700
    groups = [
        ('业务正确性', BLUE, ['字段完整性自检（32 项）',
                             '流程流转与进度归零（41 项）、指派与状态流转（20 项）',
                             '撤回机制专项校验']),
        ('权限与口径', RED, ['数据范围收敛校验',
                            '局域网全量回归（41 项）',
                            '持久化落库校验']),
        ('交互链路', GREEN, ['待办与子任务（32 项）、意见操作（29 项）',
                            '子任务详情与反馈修订（21 项）、回答修订（13 项）',
                            '信箱与通知（8 项）、全库数据自洽性体检']),
    ]
    yy = y
    for nm, c, items in groups:
        hh = 54 + len(items) * 34
        card(s, x, yy, cw, hh, kind='A')
        rect(s, x, yy, 5, hh, fill=c)
        tf = tb(s, x + 26, yy + 14, 400, 30)
        para(tf, nm, size=19, color=NAVY, bold=True, space_after=0, first=True)
        tf = tb(s, x + 26, yy + 46, cw - 52, hh - 54)
        for it in items:
            rich(tf, [('· ', {'size': 14, 'color': c, 'bold': True}),
                      (it, {'size': 14, 'color': TEXT2})],
                 space_after=4, line=1.34)
        yy += hh + 10

    footer(s, 25)


def p26_gate(prs):
    """26 上线前的八道硬门槛 —— 非对称双栏 宽60:窄40"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 760, 58)
    para(tf, '上线前的八道硬门槛', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=RED)

    tf = tb(s, PAD, 138, 720, 30)
    para(tf, '这八项不是功能缺失，是安全与可运维性缺口 —— 上线前必须逐项整改。',
         size=17, color=TEXT2, space_after=0, first=True)

    # 宽侧：8 项（2 列 × 4 行，非等宽）
    x, y, cw = PAD, 180, 700
    items = [
        ('密码明文存储', '演示版为明文比对，需改为加盐哈希'),
        ('登录态在进程内存', '重启全员掉线；多实例部署互不认识'),
        ('跨域全开', '任意网站可带用户凭据调接口'),
        ('撤回无并发保护', '两人同时操作可能互相覆盖'),
        ('附件下载不记痕', '无法证明谁看过案件材料'),
        ('配置带默认密码', '弱口令，必须去掉'),
        ('建表无版本管理', '只能加表不能改列，需引入迁移工具'),
        ('文件路径存在越界风险', '存储路径被污染可能跳出目录'),
    ]
    bw = 338
    for i, (nm, ds) in enumerate(items):
        col, row = i // 4, i % 4
        bx = x + col * (bw + 24)
        by = y + row * 116
        rect(s, bx, by, bw, 104, fill=WHITE, line=LINE,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.08)
        rect(s, bx, by, 5, 104, fill=RED)
        rect(s, bx + 22, by + 16, 32, 32, fill=RED,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.3)
        tf = tb(s, bx + 22, by + 16, 32, 32, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, str(i + 1), size=15, color=WHITE, bold=True, font=F_LATIN,
             align=PP_ALIGN.CENTER, space_after=0, first=True)
        tf = vtb(s, bx + 22, by + 10, bw - 44, 84)
        rich(tf, [(nm, {'size': 17, 'color': NAVY, 'bold': True}),
                  ('\n' + ds, {'size': 13, 'color': TEXT2})],
             space_after=0, line=1.3, first=True)

    # 窄侧：附件风险 + 判断
    x2, cw2 = 800, 410
    card(s, x2, 180, cw2, 208, head='附件存储风险最高', kind='B', head_h=46)
    tf = tb(s, x2 + 22, 240, cw2 - 44, 142)
    for t in ('只有一份、异地无副本 —— 磁盘故障即永久丢失',
              '没有版本与防篡改 —— 事后无法证明当时提交的是什么',
              '删除是真删 —— 误删不可恢复',
              '与代码同盘 —— 重装或迁移时容易被一起清掉'):
        rich(tf, [('· ', {'size': 15, 'color': RED, 'bold': True}),
                  (t, {'size': 15, 'color': TEXT2})],
             space_after=5, line=1.42)

    card(s, x2, 404, cw2, 220, kind='C')
    tf = tb(s, x2 + 24, 428, cw2 - 48, 184)
    para(tf, '不要为了省事把附件塞进数据库', size=21, color=GOLD_L, bold=True,
         space_after=10, line=1.38, first=True)
    para(tf, '数据库会被附件撑到几十 GB，备份变慢、恢复变慢，'
             '而且从此数据库备份和附件备份绑死。',
         size=15, color=WHITE, space_after=10, line=1.5)
    rich(tf, [('现在的架构是对的：', {'size': 15, 'color': GOLD_L, 'bold': True}),
              ('库内存路径、磁盘存文件。缺的是备份与版本控制，不是架构。',
               {'size': 15, 'color': WHITE})],
         space_after=0, line=1.5)

    footer(s, 26)


def p27_roadmap(prs):
    """27 后续规划与资源需求 —— 非对称双栏"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 760, 58)
    para(tf, '后续规划与资源需求', size=34, color=NAVY, bold=True,
         font=F_TITLE, space_after=0, first=True)
    rect(s, PAD, 118, 100, 3, fill=GOLD)

    # 三阶段路线（非等宽横向轴）
    x, y, cw = PAD, 146, 1140
    rect(s, x, y, cw, 246, fill=RGBColor(0xF8, 0xFA, 0xFD), line=LINE,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.04)
    # 轴线
    rect(s, x + 60, y + 92, cw - 120, 3, fill=LINE)
    phases = [
        ('第一阶段', '上线前必须整改', RED,
         ['八项安全与运维缺口', '附件每日备份脚本', '密码加盐 + 登录态迁出会话存储']),
        ('第二阶段', '稳定运维', GOLD,
         ['到期提醒改为主动推送', '健康检查与运行指标', '补单元测试与接口测试',
          '操作日志归档与瘦身']),
        ('第三阶段', '工程化与体验', BLUE,
         ['接口文档自动生成', '前端按需加载与懒加载', '操作日志记「变更前后值」供审计',
          '统计报表导出']),
    ]
    bw = 330
    gap = (cw - bw * 3) / 2
    for i, (ph, nm, c, items) in enumerate(phases):
        bx = x + 40 + i * (bw + gap)
        rect(s, bx + bw / 2 - 11, y + 81, 22, 22, fill=c, shape=MSO_SHAPE.OVAL)
        tf = tb(s, bx, y + 34, bw, 28)
        para(tf, ph, size=18, color=c, bold=True, align=PP_ALIGN.CENTER,
             space_after=0, first=True)
        tf = tb(s, bx, y + 118, bw, 30)
        para(tf, nm, size=22, color=NAVY, bold=True, font=F_TITLE,
             align=PP_ALIGN.CENTER, space_after=0, first=True)
        tf = vtb(s, bx + 20, y + 150, bw - 40, 90)
        for it in items:
            rich(tf, [('· ', {'size': 14, 'color': c, 'bold': True}),
                      (it, {'size': 14, 'color': TEXT2})],
                 space_after=3, line=1.38, align=PP_ALIGN.CENTER)

    # 下方：资源需求（左）+ 结束判断（右）
    cw2 = 556
    card(s, PAD, 400, cw2, 208, head='资源需求实测', kind='B', head_h=48)
    tf = tb(s, PAD + 24, 458, cw2 - 48, 142)
    rich(tf, [('实测：', {'size': 15, 'color': NAVY, 'bold': True}),
              ('本机 32 GB 内存、20 核，后端约 400–700 MB，数据库约 300–500 MB，'
               '合计 1 GB 以内，占可用内存不到 7%。', {'size': 15, 'color': TEXT2})],
         space_after=8, line=1.48, first=True)
    rich(tf, [('判断：', {'size': 15, 'color': NAVY, 'bold': True}),
              ('50 人单位同时在线通常不到 10 人，', {'size': 15, 'color': TEXT2}),
              ('数据库不需要单独部署', {'size': 15, 'color': GOLD, 'bold': True}),
              ('，一套普通办公服务器即可。', {'size': 15, 'color': TEXT2})],
         space_after=0, line=1.48)

    x2 = PAD + cw2 + 28
    cw3 = W - PAD * 2 - cw2 - 28
    card(s, x2, 400, cw3, 208, kind='C')
    tf = tb(s, x2 + 28, 418, cw3 - 56, 172)
    para(tf, '优先级的建议', size=21, color=GOLD_L, bold=True,
         space_after=10, first=True)
    para(tf, '八项安全缺口里，「密码明文」与「跨域全开」影响面最大，'
             '建议优先；附件备份脚本成本最低、收益最直接，可同步进行。'
             '到期主动推送涉及新增定时任务能力，建议放在第二阶段。',
         size=15, color=WHITE, space_after=0, line=1.5)

    footer(s, 27)


def p28_ending(prs):
    """28 汇报完毕 —— 全幅深色 Hero 结束页"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    hero_bg(s)

    tf = tb(s, PAD, 168, 800, 90)
    para(tf, '汇报完毕', size=96, color=WHITE, bold=True, font=F_TITLE,
         space_after=0, first=True)
    rect(s, PAD + 4, 278, 160, 3, fill=GOLD)

    tf = tb(s, PAD, 306, 760, 44)
    para(tf, '请领导指示下一步的优先级', size=30, color=GOLD_L, bold=True,
         space_after=0, first=True)

    tf = tb(s, PAD, 380, 800, 70)
    para(tf, '闭环已经成型、门槛清晰可查。',
         size=21, color=WHITE, space_after=0, line=1.5, first=True)
    para(tf, '需要请示的是：整改与推送两件事的先后顺序。',
         size=21, color=RGBColor(0x9F, 0xB4, 0xD0), space_after=0, line=1.5)

    # 右侧收口数字（Hero 锚点）
    stats = [('17', '张数据表'), ('120', '个业务接口'),
             ('14', '个自检脚本'), ('8', '项待整改')]
    bx, by = 900, 176
    for i, (num, lab) in enumerate(stats):
        col, row = i % 2, i // 2
        xx = bx + col * 176
        yy = by + row * 150
        rect(s, xx, yy, 156, 126, fill=RGBColor(0x18, 0x33, 0x59),
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.08)
        rect(s, xx, yy, 5, 126, fill=GOLD)
        tf = tb(s, xx + 26, yy + 18, 120, 66)
        para(tf, num, size=52, color=GOLD_L, bold=True, font=F_LATIN,
             space_after=0, line=1.0, first=True)
        tf = tb(s, xx + 26, yy + 84, 120, 28)
        para(tf, lab, size=16, color=WHITE, space_after=0, first=True)

    footer(s, 28, dark=True)
