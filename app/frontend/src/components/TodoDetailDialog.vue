<template>
  <el-dialog
    v-model="visible"
    :title="title"
    :width="isMobile ? '94%' : '640px'"
    append-to-body
    destroy-on-close
    @closed="onClosed"
  >
    <div v-loading="loading" class="cf-td">
      <!-- 概览 -->
      <div v-if="detail.id" class="cf-td__head">
        <el-tag :type="statusType" effect="dark" size="small">
          {{ detail.statusName || (detail.status === 'DONE' ? '已完成' : '待办') }}
        </el-tag>
        <span v-if="detail.subtaskTotal" class="cf-td__subinfo">
          子任务 {{ detail.subtaskDone }}/{{ detail.subtaskTotal }}已完成
        </span>
        <span v-if="detail.doneAt" class="cf-muted">
          完成于 {{ fmt(detail.doneAt) }} · {{ detail.doneByName || '-' }}
        </span>
      </div>

      <!-- 反馈记录（累积，时间正序；带落实状态标签） -->
      <div class="cf-td__sec">
        <div class="cf-td__sec-head">
          <span>反馈记录</span>
          <span class="cf-muted">共 {{ feedbacks.length }} 条</span>
          <!-- 折叠：反馈多时默认只看最新 3 条（时间正序，最后 3 条即最新），
               全量撑开弹窗会盖住子任务和操作区（用户反馈太占版面） -->
          <span class="cf-spacer"></span>
          <el-button v-if="feedbacks.length > FB_PREVIEW" link type="primary" size="small"
            @click.stop="fbExpanded = !fbExpanded">
            {{ fbExpanded ? '收起' : `展开全部 ${feedbacks.length} 条` }}
          </el-button>
        </div>
        <ul v-if="feedbacks.length" class="cf-td__fb-list">
          <li v-for="f in visibleFeedbacks" :key="f.id" class="cf-td__fb">
            <div class="cf-td__fb-meta">
              <span class="cf-td__fb-time">{{ fmt(f.createdAt) }}</span>
              <span class="cf-td__fb-by">{{ f.creatorName || '-' }}</span>
              <!-- statusAt 是反馈当时的落实状态快照，不是当前状态 -->
              <el-tag v-if="f.statusAt" size="small" effect="plain"
                :type="(STATUS_META[f.statusAt] || {}).type">
                {{ (STATUS_META[f.statusAt] || {}).label }}
              </el-tag>
              <!-- 来源标注：主任务的详情里会合并展示子任务的提交记录，
                   不标来源就分不清是哪一步交的。 -->
              <el-tag v-if="subTitleOf(f)" size="small" type="info" effect="plain" class="cf-td__fb-from">
                {{ subTitleOf(f) }}
              </el-tag>
            </div>
            <div class="cf-td__fb-text">{{ f.content }}</div>
          </li>
        </ul>
        <div v-else class="cf-muted cf-td__empty">
          暂无反馈记录{{ isParent ? '。完成本任务前需至少提交一条反馈说明' : '' }}
        </div>
      </div>

      <!-- 子任务列表：两级结构，此处为第二级。
           默认收成一行摘要（反馈13条+子任务8个全铺开会撑爆弹窗、没法滚动看），
           点标题行展开；有未完成子任务时摘要带橙色提醒，不会漏看。 -->
      <div v-if="isParent" class="cf-td__sec">
        <div class="cf-td__sec-head cf-td__sec-head--click" @click="subExpanded = !subExpanded">
          <el-icon class="cf-td__fold-icon" :class="{ 'is-open': subExpanded }"><ArrowRight /></el-icon>
          <span>子任务</span>
          <span class="cf-muted">共 {{ subtasks.length }} 个，已完成 {{ subtaskDone }} 个</span>
          <span v-if="!subExpanded && subtaskDone < subtasks.length" class="cf-td__block">
            还有 {{ subtasks.length - subtaskDone }} 个未完成
          </span>
          <span class="cf-spacer"></span>
        </div>
        <template v-if="subExpanded">
        <ul v-if="subtasks.length" class="cf-td__sub-list">
          <li v-for="s in subtasks" :key="s.id" class="cf-td__sub"
            :class="{ 'is-done': s.status === 'DONE' }">
            <!-- 勾选 = 走同一个落实反馈弹窗（选「完成」才真正勾上）；
                 取消勾选 = 撤销该子任务完成（承办人本人与管理员均可） -->
            <el-checkbox
              :model-value="s.status === 'DONE'"
              :disabled="savingId === s.id"
              @change="(v) => toggleSub(s, v)" />
            <span class="cf-td__sub-text">{{ s.content }}</span>
            <span v-if="s.status === 'DONE' && s.doneAt" class="cf-muted">
              {{ fmt(s.doneAt) }} · {{ s.doneByName || '-' }}
            </span>
            <!-- 编辑子任务内容：写错了要能改，不能只能删了重加 -->
            <el-button v-if="editingSubId !== s.id" link type="primary" size="small"
              class="cf-td__sub-del" @click="startEditSub(s)">
              编辑
            </el-button>
            <template v-else>
              <el-input v-model="editingSubText" size="small" maxlength="200" class="cf-td__sub-edit"
                @keyup.enter="commitEditSub(s)" @keyup.esc="cancelEditSub" />
              <el-button link type="primary" size="small" @click="commitEditSub(s)">存</el-button>
              <el-button link size="small" @click="cancelEditSub">取消</el-button>
            </template>
            <!-- 删除子任务：误加的不能只能干等。
                 权限同「添加子任务」（普通用户与管理员均可）——
                 子任务是干活的人自己拆的，没理由只有领导能删。 -->
            <el-button link type="danger" size="small" class="cf-td__sub-del"
              :disabled="savingId === s.id" @click="removeSub(s)">
              删除
            </el-button>
          </li>
        </ul>
        <div v-else class="cf-muted cf-td__empty">暂无子任务，可点下方添加</div>
        <!-- 添加子任务：普通用户与管理员均可（执行细节干活的人最清楚） -->
        <div class="cf-td__add-row">
          <el-input
            v-model="newSub"
            size="small"
            maxlength="200"
            placeholder="补充一条细节工作，如：打印询问笔录并送达"
            :disabled="subtasks.length >= 50"
            @keyup.enter="addSub" />
          <el-button type="primary" size="small" :loading="addingSub"
            :disabled="!newSub.trim() || subtasks.length >= 50" @click="addSub">
            添加子任务
          </el-button>
        </div>
        </template>
      </div>

      <!-- 疑问问答：员工遇到不懂的在此提问，管理层回答。
           独立于待办与任务——不派生待办、不影响完成规则，纯沟通记录。
           折叠（默认展开？否，与反馈/子任务一致默认收起，点区头展开）+ 增删改。 -->
      <div class="cf-td__sec">
        <div class="cf-td__sec-head cf-td__sec-head--click" @click="qExpanded = !qExpanded">
          <el-icon class="cf-td__fold-icon" :class="{ 'is-open': qExpanded }"><ArrowRight /></el-icon>
          <span>疑问</span>
          <span class="cf-muted">共 {{ questions.length }} 条</span>
          <span v-if="!qExpanded && questions.length" class="cf-td__block">
            {{ questions.filter(q => !q.answer).length }} 条待回答
          </span>
          <span class="cf-spacer"></span>
        </div>
        <template v-if="qExpanded">
        <ul v-if="questions.length" class="cf-td__q-list">
          <li v-for="q in questions" :key="q.id" class="cf-td__q">
            <div class="cf-td__fb-meta">
              <span class="cf-td__fb-time">{{ fmt(q.createdAt) }}</span>
              <span class="cf-td__fb-by">{{ q.questionByName || '-' }}</span>
              <el-tag v-if="!q.answer" size="small" type="warning" effect="plain">待回答</el-tag>
              <el-tag v-else size="small" type="success" effect="plain">已回答</el-tag>
              <span class="cf-spacer"></span>
              <!-- 增删改：编辑/删除（提问人本人或管理层；后端二次校验） -->
              <el-button v-if="canEditQuestion(q)" link type="primary" size="small"
                class="cf-td__q-op" @click.stop="startEditQuestion(q)">编辑</el-button>
              <el-button v-if="canEditQuestion(q)" link type="danger" size="small"
                class="cf-td__q-op" @click.stop="removeQuestion(q)">删除</el-button>
            </div>
            <!-- 编辑态：问题就地改 -->
            <template v-if="editingQuestionId === q.id">
              <el-input v-model="editingQuestionText" size="small" maxlength="500"
                class="cf-td__sub-edit" @keyup.enter="commitEditQuestion(q)" @keyup.esc="cancelEditQuestion" />
              <el-button link type="primary" size="small" @click="commitEditQuestion(q)">存</el-button>
              <el-button link size="small" @click="cancelEditQuestion">取消</el-button>
            </template>
            <div v-else class="cf-td__fb-text">{{ q.content }}</div>
            <!-- 回答块：管理层看到未回答的显示行内输入框 -->
            <div v-if="q.answer" class="cf-td__q-answer">
              <span class="cf-td__q-answer-by">{{ q.answerByName || '-' }} · {{ fmt(q.answeredAt) }} 回答</span>
              <div class="cf-td__fb-text">{{ q.answer }}</div>
            </div>
            <div v-else-if="isAdmin" class="cf-td__q-answer cf-td__q-answer--input">
              <el-input v-model="answerText[q.id]" size="small" maxlength="500"
                placeholder="填写回答…" @keyup.enter="submitAnswer(q)" />
              <el-button type="primary" size="small" :loading="answeringId === q.id"
                :disabled="!(answerText[q.id] || '').trim()" @click="submitAnswer(q)">
                回答
              </el-button>
            </div>
          </li>
        </ul>
        <div v-else class="cf-muted cf-td__empty">暂无疑问。遇到不懂的，直接在下方提问</div>
        <div class="cf-td__add-row">
          <el-input v-model="newQuestion" size="small" maxlength="500"
            placeholder="遇到不懂的？写下你的问题，管理层会回答"
            @keyup.enter="submitQuestion" />
          <el-button type="primary" size="small" :loading="asking"
            :disabled="!newQuestion.trim()" @click="submitQuestion">
            提交问题
          </el-button>
        </div>
        </template>
      </div>

      <!-- 提交反馈入口：走落实反馈弹窗（状态+说明+上传声明） -->
      <div class="cf-td__sec">
        <div class="cf-td__sec-head"><span>提交反馈</span></div>
        <div class="cf-td__actions">
          <el-button type="primary" size="small" @click="openRecord">
            提交反馈（选落实状态）
          </el-button>
          <span class="cf-muted cf-td__hint">
            {{ isParent ? '反馈与完成是两个动作：先反馈说明，再点下方「标记完成」' : '' }}
          </span>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <!-- 撤销完成仅管理层（后端 reopen 限管理员）；标记完成走落实反馈弹窗 -->
      <el-button
        v-if="isParent && isAdmin && done"
        type="warning"
        size="small"
        :loading="savingDone"
        @click="undoMain">
        撤销完成
      </el-button>
      <el-button
        v-if="isParent && !done"
        type="primary"
        size="small"
        :loading="savingDone"
        :disabled="!canDone"
        @click="openCompleteMain">
        标记完成
      </el-button>
    </template>

    <!-- 落实反馈弹窗（主任务/子任务共用，图二口径） -->
    <FeedbackDialog
      v-model="fb.visible"
      :title="fb.title"
      :quote="fb.quote"
      :default-status="fb.status"
      :loading="fb.loading"
      @submit="submitFeedback"
    />
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { todoApi, questionApi } from '../api'
import { useDevice } from '../utils/device'
import { useUserStore } from '../store/user'
import FeedbackDialog from './FeedbackDialog.vue'

