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
              <!-- 未上传佐证时的提示：明确告知「为什么勾不动」 -->
              <div v-else-if="!t.hasEvidence" class="cf-todo__meta">
                <el-tag type="warning" size="small" effect="plain">待上传佐证</el-tag>
                <span class="cf-muted">标记完成前需先上传佐证材料</span>
              </div>

              <!-- 佐证材料 -->
              <div v-if="t.evidence && t.evidence.length" class="cf-todo__evidence">
                <div v-for="f in t.evidence" :key="f.id" class="cf-todo__file">
                  <el-icon class="cf-todo__file-icon"><Document /></el-icon>
                  <a class="cf-todo__file-name" @click="download(f)">{{ f.fileName }}</a>
                  <span class="cf-muted">{{ f.sizeText }}</span>
                  <span class="cf-muted">{{ f.uploadedByName || '-' }} · {{ fmtTime(f.uploadedAt) }}</span>
                  <el-button v-if="canEditFile(f)" link type="danger" size="small" @click="removeEvidence(f, t)">
                    删除
                  </el-button>
                </div>
              </div>
            </div>

            <div class="cf-todo__actions">
              <el-upload
                :show-file-list="false"
                :http-request="(opt) => doUpload(opt, t)"
                :before-upload="(file) => beforeUpload(file, t)"
                :accept="acceptAttr"
              >
                <el-button link type="primary" size="small">上传佐证</el-button>
              </el-upload>
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

      <!-- 规则说明：把允许的类型、大小、存储方式讲清楚，避免上传后才被拒 -->
      <div v-if="rules" class="cf-todo__rules">
        <span class="cf-todo__rules-title">佐证材料要求</span>
        <span>格式：{{ rules.extHint }}</span>
        <span>单个文件不超过 {{ rules.maxText }}</span>
      </div>
    </div>

    <!-- 新增 / 编辑待办 -->
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
import { Document } from '@element-plus/icons-vue'
import { fileApi, todoApi } from '../api'
import { useUserStore } from '../store/user'
import { useDevice } from '../utils/device'

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
const rules = ref(null)
const saving = ref(false)
const editVisible = ref(false)
const form = ref({ id: null, content: '' })

const doneCount = computed(() => todos.value.filter((t) => t.status === 'DONE').length)
const percent = computed(() =>
  todos.value.length ? Math.round((doneCount.value / todos.value.length) * 100) : 0
)
const acceptAttr = computed(() =>
  rules.value?.allowedExt ? rules.value.allowedExt.map((e) => '.' + e).join(',') : undefined
)

const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '-')

// 非管理员：只能勾选「本案 + 已上传佐证」的待办；勾选后不可自行取消
const canToggle = (t) => {
  if (t.status === 'DONE') return isAdmin.value
  if (!t.hasEvidence) return false
  return true
}

// 佐证删除：管理层，或本人上传的材料
const canEditFile = (f) => isAdmin.value || f.uploadedByName === userStore.userInfo?.displayName

const load = async () => {
  todos.value = await todoApi.listOfCase(props.caseId)
}

onMounted(async () => {
  try {
    rules.value = await todoApi.rules()
  } catch (e) {
    /* 规则拿不到不影响主流程 */
  }
  await load()
})

const toggle = async (t, checked) => {
  try {
    if (checked) {
      if (!t.hasEvidence) {
        ElMessage.warning('请先上传佐证材料，再勾选完成')
        return
      }
      await todoApi.done(t.id)
      ElMessage.success('已标记完成')
    } else {
      // 取消勾选 = 撤销完成，仅管理层
      if (!isAdmin.value) return
      await todoApi.reopen(t.id)
      ElMessage.success('已撤销完成')
    }
    await load()
    emit('changed')
  } catch (e) {
    await load()
  }
}

// 上传前本地先按后端同一套规则挡一次，省去白跑一趟
const beforeUpload = (file, todo) => {
  const ext = (file.name.split('.').pop() || '').toLowerCase()
  if (rules.value?.allowedExt && !rules.value.allowedExt.includes(ext)) {
    ElMessage.error(`不支持的格式（.${ext}）。允许：${rules.value.extHint}`)
    return false
  }
  if (rules.value?.maxBytes && file.size > rules.value.maxBytes) {
    ElMessage.error(`文件超过 ${rules.value.maxText}，当前 ${(file.size / 1024 / 1024).toFixed(1)}MB`)
    return false
  }
  return true
}

const doUpload = async (opt, todo) => {
  const fd = new FormData()
  fd.append('file', opt.file)
  try {
    await todoApi.uploadEvidence(todo.id, fd)
    ElMessage.success('佐证已上传')
    // 提示下一步动作，上传后即可勾选
    if (todo.status !== 'DONE') {
      ElMessage.info('佐证已就绪，现在可以勾选完成')
    }
    await load()
    emit('changed')
    opt.onSuccess?.({})
  } catch (e) {
    opt.onError?.(e)
  }
}

const removeEvidence = async (f, todo) => {
  await ElMessageBox.confirm(`确认删除佐证「${f.fileName}」？`, '提示', { type: 'warning' })
  await fileApi.remove(f.id)
  ElMessage.success('已删除')
  await load()
  emit('changed')
}

const download = (f) => {
  const token = localStorage.getItem('cf_token') || ''
  window.open(`${fileApi.downloadUrl(f.id)}?token=${encodeURIComponent(token)}`, '_blank')
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
