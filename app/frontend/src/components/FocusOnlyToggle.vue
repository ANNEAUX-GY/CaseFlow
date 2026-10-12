<template>
  <!-- 只看重点（2026-10-12）：与「到期提醒」同一个控件。
       inline = 放进筛选条/工具条里（不独占一行），窄条页面用这个更耐看 -->
  <div v-if="canFocus" class="cf-focus-toggle" :class="{ 'is-inline': inline }">
    <el-checkbox :model-value="modelValue" :size="inline ? 'small' : 'default'" @change="onChange">只看重点</el-checkbox>
    <span v-if="modelValue" class="cf-muted" style="margin-left: 8px">仅显示已标为重点的案件</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useUserStore } from '../store/user'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  inline: { type: Boolean, default: false }
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
