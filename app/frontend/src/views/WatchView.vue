<template>
  <div class="cf-page">
    <!-- 类型锁定提示条：看板四组卡片与三子模块列表都只统计这一类案件 -->
    <div class="cf-gatebar" :class="'is-' + (caseTypeStore.currentOption?.type || 'info')">
      <span class="cf-gatebar__tag">{{ caseTypeStore.currentOption?.label || '未选择' }}</span>
      <template v-if="scopeCategory">
        <span class="cf-gatebar__divider"></span>
        <span>类别：<b>{{ scopeCategoryLabel }}</b></span>
        <el-button link size="small" @click="clearCategory">清除</el-button>
      </template>
      <span>当前只看{{ scopeCategory ? '这一小类' : '这一类' }}案件；看板计数、图表、页签列表与检索均限定在此范围内</span>
      <span class="cf-spacer"></span>
      <span class="cf-gatebar__tip">退回上一级或换类型，请用顶部类型条右侧的按钮</span>
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

    <!-- 可视化：三张图与上方卡片同源同口径（都带当前类型 + 小类），
         结构（占比） / 期限（紧迫度） / 分类（横向比较）三个角度互补，
         卡片上已有的数字不再重复画一遍，图只补数字表达不出来的分布信息 -->
    <el-row :gutter="12" class="cf-watch__charts">
      <el-col :span="8" :xs="24">
        <ChartPanel title="在办案件构成（点击切换页签）" :empty="!moduleData.length">
          <EChart :option="modulePieOption" :height="196" @click="onPieClick" />
        </ChartPanel>
      </el-col>
      <el-col :span="8" :xs="24">
        <ChartPanel :title="'措施期限分布' + (scopeCategory ? '（' + scopeCategoryLabel + '）' : '')"
          :empty="!dueTotal">
          <EChart :option="dueBarOption" :height="196" />
        </ChartPanel>
      </el-col>
      <el-col :span="8" :xs="24">
        <ChartPanel title="各小类案件数（点击即筛该类）" :empty="!categoryBars.length">
          <EChart :option="categoryBarOption" :height="categoryChartHeight"
            :max-height="categoryViewport" @click="onCategoryBarClick" />
        </ChartPanel>
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
              @change="onFilterChange"
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
          <el-table-column label="嫌疑人" width="140">
            <template #default="{ row }">
              <el-tooltip v-if="suspectLabel(row)" :content="suspectNamesText(row)"
                placement="top" :disabled="!suspectNamesText(row)">
                <span class="cf-suspect">{{ suspectLabel(row) }}</span>
              </el-tooltip>
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
import { useRoute } from 'vue-router'
import { watchApi, employeeApi } from '../api'
import { useCategoryStore } from '../store/category'
import { withCaseType, useCaseTypeStore } from '../store/caseType'
import { CASE_TYPE_META, INVEST_STATUS_META, MEASURE_META, STAGE_META, stageLabel, suspectLabel, suspectNamesText } from '../utils/format'
import { CHART, barOption } from '../utils/chart'
import WatchDrawer from '../components/WatchDrawer.vue'
import ChartPanel from '../components/ChartPanel.vue'
import EChart from '../components/EChart.vue'
import PageFooter from '../components/PageFooter.vue'

const categoryStore = useCategoryStore()
const caseTypeStore = useCaseTypeStore()
const route = useRoute()
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
  // 级联为空 ≠ 一定要清掉小类筛选：NONE（未分类）与字典外的小类都反投影不出级联，
  // 照常清掉会让「未分类」卡片、以及从图表点选进来的筛选当场失效（2026-10-09）。
  // 只有「级联本可以表示这个类别、但被用户清掉了」才真的清筛选。
  if (!val?.length) {
    const keep = query.category === 'NONE'
      || (query.category && !categoryStore.typeOfCategory(query.category))
    if (keep) {
      query.page = 1
      return
    }
  }
  query.caseType = val?.[0] || ''
  query.category = val?.[1] || ''
})

/**
 * 从地址栏同步小类筛选（2026-10-09 三级浏览）：
 * 小类卡片页选完会带 ?category=xxx 落到本栏目，而本页原本只认页内级联控件，
 * 不读地址栏就会"跳过来了却没筛"。NONE=未分类，不在级联树里，只设筛选值不显示级联。
 */
