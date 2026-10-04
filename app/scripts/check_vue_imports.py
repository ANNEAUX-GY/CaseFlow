#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
扫描前端所有 .vue 里「用了 Vue 组合式 API 但没从 vue 导入」的隐患。

背景：漏导入 computed/ref/watch 这类问题**编译期不报错**（Vite 只做转译不做作用域检查），
只在运行时 setup() 执行到那一行才抛 ReferenceError，而且往往表现为
「整块功能静默不出现」，极难定位。
本脚本用文本匹配做静态排查，作为发版前的一道闸。
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "frontend", "src")
API = ["computed", "ref", "reactive", "watch", "onMounted", "onBeforeUnmount",
       "onUnmounted", "nextTick", "defineProps", "defineEmits", "defineExpose",
       "shallowRef", "toRef", "toRefs", "watchEffect"]

# 宏不需要导入（编译期由插件处理）
MACRO = {"defineProps", "defineEmits", "defineExpose"}

problems = []
scanned = 0

for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in ("node_modules", "dist", ".git")]
    for fn in filenames:
        if not fn.endswith((".vue", ".js")):
            continue
        p = os.path.join(dirpath, fn)
        with open(p, "r", encoding="utf-8", errors="replace") as f:
            src = f.read()
        scanned += 1
        if fn.endswith(".js"):
            continue  # .js 里 Vue API 未用引号形式出现的情况多，先不管

        # 只看 <script> 段
        m = re.search(r"<script[^>]*>(.*?)</script>", src, re.S)
        if not m:
            continue
        script = m.group(1)

        # 收集从 'vue' 导入的名字
        imported = set()
        for im in re.finditer(r"import\s*\{([^}]*)\}\s*from\s*['\"]vue['\"]", script):
            for name in im.group(1).split(","):
                name = name.strip().split(" as ")[0].strip()
                if name:
                    imported.add(name)

        missing = set()
        for api in API:
            if api in MACRO or api in imported:
                continue
            # 出现即视为使用：函数调用 `xxx(` 或模板里直接用
            if re.search(r"(?<![\w.$])%s\s*\(" % re.escape(api), script):
                missing.add(api)

        if missing:
            rel = os.path.relpath(p, os.path.join(ROOT, ".."))
            problems.append((rel, sorted(missing)))

print("扫描 %d 个文件" % scanned)
if not problems:
    print("✅ 未发现 Vue API 漏导入问题")
    sys.exit(0)

print("❌ 发现 %d 处可能的漏导入：" % len(problems))
for rel, names in problems:
    print("   %-46s 缺: %s" % (rel, ", ".join(names)))
print("")
print("修复方式：在该文件的 import { ... } from 'vue' 里补上对应名字。")
sys.exit(1)
