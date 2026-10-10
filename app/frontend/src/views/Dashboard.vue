<template>
  <div class="cf-page">
    <div class="cf-kpi-grid">
      <div class="cf-kpi" @click="go('OPEN')">
        <div class="cf-kpi__value">{{ data.totalCase || 0 }}</div>
        <div class="cf-kpi__label">案件总数</div>
      </div>
      <div class="cf-kpi" @click="go('PENDING_ASSIGN')">
        <div class="cf-kpi__value">{{ data.pendingAssign || 0 }}</div>
        <div class="cf-kpi__label">待指派</div>
      </div>
      <div class="cf-kpi" @click="go('IN_PROGRESS')">
        <div class="cf-kpi__value">{{ data.inProgress || 0 }}</div>
        <div class="cf-kpi__label">处理中</div>
      </div>
      <div class="cf-kpi" @click="go('DONE')">
        <div class="cf-kpi__value">{{ data.done || 0 }}</div>
        <div class="cf-kpi__label">已办结</div>
      </div>
      <div class="cf-kpi cf-kpi--danger" @click="gotoReminder('OVERDUE')">
        <div class="cf-kpi__value">{{ data.overdue || 0 }}</div>
        <div class="cf-kpi__label">已逾期</div>
      </div>
      <div class="cf-kpi cf-kpi--warn" @click="gotoReminder('TODAY')">
        <div class="cf-kpi__value">{{ data.dueToday || 0 }}</div>
        <div class="cf-kpi__label">今日到期</div>
      </div>
      <div class="cf-kpi cf-kpi--warn" @click="gotoReminder('D3')">
        <div class="cf-kpi__value">{{ data.dueIn3Days || 0 }}</div>
        <div class="cf-kpi__label">3天内到期</div>
      </div>
      <div class="cf-kpi" @click="gotoReminder('D7')">
        <div class="cf-kpi__value">{{ data.dueIn7Days || 0 }}</div>
        <div class="cf-kpi__label">7天内到期</div>
      </div>
    </div>

    <!-- 案件类型分析栏：图例即筛选（点色块切换类型），期限 / 紧急程度多选组合，全部图表实时联动 -->
    <div class="cf-panel">
      <div class="cf-panel__head">
        <span>案件类型分析</span>
        <span class="cf-ta__hint">点击色块图例筛选案件类型，可与期限、紧急程度组合</span>
        <span class="cf-spacer"></span>
        <el-select v-model="trendDays" size="small" style="width: 96px" @change="loadStats">
          <el-option label="近 7 天" :value="7" />
          <el-option label="近 14 天" :value="14" />
          <el-option label="近 30 天" :value="30" />
        </el-select>
        <el-button size="small" @click="loadStats">刷新</el-button>
      </div>
      <div class="cf-panel__body cf-ta">
        <!-- 筛选行：图例 chips（色块 + 名称 + 实时计数）+ 维度多选 -->
        <div class="cf-ta__filters">
          <div class="cf-ta__legend">
            <button
              v-for="t in legendChips"
              :key="t.code"
              type="button"
              class="cf-ta__chip"
              :class="{ 'is-off': !isTypeOn(t.code) }"
              :title="isTypeOn(t.code) ? '点击取消该类型' : '点击只看该类型'"
              @click="toggleType(t.code)"
            >
              <span class="cf-ta__swatch" :style="swatchStyle(t.code)"></span>
              {{ t.label }}<b>{{ t.count }}</b>
            </button>
            <button
              type="button"
              class="cf-ta__chip"
              :class="{ 'is-off': caseTypeSel.length > 0 }"
              @click="caseTypeSel = []; loadStats()"
            >
              全部
            </button>
          </div>
          <div class="cf-ta__selects">
            <el-select
              v-model="dueBucketSel"
              multiple
              collapse-tags
              clearable
              placeholder="期限（可多选）"
              style="width: 190px"
              @change="loadStats"
            >
              <el-option v-for="d in DUE_BUCKET_OPTIONS" :key="d.code" :label="d.label" :value="d.code" />
            </el-select>
            <el-select
              v-model="prioritySel"
              multiple
              collapse-tags
              clearable
              placeholder="紧急程度（可多选）"
              style="width: 190px"
              @change="loadStats"
            >
              <el-option label="特急" value="URGENT" />
              <el-option label="紧急" value="HIGH" />
              <el-option label="普通" value="NORMAL" />
              <el-option label="低" value="LOW" />
            </el-select>
            <el-button v-if="hasFilter" link type="primary" @click="clearFilters">清空筛选</el-button>
          </div>
        </div>

        <!-- 空态：当前筛选组合下没有案件 -->
        <div v-if="!totalFiltered" class="cf-ta__empty">
          <div class="cf-ta__empty-title">当前筛选组合下暂无案件</div>
          <div class="cf-ta__empty-sub">试试取消某个类型或放宽期限、紧急程度条件</div>
          <el-button size="small" @click="clearFilters">清空全部筛选</el-button>
        </div>

        <!-- 图表区：占比环图（中心总数）+ 类型分色趋势 + 类型分色到期分布 -->
        <el-row v-else :gutter="12" class="cf-ta__charts">
          <el-col :span="6">
            <div class="cf-ta__chart-title">类型占比</div>
            <EChart :option="typePieOption" :height="230" @click="onPieClick" />
          </el-col>
          <el-col :span="9">
            <div class="cf-ta__chart-title">近 {{ trendDays }} 天新增（按类型着色）</div>
            <EChart :option="typeTrendOption" :height="230" />
          </el-col>
          <el-col :span="9">
            <div class="cf-ta__chart-title">到期分布（按类型着色，点击柱体按期限筛选）</div>
            <EChart :option="dueByTypeOption" :height="230" @click="onDueBarClick" />
          </el-col>
        </el-row>
      </div>
    </div>

    <el-row :gutter="12">
      <el-col :span="12" :xs="24">
        <ChartPanel title="案件状态分布（点击查看该类案件）" :empty="!statusData.length">
          <template #tools>
            <el-select v-model="statusType" size="small">
              <el-option label="柱状图" value="bar" />
              <el-option label="折线图" value="line" />
            </el-select>
          </template>
          <EChart :option="statusOption" :height="220" @click="onStatusClick" />
        </ChartPanel>
      </el-col>
      <el-col :span="12" :xs="24">
        <ChartPanel title="承办人在手负载（点击查看其案件）" :empty="!ownerData.length">
          <template #tools>
            <!-- 条数会随人员增长：明说「可上下滑动」，别让人以为只有这几根条 -->
            <span v-if="ownerData.length > OWNER_SCROLL_AT" class="cf-dash__chart-hint">
              列出 {{ ownerData.length }} 人 · 可上下滑动
            </span>
            <el-select v-model="loadMetric" size="small">
              <el-option label="在手 / 逾期" value="both" />
              <el-option label="仅在手" value="total" />
              <el-option label="仅逾期" value="overdue" />
            </el-select>
          </template>
          <EChart :option="ownerOption" :height="ownerChartHeight" :max-height="ownerViewport"
            @click="onOwnerClick" />
        </ChartPanel>
      </el-col>
    </el-row>

    <el-row :gutter="12">
      <el-col :span="12" :xs="24">
        <div class="cf-panel">
          <div class="cf-panel__head">
            <span class="cf-danger">已逾期（{{ data.overdue }}）</span>
            <el-button link type="primary" @click="gotoReminder('OVERDUE')">查看全部</el-button>
          </div>
          <CaseTable
            :rows="data.overdueList || []"
            :height="270"
            :min-body="300"
            :loading="loading"
            @open="openDetail"
            @assign="openAssign"
          />
        </div>
      </el-col>
      <el-col :span="12" :xs="24">
        <div class="cf-panel">
          <div class="cf-panel__head">
            <span>待指派（{{ data.pendingAssign }}）</span>
            <el-button link type="primary" @click="go('PENDING_ASSIGN')">查看全部</el-button>
          </div>
          <CaseTable
            :rows="data.pendingList || []"
            :height="270"
            :min-body="300"
            :loading="loading"
            @open="openDetail"
            @assign="openAssign"
          />
        </div>
      </el-col>
    </el-row>

    <!-- cf-dash-pair：让「7 天内到期」与「最近操作」两列严格等高 -->
    <el-row :gutter="12" class="cf-dash-pair">
      <el-col :span="16" :xs="24">
        <div class="cf-panel">
          <div class="cf-panel__head">
            <span>7 天内到期</span>
            <el-button link type="primary" @click="gotoReminder('D7')">查看全部</el-button>
          </div>
          <CaseTable
            :rows="data.dueSoonList || []"
            :height="300"
            :min-body="300"
            compact
            :loading="loading"
            @open="openDetail"
            @assign="openAssign"
          />
        </div>
      </el-col>
      <el-col :span="8" :xs="24">
        <div class="cf-panel">
          <div class="cf-panel__head">
            <span>最近操作</span>
            <el-button
              v-if="userStore.isFullAccess"
              size="small"
              :disabled="!undoableLatest"
              @click="onUndoLatest"
            >
              撤回上一步
            </el-button>
          </div>
          <!-- 案件相关 / 其他操作 分开看：登录记录产生频繁，
               混排时会把案件操作挤出面板（实测最近 60 条里 33 条是登录）。 -->
          <div class="cf-oplog__tabs">
            <button
              v-for="t in LOG_TABS"
              :key="t.key"
              type="button"
              class="cf-oplog__tab"
              :class="{ 'is-active': logTab === t.key }"
              @click="switchLogTab(t.key)"
            >
              {{ t.label }}
              <span class="cf-oplog__tab-n">{{ logCounts[t.key] }}</span>
            </button>
          </div>
          <div class="cf-panel__body cf-oplog">
            <div class="cf-oplog__list">
              <button
                v-for="l in shownLogs"
                :key="l.id"
                type="button"
                class="cf-oplog__item"
                @click="openLog(l)"
              >
                <span class="cf-oplog__row1">
                  <span class="cf-oplog__tag" :class="'is-' + (l.action || '').toLowerCase()">
                    {{ l.actionName }}
                  </span>
                  <span class="cf-oplog__text">{{ l.content }}</span>
                </span>
                <span class="cf-oplog__row2">
                  <span>{{ l.operatorName }} · {{ l.createdAtText }}</span>
                  <span v-if="l.undone" class="cf-oplog__flag">已撤回</span>
                  <span v-else-if="l.undoable" class="cf-oplog__flag cf-oplog__flag--ok">可撤回</span>
                </span>
              </button>
              <div v-if="!shownLogs.length" class="cf-muted">{{ logTab === 'case' ? '暂无案件相关操作' : '暂无其他操作' }}</div>
            </div>
            <div class="cf-oplog__hint">
              点击任一条查看执行详情<span class="cf-oplog__kbd">Ctrl</span><span class="cf-oplog__kbd">Z</span>撤回上一步
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <CaseDetailDrawer v-model="detailVisible" :case-id="currentId" @done="load" />
    <AssignDialog
      v-model="assignVisible"
      :case-id="currentId"
      :case-name="currentName"
      :current-deadline="currentDeadline"
      @done="load"
    />
    <OperationLogDrawer
      v-model="logVisible"
      :log-id="currentLogId"
      @done="onLogUndone"
      @open-case="openCaseFromLog"
    />

    <PageFooter />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { caseApi, logApi } from '../api'
