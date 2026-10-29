<template>
  <div class="cf-page">
    <div class="cf-panel">
      <div class="cf-panel__head">
        <span>工作记录本</span>
        <span class="cf-panel__head-tip">
          {{ canViewAll ? '可检索查看任意民警的工作记录' : '记录自己每天做了什么，仅本人可见' }}
        </span>
        <div class="cf-panel__head-actions">
          <el-button type="primary" @click="openAdd">新增记录</el-button>
        </div>
      </div>

      <div class="cf-panel__body">
        <!-- 工具栏 -->
        <div class="cf-filter-bar">
          <!-- 管理侧：按民警筛选 -->
          <el-select
            v-if="canViewAll"
            v-model="query.employeeId"
            placeholder="按民警筛选"
            clearable
            filterable
            style="width: 200px"
            @change="load"
          >
            <el-option
              v-for="e in employees"
              :key="e.employeeId"
              :label="e.name + (e.dept ? '（' + e.dept + '）' : '')"
              :value="e.employeeId"
            />
          </el-select>
          <el-input
            v-model="query.keyword"
            placeholder="搜索内容 / 案件名"
            clearable
            style="width: 220px"
            @keyup.enter="load"
            @clear="load"
          >
            <template #append>
              <el-button @click="load">搜索</el-button>
            </template>
          </el-input>
          <span class="cf-muted" style="font-size: 12px">共 {{ rows.length }} 条</span>
        </div>

        <!-- 列表 -->
        <el-table :data="rows" v-loading="loading" style="width: 100%">
          <el-table-column v-if="canViewAll" prop="employeeName" label="民警" width="120">
            <template #default="{ row }">{{ row.employeeName || '—' }}</template>
          </el-table-column>
          <el-table-column label="工作内容" min-width="320">
            <template #default="{ row }">
              <span class="cf-wlog-content">{{ row.content }}</span>
            </template>
          </el-table-column>
          <el-table-column label="关联案件" width="180">
            <template #default="{ row }">
              <el-tag v-if="row.caseName" size="small" type="info" effect="plain">{{ row.caseName }}</el-tag>
              <span v-else class="cf-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="记录时间" width="150">
            <template #default="{ row }">
              <span class="cf-wlog-time">{{ fmtTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="onRemove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!rows.length && !loading" class="cf-wlog-empty">
          <span class="cf-wlog-empty__icon">📝</span>
          <div>还没有工作记录</div>
          <div class="cf-muted">点右上角「新增记录」，记下今天做了什么</div>
        </div>
      </div>
    </div>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑记录' : '新增工作记录'" width="520px">
      <el-form label-width="80px">
        <el-form-item v-if="canViewAll && !editingId" label="归属民警">
          <el-select v-model="form.employeeId" placeholder="选择记录归属的民警" filterable style="width: 100%">
            <el-option
              v-for="e in employees"
              :key="e.employeeId"
              :label="e.name + (e.dept ? '（' + e.dept + '）' : '')"
              :value="e.employeeId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="工作内容">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="5"
            maxlength="1000"
            show-word-limit
            placeholder="今天做了什么，例如：赴现场勘查、讯问嫌疑人、整理卷宗……"
          />
        </el-form-item>
        <el-form-item label="关联案件">
          <el-input v-model="form.caseName" placeholder="可选，填写案件名称便于检索" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <PageFooter />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workLogApi } from '../api'
import { useUserStore } from '../store/user'
import PageFooter from '../components/PageFooter.vue'
import { dateKeyOf } from '../utils/format'

const userStore = useUserStore()
const canViewAll = computed(() => userStore.canViewAllCases)

const rows = ref([])
const employees = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref(null)

const query = reactive({ employeeId: null, keyword: '' })
const form = reactive({ employeeId: null, content: '', caseName: '' })

const WEEK = ['日', '一', '二', '三', '四', '五', '六']
const fmtTime = (t) => {
  if (!t) return '—'
  // 用字符串拆日期算星期，绕开 new Date('yyyy-MM-dd') 按 UTC 午夜解析的时区坑
  const key = dateKeyOf(t)
  if (!key) return '—'
  const [y, m, d] = key.split('-').map(Number)
  const week = WEEK[new Date(y, m - 1, d).getDay()]
  return key + '（周' + week + '）'
}

async function load() {
  loading.value = true
  try {
    const res = await workLogApi.list({
      employeeId: query.employeeId || undefined,
      keyword: query.keyword || undefined
    })
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function loadEmployees() {
  if (!canViewAll.value) return
  try {
    const res = await workLogApi.employees()
    employees.value = res.data || []
  } catch (e) {
    // 忽略
  }
}

function openAdd() {
  editingId.value = null
  form.employeeId = null
  form.content = ''
  form.caseName = ''
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.employeeId = row.employeeId
  form.content = row.content
  form.caseName = row.caseName || ''
  dialogVisible.value = true
}

async function save() {
  if (!form.content || !form.content.trim()) {
    ElMessage.warning('请填写工作内容')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await workLogApi.update(editingId.value, {
        content: form.content,
        caseId: form.caseId,
        caseName: form.caseName
      })
      ElMessage.success('已保存')
    } else {
      await workLogApi.add({
        employeeId: form.employeeId,
        content: form.content,
        caseName: form.caseName
      })
      ElMessage.success('已新增')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

function onRemove(row) {
  ElMessageBox.confirm(`确定删除这条记录吗？`, '删除确认', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  })
    .then(async () => {
      await workLogApi.remove(row.id)
      ElMessage.success('已删除')
      load()
    })
    .catch(() => {})
}

onMounted(() => {
  load()
  loadEmployees()
})
</script>

<style scoped>
/* 工作内容：允许换行显示，长内容不强制单行截断（比 show-overflow-tooltip 更易读，且符合全项目禁悬浮提示的约定） */
.cf-wlog-content {
  display: inline-block;
  max-width: 100%;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
}

/* 记录时间：日期 + 星期，等宽数字对齐 */
.cf-wlog-time {
  font-variant-numeric: tabular-nums;
  color: var(--cf-text-2);
}

/* 空状态：居中图标 + 主次两行文案 */
.cf-wlog-empty {
  padding: 48px 24px;
  text-align: center;
  color: var(--cf-text-2);
  line-height: 1.9;
}
.cf-wlog-empty__icon {
  display: block;
  font-size: 32px;
  margin-bottom: 8px;
  opacity: 0.6;
}
</style>
