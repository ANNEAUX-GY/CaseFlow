# -*- coding: utf-8 -*-
"""
案件指派系统技术汇报 — PPT 生成器

按 DESIGN.md（红金政务风 + 公安蓝主色）与 STORY.md 的 24 页骨架生成。
画布 1280x720（16:9），母版三分区：
  A 留白  y 0-48
  B 标题  y 48-110
  C 内容  y 110-660
  D 页脚条 y 660-700（三段不等宽：警蓝52% 藏蓝30% 金18%）
"""
import os
from pptx import Presentation
from pptx.util import Emu, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from PIL import Image

# ---------- 设计令牌（DESIGN.md 第 2 节） ----------
NAVY = RGBColor(0x12, 0x29, 0x4A)      # 深藏蓝：内页标题、美术层深底
BLUE = RGBColor(0x1B, 0x4A, 0x8C)      # 警蓝：主色
BLUE2 = RGBColor(0x2A, 0x5D, 0xA6)     # 警蓝浅：渐变中段
BLUE_BG = RGBColor(0xE8, 0xEF, 0xF8)   # 警蓝极浅：卡头底
GOLD = RGBColor(0xC8, 0xA4, 0x5C)      # 警徽金：重点
GOLD_L = RGBColor(0xE8, 0xC8, 0x7C)    # 金浅
TEXT = RGBColor(0x1B, 0x24, 0x30)      # 正文主字
TEXT2 = RGBColor(0x5A, 0x64, 0x72)     # 正文次字
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
RED = RGBColor(0xC6, 0x2A, 0x2A)       # 语义红：逾期/风险
GREEN = RGBColor(0x1E, 0x8E, 0x58)     # 语义绿：已完成
GRAY = RGBColor(0x8A, 0x92, 0x9E)      # 脚注
LINE = RGBColor(0xDF, 0xE4, 0xEA)      # 分隔线/描边

F_TITLE = '宋体'          # B 区主标 / 美术大字
F_BODY = '微软雅黑'       # 正文（无衬线）
F_LATIN = 'Arial'        # 技术字段

W, H = 1280, 720
PAD = 70

BASE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(BASE, 'assets')
TOTAL = 28


# ---------- 基础工具 ----------
def px(v):
    """像素 -> EMU（1px = 1/96 英寸）"""
    return Emu(int(v * 9525))


def rect(slide, x, y, w, h, fill=None, line=None, lw=1, shape=MSO_SHAPE.RECTANGLE, radius=None):
    s = slide.shapes.add_shape(shape, px(x), px(y), px(w), px(h))
    if radius is not None and shape == MSO_SHAPE.ROUNDED_RECTANGLE:
        s.adjustments[0] = radius
    if fill is None:
        s.fill.background()
    else:
        s.fill.solid()
        s.fill.fore_color.rgb = fill
    if line is None:
        s.line.fill.background()
    else:
        s.line.color.rgb = line
        s.line.width = Pt(lw)
    s.shadow.inherit = False
    return s


def tb(slide, x, y, w, h, anchor=MSO_ANCHOR.TOP):
    """纯文本框（无填充无边框）"""
    t = slide.shapes.add_textbox(px(x), px(y), px(w), px(h))
    tf = t.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    tf.vertical_anchor = anchor
    return tf


def vtb(slide, x, y, w, h, anchor=MSO_ANCHOR.MIDDLE):
    """卡片内文字专用框：**垂直居中**。

    坑（2026-10-08 踩了 22 处）：python-pptx 的文本框不会按内容自动撑高，
    给固定高度的框塞多段文字时，所有段落都从框顶开始排 —— 于是
    「标题」和「描述」各自贴顶、互相叠压，而卡片底部大片留白。
    凡是「一段标题 + 若干段正文」的卡片，都要用这个 MIDDLE 版，
    让整块文字在给定矩形内居中，超出部分上下对称溢出而不是往上堆。
    """
    return tb(slide, x, y, w, h, anchor=anchor)


def para(tf, text, size=22, color=TEXT, bold=False, font=F_BODY, align=PP_ALIGN.LEFT,
         space_before=0, space_after=6, line=1.35, first=False):
    p = tf.paragraphs[0] if first else tf.add_paragraph()
    p.alignment = align
    p.space_before = Pt(space_before)
    p.space_after = Pt(space_after)
    p.line_spacing = line
    # 换行符必须用 add_break()：直接写 '\n' 在 python-pptx 里会被当成软空格忽略，
    # 结果「标题\n描述」会渲染成「标题描述」连在一起（p06/p07 连续踩到）。
    parts = str(text).split('\n')
    for i, seg in enumerate(parts):
        if i:
            p.add_line_break()
        r = p.add_run()
        r.text = seg
        r.font.size = Pt(size)
        r.font.color.rgb = color
        r.font.bold = bold
        r.font.name = font
    return p


def rich(tf, segs, size=22, align=PP_ALIGN.LEFT, space_before=0, space_after=6,
         line=1.35, first=False):
    """一段里混排多段不同样式：segs = [(text, {size,color,bold,font}), ...]

    段内文本里的 '\\n' 会转成软换行（add_line_break）——
    **这是让「标题\\n描述」正确分两行的唯一可靠写法**：
    用两个 para() 写同一框，PowerPoint 会把它们渲染到同一行（实测 p06/p07 连续踩）。
    """
    p = tf.paragraphs[0] if first else tf.add_paragraph()
    p.alignment = align
    p.space_before = Pt(space_before)
    p.space_after = Pt(space_after)
    p.line_spacing = line
    for text, st in segs:
        parts = str(text).split('\n')
        for i, seg in enumerate(parts):
            if i:
                p.add_line_break()
            r = p.add_run()
            r.text = seg
            r.font.size = Pt(st.get('size', size))
            r.font.color.rgb = st.get('color', TEXT)
            r.font.bold = st.get('bold', False)
            r.font.name = st.get('font', F_BODY)
    return p