import { gotoGated } from '../store/caseType'
import { useUserStore } from '../store/user'
import { useEventStore } from '../store/events'
import CaseTable from '../components/CaseTable.vue'
import CaseDetailDrawer from '../components/CaseDetailDrawer.vue'
import AssignDialog from '../components/AssignDialog.vue'
import ChartPanel from '../components/ChartPanel.vue'
import EChart from '../components/EChart.vue'
import OperationLogDrawer from '../components/OperationLogDrawer.vue'
import { CHART, lineOption, barOption, CASE_TYPE_COLORS, caseTypeColor } from '../utils/chart'
import PageFooter from '../components/PageFooter.vue'

const router = useRouter()
const userStore = useUserStore()
const data = ref({})
const stats = ref({})

// 图表可调项：天数、图形类型、负载口径
const trendDays = ref(14)
const statusType = ref('bar')
const loadMetric = ref('both')

// ---- 案件类型分析栏：多维组合筛选（维度间 AND、维度内 OR；全空 = 不限） ----
const caseTypeSel = ref([]) // 图例 chips 驱动；空 = 全部类型
const dueBucketSel = ref([]) // 期限多选
const prioritySel = ref([]) // 紧急程度多选
const DUE_BUCKET_OPTIONS = [
  { code: 'OVERDUE', label: '已逾期' },
  { code: 'TODAY', label: '今天到期' },
  { code: 'D3', label: '3天内' },
  { code: 'D7', label: '7天内' },
  { code: 'LATER', label: '更晚' },
  { code: 'NONE', label: '未设期限' }
]
const hasFilter = computed(() => caseTypeSel.value.length + dueBucketSel.value.length + prioritySel.value.length > 0)
const clearFilters = () => {
  caseTypeSel.value = []
  dueBucketSel.value = []
  prioritySel.value = []
  loadStats()
}
const isTypeOn = (code) => caseTypeSel.value.length === 0 || caseTypeSel.value.includes(code)
const toggleType = (code) => {
  const i = caseTypeSel.value.indexOf(code)
  if (i >= 0) caseTypeSel.value.splice(i, 1)
  else caseTypeSel.value.push(code)
  loadStats()
}
const detailVisible = ref(false)
const assignVisible = ref(false)
const currentId = ref(null)
const currentName = ref('')
const currentDeadline = ref(null)

