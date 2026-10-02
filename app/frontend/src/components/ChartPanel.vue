<template>
  <div class="cf-panel cf-chart" :class="{ 'cf-chart--fill': fill }">
    <div class="cf-panel__head">
      <span>{{ title }}</span>
      <div class="cf-chart__tools">
        <slot name="tools" />
      </div>
    </div>
    <div class="cf-chart__body">
      <slot />
      <div v-if="empty" class="cf-chart__empty">暂无数据</div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  empty: { type: Boolean, default: false },
  // 填充模式：整块撑满所在容器高度，图表本体随之自适应（配合 EChart height="100%"）
  fill: { type: Boolean, default: false }
})
</script>

<style scoped>
.cf-chart__tools {
  display: flex;
  align-items: center;
  gap: 6px;
}
.cf-chart__body {
  position: relative;
  padding: 10px 12px 12px;
}
.cf-chart__empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--cf-text-3);
  font-size: 12px;
  background: rgba(255, 255, 255, 0.75);
}
/* 面板内控件紧凑化：不抢图表视觉 */
.cf-chart__tools :deep(.el-button + .el-button) { margin-left: 0; }
.cf-chart__tools :deep(.el-radio-button__inner) { padding: 4px 8px; }
.cf-chart__tools :deep(.el-select) { width: 96px; }

/* 填充模式：卡片 → 主体 → 图表 三级 flex，图表自动吃掉剩余高度 */
.cf-chart--fill { height: 100%; display: flex; flex-direction: column; }
.cf-chart--fill > .cf-chart__body { flex: 1; min-height: 0; }
</style>
