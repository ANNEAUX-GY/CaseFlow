# -*- coding: utf-8 -*-
"""构建时断言：检查每张「要点卡」的卡高是否装得下里面的要点。

**为什么必须有这个**（2026-10-08 实测踩了 3 轮）：
python-pptx 的文本框不随内容撑高。卡里塞 4 条要点、卡却只有 178 高，
渲染出来就是**要点溢出到卡外白底上**，肉眼看整体页面只觉得"好像有点乱"，
很难定位到是哪一张卡。

卡内所需高 ≈ Σ(每条要点折行数 × 字号 × 行距) + 要点条数 × 段后距
判据：所需高 ≤ 卡高 - 卡头 - 上下留白(24)
"""
import io
import os
import re
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
FILES = ['pages_arts.py', 'pages_ch1.py', 'pages_ch2.py',
         'pages_ch34.py', 'pages_ch56.py']

# 找出所有 card(...) / rect(...) 定义的卡，以及其内部 vtb 的高度与字号
CARD_RE = re.compile(r"card\(s,\s*([^,]+),\s*([^,]+),\s*([\w.\-]+),\s*([\w.()\-]+)")
VTB_RE = re.compile(r"vtb\(s,\s*([^,]+),\s*([^,]+),\s*([\w.\-+ ]+?),\s*([\w.()\-]+)\)")
SIZE_RE = re.compile(r"'size':\s*(\d+)")
PARA_SIZE_RE = re.compile(r"para\(tf,[^)]*?size=(\d+)")

HEAD_H_DEFAULT = 50     # card(kind='B') 默认卡头
PAD = 24                # 卡内上下留白合计


def cw_ratio(ch):
    o = ord(ch)
    return 1.0 if o > 0x2E80 else (0.32 if ch == ' ' else (0.6 if ch in '·—→←（）()、，。：；「」' else 0.52))


def need_h(text, size, line, box_w, space_after=0):
    size_px = size * 96.0 / 72.0
    per_line = max(1.0, box_w / size_px)
    wsum = sum(cw_ratio(c) for c in text)
    lines = int(wsum / per_line) + (1 if wsum % per_line > 0.01 else 0)
    return lines * size_px * line * 1.30 + space_after * 96.0 / 72.0


print('%-14s %-8s %7s %7s %7s  %s' % ('文件', '行', '卡高', '需高', '差额', '结论'))
print('-' * 70)
issues = []
for f in FILES:
    p = os.path.join(BASE, f)
    src = io.open(p, encoding='utf-8').read()
    lines = src.split('\n')
    # 找 card(...) 后 12 行内的 vtb，估算其所需高度
    for i, ln in enumerate(lines):
        m = CARD_RE.search(ln)
        if not m:
            continue
        card_h = m.group(4)
        if not card_h.replace('.', '').isdigit():
            continue
        ch = int(card_h)
        # 找紧随的 vtb
        vt = None
        for j in range(i + 1, min(i + 16, len(lines))):
            mv = VTB_RE.search(lines[j])
            if mv:
                vt = (j, mv)
                break
        if not vt:
            continue
        vline, mv = vt
        # 收集该 vtb 之后直到下一个 tb/vtb 之间的段落文字
        body = []
        sizes = []
        for j in range(vline, min(vline + 30, len(lines))):
            if j > vline and (VTB_RE.search(lines[j]) or 'tb(s,' in lines[j] or 'rect(s,' in lines[j] or 'card(s,' in lines[j] or 'for ' in lines[j] or 'yy +=' in lines[j] or 'y +=' in lines[j]):
                break
            body.append(lines[j])
            for sm in SIZE_RE.findall(lines[j]):
                sizes.append(int(sm))
            for sm in PARA_SIZE_RE.findall(lines[j]):
                sizes.append(int(sm))
        if not body or not sizes:
            continue
        txt = ''.join(body)
        texts = re.findall(r"'([^']{2,})'", txt)
        if not texts:
            continue
        bw_m = mv.group(3).strip()
        # 估算框宽：数字字面值或常见变量
        bw = {'cw2 - 40': 336, 'cw - 40': 300, 'cw - 48': 300, 'cw - 56': 280,
              'cw - 60': 680, 'cw - 90': 590, 'cw - 170': 560, 'cw - 140': 570,
              'cw2 - 96': 314, 'cw - 190': 580, 'cw - 168': 592}.get(bw_m, 320)
        size = max(sizes) if sizes else 14
        line = 1.38
        lm = re.search(r"line=([\d.]+)", txt)
        if lm:
            line = float(lm.group(1))
        sa = 4 if txt.count("'size'") > 2 or 'for it in' in txt else 0
        total = sum(need_h(t, size, line, bw, sa) for t in texts)
        head = HEAD_H_DEFAULT if 'kind=' in ln and "'B'" in ln else 0
        avail = ch - head - PAD
        diff = total - avail
        if diff > 0:
            issues.append((f, i + 1, ch, total, diff, texts[0][:24]))
            print('%-14s %-8d %7d %7.0f %+7.0f  ⚠ 超出 %s' % (
                f.replace('pages_', ''), i + 1, ch, total, diff, texts[0][:20]))

print()
if issues:
    print('发现 %d 张卡可能溢出：' % len(issues))
    for f, ln, ch, need, diff, t in issues:
        print('   %s:%d  卡高 %d 需 %.0f 缺 %.0f  「%s」' % (f, ln, ch, need, diff, t))
    sys.exit(1)
print('✅ 未发现卡片高度不足')