// 操作日志：点击「最近操作」任一条打开详情抽屉
const logVisible = ref(false)
const currentLogId = ref(null)

/* ---- 最近操作：案件相关 / 其他操作 分开查看 ----
 * 为什么要分而不是混排：登录记录产生极频繁（实测最近 60 条里 33 条是登录），
 * 混排时案件操作会被挤出面板，用户几乎看不到自己关心的案件变更。
 * 取数在后端按类型过滤（/logs/recent-by-type），不在前端筛——前端筛只能
 * 在已被登录记录占满的 N 条里挑，案件操作照样不全。
 */
const LOG_TABS = [
  { key: 'case', label: '案件相关' },
  { key: 'other', label: '其他操作' }
]
const logTab = ref('case')
const logsByType = ref({ case: [], other: [] })
const logCounts = computed(() => ({
  case: logsByType.value.case.length,
  other: logsByType.value.other.length
}))
const shownLogs = computed(() => logsByType.value[logTab.value] || [])

/** 切页签：首次进入某页签时才拉取（工作台不是所有人都有日志权限，避免多余请求） */
const switchLogTab = async (key) => {
  if (logTab.value === key) return
  logTab.value = key
  if (!logsByType.value[key].length) {
    await loadLogsByType(key)
  }
}
const loadLogsByType = async (type) => {
  try {
    logsByType.value[type] = await logApi.recentByType(type, 20) || []
  } catch (e) {
    logsByType.value[type] = []
  }
}

