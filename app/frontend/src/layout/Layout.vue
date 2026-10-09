<template>
  <el-container style="height: 100%">
    <!-- 常驻侧栏 -->
    <el-aside width="224px" class="cf-aside">
      <NavPanel />
    </el-aside>

    <el-container>
      <el-header class="cf-header">
        <div class="cf-header__title">
          <span class="cf-title">{{ pageTitle }}</span>
          <span class="cf-subtitle">CaseFlow</span>
        </div>

        <div class="cf-header__right">
          <span class="cf-header__date">{{ today }}</span>
          <!-- 统一信箱：管理层与普通用户都有，未读数实时随 SSE 增减 -->
          <NotificationBell />
          <span class="cf-header__user">
            {{ userStore.userInfo?.displayName || '' }}
            <span v-if="userStore.roleName" class="cf-header__role">{{ userStore.roleName }}</span>
          </span>
          <el-button link type="primary" class="cf-header__logout" @click="onLogout">退出</el-button>
        </div>
      </el-header>

      <!-- 当前案件类型条（统一入口门控，2026-10-04）：
           独立成第二行，不与顶栏的「退出」登录挤在一行——
           两个按钮都叫「退出」放同一行，45岁以上的用户极易误点。
           2026-10-09 三级浏览：条上同时显示所在层级，右侧按钮逐级退回
           （栏目页「返回类别」→ 小类页「返回大类」→ 大类页「退出类型」）。 -->
      <div v-if="caseTypeStore.selected" class="cf-ctypebar">
        <el-icon class="cf-ctypebar__icon"><Filter /></el-icon>
        <span class="cf-ctypebar__label">{{ levelLabel }}</span>
        <span class="cf-ctypebar__tag" :class="'is-' + (caseTypeStore.currentOption?.type || 'info')">
          {{ caseTypeStore.currentOption?.label }}
        </span>
        <template v-if="currentCategory">
          <span class="cf-ctypebar__sep">/</span>
          <span class="cf-ctypebar__tag is-info">{{ currentCategory }}</span>
        </template>
        <span class="cf-ctypebar__tip">筛选与统计均限定在此类型内</span>
        <span class="cf-spacer"></span>
        <el-button link type="warning" class="cf-ctypebar__exit" @click="onBackLevel">
          {{ backCtx.label }}
        </el-button>
      </div>

      <el-main class="cf-main">
        <router-view v-slot="{ Component }">
          <transition name="cf-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>

      <!-- 登录欢迎弹窗（仅普通民警，每次登录弹一次）。
           由 Login.vue 写入 sessionStorage 标记、这里消费——
           因为登录后 Login 组件立刻被销毁，弹窗不能挂在它上面。
           append-to-body 会把弹窗挂到 body，不受抽屉/侧栏容器影响。 -->
      <WelcomeDialog v-if="!userStore.isFullAccess" ref="welcomeRef" @open-inbox="onOpenInbox" />
      <!-- 意见收件箱：欢迎弹窗点「新增领导意见」在当前页直接打开（邮件式查看，已读即移除） -->
      <OpinionInboxDialog ref="inboxRef" />
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Filter } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'
import { usePendingStore } from '../store/pending'
import { useEventStore } from '../store/events'
import { useCaseTypeStore, boardsUrl, levelOf, PICKER_PATH } from '../store/caseType'
import { useMyTodoStore } from '../store/myTodo'
import NavPanel from './NavPanel.vue'
import WelcomeDialog from '../components/WelcomeDialog.vue'
import OpinionInboxDialog from '../components/OpinionInboxDialog.vue'
import NotificationBell from '../components/NotificationBell.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 待审核注册申请数：作为「账号管理」上的红点，系统管理员一进系统就能看到有人等审核。
// 放在 store 里而不是局部 ref —— 审批动作发生在账号管理页，两处必须读同一份数据，
// 否则审批通过后侧栏红点不会消失（Layout 整个会话只挂载一次，不会重新取数）。
const pendingStore = usePendingStore()
// 案件类型选择器（统一入口门控）：顶栏显示当前类型 + 退出按钮，也负责登出时清状态
const caseTypeStore = useCaseTypeStore()
// 我的待办计数（普通民警侧栏红点 + 欢迎弹窗数据源）
const myTodoStore = useMyTodoStore()

const loadPending = () => {
  // 红点是给「账号管理」栏目用的，而该栏目只有系统管理员有（2026-10-09 侧栏分档）
  // —— 领导/民警不必发这个请求，省一次无用调用。
  if (!userStore.canSystemManage) return
  pendingStore.refresh()
}

