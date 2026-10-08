# -*- coding: utf-8 -*-
"""成品体检（可靠版）：直接读 .pptx，用 PIL 实测字体度量，算每个文本框
渲染后的真实高度，与「所在画布区域」比对。

**为什么必须实测字宽**：估算字宽（CJK=1.0、ASCII=0.52）在中文标点、
英文混排、数字上标下标上偏差可达 20%，导致「估算出通过、实渲染溢出」。
用 PIL 的 ImageFont.getlength() 拿到的是**真实字形宽度**，零猜测。

判据：
  1) 出血 —— 文字底边超过内容区下沿 660（页脚除外）
  2) 越界 —— 文字底边超过其所属容器（卡片）底边
"""
import os
import sys

from PIL import ImageFont
from pptx import Presentation
from pptx.util import Emu

BASE = os.path.dirname(os.path.abspath(__file__))
PPTX = os.path.join(BASE, '案件指派系统技术汇报.pptx')
EMU_PX = 9525.0
C_BOTTOM = 656.0

FONTS = {
    ('宋体', True): ('C:/Windows/Fonts/simhei.ttf', 0),   # simsunb.ttc 不存在，粗宋用黑体代
    ('宋体', False): ('C:/Windows/Fonts/simsun.ttc', 0),
    ('微软雅黑', True): ('C:/Windows/Fonts/msyhbd.ttc', 0),
    ('微软雅黑', False): ('C:/Windows/Fonts/msyh.ttc', 0),
    ('Arial', True): ('C:/Windows/Fonts/arialbd.ttf', 0),
    ('Arial', False): ('C:/Windows/Fonts/arial.ttf', 0),
}
_cache = {}


def font(name, bold, size_pt):
    px = max(6, int(round(size_pt * 96.0 / 72.0)))
    key = (name, bold, px)
    if key in _cache:
        return _cache[key]
    path, idx = FONTS.get((name, bold)) or FONTS[('微软雅黑', bold)]
    f = ImageFont.truetype(path, px, index=idx)
    _cache[key] = f
    return f


def e2p(v):
    return (v or 0) / EMU_PX


def run_width_px(text, name, bold, size_pt):
    if not text:
        return 0.0
    return font(name, bold, size_pt).getlength(text)


def frame_metrics(tf, box_w):
    """返回 (需要高度px, 最长单行是否超框宽)"""
    total = 0.0
    over_w = False
    for p in tf.paragraphs:
        runs = p.runs
        if not runs:
            sz = 13 * 96.0 / 72.0
            total += sz * 1.2
            continue
        ls = p.line_spacing if isinstance(p.line_spacing, float) else 1.2
        sa = p.space_after.pt if p.space_after else 0
        sb = p.space_before.pt if p.space_before else 0
        size_pt = max((r.font.size.pt for r in runs if r.font.size), default=13.0)
        # 逐 run 累加宽度，遇软换行则折行
        cur = 0.0
        max_size = size_pt
        lines = 1
        for r in runs:
            segs = r.text.split('\n')
            for i, seg in enumerate(segs):
                if i:
                    total += lines * max_size * 96.0 / 72.0 * ls
                    lines = 1
                    cur = 0.0
                    max_size = size_pt
                w = run_width_px(seg, r.font.name or '微软雅黑',
                                 bool(r.font.bold), size_pt)
                cur += w
                if cur > box_w:
                    lines += int(cur // box_w)
                    cur = cur % box_w
        total += lines * max_size * 96.0 / 72.0 * ls
        total += (sa + sb) * 96.0 / 72.0
        # 软换行符单独占一行
        for r in runs:
            total += r.text.count('\n') * 0  # 已在上面折行计入
    return total


prs = Presentation(PPTX)
print('画布 %.0f × %.0f' % (e2p(prs.slide_width), e2p(prs.slide_height)))
print('=' * 88)
issues = []
for si, slide in enumerate(prs.slides, 1):
    # 先收集卡片矩形（用于判定文字是否超出所属卡）
    cards = []
    texts = []
    for sh in slide.shapes:
        x, y = e2p(sh.left), e2p(sh.top)
        w, h = e2p(sh.width), e2p(sh.height)
        if sh.has_text_frame:
            t = ''.join(r.text for p in sh.text_frame.paragraphs for r in p.runs).strip()
            if t:
                texts.append((x, y, w, h, sh.text_frame, t))
        # 圆角矩形近似：宽>120 高>60 且无文字 → 当作卡片
        elif w > 120 and h > 60:
            cards.append((x, y, w, h))
    for (x, y, w, h, tf, t) in texts:
        if 'CaseFlow · 技术汇报' in t or '/ 28' in t:
            continue
        need = frame_metrics(tf, w)
        bottom = y + need
        # 1) 出血
        if bottom > C_BOTTOM + 4 and y < C_BOTTOM:
            issues.append((si, '出血', t[:30], y, bottom, C_BOTTOM))
        # 2) 超出所属卡片
        for (cx, cy, cw, ch) in cards:
            inside_x = cx - 4 <= x <= cx + cw
            inside_y = cy - 4 <= y <= cy + ch
            if inside_x and inside_y and bottom > cy + ch + 3:
                issues.append((si, '超卡', t[:30], bottom, cy + ch, (cy + ch) - y))
                break

if not issues:
    print('✅ 28 页均未发现文字出血或超出卡片')
else:
    print('发现 %d 处：\n' % len(issues))
    cur = None
    for it in issues:
        if it[0] != cur:
            print('── 第 %02d 页 ──' % it[0])
            cur = it[0]
        if it[1] == '出血':
            print('   [出血] 「%s」 底边 %.0f 超出内容区 %.0f（%.0fpx）' % (it[2], it[4], it[5], it[4] - it[5]))
        else:
            print('   [超卡] 「%s」 底边 %.0f 超出卡底 %.0f（%.0fpx）' % (it[2], it[3], it[4], -it[5]))
    sys.exit(1)
