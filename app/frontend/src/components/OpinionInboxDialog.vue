<template>
  <el-dialog
    v-model="visible"
    title="新增领导意见"
    width="660px"
    append-to-body
    class="cf-inbox"
    @closed="onClosed"
  >
    <!-- 工具条：未读数 + 全部已读。
         「全部标为已读」是邮件客户端的标配——用户扫一眼都觉得不用细看时，一键清空。 -->
    <div class="cf-inbox__bar">
      <span class="cf-inbox__count">
        未读 <b>{{ list.length }}</b> 条
      </span>
      <span class="cf-spacer"></span>
      <el-button v-if="list.length" link type="primary" :loading="markingAll" @click="markAll">
        全部标为已读
      </el-button>
    </div>

    <!-- 邮件列表：点开一条 = 展开阅读 + 标已读；已读的条目从列表去掉（用户要求）。
         transition-group 让移除有淡出动画，条目消失是"被读掉了"而不是闪没。 -->
    <transition-group name="cf-inbox__fade" tag="ul" class="cf-inbox__list" v-if="list.length">
      <li v-for="o in list" :key="o.id" class="cf-inbox__item"
        :class="{ 'is-open': expandedId === o.id, 'is-a': o.importance === 'A' }"
        @click="toggle(o)">
        <!-- 首行：案号（主识别）+ 案件名 + 重要性 + 紧急性 -->
        <div class="cf-inbox__head">
          <span class="cf-inbox__dot" aria-hidden="true"></span>
          <span class="cf-inbox__caseno">{{ o.caseNo || ('案件#' + o.caseId) }}</span>
          <span class="cf-inbox__casename">{{ o.caseName || '' }}</span>
          <span class="cf-inbox__imp" :class="'is-' + (o.importance || 'C')">
            {{ { A: 'A·最重要', B: 'B·重要', C: 'C·一般' }[o.importance || 'C'] }}
          </span>
          <span class="cf-spacer"></span>
          <span v-if="urgencyOf(o)" class="cf-inbox__urgency" :class="'is-' + urgencyOf(o).key">
            {{ urgencyOf(o).label }}
          </span>
        </div>

        <!-- 摘要（收起时两行截断；展开时显示全文，样式随 is-open 放开） -->
        <div class="cf-inbox__content" :class="{ 'is-clamp': expandedId !== o.id }">
          {{ o.content }}
        </div>

        <!-- meta：提出人 + 提出时间；展开后追加操作按钮 -->
        <div class="cf-inbox__meta">
          <span>{{ o.creatorName || '领导' }} · {{ fmt(o.createdAt) }}</span>
          <span v-if="o.deadline" class="cf-inbox__dl">落实期限：{{ fmt(o.deadline) }}</span>
          <span class="cf-spacer"></span>
          <template v-if="expandedId === o.id">
            <el-button size="small" type="primary" plain @click.stop="goHandle(o)">去处理</el-button>
            <el-button size="small" @click.stop="removeItem(o)">知道了</el-button>
          </template>
          <span v-else class="cf-inbox__hint">点击查看</span>
        </div>
      </li>
    </transition-group>

    <!-- 空态：读完说清楚读完了，别留一片空白 -->
    <div v-else class="cf-inbox__empty">
      <el-icon :size="30" color="#c8a45c"><Bell /></el-icon>
      <div class="cf-inbox__empty-title">{{ loaded ? '已全部读完' : '加载中…' }}</div>
      <div class="cf-muted">领导提出的新意见都会先出现在这里，读过即自动归档</div>
    </div>

    <template #footer>
      <span class="cf-muted cf-inbox__tip">点开即记为已读；需要落实的在案件详情里反馈</span>
      <span class="cf-spacer"></span>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 意见收件箱（2026-10-04）：邮件式查看「新增领导意见」。
 *
 * <p><b>交互模型照搬邮件</b>：未读列表 → 点开一条（展开全文）即标已读 →
 * 已读条目从列表去掉；「去处理」跳到对应案件的详情抽屉（意见派生的待办在那里落实）。
 *
 * <p><b>口径</b>：后端 unreadForMe = 本人承办案件里 feedback_status 为空且本人未读过，
 * 与欢迎弹窗的 newOpinionCount 完全一致——数字和点进来的条数对不上会被当成 bug。
 *
 * <p><b>为什么不跳页</b>：用户在欢迎弹窗里点卡片，期待的是"就地打开看"，
 * 跳到案件列表还要再找一遍是倒退（需求原文：可以在当前页直接打开）。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import { watchApi } from '../api'
import { useMyTodoStore } from '../store/myTodo'

const emit = defineEmits(['changed'])
const router = useRouter()
const myTodoStore = useMyTodoStore()

const visible = ref(false)
const loaded = ref(false)
const list = ref([])
const expandedId = ref(null)
const markingAll = ref(false)

const fmt = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '-')

/** 紧急性：与意见面板同一套判定（已逾期红 / 3 天内临期橙 / 无期限不显示） */
const urgencyOf = (o) => {
  if (!o.deadline) return null
  const dl = new Date(String(o.deadline).replace(' ', 'T'))
  const now = new Date()
  if (dl < now) return { key: 'over', label: '已逾期' }
  if (dl - now < 3 * 864e5) return { key: 'soon', label: '临期' }
  return null
}

