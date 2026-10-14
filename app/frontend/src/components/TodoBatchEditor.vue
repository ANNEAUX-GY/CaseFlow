<template>
  <div class="cf-bt">
    <!-- 顶部提示：默认说清"怎么连着写"；指派 / 建案由外部传更贴切的文案 -->
    <div v-if="hint" :class="c('hint')">
      <el-icon><InfoFilled /></el-icon>
      <span v-html="hint" />
    </div>

    <!-- 列头只说一次列义，行里只放控件。
         这是它比"每行一套 label"更省地方的关键：十几条待办也不会把弹窗撑爆。 -->
    <div :class="c('grid', 'grid--head')">
      <span>序号</span>
      <span>待办内容（必填）</span>
      <span>截止时间（选填）</span>
      <span>重要程度</span>
      <span />
    </div>

    <div ref="rowsEl" :class="c('rows')">
      <div v-for="(r, i) in rows" :key="r.key" :class="c('grid', 'row')">
        <span :class="c('no')">{{ i + 1 }}</span>
        <el-input
          v-model="r.content"
          :class="c('content')"
          maxlength="500"
          :placeholder="placeholder"
          @keyup.enter="onEnter(i)"
        />
        <el-date-picker
          v-model="r.deadline"
          :class="c('date')"
          type="datetime"
          size="small"
          format="YYYY-MM-DD HH:mm"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="选填"
          :clearable="true"
        />
        <el-radio-group v-model="r.importance" size="small" :class="['cf-abc', c('abc')]">
          <el-radio-button value="A" class="is-a">A</el-radio-button>
          <el-radio-button value="B" class="is-b">B</el-radio-button>
          <el-radio-button value="C" class="is-c">C</el-radio-button>
        </el-radio-group>
        <el-button
          link
          type="danger"
          :class="c('del')"
          :title="rows.length === 1 ? '清空这一行' : '删除这一行'"
          @click="removeRow(i)"
        >
          <el-icon><Delete /></el-icon>
        </el-button>
      </div>
    </div>

    <!-- 常用待办：服务端按「本人写过几次」记下来的（次数越多排越前）。
         fill 模式点一下填进表格第一空行，确认内容与时限后再统一保存；
         submit 模式供明确需要直接落库的调用方使用。 -->
    <div v-if="presets && presets.length" :class="c('preset')">
      <div :class="c('preset-label')">
        常用待办
        <span :class="c('preset-tip')">
          {{ presetMode === 'submit' ? '点一下就直接加一条，不用手打' : '点一下填进上面的表格' }}
        </span>
      </div>
      <div :class="c('chips')">
        <button
          v-for="p in presets"
          :key="p.id"
          type="button"
          :class="c('chip')"
          :disabled="presetSaving || (presetMode === 'fill' && rows.length >= max)"
          :title="presetMode === 'submit' ? `点击直接添加：${p.content}` : `点击填入：${p.content}`"
          @click="onPreset(p)"
        >
          <span :class="c('chip-plus')">＋</span>
          <span :class="c('chip-text')">{{ p.content }}</span>
          <span v-if="p.useCount > 1" :class="c('chip-n')">{{ p.useCount }}</span>
        </button>
      </div>
    </div>

    <div v-if="showLegend" :class="c('legend')">
      重要程度：<b class="is-a">A</b> 最重要 · <b class="is-b">B</b> 重要 · <b class="is-c">C</b> 一般
    </div>
  </div>
</template>

