<template>
  <div ref="el" class="cf-echart" :style="{ width: '100%', height: styleHeight }" />
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, TreeChart, GraphChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

// GraphChart：领导意见「工作流程图」用的关系图（节点+箭头），按需注册不影响其他图表
echarts.use([BarChart, LineChart, PieChart, TreeChart, GraphChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent, CanvasRenderer])

const props = defineProps({
  option: { type: Object, required: true },
  // 数字 = 固定像素高度；字符串（如 '100%'）= 跟随父容器高度自适应
  height: { type: [Number, String], default: 220 }
})
const emit = defineEmits(['click'])

const styleHeight = computed(() => (typeof props.height === 'number' ? `${props.height}px` : props.height))

const el = ref(null)
let chart = null
let ro = null
let timer = null
let frame = null
let disposed = false

const render = () => {
  if (!chart || !props.option) return
  // notMerge=true：切换图表类型时不会残留上一份配置
  chart.setOption(props.option, true)
}

const resize = () => {
  if (frame !== null) return
  frame = requestAnimationFrame(() => {
    frame = null
    ensureSize()
  })
}

// 容器尺寸变化（窗口缩放、侧栏折叠、卡片重排）都要跟上，
// 否则图表会停留在旧尺寸，看起来像「跑位了」。
// 宽、高都要比：自适应高度的图表是靠高度变化才需要重绘的。
const ensureSize = () => {
  if (!chart || !el.value) return
  const w = el.value.clientWidth
  const h = el.value.clientHeight
  if (w > 0 && h > 0 && (Math.abs(w - chart.getWidth()) > 1 || Math.abs(h - chart.getHeight()) > 1)) {
    chart.resize()
  }
}

onMounted(async () => {
  await nextTick()
  if (disposed || !el.value) return
  chart = echarts.init(el.value)
  render()
  chart.on('click', (params) => emit('click', params))
  if (window.ResizeObserver) {
    ro = new ResizeObserver(resize)
    ro.observe(el.value)
  } else {
    window.addEventListener('resize', resize)
  }
  // 容器首帧可能还没布局完（宽度 0），补一次
  timer = setTimeout(ensureSize, 120)
})

onBeforeUnmount(() => {
  disposed = true
  clearTimeout(timer)
  if (frame !== null) cancelAnimationFrame(frame)
  window.removeEventListener('resize', resize)
  if (ro) { ro.disconnect(); ro = null }
  if (chart) {
    chart.dispose()
    chart = null
  }
})

watch(() => props.option, render, { deep: true })
watch(() => props.height, () => { nextTick(() => { resize(); ensureSize() }) })
</script>

<style scoped>
/* 容器高度固定，避免图表渲染前后页面抖动 */
.cf-echart { width: 100%; overflow: hidden; }
</style>
