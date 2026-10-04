<template>
  <div class="cf-opinion">
    <!-- 面板头：计数（管理端的录入入口在下方接龙区，不再用弹窗） -->
    <div class="cf-opinion__head">
      <span>领导意见</span>
      <span class="cf-muted">共 {{ opinions.length }} 条</span>
      <span class="cf-spacer"></span>
    </div>

    <!-- 统计条：A/B/C 与三种紧急性各有多少条，一眼看清重点 -->
    <div v-if="opinions.length" class="cf-opinion__stat">
      <span v-for="s in statBars" :key="s.key" class="cf-opinion__stat-item">
        <i class="cf-opinion__dot" :style="{ background: s.color }"></i>{{ s.label }} {{ s.n }}
      </span>
    </div>

    <!-- 实时提醒条：领导改了/删/加了意见时自动弹出，8 秒后自隐 -->
    <transition name="cf-fade">
      <div v-if="liveNotice" class="cf-opinion__notice">
        <el-icon><Bell /></el-icon>
        <span class="cf-opinion__notice-text">{{ liveNotice }}</span>
        <el-button link size="small" @click="liveNotice = ''">知道了</el-button>
      </div>
    </transition>

    <!-- 已提交意见：接龙式编号列表，每条独占一行。
         序号用数组下标实时计算（i + 1），插入/删除/拖拽后由 Vue 重渲染自动重算，
         结构上不可能断号或重复号。sort_order 只在后端持久化顺序，不参与显示编号。 -->
    <div v-if="opinions.length" ref="listRef" class="cf-opinion__list">
      <div v-for="(o, i) in opinions" :key="o.id" class="cf-opinion__item"
        :class="[`is-${urgencyOf(o.deadline)}`, { 'is-dragging': dragId === o.id }]">
        <span class="cf-opinion__no">{{ i + 1 }}</span>
        <div class="cf-opinion__item-body">
          <!-- 标题行：内容 + 重要性 + 紧急性角标 -->
          <div class="cf-opinion__title">
            <div class="cf-opinion__content">{{ o.content }}</div>
            <span class="cf-opinion__badges">
              <el-tag size="small" :type="IMPORTANCE_META[importanceOf(o)].type" effect="dark">
                {{ IMPORTANCE_META[importanceOf(o)].short }}
              </el-tag>
              <el-tag v-if="o.deadline" size="small" :type="URGENCY_META[urgencyOf(o.deadline)].type" effect="plain">
                {{ URGENCY_META[urgencyOf(o.deadline)].label }}
              </el-tag>
            </span>
          </div>
          <div class="cf-opinion__meta">
            {{ o.creatorName || '管理层' }} · {{ (o.createdAt || '').slice(0, 16) }} 提出
            <span v-if="o.deadline" class="cf-opinion__deadline">
              · 截止 {{ deadlineTextOf(o.deadline) }}
            </span>
          </div>

          <!-- 管理层可就地改截止时间与重要性；员工只读（顺序和定级是领导定的） -->
          <div v-if="isFullAccess" class="cf-opinion__meta-edit">
            <el-date-picker :model-value="o.deadline || ''" type="datetime" size="small"
              format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="设截止时间"
              :clearable="true" style="width: 190px" @change="(v) => saveMeta(o, { deadline: v || '' })" />
            <!-- 重要性：受控绑定 + 本地乐观更新。
                 为什么不能只用 @click 读 event.target：点击实际落在
                 <span class="el-radio-button__inner"> 上，target.value 是 undefined，
                 结果是「点了没反应」。而只用 @change 也不够——受控模式下
                 内部值变了父级没更新，视觉弹回后再点同一个值 change 不再触发，
                 表现为「点几次就改不动」。两者结合：@change 拿权威值，
                 setImportance 先改本地再落库，交互零延迟。 -->
            <el-radio-group :model-value="importanceOf(o)" size="small"
              @change="(v) => setImportance(o, v)">
              <el-radio-button value="A">A</el-radio-button>
              <el-radio-button value="B">B</el-radio-button>
              <el-radio-button value="C">C</el-radio-button>
            </el-radio-group>
            <!-- 内容就地编辑：点铅笔变输入框，Enter/失焦保存，Esc 取消 -->
            <el-button v-if="!editingId" link size="small" class="cf-opinion__edit-btn"
              @click="startEdit(o)">编辑</el-button>
            <template v-else-if="editingId === o.id">
              <el-input v-model="editingText" size="small" maxlength="500" class="cf-opinion__edit-input"
                placeholder="修改意见内容" @keyup.enter="commitEdit(o)" @keyup.esc="cancelEdit" />
              <el-button link type="primary" size="small" @click="commitEdit(o)">保存</el-button>
              <el-button link size="small" @click="cancelEdit">取消</el-button>
            </template>
            <!-- 移除：二次确认，避免误点删掉领导已提的意见 -->
            <el-popconfirm title="移除这条意见？移除后序号会自动重排" width="240"
              confirm-button-text="确认移除" cancel-button-text="取消"
              @confirm="doRemove(o)">
              <template #reference>
                <el-button link type="danger" size="small" class="cf-opinion__edit-btn">移除</el-button>
              </template>
            </el-popconfirm>
          </div>

          <!-- 反馈区：未反馈 / 已反馈两种态 -->
          <div class="cf-opinion__feedback">
            <template v-if="o.feedbackStatus">
              <el-tag size="small" :type="(FB_META[o.feedbackStatus] || {}).type" effect="dark">
                {{ (FB_META[o.feedbackStatus] || {}).label }}
              </el-tag>
              <span class="cf-opinion__feedback-meta">
                {{ o.feedbackByName }} · {{ (o.feedbackAt || '').slice(0, 19) }} 反馈
              </span>
            </template>
            <template v-else>
              <el-tag size="small" type="info" effect="plain">待反馈</el-tag>
              <span class="cf-opinion__feedback-meta cf-muted">办案人尚未反馈落实情况</span>
            </template>
            <span class="cf-spacer"></span>
            <!-- 反馈入口：仅本案办案人（管理员/领导不代反馈，落实是办案人的事） -->
            <el-button v-if="isAssignee" link type="primary" size="small" @click="openFeedback(o)">
              {{ o.feedbackStatus ? '更新反馈' : '反馈落实情况' }}
            </el-button>
          </div>
          <div v-if="o.feedbackNote" class="cf-opinion__note">{{ o.feedbackNote }}</div>
        </div>
        <!-- 拖拽把手：仅管理层、且非手机端（sortablejs 触摸支持有限，手机改用流程图与序号操作） -->
        <span v-if="isFullAccess && !isMobile" class="cf-opinion__handle" title="拖拽调整顺序">⋮⋮</span>
      </div>
    </div>
    <div v-else class="cf-muted" style="padding: 4px 0 8px">
      {{ isFullAccess ? '暂无意见，点下方 ＋ 逐条输入' : '暂无意见' }}
    </div>

    <!-- 接龙式录入（管理端）：点 ＋ 生成一行「序号 + 输入框 + 截止时间 + 重要性」，每条意见独占一行 -->
    <div v-if="isFullAccess" class="cf-opinion__drafts">
      <div v-for="(d, i) in drafts" :key="'d' + i" class="cf-opinion__draft">
        <span class="cf-opinion__no">{{ opinions.length + i + 1 }}</span>
        <el-input
          v-model="drafts[i].content"
          size="small"
          maxlength="500"
          placeholder="输入意见内容…"
          @keyup.enter="submitDrafts"
        />
        <el-date-picker v-model="drafts[i].deadline" type="datetime" size="small"
          format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="截止时间（选填）" :clearable="true" style="width: 180px"
          :class="{ 'is-mobile-full': isMobile }" />
        <el-radio-group v-model="drafts[i].importance" size="small">
          <el-radio-button value="A">A</el-radio-button>
          <el-radio-button value="B">B</el-radio-button>
          <el-radio-button value="C">C</el-radio-button>
        </el-radio-group>
        <el-button link type="danger" size="small" class="cf-opinion__draft-del"
          @click="drafts.splice(i, 1)">移除</el-button>
      </div>

      <div class="cf-opinion__draft-add">
        <el-button class="cf-opinion__plus" circle size="small" @click="addDraft">＋</el-button>
        <span class="cf-muted cf-opinion__plus-hint">
          {{ drafts.length ? '继续点 ＋ 依次添加' : '点 ＋ 开始输入意见，每条一行' }}
        </span>
        <span class="cf-spacer"></span>
        <el-button v-if="filledCount > 0" type="primary" size="small" :loading="saving"
          @click="submitDrafts">
          提交 {{ filledCount }} 条意见
        </el-button>
      </div>
    </div>
    <div v-else-if="!opinions.length" style="padding: 0 0 8px"></div>

    <!-- 反馈落实情况（办案人）。功能3：取消佐证材料上传，改为结构化"上传声明"提示语 -->
    <el-dialog v-model="fbDlg.visible" title="反馈意见落实情况" :width="isMobile ? '96%' : '560px'" append-to-body>
      <div class="cf-opinion__quote">{{ fbDlg.row?.content }}</div>

      <el-form label-width="92px" style="margin-top: 12px">
        <el-form-item label="落实状态" required>
          <el-radio-group v-model="fbDlg.status">
            <el-radio-button value="DONE">完成</el-radio-button>
            <el-radio-button value="IN_PROGRESS">进行中</el-radio-button>
            <el-radio-button value="NOT_DONE">未完成</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="落实说明">
          <el-input v-model="fbDlg.note" type="textarea" :rows="2"
            placeholder="选填，补充说明落实情况" maxlength="600" show-word-limit />
        </el-form-item>
      </el-form>

      <!-- 上传声明：替代原"佐证材料"上传。填了平台/文件即视为作出声明，
           提交时自动拼成一句"于…在…上传了…。"并入反馈说明 -->
      <div class="cf-opinion__declare">
        <div class="cf-opinion__declare-title">上传声明<span class="cf-muted">（代替上传佐证材料，选填）</span></div>
        <div class="cf-opinion__declare-tip">
          请输入：于<el-date-picker v-model="fbDlg.declareTime" type="datetime" size="small"
            format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="点击选择时间" :clearable="false"
            class="cf-opinion__slot-time" style="width: 190px" />（不选则默认当前时间，精确到秒）在
          <el-input v-model="fbDlg.declarePlatform" class="cf-opinion__slot-input" size="small"
            placeholder="平台名称，如：一体化办案平台" maxlength="100" style="width: 180px" /> 上传了
          <el-input v-model="fbDlg.declareFile" class="cf-opinion__slot-input" size="small"
            placeholder="文件名称，如：调取监控情况说明.docx" maxlength="200" style="width: 200px" /> 。
        </div>
        <!-- 常用平台一键填入 -->
        <div class="cf-opinion__declare-quick">
          常用平台：
          <el-link v-for="p in COMMON_PLATFORMS" :key="p" type="primary" :underline="false"
            style="font-size: 12px; margin-right: 10px" @click="fbDlg.declarePlatform = p">{{ p }}</el-link>
        </div>
        <!-- 实时预览：最终并入说明的声明句 -->
        <div v-if="declareSentence" class="cf-opinion__declare-preview">
          将并入说明：{{ declareSentence }}
        </div>
      </div>

      <template #footer>
        <el-button @click="fbDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="fbDlg.loading" @click="submitFeedback">提交反馈</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import Sortable from 'sortablejs'
