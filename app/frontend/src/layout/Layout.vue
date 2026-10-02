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
            :title="`已识别为${deviceKindLabel}网页，点击切换版式`"
            @click="toggleLayout"
          >
            <el-icon><component :is="isMobile ? Cellphone : Monitor" /></el-icon>
            {{ isMobile ? '手机版' : '电脑版' }}
          </button>
          <el-button v-if="!isMobile" link type="primary" class="cf-header__logout" @click="onLogout">退出</el-button>
        </div>
      </el-header>

      <el-main class="cf-main">
        <router-view v-slot="{ Component }">
          <transition name="cf-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Fold, Cellphone, Monitor } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'
import { usePendingStore } from '../store/pending'
import { useEventStore } from '../store/events'
import { useDevice } from '../utils/device'
import NavPanel from './NavPanel.vue'

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

const loadPending = () => {
  if (!userStore.isFullAccess) return
  pendingStore.refresh()
}

onMounted(() => {
  loadPending()
  // 登录后建立全局 SSE 事件流：任何案件操作实时广播（办理进度/最近操作自动刷新）
  eventStore.connect()
})

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
  router.push('/login')
}
</script>