const open = async () => {
  visible.value = true
  loaded.value = false
  expandedId.value = null
  try {
    list.value = await watchApi.unreadOpinions() || []
  } catch (e) {
    list.value = []
  } finally {
    loaded.value = true
  }
}

/**
 * 点条目：展开/收起。展开时标已读——**不立刻从列表移除**（用户正在读），
 * 而是灰显加「已读」态，点「知道了」或去处理时才从列表去掉。
 * 拆成两步是因为"看"和"看完"是两个时刻，读一半就消失会打断阅读。
 */
const toggle = async (o) => {
  if (expandedId.value === o.id) {
    removeItem(o)
    return
  }
  expandedId.value = o.id
  try {
    await watchApi.markOpinionRead(o.id)
  } catch (e) {
    // 标记失败不打断阅读；下次打开欢迎弹窗数字仍会提示
  }
}

/** 看完一条：从列表去掉（已读意见不再展示——需求原话） */
const removeItem = (o) => {
  list.value = list.value.filter((x) => x.id !== o.id)
  if (expandedId.value === o.id) expandedId.value = null
}

/** 一键清空：全部标已读并移除 */
const markAll = async () => {
  markingAll.value = true
  try {
    await watchApi.markAllOpinionsRead()
    list.value = []
    expandedId.value = null
    ElMessage.success('已全部标为已读')
    myTodoStore.refresh()
    emit('changed')
  } finally {
    markingAll.value = false
  }
}

/** 去处理：跳到对应案件详情（意见派生的待办在那里），已读已记，直接关箱 */
const goHandle = (o) => {
  removeItem(o)
  visible.value = false
  router.push({ path: '/my-cases', query: { caseId: o.caseId, openDetail: 1 } })
}

const onClosed = () => {
  // 关箱时刷新欢迎弹窗的汇总，让"新增领导意见"数字与刚读掉的对上
  myTodoStore.refresh()
  emit('changed')
}

defineExpose({ open })
</script>

<style>
/* 收件箱：风格沿用系统面板（列表容器写死限高滚动——项目既定约定） */
.cf-inbox__bar { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; font-size: 13px; color: #5a6472 }
.cf-inbox__count b { color: #1b4a8c; font-size: 15px }
.cf-inbox__list {
  list-style: none; margin: 0; padding: 0;
  max-height: 52vh; overflow-y: auto;
  border: 1px solid #eef1f5; border-radius: 5px;
}
.cf-inbox__item {
  padding: 12px 14px; border-bottom: 1px solid #eef1f5; cursor: pointer;
  transition: background .15s;
}
.cf-inbox__item:last-child { border-bottom: none }
.cf-inbox__item:hover { background: #f7faff }
.cf-inbox__item.is-open { background: #f2f6fc; cursor: default }
.cf-inbox__head { display: flex; align-items: center; gap: 8px; min-width: 0 }
.cf-inbox__dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: #1b4a8c }
.cf-inbox__item.is-open .cf-inbox__dot { background: #dfe4ea }
.cf-inbox__caseno { font-weight: 600; color: #1b2430; white-space: nowrap }
.cf-inbox__casename { color: #5a6472; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
/* 重要性配色与意见面板 ABC 三档一致：A 红 / B 橙 / C 蓝 */
.cf-inbox__imp {
  flex: none; font-size: 12px; font-weight: 600; color: #fff;
  padding: 1px 8px; border-radius: 3px;
}
.cf-inbox__imp.is-A { background: #c62a2a }
.cf-inbox__imp.is-B { background: #d98a0b }
.cf-inbox__imp.is-C { background: #1b4a8c }
.cf-inbox__urgency { flex: none; font-size: 12px; font-weight: 600 }
.cf-inbox__urgency.is-over { color: #c62a2a }
.cf-inbox__urgency.is-soon { color: #d98a0b }
.cf-inbox__content { margin: 8px 0 6px 16px; font-size: 14px; line-height: 1.65; color: #1b2430 }
/* 收起时两行截断；展开显示全文 */
.cf-inbox__content.is-clamp {
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden; color: #3d4653;
}
.cf-inbox__meta { display: flex; align-items: center; gap: 10px; margin-left: 16px; font-size: 12px; color: #8a929e; flex-wrap: wrap }
.cf-inbox__dl { color: #a8620a }
.cf-inbox__hint { color: #b9c0ca }
.cf-inbox__empty { text-align: center; padding: 34px 0 26px }
.cf-inbox__empty-title { font-size: 15px; font-weight: 600; color: #1b2430; margin: 10px 0 6px }
.cf-inbox__tip { font-size: 12px }
/* 已读条目移除时的淡出（transition-group） */
.cf-inbox__fade-leave-active { transition: all .35s ease }
.cf-inbox__fade-leave-to { opacity: 0; transform: translateX(24px) }
.cf-inbox__fade-move { transition: transform .35s ease }

@media (max-width: 768px) {
  .cf-inbox__casename { display: none }
  .cf-inbox__content { margin-left: 0 }
  .cf-inbox__meta { margin-left: 0 }
}
</style>