const loading = ref(false)
/**
 * 工作台是登录后的落地页，一次要打十几个请求。
 * 后端没就绪 / 网络不通 / 登录失效时，这里必须自己接住异常：
 * 提示语由 axios 拦截器统一给（已去重），页面这边只管把数字留空，
 * 否则 Promise.all 会抛出未处理的 rejection（控制台一整段红字，看着像崩了）。
 */
const load = async () => {
  loading.value = true
  try {
    data.value = await caseApi.dashboard()
    // 最近操作：同步刷新当前页签（事件流来了新操作要在面板里看到）
    await loadLogsByType(logTab.value)
  } catch (e) {
    // 保持空数据即可；错误提示已由拦截器给出
  } finally {
    loading.value = false
  }
}
const loadStats = async () => {
  const params = { days: trendDays.value }
  // 多值参数逗号分隔；空数组不传 = 该维度不限
  if (caseTypeSel.value.length) params.caseTypes = caseTypeSel.value.join(',')
  if (dueBucketSel.value.length) params.dueBuckets = dueBucketSel.value.join(',')
  if (prioritySel.value.length) params.priorities = prioritySel.value.join(',')
  try {
    stats.value = await caseApi.stats(params)
  } catch (e) {
    // 同上：图表留空，不抛未处理异常
  }
}

// ---- 图表数据 ----
const statusData = computed(() => stats.value.statusDist || [])
const ownerData = computed(() => stats.value.ownerLoad || [])

const shortDate = (d) => (d || '').slice(5)

