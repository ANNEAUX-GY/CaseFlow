<template>
  <!-- 信箱铃铛 + 未读角标。悬浮/点击弹出下拉面板（邮件式）。
       挂在顶栏，管理层与普通用户都显示；未读数实时随 SSE 增减。
       2026-10-06：未读/已读双页签，已读历史保留在「已读」页签里可翻，
       点一条不再"从信箱消失"，而是从未读挪到已读。 -->
  <el-popover
    ref="popRef"
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
        <div class="cf-notif__tabs">
          <button type="button" class="cf-notif__tab" :class="{ 'is-active': box === 'unread' }" @click="box = 'unread'">
            未读 {{ unreadCount }}
          </button>
          <button type="button" class="cf-notif__tab" :class="{ 'is-active': box === 'read' }" @click="box = 'read'">
            已读 {{ readList.length }}
          </button>
        </div>
        <span class="cf-spacer"></span>
        <el-button v-if="box === 'unread' && unreadList.length" link type="primary" :loading="markingAll" @click="markAll">
          全部已读
        </el-button>
      </div>

      <transition-group name="cf-notif__fade" tag="ul" class="cf-notif__list" v-if="current.length">
        <li v-for="n in current" :key="n.id" class="cf-notif__item"
          :class="[isRead(n) ? 'is-read' : '', 'is-' + (n.type || 'OTHER').toLowerCase()]" @click="read(n)">
          <span class="cf-notif__dot" aria-hidden="true"></span>
          <div class="cf-notif__body">
            <div class="cf-notif__title-row">
              <span class="cf-notif__item-title">{{ n.title }}</span>
              <span class="cf-notif__type">{{ typeLabel(n.type) }}</span>
            </div>
            <div class="cf-notif__content">{{ n.content }}</div>
            <div class="cf-notif__time">
              <template v-if="isRead(n)">已读 {{ fmt(n.readAt) }}</template>
              <template v-else>{{ fmt(n.createdAt) }}</template>
              <span v-if="n.caseId" class="cf-notif__go">查看案件 →</span>
            </div>
          </div>
        </li>
      </transition-group>

      <div v-else class="cf-notif__empty">
        <el-icon :size="26" color="#c8a45c"><Bell /></el-icon>
        <div class="cf-notif__empty-title">{{ box === 'unread' ? '暂无新消息' : '暂无已读消息' }}</div>
        <div class="cf-muted">{{ box === 'unread' ? '与自己有关的操作变更会出现在这里' : '点开的信件会保留在这里' }}</div>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
/**
 * 统一信箱（2026-10-04）：顶栏铃铛 + 下拉面板。
 *
 * <p>数据来自后端 /notifications/list?box=unread|read（按当前登录人收敛），未读数走 SSE 实时增。
 * 2026-10-06 起改为未读/已读双页签：点一条 = 标已读并**挪到已读页签**（不是删除），
 * 已读历史随时可翻（需求原话：能看到历史的已读信息）。
 *
 * <p>管理层视角：全站所有用户的操作（除自己）；普通用户视角：本人承办案件相关。
 * 区分逻辑全在后端，前端无感知。
 */
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import { notificationApi } from '../api'
import { useEventStore } from '../store/events'
import { useUserStore } from '../store/user'

const eventStore = useEventStore()
const userStore = useUserStore()
const router = useRouter()
const myUserId = computed(() => userStore.userInfo?.userId || null)

const box = ref('unread')
const unreadList = ref([])
const readList = ref([])
const unreadCount = ref(0)
const markingAll = ref(false)
const popRef = ref(null)
let unsubscribe = null

const current = computed(() => (box.value === 'unread' ? unreadList.value : readList.value))

const isRead = (n) => !!n.readAt
const fmt = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '-')

const typeLabel = (t) => ({
  OPINION: '意见', ASSIGN: '指派', STATUS: '状态',
  TODO: '待办', QUESTION: '疑问', FILE: '文件', OTHER: '其他'
}[t] || '其他')

/** 两个页签一起刷：数据量小（各封顶 200 条），换来页签切换零等待、状态永不串 */
const refresh = async () => {
  try {
    const [unread, read, cnt] = await Promise.all([
      notificationApi.unread(),
      notificationApi.list('read'),
      notificationApi.unreadCount()
    ])
    unreadList.value = unread || []
    readList.value = read || []
    unreadCount.value = cnt || 0
  } catch (e) {
    /* 网络异常保持现状 */
  }
}

/** 打开下拉时刷新（可能期间又来了新通知） */
const onShow = () => refresh()

/** 点一条 = 标已读 + 从未读页签挪到已读页签（不再移除）+ 跳到对应案件详情。
 *  路由按角色分：普通用户走「我的案件」，管理层走「案件管理」——
 *  两边都支持 ?caseId= 直开详情抽屉（打开后各自把 query 抹掉，刷新不重复弹）。 */
