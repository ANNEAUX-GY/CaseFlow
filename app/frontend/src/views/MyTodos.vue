<template>
  <div class="cf-page">
    <div class="cf-panel">
      <div class="cf-panel__head">
        <span>我的待办</span>
        <span class="cf-panel__head-tip">来自领导意见，逐项落实；点标题可跳到对应案件</span>
        <span class="cf-spacer"></span>
        <span v-if="list.length" class="cf-muted">共 {{ list.length }} 条</span>
        <el-button size="small" @click="load">刷新</el-button>
      </div>

      <div class="cf-panel__body">
        <!-- 筛选 + 排序：排序方向可切换，点同一列在升/降之间轮换 -->
        <div class="cf-toolbar" style="margin-bottom: 10px">
          <el-input v-model="query.keyword" placeholder="搜索任务标题" clearable
            style="width: 200px" @keyup.enter="load" />
          <el-select v-model="query.status" placeholder="状态" clearable style="width: 110px" @change="load">
            <el-option label="待办" value="PENDING" />
            <el-option label="已完成" value="DONE" />
          </el-select>

          <span class="cf-spacer"></span>

          <!-- 紧急程度排序：点击切换方向 -->
          <span class="cf-sort">
            <span class="cf-sort__label">紧急程度</span>
            <el-button link type="primary" size="small" class="cf-sort__btn" @click="toggleSort('urgency')">
              {{ URGENCY_META.URGENT.label }}优先
              <span class="cf-sort__arrow">{{ query.urgencyOrder === 'asc' ? '↑' : '↓' }}</span>
            </el-button>
          </span>
          <span class="cf-sort">
            <span class="cf-sort__label">重点程度</span>
            <el-button link type="primary" size="small" class="cf-sort__btn" @click="toggleSort('importance')">
              {{ TODO_IMPORTANCE_META.KEY.label }}优先
              <span class="cf-sort__arrow">{{ query.importanceOrder === 'asc' ? '↑' : '↓' }}</span>
            </el-button>
          </span>
          <el-button v-if="sortChanged" link type="info" size="small" @click="resetSort">
            恢复默认排序
          </el-button>
        </div>

        <!-- 当前生效的排序规则说明：多字段组合时让人看得懂在按什么排 -->
        <div v-if="sortByFields.length" class="cf-sortdesc">
          当前排序：{{ sortDescText }}
        </div>

        <!-- 列表 -->
        <el-table v-loading="loading" :data="list" style="width: 100%"
          :row-class-name="rowClass" @row-click="onRowClick">
          <el-table-column label="任务标题" min-width="260">
            <template #default="{ row }">
              <div class="cf-mtodo__title" :class="{ 'is-done': row.status === 'DONE' }">
                {{ row.content }}
              </div>
              <div v-if="row.caseNo" class="cf-mtodo__case">{{ row.caseNo }} · {{ row.caseName }}</div>
            </template>
          </el-table-column>

          <el-table-column label="所属部门/来源" width="130">
            <template #default="{ row }">
              <span :class="{ 'cf-muted': !row.deptSource }">{{ row.deptSource || '—' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="紧急程度" width="96">
            <template #default="{ row }">
              <!-- 分级由领导/管理员统一设定（2026-10 收紧）：管理层可就地切换，普通用户只读标签 -->
              <el-select v-if="canEditGrade" :model-value="todoUrgencyOf(row)" size="small" class="cf-mtodo__grade"
                @click.stop @change="(v) => setGrade(row, { urgency: v })">
                <el-option v-for="(m, k) in URGENCY_META" :key="k" :label="m.label" :value="k" />
              </el-select>
              <el-tag v-else size="small" effect="plain"
                :type="URGENCY_META[todoUrgencyOf(row)].type">
                {{ URGENCY_META[todoUrgencyOf(row)].label }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="重点程度" width="96">
            <template #default="{ row }">
              <el-select v-if="canEditGrade" :model-value="todoImportanceOf(row)" size="small" class="cf-mtodo__grade"
                @click.stop @change="(v) => setGrade(row, { importance: v })">
                <el-option v-for="(m, k) in TODO_IMPORTANCE_META" :key="k" :label="m.label" :value="k" />
              </el-select>
              <el-tag v-else size="small" effect="plain"
                :type="TODO_IMPORTANCE_META[todoImportanceOf(row)].type">
                {{ TODO_IMPORTANCE_META[todoImportanceOf(row)].label }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="截止时间" width="180">
            <template #default="{ row }">
              <span v-if="todoDueTextOf(row).text" :class="['cf-mtodo__due', todoDueTextOf(row).cls]">
                {{ todoDueTextOf(row).text }}
              </span>
              <span v-else class="cf-muted">无期限</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="88">
            <template #default="{ row }">
              <el-tag size="small" :type="(TODO_STATUS_META[row.status] || {}).type" effect="plain">
                {{ (TODO_STATUS_META[row.status] || {}).label || row.status }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>

        <!-- 空状态：区分「没有待办」和「筛选后无结果」 -->
        <div v-if="!loading && !list.length" class="cf-mtodo__empty">
          <template v-if="hasFilter">
            <div class="cf-mtodo__empty-title">没有符合条件的待办</div>
            <div class="cf-muted">试试清空筛选条件或切换排序方式</div>
            <el-button size="small" style="margin-top: 10px" @click="clearFilter">清空筛选</el-button>
          </template>
          <template v-else>
            <div class="cf-mtodo__empty-title">暂无待办事项</div>
            <div class="cf-muted">领导在案件盯办中提出意见后，会自动出现在这里</div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { todoApi } from '../api'
import { useMyTodoStore } from '../store/myTodo'
import { useUserStore } from '../store/user'
import {
  TODO_URGENCY_META as URGENCY_META,
  TODO_IMPORTANCE_META,
  TODO_STATUS_META,
  todoUrgencyOf,
  todoImportanceOf,
  todoDueTextOf
} from '../utils/format'

const route = useRoute()
const router = useRouter()
// 改了分级会影响侧栏红点的口径，操作后刷新
const myTodoStore = useMyTodoStore()
// 分级由领导/管理员设定（2026-10 收紧）：普通用户只读展示
const canEditGrade = computed(() => useUserStore().isFullAccess)

const list = ref([])
const loading = ref(false)

/** 默认排序：紧急降序 → 重点降序 → 截止升序（与后端 sortTodos 一致） */
const DEFAULT_SORT = 'urgency,importance,deadline'

const query = reactive({
  keyword: '',
  status: '',
  sortBy: DEFAULT_SORT,
  urgencyOrder: 'desc',
  importanceOrder: 'desc'
})

const sortByFields = computed(() => (query.sortBy || '').split(',').filter(Boolean))

const sortDescText = computed(() => {
  const parts = []
  if (sortByFields.value.includes('urgency')) {
    parts.push('紧急程度 ' + (query.urgencyOrder === 'asc' ? '低→高' : '高→低'))
  }
  if (sortByFields.value.includes('importance')) {
    parts.push('重点程度 ' + (query.importanceOrder === 'asc' ? '低→高' : '高→低'))
  }
  if (sortByFields.value.includes('deadline')) parts.push('截止时间 近→远')
  return parts.join(' › ') || '默认'
})

const sortChanged = computed(() =>
  query.sortBy !== DEFAULT_SORT || query.urgencyOrder !== 'desc' || query.importanceOrder !== 'desc')

const hasFilter = computed(() => !!(query.keyword || query.status))

/** 切换某字段的排序方向；再次点击同一列则反向 */
const toggleSort = (field) => {
  const orderKey = field === 'urgency' ? 'urgencyOrder' : 'importanceOrder'
  query[orderKey] = query[orderKey] === 'asc' ? 'desc' : 'asc'
  load()
}

const resetSort = () => {
  query.sortBy = DEFAULT_SORT
  query.urgencyOrder = 'desc'
  query.importanceOrder = 'desc'
  load()
}

const clearFilter = () => {
  query.keyword = ''
  query.status = ''
  load()
}

const load = async () => {
  loading.value = true
  try {
    const params = {}
    if (query.keyword) params.keyword = query.keyword
    if (query.status) params.status = query.status
    if (query.sortBy) params.sortBy = query.sortBy
    if (query.urgencyOrder) params.urgencyOrder = query.urgencyOrder
    if (query.importanceOrder) params.importanceOrder = query.importanceOrder
    list.value = await todoApi.myTodos(params) || []
  } catch (e) {
    list.value = []
  } finally {
    loading.value = false
  }
}

/** 管理层调整待办的紧急/重点程度（普通用户列已置为只读标签） */
const setGrade = async (row, patch) => {
  const payload = {
    urgency: patch.urgency !== undefined ? patch.urgency : todoUrgencyOf(row),
    importance: patch.importance !== undefined ? patch.importance : todoImportanceOf(row)
  }
  try {
    await todoApi.updateMyTodoGrade(row.id, payload)
    await load()
    myTodoStore.refresh()
  } catch (e) {
    ElMessage.error('保存失败')
    await load()
  }
}

const rowClass = ({ row }) => (todoDueTextOf(row).overdue ? 'cf-mtodo__row-overdue' : '')

/** 点行跳到对应案件详情（普通民警在案件管理里找该案件） */
const onRowClick = (row) => {
  if (row.caseId) router.push({ path: '/my-cases', query: { caseId: row.caseId } })
}

// 支持从欢迎弹窗跳进来时带筛选条件（如 status=DUE_SOON）
watch(() => route.query, (q) => {
  if (!q) return
  if (q.status) query.status = String(q.status)
  if (q.sortBy) query.sortBy = String(q.sortBy)
  load()
}, { immediate: true })
</script>

<style>
.cf-mtodo__title { font-size: 13px; color: #1b2430; line-height: 1.5 }
.cf-mtodo__title.is-done { color: #8a929e; text-decoration: line-through }
.cf-mtodo__case { font-size: 12px; color: #8a929e; margin-top: 2px }
.cf-mtodo__grade { width: 84px }
.cf-mtodo__due { font-size: 12px; color: #5a6472 }
.cf-mtodo__due.is-today { color: #d98a0b; font-weight: 600 }
.cf-mtodo__due.is-soon { color: #d98a0b }
.cf-mtodo__due.is-overdue { color: #c62a2a; font-weight: 600 }
.cf-mtodo__row-overdue { background: #fdf6f6 }
.cf-mtodo__empty { padding: 40px 0; text-align: center }
.cf-mtodo__empty-title { font-size: 14px; color: #5a6472; margin-bottom: 6px }
.cf-sort { display: inline-flex; align-items: center; gap: 4px }
.cf-sort__label { font-size: 12px; color: #8a929e }
.cf-sort__btn { font-size: 12px !important }
.cf-sort__arrow { font-weight: 700; margin-left: 2px }
.cf-sortdesc {
  font-size: 12px; color: #1b4a8c; background: #eef3fa;
  border-radius: 3px; padding: 4px 8px; margin-bottom: 10px;
}
</style>
