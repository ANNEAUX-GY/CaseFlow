<template>
  <div class="cf-page">
    <div class="cf-panel">
      <div class="cf-panel__head">
        <span>待办总览</span>
        <span class="cf-panel__head-tip">查看各案件待办的完成状态与对应佐证材料</span>
      </div>

      <div class="cf-panel__body">
        <!-- 汇总数字 -->
        <div class="cf-todo-stats">
          <div class="cf-todo-stat">
            <span class="cf-todo-stat__num">{{ summary.total || 0 }}</span>
            <span class="cf-todo-stat__label">待办总数</span>
          </div>
          <div class="cf-todo-stat is-ok">
            <span class="cf-todo-stat__num">{{ summary.done || 0 }}</span>
            <span class="cf-todo-stat__label">已完成</span>
          </div>
          <div class="cf-todo-stat is-warn">
            <span class="cf-todo-stat__num">{{ summary.pending || 0 }}</span>
            <span class="cf-todo-stat__label">待完成</span>
          </div>
          <div class="cf-todo-stat is-danger">
            <span class="cf-todo-stat__num">{{ summary.doneNoEvidence || 0 }}</span>
            <span class="cf-todo-stat__label">已完成但无佐证</span>
          </div>
        </div>

        <!-- 筛选 -->
        <div class="cf-filter-bar">
          <el-radio-group v-model="status" size="small" @change="load">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="PENDING">待完成</el-radio-button>
            <el-radio-button value="DONE">已完成</el-radio-button>
          </el-radio-group>
          <el-input
            v-model="keyword"
            size="small"
            clearable
            placeholder="搜索案件编号 / 名称 / 待办内容"
            class="cf-filter-bar__search"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <!-- 只看重点（2026-10-10）：与案件管理/盯办同一个勾选框。
               跟星标列一样按权限收敛——普通民警看不到星标，给了也是永远筛空的 -->
          <el-checkbox v-if="canFocus" v-model="focusOnly" size="small" style="margin: 0 2px" @change="load">
            只看重点
          </el-checkbox>
          <el-button size="small" @click="load">刷新</el-button>
        </div>

        <div class="cf-tscroll">
          <el-table :data="filtered" v-loading="loading" size="small" row-key="id"
            :row-class-name="({ row }) => (row.caseFocus === 1 ? 'cf-row--focus' : '')">
            <!-- 一键重点关注：待办总览按待办列行，星标打的是背后的案件
                 （FocusStar 的 idProp/focusProp 指到 caseId / caseFocus） -->
            <el-table-column v-if="canFocus" label="重点" width="60" align="center">
              <template #default="{ row }">
                <FocusStar :row="row" id-prop="caseId" focus-prop="caseFocus" />
              </template>
            </el-table-column>
            <el-table-column label="案件" min-width="220">
              <template #default="{ row }">
                <div class="cf-cell-strong">{{ row.caseName || '-' }}</div>
                <div class="cf-muted">{{ row.caseNo || '-' }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="content" label="待办内容" min-width="220" show-overflow-tooltip />
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.status === 'DONE' ? 'success' : 'warning'" size="small" effect="plain">
                  {{ row.statusName }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="完成情况" width="190">
              <template #default="{ row }">
                <template v-if="row.status === 'DONE'">
                  <div>{{ row.doneByName || '-' }}</div>
                  <div class="cf-muted">{{ fmtTime(row.doneAt) }}</div>
                </template>
                <span v-else class="cf-muted">-</span>
              </template>
            </el-table-column>
            <el-table-column label="佐证材料" min-width="260">
              <template #default="{ row }">
                <div v-if="row.evidence && row.evidence.length" class="cf-evidence-list">
                  <div v-for="f in row.evidence" :key="f.id" class="cf-evidence-item">
                    <a class="cf-evidence-item__name" @click="download(f)">{{ f.fileName }}</a>
                    <span class="cf-muted">{{ f.sizeText }} · {{ f.uploadedByName || '-' }} · {{ fmtTime(f.uploadedAt) }}</span>
                  </div>
                </div>
                <span v-else class="cf-danger">未上传</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { fileApi, todoApi } from '../api'
import { withCaseType } from '../store/caseType'
import { useUserStore } from '../store/user'
import FocusStar from '../components/FocusStar.vue'

// 与盯办一致：标注重点是管理端能力，普通民警不渲染星标
const userStore = useUserStore()
const canFocus = computed(() => userStore.isFullAccess)


const list = ref([])
const summary = ref({})
const status = ref('')
const keyword = ref('')
// 只看重点（2026-10-10）：星标打在案件上，这里筛出「重点案件名下的待办」
const focusOnly = ref(false)
const loading = ref(false)

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter((t) =>
    [t.caseName, t.caseNo, t.content].some((v) => (v || '').toLowerCase().includes(kw))
  )
})

const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '-')

const load = async () => {
  loading.value = true
  try {
    // 锁定当前案件类型 + 只看重点：卡片汇总与下方列表必须同口径，
    // 否则勾了「只看重点」会出现"卡片 21 件、列表 2 条"的对不上
    const base = { focusOnly: focusOnly.value || undefined, ...withCaseType() }
    list.value = await todoApi.overview({ status: status.value || undefined, ...base })
    summary.value = await todoApi.summary(base)
  } finally {
    loading.value = false
  }
}

const download = (f) => {
  const token = localStorage.getItem('cf_token') || ''
  window.open(`${fileApi.downloadUrl(f.id)}?token=${encodeURIComponent(token)}`, '_blank')
}

onMounted(load)
</script>
