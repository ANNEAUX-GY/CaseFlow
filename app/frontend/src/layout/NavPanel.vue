<template>
  <div class="cf-nav">
    <div class="cf-logo">
      <svg viewBox="0 0 24 24" width="30" height="30" aria-hidden="true">
        <path d="M12 2l8 3v7c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V5l8-3z" fill="#1b4a8c" stroke="#c8a45c" stroke-width="1.4" />
        <path d="M12 7l1.6 3.3 3.6.5-2.6 2.6.6 3.6L12 15.6 8.8 17l.6-3.6L6.8 10.8l3.6-.5L12 7z" fill="#c8a45c" />
      </svg>
      <span>
        案件指派系统
        <span class="cf-logo__sub">POLICE CASE FLOW</span>
      </span>
    </div>

    <el-menu :default-active="activePath" router :collapse="false" class="cf-nav__menu" @select="onSelect">
      <!-- 侧栏三档（判据见 store/user.js 的 navTier）：
           ① 普通民警：只有「我的案件 / 我的待办 / 到期提醒」，内容都只与本人民下案件相关；
           ② 业务领导（所长 / 副所长 / 法制员）：全所业务栏目（工作台、盯办、待办总览、案件管理、到期提醒），
              **不含**员工图谱 / 类别管理 / 账号管理——这三项属于系统运维，只留给系统管理员；
           ③ 系统管理员：业务栏目 + 系统管理类三项。 -->
      <template v-if="userStore.navTier === 'STAFF'">
        <el-menu-item index="/my-cases">
          <el-icon><Folder /></el-icon>
          <span>我的案件</span>
        </el-menu-item>
        <el-menu-item index="/my-todos">
          <el-icon><List /></el-icon>
          <span>我的待办</span>
          <!-- 待办角标：待办未完成数（含即将超期），一眼提醒还有多少事要做 -->
          <span v-if="pendingTodoCount > 0" class="cf-nav-badge">{{ pendingTodoCount }}</span>
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

        <!-- 系统管理类栏目：只有系统管理员看得到（业务领导侧栏不出现这三项）。
             路由守卫对同样三个路径做了兜底，手敲地址会被送回落地页。 -->
        <template v-if="userStore.canSystemManage">
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
      </template>
    </el-menu>

    <div class="cf-aside__foot">
      <span>CaseFlow v1.0</span>
      <span>案件管理中心</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Odometer, Tickets, AlarmClock, Connection, View, UserFilled, Collection, List, Folder } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'
import { usePendingStore } from '../store/pending'
import { useCaseTypeStore } from '../store/caseType'
import { useMyTodoStore } from '../store/myTodo'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const pendingStore = usePendingStore()
const myTodoStore = useMyTodoStore()
const pendingTodoCount = computed(() => myTodoStore.count)
const caseTypeStore = useCaseTypeStore()

const pendingCount = computed(() => pendingStore.count)
const activePath = computed(() => '/' + (route.path.split('/')[1] || 'dashboard'))

/**
 * 「案件类型门控」的拦截：el-menu 开了 router 属性会**自动跳转**，
 * 这里在跳转发生前先判断路径是否受门控：
 * <ul>
 *   <li>已选过类型 → 正常进入（切栏目不重置选择，符合需求4）；</li>
 *   <li>未选类型 → 先跳类型选择器，并把目标栏目带上，
 *       选完直接回到用户本来想去的那个栏目。</li>
 * </ul>
 * 守卫里也有一道（防手敲地址刷新），这里是第一道，能避免"先闪一下再被弹回"。
 */
const onSelect = (indexPath) => {
  if (!caseTypeStore.isGated(indexPath)) return
  caseTypeStore.rememberPath(indexPath)
  if (!caseTypeStore.selected) {
    router.push({ path: '/case-type', query: { from: indexPath } })
  }
}
</script>
