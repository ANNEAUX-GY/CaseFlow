<template>
  <div class="cf-panel cf-todo">
    <div class="cf-panel__head">
      <span>案件待办<template v-if="opinionCount">（含领导意见 {{ opinionCount }} 条）</template></span>
      <span class="cf-panel__head-tip" v-if="todos.length">
        已完成 {{ doneCount }} / {{ todos.length }} 项
      </span>
      <div class="cf-panel__head-actions">
        <!-- 一个入口：提意见=生成待办，不再分「提意见 / 添加待办」两个按钮 -->
        <el-button v-if="isAdmin" link type="primary" @click="openAdd">＋ 添加</el-button>
      </div>
    </div>

    <!-- 空状态：把"这里是什么、接下来干什么"一次讲清 -->
    <div v-if="!todos.length" class="cf-todo__empty">
      <div class="cf-todo__empty-badge">待办</div>
      <div class="cf-todo__empty-title">本案件还没有待办事项</div>
      <ol class="cf-todo__empty-steps">
        <li v-if="isAdmin">点右上角「＋ 添加」，写清要办的事（可设截止时间和 A/B/C 重要级）</li>
        <li>添加的事项就是办案人的待办，干完活提交反馈说明</li>
        <li>反馈过后勾选前面的方框，该事项即标记完成</li>
      </ol>
      <el-button v-if="isAdmin" type="primary" @click="openAdd">＋ 添加第一条</el-button>
      <div v-else class="cf-muted" style="margin-top: 6px">领导提出意见后，这里会自动出现待办</div>
    </div>

    <template v-else>
      <!-- 新手引导：三步用法，可关闭（本会话不再出现） -->
      <el-alert v-if="showGuide" type="info" class="cf-todo__guide" :closable="true" @close="dismissGuide">
        <template #title>
          三步完成待办：① 先办 <b>A 级</b>、<b>临期/逾期</b> 的 → ② 干完后点「详情」提交一条<b>反馈说明</b>
          → ③ 再勾选前面的方框标记完成。有子任务的，先把子任务全部勾完。
        </template>
      </el-alert>

      <!-- 实时提醒条：领导加/改/删意见时自动弹出，8 秒后自隐 -->
      <transition name="cf-fade">
        <div v-if="liveNotice" class="cf-todo__notice">
          <el-icon><Bell /></el-icon>
          <span class="cf-todo__notice-text">{{ liveNotice }}</span>
          <el-button link size="small" @click="liveNotice = ''">知道了</el-button>
        </div>
      </transition>

      <!-- 进度与意见分布：一眼看清整体进度和紧急程度 -->
      <div class="cf-todo__summary">
        <div class="cf-todo__progress">
          <el-progress
            :percentage="percent"
            :stroke-width="8"
            :show-text="false"
            :color="percent === 100 ? 'var(--cf-ok)' : 'var(--cf-primary)'"
          />
          <span class="cf-todo__progress-text">{{ doneCount }}/{{ todos.length }}</span>
        </div>
        <div v-if="opinionCount" class="cf-todo__opstat">
          <span class="cf-todo__opstat-label">意见：</span>
          <span v-for="s in urgencyStats" :key="s.key" class="cf-todo__opstat-item">
            <i class="cf-todo__dot" :style="{ background: s.color }"></i>{{ s.label }} {{ s.n }}
          </span>
        </div>
      </div>

      <!-- 卡片列表：一张卡一件事，点击卡片任意空白处即可看详情/反馈 -->
      <ul ref="listEl" class="cf-todo__cards">
        <li v-for="(t, i) in todos" :key="t.id" class="cf-todo__card"
          :class="[
            { 'is-done': t.status === 'DONE', 'is-dragging': dragId === t.id },
            t.opinionId && t.status !== 'DONE' ? 'is-' + urgencyOf(t) : ''
          ]">
          <div class="cf-todo__card-main">
            <!-- 拖拽把手：仅管理层、桌面端；手机端用「管理 ▾」里的上移/下移 -->
            <span v-if="isAdmin && !isMobile" class="cf-todo__drag">⋮⋮</span>
            <!-- 勾选框：完成的主开关。
                 「为什么勾不动」不用悬浮提示（会挡住旁边的操作按钮，用户明确要求去掉），
                 改成紧跟在卡片里的内联文字——一直可见，不挡任何东西。 -->
            <el-checkbox
              class="cf-todo__check"
              :model-value="t.status === 'DONE'"
              :disabled="!canToggle(t)"
              @change="(v) => toggle(t, v)"
            />
            <span v-if="!canToggle(t) && t.status !== 'DONE'" class="cf-todo__block">
              {{ blockReason(t) }}
            </span>

            <!-- 卡片主体：点击打开详情 -->
            <div class="cf-todo__card-body" @click="openDetail(t)">
              <div class="cf-todo__card-title">
                <span class="cf-todo__no">{{ i + 1 }}</span>
                <span class="cf-todo__name" :class="{ 'is-done-text': t.status === 'DONE' }">{{ t.content }}</span>
                <!-- 状态徽章：一眼分清 已完成 / 待反馈 / 进行中 / 未完成 -->
                <el-tag v-if="t.status === 'DONE'" size="small" type="success" effect="dark">已完成</el-tag>
                <template v-else-if="t.opinionId">
                  <el-tag size="small" effect="dark"
                    :type="(FEEDBACK_META[t.opinionFeedbackStatus] || {}).type || 'info'">
                    {{ (FEEDBACK_META[t.opinionFeedbackStatus] || {}).label || '待反馈' }}
                  </el-tag>
                  <el-tag size="small" effect="dark" :type="IMPORTANCE_META[impOf(t)].type">
                    {{ impOf(t) }} 级
                  </el-tag>
                  <el-tag v-if="t.opinionDeadline" size="small" effect="plain"
                    :type="URGENCY_META[urgencyOf(t)].type">
                    {{ URGENCY_META[urgencyOf(t)].label }}
                  </el-tag>
                </template>
                <el-tag v-else size="small" type="warning" effect="plain">待办</el-tag>
              </div>

              <div class="cf-todo__card-meta">
                <template v-if="t.status === 'DONE'">
                  <span class="cf-todo__meta-item">
                    <el-icon><User /></el-icon>{{ t.doneByName || '-' }}
                  </span>
                  <span class="cf-todo__meta-item">
                    <el-icon><Clock /></el-icon>{{ fmtTime(t.doneAt) }} 完成
                  </span>
                  <span v-if="t.remark" class="cf-todo__meta-item is-note">{{ t.remark }}</span>
                </template>
                <template v-else-if="t.opinionId">
                  <span class="cf-todo__meta-item">
                    <el-icon><User /></el-icon>{{ t.opinionCreatorName || '管理层' }} 提出
                  </span>
                  <span class="cf-todo__meta-item">
                    <el-icon><Clock /></el-icon>{{ (t.opinionCreatedAt || '').slice(5, 16) }}
                  </span>
                  <span v-if="t.opinionDeadline" class="cf-todo__meta-item"
                    :class="'is-' + urgencyOf(t).toLowerCase()">
                    <el-icon><AlarmClock /></el-icon>截止 {{ deadlineTextOf(t.opinionDeadline) }}
                  </span>
                </template>
                <template v-else>
                  <span class="cf-todo__meta-item is-hint">
                    <el-icon><InfoFilled /></el-icon>{{ blockReason(t) || '提交一条反馈说明后即可勾选完成' }}
                  </span>
                </template>
              </div>

              <!-- 子任务入口：有子任务才显示，点开就地展开，不用进弹窗 -->
              <div v-if="t.subtaskTotal" class="cf-todo__sub-toggle"
                :class="{ 'is-open': subOpen[t.id] }" @click.stop="toggleSubs(t)">
                <el-icon><component :is="subOpen[t.id] ? ArrowUp : ArrowDown" /></el-icon>
                <span>子任务 {{ t.subtaskDone || 0 }}/{{ t.subtaskTotal }}</span>
                <span v-if="t.subtaskDone === t.subtaskTotal" class="cf-todo__sub-all">已全部完成</span>
                <span v-else-if="t.status !== 'DONE'" class="cf-todo__sub-tip">全部勾完才能勾选主任务</span>
              </div>

              <!-- 子任务就地展开：勾选/新增都在这里，不用进弹窗 -->
              <div v-if="subOpen[t.id]" class="cf-todo__subs" @click.stop>
                <div v-if="subData[t.id]?.loading" class="cf-muted" style="font-size: 12px; padding: 4px 0">
                  加载中…
                </div>
                <template v-else>
                  <div v-for="s in (subData[t.id]?.list || [])" :key="s.id" class="cf-todo__sub"
                    :class="{ 'is-done': s.status === 'DONE' }">
                    <el-checkbox size="small"
                      :model-value="s.status === 'DONE'"
                      :disabled="savingSubId === s.id"
                      @change="(v) => toggleSub(t, s, v)" />
                    <span class="cf-todo__sub-name">{{ s.content }}</span>
                    <span v-if="s.status === 'DONE' && s.doneAt" class="cf-todo__sub-done">
                      {{ fmtTime(s.doneAt) }}
                    </span>
                  </div>
                  <div v-if="!(subData[t.id]?.list || []).length" class="cf-todo__sub-none cf-muted">
                    还没有子任务，在下面添加第一条
                  </div>
                  <div class="cf-todo__sub-add">
                    <el-input v-model="newSub[t.id]" size="small" maxlength="200"
                      :placeholder="'补充细节工作，如：打印询问笔录并送达'"
                      :disabled="(subData[t.id]?.list || []).length >= 50"
                      @keyup.enter="addSub(t)" />
                    <el-button type="primary" size="small" plain :loading="addingSubId === t.id"
                      :disabled="!(newSub[t.id] || '').trim() || (subData[t.id]?.list || []).length >= 50"
                      @click="addSub(t)">
                      添加子任务
                    </el-button>
                  </div>
                </template>
              </div>

              <!-- 管理层就地调整意见的截止时间与重要性（同步回意见） -->
              <div v-if="isAdmin && t.opinionId && t.status !== 'DONE'" class="cf-todo__meta-edit" @click.stop>
                <span class="cf-todo__meta-edit-label">意见设置</span>
                <el-date-picker :model-value="t.opinionDeadline || ''" type="datetime" size="small"
                  format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="设截止时间"
                  :clearable="true" style="width: 170px" @change="(v) => saveOpinionMeta(t, { deadline: v || '' })" />
                <!-- ABC 三档：选中后各自显示颜色（A 红=最重要 / B 橙=重要 / C 蓝=一般），
                     用 is-a/is-b/is-c 三个类给 el-radio-button 单独上色。
                     不用 el-tag 是因为它只能只读展示，这里要能点。 -->
                <el-radio-group :model-value="impOf(t)" size="small"
                  class="cf-abc" @change="(v) => saveOpinionMeta(t, { importance: v })">
                  <el-radio-button value="A" class="is-a">A</el-radio-button>
                  <el-radio-button value="B" class="is-b">B</el-radio-button>
                  <el-radio-button value="C" class="is-c">C</el-radio-button>
                </el-radio-group>
              </div>
            </div>

            <!-- 操作区：常用两步（看详情/反馈、加子任务）做成大按钮，管理动作收进菜单 -->
            <div class="cf-todo__card-actions" @click.stop>
              <el-button size="small" type="primary" plain class="cf-todo__act" @click="openDetail(t)">
                <el-icon><View /></el-icon>详情·子任务
              </el-button>
              <!-- 提交反馈：独立入口，不必先开浮窗。
                   主任务与子任务一视同仁——只要是承办人的活就能提交，
                   与「有没有子任务」无关。已完成的任务不再显示（没什么可提交的了）。
                   原先挂在按钮上的悬浮提示已去掉：会盖住旁边的操作按钮，
                   改用按钮文字本身表意（「提交反馈」已说明用途）。 -->
              <el-button v-if="canSubmit && t.status !== 'DONE'"
                size="small" type="warning" plain class="cf-todo__act"
                :loading="submittingId === t.id" @click="openSubmit(t)">
                <el-icon><ChatLineSquare /></el-icon>提交反馈
              </el-button>
              <el-button size="small" class="cf-todo__act" :disabled="t.status === 'DONE'" @click="openSubsAndFocus(t)">
                <el-icon><Plus /></el-icon>子任务
              </el-button>
              <el-dropdown v-if="isAdmin" trigger="click" @command="(cmd) => onAdminCmd(t, i, cmd)">
                <el-button size="small" class="cf-todo__act">
                  管理<el-icon class="cf-todo__act-more"><ArrowDown /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">编辑内容</el-dropdown-item>
                    <el-dropdown-item command="up" :disabled="i === 0">上移</el-dropdown-item>
                    <el-dropdown-item command="down" :disabled="i === todos.length - 1">下移</el-dropdown-item>
                    <el-dropdown-item command="remove" divided class="cf-todo__menu-danger">
                      {{ t.opinionId ? '移除意见（连带待办）' : '删除待办' }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
        </li>
      </ul>
    </template>

    <!-- 提交反馈弹窗：列表页直接提交用。
         **纯手动**——openSubmit 只开弹窗不写库，真正落库要等用户
         在弹窗里点「提交反馈」（emit submit）才发生。 -->
    <FeedbackDialog
      v-model="fbDlg.visible"
      :title="fbDlg.title"
      :quote="fbDlg.quote"
      :default-status="fbDlg.status"
      :loading="fbDlg.loading"
      @submit="submitFeedback"
    />

    <!-- 任务详情浮窗：反馈记录 + 落实反馈弹窗（图二口径） -->
    <TodoDetailDialog ref="detailRef" @changed="onDetailChanged" />

    <el-dialog
      v-model="editVisible"
      :title="form.id ? '编辑待办' : '添加待办'"
      :width="isMobile ? '92%' : '460px'"
      append-to-body
      destroy-on-close
    >
      <el-form :label-width="isMobile ? '64px' : '72px'">
        <el-form-item label="内容" required>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="写清要办的事，如：72小时内完成受害人走访"
          />
        </el-form-item>
        <!-- 仅新增时展示：截止时间与重要程度（编辑时只改内容） -->
        <template v-if="!form.id">
          <el-form-item label="截止时间">
            <el-date-picker v-model="form.deadline" type="datetime"
              format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="选填；到期前会提示临期 / 逾期" :clearable="true" style="width: 100%" />
          </el-form-item>
          <el-form-item label="重要程度">
            <el-radio-group v-model="form.importance">
              <el-radio-button value="A">A 最重要</el-radio-button>
              <el-radio-button value="B">B 重要</el-radio-button>
              <el-radio-button value="C">C 一般</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 案件待办（合并领导意见）：一张卡一件事，点击卡片看详情，
 * 子任务就地展开勾选，管理动作收进「管理」菜单。
 *
 * <p>面向不熟悉电脑的使用者的三处引导：
 * <ul>
 *   <li>顶部「三步完成待办」提示条（可关闭，本会话不再出现）；</li>
 *   <li>勾选框灰住时悬停即说明原因，不用反复试；</li>
 *   <li>空状态直接给出"第一步点哪里"。</li>
 * </ul>
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bell, View, Plus, ArrowDown, ArrowUp, User, Clock, AlarmClock, InfoFilled, ChatLineSquare } from '@element-plus/icons-vue'
import Sortable from 'sortablejs'
import { todoApi, watchApi } from '../api'
import { useUserStore } from '../store/user'
import { useEventStore } from '../store/events'
import { useDevice } from '../utils/device'
import {
  FEEDBACK_STATUS_META as FEEDBACK_META,
  IMPORTANCE_META,
  URGENCY_META,
  importanceOf,
  urgencyOf as urgencyOfFn,
  deadlineTextOf as deadlineTextOfFn
} from '../utils/format'
import TodoDetailDialog from './TodoDetailDialog.vue'
import FeedbackDialog from './FeedbackDialog.vue'

