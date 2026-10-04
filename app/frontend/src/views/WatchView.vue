<template>
  <div class="cf-page">
    <!-- 类型锁定提示条：看板四组卡片与三子模块列表都只统计这一类案件 -->
    <div class="cf-gatebar" :class="'is-' + (caseTypeStore.currentOption?.type || 'info')">
      <span class="cf-gatebar__tag">{{ caseTypeStore.currentOption?.label || '未选择' }}</span>
      <span>当前只看这一类案件；看板计数、页签列表与检索均限定在此类型内</span>
      <span class="cf-spacer"></span>
      <span class="cf-gatebar__tip">需更换类型请用顶部类型条的「退出类型」</span>
    </div>

    <!-- 盯办看板：三大子模块 + 待审批，点击卡片跳对应页签 -->
    <el-row :gutter="12" class="cf-watch__board">
      <el-col :span="6" v-for="card in boardCards" :key="card.key">
        <div class="cf-panel cf-watch__card" :class="{ 'is-hot': card.hot }" @click="goModule(card)">
          <div class="cf-watch__card-title">{{ card.title }}</div>
          <div class="cf-watch__card-value">{{ card.value }}</div>
          <div class="cf-watch__card-sub">
            <span v-for="s in card.subs" :key="s.label" class="cf-watch__card-item">
              <span class="cf-watch__card-item-label">{{ s.label }}</span>
              <span class="cf-watch__card-item-value" :class="{ 'is-danger': s.danger }">{{ s.value }}</span>
            </span>
          </div>
        </div>
      </el-col>
    </el-row>

    <div class="cf-panel">
      <div class="cf-panel__head">
        <span>案件盯办</span>
        <span class="cf-panel__head-tip">初查 / 刑拘在办 / 取保及监居，侦查计划执行与期限预警</span>
        <span class="cf-spacer"></span>
        <el-button size="small" @click="loadBoard">刷新看板</el-button>
      </div>
      <div class="cf-panel__body">
        <el-tabs v-model="module" @tab-change="load">
          <el-tab-pane label="初查案件" name="INITIAL" />
          <el-tab-pane label="刑拘在办" name="DETENTION" />
          <el-tab-pane label="取保及监居" name="BAIL_RESIDENCE" />
        </el-tabs>

        <!-- 检索区：初查页签支持嫌疑人检索 -->
        <div class="cf-toolbar" style="margin-bottom: 12px">
          <el-input v-model="query.keyword" placeholder="案件名 / 编号" clearable style="width: 190px" @keyup.enter="load" />
          <template v-if="module === 'INITIAL'">
            <el-cascader
              v-model="cascadeFilter"
              :options="categoryStore.tree"
              :props="{ checkStrictly: true }"
              placeholder="案件分类"
              clearable
              filterable
              style="width: 165px"
            />
            <el-input v-model="query.suspectName" placeholder="嫌疑人姓名" clearable style="width: 130px" @keyup.enter="load" />
            <el-input v-model="query.suspectIdCard" placeholder="身份证号" clearable style="width: 160px" @keyup.enter="load" />
          </template>
          <el-select
            v-model="query.employeeId"
            filterable remote clearable reserve-keyword
            placeholder="经办人姓名"
            :remote-method="searchEmployee"
            :loading="empLoading"
            style="width: 140px"
          >
            <el-option v-for="e in employeeOptions" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
          <el-select v-model="query.investigationStatus" placeholder="侦查进度" clearable style="width: 120px" @change="load">
            <el-option v-for="(m, code) in INVEST_STATUS_META" :key="code" :label="m.label" :value="code" />
          </el-select>
          <el-button @click="reset">重置</el-button>
          <el-button type="primary" @click="load">查询</el-button>
        </div>

        <el-table :data="rows" v-loading="loading" stripe>
          <el-table-column prop="caseNo" label="编号" width="140" />
          <el-table-column prop="name" label="案件名称" min-width="180" show-overflow-tooltip />
          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.caseType" size="small" :type="(CASE_TYPE_META[row.caseType] || {}).type" effect="plain">
                {{ (CASE_TYPE_META[row.caseType] || {}).label }}
              </el-tag>
              <div v-if="row.category" class="cf-muted" style="font-size: 12px">{{ row.category }}</div>
            </template>
          </el-table-column>
          <el-table-column label="嫌疑人" width="70" align="center">
            <template #default="{ row }">
              <span v-if="row.suspectCount" style="font-weight: 600">{{ row.suspectCount }}</span>
              <span v-else class="cf-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="经办人" width="100">
            <template #default="{ row }">{{ row.owner ? row.owner.employeeName : '未指派' }}</template>
          </el-table-column>
          <el-table-column label="侦查状态" width="95" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.investigationStatus" size="small"
                :type="(INVEST_STATUS_META[row.investigationStatus] || {}).type">
                {{ (INVEST_STATUS_META[row.investigationStatus] || {}).label }}
              </el-tag>
              <span v-else class="cf-muted">未开始</span>
            </template>
          </el-table-column>
          <el-table-column label="阶段进度" width="178">
            <template #default="{ row }">
              <div style="display: flex; align-items: center; gap: 6px">
                <el-tag size="small" :type="(STAGE_META[row.flowStage] || {}).type" effect="plain">
                  {{ row.flowStageName || stageLabel(row.flowStage) }}
                </el-tag>
                <el-progress v-if="row.planTotal > 0"
                  :percentage="Math.round((row.planDone / row.planTotal) * 100)"
                  :status="planProgressStatus(row)"
                  :stroke-width="8"
                  style="flex: 1; min-width: 60px" />
                <span v-else class="cf-muted" style="font-size: 12px">暂无任务</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="措施期限" width="140">
            <template #default="{ row }">
              <template v-if="row.caseMeasure && row.caseMeasure !== 'NONE'">
                <el-tag size="small" :type="(MEASURE_META[row.caseMeasure] || {}).type" effect="plain">
                  {{ (MEASURE_META[row.caseMeasure] || {}).label }}
                </el-tag>
                <div :class="deadlineClass(row)" style="font-size: 12px">{{ deadlineText(row) }}</div>
              </template>
              <span v-else class="cf-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="预警" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.planOverdue > 0" type="danger" size="small" effect="dark">计划逾期{{ row.planOverdue }}</el-tag>
              <el-tag v-else-if="measureOverdue(row)" type="danger" size="small" effect="dark">措施超期</el-tag>
              <el-tag v-else-if="measureDueSoon(row)" type="warning" size="small">措施临期</el-tag>
              <span v-else class="cf-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDrawer(row)">侦查详情</el-button>
            </template>
          </el-table-column>
          <template #empty><span class="cf-muted">当前条件下暂无案件</span></template>
        </el-table>

        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          style="margin-top: 12px; justify-content: flex-end"
          @current-change="load"
        />
      </div>
    </div>

    <WatchDrawer v-model="drawerVisible" :case-id="currentId" @done="refreshAll" />
    <PageFooter />
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch, onMounted } from 'vue'
import { watchApi, employeeApi } from '../api'
import { useCategoryStore } from '../store/category'
import { withCaseType, useCaseTypeStore } from '../store/caseType'
import { CASE_TYPE_META, INVEST_STATUS_META, MEASURE_META, STAGE_META, stageLabel } from '../utils/format'
import WatchDrawer from '../components/WatchDrawer.vue'
import PageFooter from '../components/PageFooter.vue'