// ---- 案件类型分析：图例计数 / 占比环图 / 类型分色堆叠图 ----
const TYPE_FIELD = { CRIMINAL: 'criminal', ADMINISTRATIVE: 'administrative', PRELIMINARY: 'preliminary', CIVIL: 'civil' }

const typeCounts = computed(() => {
  const m = {}
  for (const d of stats.value.caseTypeDist || []) {
    if (d.code) m[d.code] = d.value
  }
  return m
})
const legendChips = computed(() => CASE_TYPE_COLORS.map((t) => ({ code: t.code, label: t.label, count: typeCounts.value[t.code] || 0 })))
const totalFiltered = computed(() => (stats.value.caseTypeDist || []).reduce((s, d) => s + d.value, 0))

const swatchStyle = (code) => {
  const c = caseTypeColor(code)
  return { background: c.color, border: `1.5px solid ${c.border || c.color}` }
}

/** 类型占比环图：白=未立案（灰边），中心显示筛选后总数 */
const typePieOption = computed(() => {
  const dist = stats.value.caseTypeDist || []
  const total = dist.reduce((s, d) => s + d.value, 0)
  const data = dist
    .filter((d) => d.value > 0 && d.code)
    .map((d) => {
      const c = caseTypeColor(d.code)
      return {
        name: d.name,
        value: d.value,
        itemStyle: { color: c.color, borderColor: c.border || '#ffffff', borderWidth: c.border ? 1.2 : 2 }
      }
    })
  return {
    tooltip: {
      trigger: 'item', confine: true,
      backgroundColor: 'rgba(18,41,74,0.92)', borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 12 },
      formatter: '{b}：{c} 件（{d}%）'
    },
    title: {
      text: String(total), subtext: '案件总数',
      left: 'center', top: '34%',
      textStyle: { fontSize: 26, fontWeight: 600, color: CHART.text },
      subtextStyle: { fontSize: 12, color: CHART.text3 }
    },
    series: [{
      type: 'pie',
      radius: ['50%', '72%'],
      center: ['50%', '46%'],
      avoidLabelOverlap: true,
      // 计数已由图例 chips 承担，标签不再重复（窄列下还会截断）
      label: { show: false },
      labelLine: { show: false },
      emphasis: { label: { show: false } },
      data
    }]
  }
})

/** 近 N 天新增：按类型堆叠柱，只画当前选中的类型 */
const typeTrendOption = computed(() => {
  const rows = stats.value.typeTrend || []
  const cats = rows.map((r) => shortDate(r.date))
  const active = CASE_TYPE_COLORS.filter((t) => isTypeOn(t.code))
  const series = active.map((t) => ({
    name: t.label,
    data: rows.map((r) => r[TYPE_FIELD[t.code]] || 0),
    borderColor: t.border
  }))
  return barOption({
    categories: cats,
    series,
    stack: true,
    colors: active.map((t) => t.color),
    narrow: false
  })
})

/** 到期分布：每个到期桶内按类型堆叠；点击桶切换期限筛选 */
const dueByTypeOption = computed(() => {
  const rows = stats.value.dueByType || []
  const cats = rows.map((r) => r.name)
  const series = CASE_TYPE_COLORS.filter((t) => isTypeOn(t.code)).map((t) => ({
    name: t.label,
    data: rows.map((r) => r[TYPE_FIELD[t.code]] || 0),
    borderColor: t.border
  }))
  const colors = CASE_TYPE_COLORS.filter((t) => isTypeOn(t.code)).map((t) => t.color)
  return barOption({ categories: cats, series, stack: true, colors, narrow: false })
})

const onPieClick = (p) => {
  const t = CASE_TYPE_COLORS.find((x) => x.label === p.name)
  if (t) toggleType(t.code)
}
const onDueBarClick = (p) => {
  const row = (stats.value.dueByType || [])[p.dataIndex]
  if (!row) return
  const i = dueBucketSel.value.indexOf(row.code)
  if (i >= 0) dueBucketSel.value.splice(i, 1)
  else dueBucketSel.value.push(row.code)
  loadStats()
}