import { watchApi } from '../api'
import {
  FEEDBACK_STATUS_META as FB_META,
  IMPORTANCE_META,
  URGENCY_META,
  importanceOf,
  urgencyOf as urgencyOfFn,
  deadlineTextOf as deadlineTextOfFn
} from '../utils/format'
import { useUserStore } from '../store/user'
import { useEventStore } from '../store/events'
import { useDevice } from '../utils/device'

const { isMobile } = useDevice()
const userStore = useUserStore()

const props = defineProps({
  caseId: { type: [Number, null], default: null },
  /** 案件详情（用于判断当前用户是否本案办案人） */
  detail: { type: Object, default: () => ({}) },
  /** 是否管理层（管理员/领导）：决定接龙式录入区是否显示 */
  isFullAccess: { type: Boolean, default: false }
})
const emit = defineEmits(['changed'])

// 常用办案平台建议（一键填入，可自由输入其他平台）
const COMMON_PLATFORMS = ['一体化办案平台', '全国公安信息平台', '电子卷宗系统', '执法办案系统']

const isAssignee = computed(() => {
  const myEmp = userStore.userInfo?.employeeId
  if (!myEmp) return false
  return (props.detail.assignHistory || []).some(
    (a) => a.status === 'ACTIVE' && String(a.employeeId) === String(myEmp))
})

