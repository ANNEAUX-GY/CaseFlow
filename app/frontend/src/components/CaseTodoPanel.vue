<template>
  <div class="cf-panel cf-todo">
    <div class="cf-panel__head">
      <span>案件待办</span>
      <span class="cf-panel__head-tip">
        共 {{ todos.length }} 项，已完成 {{ doneCount }} 项
      </span>
      <div class="cf-panel__head-actions">
        <el-button v-if="isAdmin" link type="primary" @click="openAdd">添加待办</el-button>
      </div>
    </div>

    <div class="cf-todo__body">
      <!-- 进度条：一眼看出办理进度，不用数条目 -->
      <div v-if="todos.length" class="cf-todo__progress">
        <el-progress
          :percentage="percent"
          :stroke-width="8"
          :show-text="false"
          :color="percent === 100 ? 'var(--cf-ok)' : 'var(--cf-primary)'"
        />
        <span class="cf-todo__progress-text">{{ doneCount }}/{{ todos.length }}</span>
      </div>

      <ul v-if="todos.length" class="cf-todo__list">
        <li v-for="(t, i) in todos" :key="t.id" class="cf-todo__item" :class="{ 'is-done': t.status === 'DONE' }">
          <div class="cf-todo__main">
            <el-checkbox
              :model-value="t.status === 'DONE'"
              :disabled="!canToggle(t)"
              @change="(v) => toggle(t, v)"
            />
            <div class="cf-todo__text">
              <div class="cf-todo__content">
                <span class="cf-todo__index">{{ i + 1 }}.</span>
                <span>{{ t.content }}</span>
              </div>

              <!-- 完成信息 -->
              <div v-if="t.status === 'DONE'" class="cf-todo__meta">
                <el-tag type="success" size="small" effect="plain">已完成</el-tag>
                <span class="cf-muted">{{ t.doneByName || '-' }} · {{ fmtTime(t.doneAt) }}</span>
                <span v-if="t.remark" class="cf-muted">说明：{{ t.remark }}</span>
              </div>
              <!-- 未完成时说明「为什么还勾不动」，省得用户反复试 -->
              <div v-else class="cf-todo__meta">
                <el-tag v-if="blockReason(t)" type="warning" size="small" effect="plain">待处理</el-tag>
                <span class="cf-muted">{{ blockReason(t) || (t.subtaskTotal ? `子任务 ${t.subtaskDone}/${t.subtaskTotal} 已完成` : '可勾选完成') }}</span>
              </div>

              <!-- 子任务进度条：一眼看出还差几个 -->
              <div v-if="t.subtaskTotal" class="cf-todo__subbar">
                <el-progress
                  :percentage="Math.round((t.subtaskDone / t.subtaskTotal) * 100)"
                  :stroke-width="4" :show-text="false" />
                <span class="cf-muted">子任务 {{ t.subtaskDone }}/{{ t.subtaskTotal }}</span>
              </div>
            </div>

            <div class="cf-todo__actions">
              <!-- 详情：反馈记录 + 子任务，可在此设置完成状态 -->
              <el-button link type="primary" size="small" @click="openDetail(t)">详情</el-button>
              <!-- 添加子任务：普通用户与管理员均可（需求明确适用于所有待办任务） -->
              <el-button link type="primary" size="small" @click="openAddSub(t)">＋</el-button>
              <template v-if="isAdmin">
                <el-button link type="primary" size="small" @click="openEdit(t)">编辑</el-button>
                <el-button link size="small" :disabled="i === 0" @click="move(i, -1)">上移</el-button>
                <el-button link size="small" :disabled="i === todos.length - 1" @click="move(i, 1)">下移</el-button>
                <el-button link type="danger" size="small" @click="removeTodo(t)">删除</el-button>
              </template>
            </div>
          </div>
        </li>
      </ul>

      <el-empty v-else :image-size="60" description="暂无待办事项">
        <el-button v-if="isAdmin" type="primary" @click="openAdd">添加待办</el-button>
      </el-empty>
    </div>

    <!-- 任务详情浮窗：反馈记录 + 子任务，可设置完成状态 -->
    <TodoDetailDialog ref="detailRef" @changed="reload" />

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
            placeholder="例如：调取银行流水、制作询问笔录"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { todoApi } from '../api'
import { useUserStore } from '../store/user'
import { useDevice } from '../utils/device'
import TodoDetailDialog from './TodoDetailDialog.vue'

const props = defineProps({
  caseId: { type: [Number, String], required: true },
  // 有未保存变更时通知外层（案件详情据此刷新待办进度）
  readOnly: { type: Boolean, default: false }
})
const emit = defineEmits(['changed'])

const { isMobile } = useDevice()
const userStore = useUserStore()
const isAdmin = computed(() => userStore.isFullAccess)

const todos = ref([])
const saving = ref(false)
const editVisible = ref(false)
const form = ref({ id: null, content: '' })
const detailRef = ref(null)

const doneCount = computed(() => todos.value.filter((t) => t.status === 'DONE').length)
const percent = computed(() =>
  todos.value.length ? Math.round((doneCount.value / todos.value.length) * 100) : 0
)

const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '-')

/**
 * 勾选前置条件（2026-10-04 新规则，与后端一致）：
 * 1) 主任务至少要有一条反馈说明
 * 2) 子任务全完成才能完成主任务
 *
 * <p>返回空串表示可勾。前端这里只负责「置灰 + 说清原因」，
 * 真正的拦截在后端 done()——前端 disabled 不能当校验用。
 */
const blockReason = (t) => {
  const total = t.subtaskTotal || 0
  const dn = t.subtaskDone || 0
  if (total > 0 && dn < total) {
    return `还有 ${total - dn} 个子任务未完成，全部完成后才能勾选`
  }
  if (t.feedbackCount === 0 && !t.remark) {
    return '需先在「详情」里提交一条反馈说明，才能勾选完成'
  }
  return ''
}

const canToggle = (t) => {
  if (t.status === 'DONE') return isAdmin.value   // 撤销完成仅管理层
  return !blockReason(t)
}

const load = async () => {
  todos.value = await todoApi.listOfCase(props.caseId)
}

onMounted(load)

/** 打开详情浮窗（反馈记录 + 子任务 + 完成设置） */
const openDetail = (t) => detailRef.value?.open(t.id)

/** "＋"：添加子任务。普通用户与管理员均可，点开浮窗后填内容更从容 */
const openAddSub = (t) => detailRef.value?.open(t.id)

const toggle = async (t, checked) => {
  if (checked) {
    const why = blockReason(t)
    if (why) { ElMessage.warning(why); return }
  } else if (!isAdmin.value) {
    return
  }
  try {
    if (checked) {
      await todoApi.done(t.id, '')
      ElMessage.success('已标记完成')
    } else {
      await todoApi.reopen(t.id)
      ElMessage.success('已撤销完成')
    }
    await load()
    emit('changed')
  } catch (e) {
    await load()   // 失败时以服务端为准回滚本地状态
  }
}

// ---- 管理层维护 ----
const openAdd = () => {
  form.value = { id: null, content: '' }
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
      await todoApi.add(props.caseId, { content: form.value.content })
    }
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
    emit('changed')
  } finally {
    saving.value = false
  }
}

const removeTodo = async (t) => {
  await ElMessageBox.confirm(`确认删除待办「${t.content}」？`, '提示', { type: 'warning' })
  await todoApi.remove(t.id)
  ElMessage.success('已删除')
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
</script>
