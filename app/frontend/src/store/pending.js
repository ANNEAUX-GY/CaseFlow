import { defineStore } from 'pinia'
import { userApi } from '../api'

/**
 * 待审核注册申请数（侧栏「账号管理」红点 + 账号管理页「待审核」角标）。
 *
 * 为什么单独放一个 store：侧栏在 Layout.vue（整个会话只挂载一次），
 * 账号管理页在 UserManage.vue。如果各自用局部 ref，审批通过后侧栏那份不会重取，
 * 红点就会一直挂着 —— 必须让两处读同一份状态，并在任何会改变数量的操作后刷新。
 */
export const usePendingStore = defineStore('pending', {
  state: () => ({
    count: 0
  }),
  actions: {
    /** 重新向后端要一次数量；失败时归零，避免显示一个假的旧红点 */
    async refresh() {
      try {
        const res = await userApi.pendingCount()
        this.count = res?.count || 0
      } catch (e) {
        this.count = 0
      }
      return this.count
    },
    /** 退出登录时清掉，防止换人登录后看到上一个人的红点 */
    reset() {
      this.count = 0
    }
  }
})