const syncCategoryFromRoute = () => {
  const cat = String(route.query.category || '')
  if (!cat) return false
  query.category = cat
  query.page = 1
  if (cat === 'NONE') cascadeFilter.value = []
  else {
    const t = categoryStore.typeOfCategory(cat)
    cascadeFilter.value = t ? [t, cat] : []
    // 级联反查不到时上面那句会把 category 清空，这里补回，保证筛选不丢
    query.category = cat
  }
  return true
}

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

// ---- 当前浏览的小类（看板、图表与列表共用的唯一口径）----
// 就是 query.category：既来自三级浏览的地址栏参数，也来自页内级联控件，
// 不再另立一份状态，避免"筛选条写电诈、卡片算全刑事"这种两套口径打架。
const scopeCategory = computed(() => query.category || '')
const scopeCategoryLabel = computed(() => (!scopeCategory.value ? ''
  : scopeCategory.value === 'NONE' ? '未分类' : scopeCategory.value))
const clearCategory = () => {
  query.category = ''
  query.page = 1
  cascadeFilter.value = []
  load()
  loadBoard()
}

// ---- 图表（三张，数据全部来自同一次 /watch/board 返回，与卡片同源）----
// 卡片上已经有的数字不再重复画一遍，图只补「数字表达不出来的分布」：
// 占比（在办构成）、紧迫度（期限分桶）、横向比较（各小类多少）。

/** 在办构成：初查 / 刑拘在办 / 取保及监居。三者互斥且完备，相加即全部在办案件 */
const moduleData = computed(() => {
  const b = board.value || {}
  return [
    { key: 'INITIAL', name: '初查案件', value: b.initialTotal ?? 0, color: CHART.primary },
    { key: 'DETENTION', name: '刑拘在办', value: b.detentionTotal ?? 0, color: CHART.danger },
    { key: 'BAIL_RESIDENCE', name: '取保及监居', value: b.bailTotal ?? 0, color: CHART.gold }
  ].filter((d) => d.value > 0)
})

const modulePieOption = computed(() => {
  const data = moduleData.value.map((d) => ({
    name: d.name,
    value: d.value,
    itemStyle: { color: d.color, borderColor: '#ffffff', borderWidth: 2 }
  }))
  const total = data.reduce((s, d) => s + d.value, 0)
  return {
    tooltip: {
      trigger: 'item',
      confine: true,
      backgroundColor: 'rgba(18,41,74,0.92)',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 12 },
      formatter: '{b}：{c} 件（{d}%）'
    },
    // 图例放底部：三块占比相近时环上标签会互相压，图例才是可靠的读数入口
    legend: {
      bottom: 0, left: 'center', itemWidth: 10, itemHeight: 8, itemGap: 12,
      textStyle: { color: CHART.text2, fontSize: 11 }
    },
    title: {
      text: String(total), subtext: '在办合计', left: 'center', top: '30%',
      textStyle: { fontSize: 24, fontWeight: 600, color: CHART.text },
      subtextStyle: { fontSize: 11, color: CHART.text3 }
    },
    series: [{
      type: 'pie',
      radius: ['52%', '72%'],
      center: ['50%', '42%'],
      avoidLabelOverlap: true,
      label: { show: false },
      labelLine: { show: false },
      emphasis: { label: { show: false }, scaleSize: 6 },
      data
    }],
    animationDuration: 380
  }
})

/** 点环形图某一块 → 跳到对应页签（与点卡片同一行为） */
const onPieClick = (p) => {
  const item = moduleData.value[p.dataIndex]
  if (item) goModule({ key: item.key })
}

/** 期限分桶配色：红=已超期、橙=7天内、蓝=更宽裕，灰=未登记（缺数据，不是风险） */
const DUE_COLOR = {
  OVERDUE: CHART.danger,
  D7: CHART.warn,
  D30: CHART.primary,
  LATER: CHART.primaryLight,
  UNSET: CHART.text3
}
const dueBars = computed(() => board.value.dueDist || [])
// 空桶也由后端返回（保持柱数稳定），所以"有没有数据"要看总数，不能看数组长度——
// 否则某一小类下没有采取措施的案件时，会画出五根全 0 的柱子，看着像坏了
const dueTotal = computed(() => dueBars.value.reduce((s, d) => s + d.value, 0))
const dueBarOption = computed(() => {
  const list = dueBars.value
  return barOption({
    categories: list.map((d) => d.name),
    series: [{ name: '案件数', data: list.map((d) => d.value) }],
    horizontal: true,
    colors: [list.map((d) => DUE_COLOR[d.code] || CHART.primary)],
    narrow: false
  })
})