const read = async (n) => {
  if (!isRead(n)) {
    try {
      await notificationApi.markRead(n.id)
    } catch (e) {
      /* 标记失败不打断跳转 */
    }
    // 乐观挪动（时间戳本地生成，下次打开 onShow 会以服务端为准重刷）
    const d = new Date()
    const p = (x) => String(x).padStart(2, '0')
    const now = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
    unreadList.value = unreadList.value.filter((x) => x.id !== n.id)
    readList.value = [{ ...n, readAt: now }, ...readList.value.filter((x) => x.id !== n.id)]
    unreadCount.value = Math.max(0, unreadCount.value - 1)
  }
  /**
 * 点击信件 → 跳到**具体内容**而不只是案件列表（2026-10-08）。
 *
 * <p>路由参数（全部可空，前端只带有的）：
 * <ul>
 *   <li>{@code caseId} —— 必带，落点案件；</li>
 *   <li>{@code todoId} —— 待办锚点，落点任务详情浮窗；</li>
 *   <li>{@code questionId} —— 疑问锚点，落点该条疑问/回答并高亮；</li>
 *   <li>{@code subtaskId} —— 子任务锚点，落点该子任务并高亮。</li>
 * </ul>
 *
 * <p><b>降级</b>：后端对老数据/无锚点通知不填这些参数，此时退化为
 * "打开案件详情"；目标已被删除时由落地页（CaseTodoPanel/TodoDetailDialog）
 * 判定并静默降级，不会白屏或报错。
 */
if (n.caseId) {
  // 关面板再跳转：el-popover 的 hide()，别手动置 v-model（trigger=click 模式没有它）
  popRef.value?.hide()
  const path = userStore.isFullAccess ? '/cases' : '/my-cases'
  const query = { caseId: n.caseId }
  if (n.anchorTodoId) query.todoId = n.anchorTodoId
  if (n.anchorQuestionId) query.questionId = n.anchorQuestionId
  if (n.anchorSubtaskId) query.subtaskId = n.anchorSubtaskId
  router.push({ path, query })
}
}

const markAll = async () => {
  markingAll.value = true
  try {
    await notificationApi.markAllRead()
    ElMessage.success('已全部标为已读')
    await refresh()
  } finally {
    markingAll.value = false
  }
}

const onHide = () => {
  // 关闭后刷新（可能有点开未读的，以服务端为准）
  refresh()
}

// SSE：收到发给我的定向通知（userId 对得上）→ 未读 +1
const onEvent = (e) => {
  if (e.kind !== 'notification') return
  const me = myUserId.value
  if (!me || Number(e.userId) !== Number(me)) return
  unreadCount.value += 1
  // 直接插到未读页签头（当前在已读页签也能看到未读数在涨）
  unreadList.value = [{
    id: e.id, caseId: e.caseId, type: e.type,
    // 定位锚点一并带上：实时到达的通知也能直达具体内容
    anchorTodoId: e.anchorTodoId,
    anchorQuestionId: e.anchorQuestionId,
    anchorSubtaskId: e.anchorSubtaskId,
    title: e.title, content: e.content, createdAt: e.createdAt
  }, ...unreadList.value.filter((x) => x.id !== e.id)]
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
/* 未读/已读页签（2026-10-06） */
.cf-notif__tabs { display: inline-flex; background: #f2f4f7; border-radius: 5px; padding: 2px }
.cf-notif__tab {
  border: none; background: transparent; cursor: pointer; font-size: 12px; color: #5a6472;
  padding: 3px 10px; border-radius: 4px; line-height: 1.4; transition: all .15s;
}
.cf-notif__tab.is-active { background: #fff; color: #1b2430; font-weight: 600;
  box-shadow: 0 1px 2px rgba(27, 36, 48, .12) }
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
/* 已读条目：置灰（放在类型配色之后，已读优先于类型） */
.cf-notif__item.is-read { opacity: .78 }
.cf-notif__item.is-read:hover { background: #fafbfc }
.cf-notif__item.is-read .cf-notif__dot { background: #c9ced6 }
.cf-notif__item.is-read .cf-notif__item-title { font-weight: 500; color: #5a6472 }
.cf-notif__item.is-read .cf-notif__content { color: #8a929e }
.cf-notif__body { min-width: 0; flex: 1 }
.cf-notif__title-row { display: flex; align-items: center; gap: 8px }
.cf-notif__item-title { font-weight: 600; color: #1b2430; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.cf-notif__type { flex: none; font-size: 11px; color: #8a929e; background: #f2f4f7; padding: 0 6px; border-radius: 3px }
.cf-notif__content { margin-top: 3px; font-size: 13px; color: #3d4653; line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden }
.cf-notif__time { margin-top: 4px; font-size: 12px; color: #b9c0ca }
/* 「查看案件」常驻提示（项目约定：禁悬浮提示，说明要一直可见） */
.cf-notif__go { margin-left: 8px; color: #1b4a8c; font-weight: 600 }
.cf-notif__empty { text-align: center; padding: 28px 0 20px }
.cf-notif__empty-title { font-size: 14px; font-weight: 600; color: #1b2430; margin: 8px 0 4px }
/* 已读挪动淡出 */
.cf-notif__fade-leave-active { transition: all .3s ease }
.cf-notif__fade-leave-to { opacity: 0; transform: translateX(20px) }
.cf-notif__fade-move { transition: transform .3s ease }
</style>
