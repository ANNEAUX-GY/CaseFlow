import { defineStore } from 'pinia'
import { markRaw } from 'vue'

/**
 * 全局事件流（SSE）：登录后建立一条长连接，后端任何写操作都会实时广播到这里。
 * 组件通过 subscribe(fn) 订阅，收到事件后自行决定刷新哪块数据。
 *
 * 事件结构（后端 LogService 统一发布）：
 * { module, action, actionName, targetType, targetId, content, operatorName, createdAt }
 */
const listeners = new Set()
let failCount = 0

export const useEventStore = defineStore('events', {
  state: () => ({
    source: null,
    connected: false
  }),
  actions: {
    connect() {
      if (this.source) return
      const token = localStorage.getItem('cf_token')
      if (!token) return
      failCount = 0
      const base = (import.meta.env.VITE_API_BASE || '/api').replace(/\/$/, '')
      const es = new EventSource(base + '/events/stream?token=' + encodeURIComponent(token))
      es.onopen = () => { this.connected = true; failCount = 0 }
      // 后端以 event name "case-event" 推送 JSON
      es.addEventListener('case-event', (e) => {
        try {
          const data = JSON.parse(e.data)
          listeners.forEach((fn) => {
            try { fn(data) } catch (err) { /* 单个订阅者出错不影响其他 */ }
          })
        } catch (err) { /* 忽略坏帧 */ }
      })
      es.onerror = () => {
        this.connected = false
        // 连续失败超过 5 次停止重连（token 过期场景），重新登录后会 connect 新连接
        failCount++
        if (failCount > 5) { es.close(); this.source = null }
      }
      this.source = markRaw(es)
    },
    disconnect() {
      if (this.source) {
        this.source.onopen = null
        this.source.onerror = null
        this.source.close()
      }
      this.source = null
      this.connected = false
      failCount = 0
    },
    /** 订阅事件，返回取消订阅函数 */
    subscribe(fn) {
      listeners.add(fn)
      return () => listeners.delete(fn)
    }
  }
})