const emit = defineEmits(['changed'])

const { isMobile } = useDevice()
const userStore = useUserStore()
const isAdmin = computed(() => userStore.isFullAccess)

const visible = ref(false)
const loading = ref(false)
const todoId = ref(null)
const data = ref({})

const savingId = ref(null)
const addingSub = ref(false)
const savingDone = ref(false)
const newSub = ref('')
const editingSubId = ref(null)
const editingSubText = ref('')

const STATUS_META = {
  DONE: { label: '已完成', type: 'success' },
  IN_PROGRESS: { label: '进行中', type: 'warning' },
  NOT_DONE: { label: '未完成', type: 'danger' },
  PENDING: { label: '待办', type: 'info' }
}

const fmt = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '-')
const detail = computed(() => data.value || {})
const feedbacks = computed(() => detail.value.feedbacks || [])
const subtasks = computed(() => detail.value.subtasks || [])
const subtaskDone = computed(() => subtasks.value.filter((s) => s.status === 'DONE').length)
const done = computed(() => detail.value?.status === 'DONE')
/** 反馈来源：若该条来自某个子任务，返回「子任务：xxx」；来自主任务本身则返回空 */
const subTitleOf = (f) => {
  const hit = subtasks.value.find((s) => String(s.id) === String(f.todoId))
  return hit ? '子任务：' + (hit.content || '') : ''
}