const props = defineProps({
  caseId: { type: [Number, String], required: true }
})
const emit = defineEmits(['changed'])

const { isMobile } = useDevice()
const userStore = useUserStore()
const isAdmin = computed(() => userStore.isFullAccess)

const urgencyOf = (t) => urgencyOfFn(t.opinionDeadline)
const deadlineTextOf = deadlineTextOfFn
/** 重要性：意见派生条目优先读意见侧字段（与意见面板同一来源） */
const impOf = (t) => importanceOf({ importance: t.opinionId ? t.opinionImportance : t.importance })

const todos = ref([])
const saving = ref(false)
const editVisible = ref(false)
const form = ref({ id: null, content: '' })
const detailRef = ref(null)

const doneCount = computed(() => todos.value.filter((t) => t.status === 'DONE').length)
const percent = computed(() =>
  todos.value.length ? Math.round((doneCount.value / todos.value.length) * 100) : 0
)
const opinionCount = computed(() => todos.value.filter((t) => t.opinionId).length)

/** 意见紧急性统计（已逾期/临期/正常），口径与卡片角标一致 */
const urgencyStats = computed(() => {
  const n = { OVERDUE: 0, URGENT: 0, NORMAL: 0 }
  for (const t of todos.value) {
    if (t.opinionId && t.status !== 'DONE') n[urgencyOf(t)]++
  }
  return [
    { key: 'OVERDUE', label: '已逾期', n: n.OVERDUE, color: '#c62a2a' },
    { key: 'URGENT', label: '临期', n: n.URGENT, color: '#d98a0b' },
    { key: 'NORMAL', label: '正常', n: n.NORMAL, color: '#8a929e' }
  ]
})