<script setup>
/**
 * 待办批量编辑器（2026-10-13 抽出，三处共用）。
 *
 * <p><b>为什么抽成公共组件</b>：待办的录入现在有三个入口——案件详情的
 * 「添加待办」弹窗、指派弹窗、建案弹窗。用户明确要求三者**逻辑一致**，
 * 而"一致"最容易在复制粘贴中失效（改了一处忘了另一处）。
 * 交互细节多到抄一遍必错：Enter 续行、新行沿用上一行重要程度、上限 10、
 * 只剩一行时删除=清空、A/B/C 三档、常用待办、已填 N 条统计。
 * 任何一处漏掉都会变成「指派能填截止时间、详情不能」这类用户看得见的不一致。
 *
 * <p><b>class 双写的原因</b>：`.cf-bt__*` 是本组件的样式钩子（单一来源，
 * 改样式只动这里）；同时按 `scope` 再挂一层 `.cf-btodo__*`，
 * 是为了让已有的 e2e 脚本（cf-e2e-todo-batch.mjs / cf-e2e-todo-preset.mjs）
 * 继续按原选择器定位，不必为了重构去改断言——断言一改，回归就失去意义了。
 *
 * <p><b>常用待办的两种点法</b>（`presetMode`）：
 * <ul>
 *   <li>`submit` = 点一下触发上层直接落库，供明确需要此行为的调用方使用；</li>
 *   <li>`fill` = 点一下填进表格第一空行。用户可以先检查、修改内容与时限，
 *       再通过当前表单的「保存」统一提交，取消时不会产生待办。</li>
 * </ul>
 */
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, InfoFilled } from '@element-plus/icons-vue'

const props = defineProps({
  /** 行数组（受控）。每行 { key, content, deadline, importance } */
  rows: { type: Array, default: () => [] },
  /** 一次最多几行。与后端 MAX_BATCH 一致，超了后端也会拒。 */
  max: { type: Number, default: 10 },
  /** 兼容 class 前缀。默认 'cf-btodo'（保持既有 e2e 选择器可用） */
  scope: { type: String, default: 'cf-btodo' },
  /** 顶部提示文案（允许 <b>）。留空则不显示这一条。 */
  hint: { type: String, default: '' },
  placeholder: { type: String, default: '写清要办的事，如：72 小时内完成受害人走访' },
  /** 常用待办列表 */
  presets: { type: Array, default: () => [] },
  /** 常用待办的点法：fill=填入表格 / submit=直接落库 */
  presetMode: { type: String, default: 'fill' },
  presetSaving: { type: Boolean, default: false },
  showLegend: { type: Boolean, default: true }
})
const emit = defineEmits(['update:rows', 'preset-click'])

const rowsEl = ref(null)
let rowSeq = 0

/** 同时给出样式钩子与兼容钩子两套 class */
const c = (...suffixes) => suffixes.flatMap(
  (sfx) => [`cf-bt__${sfx}`, `${props.scope}__${sfx}`]
)

const newRow = (importance = 'C') => ({
  key: ++rowSeq,
  content: '',
  deadline: '',
  importance
})

/** 已填（内容非空白）的行数——「已填 N 条」与提交范围都用它 */
const filled = computed(() => props.rows.filter((r) => (r.content || '').trim()))

/** 光标定位到第 i 行的内容框。用容器 ref 逐层往下找，不做全局查询：
 *  详情抽屉与盯办抽屉各挂一份本组件时，全局 querySelector 会点错面板。 */
const focusRow = (i) => {
  const el = rowsEl.value?.children?.[i]?.querySelector('input')
  el?.focus()
}

/** Enter = 接着写下一行；已在最后一行就追加一行（到上限则原地不动） */
const onEnter = (i) => {
  if (i < props.rows.length - 1) {
    focusRow(i + 1)
    return
  }
  addRow(i)
}

/**
 * 追加一行。after 为空即加到末尾。
 * 新行沿用上一行的重要程度——一批待办常常是同一档，省得每条都点一遍。
 */
const addRow = (after) => {
  if (props.rows.length >= props.max) return
  const at = after == null ? props.rows.length - 1 : after
  const prev = props.rows[at]
  const next = [...props.rows]
  next.splice(at + 1, 0, newRow(prev ? prev.importance : 'C'))
  emit('update:rows', next)
  nextTick(() => focusRow(at + 1))
}