/* ---- 折叠（反馈/子任务多时默认收起，避免撑爆弹窗没法滚动看） ---- */
const FB_PREVIEW = 3   // 反馈默认预览条数（时间正序，最后 3 条即最新）
const fbExpanded = ref(false)
const qExpanded = ref(false)
const subExpanded = ref(false)
const visibleFeedbacks = computed(() =>
  fbExpanded.value ? feedbacks.value : feedbacks.value.slice(-FB_PREVIEW))

const isParent = computed(() => !detail.value?.parentId)

const title = computed(() => detail.value?.content || '任务详情')
const statusType = computed(() => (done.value ? 'success' : 'warning'))

/**
 * 能否标记完成——与后端两条规则保持一致，前端先拦一次给出即时反馈，
 * 后端再挡一次防绕过（前端 disabled 永远不能当校验用）。
 */
const canDone = computed(() => {
  const d = detail.value
  if (!d.id || done.value) return false
  // 规则：子任务全完成
  const total = d.subtaskTotal ?? subtasks.value.length
  const dn = d.subtaskDone ?? subtaskDone.value
  if (total > 0 && dn < total) return false
  // 规则：至少一条反馈说明
  return feedbacks.value.length > 0
})

/** 疑问问答：只看本任务上下文的（todoId 过滤）；数据独立，不影响任务状态 */
const questions = ref([])
const newQuestion = ref('')
const asking = ref(false)
const answerText = reactive({})
const answeringId = ref(null)
const editingQuestionId = ref(null)
const editingQuestionText = ref('')

