<template>
  <el-dialog
    v-model="visible"
    :title="`欢迎回来，${userName}`"
    :width="isMobile ? '94%' : '520px'"
    align-center
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="true"
    @closed="onClosed"
  >
    <div class="cf-welcome__date">{{ today }}</div>

    <!-- 汇总卡片：每个都可点击跳转，带上默认筛选条件 -->
    <div class="cf-welcome__grid">
      <div
        v-for="c in cards" :key="c.key"
        class="cf-welcome__card"
        :class="['is-' + c.tone, { 'is-zero': c.value === 0, 'is-clickable': c.value > 0 }]"
        role="button" tabindex="0"
        @click="go(c)" @keyup.enter="go(c)"
      >
        <div class="cf-welcome__num">{{ c.value }}</div>
        <div class="cf-welcome__label">{{ c.label }}</div>
        <div class="cf-welcome__hint">{{ c.hint }}</div>
      </div>
    </div>

    <!-- 空状态：不是"0 数字"就完事，得说清为什么 -->
    <div v-if="isAllZero" class="cf-welcome__empty">
      <div class="cf-welcome__empty-title">今天没有待处理事项</div>
      <div class="cf-muted">
        领导在案件盯办中提出意见后会自动生成待办；<br />
        有紧急任务时会在这里提醒你
      </div>
    </div>

    <template #footer>
      <span class="cf-welcome__tip cf-muted">数据截至{{ nowText }}</span>
      <span class="cf-spacer"></span>
      <el-button type="primary" @click="goMyTodos">进入我的待办</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMyTodoStore } from '../store/myTodo'
import { useUserStore } from '../store/user'
import { useDevice } from '../utils/device'

/**
 * 登录欢迎弹窗（普通民警端）。
 *
 * <p><b>触发时机</b>：每次登录成功后在落地页渲染完成时弹出——
 * 由 Login.vue 在 router.push 之后调用 show()，**每次登录必现**（需求要求），
 * 不做"当天只弹一次"的记忆。用户主动关闭或点卡片跳转即视为已读。
 *
 * <p><b>数据来源</b>：GET /todos/welcome-summary，四个数字全部按当前登录人收敛
 * （后端用 myVisibleCaseIds 限定到本人承办/协办的案件）。
 *
 * <p><b>跳转</b>：点卡片跳到我的待办并带上默认筛选条件，
 * 例如「即将超期」跳过去自动只看 PENDING + 截止临近。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'closed'])

const router = useRouter()
const userStore = useUserStore()
const myTodoStore = useMyTodoStore()
const { isMobile } = useDevice()

const visible = ref(props.modelValue)
const summary = ref({
  todayTodoCount: 0,
  dueSoonCount: 0,
  overdueCount: 0,
  newOpinionCount: 0
})
const nowText = ref('')

const today = computed(() => {
  const d = new Date()
  const week = '日一二三四五六'[d.getDay()]
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 星期${week}`
})

const userName = computed(() =>
  userStore.userInfo?.displayName || userStore.userInfo?.username || '')

const pad = (n) => String(n).padStart(2, '0')
const nowStr = () => {
  const d = new Date()
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const isAllZero = computed(() =>
  cards.value.every(c => c.value === 0))

/**
 * 四张卡片。jump 里带的是**跳转后的默认筛选条件**，
 * 让用户点"即将超期"就落在已经筛好的列表上，而不是自己再点一次筛选。
 */
const cards = computed(() => [
  {
    key: 'today',
    label: '今日需完成',
    value: summary.value.todayTodoCount || 0,
    tone: 'primary',
    hint: '今天到期',
    jump: { path: '/my-todos', query: { status: 'PENDING', sortBy: 'urgency,importance,deadline' } }
  },
  {
    key: 'dueSoon',
    label: '即将超期',
    value: summary.value.dueSoonCount || 0,
    tone: 'warn',
    hint: '3 天内到期或已超期',
    jump: { path: '/my-todos', query: { status: 'PENDING', sortBy: 'deadline' } }
  },
  {
    key: 'overdue',
    label: '已超期',
    value: summary.value.overdueCount || 0,
    tone: 'danger',
    hint: '需立即处理',
    jump: { path: '/my-todos', query: { status: 'PENDING', sortBy: 'deadline' } }
  },
  {
    key: 'opinion',
    label: '新增领导意见',
    value: summary.value.newOpinionCount || 0,
    tone: 'info',
    hint: '待你反馈落实',
    jump: { path: '/my-cases', query: {} }
  }
])

/** 拉汇总数据（弹窗打开时调用，保证数字是最新的） */
const loadSummary = async () => {
  nowText.value = nowStr()
  // 走 store：Layout 挂载时已取过一次，这里复用同一份，不再发第二个请求
  await myTodoStore.refresh()
  summary.value = myTodoStore.summary
}

const show = async () => {
  visible.value = true
  await loadSummary()
}

const go = (c) => {
  if (c.value === 0) {
    // 0 条时不给跳转——点了没反应会让人以为界面卡住
    visible.value = false
    return
  }
  visible.value = false
  router.push(c.jump)
}

const goMyTodos = () => {
  visible.value = false
  router.push({ path: '/my-todos' })
}

const onClosed = () => emit('closed')

// 与父组件双向同步（父组件可能从别处控制显隐）
watch(() => props.modelValue, (v) => { visible.value = v })
watch(visible, (v) => emit('update:modelValue', v))

defineExpose({ show, loadSummary })
</script>

<style>
.cf-welcome__date { font-size: 13px; color: #8a929e; margin-bottom: 14px }
.cf-welcome__grid {
  display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px;
}
.cf-welcome__card {
  border: 1px solid #dfe4ea; border-radius: 5px; padding: 14px 12px;
  text-align: center; background: #fff; transition: all .15s;
  border-left-width: 4px;
}
.cf-welcome__card.is-clickable { cursor: pointer }
.cf-welcome__card.is-clickable:hover { transform: translateY(-1px); box-shadow: 0 2px 8px rgba(27, 74, 140, .12) }
.cf-welcome__card:focus-visible { outline: 2px solid #1b4a8c; outline-offset: 2px }
/* 0 条时降低视觉权重，不制造"欠着工作"的错觉 */
.cf-welcome__card.is-zero { opacity: .55 }
.cf-welcome__card.is-primary { border-left-color: #1b4a8c }
.cf-welcome__card.is-warn { border-left-color: #d98a0b }
.cf-welcome__card.is-danger { border-left-color: #c62a2a }
.cf-welcome__card.is-info { border-left-color: #5a6472 }
.cf-welcome__num { font-size: 26px; font-weight: 700; color: #1b2430; line-height: 1.1 }
.cf-welcome__label { font-size: 13px; color: #1b2430; margin-top: 4px }
.cf-welcome__hint { font-size: 11px; color: #8a929e; margin-top: 2px }
.cf-welcome__empty {
  margin-top: 16px; padding: 18px 12px; text-align: center;
  background: #f7f9fc; border-radius: 5px; line-height: 1.8;
}
.cf-welcome__empty-title { font-size: 14px; color: #3d4654; margin-bottom: 4px }
.cf-welcome__tip { font-size: 12px; margin-right: 10px }

@media (max-width: 768px) {
  .cf-welcome__grid { grid-template-columns: 1fr 1fr; gap: 8px }
  .cf-welcome__num { font-size: 22px }
}
</style>
