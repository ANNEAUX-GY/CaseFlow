# -*- coding: utf-8 -*-
"""封面 / 目录 / 六张章节扉页 —— 美术层"""
from gen_deck import *


def p01_cover(prs):
    s = prs.slides.add_slide(prs.slide_layouts[6])
    hero_bg(s)

    # 主标：横排大字（宋体 Heavy 视觉用 宋体加粗）。宽 800 装 6 字，128px 刚好一行
    tf = vtb(s, PAD, 150, 800, 176)
    para(tf, '案件指派系统', size=96, color=WHITE, bold=True, font=F_TITLE,
         space_after=0, line=1.06, first=True)
    rect(s, PAD + 6, 344, 620, 3, fill=GOLD)

    tf = tb(s, PAD, 366, 820, 56)
    para(tf, 'CaseFlow', size=42, color=GOLD_L, bold=True, font=F_LATIN,
         space_after=0, first=True)

    # 副标飘带（0° 水平）
    rect(s, PAD, 450, 8, 52, fill=GOLD)
    tf = vtb(s, PAD + 26, 450, 700, 52)
    para(tf, '把「领导意见」到「办案落实」接成闭环',
         size=25, color=WHITE, bold=True, space_after=0, first=True)

    # 副题：一句定位
    tf = vtb(s, PAD, 528, 800, 76)
    para(tf, '从收案、指派、盯办到办结归档', size=18,
         color=RGBColor(0x9F, 0xB4, 0xD0), space_after=6, line=1.5, first=True)
    para(tf, '全程留痕，任一环节可回看「谁在什么时候做了什么」', size=18,
         color=RGBColor(0x9F, 0xB4, 0xD0), space_after=0, line=1.5)

    # 两枚胶囊药丸（汇报对象 / 日期）
    rect(s, PAD, 626, 262, 40, fill=BLUE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.5)
    tf = vtb(s, PAD, 626, 262, 40)
    para(tf, '汇报对象：所领导 · 法制员', size=15, color=WHITE,
         align=PP_ALIGN.CENTER, space_after=0, first=True)
    rect(s, PAD + 280, 626, 170, 40, fill=BLUE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.5)
    tf = vtb(s, PAD + 280, 626, 170, 40)
    para(tf, '2026 年 10 月', size=15, color=WHITE,
         align=PP_ALIGN.CENTER, space_after=0, first=True)

    # 右侧视觉锚点：三层递进数据卡（呼应「17表/120接口/14自检」）
    bx, by, bw = 890, 190, 320
    for i, (num, lab) in enumerate((('17', '数据表'), ('120', '业务接口'), ('14', '自动化自检'))):
        yy = by + i * 136
        rect(s, bx, yy, bw, 112, fill=RGBColor(0x18, 0x33, 0x59),
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.08)
        rect(s, bx, yy, 5, 112, fill=GOLD)
        tf = vtb(s, bx + 34, yy + 12, 250, 56)
        para(tf, num, size=52, color=GOLD_L, bold=True, font=F_LATIN,
             space_after=0, line=1.0, first=True)
        tf = tb(s, bx + 34, yy + 74, 250, 26)
        para(tf, lab, size=17, color=RGBColor(0x9F, 0xB4, 0xD0),
             space_after=0, first=True)

    footer(s, 1, dark=True)


def p02_catalog(prs):
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, W, H, fill=WHITE)

    tf = tb(s, PAD, 52, 400, 70)
    para(tf, '汇报目录', size=60, color=NAVY, bold=True, font=F_TITLE,
         space_after=0, first=True)
    rect(s, PAD, 132, 120, 3, fill=GOLD)
    tf = tb(s, PAD, 150, 500, 30)
    para(tf, 'CONTENTS', size=16, color=GRAY, font=F_LATIN, space_after=0, first=True)

    tf = tb(s, PAD, 196, 420, 200)
    para(tf, '六个部分，讲清这套系统是什么、怎么搭的、怎么转的、还差什么。',
         size=19, color=TEXT2, space_after=0, line=1.6, first=True)

    # 6 张警蓝头白卡，3×2
    items = [
        ('01', '系统全景', '解决的痛点、已建成的能力、两类角色的视角', '04-08'),
        ('02', '技术架构', '技术选型取舍、分层架构、权限双层与快照留痕', '10-12'),
        ('03', '数据库设计', '17 张表全景、核心关系、索引约束与平滑升级', '14-16'),
        ('04', '接口与路由', '120 个接口的分布、路由守卫与数据可见范围', '18-19'),
        ('05', '业务操作逻辑', '一条案件的完整生命周期、九条设计决策、通知机制', '21-23'),
        ('06', '保障与展望', '自动化自检体系、上线前八道门槛、后续规划', '25-27'),
    ]
    gx, gy = 508, 112
    cw, ch = 356, 168
    for i, (no, name, desc, rng) in enumerate(items):
        x = gx + (i % 2) * (cw + 16)
        y = gy + (i // 2) * (ch + 14)
        card(s, x, y, cw, ch, head=no, kind='B', head_h=44)
        tf = tb(s, x + 22, y + 56, cw - 44, 32)
        para(tf, name, size=21, color=NAVY, bold=True, font=F_TITLE,
             space_after=0, first=True)
        tf = vtb(s, x + 22, y + 92, cw - 44, 44)
        para(tf, desc, size=14, color=TEXT2, space_after=0, line=1.4, first=True)
        tf = tb(s, x + 22, y + ch - 34, cw - 44, 24)
        para(tf, '第 %s 页' % rng, size=13, color=GOLD, bold=True, font=F_LATIN,
             space_after=0, first=True)

    footer(s, 2)


def s03(prs):
    """03 章节扉页 01 系统全景"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    section(s, '01', '系统全景', '从管理痛点到已建成的能力',
            ['四个断点如何互相放大', '五环闭环怎么转起来', '民警端为何只留三个入口'], 3)


def s09(prs):
    """09 章节扉页 02 技术架构"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    section(s, '02', '技术架构', '支撑系统跑起来的技术底座',
            ['技术选型的取舍理由', '五层分层与两处拦截', '可靠性来自快照而非自觉'], 9)


def s13(prs):
    """13 章节扉页 03 数据库设计"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    section(s, '03', '数据库设计', '17 张表如何支撑起整条业务链',
            ['四个数据域的表分布', '意见派生待办的唯一链路', '加列为何要放在启动时'], 13)


def s17(prs):
    """17 章节扉页 04 接口与路由"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    section(s, '04', '接口与路由', '系统对外的契约与数据边界',
            ['12 个业务域共 120 个接口', '路由守卫的五道关卡', '数据范围由后端强制收敛'], 17)


def s20(prs):
    """20 章节扉页 05 业务操作逻辑"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    section(s, '05', '业务操作逻辑', '系统在实际办案场景里怎么转',
            ['一条案件的完整生命周期', '九条关键设计决策及理由', '通知与实时同步机制'], 20)


def s24(prs):
    """24 章节扉页 06 保障与展望"""
    s = prs.slides.add_slide(prs.slide_layouts[6])
    section(s, '06', '保障与展望', '质量怎么保，还差什么',
            ['14 个自动化自检脚本', '上线前八道硬门槛', '三阶段路线与资源需求'], 24)