const loadQuestions = async () => {
  if (!todoId.value) { questions.value = []; return }
  // 按当前任务过滤（接口返回全案，浮窗只看本任务上下文的疑问）
  try {
    const all = await questionApi.listOfCase(data.value.caseId) || []
    questions.value = all.filter((q) => String(q.todoId) === String(todoId.value))
  }
  catch (e) { questions.value = [] }
}

const submitQuestion = async () => {
  const content = (newQuestion.value || '').trim()
  if (!content) return
  asking.value = true
  try {
    await questionApi.ask(data.value.caseId, todoId.value, content)
    newQuestion.value = ''
    ElMessage.success('问题已提交，管理层会在此回答')
    await loadQuestions()
    emit('changed')
  } finally { asking.value = false }
}

const submitAnswer = async (q) => {
  const content = (answerText[q.id] || '').trim()
  if (!content) return
  answeringId.value = q.id
  try {
    await questionApi.answer(q.id, content)
    answerText[q.id] = ''
    ElMessage.success('已回答')
    await loadQuestions()
    emit('changed')
  } finally { answeringId.value = null }
}

/** 能否编辑/删除这条疑问：提问人本人或管理层（后端二次校验，前端只控按钮显隐） */
const canEditQuestion = (q) => isAdmin.value || String(q.questionBy) === String(userStore.userInfo?.userId)