const categoryStore = useCategoryStore()
const caseTypeStore = useCaseTypeStore()
const module = ref('INITIAL')
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const board = ref({})
const drawerVisible = ref(false)
const currentId = ref(null)

const query = reactive({
  page: 1, size: 20, keyword: '', caseType: '', category: '',
  suspectName: '', suspectIdCard: '', employeeId: null, investigationStatus: ''
})
const cascadeFilter = ref([])
watch(cascadeFilter, (val) => {
  query.caseType = val?.[0] || ''
  query.category = val?.[1] || ''
})

const employeeOptions = ref([])
const empLoading = ref(false)
const searchEmployee = async (kw) => {
  if (!kw) { employeeOptions.value = []; return }
  empLoading.value = true
  try { employeeOptions.value = await employeeApi.search({ keyword: kw, limit: 30 }) }
  finally { empLoading.value = false }
}

// ---- 看板卡片 ----
// sub 拆成 {label, value, danger} 的数组：数字单独渲染，便于按阈值着色，
// 也避免把「计划逾期 0」这种无风险项和真逾期项混在一起看不出重点。
const boardCards = computed(() => [
  { key: 'INITIAL', title: '初查案件', value: board.value.initialTotal ?? 0,
    subs: [
      { label: '计划逾期', value: board.value.initialPlanOverdue ?? 0,
        danger: (board.value.initialPlanOverdue ?? 0) > 0 },
      { label: '有嫌疑人', value: board.value.initialWithSuspect ?? 0 }
    ],
    hot: (board.value.initialPlanOverdue ?? 0) > 0 },
  { key: 'DETENTION', title: '刑拘在办', value: board.value.detentionTotal ?? 0,
    subs: [
      { label: '临期', value: board.value.detentionDueSoon ?? 0 },
      { label: '超期', value: board.value.detentionOverdue ?? 0,
        danger: (board.value.detentionOverdue ?? 0) > 0 }
    ],
    hot: (board.value.detentionOverdue ?? 0) > 0 },
  { key: 'BAIL_RESIDENCE', title: '取保及监居', value: board.value.bailTotal ?? 0,
    subs: [{ label: '30天内到期', value: board.value.bailDueSoon ?? 0 }],
    hot: false },
  { key: 'APPROVAL', title: '待审批', value: board.value.approvalTotal ?? 0,
    subs: [{ label: '滞留≥2天', value: board.value.approvalStale ?? 0,
             danger: (board.value.approvalStale ?? 0) > 0 }],
    hot: (board.value.approvalStale ?? 0) > 0 }
])
const goModule = (card) => {
  if (card.key === 'APPROVAL') {
    module.value = 'DETENTION'
    query.investigationStatus = 'PENDING_APPROVAL'
  } else {
    module.value = card.key
    query.investigationStatus = ''
  }
  load()
}

