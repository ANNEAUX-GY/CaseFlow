<template>
  <!-- 信箱铃铛 + 未读角标。悬浮/点击弹出下拉面板（邮件式）。
       挂在顶栏，管理层与普通用户都显示；未读数实时随 SSE 增减。 -->
  <el-popover
    placement="bottom-end"
    :width="380"
    trigger="click"
    popper-class="cf-notif-popper"
    @show="onShow"
    @hide="onHide"
  >
    <template #reference>
      <button type="button" class="cf-notif__bell" aria-label="信箱">
        <el-icon :size="19"><Bell /></el-icon>
        <span v-if="unreadCount > 0" class="cf-notif__badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
      </button>
    </template>

    <div class="cf-notif">
      <div class="cf-notif__head">
        <span class="cf-notif__title">信箱</span>
        <span class="cf-muted">未读 {{ unreadCount }} 条</span>
        <span class="cf-spacer"></span>
        <el-button v-if="list.length" link type="primary" :loading="markingAll" @click="markAll">
          全部已读
        </el-button>
      </div>

      <transition-group name="cf-notif__fade" tag="ul" class="cf-notif__list" v-if="list.length">
        <li v-for="n in list" :key="n.id" class="cf-notif__item"
          :class="'is-' + (n.type || 'OTHER').toLowerCase()" @click="read(n)">
          <span class="cf-notif__dot" aria-hidden="true"></span>
          <div class="cf-notif__body">
            <div class="cf-notif__title-row">
              <span class="cf-notif__item-title">{{ n.title }}</span>
              <span class="cf-notif__type">{{ typeLabel(n.type) }}</span>
            </div>
            <div class="cf-notif__content">{{ n.content }}</div>
            <div class="cf-notif__time">{{ fmt(n.createdAt) }}</div>
          </div>
        </li>
      </transition-group>

      <div v-else class="cf-notif__empty">
        <el-icon :size="26" color="#c8a45c"><Bell /></el-icon>
        <div class="cf-notif__empty-title">暂无新消息</div>
        <div class="cf-muted">与自己有关的操作变更会出现在这里</div>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
/**
 * 统一信箱（2026-10-04）：顶栏铃铛 + 下拉面板。
 *
 * <p>数据来自后端 /notifications/unread（按当前登录人收敛），未读数走 SSE 实时增。
 * 点一条 = 标已读并从列表移除（邮件式，复用意见收件箱的交互约定）。
 *
 * <p>管理层视角：全站所有用户的操作（除自己）；普通用户视角：本人承办案件相关。
 * 区分逻辑全在后端，前端无感知。
 */
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import { notificationApi } from '../api'
import { useEventStore } from '../store/events'
import { useUserStore } from '../store/user'

const eventStore = useEventStore()
const userStore = useUserStore()
const myUserId = computed(() => userStore.userInfo?.userId || null)

const list = ref([])
const unreadCount = ref(0)
const markingAll = ref(false)
let unsubscribe = null

const fmt = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '-')

const typeLabel = (t) => ({
  OPINION: '意见', ASSIGN: '指派', STATUS: '状态',
  TODO: '待办', QUESTION: '疑问', FILE: '文件', OTHER: '其他'
}[t] || '其他')

const refresh = async () => {
  try {
    const [items, cnt] = await Promise.all([
      notificationApi.unread(),
      notificationApi.unreadCount()
    ])
    list.value = items || []
    unreadCount.value = cnt || 0
  } catch (e) {
    /* 网络异常保持现状 */
  }
}

/** 打开下拉时刷新（可能期间又来了新通知） */
const onShow = () => refresh()

/** 点一条 = 标已读 + 从列表移除 */
const read = async (n) => {
  try {
    await notificationApi.markRead(n.id)
  } catch (e) {
    /* 标记失败不打断，下次打开仍在 */
  }
  list.value = list.value.filter((x) => x.id !== n.id)
  unreadCount.value = Math.max(0, unreadCount.value - 1)
}

