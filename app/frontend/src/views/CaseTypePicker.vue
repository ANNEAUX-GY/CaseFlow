<template>
  <div class="cf-picker">
    <div class="cf-picker__head">
      <div class="cf-picker__title">请选择案件类型</div>
      <div class="cf-picker__sub">
        选择后，本次浏览的案件列表、看板与统计都只显示该类案件；
        选定后顶部会出现类型条，需要换类型时点那条上的「退出类型」
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
      提示：切换侧边栏栏目不会丢失当前选择，只有主动「退出类型」才会重置
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CASE_TYPE_OPTIONS, GATED_PATHS, useCaseTypeStore } from '../store/caseType'
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

/** 选中类型：写状态 → 跳目标栏目 */
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
  router.push(target.query ? { path: target.path, query: target.query } : { path: target.path })
}

/** 解析 from 参数：还原成 { path, query }，非法值回退到 store 记住的栏目 */
const resolveFrom = () => {
  const raw = props.redirectTo || route.query.from || store.lastGatedPath || '/cases'
  const s = String(raw)
  if (!s.startsWith('/')) return { path: '/cases' }   // 防注入：只接受站内绝对路径
  const [p, qs] = s.split('?')
  // 只允许跳到受门控的栏目，防止 from 被构造成跳到任意路由
  if (!GATED_PATHS.some(g => p === g)) return { path: '/cases' }
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
      const data = await caseApi.page({ page: 1, size: 1, caseType: o.scope })
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
/* 2026-10-04 放大：原先容器 852px / 卡片 275×125 偏小，
   45~50 岁使用者要凑近看字。三档全放开：
   容器加宽到 1180px、卡片加高到 190px、字号统一上调 2px。 */
.cf-picker { padding: 40px 32px 28px; max-width: 1180px; margin: 0 auto }
.cf-picker__head { text-align: center; margin-bottom: 28px }
.cf-picker__title { font-size: 22px; font-weight: 600; color: #1b2430 }
.cf-picker__sub { font-size: 15px; color: #5a6472; margin-top: 10px; line-height: 1.75 }
.cf-picker__cards {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px;
}
.cf-picker__card {
  border: 1px solid #dfe4ea; border-radius: 8px; padding: 26px 22px;
  cursor: pointer; background: #fff; transition: all .15s;
  min-width: 0;
  /* 三卡片等高，长描述不会把某张卡顶矮 */
  display: flex; flex-direction: column;
}
.cf-picker__card:hover { border-color: #1b4a8c; background: #f7faff; transform: translateY(-2px) }
.cf-picker__card:focus-visible { outline: 2px solid #1b4a8c; outline-offset: 2px }
.cf-picker__card.is-active { border-color: #1b4a8c; background: #f2f6fc; box-shadow: 0 0 0 2px #1b4a8c inset }
.cf-picker__card-top { display: flex; align-items: center; gap: 10px; margin-bottom: 14px }
.cf-picker__badge {
  display: inline-block; padding: 5px 14px; border-radius: 4px;
  font-size: 15px; font-weight: 600; color: #fff;
}
.cf-picker__badge.is-danger { background: #c62a2a }
.cf-picker__badge.is-warning { background: #d98a0b }
.cf-picker__badge.is-info { background: #5a6472 }
.cf-picker__ok { color: #1b4a8c; font-size: 20px; margin-left: auto }
.cf-picker__desc { font-size: 14px; color: #5a6472; line-height: 1.75; flex: 1 }
/* 案件数是选择前就要看的信息，给足视觉权重并与描述拉开距离 */
.cf-picker__count {
  font-size: 14px; color: #1b2430; margin-top: 16px; padding-top: 12px;
  border-top: 1px dashed #e4e8ee; font-weight: 600;
}
.cf-picker__foot { text-align: center; font-size: 13px; margin-top: 24px }
</style>