// ---- 新手引导（本会话内关闭后不再出现） ----
const GUIDE_KEY = 'cf_todo_guide_dismissed'
const guideDismissed = ref(false)
try { guideDismissed.value = sessionStorage.getItem(GUIDE_KEY) === '1' } catch (e) { /* 忽略 */ }
const showGuide = computed(() => !guideDismissed.value && todos.value.length > 0)
const dismissGuide = () => {
  guideDismissed.value = true
  try { sessionStorage.setItem(GUIDE_KEY, '1') } catch (e) { /* 忽略 */ }
}

const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '-')

/**
 * 勾选前置条件（与后端一致）：
 * 1) 主任务至少要有一条反馈说明
 * 2) 子任务全完成才能完成主任务
 */
const blockReason = (t) => {
  const total = t.subtaskTotal || 0
  const dn = t.subtaskDone || 0
  if (total > 0 && dn < total) {
    return `还有 ${total - dn} 个子任务未完成，全部完成后才能勾选`
  }
  if (t.feedbackCount === 0 && !t.remark) {
    return '需先提交一条反馈说明，才能勾选完成'
  }
  return ''
}

const canToggle = (t) => {
  if (t.status === 'DONE') return isAdmin.value   // 撤销完成仅管理层
  return !blockReason(t)
}