const opinions = ref([])
const reload = async () => {
  if (!props.caseId) { opinions.value = []; return }
  opinions.value = await watchApi.opinions(props.caseId)
  // 数据回来后再挂拖拽：DOM 此时才稳定，Sortable 才能正确量高度
  nextTick(setupSortable)
}
watch(() => props.caseId, reload, { immediate: true })

// ============ 实时推送：领导的操作即时同步给主办人 / 经办人 ============
/**
 * 需求：「xx案，领导更新意见为：xxx，请尽快查看」。
 *
 * <p>后端把写操作统一广播到 SSE（LogService 是唯一汇聚点），这里只订阅：
 * - 只处理本案（targetId 对得上）且是意见类操作的事件，别的案件/模块不打扰
 * - 自己触发的操作不回显提示（操作人是我 → 我当然知道我刚做了什么）
 * - 去抖 400ms：拖拽排序会连发多次 reorder，合并成一次刷新
 */
const eventStore = useEventStore()
const liveNotice = ref('')
let noticeTimer = null
let reloadTimer = null

const OPINION_ACTIONS = [
  'OPINION_ADD', 'OPINION_UPDATE_CONTENT', 'OPINION_UPDATE_META', 'OPINION_REMOVE'
]

const showNotice = (text) => {
  liveNotice.value = text
  clearTimeout(noticeTimer)
  // 8 秒自动消失：常驻会挡住意见内容，但一晃而过又看不清
  noticeTimer = setTimeout(() => { liveNotice.value = '' }, 8000)
}

