<template>
  <div class="cf-page">
    <!-- 类型锁定提示条：明示"当前只看得见这一类"，避免用户以为筛漏了数据。
         按需求不在页内提供类型切换（那会破坏筛选约束），要换类型请用右上角「退出」。 -->
    <div class="cf-gatebar" :class="'is-' + (caseTypeStore.currentOption?.type || 'info')">
      <span class="cf-gatebar__tag">{{ caseTypeStore.currentOption?.label || '未选择' }}</span>
      <span>当前只看这一类案件，共 {{ total }} 件；筛选、标签与图表均限定在此类型内</span>
      <span class="cf-spacer"></span>
      <span class="cf-gatebar__tip">需更换类型请点右上角「退出」</span>
    </div>

    <div class="cf-toolbar">
      <el-input v-model="query.keyword" placeholder="案件名 / 编号 / 备注" clearable style="width: 220px" @keyup.enter="load" />
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px" @change="load">
        <el-option label="未办结" value="OPEN" />
        <el-option label="待指派" value="PENDING_ASSIGN" />
        <el-option label="已指派" value="ASSIGNED" />
        <el-option label="处理中" value="IN_PROGRESS" />
        <el-option label="已办结" value="DONE" />
        <el-option label="已撤销" value="CANCELLED" />
      </el-select>
      <el-select v-model="query.priority" placeholder="优先级" clearable style="width: 110px" @change="load">
        <el-option label="特急" value="URGENT" />
        <el-option label="紧急" value="HIGH" />
        <el-option label="普通" value="NORMAL" />
        <el-option label="低" value="LOW" />
      </el-select>
      <el-select v-model="query.sourceType" placeholder="来源" clearable style="width: 110px" @change="load">
        <el-option label="手工录入" value="MANUAL" />
        <el-option label="PDF" value="PDF" />
        <el-option label="Word" value="WORD" />
        <el-option label="Excel" value="EXCEL" />
      </el-select>
      <el-cascader
        v-model="cascadeFilter"
        :options="categoryStore.tree"
        :props="{ checkStrictly: true }"
        placeholder="案件分类"
        clearable
        filterable
        style="width: 170px"
      />
      <el-select
        v-model="query.employeeId"
        filterable
        remote
        clearable
        reserve-keyword
        placeholder="经办人姓名"
        :remote-method="searchEmployee"
        :loading="empLoading"
        style="width: 150px"
        @change="onEmployeePick"
      >
        <el-option v-for="e in employeeOptions" :key="e.id" :label="e.name" :value="e.id" />
      </el-select>
      <el-select v-model="query.hasSuspect" placeholder="嫌疑人" clearable style="width: 110px" @change="load">
        <el-option label="有嫌疑人" value="YES" />
        <el-option label="无嫌疑人" value="NO" />
      </el-select>
      <el-select v-model="query.dueBucket" placeholder="到期" clearable style="width: 130px" @change="load">
        <el-option label="已逾期" value="OVERDUE" />
        <el-option label="今天到期" value="TODAY" />
        <el-option label="3天内" value="D3" />
        <el-option label="7天内" value="D7" />
        <el-option label="未设期限" value="NONE" />
      </el-select>
      <el-button @click="reset">重置</el-button>
      <span class="cf-spacer" />
      <el-button type="primary" @click="onCreate">新建案件</el-button>
    </div>

    <!-- 可视化：随筛选条件联动统计 -->
    <el-row :gutter="12">
      <el-col :span="14" :xs="24">
        <ChartPanel title="近 14 天案件趋势" :empty="!trendData.length">
          <template #tools>
            <el-select v-model="trendType" size="small">
              <el-option label="折线图" value="line" />
              <el-option label="柱状图" value="bar" />
            </el-select>
            <el-button size="small" @click="loadStats">刷新</el-button>
          </template>
          <EChart :option="trendOption" :height="isMobile ? 170 : 200" />
        </ChartPanel>
      </el-col>
      <el-col :span="10" :xs="24">
        <ChartPanel title="维度分布（点击柱体即按该值筛选）" :empty="!distData.length">
          <template #tools>
            <el-select v-model="distDim" size="small">
              <el-option label="状态" value="statusDist" />
              <el-option label="优先级" value="priorityDist" />
              <el-option label="来源" value="sourceDist" />
              <el-option label="案卷类型" value="caseTypeDist" />
              <el-option label="案件类别" value="categoryDist" />
            </el-select>
            <el-select v-model="distType" size="small">
              <el-option label="柱状图" value="bar" />
              <el-option label="折线图" value="line" />
            </el-select>
          </template>
          <EChart :option="distOption" :height="isMobile ? 170 : 200" @click="onDistClick" />
        </ChartPanel>
      </el-col>
    </el-row>

    <div class="cf-panel" style="padding: 4px">
      <CaseTable
        :rows="rows"
        :loading="loading"
        :min-body="isMobile ? 0 : 360"
        show-case-no
        @open="openDetail"
        @assign="openAssign"
        @remove="onRemove"
      />
      <div class="cf-pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load"
        />
      </div>
    </div>

    <CaseFormDialog v-model="formVisible" :case-id="editId" @done="load" />
    <AssignDialog
      v-model="assignVisible"
      :case-id="currentId"
      :case-name="currentName"
      :current-owner-id="currentOwnerId"
      :current-member-ids="currentMemberIds"
      :current-deadline="currentDeadline"
      @done="load"
    />
    <CaseDetailDrawer v-model="detailVisible" :case-id="currentId" @done="load" />

    <PageFooter />
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { caseApi, employeeApi } from '../api'
import CaseTable from '../components/CaseTable.vue'
import CaseFormDialog from '../components/CaseFormDialog.vue'
import AssignDialog from '../components/AssignDialog.vue'
import CaseDetailDrawer from '../components/CaseDetailDrawer.vue'
import ChartPanel from '../components/ChartPanel.vue'
import EChart from '../components/EChart.vue'
import { CHART, lineOption, barOption } from '../utils/chart'
import { useDevice } from '../utils/device'
import { useCategoryStore } from '../store/category'
import { withCaseType, useCaseTypeStore } from '../store/caseType'
import PageFooter from '../components/PageFooter.vue'