const load = async () => {
  // 父容器可能在案件详情就绪前挂载（如盯办抽屉先开壳再拉数据），caseId 未就绪时先空着
  if (!props.caseId) {
    todos.value = []
    return
  }
  todos.value = await todoApi.listOfCase(props.caseId)
  // DOM 更新后再挂拖拽，Sortable 才能正确量高度
  nextTick(setupSortable)
}

onMounted(load)
watch(() => props.caseId, load)

// ---- 拖拽排序（仅管理层、桌面端；复用原来的 sortablejs 交互） ----
const listEl = ref(null)
let sortable = null
const dragId = ref(null)

const setupSortable = () => {
  sortable?.destroy()
  sortable = null
  if (!isAdmin.value || isMobile.value || !listEl.value) return
  if (!listEl.value.children.length) return
  sortable = Sortable.create(listEl.value, {
    handle: '.cf-todo__drag',
    animation: 150,
    onStart: (e) => { dragId.value = todos.value[e.oldIndex]?.id ?? null },
    onEnd: async (e) => {
      dragId.value = null
      if (e.oldIndex === e.newIndex) return
      // 先按新下标重排本地数组，序号立刻重算（不等后端）
      const next = todos.value.slice()
      const [moved] = next.splice(e.oldIndex, 1)
      next.splice(e.newIndex, 0, moved)
      todos.value = next
      try {
        await todoApi.reorder(props.caseId, next.map((t) => t.id))
        ElMessage.success('顺序已保存')
        emit('changed')
      } catch (err) {
        // 保存失败回滚到服务端顺序
        await load()
        ElMessage.error('顺序保存失败，已恢复原顺序')
      }
    }
  })
}
onBeforeUnmount(() => sortable?.destroy())
watch([isAdmin, isMobile], () => nextTick(setupSortable))

/* ============ 提交工作反馈（纯手动，2026-10-04） ============ */
/**
 * 谁能提交。
 *
 * <p>本组件拿不到案件的 assignHistory（没接 detail prop），所以**不在前端判定
 * 「是否本案承办人」**，而是交给后端 addFeedback 里的 checkOperate：
 * 非承办人会被拦下并返回「只有案件承办人或管理层可以…」的中文原因。
 * 按钮对所有能看到该案件的人显示——非承办人点了会得到明确提示，
 * 比按钮凭空消失更好（后者会让人以为系统坏了）。
 */
