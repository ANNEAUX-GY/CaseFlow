# -*- coding: utf-8 -*-
"""构建案件指派系统技术汇报 PPT

注意页面顺序：python-pptx 只能往后追加，所以先把 28 个页面函数收集起来，
最后按**页码顺序**统一追加 —— 否则六张章节扉页会全挤在前面
（踩过一次：扉页占了第 4-8 位，内容页全错位）。
"""
import os
from pptx import Presentation

from gen_deck import px, W, H
import pages_arts as A
import pages_ch1 as C1
import pages_ch2 as C2
import pages_ch34 as C34
import pages_ch56 as C56

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   '案件指派系统技术汇报.pptx')

# 页码 → 构造函数（**顺序即页序**，改这里就能调页序）
PAGES = [
    A.p01_cover,        # 01
    A.p02_catalog,      # 02
    A.s03,              # 03 章节扉页 01 系统全景
    C1.p04_pain,        # 04
    C1.p05_loop,        # 05
    C1.p06_capability,  # 06
    C1.p07_roles,       # 07
    C1.p08_screenshot,  # 08
    A.s09,              # 09 章节扉页 02 技术架构
    C2.p10_stack,       # 10
    C2.p11_arch,        # 11
    C2.p12_snapshot,    # 12
    A.s13,              # 13 章节扉页 03 数据库设计
    C34.p14_tables,     # 14
    C34.p15_relations,  # 15
    C34.p16_index,      # 16
    A.s17,              # 17 章节扉页 04 接口与路由
    C34.p18_api,        # 18
    C34.p19_guard,      # 19
    A.s20,              # 20 章节扉页 05 业务操作逻辑
    C56.p21_lifecycle,  # 21
    C56.p22_decisions,  # 22
    C56.p23_notify,     # 23
    A.s24,              # 24 章节扉页 06 保障与展望
    C56.p25_quality,    # 25
    C56.p26_gate,       # 26
    C56.p27_roadmap,    # 27
    C56.p28_ending,     # 28
]

prs = Presentation()
prs.slide_width = px(W)
prs.slide_height = px(H)

for i, fn in enumerate(PAGES, 1):
    fn(prs)

prs.save(OUT)
print('已生成：%s' % OUT)
print('页数：%d' % len(prs.slides._sldIdLst))