const route = useRoute()
const { isMobile } = useDevice()
const categoryStore = useCategoryStore()
const caseTypeStore = useCaseTypeStore()
const rows = ref([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  status: '',
  priority: '',
  sourceType: '',
  caseType: '',
  category: '',
  hasSuspect: '',
  employeeId: null,
  dueBucket: ''
})

// 经办人姓名远程搜索：从员工图谱按姓名联想，选中即过滤该民警经办的案件
const employeeOptions = ref([])
const empLoading = ref(false)
const searchEmployee = async (kw) => {
  if (!kw) {
    employeeOptions.value = []
    return
  }
  empLoading.value = true
  try {
    employeeOptions.value = await employeeApi.search({ keyword: kw, limit: 30 })
  } finally {
    empLoading.value = false
  }
}
const onEmployeePick = (id) => {
  query.employeeId = id || null
  query.page = 1
  load()
}

// 案件分类级联（大类 -> 小类逐层选择）；query.caseType/category 由级联路径拆出
const cascadeFilter = ref([])
watch(cascadeFilter, (val) => {
  query.caseType = val?.[0] || ''
  query.category = val?.[1] || ''
  query.page = 1
  load()
})
// 把 query 里的分类条件反投影回级联路径（统计柱体点击时用）
const syncCascade = () => {
  if (query.caseType && query.category) cascadeFilter.value = [query.caseType, query.category]
  else if (query.caseType) cascadeFilter.value = [query.caseType]
  else if (query.category) {
    const t = categoryStore.typeOfCategory(query.category)
    cascadeFilter.value = t ? [t, query.category] : []
  } else cascadeFilter.value = []
}

const formVisible = ref(false)
const assignVisible = ref(false)
const detailVisible = ref(false)
const editId = ref(null)
const currentId = ref(null)
const currentName = ref('')
const currentOwnerId = ref(null)
const currentMemberIds = ref([])
const currentDeadline = ref(null)