const onCaseEvent = (e) => {
  if (!e || !props.caseId) return
  if (String(e.targetId) !== String(props.caseId)) return
  if (!OPINION_ACTIONS.includes(e.action)) return
  // 自己刚做的操作不弹提示
  const myName = userStore.userInfo?.displayName || userStore.userInfo?.name
  if (e.operatorName && myName && e.operatorName === myName) return

  showNotice(`${e.operatorName || '领导'} ${e.content || '更新了意见'}，请尽快查看`)
  clearTimeout(reloadTimer)
  reloadTimer = setTimeout(() => reload(), 400)
}

let unsubscribe = null
onMounted(() => { unsubscribe = eventStore.subscribe(onCaseEvent) })
onBeforeUnmount(() => {
  if (unsubscribe) unsubscribe()
  clearTimeout(noticeTimer)
  clearTimeout(reloadTimer)
})

// ============ 紧急性 / 重要性（展示层纯函数，判定逻辑集中在 utils/format） ============
const urgencyOf = urgencyOfFn
const deadlineTextOf = deadlineTextOfFn

/** 管理层标记：模板里 props 自动解包，脚本里必须走 props，故统一成一个计算属性 */
const isManager = computed(() => props.isFullAccess)

/** 顶部统计条：三种紧急性各几条 */
const statBars = computed(() => {
  const n = { OVERDUE: 0, URGENT: 0, NORMAL: 0 }
  for (const o of opinions.value) n[urgencyOf(o.deadline)]++
  return [
    { key: 'OVERDUE', label: '已逾期', n: n.OVERDUE, color: '#c62a2a' },
    { key: 'URGENT', label: '临期', n: n.URGENT, color: '#d98a0b' },
    { key: 'NORMAL', label: '正常', n: n.NORMAL, color: '#8a929e' }
  ]
})

// ============ 拖拽排序（仅管理层、仅桌面） ============
const listRef = ref(null)
const dragId = ref(null)
let sortable = null