/** 只剩一行时不清空整个列表，只把这一行恢复成空白——按钮语义仍是「这条不要了」 */
const removeRow = (i) => {
  if (props.rows.length === 1) {
    emit('update:rows', [newRow('C')])
    nextTick(() => focusRow(0))
    return
  }
  const next = [...props.rows]
  next.splice(i, 1)
  emit('update:rows', next)
}

/** 点常用待办：submit 模式交给上层落库，fill 模式填进第一空行（没有就追加一行） */
const onPreset = (p) => {
  if (props.presetMode === 'submit') {
    emit('preset-click', p)
    return
  }
  const next = props.rows.map((r) => ({ ...r }))
  const blank = next.findIndex((r) => !(r.content || '').trim())
  if (blank >= 0) {
    next[blank].content = p.content
    next[blank].importance = p.importance || 'C'
    emit('update:rows', next)
    nextTick(() => focusRow(blank))
    return
  }
  if (next.length >= props.max) {
    ElMessage.warning(`一次最多添加 ${props.max} 条待办，请先保存再来添加`)
    return
  }
  const at = next.length - 1
  next.splice(at + 1, 0, { ...newRow(p.importance || 'C'), content: p.content })
  emit('update:rows', next)
  nextTick(() => focusRow(at + 1))
}

/** 重置成 count 行空白（打开弹窗 / 建案表单初始化时用） */
const reset = (count = 1) => {
  emit('update:rows', Array.from({ length: count }, () => newRow('C')))
}

/** 打开后把光标放进第一行，少点一次鼠标 */
const focusFirst = () => nextTick(() => focusRow(0))

/** 取出提交用的条目（空行已滤掉） */
const items = () => filled.value.map((r) => ({
  content: (r.content || '').trim(),
  deadline: r.deadline || '',
  importance: r.importance || 'C'
}))

// 行数被外部改小时（例如建案表单重置），兜住上限避免超发
watch(() => props.rows.length, (n) => {
  if (n > props.max) emit('update:rows', props.rows.slice(0, props.max))
})

defineExpose({ addRow, removeRow, reset, focusFirst, items, filled, focusRow })
</script>

<style>
/* ============ 待办批量编辑器 ============
   列宽一套变量统一管：序号 / 内容 / 截止时间 / 重要程度 / 删除。
   样式钩子固定用 `.cf-bt__*`（不走 scope 变量），三个入口共用同一份——
   各自按 scope 派生一份 CSS 只会带来"改了一处忘了另一处"的漂移。 */
.cf-bt { --cf-bt-cols: 36px minmax(0, 1fr) 182px 124px 30px; }

