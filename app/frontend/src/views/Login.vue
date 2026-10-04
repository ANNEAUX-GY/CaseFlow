<template>
  <div class="cf-login">
    <div class="cf-login__emblem">
      <svg viewBox="0 0 24 24" width="52" height="52" aria-hidden="true">
        <path d="M12 2l8 3v7c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V5l8-3z" fill="#1b4a8c" stroke="#c8a45c" stroke-width="1.2" />
        <path d="M12 7l1.6 3.3 3.6.5-2.6 2.6.6 3.6L12 15.6 8.8 17l.6-3.6L6.8 10.8l3.6-.5L12 7z" fill="#c8a45c" />
      </svg>
    </div>
    <div class="cf-login__brand">
      <h1>案件指派系统</h1>
      <p>PUBLIC SECURITY CASE ASSIGNMENT</p>
    </div>
    <div class="cf-login__box">
      <h2 class="cf-login__title">登录</h2>
      <el-form :model="form" size="large" @keyup.enter="onLogin">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名 / 手机号" autofocus />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="onLogin">登 录</el-button>
      </el-form>
      <div class="cf-login__more">
        <span>还没有账号？</span>
        <el-button link type="primary" @click="router.push('/register')">注册账号</el-button>
        <span class="cf-login__more-tip">注册后需所长或法制员审核通过</span>
      </div>
    </div>

    <div class="cf-login__foot">
      <span class="cf-login__rule" />
      <span class="cf-login__slogan">{{ slogan.text }}</span>
      <span class="cf-login__rule" />
    </div>
    <div class="cf-login__copyright">{{ FOOTER_BRAND.name }} · {{ FOOTER_BRAND.version }}</div>
  </div>
</template>

<script setup>
import { reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { homePathOf } from '../router'
import { useUserStore } from '../store/user'
import { ElMessage } from 'element-plus'
import { SLOGANS, FOOTER_BRAND } from '../config/slogans'

const slogan = computed(() => SLOGANS[new Date().getDate() % SLOGANS.length])

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const router = useRouter()
const userStore = useUserStore()

const onLogin = async () => {
  const account = (form.username || '').trim()
  if (!account || !form.password) {
    ElMessage.warning('请输入用户名或手机号，以及密码')
    return
  }
  loading.value = true
  try {
    await userStore.login(account, form.password)
    // 按角色决定落地页：管理层进工作台，普通民警进「我的案件」
    router.push(homePathOf(userStore.userInfo?.role))
    // 普通民警登录后弹欢迎汇总框（需求：每次登录都弹）。
    // 等待路由切换完成再由落地页触发——Login 组件随即被销毁，
    // 弹窗挂在它上面会一起没了，所以把"要弹窗"记在 sessionStorage，
    // 由 Layout 挂载时消费（见 Layout.vue 的 checkWelcome）。
    if (!userStore.isFullAccess) {
      try {
        sessionStorage.setItem('cf_welcome_pending', '1')
      } catch (e) { /* 隐私模式忽略 */ }
    }
  } catch (e) {
    // 错误提示已由 axios 拦截器统一弹出（含「等待审核」「密码错误」等后端原文），此处不再重复提示
  } finally {
    loading.value = false
  }
}
</script>
