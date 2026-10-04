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
        </div>
        <ul v-if="feedbacks.length" class="cf-td__fb-list">
          <li v-for="f in feedbacks" :key="f.id" class="cf-td__fb">
            <div class="cf-td__fb-meta">
              <span class="cf-td__fb-time">{{ fmt(f.createdAt) }}</span>
              <span class="cf-td__fb-by">{{ f.creatorName || '-' }}</span>
              <!-- statusAt 是反馈当时的落实状态快照，不是当前状态 -->
              <el-tag v-if="f.statusAt" size="small" effect="plain"
                :type="(STATUS_META[f.statusAt] || {}).type">
                {{ (STATUS_META[f.statusAt] || {}).label }}
              </el-tag>
            </div>
            <div class="cf-td__fb-text">{{ f.content }}</div>
          </li>
        </ul>
        <div v-else class="cf-muted cf-td__empty">
          暂无反馈记录{{ isParent ? '。完成本任务前需至少提交一条反馈说明' : '' }}
        </div>
      </div>

      <!-- 子任务列表：两级结构，此处为第二级 -->
      <div v-if="isParent" class="cf-td__sec">
        <div class="cf-td__sec-head">
          <span>子任务</span>
          <span class="cf-muted">共 {{ subtasks.length }} 个{{ subtaskDone === subtasks.length && subtasks.length ? '，已全部完成' : '' }}</span>
        </div>
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
import { todoApi } from '../api'
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

const load = async () => {
  if (!todoId.value) return
  loading.value = true
  try {
    data.value = await todoApi.detail(todoId.value) || {}
  } catch (e) {
    data.value = {}
  } finally {
    loading.value = false
  }
}

const open = async (id) => {
  todoId.value = id
  newSub.value = ''
  visible.value = true
  await load()
}

/** 勾选主任务时直接打开「标记完成」的反馈弹窗（完成必须带落实状态） */
const openComplete = async (id) => {
  await open(id)
  openCompleteMain()
}

defineExpose({ open, openComplete })

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
      if (fb.subId) {
        await todoApi.done(fb.subId, note)
      } else {
        await todoApi.done(todoId.value, note)
      }
      ElMessage.success('已标记完成')
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