const canSubmit = computed(() => true)

const fbDlg = reactive({ visible: false, title: '', quote: '', status: '', loading: false })
const submittingId = ref(null)

/**
 * 打开提交反馈弹窗（主任务，纯反馈不完成）。
 *
 * <p><b>这里只开弹窗，不写任何数据。</b>真正落库要等用户在弹窗里主动点
 * 「提交反馈」按钮（FeedbackDialog emit submit）才会发生——
 * 这就是「纯手动」的全部含义：系统没有任何自动提交/自动流转路径。
 */
const openSubmit = (t) => {
  submittingId.value = t.id
  fbDlg.title = '提交工作反馈'
  fbDlg.quote = t.content || ''
  fbDlg.status = 'IN_PROGRESS'
  fbDlg.visible = true
}

/**
 * 用户在弹窗里主动点了「提交反馈」才走到这里——这是唯一的提交入口。
 * 纯反馈不改完成状态；勾选完成走 detailRef.openComplete 的弹窗（同样的汇报形式）。
 */
const submitFeedback = async ({ status, note }) => {
  if (!submittingId.value) return
  const t = todos.value.find((x) => x.id === submittingId.value)
  if (!t) return
  fbDlg.loading = true
  try {
    // 字段名必须是 content（后端 TodoSaveRequest），传 note 后端收不到——上一轮踩过
    await todoApi.addFeedback(t.id, { status, content: note })
    ElMessage.success('反馈已提交')
    fbDlg.visible = false
    await load()
    emit('changed')
  } catch (e) {
    // 失败时保留弹窗，让用户改内容重试，不用重新填一遍
  } finally {
    fbDlg.loading = false
    submittingId.value = null
  }
}

/** 打开详情浮窗（反馈记录 + 落实反馈弹窗） */
const openDetail = (t) => detailRef.value?.open(t.id)

const toggle = async (t, checked) => {
  if (checked) {
    // 勾选 = 打开落实反馈弹窗（选「完成」才真正完成，完成情况要留落实状态）
    const why = blockReason(t)
    if (why) { ElMessage.warning(why); return }
    detailRef.value?.openComplete(t.id)
    return
  }
  if (!isAdmin.value) return
  try {
    await todoApi.reopen(t.id)
    ElMessage.success('已撤销完成')
    await load()
    emit('changed')
  } catch (e) {
    await load()   // 失败时以服务端为准回滚本地状态
  }
}

// ---- 子任务就地展开 ----
const subOpen = ref({})
const subData = ref({})          // todoId -> { loading, list }
const newSub = reactive({})      // todoId -> 输入内容
const savingSubId = ref(null)
const addingSubId = ref(null)

const fetchSubs = async (t) => {
  subData.value[t.id] = { loading: true, list: [] }
  try {
    const d = await todoApi.detail(t.id)
    subData.value[t.id] = { loading: false, list: d.subtasks || [] }
  } catch (e) {
    subData.value[t.id] = { loading: false, list: [] }
  }
}

const toggleSubs = async (t) => {
  if (subOpen.value[t.id]) {
    subOpen.value[t.id] = false
    return
  }
  subOpen.value[t.id] = true
  if (!subData.value[t.id]) await fetchSubs(t)
}

/** 「子任务」按钮：展开该卡的子任务区并把输入框聚焦 */
const openSubsAndFocus = async (t) => {
  subOpen.value[t.id] = true
  if (!subData.value[t.id]) await fetchSubs(t)
  nextTick(() => {
    const card = [...document.querySelectorAll('.cf-todo__card')].find(
      (el) => el.textContent.includes(t.content.slice(0, 12)))
    ;(card && card.querySelector('.cf-todo__sub-add input'))?.focus()
  })
}

const toggleSub = async (t, s, checked) => {
  if (checked) {
    // 勾选子任务 = 打开与主任务同一套的汇报弹窗（落实状态+说明+上传声明），
    // 选「完成」才真正勾上——不能在列表里直接勾掉（用户明确的交互要求）。
    detailRef.value?.openCompleteSub(t.id, s.id)
    return
  }
  savingSubId.value = s.id
  try {
    await todoApi.toggleSubtask(s.id, false)
    ElMessage.success('已撤销子任务完成')
    await fetchSubs(t)
    await load()
    emit('changed')
  } catch (e) {
    await fetchSubs(t)
  } finally {
    savingSubId.value = null
  }
}

const addSub = async (t) => {
  const content = (newSub[t.id] || '').trim()
  if (!content) return
  addingSubId.value = t.id
  try {
    await todoApi.addSubtask(t.id, content)
    newSub[t.id] = ''
    ElMessage.success('子任务已添加')
    await fetchSubs(t)
    await load()
    emit('changed')
  } finally {
    addingSubId.value = null
  }
}

/** 详情弹窗内变动后，若某卡的子任务区开着，同步刷新 */
const onDetailChanged = async () => {
  const openId = Object.keys(subOpen.value).find((k) => subOpen.value[k])
  if (openId) {
    const t = todos.value.find((x) => String(x.id) === String(openId))
    if (t) await fetchSubs(t)
  }
  await load()
  emit('changed')
}

// ---- 管理层维护 ----
const openAdd = () => {
  form.value = { id: null, content: '', deadline: '', importance: 'C' }
  editVisible.value = true
}
const openEdit = (t) => {
  form.value = { id: t.id, content: t.content }
  editVisible.value = true
}

