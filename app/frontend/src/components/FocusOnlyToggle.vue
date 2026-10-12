<template>
  <!-- 只看重点（2026-10-12）：与「到期提醒」同一个控件。
       inline = 放进筛选条/工具条里（不独占一行），窄条页面用这个更耐看；
       lead   = 排在筛选条最左端的「星标筛选片」，选中时整片镀金，与重点行高亮同色。 -->
  <div v-if="canFocus" class="cf-focus-toggle"
    :class="{ 'is-inline': inline, 'is-lead': lead }">
    <button v-if="lead" type="button" class="cf-focus-lead"
      :class="{ 'is-on': modelValue }"
      :aria-pressed="modelValue ? 'true' : 'false'"
      @click="onChange(!modelValue)">
      <el-icon :size="14"><StarFilled v-if="modelValue" /><Star v-else /></el-icon>
      <span>只看重点</span>
    </button>
    <el-checkbox v-else :model-value="modelValue"
      :size="inline ? 'small' : 'default'" @change="onChange">只看重点</el-checkbox>
    <span v-if="modelValue" class="cf-muted cf-focus-toggle__hint">仅显示已标为重点的案件</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Star, StarFilled } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  inline: { type: Boolean, default: false },
  /** 排在筛选条最左端的筛选片（带星标），选中镀金 */
  lead: { type: Boolean, default: false }
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