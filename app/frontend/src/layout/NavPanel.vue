<template>
  <div class="cf-nav" :class="{ 'cf-nav--drawer': variant === 'drawer' }">
    <div class="cf-logo">
      <svg viewBox="0 0 24 24" width="30" height="30" aria-hidden="true">
        <path d="M12 2l8 3v7c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V5l8-3z" fill="#1b4a8c" stroke="#c8a45c" stroke-width="1.4" />
        <path d="M12 7l1.6 3.3 3.6.5-2.6 2.6.6 3.6L12 15.6 8.8 17l.6-3.6L6.8 10.8l3.6-.5L12 7z" fill="#c8a45c" />
      </svg>
      <span>
        案件指派系统
        <span class="cf-logo__sub">POLICE CASE FLOW</span>
      </span>
      <button v-if="variant === 'drawer'" type="button" class="cf-nav__close" aria-label="关闭导航" @click="emit('close')">
        <el-icon><Close /></el-icon>
      </button>
    </div>

    <el-menu :default-active="activePath" router :collapse="false" class="cf-nav__menu" @select="onSelect">
      <!-- 普通民警：只有「我的案件」和「到期提醒」两项，内容都只与本人民下案件相关。
           管理层才看得到工作台、案件管理、盯办、员工图谱等全所级功能。 -->
      <template v-if="!userStore.isFullAccess">
        <el-menu-item index="/my-cases">
          <el-icon><Folder /></el-icon>
          <span>我的案件</span>
        </el-menu-item>
        <el-menu-item index="/reminders">
          <el-icon><AlarmClock /></el-icon>
          <span>到期提醒</span>
        </el-menu-item>
      </template>

      <template v-else>
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>工作台</span>
        </el-menu-item>
        <el-menu-item index="/watch">
          <el-icon><View /></el-icon>
          <span>案件盯办</span>
        </el-menu-item>
        <el-menu-item index="/todos">
          <el-icon><List /></el-icon>
          <span>待办总览</span>
        </el-menu-item>
        <el-menu-item index="/cases">
          <el-icon><Tickets /></el-icon>
          <span>案件管理</span>
        </el-menu-item>
        <el-menu-item index="/reminders">
          <el-icon><AlarmClock /></el-icon>
          <span>到期提醒</span>
        </el-menu-item>
        <el-menu-item index="/org">
          <el-icon><Connection /></el-icon>
          <span>员工图谱</span>
        </el-menu-item>
        <el-menu-item index="/categories">
          <el-icon><Collection /></el-icon>
          <span>类别管理</span>
        </el-menu-item>
        <el-menu-item index="/users">
          <el-icon><UserFilled /></el-icon>
          <span>账号管理</span>
          <span v-if="pendingCount > 0" class="cf-nav-badge">{{ pendingCount }}</span>
        </el-menu-item>
      </template>
    </el-menu>

    <!-- 手机抽屉：把桌面顶栏里的用户信息、退出、版式切换都收到这里，
         顶栏才能瘦下来给内容让宽度 -->
    <div v-if="variant === 'drawer'" class="cf-nav__user">
      <div class="cf-nav__user-row">
        <span class="cf-nav__uname">{{ userStore.userInfo?.displayName || userStore.userInfo?.username || '' }}</span>
        <span v-if="userStore.roleName" class="cf-nav__urole">{{ userStore.roleName }}</span>
      </div>
      <div class="cf-nav__udate">{{ today }}</div>
      <button type="button" class="cf-nav__logout" @click="emit('logout')">退出登录</button>

      <div class="cf-nav__device">
        <div class="cf-nav__device-head">
          <span>当前版式：{{ layoutLabel }}</span>
          <span class="cf-nav__device-kind">识别为{{ deviceKindLabel }}网页</span>
        </div>
        <div class="cf-nav__modes">
          <button type="button" :class="{ 'is-active': mode !== 'desktop' }" @click="setDeviceMode('mobile')">手机版</button>
          <button type="button" :class="{ 'is-active': mode === 'desktop' }" @click="setDeviceMode('desktop')">电脑版</button>
        </div>
        <button v-if="mode !== 'auto'" type="button" class="cf-nav__auto" @click="setDeviceMode('auto')">恢复自动识别</button>
      </div>
    </div>

    <div v-else class="cf-aside__foot">
      <span>CaseFlow v1.0</span>
      <span>案件管理中心</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Odometer, Tickets, AlarmClock, Connection, View, UserFilled, Collection, Close, List, Folder } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'
import { usePendingStore } from '../store/pending'
import { useCaseTypeStore } from '../store/caseType'
import { useDevice } from '../utils/device'

const props = defineProps({
  /** aside = 桌面侧栏；drawer = 手机抽屉 */
  variant: { type: String, default: 'aside' }
})
const emit = defineEmits(['navigate', 'close', 'logout'])

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const pendingStore = usePendingStore()
const caseTypeStore = useCaseTypeStore()
const { mode, setDeviceMode, layoutLabel, deviceKindLabel } = useDevice()

const pendingCount = computed(() => pendingStore.count)
const activePath = computed(() => '/' + (route.path.split('/')[1] || 'dashboard'))

/**
 * 手机上点完导航要把抽屉收起来，否则挡住整屏。
 *
 * <p>同时承担「案件类型门控」的拦截：el-menu 开了 router 属性会**自动跳转**，
 * 这里在跳转发生前先判断路径是否受门控：
 * <ul>
 *   <li>已选过类型 → 正常进入（切栏目不重置选择，符合需求4）；</li>
 *   <li>未选类型 → 先跳类型选择器，并把目标栏目带上，
 *       选完直接回到用户本来想去的那个栏目。</li>
 * </ul>
 * 守卫里也有一道（防手敲地址刷新），这里是第一道，能避免"先闪一下再被弹回"。
 */
const onSelect = (indexPath) => {
  if (caseTypeStore.isGated(indexPath)) {
    caseTypeStore.rememberPath(indexPath)
    if (!caseTypeStore.selected) {
      router.push({ path: '/case-type', query: { from: indexPath } })
      if (props.variant === 'drawer') emit('navigate')
      return
    }
  }
  if (props.variant === 'drawer') emit('navigate')
}

const today = computed(() => {
  const d = new Date()
  const week = '日一二三四五六'[d.getDay()]
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 星期${week}`
})
</script>