const loading = ref(false)
const load = async () => {
  loading.value = true
  try {
    // 混入当前案件类型（统一入口门控）：列表与下方图表必须同一口径，
    // 否则会出现"列表是刑事、趋势图是行政"这种看不懂的错位
    const params = withCaseType(query)
    Object.keys(params).forEach((k) => {
      if (params[k] === '' || params[k] == null) delete params[k]
    })
    const data = await caseApi.page(params)
    rows.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

// ---- 图表（同样锁定类型） ----
const stats = ref({})
const trendType = ref('line')
const distDim = ref('statusDist')
const distType = ref('bar')
const loadStats = async () => {
  stats.value = await caseApi.stats(withCaseType({ days: 14 }))
}

const trendData = computed(() => stats.value.trend || [])
const distData = computed(() => stats.value[distDim.value] || [])

const trendOption = computed(() => {
  const cats = trendData.value.map((p) => (p.date || '').slice(5))
  const series = [
    { name: '新增', data: trendData.value.map((p) => p.created) },
    { name: '办结', data: trendData.value.map((p) => p.done), color: CHART.gold }
  ]
  return trendType.value === 'bar'
    ? barOption({ categories: cats, series, narrow: isMobile.value })
    : lineOption({ categories: cats, series, area: true, narrow: isMobile.value })
})

const DIST_COLOR = {
  PENDING_ASSIGN: CHART.danger,
  ASSIGNED: CHART.warn,
  IN_PROGRESS: CHART.primary,
  DONE: CHART.ok,
  CANCELLED: CHART.text3,
  URGENT: CHART.danger,
  HIGH: CHART.warn,
  NORMAL: CHART.primary,
  LOW: CHART.text3,
  MANUAL: CHART.primary,
  PDF: CHART.danger,
  WORD: CHART.primaryLight,
  EXCEL: CHART.ok,
  PRELIMINARY: CHART.text3,
  CRIMINAL: CHART.danger,
  ADMINISTRATIVE: CHART.warn,
  CIVIL: CHART.ok
}
const distOption = computed(() => {
  const cats = distData.value.map((d) => d.name)
  const series = [{ name: '案件数', data: distData.value.map((d) => d.value) }]
  const colors = [distData.value.map((d) => DIST_COLOR[d.code] || CHART.primary)]
  return distType.value === 'line'
    ? lineOption({ categories: cats, series, narrow: isMobile.value })
    : barOption({ categories: cats, series, colors, narrow: isMobile.value })
})

// 点柱体 -> 直接把该值填进上方筛选条件并刷新列表
// 案件类别是自由文本，没有固定色板，统一用警蓝；点柱体仍按该类别筛选
const DIM_TO_QUERY = {
  statusDist: 'status',
  priorityDist: 'priority',
  sourceDist: 'sourceType',
  caseTypeDist: 'caseType',
  categoryDist: 'category'
}
const onDistClick = (p) => {
  const item = distData.value[p.dataIndex]
  if (!item) return
  const key = DIM_TO_QUERY[distDim.value]
  query[key] = query[key] === item.code ? '' : item.code
  query.page = 1
  // 分类相关柱体点击后，把条件同步回级联选择器的显示
  if (key === 'caseType' || key === 'category') syncCascade()
  load()
}

const reset = () => {
  Object.assign(query, {
    page: 1,
    keyword: '',
    status: '',
    priority: '',
    sourceType: '',
    caseType: '',
    category: '',
    hasSuspect: '',
    employeeId: null,
    dueBucket: ''
  })
  cascadeFilter.value = []
  load()
}

const onCreate = () => {
  editId.value = null
  formVisible.value = true
}

const openDetail = (row) => {
  currentId.value = row.id
  detailVisible.value = true
}

const openAssign = async (row) => {
  currentId.value = row.id
  currentName.value = row.name
  currentOwnerId.value = row.owner ? row.owner.employeeId : null
  currentMemberIds.value = (row.members || []).map((m) => m.employeeId)
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
  if (!q) return
  if (q.status) {
    query.status = q.status
    load()
  }
  // 工作台「承办人在手负载」点柱体跳过来：按经办人过滤并回填姓名到下拉
  if (q.employeeId) {
    const id = Number(q.employeeId)
    query.employeeId = id
    if (q.employeeName) employeeOptions.value = [{ id, name: q.employeeName }]
    load()
  }
}, { immediate: true })

onMounted(async () => {
  await categoryStore.load()
  await load()
  await loadStats()
})
</script>