const setupSortable = () => {
  sortable?.destroy()
  sortable = null
  if (!isManager.value || isMobile.value || !listRef.value) return
  const el = listRef.value
  if (!el.children.length) return
  sortable = Sortable.create(el, {
    handle: '.cf-opinion__handle',
    animation: 150,
    // 拖动中的行淡出，其余行让位，序号实时跟着位置重排
    onStart: (e) => { dragId.value = opinions.value[e.oldIndex]?.id ?? null },
    onEnd: async (e) => {
      dragId.value = null
      if (e.oldIndex === e.newIndex) return
      // 按新下标重排本地数组 → 序号立刻重算（不等后端，交互无延迟）
      const next = opinions.value.slice()
      const [moved] = next.splice(e.oldIndex, 1)
      next.splice(e.newIndex, 0, moved)
      opinions.value = next
      try {
        await watchApi.reorderOpinions(props.caseId, next.map((o) => o.id))
        ElMessage.success('顺序已保存')
        emit('changed')
      } catch (e) {
        // 保存失败回滚到服务端顺序，避免界面与库里不一致
        await reload()
        ElMessage.error('顺序保存失败，已恢复原顺序')
      }
    }
  })
}
onBeforeUnmount(() => sortable?.destroy())
watch([isManager, isMobile], () => nextTick(setupSortable))

// ============ 截止时间 / 重要性 就地修改（仅管理层） ============
const saveMeta = async (row, patch) => {
  const payload = {
    deadline: patch.deadline !== undefined ? patch.deadline : (row.deadline || ''),
    importance: patch.importance !== undefined ? patch.importance : importanceOf(row)
  }
  try {
    await watchApi.updateOpinionMeta(row.id, payload)
    await reload()
    emit('changed')
  } catch (e) {
    ElMessage.error('保存失败')
  }
}

/**
 * 切换重要性等级。
 *
 * <p><b>为什么必须先改本地再落库</b>：el-radio-group 给的是受控绑定
 * （:model-value，没有 v-model）。点B 时组件内部选中态变了、change 也发了，
 * 但父级数据还是旧值 A，Vue 重渲染会把选中态**弹回A**；
 * 用户再点 B 时「值没变化」，change 不再触发 —— 于是表现为
 * 「点几次之后就更改不了等级了」。这不是接口问题，是绑定方式问题。
 *
 * <p>先写本地数组（选中态立刻跟着走，交互无延迟），再异步落库；
 * 失败才回滚到服务端值并提示。
 */
/**
 * 切换重要性等级。
 *
 * <p><b>三个坑叠在一起，缺一不可</b>：
 * 1. 只给 :model-value（受控）→ 点B 后内部选中态变了但父级数据没变，
 *    Vue 重渲染弹回旧值；再点 B 时「值没变化」，change 不再触发。
 *    表现就是用户说的「点几次之后就改不了」。
 * 2. 必须先写本地再落库，让选中态立刻跟着走，否则每次点击都有网络延迟的空档。
 * 3. **不能靠 reload() 来刷新**——reload 会整体替换 opinions 数组，
 *    模板里绑的row 引用随之失效，乐观改的值等于改在旧对象上；
 *    且 reload 的响应回来时可能覆盖掉用户紧接着的另一次点击
 *    （点 C 时若上一次 reload 还在飞，值就被拽回上一个）。
 *    所以成功后只做「按 id 就地同步」，让列表重渲染但保持对象引用稳定。
 */
const setImportance = async (row, next) => {
  const v = next || importanceOf(row)
  if (!IMPORTANCE_META[v]) return
  const prev = importanceOf(row)
  if (v === prev) return
  row.importance = v
  try {
    await watchApi.updateOpinionMeta(row.id, { deadline: row.deadline || '', importance: v })
    // 就地同步而不是 reload：保住引用，且不与用户下一次点击竞争
    const fresh = await watchApi.opinions(props.caseId)
    const hit = (fresh || []).find((o) => o.id === row.id)
    if (hit) {
      Object.keys(row).forEach((k) => { if (k !== 'id') row[k] = hit[k] })
    }
    ElMessage.success(`已设为 ${IMPORTANCE_META[v].label}`)
    emit('changed')
  } catch (e) {
    row.importance = prev
    ElMessage.error('等级修改失败，已恢复')
  }
}