const submit = async () => {
  if (!form.value.content || !form.value.content.trim()) {
    ElMessage.warning('请填写待办内容')
    return
  }
  saving.value = true
  try {
    if (form.value.id) {
      await todoApi.update(form.value.id, { content: form.value.content })
    } else {
      // 统一走「提意见」链路：一条意见 = 一条待办（新条目由后端排到列表末尾，序号递增）
      await watchApi.addOpinion(props.caseId, {
        content: form.value.content,
        deadline: form.value.deadline || '',
        importance: form.value.importance || 'C'
      })
    }
    ElMessage.success(form.value.id ? '已保存' : '已添加')
    editVisible.value = false
    await load()
    emit('changed')
  } finally {
    saving.value = false
  }
}

const removeTodo = async (t) => {
  if (t.opinionId) {
    // 意见派生条目：移除意见（后端会连带删掉派生待办，两边不会留孤儿）
    await ElMessageBox.confirm(
      `确认移除这条领导意见？「${t.content}」及其派生待办会一起删除。`,
      '移除意见', { type: 'warning', confirmButtonText: '确认移除', cancelButtonText: '取消' }
    )
    await watchApi.removeOpinion(t.opinionId)
    ElMessage.success('意见已移除')
  } else {
    await ElMessageBox.confirm(`确认删除待办「${t.content}」？`, '提示', { type: 'warning' })
    await todoApi.remove(t.id)
    ElMessage.success('已删除')
  }
  await load()
  emit('changed')
}

const move = async (index, delta) => {
  const list = [...todos.value]
  const target = index + delta
  if (target < 0 || target >= list.length) return
  ;[list[index], list[target]] = [list[target], list[index]]
  todos.value = list
  await todoApi.reorder(props.caseId, list.map((t) => t.id))
  await load()
  emit('changed')
}

/** 管理▾ 菜单命令分发 */
const onAdminCmd = (t, i, cmd) => {
  if (cmd === 'edit') openEdit(t)
  else if (cmd === 'up') move(i, -1)
  else if (cmd === 'down') move(i, 1)
  else if (cmd === 'remove') removeTodo(t)
}

// ---- 管理层就地调整意见元信息（截止时间/重要性，同步回意见与待办） ----
const saveOpinionMeta = async (row, patch) => {
  const payload = {
    deadline: patch.deadline !== undefined ? patch.deadline : (row.opinionDeadline || ''),
    importance: patch.importance !== undefined ? patch.importance : impOf(row)
  }
  try {
    await watchApi.updateOpinionMeta(row.opinionId, payload)
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error('保存失败')
  }
}

// ---- 实时推送：领导的意见操作即时同步（SSE） ----
const eventStore = useEventStore()
const liveNotice = ref('')
let noticeTimer = null
let reloadTimer = null

const OPINION_ACTIONS = ['OPINION_ADD', 'OPINION_UPDATE_CONTENT', 'OPINION_UPDATE_META', 'OPINION_REMOVE']
const TODO_ACTIONS = ['TODO_ADD', 'TODO_UPDATE', 'TODO_DELETE', 'TODO_DONE', 'TODO_REOPEN',
  'TODO_FEEDBACK', 'TODO_SUBTASK_ADD', 'TODO_SUBTASK_REOPEN']

const showNotice = (text) => {
  liveNotice.value = text
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { liveNotice.value = '' }, 8000)
}

const onCaseEvent = (e) => {
  if (!e || !props.caseId) return
  if (String(e.targetId) !== String(props.caseId)) return
  const mine = userStore.userInfo?.displayName || ''
  if (OPINION_ACTIONS.includes(e.action)) {
    if (!(e.operatorName && mine && e.operatorName === mine)) {
      showNotice(`${e.operatorName || '领导'} ${e.content || '更新了意见'}，请尽快查看`)
    }
    scheduleReload()
  } else if (TODO_ACTIONS.includes(e.action)) {
    scheduleReload()
  }
}

const scheduleReload = () => {
  clearTimeout(reloadTimer)
  reloadTimer = setTimeout(() => load(), 400)
}

let unsubscribe = null
onMounted(() => { unsubscribe = eventStore.subscribe(onCaseEvent) })
onBeforeUnmount(() => {
  if (unsubscribe) unsubscribe()
  clearTimeout(noticeTimer)
  clearTimeout(reloadTimer)
})

</script>

<style>
/* ============ 案件待办（合并领导意见）卡片化设计 ============ */

