<template>
  <el-container style="height: 100%">
    <!-- 电脑端：常驻侧栏 -->
    <el-aside v-if="!isMobile" width="224px" class="cf-aside">
      <NavPanel variant="aside" />
    </el-aside>

    <!-- 手机端：同一套导航收进左侧抽屉，点汉堡展开；选完自动收起 -->
    <el-drawer
      v-if="isMobile"
      v-model="navOpen"
      direction="ltr"
      size="278px"
      :with-header="false"
      class="cf-nav-drawer"
      :show-close="false"
    >
      <NavPanel
        variant="drawer"
        @navigate="navOpen = false"
        @close="navOpen = false"
        @logout="onLogout"
      />
    </el-drawer>

    <el-container>
      <el-header class="cf-header">
        <button
          v-if="isMobile"
          type="button"
          class="cf-burger"
          aria-label="打开导航"
          @click="navOpen = true"
        >
          <el-icon><Fold /></el-icon>
        </button>

        <div class="cf-header__title">
          <span class="cf-title">{{ pageTitle }}</span>
          <span v-if="!isMobile" class="cf-subtitle">CaseFlow</span>
        </div>

        <div class="cf-header__right">
          <span class="cf-header__date">{{ today }}</span>
          <span class="cf-header__user">
            {{ userStore.userInfo?.displayName || '' }}
            <span v-if="userStore.roleName" class="cf-header__role">{{ userStore.roleName }}</span>
          </span>
          <!-- 版式标识：一眼看出系统把当前设备识别成了什么，点一下可手动切换 -->
          <button
            v-if="showDeviceChip"
            type="button"
            class="cf-device-chip"
            @click="toggleLayout"
          >
            <el-icon><component :is="isMobile ? Cellphone : Monitor" /></el-icon>
            {{ isMobile ? '手机版' : '电脑版' }}
          </button>
          <el-button v-if="!isMobile" link type="primary" class="cf-header__logout" @click="onLogout">退出</el-button>
        </div>
      </el-header>

      <!-- 当前案件类型条（统一入口门控，2026-10-04）：
           独立成第二行，不与顶栏的「退出」登录挤在一行——
           两个按钮都叫「退出」放同一行，45岁以上的用户极易误点。
           这里显示"正在看哪一类案件"，右侧给出换类型的出口。 -->
      <div v-if="caseTypeStore.selected" class="cf-ctypebar">
        <el-icon class="cf-ctypebar__icon"><Filter /></el-icon>
        <span class="cf-ctypebar__label">当前案件类型</span>
        <span class="cf-ctypebar__tag" :class="'is-' + (caseTypeStore.currentOption?.type || 'info')">
          {{ caseTypeStore.currentOption?.label }}
        </span>
        <span class="cf-ctypebar__tip">筛选与统计均限定在此类型内</span>
        <span class="cf-spacer"></span>
        <el-button link type="warning" class="cf-ctypebar__exit" @click="onExitCaseType">
          退出类型
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
import { Fold, Cellphone, Monitor, Filter } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'
import { usePendingStore } from '../store/pending'
import { useEventStore } from '../store/events'
import { useCaseTypeStore } from '../store/caseType'
import { useMyTodoStore } from '../store/myTodo'
import { useDevice } from '../utils/device'
import NavPanel from './NavPanel.vue'
import WelcomeDialog from '../components/WelcomeDialog.vue'
import OpinionInboxDialog from '../components/OpinionInboxDialog.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 手机端抽屉开关；切到电脑端时顺手关掉，避免抽屉在桌面布局里残留
const navOpen = ref(false)
const { isMobile, mode, setDeviceMode, deviceKindLabel } = useDevice()
watch(isMobile, (v) => { if (!v) navOpen.value = false })
watch(() => route.path, () => { navOpen.value = false })

// 电脑端且未手动指定版式时不显示标识，保持顶栏干净
const showDeviceChip = computed(() => isMobile.value || mode.value !== 'auto')
const toggleLayout = () => setDeviceMode(isMobile.value ? 'desktop' : 'mobile')

// 待审核注册申请数：作为「账号管理」上的红点，所长/法制员一进系统就能看到有人等审核。
// 放在 store 里而不是局部 ref —— 审批动作发生在账号管理页，两处必须读同一份数据，
// 否则审批通过后侧栏红点不会消失（Layout 整个会话只挂载一次，不会重新取数）。
const pendingStore = usePendingStore()
// 案件类型选择器（统一入口门控）：顶栏显示当前类型 + 退出按钮，也负责登出时清状态
const caseTypeStore = useCaseTypeStore()
// 我的待办计数（普通民警侧栏红点 + 欢迎弹窗数据源）
const myTodoStore = useMyTodoStore()

const loadPending = () => {
  if (!userStore.isFullAccess) return
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
  navOpen.value = false
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
</script>