// ============ 意见正文就地编辑（仅管理层） ============
const editingId = ref(null)
const editingText = ref('')

const startEdit = (row) => {
  editingId.value = row.id
  editingText.value = row.content || ''
}
const cancelEdit = () => {
  editingId.value = null
  editingText.value = ''
}
const commitEdit = async (row) => {
  const text = (editingText.value || '').trim()
  if (!text) {
    ElMessage.warning('意见内容不能为空')
    return
  }
  if (text === (row.content || '').trim()) {
    cancelEdit()
    return
  }
  const prev = row.content
  row.content = text
  editingId.value = null
  try {
    await watchApi.updateOpinionContent(row.id, text)
    await reload()
    ElMessage.success('意见已更新')
    emit('changed')
  } catch (e) {
    row.content = prev
    ElMessage.error('修改失败，已恢复原文')
  }
}

// ============ 移除意见（仅管理层） ============
/**
 * 移除后本地数组整条剔除再落库：序号是按数组下标实时算的，
 * 剔除后 Vue 重渲染就自动连续，不会有断号。
 */
const doRemove = async (row) => {
  const idx = opinions.value.findIndex((o) => o.id === row.id)
  if (idx < 0) return
  const snapshot = opinions.value.slice()
  opinions.value.splice(idx, 1)
  try {
    await watchApi.removeOpinion(row.id)
    await reload()
    ElMessage.success('意见已移除')
    emit('changed')
  } catch (e) {
    opinions.value = snapshot
    ElMessage.error('移除失败，已恢复')
  }
}

// 同一时刻只允许一个「落库 + reload」在飞：连点时后一次的 reload 可能先回来，
// 把前一次的结果覆盖掉（用户会看到「点了没反应」或值来回跳）。
let savingChain = Promise.resolve()
const serial = (fn) => {
  savingChain = savingChain.then(fn, fn)
  return savingChain
}

// ---- 接龙式录入（管理端）：点 ＋ 生成一行序号+输入框+截止时间+重要性，批量提交 ----
const emptyDraft = () => ({ content: '', deadline: '', importance: 'C' })
const drafts = ref([])
const saving = ref(false)
const filledCount = computed(() => drafts.value.filter((d) => d.content.trim()).length)

const addDraft = () => {
  drafts.value.push(emptyDraft())
  // 自动聚焦最新一行，输入不断手
  requestAnimationFrame(() => {
    const inputs = document.querySelectorAll('.cf-opinion__draft .el-input__inner')
    inputs[inputs.length - 1]?.focus()
  })
}

const submitDrafts = async () => {
  const valid = drafts.value.filter((d) => d.content.trim())
  if (!valid.length) {
    ElMessage.warning('请先输入意见内容')
    return
  }
  saving.value = true
  try {
    for (const d of valid) {
      await watchApi.addOpinion(props.caseId, {
    content: d.content.trim(),
        deadline: d.deadline || '',
        importance: d.importance || 'C'
      })
    }
    ElMessage.success(`已提交 ${valid.length} 条意见`)
    drafts.value = []
    await reload()
    emit('changed')
  } finally {
    saving.value = false
  }
}

