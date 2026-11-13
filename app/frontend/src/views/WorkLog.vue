<template>
  <div class="cf-page">
    <div class="cf-panel">
      <div class="cf-panel__head">
        <span>工作记录本</span>
        <span class="cf-panel__head-tip">
          {{ canViewAll ? '可检索查看任意民警的工作记录' : '记录自己每天做了什么，仅本人可见' }}
          · 办案操作自动记入，手动记录可自行补充
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
          <el-table-column label="来源" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.source === 'AUTO'" size="small" type="success" effect="plain">自动</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">手动</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="工作内容" min-width="320">
            <template #default="{ row }">
              <span class="cf-wlog-content">{{ row.content }}</span>
            </template>
          </el-table-column>
          <el-table-column label="关联案件" width="180">
            <template #default="{ row }">
              <el-tag v-if="row.caseName" size="small" type="info" effect="plain" class="cf-wlog-case-tag">
                <a v-if="row.caseId" class="cf-wlog-case-link" @click="openCaseDetail(row)">{{ row.caseName }}</a>
                <span v-else>{{ row.caseName }}</span>
              </el-tag>
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
              <!-- 自动记录是系统派生的办案留痕，不可手动编辑/删除 -->
              <template v-if="row.source === 'AUTO'">
                <span class="cf-muted" style="font-size: 12px">系统记录</span>
              </template>
              <template v-else>
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="onRemove(row)">删除</el-button>
              </template>
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
          <el-select
            v-model="form.caseId"
            filterable
            remote
            clearable
            :remote-method="searchCases"
            :loading="caseSearching"
            @visible-change="onCaseVisible"
            placeholder="输入案件名 / 编号 / 类型 / 嫌疑人姓名检索"
            style="width: 100%"
          >
            <el-option
              v-for="c in caseOptions"
              :key="c.id"
              :label="caseLabel(c)"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 关联案件详情（点列表里的案件名打开） -->
    <CaseDetailDrawer v-model="caseDetailVisible" :case-id="caseDetailId" />

    <PageFooter />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workLogApi, caseApi } from '../api'
import { useUserStore } from '../store/user'
import PageFooter from '../components/PageFooter.vue'
import CaseDetailDrawer from '../components/CaseDetailDrawer.vue'
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
const form = reactive({ employeeId: null, content: '', caseId: null, caseName: '' })

// 关联案件：关键字远程联想下拉（选真实案件，关联 caseId）
const caseOptions = ref([])
const caseSearching = ref(false)
const caseDetailVisible = ref(false)
const caseDetailId = ref(null)

// 下拉 label：案件类型 · 案件名（编号），带截止时间，一眼可辨
const caseLabel = (c) => {
  const type = c.caseTypeName ? `${c.caseTypeName}·` : ''
  const no = c.filingNo ? `（${c.filingNo}）` : ''
  const due = c.deadlineText ? `｜截止 ${c.deadlineText}` : ''
  return `${type}${c.name}${no}${due}`
}

// 统一排序：截止时间从近到远（空截止时间排最后），二级按创建时间兜底
const sortCaseOptions = (list) => (list || []).sort((a, b) => {
  const da = a.deadline ? String(a.deadline) : '9999-12-31'
  const db = b.deadline ? String(b.deadline) : '9999-12-31'
  if (da !== db) return da < db ? -1 : 1
  const ca = String(a.createdAt || '')
  const cb = String(b.createdAt || '')
  return ca > cb ? -1 : 1
})

// 拼查询参数：民警只看本人经办；截止时间近→远
const caseQueryParams = (extra = {}) => {
  const params = { size: 50, sortField: 'deadline', sortOrder: 'asc', ...extra }
  if (!canViewAll.value) params.onlyMine = true
  return params
}

const searchCases = async (kw) => {
  const key = (kw || '').trim()
  // 空关键词：下拉展开时列出全部（管理）或本人经办（民警），按截止时间近→远
  if (!key) {
    await loadCases()
    return
  }
  caseSearching.value = true
  try {
    // 同一关键词既可能是案件名/类别，也可能是嫌疑人姓名，两条路并行搜再合并去重
    const [byKeyword, bySuspect] = await Promise.all([
      caseApi.page(caseQueryParams({ keyword: key })),
      caseApi.page(caseQueryParams({ suspectName: key }))
    ])
    const merged = new Map()
    ;[...(byKeyword?.list || []), ...(bySuspect?.list || [])].forEach((c) => {
      if (!merged.has(c.id)) merged.set(c.id, c)
    })
    caseOptions.value = sortCaseOptions(Array.from(merged.values()))
  } catch (e) {
    caseOptions.value = []
  } finally {
    caseSearching.value = false
  }
}

// 空关键词加载全量（或本人），供下拉展开即见列表
const loadCases = async () => {
  caseSearching.value = true
  try {
    const data = await caseApi.page(caseQueryParams())
    caseOptions.value = sortCaseOptions(data?.list || [])
  } catch (e) {
    caseOptions.value = []
  } finally {
    caseSearching.value = false
  }
}

// 下拉展开且还没选项时，加载一次全量列表
const onCaseVisible = (visible) => {
  if (visible && !caseOptions.value.length) loadCases()
}

const openCaseDetail = (row) => {
  if (!row.caseId) return
  caseDetailId.value = row.caseId
  caseDetailVisible.value = true
}

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
    // 拦截器已把 Result.data 解包，workLogApi.list 直接返回数组
    const rowsData = await workLogApi.list({
      employeeId: query.employeeId || undefined,
      keyword: query.keyword || undefined
    })
    rows.value = rowsData || []
  } finally {
    loading.value = false
  }
}

async function loadEmployees() {
  if (!canViewAll.value) return
  try {
    const emps = await workLogApi.employees()
    employees.value = emps || []
  } catch (e) {
    // 忽略
  }
}

function openAdd() {
  editingId.value = null
  form.employeeId = null
  form.content = ''
  form.caseId = null
  form.caseName = ''
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.employeeId = row.employeeId
  form.content = row.content
  form.caseId = row.caseId || null
  form.caseName = row.caseName || ''
  // 编辑时把当前关联案件塞进下拉候选，保证回显时能看到名字
  if (row.caseId && row.caseName) {
    caseOptions.value = [{ id: row.caseId, name: row.caseName, filingNo: row.filingNo || '' }]
  }
  dialogVisible.value = true
}

async function save() {
  if (!form.content || !form.content.trim()) {
    ElMessage.warning('请填写工作内容')
    return
  }
  // 从选中的案件里取名称（下拉选的是 caseId，名称冗余存一份便于列表展示）
  const chosen = form.caseId ? caseOptions.value.find((c) => c.id === form.caseId) : null
  const caseName = chosen ? chosen.name : (form.caseName || null)
  saving.value = true
  try {
    if (editingId.value) {
      await workLogApi.update(editingId.value, {
        content: form.content,
        caseId: form.caseId || null,
        caseName
      })
      ElMessage.success('已保存')
    } else {
      await workLogApi.add({
        employeeId: form.employeeId,
        content: form.content,
        caseId: form.caseId || null,
        caseName
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

/* 关联案件：可点击查看详情，加下划线提示可点 */
.cf-wlog-case-link {
  color: inherit;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.cf-wlog-case-link:hover {
  color: var(--cf-primary, #1b4a8c);
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