const markAll = async () => {
  markingAll.value = true
  try {
    await notificationApi.markAllRead()
    list.value = []
    unreadCount.value = 0
    ElMessage.success('已全部标为已读')
  } finally {
    markingAll.value = false
  }
}

const onHide = () => {
  // 关闭后刷新未读数（可能有点开未读的）
  refresh()
}

// SSE：收到发给我的定向通知（userId 对得上）→ 未读 +1
const onEvent = (e) => {
  if (e.kind !== 'notification') return
  const me = myUserId.value
  if (!me || Number(e.userId) !== Number(me)) return
  unreadCount.value += 1
  // 若下拉正开着，直接插到列表头
  if (list.value.length || document.querySelector('.cf-notif__list')) {
    list.value = [{
      id: e.id, caseId: e.caseId, type: e.type,
      title: e.title, content: e.content, createdAt: e.createdAt
    }, ...list.value.filter((x) => x.id !== e.id)]
  }
}

onMounted(() => {
  refresh()
  unsubscribe = eventStore.subscribe(onEvent)
})
onBeforeUnmount(() => {
  if (unsubscribe) unsubscribe()
})
</script>

<style>
/* 铃铛 + 角标（顶栏） */
.cf-notif__bell {
  position: relative; display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; border: none; background: transparent;
  color: #5a6472; cursor: pointer; border-radius: 6px;
}
.cf-notif__bell:hover { background: #f2f6fc; color: #1b4a8c }
.cf-notif__badge {
  position: absolute; top: 0; right: -2px; min-width: 16px; height: 16px;
  padding: 0 4px; border-radius: 8px; background: #c62a2a; color: #fff;
  font-size: 11px; line-height: 16px; text-align: center; font-weight: 600;
}
/* 下拉面板 */
.cf-notif__head { display: flex; align-items: center; gap: 10px; padding: 2px 2px 10px; font-size: 13px; color: #5a6472 }
.cf-notif__title { font-weight: 600; color: #1b2430; font-size: 15px }
.cf-notif__list {
  list-style: none; margin: 0; padding: 0;
  max-height: 60vh; overflow-y: auto;
  border: 1px solid #eef1f5; border-radius: 5px;
}
.cf-notif__item {
  display: flex; gap: 10px; padding: 11px 12px;
  border-bottom: 1px solid #eef1f5; cursor: pointer; transition: background .15s;
}
.cf-notif__item:last-child { border-bottom: none }
.cf-notif__item:hover { background: #f7faff }
.cf-notif__dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: #1b4a8c; margin-top: 5px }
.cf-notif__item.is-opinion .cf-notif__dot { background: #c62a2a }
.cf-notif__item.is-assign .cf-notif__dot { background: #1b4a8c }
.cf-notif__item.is-status .cf-notif__dot { background: #d98a0b }
.cf-notif__item.is-todo .cf-notif__dot { background: #1e8e58 }
.cf-notif__item.is-question .cf-notif__dot { background: #8a5cd6 }
.cf-notif__body { min-width: 0; flex: 1 }
.cf-notif__title-row { display: flex; align-items: center; gap: 8px }
.cf-notif__item-title { font-weight: 600; color: #1b2430; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.cf-notif__type { flex: none; font-size: 11px; color: #8a929e; background: #f2f4f7; padding: 0 6px; border-radius: 3px }
.cf-notif__content { margin-top: 3px; font-size: 13px; color: #3d4653; line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden }
.cf-notif__time { margin-top: 4px; font-size: 12px; color: #b9c0ca }
.cf-notif__empty { text-align: center; padding: 28px 0 20px }
.cf-notif__empty-title { font-size: 14px; font-weight: 600; color: #1b2430; margin: 8px 0 4px }
/* 已读移除淡出 */
.cf-notif__fade-leave-active { transition: all .3s ease }
.cf-notif__fade-leave-to { opacity: 0; transform: translateX(20px) }
.cf-notif__fade-move { transition: transform .3s ease }
</style>