// ---- 反馈落实情况（办案人） ----
const pad = (n) => String(n).padStart(2, '0')
const nowText = () => {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const fbDlg = reactive({
  visible: false, row: null, status: 'DONE', note: '',
  declareTime: '', declarePlatform: '', declareFile: '', loading: false
})
const openFeedback = (o) => {
  fbDlg.row = o
  // 已反馈过 → 带出上次状态便于增量更新；声明字段每次留空由办案人现填
  fbDlg.status = o.feedbackStatus || 'DONE'
  fbDlg.note = ''
  // 时间默认当前（精确到秒），可改；提交时若被清空也兜底取当下
  fbDlg.declareTime = nowText()
  fbDlg.declarePlatform = ''
  fbDlg.declareFile = ''
  fbDlg.visible = true
}

/** 声明句：平台/文件任一填写即生成；时间留空则取提交时刻 */
const declareSentence = computed(() => {
  const platform = (fbDlg.declarePlatform || '').trim()
  const file = (fbDlg.declareFile || '').trim()
  if (!platform && !file) return ''
  const time = fbDlg.declareTime || nowText()
  return `于 ${time} 在 ${platform || '（待填平台）'} 上传了 ${file || '（待填文件）'}。`
})

const submitFeedback = async () => {
  // 平台与文件必须成对：声明句里出现"（待填）"就提交不出去，避免留下残缺声明
  const platform = (fbDlg.declarePlatform || '').trim()
  const file = (fbDlg.declareFile || '').trim()
  if ((platform && !file) || (!platform && file)) {
    ElMessage.warning('上传声明的平台名称与文件名称需填写完整，或两项都留空')
    return
  }
  const parts = []
  if (fbDlg.note.trim()) parts.push(fbDlg.note.trim())
  if (declareSentence.value) parts.push(declareSentence.value)
  const note = parts.join('\n')
  if (!fbDlg.status) { ElMessage.warning('请选择落实状态'); return }
  if (!note) { ElMessage.warning('请填写落实说明或上传声明'); return }

  fbDlg.loading = true
  try {
    await watchApi.feedbackOpinion(fbDlg.row.id, { status: fbDlg.status, note })
    ElMessage.success('反馈已提交')
    fbDlg.visible = false
    await reload()
    emit('changed')
  } finally { fbDlg.loading = false }
}

defineExpose({ reload })
</script>

<style>
.cf-opinion__head { display: flex; align-items: center; gap: 8px; padding: 12px 16px 6px; font-weight: 600 }
.cf-opinion__list { padding: 0 16px }
.cf-opinion__item { display: flex; gap: 10px; padding: 10px 0; border-bottom: 1px dashed #dfe4ea }
.cf-opinion__item:last-child { border-bottom: none }
/* 接龙式序号：圆形徽标，与微信接龙的行首编号一致 */
.cf-opinion__no {
  flex: none; width: 20px; height: 20px; line-height: 20px; text-align: center;
  background: #eef3fa; color: #1b4a8c; border-radius: 50%;
  font-size: 12px; font-weight: 600; margin-top: 1px;
}
.cf-opinion__item-body { flex: 1; min-width: 0 }
.cf-opinion__content { font-size: 13px; color: #1b2430; white-space: pre-wrap; word-break: break-all }
.cf-opinion__meta { font-size: 12px; color: #8a929e; margin-top: 4px }
.cf-opinion__feedback { display: flex; align-items: center; gap: 8px; margin-top: 8px; flex-wrap: wrap }
.cf-opinion__feedback-meta { font-size: 12px; color: #5a6472 }
.cf-opinion__note {
  margin-top: 8px; padding: 8px 10px; background: #f7f9fc; border-left: 3px solid #b9c6d8;
  font-size: 12px; color: #3d4654; white-space: pre-wrap; word-break: break-all;
}
/* 接龙式录入区：＋ 按钮 + 序号行输入，简单直接 */
.cf-opinion__drafts { padding: 6px 16px 14px }
.cf-opinion__draft { display: flex; align-items: center; gap: 10px; padding: 4px 0 }
.cf-opinion__draft .el-input { flex: 1 }
.cf-opinion__draft-del { flex: none }
.cf-opinion__draft-add { display: flex; align-items: center; gap: 10px; padding: 8px 0 0 }
.cf-opinion__plus {
  width: 28px !important; height: 28px !important; padding: 0 !important;
  font-size: 17px; font-weight: 600; color: #1b4a8c !important;
  border-color: #b9cbe6 !important; background: #f2f6fc !important;
}
.cf-opinion__plus:hover { background: #e3edfa !important }
.cf-opinion__plus-hint { font-size: 12px }
/* 反馈弹窗里的意见原文引用块 */
.cf-opinion__quote {
  padding: 8px 10px; background: #f7f9fc; border-left: 3px solid #1b4a8c;
  font-size: 13px; color: #1b2430; white-space: pre-wrap; word-break: break-all;
}
/* 上传声明区：替代原佐证材料上传 */
.cf-opinion__declare {
  margin: 4px 16px 0; padding: 10px 12px; border: 1px dashed #c9d4e3; border-radius: 4px; background: #fbfcfe;
}
.cf-opinion__declare-title { font-size: 13px; font-weight: 600; color: #1b2430; margin-bottom: 8px }
.cf-opinion__declare-tip { font-size: 13px; color: #3d4654; line-height: 2.2 }
.cf-opinion__slot-time { vertical-align: middle; margin: 0 2px }
.cf-opinion__slot-input { display: inline-block; vertical-align: middle; margin: 0 2px; width: 180px !important }
.cf-opinion__declare-quick { font-size: 12px; color: #8a929e; margin-top: 6px }
.cf-opinion__declare-preview {
  margin-top: 8px; font-size: 12px; color: #1b4a8c;
  background: #eef3fa; border-radius: 3px; padding: 6px 8px; word-break: break-all;
}
.cf-muted { color: #8a929e }

/* ============ 紧急性颜色（2026-10） ============
   取色原则：左侧色条用饱和色做大范围区分，文字一律沿用原有深色（#1b2430），
   不把整块背景染成深色再配白字——那样在 45-50 岁使用者的小屏上易糊。
   色盲可读性：颜色之外一律另带文字角标（已逾期/临期），不靠颜色单独承载信息。 */
.cf-opinion__item { border-left: 3px solid transparent; padding-left: 8px; border-radius: 2px }
.cf-opinion__item.is-OVERDUE { border-left-color: #c62a2a; background: #fdf6f6 }
.cf-opinion__item.is-URGENT { border-left-color: #d98a0b; background: #fffaf1 }
.cf-opinion__item.is-dragging { opacity: .5 }
/* 标题行：内容与角标同一行，省一条竖向空间 */
.cf-opinion__title { display: flex; align-items: flex-start; gap: 8px }
.cf-opinion__title .cf-opinion__content { flex: 1; min-width: 0 }
.cf-opinion__badges { flex: none; display: flex; gap: 4px; margin-top: 1px }
.cf-opinion__deadline { color: #5a6472 }
/* 管理层就地编辑行 */
.cf-opinion__meta-edit { display: flex; align-items: center; gap: 8px; margin-top: 8px; flex-wrap: wrap }
.cf-opinion__meta-edit .is-mobile-full { width: 100% !important }
/* 就地编辑：输入框占满剩余宽度，按钮紧随其后不换行 */
.cf-opinion__edit-input { flex: 1; min-width: 220px }
.cf-opinion__edit-btn { padding: 0 2px; font-size: 12px }
/* 手机端编辑区改为整块堆叠，避免一行挤三样东西挤到换行错位 */
@media (max-width: 768px) {
  .cf-opinion__meta-edit { gap: 6px }
  .cf-opinion__edit-input { min-width: 100% }
}

/* 实时提醒条：深色底白字保证对比度（领导操作即时同步给主办/经办人） */
.cf-opinion__notice {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 0;
  padding: 8px 12px;
  border-radius: 4px;
  border-left: 4px solid var(--cf-gold, #c8a45c);
  background: #12294a;
  color: #fff;
  font-size: 13px;
}
.cf-opinion__notice-text { flex: 1; line-height: 1.5 }
.cf-opinion__notice .el-button { color: #c8a45c !important }
.cf-opinion__notice .el-button:hover { color: #e8c87c !important }
/* 拖拽把手：仅管理层桌面端出现（模板已按isFullAccess && !isMobile 控制） */
.cf-opinion__handle {
  flex: none; align-self: flex-start; margin-top: 2px;
  cursor: grab; color: #b9c6d8; font-size: 13px; letter-spacing: -1px;
  padding: 0 2px; user-select: none;
}
.cf-opinion__handle:hover { color: #1b4a8c }
.cf-opinion__handle:active { cursor: grabbing }
/* 顶部统计条 */
.cf-opinion__stat {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  padding: 0 16px 8px; font-size: 12px; color: #5a6472;
}
.cf-opinion__stat-item { display: inline-flex; align-items: center; gap: 5px }
.cf-opinion__dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block }
/* 流程图：默认收起，点击面板头按钮才展开。
   容器高度写死 + EChart height="100%"，与项目里「列表类容器必须写死高度」的约定一致，
   避免 flex:1 与内容高度互相依赖把面板顶爆。 */
</style>