const STATUS_COLOR = {
  PENDING_ASSIGN: CHART.danger,
  ASSIGNED: CHART.warn,
  IN_PROGRESS: CHART.primary,
  DONE: CHART.ok,
  CANCELLED: CHART.text3
}
const statusOption = computed(() => {
  const cats = statusData.value.map((d) => d.name)
  const series = [{ name: '案件数', data: statusData.value.map((d) => d.value) }]
  const colors = [statusData.value.map((d) => STATUS_COLOR[d.code] || CHART.primary)]
  return statusType.value === 'line'
    ? lineOption({ categories: cats, series, narrow: false })
    : barOption({ categories: cats, series, colors, narrow: false })
})

const ownerOption = computed(() => {
  const cats = ownerData.value.map((d) => d.name)
  let series = []
  if (loadMetric.value === 'total') {
    series = [{ name: '在手案件', data: ownerData.value.map((d) => d.value) }]
  } else if (loadMetric.value === 'overdue') {
    series = [{ name: '逾期案件', data: ownerData.value.map((d) => d.overdue) }]
  } else {
    series = [
      { name: '在手（未逾期）', data: ownerData.value.map((d) => d.value - d.overdue) },
      { name: '其中逾期', data: ownerData.value.map((d) => d.overdue) }
    ]
  }
  return barOption({
    categories: cats,
    series,
    horizontal: true,
    stack: loadMetric.value === 'both',
    colors: [CHART.primary, CHART.danger],
    narrow: false
  })
})

/* ---- 人员增多时的滚动（2026-10-08）----
 * 面板高度不能动（它和左边「案件状态分布」同排等高，一动整行就错位），
 * 所以：**视口固定、画布按人数长高、超出部分用原生滚动条上下滑**。
 * 后端已不再截断 Top 10（StatsService.OWNER_LOAD_LIMIT 只作兜底），
 * 这里若还按固定高度画，条子会被越压越扁、名字挤成一团。 */
const OWNER_ROW_H = 20      // 每人一条的高度（低于这个字号就看不清了）
const OWNER_CHART_PAD = 34  // 网格上下留白（chart.js 里 top 28 + bottom 6）
const OWNER_SCROLL_AT = 8   // 超过这个条数才提示「可上下滑动」

/** 视口高度：维持原样，页面外观不变 */
const ownerViewport = computed(() => 220)
/** 画布高度：够放就等于视口（不出现滚动条），不够就按人数长高 */
const ownerChartHeight = computed(() => {
  const need = OWNER_CHART_PAD + ownerData.value.length * OWNER_ROW_H
  return Math.max(ownerViewport.value, need)
})

// ---- 图表联动：点柱体直接跳到对应清单 ----
const onStatusClick = (p) => {
  const item = statusData.value[p.dataIndex]
  if (item) go(item.code)
}
const onOwnerClick = (p) => {
  const item = ownerData.value[p.dataIndex]
  if (item) gotoGated(router, '/cases', { employeeId: item.code, employeeName: item.name })
}

const go = (status) => gotoGated(router, '/cases', { status })
const gotoReminder = (bucket) => gotoGated(router, '/reminders', { bucket })
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

// ---- 操作日志：查看详情 / 撤回上一步（Ctrl+Z） ----
const undoableLatest = computed(() => (data.value.recentLogs || []).find((l) => l.undoable) || null)

const openLog = (log) => {
  currentLogId.value = log.id
  logVisible.value = true
}

const openCaseFromLog = (caseId) => {
  if (!caseId) return
  logVisible.value = false
  currentId.value = caseId
  detailVisible.value = true
}

const onLogUndone = async () => {
  await load()
  await loadStats()
}