onMounted(() => {
  loadPending()
  // 登录后建立全局 SSE 事件流：任何案件操作实时广播（办理进度/最近操作自动刷新）
  eventStore.connect()
  // 普通民警：取一次我的待办计数（侧栏红点）
  if (!userStore.isFullAccess) myTodoStore.refresh()
  // 消费登录时写下的"待弹欢迎框"标记（仅普通民警）
  checkWelcome()
})

/**
 * 登录欢迎弹窗的触发点。
 *
 * <p>为什么不直接写在 Login.vue：登录成功后 router.push 会立刻销毁 Login 组件，
 * 挂在它上面的弹窗会跟着一起没了。改由 Login 写 sessionStorage 标记、
 * Layout 挂载时消费——Layout 是整个会话只挂载一次的外壳，弹窗挂在它上面才稳。
 */
const welcomeRef = ref(null)
const inboxRef = ref(null)
/** 欢迎弹窗点「新增领导意见」→ 当前页直接打开收件箱（邮件式，不跳页） */
const onOpenInbox = () => inboxRef.value?.open()
const checkWelcome = () => {
  if (userStore.isFullAccess) return
  let pending = false
  try {
    pending = sessionStorage.getItem('cf_welcome_pending') === '1'
    if (pending) sessionStorage.removeItem('cf_welcome_pending')
  } catch (e) {
    return
  }
  if (!pending) return
  // 等首屏渲染完再弹，避免和页面入场动画抢焦点
  setTimeout(() => welcomeRef.value?.show(), 400)
}

const eventStore = useEventStore()
onBeforeUnmount(() => eventStore.disconnect())

// 每次切页都重新取一次：既能兜住审批后的刷新，也能看到别人（另一台电脑）刚提交的申请
watch(() => route.path, loadPending)

const pageTitle = computed(() => route.meta.title || '')

const today = computed(() => {
  const d = new Date()
  const week = '日一二三四五六'[d.getDay()]
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 星期${week}`
})

const onLogout = async () => {
  await userStore.logout()
  pendingStore.reset()
  myTodoStore.reset()
  // 退出登录必须连案件类型一起清：否则下一个登录的人会继承上一个人的类型选择
  caseTypeStore.reset()
  router.push('/login')
}

/**
 * 退出「案件类型选择」（不是退出登录）。
 * 清状态后回类型选择器；用 lastGatedPath 记住的栏目作为选完后的落点，
 * 这样"退出 → 重选"的过程是连贯的，不会莫名其妙跳到工作台。
 */
const onExitCaseType = () => {
  const back = caseTypeStore.lastGatedPath || '/cases'
  caseTypeStore.exit()
  router.push({ path: '/case-type', query: { from: back } })
}

// ---- 三级浏览的逐级退回（2026-10-09）----
// 层级：1=大类卡片页，2=小类卡片页，3=栏目页。按钮语义随所在层级自动变，
// 点一次退一级，正好对应"先退小类再退大类"。
const level = computed(() => levelOf(route.path))

/** 类型条左侧的层级提示语 */
const levelLabel = computed(() => {
  if (level.value === 1) return '选择案件大类'
  if (level.value === 2) return '选择小类'
  return '当前案件类型'
})

/** 栏目页若带了小类筛选，条上把它一并显示出来（否则用户不知道now在看哪一小类） */
const currentCategory = computed(() => {
  const c = String(route.query.category || '')
  if (!c) return ''
  return c === 'NONE' ? '未分类' : c
})

/** 返回按钮文案：按层级决定 */
const backCtx = computed(() => {
  if (level.value === 1) return { label: '退出类型', kind: 'exit' }
  if (level.value === 2) return { label: '返回大类', kind: 'upType' }
  return { label: '返回类别', kind: 'upCategory' }
})

/**
 * 逐级退回。
 *
 * <p>关键点：往上一级退时要把「当前栏目」作为落点带走——
 * 在第 2 级重新选完小类后，用户应该回到原来的栏目（如案件盯办），
 * 而不是被一律送回案件管理。所以上退时把 route 剥掉 category 再包成 to。
 */
const onBackLevel = () => {
  const kind = backCtx.value.kind
  if (kind === 'exit') {
    onExitCaseType()
    return
  }
  if (kind === 'upType') {
    // 回大类页：带上 from，选完大类后能回到原来那个栏目
    router.push({ path: PICKER_PATH, query: { from: caseTypeStore.lastGatedPath || '/cases' } })
    return
  }
  // upCategory：栏目页 → 小类页。剥掉小类筛选与分页，避免把旧筛选带进新一次选择
  const q = { ...route.query }
  delete q.category
  delete q.page
  const qs = new URLSearchParams(q).toString()
  router.push(boardsUrl(qs ? `${route.path}?${qs}` : route.path))
}
</script>