const startEditQuestion = (q) => {
  editingQuestionId.value = q.id
  editingQuestionText.value = q.content || ''
}
const cancelEditQuestion = () => {
  editingQuestionId.value = null
  editingQuestionText.value = ''
}
const commitEditQuestion = async (q) => {
  const text = (editingQuestionText.value || '').trim()
  if (!text) { ElMessage.warning('请填写问题内容'); return }
  if (text === (q.content || '').trim()) { cancelEditQuestion(); return }
  const prev = q.content
  q.content = text
  editingQuestionId.value = null
  try {
    await questionApi.update(q.id, text)
    ElMessage.success('已保存')
    await loadQuestions()
    emit('changed')
  } catch (e) {
    q.content = prev
    ElMessage.error('保存失败，已恢复原文')
  }
}
const removeQuestion = async (q) => {
  try {
    await ElMessageBox.confirm(`确认删除这条疑问「${(q.content || '').slice(0, 20)}…」？`, '删除疑问',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await questionApi.remove(q.id)
    ElMessage.success('已删除')
    await loadQuestions()
    emit('changed')
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

const load = async () => {
  if (!todoId.value) return
  loading.value = true
  try {
    data.value = await todoApi.detail(todoId.value) || {}
    // 疑问问答必须在这里加载——上一轮插入丢失，导致「打开浮窗永远 0 条，
    // 发一条问题才刷出来」（只有提交路径调了 loadQuestions）。
    await loadQuestions()
  } catch (e) {
    data.value = {}
  } finally {
    loading.value = false
  }
}

const open = async (id) => {
  todoId.value = id
  newSub.value = ''
  fbExpanded.value = false
  subExpanded.value = false
  qExpanded.value = false
  visible.value = true
  await load()
}

/** 勾选主任务时直接打开「标记完成」的反馈弹窗（完成必须带落实状态） */
const openComplete = async (id) => {
  await open(id)
  openCompleteMain()
}

/**
 * 勾选子任务时打开「完成子任务」的反馈弹窗。
 *
 * <p>与主任务同一套汇报形式（落实状态 + 落实说明 + 上传声明）——
 * 用户明确要求：**每个子任务完成后都要走这个汇报弹窗**，
 * 不能在列表里直接勾上。先加载主任务详情（子任务列表要能对上号），
 * 再弹出以该子任务为对象的反馈弹窗。
 */
const openCompleteSub = async (parentId, subId) => {
  await open(parentId)
  openFeedbackDlg({ subId, complete: true })
}

defineExpose({ open, openComplete, openCompleteSub })

// ---- 落实反馈弹窗状态 ----
const fb = reactive({
  visible: false, title: '反馈落实情况', quote: '', status: 'DONE',
  loading: false,
  // complete = 提交后要标记完成；subId 非空 = 反馈对象是子任务
  complete: false, subId: null
})

const openFeedbackDlg = ({ target = 'main', subId = null, complete = false }) => {
  const row = subId ? subtasks.value.find((s) => s.id === subId) : detail.value
  fb.complete = complete
  fb.subId = subId
  fb.title = complete
    ? (subId ? '完成子任务' : '标记完成')
    : (subId ? '反馈子任务落实情况' : '反馈落实情况')
  fb.quote = row?.content || ''
  fb.status = complete ? 'DONE' : 'IN_PROGRESS'
  fb.visible = true
}

const openRecord = () => openFeedbackDlg({ target: 'main', complete: false })
const openCompleteMain = () => {
  if (!canDone.value) return
  openFeedbackDlg({ target: 'main', complete: true })
}

const undoMain = async () => {
  savingDone.value = true
  try {
    await todoApi.reopen(todoId.value)
    ElMessage.success('已撤销完成')
    await load()
    emit('changed')
  } catch (e) {
    await load()
  } finally {
    savingDone.value = false
  }
}

/** 反馈弹窗提交：complete=标记完成（主/子任务），否则只记录一条带状态的反馈 */
const submitFeedback = async ({ status, note }) => {
  fb.loading = true
  try {
    if (fb.complete) {
      // 按用户在弹窗里**实际选的落实状态**分派，而不是勾选时的意图：
      // 选「完成」才标记完成；选「进行中/未完成」说明用户改主意了，
      // 只记一条反馈、不动完成状态——否则弹窗里的状态选择形同虚设。
      const targetId = fb.subId || todoId.value
      if (status === 'DONE') {
        // 弹窗里填的落实说明是用户**主动提交的汇报内容**，要进反馈流留痕——
        // 这不是「系统自动提交」（自动指的是 done 时替用户凭空造一条），
        // 而是用户在汇报弹窗里亲手填写并点了提交。先记反馈再标记完成，
        // 顺序也满足主任务「至少一条反馈才能完成」的规则校验。
        if (note) {
          if (fb.subId) {
            await todoApi.addFeedback(fb.subId, { status, content: note })
          } else {
            await todoApi.addFeedback(todoId.value, { status, content: note })
          }
        }
        await todoApi.done(targetId, note)
        ElMessage.success(fb.subId ? '子任务已完成' : '已标记完成')
      } else {
        if (fb.subId) {
          await todoApi.addFeedback(fb.subId, { status, content: note })
        } else {
          await todoApi.addFeedback(todoId.value, { status, content: note })
        }
        ElMessage.success('反馈已记录（未标记完成）')
      }
    } else if (fb.subId) {
      await todoApi.addFeedback(fb.subId, { status, content: note })
      ElMessage.success('反馈已记录')
    } else {
      await todoApi.addFeedback(todoId.value, { status, content: note })
      ElMessage.success('反馈已记录')
    }
    fb.visible = false
    await load()
    emit('changed')
  } catch (e) {
    await load()
  } finally {
    fb.loading = false
  }
}

const addSub = async () => {
  const content = (newSub.value || '').trim()
  if (!content) return
  addingSub.value = true
  try {
    await todoApi.addSubtask(todoId.value, content)
    newSub.value = ''
    ElMessage.success('子任务已添加')
    await load()
    emit('changed')
  } finally {
    addingSub.value = false
  }
}

/* ---- 子任务内容就地编辑 ---- */
const startEditSub = (s) => {
  editingSubId.value = s.id
  editingSubText.value = s.content || ''
}
const cancelEditSub = () => {
  editingSubId.value = null
  editingSubText.value = ''
}
const commitEditSub = async (s) => {
  const text = (editingSubText.value || '').trim()
  if (!text) { ElMessage.warning('请填写子任务内容'); return }
  if (text === (s.content || '').trim()) { cancelEditSub(); return }
  const prev = s.content
  s.content = text
  editingSubId.value = null
  try {
    await todoApi.update(s.id, text)
    ElMessage.success('已保存')
    await load()
    emit('changed')
  } catch (e) {
    s.content = prev
    ElMessage.error('保存失败，已恢复原文')
  }
}

/** 删除子任务：二次确认，防手滑。已完成的也能删（删掉即从主任务的完成条件里移除）。 */
const removeSub = async (s) => {
  try {
    await ElMessageBox.confirm(
      `确认删除子任务「${s.content}」？`,
      '删除子任务',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '再想想' }
    )
  } catch { return }   // 用户取消
  savingId.value = s.id
  try {
    await todoApi.removeSubtask(s.id)
    ElMessage.success('子任务已删除')
    await load()
    emit('changed')
  } catch (e) {
    await load()
  } finally {
    savingId.value = null
  }
}

const toggleSub = async (s, checked) => {
  if (checked) {
    // 勾选子任务 = 打开同一个落实反馈弹窗，选「完成」才真正完成
    openFeedbackDlg({ subId: s.id, complete: true })
    return
  }
  savingId.value = s.id
  try {
    await todoApi.toggleSubtask(s.id, false)
    await load()
    emit('changed')
  } catch (e) {
    // 后端已给出中文原因（axios 拦截器会弹），这里恢复到服务端状态
    await load()
  } finally {
    savingId.value = null
  }
}

const onClosed = () => {
  todoId.value = null
  data.value = {}
}

watch(visible, (v) => { if (!v) onClosed() })
</script>

<style>
.cf-td { padding: 2px 4px }
.cf-td__head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px }
.cf-td__subinfo { font-size: 13px; color: #1b4a8c; font-weight: 600 }
.cf-td__sec { margin-top: 16px; padding-top: 12px; border-top: 1px dashed #e4e8ee }
.cf-td__sec:first-of-type { border-top: none }
.cf-td__sec-head {
  display: flex; align-items: center; gap: 8px; margin-bottom: 8px;
  font-size: 13px; font-weight: 600; color: #1b2430;
}
.cf-td__empty { font-size: 12px; padding: 6px 0 }
.cf-td__fb-list, .cf-td__sub-list { list-style: none; margin: 0; padding: 0 }
.cf-td__fb { padding: 6px 0; border-bottom: 1px dotted #eef1f5 }
.cf-td__fb:last-child { border-bottom: none }
.cf-td__fb-meta { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #8a929e; flex-wrap: wrap }
/* 来源标注（子任务名可能较长，限宽省略而不是撑破时间行） */
.cf-td__fb-from { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
/* 可点击折叠的区头 + 箭头转向 */
.cf-td__sec-head--click { cursor: pointer; user-select: none }
.cf-td__fold-icon { transition: transform .15s; color: #8a929e }
.cf-td__fold-icon.is-open { transform: rotate(90deg) }
/* 疑问问答：列表限高滚动（项目约定：列表类容器写死高度），回答块缩进区分 */
.cf-td__q-list { list-style: none; margin: 0; padding: 0; max-height: 240px; overflow-y: auto }
.cf-td__q { padding: 6px 0; border-bottom: 1px dotted #eef1f5 }
.cf-td__q-answer {
  margin-top: 4px; padding: 6px 10px; background: #f4f8f2;
  border-left: 3px solid var(--cf-ok, #1e8e58); border-radius: 3px;
}
/* 疑问行的编辑/删除操作按钮 */
.cf-td__q-op { font-size: 12px; padding: 0 2px; margin-left: 4px }
.cf-td__q-answer--input { display: flex; gap: 8px; align-items: center; background: #fbfcfe; border-left-color: #dfe4ea }
.cf-td__q-answer-by { font-size: 12px; color: #1e8e58 }
.cf-td__block { flex: 0 1 auto; min-width: 0; font-size: 12px; line-height: 1.4;
  color: #a8620a; background: #fdf6ec; border: 1px solid #f0dcc0;
  padding: 1px 7px; border-radius: 3px; overflow-wrap: anywhere }
.cf-td__fb-time { font-variant-numeric: tabular-nums }
.cf-td__fb-text { font-size: 13px; color: #1b2430; margin-top: 3px; line-height: 1.6; white-space: pre-wrap }
.cf-td__sub { display: flex; align-items: center; gap: 8px; padding: 4px 0; font-size: 13px }
.cf-td__sub.is-done /* 子任务行尾的操作按钮：删除/编辑。固定宽度避免点一个按钮整行跳动 */
.cf-td__sub-del { flex: none; font-size: 12px; padding: 0 2px; margin-left: 4px }
.cf-td__sub-edit { width: 150px }
.cf-td__sub-text { color: #8a929e; text-decoration: line-through }
.cf-td__sub-text { flex: 1; min-width: 0; word-break: break-all }
.cf-td__add-row { display: flex; gap: 8px; margin-top: 8px }
.cf-td__actions { display: flex; align-items: center; gap: 10px; margin-top: 8px; flex-wrap: wrap }
.cf-td__hint { font-size: 12px }
</style>