/** 各小类案件数：按**大类**统计（不受当前小类筛选影响），选中那根用金色标出 */
const categoryBars = computed(() => board.value.categoryDist || [])
const categoryBarOption = computed(() => {
  const list = categoryBars.value
  const active = scopeCategory.value
  return barOption({
    categories: list.map((d) => d.name),
    series: [{ name: '案件数', data: list.map((d) => d.value) }],
    horizontal: true,
    colors: [list.map((d) => (active && d.code === active ? CHART.gold : CHART.primary))],
    narrow: false
  })
})
// 条数多时不能硬塞进固定高度：按条数长高、外层限高滚动（与工作台负载图同一套做法）
const categoryViewport = computed(() => 196)
const categoryChartHeight = computed(() =>
  Math.max(categoryViewport.value, categoryBars.value.length * 26 + 40))

/** 点小类柱体 → 直接筛到该类（再点一次取消），列表与看板同时刷新 */
const onCategoryBarClick = (p) => {
  const item = categoryBars.value[p.dataIndex]
  if (!item) return
  applyCategory(scopeCategory.value === item.code ? '' : item.code)
}

/**
 * 统一设置当前小类（'' = 清除）。
 *
 * <p>级联控件与 query.category 必须一起改：只改一个，要么筛选条写着电诈而级联是空的，
 * 要么下次点「查询」时用级联的空值把筛选悄悄清掉。
 *
 * <p><b>不要再补一句「把 category 写回」</b>：级联的 watch 是异步 flush 的，
 * 同步写回只会被稍后的 watch 覆盖，而 load() 早已带着旧值发出去——
 * 表现就是「提示条上的小类没了、卡片数字还是小类的」（实测踩过）。
 */
const applyCategory = (cat) => {
  query.category = cat
  query.page = 1
  if (!cat || cat === 'NONE') {
    cascadeFilter.value = []
  } else {
    const t = categoryStore.typeOfCategory(cat)
    cascadeFilter.value = t ? [t, cat] : []
  }
  load()
  loadBoard()
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
/**
 * 看板四组计数锁类型 + 锁小类（2026-10-09）。
 * 只锁类型是不够的：从「按类别浏览」选完小类进来时，卡片若还按大类统计，
 * 就会出现"卡片写 7 件、下面列表只有 2 条"的口径错位。图表与卡片同一次请求，
 * 天然同源，不存在图上 6 件、卡上 7 件这种自相矛盾。
 */
const loadBoard = async () => {
  board.value = await watchApi.board(withCaseType({ category: query.category }))
}
const reset = () => {
  // 不重置 caseType：它归门控管，重置筛选不该把类型也放开
  Object.assign(query, { page: 1, keyword: '', category: '', suspectName: '', suspectIdCard: '', employeeId: null, investigationStatus: '' })
  cascadeFilter.value = []
  load()
  loadBoard()
}
/** 页内筛选变化（分类级联）：列表与看板一起刷，否则会出现"筛选条写电诈、卡片还是全刑事" */
const onFilterChange = () => {
  query.page = 1
  load()
  loadBoard()
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
  // 三级浏览：小类卡片页跳过来会带 category，先灌进筛选再取数，避免"跳到了却没筛"
  syncCategoryFromRoute()
  await load()
  await loadBoard()
})

// 已在本栏目时再次从小类页选另一个小类过来：地址栏 category 变了要跟着重取
// （看板同样要重取，否则卡片还停在上一小类的数字上）
watch(() => route.query.category, () => {
  if (syncCategoryFromRoute()) { load(); loadBoard() }
})
</script>

<style>
.cf-watch__board { margin-bottom: 12px }
/* 图表行：三张图等宽并排，与卡片同宽对齐（同一个 :gutter 12） */
.cf-watch__charts { margin-bottom: 12px }
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