.cf-bt__hint {
  display: flex; align-items: flex-start; gap: 6px;
  margin-bottom: 12px; padding: 8px 12px; border-radius: 6px;
  background: #f2f6fc; color: #1b4a8c; font-size: 12.5px; line-height: 1.6;
}
.cf-bt__hint .el-icon { margin-top: 2px; flex: none; color: #1b4a8c }
.cf-bt__hint b { color: #12294a }

.cf-bt__grid {
  display: grid;
  grid-template-columns: var(--cf-bt-cols);
  gap: 10px;
  align-items: center;
}
/* 表头：加粗一点、贴一条底边线，把"这是列名"和"这是数据"分开 */
.cf-bt__grid--head {
  padding: 0 8px 6px;
  font-size: 12px; font-weight: 600; color: #5a6472;
  border-bottom: 1px solid #e4e8ee;
}
.cf-bt__rows { max-height: 46vh; overflow-y: auto; padding: 0 8px }
.cf-bt__row { padding: 7px 0; border-bottom: 1px dashed #eef1f6 }
.cf-bt__row:last-child { border-bottom: none }
.cf-bt__row:hover { background: #fafcff }
.cf-bt__row .el-date-editor { width: 100% }
/* 注意：Element 的日期选择器根元素同时带 el-input 类，所以窄屏排版
   必须按自己的类定位（.cf-bt__content / .cf-bt__date），
   用 .el-input 会连日期框一起命中、两个控件叠到同一个栅格区里。 */
.cf-bt__no {
  width: 22px; height: 22px; line-height: 22px; text-align: center;
  background: #eef3fa; color: #1b4a8c; border-radius: 50%;
  font-size: 12px; font-weight: 700; font-variant-numeric: tabular-nums;
}
.cf-bt__del { padding: 0 !important; height: 22px }

/* 常用待办：一排可点的标签。用原生 button 而不是 el-tag——它天生带 disabled
   与键盘可达，而 el-tag 是纯展示组件，点击语义要靠额外的 tabindex 补。 */
.cf-bt__preset { margin-top: 12px; padding: 10px 8px 0; border-top: 1px dashed #e4e8ee }
.cf-bt__preset-label {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  font-size: 12.5px; font-weight: 600; color: #1b2430;
}
.cf-bt__preset-tip { font-weight: 400; color: #8a929e }
.cf-bt__chips { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px }
.cf-bt__chip {
  display: inline-flex; align-items: center; gap: 4px; max-width: 100%;
  padding: 4px 10px; border: 1px solid #c9d8ec; border-radius: 999px;
  background: #f5f9ff; color: #1b4a8c; font-size: 12.5px; line-height: 1.5;
  cursor: pointer; transition: background .15s, border-color .15s;
}
.cf-bt__chip:hover:not(:disabled) { background: #e6effb; border-color: #93b4df }
.cf-bt__chip:disabled { opacity: .55; cursor: not-allowed }
.cf-bt__chip-plus { flex: none; font-weight: 700 }
/* 内容过长时截断：标签是"一眼认出来"，完整内容在 title 与添加后的列表里都有 */
.cf-bt__chip-text {
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cf-bt__chip-n {
  flex: none; min-width: 16px; padding: 0 4px; border-radius: 999px;
  background: #1b4a8c; color: #fff; font-size: 11px; text-align: center;
  font-variant-numeric: tabular-nums;
}
.cf-bt__legend { padding: 10px 8px 0; font-size: 12px; color: #8a929e }
.cf-bt__legend b { font-weight: 700 }
.cf-bt__legend .is-a,
.cf-bt__legend .is-b,
.cf-bt__legend .is-c {
  display: inline-block; min-width: 18px; padding: 1px 5px; border: 1px solid transparent;
  border-radius: 3px; text-align: center; font-style: normal;
}
.cf-bt__legend .is-a { color: #fff; background: #c62a2a; }
.cf-bt__legend .is-b { color: #3d2f00; background: #f4c542; }
.cf-bt__legend .is-c { color: #303133; background: #fff; border-color: #cfd4dc; }

/* 底部条（由 TodoBatchFoot 渲染）：条数统计 + 「再添一条」 */
.cf-bt__foot {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.cf-bt__foot-left { display: flex; align-items: center; gap: 12px }
.cf-bt__count { font-size: 12px; color: #8a929e }
.cf-bt__count b { color: #1b4a8c; font-size: 13px; font-variant-numeric: tabular-nums }

/* 窄屏：栅格改成三条竖排（序号+内容+删除 / 截止时间 / 重要程度），
   否则固定列宽加起来会比屏幕还宽，内容框被压成一条缝。 */
@media (max-width: 760px) {
  .cf-bt__grid--head { display: none }
  .cf-bt__row {
    grid-template-columns: 26px minmax(0, 1fr) 26px;
    grid-template-areas:
      'no  content del'
      '.   date    date'
      '.   abc     abc';
    row-gap: 6px;
  }
  .cf-bt__no { grid-area: no }
  .cf-bt__content { grid-area: content }
  .cf-bt__del { grid-area: del }
  .cf-bt__date { grid-area: date }
  .cf-bt__abc { grid-area: abc }
}
</style>
