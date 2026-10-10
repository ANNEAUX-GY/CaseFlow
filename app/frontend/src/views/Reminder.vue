<template>
  <div class="cf-page">
    <el-tabs v-model="bucket" @tab-change="load">
      <el-tab-pane label="已逾期" name="OVERDUE" />
      <el-tab-pane label="今天到期" name="TODAY" />
      <el-tab-pane label="3天内" name="D3" />
      <el-tab-pane label="7天内" name="D7" />
      <el-tab-pane label="未设期限" name="NONE" />
    </el-tabs>

    <!-- 只看重点（2026-10-10）：四个栏目（案件管理 / 案件盯办 / 待办总览 / 到期提醒）
         都要能把标过星的重点案件筛出来。跟星标列同一条件按权限收敛 -->
    <div v-if="canFocus" style="margin: -6px 0 8px">
      <el-checkbox v-model="focusOnly" @change="load">只看重点</el-checkbox>
      <span v-if="focusOnly" class="cf-muted" style="margin-left: 8px">仅显示已标为重点的案件</span>
    </div>

    <!-- 可视化：账龄 + 未来 7 天到期量 -->
    <el-row :gutter="12">
      <el-col :span="10" :xs="24">
        <ChartPanel title="逾期账龄分布（点击切换上方分桶）" :empty="!ageData.length">
          <template #tools>
            <el-select v-model="ageType" size="small">
              <el-option label="柱状图" value="bar" />
              <el-option label="折线图" value="line" />
            </el-select>
          </template>
          <EChart :option="ageOption" :height="200" @click="onAgeClick" />
        </ChartPanel>
      </el-col>
      <el-col :span="14" :xs="24">
        <ChartPanel title="未来 7 天到期量（含逾期积压）" :empty="!upcomingData.length">
          <template #tools>
            <el-select v-model="upcomingType" size="small">
              <el-option label="折线图" value="line" />
              <el-option label="柱状图" value="bar" />
            </el-select>
            <el-button size="small" @click="loadStats">刷新</el-button>
          </template>
          <EChart :option="upcomingOption" :height="200" @click="onUpcomingClick" />
        </ChartPanel>
      </el-col>
    </el-row>

    <div class="cf-panel" style="padding: 4px">
      <CaseTable
        :rows="rows"
        :loading="loading"
        :min-body="300"
        show-case-no
        @open="openDetail"
        @assign="openAssign"
        @remove="onRemove"
      />
      <div class="cf-muted" style="padding: 8px; border-top: 1px solid var(--cf-border)">
        共 {{ rows.length }} 条 · 按截止时间升序排列（双击行查看详情）
      </div>
    </div>

    <AssignDialog
      v-model="assignVisible"
      :case-id="currentId"
      :case-name="currentName"
      :current-deadline="currentDeadline"
      @done="load"
    />
    <CaseDetailDrawer v-model="detailVisible" :case-id="currentId" @done="load" />

    <PageFooter />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { caseApi } from '../api'
import { withCaseType } from '../store/caseType'
import { useUserStore } from '../store/user'
import CaseTable from '../components/CaseTable.vue'
import AssignDialog from '../components/AssignDialog.vue'
import CaseDetailDrawer from '../components/CaseDetailDrawer.vue'
import ChartPanel from '../components/ChartPanel.vue'
import EChart from '../components/EChart.vue'
import { CHART, lineOption, barOption } from '../utils/chart'
import PageFooter from '../components/PageFooter.vue'

const route = useRoute()
// 与盯办/待办总览同一判据：重点筛选只给管理端（后端 /cases/{id}/focus 是 @FullAccessOnly，
// 普通民警察看不到星标列，给他一个永远筛空的勾选框只是添乱）
const userStore = useUserStore()
const canFocus = computed(() => userStore.isFullAccess)
const bucket = ref('OVERDUE')
const rows = ref([])
const assignVisible = ref(false)
const detailVisible = ref(false)
const currentId = ref(null)
const currentName = ref('')
const currentDeadline = ref(null)

const loading = ref(false)
// 只看重点（2026-10-10）：与案件管理同一个字段，后端走同一个 page() 查询，口径天然一致
const focusOnly = ref(false)
const load = async () => {
  loading.value = true
  try {
    // 锁定当前案件类型：提醒清单与下方图表同口径
    rows.value = await caseApi.reminders(withCaseType({
      bucket: bucket.value,
      limit: 100,
      focusOnly: focusOnly.value || undefined
    }))
  } finally {
    loading.value = false
  }
}

// ---- 图表 ----
const stats = ref({})
const ageType = ref('bar')
const upcomingType = ref('line')
const loadStats = async () => {
  stats.value = await caseApi.stats(withCaseType({ days: 14 }))
}

const ageData = computed(() => stats.value.overdueAgeDist || [])
const upcomingData = computed(() => stats.value.upcoming || [])

const AGE_COLOR = [CHART.warn, CHART.warn, CHART.danger, CHART.danger]
const ageOption = computed(() => {
  const cats = ageData.value.map((d) => d.name)
  const series = [{ name: '案件数', data: ageData.value.map((d) => d.value) }]
  return ageType.value === 'line'
    ? lineOption({ categories: cats, series, narrow: false })
    : barOption({ categories: cats, series, colors: [AGE_COLOR], narrow: false })
})

const upcomingOption = computed(() => {
  const cats = upcomingData.value.map((p) => (p.date || '').length > 8 ? p.date.slice(5) : p.date)
  const series = [{ name: '到期案件', data: upcomingData.value.map((p) => p.created) }]
  return upcomingType.value === 'bar'
    ? barOption({
        categories: cats,
        series,
        colors: [upcomingData.value.map((p) => (p.date === '已逾期' ? CHART.danger : CHART.primary))],
        narrow: false
      })
    : lineOption({ categories: cats, series, area: true, narrow: false })
})

// 点账龄柱体 -> 切到「已逾期」清单；点未来日期 -> 切到对应分桶
const onAgeClick = () => {
  bucket.value = 'OVERDUE'
  load()
}
const onUpcomingClick = (p) => {
  const item = upcomingData.value[p.dataIndex]
  if (!item) return
  bucket.value = item.date === '已逾期' ? 'OVERDUE' : 'D7'
  load()
}

const openDetail = (row) => {
  currentId.value = row.id
  detailVisible.value = true
}
const openAssign = (row) => {
  currentId.value = row.id
  currentName.value = row.name
  currentDeadline.value = row.deadline || null
  assignVisible.value = true
}
const onRemove = async (row) => {
  await ElMessageBox.confirm(`确认删除案件「${row.name}」？`, '提示', { type: 'warning' })
  await caseApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

watch(() => route.query, (q) => {
  if (q && q.bucket) {
    bucket.value = q.bucket
    load()
  }
}, { immediate: true })

onMounted(async () => {
  await load()
  await loadStats()
})
</script>