const load = async () => {
  loading.value = true
  try {
    // withCaseType 最后写入 caseType，会覆盖掉 query 里可能残留的同名字段——
    // 这是"不允许跨类型混选"的关键：类型只能来自门控，不来自页面筛选
    const params = withCaseType({ ...query, module: module.value })
    Object.keys(params).forEach((k) => { if (params[k] === '' || params[k] == null) delete params[k] })
    const data = await watchApi.cases(params)
    rows.value = data.list
    total.value = data.total
  } finally { loading.value = false }
}
// 看板四组计数同样锁类型，否则卡片数字与下方列表对不上
const loadBoard = async () => { board.value = await watchApi.board(withCaseType()) }
const reset = () => {
  // 不重置 caseType：它归门控管，重置筛选不该把类型也放开
  Object.assign(query, { page: 1, keyword: '', category: '', suspectName: '', suspectIdCard: '', employeeId: null, investigationStatus: '' })
  cascadeFilter.value = []
  load()
}
const refreshAll = () => { load(); loadBoard() }

const openDrawer = (row) => { currentId.value = row.id; drawerVisible.value = true }

// ---- 进度/预警展示规则 ----
const planProgressStatus = (row) => {
  if (row.planOverdue > 0 || measureOverdue(row)) return 'exception'
  if (measureDueSoon(row)) return 'warning'
  return ''
}
const measureOverdue = (row) => row.caseMeasure && row.caseMeasure !== 'NONE'
  && row.detainDaysLeft != null && row.detainDaysLeft < 0
const measureDueSoon = (row) => row.caseMeasure && row.caseMeasure !== 'NONE'
  && row.detainDaysLeft != null && row.detainDaysLeft >= 0 && row.detainDaysLeft <= 7
const deadlineClass = (row) => {
  if (measureOverdue(row)) return 'cf-danger'
  if (measureDueSoon(row)) return 'cf-warn'
  return 'cf-muted'
}
const deadlineText = (row) => {
  if (row.detainDaysLeft == null) return '未登记期限'
  if (row.detainDaysLeft < 0) return `已超期 ${Math.abs(row.detainDaysLeft)} 天`
  return `剩 ${row.detainDaysLeft} 天（${(row.detainDeadlineText || '').slice(0, 10)}）`
}

onMounted(async () => {
  await categoryStore.load()
  await load()
  await loadBoard()
})
</script>

<style>
.cf-watch__board { margin-bottom: 12px }
.cf-watch__card {
  cursor: pointer;
  transition: border-color .15s, box-shadow .18s ease;
  /* 面板本身没有内边距（其它地方靠 .cf-panel__body 提供），
     统计卡不用 __body，必须自己撑开，否则数字会顶到边框上 */
  padding: 14px 18px 15px;
  /* 左侧色条用于区分模块，与工作台 KPI 卡保持同一套视觉语言 */
  border-left: 3px solid var(--cf-primary);
}
.cf-watch__card:hover { border-color: #1b4a8c; background: #fafcff }
.cf-watch__card.is-hot { border-left-color: #c62a2a }
.cf-watch__card.is-hot .cf-watch__card-value { color: #c62a2a }

/* 标题在上、数字在下，数字与标题左对齐成一条竖线 */
.cf-watch__card-title {
  font-size: 13px;
  color: #5a6472;
  line-height: 1;
  margin-bottom: 10px;
}
.cf-watch__card-value {
  font-size: 30px;
  font-weight: 600;
  color: #1b2430;
  line-height: 1;
  letter-spacing: -0.5px;
  font-variant-numeric: tabular-nums;
}
/* 副信息：标签 + 数字成对排列，数字加粗以形成视觉重心 */
.cf-watch__card-sub {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 16px;
  font-size: 12px;
  color: #8a929e;
  line-height: 1;
  margin-top: 10px;
}
/* 两项以上才画分隔点，单项时加了会多一个孤零零的点 */
.cf-watch__card-item {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  white-space: nowrap;
}
.cf-watch__card-item + .cf-watch__card-item::before {
  content: '';
  width: 1px;
  height: 10px;
  background: #dfe5ee;
  margin-right: 12px;
  align-self: center;
  transform: translateY(1px);
}
.cf-watch__card-item-label { color: #8a929e }
.cf-watch__card-item-value {
  font-weight: 600;
  color: #5a6472;
  font-variant-numeric: tabular-nums;
}
.cf-watch__card-item-value.is-danger { color: #c62a2a }
.cf-danger { color: #c62a2a }
.cf-warn { color: #d98a0b }
</style>
