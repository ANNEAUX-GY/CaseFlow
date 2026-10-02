import { defineStore } from 'pinia'
import { authApi } from '../api'

/**
 * 拥有全部权限的角色：所长 / 副所长 / 法制员 / 系统管理员。
 * 与后端 com.caseflow.security.Roles#isFullAccess 保持一致。
 * 前端只用它控制按钮显隐（体验层），真正的拦截在后端 —— 前端隐藏只是避免误点。
 */
const FULL_ACCESS_ROLES = ['BOSS', 'CHIEF', 'DEPUTY_CHIEF', 'LAW_OFFICER']

/** 供路由守卫做「整页级」权限判断（守卫里拿不到 Pinia 实例，只能读 localStorage） */
export const isFullAccessRole = (role) => FULL_ACCESS_ROLES.includes(role)

const ROLE_NAMES = {
  CHIEF: '所长',
  DEPUTY_CHIEF: '副所长',
  LAW_OFFICER: '法制员',
  STAFF: '普通民警',
  BOSS: '系统管理员'
}

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('cf_token') || '',
    userInfo: JSON.parse(localStorage.getItem('cf_user') || 'null'),
    dict: null
  }),
  getters: {
    /** 是否拥有指派、员工维护、账号管理、撤回等高级权限 */
    isFullAccess: (state) => FULL_ACCESS_ROLES.includes(state.userInfo?.role),
    /** 角色中文名 */
    roleName: (state) => state.userInfo?.roleName || ROLE_NAMES[state.userInfo?.role] || ''
  },
  actions: {
    async login(username, password) {
      const data = await authApi.login({ username, password })
      this.token = data.token
      this.userInfo = {
        userId: data.userId,
        username: data.username,
        displayName: data.displayName,
        role: data.role,
        roleName: data.roleName,
        employeeId: data.employeeId,
        fullAccess: data.fullAccess
      }
      localStorage.setItem('cf_token', this.token)
      localStorage.setItem('cf_user', JSON.stringify(this.userInfo))
      return data
    },
    async logout() {
      try {
        await authApi.logout()
      } catch (e) {
        // 忽略
      }
      this.token = ''
      this.userInfo = null
      localStorage.removeItem('cf_token')
      localStorage.removeItem('cf_user')
    },
    async loadDict() {
      if (!this.dict) {
        this.dict = await authApi.dict()
      }
      return this.dict
    }
  }
})
