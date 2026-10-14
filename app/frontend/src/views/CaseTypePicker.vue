<template>
  <div class="cf-picker">
    <div class="cf-picker__head">
      <div class="cf-picker__title">请选择案件类型</div>
      <div class="cf-picker__sub">
        选择“查看全部”可直接进入栏目；刑事案件先选办案阶段，其他案件按原流程选择小类。
        列表、看板与统计会跟随当前选择
      </div>
    </div>

    <div class="cf-picker__cards">
      <div
        v-for="o in options" :key="o.key"
        class="cf-picker__card"
        :class="{ 'is-active': o.key === current }"
        role="button" tabindex="0"
        @click="choose(o)" @keyup.enter="choose(o)"
      >
        <div class="cf-picker__card-top">
          <span class="cf-picker__badge" :class="'is-' + o.type">{{ o.label }}</span>
          <el-icon v-if="o.key === current" class="cf-picker__ok"><CircleCheckFilled /></el-icon>
        </div>
        <div class="cf-picker__desc">{{ o.desc }}</div>
        <div class="cf-picker__count" v-if="counts[o.key] !== undefined">
          共 {{ counts[o.key] }} 件
        </div>
      </div>
    </div>

    <div class="cf-picker__foot cf-muted">
      提示：切换侧边栏栏目不会丢失当前选择；退回上一级或重选类型，请用顶部类型条右侧的按钮
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CASE_TYPE_OPTIONS, LEVEL3_PATHS, boardsUrl, useCaseTypeStore } from '../store/caseType'
import { caseApi } from '../api'

const props = defineProps({
  /** 选完类型后跳到哪；默认取地址栏的 from，再退到 store 记住的栏目 */
  redirectTo: { type: String, default: '' }
})
const emit = defineEmits(['chosen'])

const router = useRouter()
const route = useRoute()
const store = useCaseTypeStore()
const options = CASE_TYPE_OPTIONS
const current = ref('')
const counts = ref({})

/** 选中类型：写状态 → 进小类卡片页（第 2 级） */
const choose = async (o) => {
  if (!store.select(o.key)) {
    ElMessage.error('类型参数不合法')
    return
  }
  current.value = o.key
  emit('chosen', o.key)

  // from 可能是纯路径（'/cases'），也可能是带 query 的完整地址
  // （'/cases?status=IN_PROGRESS&employeeId=3'）——后者来自工作台/员工图谱的跨栏目跳转
  const target = resolveFrom()
  store.rememberPath(target.path)

  // “查看全部”跳过小类页，清掉地址中的类型和小类筛选后直接进入栏目。
  if (o.key === 'ALL') {
    // 保留通知的案件定位锚点，其余旧筛选一律清空。
    const query = {}
    for (const key of ['caseId', 'todoId', 'subtaskId']) {
      if (target.query?.[key]) query[key] = target.query[key]
    }
    router.push({ path: target.path, query })
    return
  }

  // 刑事案件先选办案阶段；初查再选小类，刑拘/取保监居直接进入案件盯办。
  if (o.key === 'CRIMINAL') {
    router.push({ path: '/case-stages' })
    return
  }

  // 其他大类沿用原有小类浏览，
  // 不再区分目标栏目；最终落点（含原有筛选）用 to 带过去，选完小类再落位。
  // 对应地，退出时也逐级退回：栏目页 → 小类页 → 大类页。
  const qs = target.query ? new URLSearchParams(target.query).toString() : ''
  const full = qs ? target.path + '?' + qs : target.path
  router.push(boardsUrl(full))
}

/** 解析 from 参数：还原成 { path, query }，非法值回退到 store 记住的栏目 */
const resolveFrom = () => {
  const raw = props.redirectTo || route.query.from || store.lastGatedPath || '/cases'
  const s = String(raw)
  if (!s.startsWith('/')) return { path: '/cases' }   // 防注入：只接受站内绝对路径
  const [p, qs] = s.split('?')
  // 只允许跳到最终栏目，避免“查看全部”误落在中间的小类卡片页。
  if (!LEVEL3_PATHS.includes(p)) return { path: '/cases' }
  if (!qs) return { path: p }
  const query = {}
  for (const [k, v] of new URLSearchParams(qs).entries()) query[k] = v
  return { path: p, query }
}

/** 各类型案件数：让用户选择前就看到规模，避免盲选 */
const loadCounts = async () => {
  const out = {}
  for (const o of options) {
    try {
      const data = await caseApi.page({ page: 1, size: 1, ...(o.scope ? { caseType: o.scope } : {}) })
      out[o.key] = data?.total ?? 0
    } catch (e) {
      out[o.key] = 0
    }
  }
  counts.value = out
}

onMounted(() => {
  current.value = store.current
  loadCounts()
})

defineExpose({ loadCounts })
</script>

<style>
/* 卡片样式已上移到 styles/index.css（cf-picker 系列，与「按类别浏览」板块页共用）。
   放组件内会按需注入——板块页直链进入时本组件未加载，样式缺失会裸奔（2026-10-08）。 */
</style>