def footer(slide, idx, dark=False):
    """D 区页脚装饰条 + 页码。三段不等宽 52/30/18"""
    y = 664
    rect(slide, 0, y, W * 0.52, 9, fill=BLUE)
    rect(slide, W * 0.52, y, W * 0.30, 9, fill=NAVY)
    rect(slide, W * 0.82, y, W * 0.18, 9, fill=GOLD)
    tf = tb(slide, PAD, y + 18, W - PAD * 2, 24)
    c = GRAY if not dark else RGBColor(0x9F, 0xB4, 0xD0)
    para(tf, '案件指派系统 CaseFlow · 技术汇报', size=14, color=c, space_after=0, first=True)
    tf2 = tb(slide, W - PAD - 160, y + 18, 160, 24)
    para(tf2, '%02d / %d' % (idx, TOTAL), size=14, color=c,
         align=PP_ALIGN.RIGHT, space_after=0, font=F_LATIN, first=True)


def title(slide, text, idx, sub=None):
    """B 区标题块 + D 区页脚。正文页统一入口"""
    tf = tb(slide, PAD, 52, W - PAD * 2, 58)
    para(tf, text, size=34, color=NAVY, bold=True, font=F_TITLE, space_after=0, first=True)
    if sub:
        tf2 = tb(slide, PAD, 100, W - PAD * 2, 22)
        para(tf2, sub, size=16, color=TEXT2, space_after=0, first=True)
    footer(slide, idx)


def section(slide, no, name, sub, points, idx):
    """章节扉页：白底左右分屏 PART 现代版
       左 50%：PART + 巨数 + 警蓝短竖条 + 中文章节名 + 副标 + 3 关键词
       右 50%：主题图示出血"""
    rect(slide, 0, 0, W, H, fill=WHITE)
    # 右侧主题面（藏蓝出血）
    rect(slide, W * 0.50, 0, W * 0.50, H, fill=NAVY)
    rect(slide, W * 0.50, 0, 10, H, fill=GOLD)

    tf = tb(slide, PAD, 106, 300, 34)
    para(tf, 'PART', size=20, color=GOLD, bold=True, font=F_LATIN, space_after=0, first=True)
    # 巨数 01–06：字号 160，行高 1.0 → 实际占 ~213px，框高给足并用 MIDDLE 居中
    tf = vtb(slide, PAD - 8, 132, 400, 216)
    para(tf, no, size=160, color=BLUE, bold=True, font=F_LATIN,
         space_after=0, line=1.0, first=True)
    # 警蓝短竖条 + 章节名
    rect(slide, PAD, 384, 6, 62, fill=BLUE)
    tf = vtb(slide, PAD + 22, 384, 460, 62)
    para(tf, name, size=50, color=NAVY, bold=True, font=F_TITLE, space_after=0, first=True)
    tf = tb(slide, PAD + 22, 456, 460, 32)
    para(tf, sub, size=19, color=TEXT2, space_after=0, first=True)
    # 3 个关键词点
    y = 512
    for pt in points:
        rect(slide, PAD + 24, y + 8, 8, 8, fill=GOLD)
        tf = tb(slide, PAD + 46, y, 400, 26)
        para(tf, pt, size=17, color=TEXT2, space_after=0, first=True)
        y += 36
    footer(slide, idx)


def hero_bg(slide):
    """美术层满幅底：藏蓝 + 径向光晕"""
    rect(slide, 0, 0, W, H, fill=NAVY)
    # 右上光晕（用两层不同大小圆近似辉光）
    for r, a in ((300, RGBColor(0x1A, 0x36, 0x5E)), (220, RGBColor(0x22, 0x44, 0x74))):
        c = rect(slide, W - 300 - r / 2, 150 - r / 2, r, r,
                 fill=a, shape=MSO_SHAPE.OVAL)
    # 左下细网格线（科技感，克制）
    for i in range(9):
        rect(slide, 40 + i * 46, 470, 2, 150, fill=RGBColor(0x1C, 0x3B, 0x66))


def card(slide, x, y, w, h, head=None, kind='A', head_h=50):
    """卡片：A 标准白卡 / B 警蓝头白卡 / C 藏蓝实心卡"""
    if kind == 'C':
        rect(slide, x, y, w, h, fill=NAVY, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.06)
        return
    rect(slide, x, y, w, h, fill=WHITE, line=LINE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.06)
    if head and kind == 'B':
        rect(slide, x, y, w, head_h, fill=BLUE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.10)
        rect(slide, x, y + head_h - 10, w, 10, fill=BLUE)
        tf = tb(slide, x + 24, y, w - 48, head_h, anchor=MSO_ANCHOR.MIDDLE)
        para(tf, head, size=24, color=WHITE, bold=True, space_after=0, first=True)


def pic(slide, name, x, y, w=None, h=None):
    """贴图：按给定宽或高等比缩放"""
    p = os.path.join(ASSETS, name)
    if not os.path.exists(p):
        return None
    im = Image.open(p)
    ar = im.height / im.width
    if w and not h:
        h = w * ar
    if h and not w:
        w = h / ar
    return slide.shapes.add_picture(p, px(x), px(y), px(w), px(h))


def pagenum(idx, total=TOTAL):
    return '%02d' % idx