/* 空状态引导 */
.cf-todo__empty { text-align: center; padding: 26px 16px 22px }
.cf-todo__empty-badge {
  display: inline-block; padding: 3px 14px; border-radius: 999px;
  background: #eef3fa; color: #1b4a8c; font-weight: 700; font-size: 13px; margin-bottom: 10px;
}
.cf-todo__empty-title { font-size: 15px; font-weight: 700; color: #1b2430; margin-bottom: 8px }
.cf-todo__empty-steps {
  display: inline-block; text-align: left; margin: 0 auto 12px; padding-left: 20px;
  color: #5a6472; font-size: 13px; line-height: 1.9;
}

/* 新手引导条 */
.cf-todo__guide { margin: 0 16px 10px }
.cf-todo__guide .el-alert__title { font-size: 12.5px; line-height: 1.7 }
.cf-todo__guide b { color: #1b4a8c }

/* 进度 + 意见分布 */
.cf-todo__summary { padding: 2px 16px 8px }
.cf-todo__progress { display: flex; align-items: center; gap: 10px }
.cf-todo__progress .el-progress { flex: 1 }
.cf-todo__progress-text {
  flex: none; font-size: 13px; font-weight: 700; color: #1b4a8c;
  font-variant-numeric: tabular-nums;
}
.cf-todo__opstat {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  padding-top: 6px; font-size: 12px; color: #5a6472;
}
.cf-todo__opstat-label { color: #8a929e }
.cf-todo__opstat-item { display: inline-flex; align-items: center; gap: 5px }
.cf-todo__dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block }

/* 接龙式提意见区 */
.cf-todo__drafts { padding: 6px 16px 10px; border-bottom: 1px dashed #dfe4ea }
.cf-todo__draft { display: flex; align-items: center; gap: 10px; padding: 4px 0 }
.cf-todo__draft .el-input { flex: 1 }
.cf-todo__draft-add { display: flex; align-items: center; gap: 10px; padding: 8px 0 0 }
.cf-todo__plus {
  width: 28px !important; height: 28px !important; padding: 0 !important;
  font-size: 17px; font-weight: 600; color: #1b4a8c !important;
  border-color: #b9cbe6 !important; background: #f2f6fc !important;
}
.cf-todo__plus:hover { background: #e3edfa !important }
.cf-todo__no {
  flex: none; width: 22px; height: 22px; line-height: 22px; text-align: center;
  background: #eef3fa; color: #1b4a8c; border-radius: 50%;
  font-size: 12px; font-weight: 700; margin-top: 1px;
}

/* 卡片列表 */
.cf-todo__cards { list-style: none; margin: 4px 0 0; padding: 0 12px 12px }
.cf-todo__card {
  border: 1px solid #e4e8ee; border-left: 4px solid #c9d4e3; border-radius: 8px;
  background: #fff; margin-top: 10px; transition: box-shadow .15s, border-color .15s;
}
.cf-todo__card:hover { box-shadow: 0 2px 10px rgba(18, 41, 74, .08); border-color: #b9cbe6 }
.cf-todo__card.is-OVERDUE { border-left-color: #c62a2a; background: #fdf6f6 }
.cf-todo__card.is-URGENT { border-left-color: #d98a0b; background: #fffaf1 }
.cf-todo__card.is-done { border-left-color: var(--cf-ok, #1e8e58); background: #f6fbf8; opacity: .92 }
/* 拖拽把手：仅管理层桌面端渲染；竖排双点更像"可抓握" */
.cf-todo__drag {
  flex: none; align-self: center; cursor: grab; user-select: none;
  color: #b9c6d8; font-size: 13px; letter-spacing: -1px; line-height: 1;
  padding: 6px 0 6px 2px;
}
.cf-todo__drag:hover { color: #1b4a8c }
.cf-todo__drag:active { cursor: grabbing }
.cf-todo__card.is-dragging { opacity: .5 }
.cf-todo__card-main { display: flex; gap: 10px; padding: 12px 12px 10px 12px }
.cf-todo__check { margin-top: 2px }
.cf-todo__check .el-checkbox__inner { width: 18px; height: 18px; border-radius: 4px }
.cf-todo__check .el-checkbox__inner::after { height: 9px; left: 6px; top: 2px }

.cf-todo__card-body { flex: 1; min-width: 0; cursor: pointer }
.cf-todo__card-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap }
.cf-todo__name { font-size: 14px; font-weight: 600; color: #1b2430; word-break: break-all }
.cf-todo__name.is-done-text { color: #8a929e; text-decoration: line-through; font-weight: 500 }

.cf-todo__card-meta {
  display: flex; align-items: center; gap: 4px 14px; flex-wrap: wrap;
  margin-top: 5px; font-size: 12px; color: #5a6472;
}
.cf-todo__meta-item { display: inline-flex; align-items: center; gap: 4px }
.cf-todo__meta-item .el-icon { font-size: 13px; color: #8a929e }
.cf-todo__meta-item.is-OVERDUE, .cf-todo__meta-item.is-overdue { color: #c62a2a; font-weight: 600 }
.cf-todo__meta-item.is-URGENT, .cf-todo__meta-item.is-urgent { color: #d98a0b; font-weight: 600 }
.cf-todo__meta-item.is-note {
  color: #3d4654; background: #f7f9fc; border-radius: 3px; padding: 1px 8px;
  max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cf-todo__meta-item.is-hint { color: #8a929e }

/* 子任务开合与列表 */
.cf-todo__sub-toggle {
  display: inline-flex; align-items: center; gap: 6px; margin-top: 7px;
  font-size: 12px; color: #1b4a8c; background: #eef3fa; border-radius: 999px;
  padding: 3px 12px; cursor: pointer; user-select: none;
}
.cf-todo__sub-toggle:hover { background: #e3edfa }
.cf-todo__sub-toggle .el-icon { font-size: 12px }
.cf-todo__sub-all { color: var(--cf-ok, #1e8e58); font-weight: 600 }
.cf-todo__sub-tip { color: #8a929e }
.cf-todo__subs {
  margin-top: 8px; padding: 8px 10px; background: #f7f9fc;
  border: 1px dashed #d6dee9; border-radius: 6px;
}
.cf-todo__sub { display: flex; align-items: center; gap: 8px; padding: 4px 0; font-size: 13px }
.cf-todo__sub.is-done .cf-todo__sub-name { color: #8a929e; text-decoration: line-through }
.cf-todo__sub-name { flex: 1; min-width: 0; word-break: break-all; color: #1b2430 }
.cf-todo__sub-done { flex: none; font-size: 12px; color: #8a929e }
.cf-todo__sub-none { font-size: 12px; padding: 2px 0 }
.cf-todo__sub-add { display: flex; gap: 8px; margin-top: 6px }
.cf-todo__sub-add .el-input { flex: 1 }

/* 管理层意见设置行 */
.cf-todo__meta-edit {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 8px;
  padding: 6px 8px; background: #fbfcfe; border: 1px dashed #dfe4ea; border-radius: 6px;
}
/* 「为什么勾不动」的内联说明：常驻可见，不悬浮、不遮挡任何按钮 */
.cf-todo__block {
  flex: none; align-self: center; font-size: 12px; line-height: 1.4;
  color: #a8620a; background: #fdf6ec; border: 1px solid #f0dcc0;
  padding: 1px 7px; border-radius: 3px; max-width: 190px;
}
.cf-todo__meta-edit-label { font-size: 12px; color: #8a929e; flex: none }

/* ABC 三档选中色（需求：点选时各自显示不同颜色）
   Element 的 el-radio-button 选中态统一是主题蓝，三档看不出差别。
   这里覆盖 .is-active 的背景/边框/文字色：A 红=最重要、B 橙=重要、C 蓝=一般，
   配色沿用项目警情色板（#c62a2a 危险红 / #d98a0b 警告橙 / #1b4a8c 警蓝）。
   钩子比 .is-active 稍深，选中时白字压在上面保证对比度 ≥ 4.5:1。 */
.cf-abc .el-radio-button__inner {
  border-left: none !important;
  border-right: none !important;
}
.cf-abc .el-radio-button:first-child .el-radio-button__inner { border-left: 1px solid var(--el-border) !important; border-radius: 4px 0 0 4px; }
.cf-abc .el-radio-button:last-child .el-radio-button__inner { border-right: 1px solid var(--el-border) !important; border-radius: 0 4px 4px 0; }

.cf-abc .el-radio-button.is-a.is-active .el-radio-button__inner {
  background: #c62a2a; border-color: #c62a2a !important; box-shadow: -1px 0 0 0 #c62a2a;
}
.cf-abc .el-radio-button.is-b.is-active .el-radio-button__inner {
  background: #d98a0b; border-color: #d98a0b !important; box-shadow: -1px 0 0 0 #d98a0b;
}
.cf-abc .el-radio-button.is-c.is-active .el-radio-button__inner {
  background: #1b4a8c; border-color: #1b4a8c !important; box-shadow: -1px 0 0 0 #1b4a8c;
}
/* 悬停时用浅色底提示可点。
   注意：悬停文字色不能盖在 .is-active 上——Element 的默认 hover 规则是
   "文字变主题色"，若不排除 active 态，点中后鼠标仍悬停在按钮上时
   会出现「红底红字」这种看不清的情况（实测踩过）。
   所以用 :hover:not(.is-active)。 */
.cf-abc .el-radio-button.is-a:hover:not(.is-active) .el-radio-button__inner { color: #c62a2a; }
.cf-abc .el-radio-button.is-b:hover:not(.is-active) .el-radio-button__inner { color: #d98a0b; }
.cf-abc .el-radio-button.is-c:hover:not(.is-active) .el-radio-button__inner { color: #1b4a8c; }
/* 选中态文字必须是白的（浅色底上的深色字）——显式声明，不依赖继承 */
.cf-abc .el-radio-button.is-active .el-radio-button__inner { color: #fff; }

/* 操作区 */
.cf-todo__card-actions {
  flex: none; display: flex; flex-direction: column; align-items: flex-end; gap: 6px;
}
.cf-todo__act { width: 96px; margin: 0 !important; padding-left: 8px !important; padding-right: 8px !important }
.cf-todo__act .el-icon { margin-right: 3px }
.cf-todo__act-more { margin-left: 2px !important; margin-right: 0 !important }
.cf-todo__menu-danger { color: #c62a2a }

/* 实时提醒条 */
.cf-todo__notice {
  display: flex; align-items: center; gap: 8px;
  margin: 6px 16px; padding: 8px 12px; border-radius: 4px;
  border-left: 4px solid var(--cf-gold, #c8a45c);
  background: #12294a; color: #fff; font-size: 13px;
}
.cf-todo__notice-text { flex: 1; line-height: 1.5 }
.cf-todo__notice .el-button { color: #c8a45c !important }
.cf-todo__notice .el-button:hover { color: #e8c87c !important }

.cf-fade-enter-active, .cf-fade-leave-active { transition: opacity .25s }
.cf-fade-enter-from, .cf-fade-leave-to { opacity: 0 }

@media (max-width: 768px) {
  .cf-todo__draft { flex-wrap: wrap }
  .cf-todo__card-actions { flex-direction: row; align-items: center; width: 100% }
  .cf-todo__act { width: auto; flex: 1 }
  .cf-todo__card-main { flex-wrap: wrap }
  .cf-todo__card-body { width: 100%; order: 3; flex-basis: 100% }
}
</style>
