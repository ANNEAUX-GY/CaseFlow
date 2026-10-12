<template>
  <!-- 只看重点（2026-10-12）：与「到期提醒」完全同一个位置、同一句提示。
       原先三个页面也都有这个勾选框，但塞在筛选行里（一堆下拉框挤在一起），
       用户根本发现不了——反馈"这三个栏目没有只看重点"就是这么来的。
       提到页面顶部后，卡片 / 图表 / 汇总 / 列表都在它下面，勾选即整页同口径。 -->
  <div v-if="canFocus" class="cf-focus-toggle">
    <el-checkbox :model-value="modelValue" @change="onChange">只看重点</el-checkbox>
    <span v-if="modelValue" class="cf-muted" style="margin-left: 8px">仅显示已标为重点的案件</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useUserStore } from '../store/user'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'change'])

const userStore = useUserStore()
// 与首列星标同一个权限判据：普通民警看不到星标列，
// 给他一个永远筛不出东西的勾选框只是添乱（后端 /cases/{id}/focus 也是 @FullAccessOnly）
const canFocus = computed(() => userStore.isFullAccess)

const onChange = (val) => {
  emit('update:modelValue', val)
  emit('change', val)
}
</script>
