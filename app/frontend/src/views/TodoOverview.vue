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
          <el-button size="small" @click="load">刷新</el-button>
        </div>

        <!-- 桌面：表格 -->
        <div v-if="!isMobile" class="cf-tscroll">
          <el-table :data="filtered" v-loading="loading" size="small" row-key="id">
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

        <!-- 移动端：卡片列表，避免横向滚动 -->
        <div v-else class="cf-card-list" v-loading="loading">
          <div v-for="row in filtered" :key="row.id" class="cf-todo-card">
            <div class="cf-todo-card__head">
              <span class="cf-cell-strong">{{ row.caseName || '-' }}</span>
              <el-tag :type="row.status === 'DONE' ? 'success' : 'warning'" size="small" effect="plain">
                {{ row.statusName }}
              </el-tag>
            </div>
            <div class="cf-todo-card__content">{{ row.content }}</div>
            <div class="cf-todo-card__meta">
              <span class="cf-muted">{{ row.caseNo || '-' }}</span>
              <span v-if="row.status === 'DONE'" class="cf-muted">{{ row.doneByName }} · {{ fmtTime(row.doneAt) }}</span>
            </div>
            <div class="cf-todo-card__files">
              <template v-if="row.evidence && row.evidence.length">
                <a v-for="f in row.evidence" :key="f.id" class="cf-evidence-item__name" @click="download(f)">
                  {{ f.fileName }}
                </a>
              </template>
              <span v-else class="cf-danger">无佐证材料</span>
            </div>
          </div>
          <el-empty v-if="!filtered.length" :image-size="60" description="暂无待办" />
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
import { useDevice } from '../utils/device'

const { isMobile } = useDevice()

const list = ref([])
const summary = ref({})
const status = ref('')
const keyword = ref('')
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
    // 锁定当前案件类型：卡片汇总与下方列表必须同口径
    list.value = await todoApi.overview({ status: status.value || undefined, ...withCaseType() })
    summary.value = await todoApi.summary(withCaseType())
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
