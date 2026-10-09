import { defineStore } from 'pinia'
import { authApi } from '../api'

/**
 * 拥有全部权限的角色：所长 / 副所长 / 法制员 / 系统管理员。
 * 与后端 com.caseflow.security.Roles#isFullAccess 保持一致。
 * 前端只用它控制按钮显隐（体验层），真正的拦截在后端 —— 前端隐藏只是避免误点。
 */
const FULL_ACCESS_ROLES = ['BOSS', 'CHIEF', 'DEPUTY_CHIEF', 'LAW_OFFICER']

/**
 * 「系统管理类」栏目（员工图谱 / 类别管理 / 账号管理）的专属角色：只有系统管理员。
 *
 * <p>2026-10-09 需求：所长 / 副所长 / 法制员是**业务领导**——
 * 账号、组织架构、案件类别字典属于系统运维，不是办案业务，侧栏不出现这三项。
 * 要调整这条边界只改这一个数组：侧栏（NavPanel）与路由守卫（router）都读它，
 * 不会出现「菜单藏了但手敲地址还能进」的两套口径。
 */
const SYSTEM_ADMIN_ROLES = ['BOSS']

/** 供路由守卫做「整页级」权限判断（守卫里拿不到 Pinia 实例，只能读 localStorage） */
export const isFullAccessRole = (role) => FULL_ACCESS_ROLES.includes(role)

/** 是否系统管理员（唯一能看到系统管理类栏目的角色） */
export const isSystemAdminRole = (role) => SYSTEM_ADMIN_ROLES.includes(role)

/** 侧栏分档：STAFF 普通民警 / LEADER 业务领导 / ADMIN 系统管理员 */
export const NAV_TIER = { STAFF: 'STAFF', LEADER: 'LEADER', ADMIN: 'ADMIN' }

/**
 * 角色 → 侧栏分档。侧栏只有三套菜单（见 NavPanel.vue）：
 * <ul>
 *   <li>{@code STAFF} 普通民警：我的案件 / 我的待办 / 到期提醒（都只关乎本人）</li>
 *   <li>{@code LEADER} 业务领导（所长 / 副所长 / 法制员）：全所业务栏目
 *       —— 工作台、案件盯办、待办总览、案件管理、到期提醒</li>
 *   <li>{@code ADMIN} 系统管理员：业务栏目 + 员工图谱 / 类别管理 / 账号管理</li>
 * </ul>
 * 注意这只是**菜单分档**，不等于权限分档：领导对业务的写权限仍与管理员同级
 * （isFullAccess 不变），后端接口一行没动。
 */
export const navTierOf = (role) => {
  if (!isFullAccessRole(role)) return NAV_TIER.STAFF
  return isSystemAdminRole(role) ? NAV_TIER.ADMIN : NAV_TIER.LEADER
}

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
    /** 侧栏分档：STAFF / LEADER / ADMIN —— 侧栏三套菜单的唯一判据 */
    navTier: (state) => navTierOf(state.userInfo?.role),
    /** 是否看得到系统管理类栏目（员工图谱 / 类别管理 / 账号管理） */
    canSystemManage: (state) => isSystemAdminRole(state.userInfo?.role),
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
