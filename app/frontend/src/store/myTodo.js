import { defineStore } from 'pinia'
import { todoApi } from '../api'

/**
 * 我的待办未完成数（侧栏「我的待办」红点 + 欢迎弹窗数据源）。
 *
 * 为什么要单独放一个 store：侧栏在 Layout.vue（整个会话只挂载一次），
 * 待办页在 MyTodos.vue。如果各自用局部 ref，在待办页标记完成后
 * 侧栏那份不会重取，红点会一直挂着 —— 必须让两处读同一份状态，
 * 并在任何会改变数量的操作后 refresh()。
 *
 * 与 pending.js（待审核注册申请）同一个模式，区别只在于数据源接口不同。
 */
export const useMyTodoStore = defineStore('myTodo', {
  state: () => ({
    /** 未完成待办数（不含已完成/已取消） */
    count: 0,
    /** 欢迎弹窗汇总：今日需完成 / 即将超期 / 已超期 / 新增领导意见 */
    summary: {
      todayTodoCount: 0,
      dueSoonCount: 0,
      overdueCount: 0,
      newOpinionCount: 0
    }
  }),
  actions: {
    /**
     * 重新取一次汇总。失败时归零，避免显示一个假的旧数字。
     * 一次请求同时喂红点和欢迎弹窗，不额外发第二个请求。
     */
    async refresh() {
      try {
        const s = await todoApi.welcomeSummary()
        this.summary = s || this.summary
        // 红点 = 未完成待办总数（pendingCount，不限期限）——与「我的待办」页
        // 的条目口径一致，点进去数字才能对上。旧口径 dueSoonCount（限 3 天内）
        // 会出现侧栏 3、列表 6 的"对不上"困惑；dueSoon 仍保留在 summary 里给欢迎弹窗用。
        this.count = (s?.pendingCount ?? s?.dueSoonCount ?? 0)
      } catch (e) {
        this.count = 0
        this.summary = {
          todayTodoCount: 0, dueSoonCount: 0, overdueCount: 0, newOpinionCount: 0
        }
      }
      return this.count
    },
    /** 退出登录时清掉，防止换人登录后看到上一个人的数字 */
    reset() {
      this.count = 0
      this.summary = {
        todayTodoCount: 0, dueSoonCount: 0, overdueCount: 0, newOpinionCount: 0
      }
    }
  }
})