const onUndoLatest = async () => {
  const target = undoableLatest.value
  if (!target) {
    ElMessage.info('暂无可撤回的操作')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将撤回最近一步操作，相关数据回到操作前：\n\n【${target.actionName}】${target.content}`,
      '撤回上一步（Ctrl+Z）',
      { type: 'warning', confirmButtonText: '确认撤回', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const undoLog = await logApi.undoLatest()
  ElMessage.success('已撤回上一步')
  await onLogUndone()
  // 撤回本身也是一条记录，直接把它打开，让人看清回滚成了什么
  if (undoLog && undoLog.id) {
    currentLogId.value = undoLog.id
    logVisible.value = true
  }
}

/** 在输入框里按 Ctrl+Z 是文本撤销，不能抢 */
const isTyping = (el) => {
  if (!el) return false
  const tag = (el.tagName || '').toLowerCase()
  return tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable
}

const onKeydown = (e) => {
  // 撤回属于高级功能，普通民警不绑定 Ctrl+Z，避免按了没反应还以为系统坏了
  if (!userStore.isFullAccess) return
  if (!(e.ctrlKey || e.metaKey) || e.shiftKey || e.altKey) return
  if ((e.key || '').toLowerCase() !== 'z') return
  if (isTyping(document.activeElement)) return
  e.preventDefault()
  onUndoLatest()
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  // 全局事件流：别人推进案件时，「最近操作」面板与 KPI 自动刷新（2 秒防抖合并）
  unsubscribeEvents = eventStore.subscribe(onCaseEvent)
  await Promise.all([load(), loadStats()])
})

const eventStore = useEventStore()
let unsubscribeEvents = null
let dashboardTimer = null
const onCaseEvent = (e) => {
  if (e.module !== 'CASE') return
  if (dashboardTimer) return
  dashboardTimer = setTimeout(async () => {
    dashboardTimer = null
    try {
      await Promise.all([load(), loadStats()])
      // 当前不在「案件相关」页签时，把另一页签也刷新一次——
      // 否则用户切过去看到的还是旧数据（登录/员工操作只在 other 页签里）
      const other = logTab.value === 'case' ? 'other' : 'case'
      await loadLogsByType(other)
    } catch (err) { /* 忽略 */ }
  }, 2000)
}

onBeforeUnmount(() => {
  clearTimeout(dashboardTimer)
  window.removeEventListener('keydown', onKeydown)
  if (unsubscribeEvents) unsubscribeEvents()
})
</script>

<style>
/* 负载图工具区的常驻说明（人员变多时才出现）。
   项目禁用悬浮提示，所以「可以滑动」这件事必须一直看得见，
   否则用户只会以为图就这么高、下面的人没案子。 */
.cf-dash__chart-hint {
  font-size: 12px;
  color: var(--cf-text-3);
  white-space: nowrap;
}
/* 「7 天内到期」与「最近操作」同排等高（2026-10-08）：
   两列高度由内部写死的内容区决定（表格 262 / 日志区 262），
   这里给面板外框一个**确定高度**，让两者严格对齐、底部不出现高低差。
   **不要**用 flex:1 + height:100% 让高度依赖列高——那是 flex 循环依赖，
   会把面板撑到整页高（实测 1289px）。项目里已踩过同类坑。 */
.cf-dash-pair { align-items: stretch }
.cf-dash-pair :deep(.el-col) > .cf-panel { height: 346px; box-sizing: border-box }

/* 案件类型分析栏：图例 chips + 组合筛选 + 三图联动（桌面端） */
.cf-ta {
  padding: 12px 16px 16px;
}
.cf-ta__hint {
  margin-left: 12px;
  font-size: 12px;
  font-weight: 400;
  color: #8a929e;
}
.cf-ta__filters {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.cf-ta__legend {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.cf-ta__chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 13px;
  border: 1px solid #dfe4ea;
  border-radius: 16px;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  color: #1b2430;
  line-height: 1;
  transition: border-color 0.15s, opacity 0.15s;
}
.cf-ta__chip:hover {
  border-color: #1b4a8c;
}
.cf-ta__chip b {
  font-weight: 600;
  font-size: 13px;
}
.cf-ta__chip.is-off {
  opacity: 0.4;
  border-style: dashed;
  color: #8a929e;
}
.cf-ta__swatch {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 3px;
  flex: none;
}
.cf-ta__selects {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.cf-ta__chart-title {
  font-size: 13px;
  color: #5a6472;
  margin: 4px 0 2px;
}
.cf-ta__charts {
  margin-top: 4px;
}
.cf-ta__empty {
  padding: 64px 0 72px;
  text-align: center;
}
.cf-ta__empty-title {
  font-size: 15px;
  color: #1b2430;
  margin-bottom: 6px;
}
.cf-ta__empty-sub {
  font-size: 13px;
  color: #8a929e;
  margin-bottom: 16px;
}
</style>
