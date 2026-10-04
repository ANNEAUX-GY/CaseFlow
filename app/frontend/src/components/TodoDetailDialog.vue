<template>
  <el-dialog
    v-model="visible"
    :title="detail.title"
    :width="isMobile ? '94%' : '640px'"
    append-to-body
    destroy-on-close
    @closed="onClosed"
  >
    <div v-loading="loading" class="cf-td">
      <!-- 概览 -->
      <div v-if="detail.todo" class="cf-td__head">
        <el-tag :type="statusType" effect="dark" size="small">
          {{ detail.todo.statusName || (detail.todo.status === 'DONE' ? '已完成' : '待办') }}
        </el-tag>
        <span v-if="detail.todo.subtaskTotal" class="cf-td__subinfo">
          子任务 {{ detail.todo.subtaskDone }}/{{ detail.todo.subtaskTotal }}已完成
        </span>
        <span v-if="detail.todo.doneAt" class="cf-muted">
          完成于 {{ fmt(detail.todo.doneAt) }} · {{ detail.todo.doneByName || '-' }}
        </span>
      </div>

      <!-- 反馈记录（累积，时间正序） -->
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
              <!-- status_at 是反馈当时的快照，不是当前状态 -->
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
            <el-checkbox
              :model-value="s.status === 'DONE'"
              :disabled="savingId === s.id"
              @change="(v) => toggleSub(s, v)" />
            <span class="cf-td__sub-text">{{ s.content }}</span>
            <span v-if="s.status === 'DONE' && s.doneAt" class="cf-muted">
              {{ fmt(s.doneAt) }} · {{ s.doneByName || '-' }}
            </span>
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

      <!-- 提交反馈 -->
      <div class="cf-td__sec">
        <div class="cf-td__sec-head"><span>提交反馈</span></div>
        <el-input
          v-model="newFb"
          type="textarea"
          :rows="3"
          maxlength="600"
          show-word-limit
          :placeholder="isParent
            ? '填写本任务的反馈说明（完成本任务前至少需要一条）'
            : '填写该子任务的反馈说明'" />
        <div class="cf-td__actions">
          <el-button type="primary" size="small" :loading="savingFb"
            :disabled="!newFb.trim()" @click="submitFeedback">
            提交反馈
          </el-button>
          <span class="cf-muted cf-td__hint">
            {{ isParent ? '反馈与完成是两个动作：先反馈说明，再点下方「标记完成」' : '' }}
          </span>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <!-- 主任务完成：子任务全完成 + 已有反馈说明才允许 -->
      <el-button
        v-if="isParent"
        :type="done ? 'warning' : 'primary'"
        size="small"
        :loading="savingDone"
        :disabled="done || !canDone"
        @click="toggleMain">
        {{ done ? '撤销完成' : '标记完成' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { todoApi } from '../api'
import { useDevice } from '../utils/device'

const emit = defineEmits(['changed'])

const { isMobile } = useDevice()

const visible = ref(false)
const loading = ref(false)
const todoId = ref(null)
const data = ref({})

const savingId = ref(null)
const addingSub = ref(false)
const savingFb = ref(false)
const savingDone = ref(false)
const newSub = ref('')
const newFb = ref('')

const STATUS_META = {
  DONE: { label: '已完成', type: 'success' },
  IN_PROGRESS: { label: '进行中', type: 'warning' },
  PENDING: { label: '待办', type: 'info' }
}

const fmt = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '-')
const detail = computed(() => data.value || {})
const feedbacks = computed(() => detail.value.feedbacks || [])
const subtasks = computed(() => detail.value.subtasks || [])
const subtaskDone = computed(() => subtasks.value.filter((s) => s.status === 'DONE').length)
const done = computed(() => detail.value.todo?.status === 'DONE')
const isParent = computed(() => !detail.value.todo?.parentId)

const title = computed(() => detail.value.todo?.content || '任务详情')
const statusType = computed(() => (done.value ? 'success' : 'warning'))

/**
 * 能否标记完成——与后端两条规则保持一致，前端先拦一次给出即时反馈，
 * 后端再挡一次防绕过（前端 disabled 永远不能当校验用）。
 */
const canDone = computed(() => {
  const d = detail.value
  if (!d.todo || done.value) return false
  // 规则：子任务全完成
  const total = d.todo.subtaskTotal ?? subtasks.value.length
  const dn = d.todo.subtaskDone ?? subtaskDone.value
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
  newFb.value = ''
  visible.value = true
  await load()
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

const toggleSub = async (s, checked) => {
  savingId.value = s.id
  try {
    await todoApi.toggleSubtask(s.id, !!checked)
    await load()
    emit('changed')
  } catch (e) {
    // 后端已给出中文原因（axios 拦截器会弹），这里只需恢复到服务端状态
    await load()
  } finally {
    savingId.value = null
  }
}

const submitFeedback = async () => {
  const content = (newFb.value || '').trim()
  if (!content) return
  savingFb.value = true
  try {
    data.value = await todoApi.addFeedback(todoId.value, content) || data.value
    newFb.value = ''
    ElMessage.success('反馈已记录')
    emit('changed')
  } finally {
    savingFb.value = false
  }
}

const toggleMain = async () => {
  if (done.value) {
    savingDone.value = true
    try {
      await todoApi.reopen(todoId.value)
      ElMessage.success('已撤销完成')
      await load()
      emit('changed')
    } finally {
      savingDone.value = false
    }
    return
  }
  savingDone.value = true
  try {
    await todoApi.done(todoId.value, newFb.value.trim() || '')
    ElMessage.success('已标记完成')
    await load()
    emit('changed')
  } catch (e) {
    await load()
  } finally {
    savingDone.value = false
  }
}

const onClosed = () => {
  todoId.value = null
  data.value = {}
}

watch(visible, (v) => { if (!v) onClosed() })

defineExpose({ open })
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
.cf-td__sub.is-done .cf-td__sub-text { color: #8a929e; text-decoration: line-through }
.cf-td__sub-text { flex: 1; min-width: 0; word-break: break-all }
.cf-td__add-row { display: flex; gap: 8px; margin-top: 8px }
.cf-td__actions { display: flex; align-items: center; gap: 10px; margin-top: 8px; flex-wrap: wrap }
.cf-td__hint { font-size: 12px }
</style>
