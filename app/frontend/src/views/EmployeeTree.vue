<template>
  <div class="cf-page">
    <!-- 组织树：本页主角，占更宽的一栏，横竖都给足空间 -->
    <el-row :gutter="16">
      <el-col :span="16" :xs="24">
        <div class="cf-panel cf-panel--fill">
          <div class="cf-panel__head">
            <span>组织树</span>
            <div class="cf-toolbar">
              <el-radio-group v-model="viewMode" size="small">
                <el-radio-button label="list">列表</el-radio-button>
                <el-radio-button label="chart">树状图</el-radio-button>
              </el-radio-group>
              <el-button link type="primary" @click="downloadTemplate">下载导入模板</el-button>
              <template v-if="canManage">
                <el-upload
                  :action="`${base}/employees/import`"
                  :headers="uploadHeaders"
                  :show-file-list="false"
                  :on-success="onImportSuccess"
                  :on-error="onImportError"
                  style="display: inline-flex"
                >
                  <el-button size="small" type="primary">Excel 导入</el-button>
                </el-upload>
                <el-button size="small" @click="openCreate">新增员工</el-button>
              </template>
            </div>
          </div>

          <div v-if="viewMode === 'list'" class="cf-panel__body cf-org__list">
            <el-input v-model="filterText" placeholder="过滤：姓名 / 部门 / 职务" clearable />
            <div class="cf-org__legend">
              <span class="cf-org__legend-t">组织层级</span>
              <span v-for="r in RANK_ORDER" :key="r" class="cf-org__legend-i">
                <i :style="{ background: RANK_COLORS[r] }" />{{ rankLabel(r) }}
              </span>
              <i class="cf-org__legend-sep" />
              <span class="cf-org__legend-i">
                <i :style="{ background: DEPT_ANOMALY_COLOR }" />部门待核
              </span>
            </div>
            <el-tree
              ref="treeRef"
              class="cf-org__tree"
              :data="treeData"
              :props="{ label: 'name', children: 'children' }"
              :filter-node-method="filterNode"
              default-expand-all
              @node-click="onSelect"
            >
              <template #default="{ data }">
                <span class="cf-org__row">
                  <i class="cf-org__dot" :style="{ background: nodeColor(data) }" />
                  <span class="cf-org__name">{{ data.name }}</span>
                  <span class="cf-muted">{{ data.rankLabel || data.title || '' }}</span>
                  <span v-if="!isMobile" class="cf-muted">{{ data.dept || '' }}</span>
                  <span v-if="data.deptAnomaly" class="cf-org__flag">
                    {{ isMobile ? deptAnomalyTextShort(data) : deptAnomalyText(data) }}
                  </span>
                  <span v-if="data.activeCaseCount > 0" class="cf-org__load">在手 {{ data.activeCaseCount }}</span>
                </span>
              </template>
            </el-tree>
          </div>

          <div v-else class="cf-panel__body cf-org__chart">
            <div class="cf-orgchart__tip">点击节点选中该员工 · 可拖拽平移 / 滚轮缩放 · 点击圆点展开收起</div>
            <div class="cf-org__canvas" :style="{ minHeight: treeHeight + 'px' }">
              <EChart :option="orgTreeOption" height="100%" @click="onChartNodeClick" />
            </div>
          </div>

          <!-- 底部概括条：给面板一个收口，顺便把组织规模说清楚 -->
          <div class="cf-org__foot">
            <span>共 <b>{{ orgSummary.count }}</b> 人</span>
            <i />
            <span><b>{{ orgSummary.depth }}</b> 级组织</span>
            <i />
            <span><b>{{ orgSummary.depts }}</b> 个部门</span>
            <template v-if="orgSummary.anomaly">
              <i />
              <span class="cf-org__foot-warn">部门待核 <b>{{ orgSummary.anomaly }}</b> 人</span>
            </template>
          </div>
        </div>
      </el-col>

      <el-col :span="8" :xs="24">
        <div class="cf-panel cf-panel--fill">
          <div class="cf-panel__head">
            <span>{{ form.id ? '编辑员工' : '员工详情' }}</span>
            <el-button v-if="form.id && canManage" link type="danger" @click="onDelete">删除</el-button>
          </div>
          <div class="cf-panel__body cf-detail">
            <el-form :model="form" label-width="76px" size="small" :disabled="!canManage">
              <el-form-item label="姓名">
                <el-input v-model="form.name" />
              </el-form-item>
              <el-form-item label="工号">
                <el-input v-model="form.employeeNo" placeholder="导入时的唯一标识" />
              </el-form-item>
              <el-form-item label="上级">
                <el-select
                  v-model="form.parentId"
                  filterable
                  clearable
                  :placeholder="parentHint"
                  style="width: 100%"
                >
                  <el-option
                    v-for="e in parentCandidates"
                    :key="e.id"
                    :label="`${e.name}（${e.pathName || e.rankLabel || ''}）`"
                    :value="e.id"
                  />
                </el-select>
                <div class="cf-form-tip">{{ parentTip }}</div>
              </el-form-item>
              <el-form-item label="部门">
                <el-select
                  v-model="form.dept"
                  filterable
                  allow-create
                  clearable
                  default-first-option
                  placeholder="从已有部门中选择"
                  style="width: 100%"
                >
                  <el-option v-for="d in deptOptions" :key="d.name" :label="`${d.name}（${d.count}人）`" :value="d.name" />
                </el-select>
                <div class="cf-form-tip">
                  从下拉里选已有部门；若确实要新建部门，直接输入新名称即可。
                </div>
              </el-form-item>
              <el-form-item label="职务">
                <el-select v-model="form.title" filterable allow-create clearable placeholder="领导 / 副领导 / 组长 / 组员" style="width: 100%">
                  <el-option label="领导" value="领导" />
                  <el-option label="副领导" value="副领导" />
                  <el-option label="组长" value="组长" />
                  <el-option label="组员" value="组员" />
                </el-select>
                <div class="cf-form-tip">职务决定组织层级：{{ formRankLabel }}。当前上级候选：{{ parentTip }}</div>
              </el-form-item>
   <!-- 办案组别（2026-10-04）：决定他能接哪类案件，指派时按此校验 -->
              <el-form-item label="办案组别">
          <el-radio-group v-model="form.policeGroup" style="width: 100%">
            <el-radio-button value="INITIAL">初查组</el-radio-button>
   <el-radio-button value="CLEAR">清案组</el-radio-button>
                  <el-radio-button value="NONE">不限</el-radio-button>
           </el-radio-group>
           <div class="cf-muted" style="font-size: 12px; margin-top: 4px">
    {{ groupHint }}
          </div>
         </el-form-item>
              <el-form-item label="手机">
                <el-input v-model="form.phone" />
              </el-form-item>
              <el-form-item label="邮箱">
                <el-input v-model="form.email" />
              </el-form-item>
              <el-form-item label="排序">
                <el-input-number v-model="form.sortNo" :min="0" controls-position="right" />
              </el-form-item>
            </el-form>

            <div v-if="canManage" class="cf-toolbar">
              <el-button type="primary" size="small" @click="onSave">{{ form.id ? '保存修改' : '新增员工' }}</el-button>
              <el-button size="small" @click="resetForm">清空</el-button>
            </div>

            <!-- 下半区：用手上有用的信息把空间填满，而不是硬拉高留白 -->
            <div class="cf-cases">
              <div class="cf-cases__head">
                <span>{{ selected ? `在手案件（${selected.activeCaseCount || 0}）` : '在手案件' }}</span>
                <a v-if="selected" class="cf-cases__more" @click="gotoCases">全部 →</a>
              </div>
              <div class="cf-cases__body">
                <div v-if="!selected" class="cf-cases__hint">点击左侧员工，查看其名下案件</div>
                <div v-else-if="!myCases.length" class="cf-cases__hint">暂无在手案件</div>
                <div v-for="c in myCases" :key="c.id" class="cf-cases__item" :title="`${c.caseNo} ${c.name}`">
                  <span class="cf-cases__name">{{ c.name }}</span>
                  <span class="cf-cases__due" :class="dueClass(c)">{{ dueText(c) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 可视化：部门 / 个人在手负载 -->
    <el-row :gutter="16">
      <el-col :span="12" :xs="24">
        <ChartPanel title="部门在手案件负载" :empty="!deptData.length">
          <template #tools>
            <el-select v-model="deptType" size="small">
              <el-option label="柱状图" value="bar" />
              <el-option label="折线图" value="line" />
            </el-select>
          </template>
          <EChart :option="deptOption" :height="isMobile ? 200 : 260" @click="onDeptClick" />
        </ChartPanel>
      </el-col>
      <el-col :span="12" :xs="24">
        <ChartPanel title="个人在手负载 Top 10（点击查看其案件）" :empty="!ownerData.length">
          <template #tools>
            <el-select v-model="ownerMetric" size="small">
              <el-option label="在手 / 逾期" value="both" />
              <el-option label="仅在手" value="total" />
              <el-option label="仅逾期" value="overdue" />
            </el-select>
            <el-button size="small" @click="loadStats">刷新</el-button>
          </template>
          <EChart :option="ownerOption" :height="isMobile ? 200 : 260" @click="onOwnerClick" />
        </ChartPanel>
      </el-col>
    </el-row>

    <PageFooter />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { employeeApi, caseApi } from '../api'
import { gotoGated } from '../store/caseType'
import ChartPanel from '../components/ChartPanel.vue'
import EChart from '../components/EChart.vue'
import { CHART, lineOption, barOption } from '../utils/chart'
import {
  RANK, RANK_COLORS, DEPT_ANOMALY_COLOR, rankLabel, rankOf, nodeColor, deptAnomalyText, deptAnomalyTextShort
} from '../utils/org'
import { useDevice } from '../utils/device'
import PageFooter from '../components/PageFooter.vue'
import { useUserStore } from '../store/user'

const userStore = useUserStore()
/** 员工图谱的增删改与导入属于高级功能，只有所长/副所长/法制员可用 */
const canManage = computed(() => userStore.isFullAccess)

// 手机端：栅格单列、图表降高、组织树标签收窄
const { isMobile } = useDevice()

const router = useRouter()

const base = '/api'
const uploadHeaders = { 'X-Token': localStorage.getItem('cf_token') || '' }

const treeData = ref([])
const flatEmployees = ref([])
const deptOptions = ref([])
const filterText = ref('')
const treeRef = ref()
const selected = ref(null)
const myCases = ref([])

/** 层级色板与中文名（org.js 与后端 OrgRank 同一套口径） */
const RANK_ORDER = [RANK.TOP, RANK.DEPUTY, RANK.LEADER, RANK.STAFF]

const emptyForm = () => ({
  id: null,
  name: '',
  employeeNo: '',
  parentId: null,
  dept: '',
  title: '',
  // 办案组别：INITIAL初查组 / CLEAR清案组 / NONE不限（指派校验依据）
  policeGroup: 'NONE',
  phone: '',
  email: '',
  sortNo: 0,
  status: 1
})
const form = reactive(emptyForm())

/** 表单里这个人属于哪一层：由职务实时推导，改职务层级说明立刻跟着变 */
const formRank = computed(() => rankOf(form.title))
const formRankLabel = computed(() => rankLabel(formRank.value))

/**
 * 上级候选：只给「恰好高一层」的人。
 * 层级固定为总-副总-组长-员工，选了平级或跨级的人没有意义，
 * 所以干脆不让他们出现在下拉里（后端也会再兜底一次）。
 */
const parentCandidates = computed(() => {
  const want = formRank.value - 1
  if (want < RANK.TOP) return []
  return flatEmployees.value.filter((e) => rankOf(e.title) === want)
})

const parentHint = computed(() =>
  formRank.value <= RANK.TOP ? '总/领导为最高层，无需选择上级' : '请选择上一级'
)

const parentTip = computed(() => {
  const want = formRank.value - 1
  if (want < RANK.TOP) return '总/领导是组织树第一层，不设上级'
  const names = parentCandidates.value.map((e) => e.name)
  if (!names.length) return `${rankLabel(want)}层还没有人，请先添加${rankLabel(want)}`
  return `可选：${names.join('、')}`
})

/** 组别说明：随选择变化，让人知道这个组别能接什么 */
const groupHint = computed(() => {
  if (form.policeGroup === 'INITIAL') return '只能承接初查任务'
  if (form.policeGroup === 'CLEAR') return '只能承接刑拘在办案件'
  return '可承接各类案件（不受组别限制）'
})
const resetForm = () => Object.assign(form, emptyForm())

const load = async () => {
  treeData.value = await employeeApi.tree({})
  flatEmployees.value = await employeeApi.search({ limit: 500 })
  await loadDepts()
}

/** 部门下拉的选项：从已有部门里选，避免同一个部门被填成好几种写法 */
const loadDepts = async () => {
  try {
    deptOptions.value = await employeeApi.depts()
  } catch (e) {
    deptOptions.value = []
  }
}

// ---- 组织树呈现模式：list = 原版缩进列表，chart = 自上而下树状图 ----
const viewMode = ref('list')

// 标签定宽：所有节点用同一个宽度，文字在框内换行/居中，
// 同级节点即便姓名很长也只会各自折行，不会横向叠字
// 手机上画布只有 300 多像素，定宽要跟着收，否则两三个节点就铺满了
const labelW = computed(() => (isMobile.value ? 74 : 104))

// 姓名色块（按层级取色）：一排节点一眼可辨层级
// 注意：ECharts 富文本的 lineHeight 不决定背景色块高度，色块高 = 字号 + 上下 padding，
// 所以这里靠 padding 给文字留出上下呼吸位（12.5 + 3*2 = 18.5px 色块）
// 层级已由后端按职务定好（org.js 的 rankOf），这里只是把颜色按 rank 分配，
// 不再按「在树里的第几层」猜——挂在第 0 层不代表他就是总。
const nameRich = computed(() => {
  const o = {}
  RANK_ORDER.forEach((r) => {
    o[`n${r}`] = {
      width: labelW.value,
      align: 'center',
      fontSize: isMobile.value ? 11 : 12.5,
      fontWeight: 600,
      color: '#fff',
      backgroundColor: RANK_COLORS[r],
      borderRadius: 3,
      lineHeight: isMobile.value ? 16 : 18,
      padding: isMobile.value ? [3, 2] : [3, 4]
    }
  })
  // 部门待核：单独一个橙，一眼跳出来提示要补正
  o.nAnomaly = Object.assign({}, o.n4, { backgroundColor: DEPT_ANOMALY_COLOR })
  return o
})

const toChartTree = (nodes) =>
  (nodes || []).map((n) => {
    const anomaly = !!n.deptAnomaly
    return {
      name: n.name,
      rank: n.rank,
      anomaly,
      node: n,
      itemStyle: {
        color: anomaly ? DEPT_ANOMALY_COLOR : (RANK_COLORS[n.rank] || RANK_COLORS[RANK.STAFF]),
        borderColor: '#fff',
        borderWidth: 2
      },
      children: toChartTree(n.children)
    }
  })

/** 树状图数据：顶层不止一人时（正副职各一人、单位里没设副总），
 *  ECharts 的 tree 系列只把第一棵树画进画布，其余整棵消失
 *  （2026-10-07 实测：3 个顶层只剩王总一个节点）。
 *  包一层看不见的虚拟根把森林变成一棵树。
 *  层级配色不再依赖树里的层数（2026-10-08 改为按职务定级），
 *  所以虚拟根的 depth 已无用途，去掉即可。 */
const orgChartData = computed(() => {
  const wrapped = toChartTree(treeData.value)
  if (wrapped.length <= 1) {
    return wrapped
  }
  return [{
    name: '__virtual__',
    virtual: true,
    symbolSize: 0.1,
    itemStyle: { opacity: 0 },
    lineStyle: { opacity: 0 },
    label: { show: false },
    children: wrapped
  }]
})

// 层级越深给越高画布，保证三行标签始终有落脚空间（避免纵向也挤在一起）
const maxDepth = (nodes) =>
  (nodes || []).reduce((m, n) => Math.max(m, 1 + maxDepth(n.children)), 0)
const treeHeight = computed(() => {
  const d = maxDepth(treeData.value) || 3
  const base = 118 * d + 90
  // 手机端每层给的绝对高度小一些：一行标签本来就窄，给太多只是留白
  return isMobile.value ? Math.max(340, Math.min(620, base - 60)) : Math.max(430, Math.min(760, base))
})

// 组织规模（全部由树上数据推导，口径不会飘）
const orgSummary = computed(() => {
  let count = 0
  let depth = 0
  const depts = new Set()
  let anomaly = 0
  const walk = (nodes, d) => {
    ;(nodes || []).forEach((n) => {
      count += 1
      depth = Math.max(depth, d)
      if (n.dept) depts.add(n.dept)
      if (n.deptAnomaly) anomaly += 1
      walk(n.children, d + 1)
    })
  }
  walk(treeData.value, 1)
  return { count, depth, depts: depts.size, anomaly }
})

const orgTreeOption = computed(() => ({
  tooltip: { show: false },
  series: [
    {
      type: 'tree',
      data: orgChartData.value,
      orient: 'TB',
      edgeShape: 'polyline',
      // 分叉点抬高到 38%，让横向折线落在子节点标签上方而不是穿过文字
      edgeForkPosition: '38%',
      // 左右留 0%：tree 系列的横向排布是「按结构间距铺满整个框」，
      // 框留白越多节点越挤，所以这里把可用宽度全部给它，靠面板内边距来兜留白
      left: 0,
      right: 0,
      top: '14%',
      bottom: '8%',
      symbol: 'circle',
      symbolSize: 12,
      initialTreeDepth: -1,
      expandAndCollapse: true,
      roam: true,
      emphasis: { focus: 'ancestor', lineStyle: { width: 2.2 } },
      lineStyle: { color: '#bcc8d8', width: 1.4 },
      label: {
        position: 'top',
        distance: 9,
        formatter: (p) => {
          const n = p.data.node || {}
          // 色块按职务层级取，部门待核的一律用橙色 nAnomaly
          const key = p.data.anomaly ? 'nAnomaly' : `n${n.rank || RANK.STAFF}`
          const meta = [n.rankLabel || n.title, n.dept].filter(Boolean).join(' · ')
          const cnt = n.activeCaseCount || 0
          // 三行定宽：姓名色块 / 层级·部门 / 在手件数
          return [
            `{${key}|${n.name}}`,
            meta ? `{meta|${meta}}` : '',
            cnt > 0 ? `{load|在手 ${cnt}}` : `{idle|在手 0}`
          ].filter(Boolean).join('\n')
        },
        rich: Object.assign(
          {
            meta: { width: labelW.value, align: 'center', fontSize: isMobile.value ? 9 : 10, color: CHART.text3, lineHeight: 15, overflow: 'break' },
            load: { width: labelW.value, align: 'center', fontSize: isMobile.value ? 9.5 : 10.5, fontWeight: 600, color: CHART.warn, lineHeight: 15 },
            idle: { width: labelW.value, align: 'center', fontSize: isMobile.value ? 9.5 : 10.5, color: CHART.text3, lineHeight: 15 }
          },
          nameRich.value
        )
      },
      leaves: { label: { position: 'top', distance: 9 } },
      animationDuration: 300
    }
  ]
}))

const onChartNodeClick = (p) => {
  const n = p?.data?.node
  if (n && n.id != null) onSelect(n)
}

// ---- 图表 ----
const stats = ref({})
const deptType = ref('bar')
const ownerMetric = ref('both')
const loadStats = async () => {
  stats.value = await caseApi.stats({ days: 14 })
}

const deptData = computed(() => stats.value.deptLoad || [])
const ownerData = computed(() => stats.value.ownerLoad || [])

const deptOption = computed(() => {
  const cats = deptData.value.map((d) => d.name)
  const series = [{ name: '在手案件', data: deptData.value.map((d) => d.value) }]
  return deptType.value === 'line'
    ? lineOption({ categories: cats, series, narrow: isMobile.value })
    : barOption({ categories: cats, series, colors: [[CHART.primary]], narrow: isMobile.value })
})

const ownerOption = computed(() => {
  const cats = ownerData.value.map((d) => d.name)
  let series = []
  if (ownerMetric.value === 'total') {
    series = [{ name: '在手案件', data: ownerData.value.map((d) => d.value) }]
  } else if (ownerMetric.value === 'overdue') {
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
    stack: ownerMetric.value === 'both',
    colors: [CHART.primary, CHART.danger],
    narrow: isMobile.value
  })
})

const onDeptClick = (p) => {
  const item = deptData.value[p.dataIndex]
  if (item) ElMessage.info(`部门「${item.name}」在手 ${item.value} 件，其中逾期 ${item.overdue} 件`)
}
const onOwnerClick = (p) => {
  const item = ownerData.value[p.dataIndex]
  if (item) gotoGated(router, '/cases', { employeeId: item.code })
}

const filterNode = (value, data) => {
  if (!value) return true
  const kw = value.toLowerCase()
  return (data.name || '').toLowerCase().includes(kw)
    || (data.dept || '').toLowerCase().includes(kw)
    || (data.title || '').toLowerCase().includes(kw)
}
watch(filterText, (v) => treeRef.value?.filter(v))

const onSelect = async (node) => {
  selected.value = node
  Object.assign(form, emptyForm())
  loadMyCases(node.id)
  const d = await employeeApi.detail(node.id)
  Object.assign(form, {
    id: d.id,
    name: d.name,
    employeeNo: d.employeeNo || '',
    parentId: d.parentId || null,
    dept: d.dept || '',
    title: d.title || '',
    policeGroup: d.policeGroup || 'NONE',
    phone: d.phone || '',
    email: d.email || '',
    sortNo: d.sortNo || 0
  })
}

// 选中员工的在手案件：按期限升序，办结/撤销的不算在手
const loadMyCases = async (employeeId) => {
  myCases.value = []
  if (!employeeId) return
  try {
    const data = await caseApi.page({ employeeId, page: 1, size: 20, sortField: 'deadline', sortOrder: 'asc' })
    myCases.value = (data.list || []).filter((c) => c.status !== 'DONE' && c.status !== 'CANCELLED')
  } catch (e) {
    myCases.value = []
  }
}

const dueText = (c) => {
  if (c.status === 'DONE') return c.statusName
  if (c.status === 'CANCELLED') return c.statusName
  if (c.daysLeft == null) return '无期限'
  if (c.daysLeft < 0) return `逾期 ${-c.daysLeft} 天`
  if (c.daysLeft === 0) return '今天到期'
  return `剩 ${c.daysLeft} 天`
}
const dueClass = (c) => {
  if (c.status === 'DONE') return 'cf-ok'
  if (c.status === 'CANCELLED') return 'cf-muted'
  if (c.daysLeft != null && c.daysLeft < 0) return 'cf-danger'
  if (c.daysLeft != null && c.daysLeft <= 3) return 'cf-warn'
  return 'cf-muted'
}

const gotoCases = () => {
  if (selected.value) gotoGated(router, '/cases', { employeeId: selected.value.id })
}

const openCreate = () => {
  selected.value = null
  myCases.value = []
  resetForm()
}

const onSave = async () => {
  if (!form.name || !form.name.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  const payload = { ...form, parentId: form.parentId || 0 }
  if (form.id) {
    await employeeApi.update(form.id, payload)
    ElMessage.success('已保存')
  } else {
    await employeeApi.create(payload)
    ElMessage.success('已新增')
  }
  resetForm()
  await load()
}

const onDelete = async () => {
  await ElMessageBox.confirm(`确认删除员工「${form.name}」？`, '提示', { type: 'warning' })
  await employeeApi.remove(form.id)
  ElMessage.success('已删除')
  selected.value = null
  myCases.value = []
  resetForm()
  await load()
}

const downloadTemplate = () => {
  window.open(`${base}/employees/template?token=${encodeURIComponent(uploadHeaders['X-Token'])}`, '_blank')
}

const onImportSuccess = (res) => {
  if (res && res.code === 0) {
    const d = res.data
    ElMessage.success(`导入完成：成功 ${d.success} 条 / 共 ${d.total} 条`)
    if (d.errors && d.errors.length) {
      ElMessageBox.alert(d.errors.slice(0, 10).join('<br/>'), '部分数据被修正', {
        dangerouslyUseHTMLString: true
      })
    }
    load()
  } else {
    ElMessage.error(res?.msg || '导入失败')
  }
}
const onImportError = (err) => {
  ElMessage.error('导入失败：' + (err?.message || '未知错误'))
}

onMounted(async () => {
  await load()
  await loadStats()
})
</script>

<style scoped>
/* ---- 组织树：面板撑满所在行，列表/树状图都吃掉剩余高度 ---- */
.cf-org__list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex: 1;
  min-height: 0;
}
.cf-org__tree {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
/* 图例：把「颜色 = 层级 / 橙色 = 部门待核」直接写在界面上。
   项目禁用悬浮提示（会挡住相邻按钮），所以说明必须常驻可见。 */
.cf-org__legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: var(--cf-text-3);
}
.cf-org__legend-t {
  font-weight: 600;
  color: var(--cf-navy);
}
.cf-org__legend-i {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.cf-org__legend-i i {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  display: inline-block;
}
.cf-org__legend-sep {
  width: 1px;
  height: 10px;
  background: var(--cf-border);
}
.cf-org__row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.cf-org__dot {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  flex-shrink: 0;
  display: inline-block;
}
.cf-org__name {
  font-weight: 600;
  color: var(--cf-navy);
  flex-shrink: 0;
}
.cf-org__row .cf-muted {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.cf-org__load {
  flex-shrink: 0;
  color: var(--cf-warn);
  font-variant-numeric: tabular-nums;
}
.cf-org__flag {
  flex-shrink: 0;
  padding: 0 5px;
  height: 17px;
  line-height: 17px;
  border-radius: 2px;
  font-size: 11px;
  color: #fff;
  background: #d98a0b;
}
.cf-org__chart {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  padding-top: 10px;
}
.cf-org__canvas {
  flex: 1;
  min-height: 0;
}
/* 底部概括条 */
.cf-org__foot {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 16px;
  border-top: 1px solid var(--cf-border);
  font-size: 12px;
  color: var(--cf-text-3);
}
.cf-org__foot b {
  color: var(--cf-navy);
  font-variant-numeric: tabular-nums;
}
.cf-org__foot-warn,
.cf-org__foot-warn b {
  color: #d98a0b;
}
.cf-org__foot i {
  width: 1px;
  height: 10px;
  background: var(--cf-border);
}

/* ---- 员工详情：表单固定，下方「在手案件」占满剩余高度 ---- */
.cf-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex: 1;
  min-height: 0;
}
.cf-cases {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 140px;
  border-top: 1px solid var(--cf-border);
  padding-top: 10px;
}
.cf-cases__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 600;
  color: var(--cf-navy);
  margin-bottom: 6px;
}
.cf-cases__more {
  font-weight: 400;
  color: var(--cf-primary);
  cursor: pointer;
}
.cf-cases__more:hover { text-decoration: underline; }
.cf-cases__body {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.cf-cases__hint {
  padding: 12px 2px;
  color: var(--cf-text-3);
  font-size: 12px;
}
.cf-cases__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  height: 30px;
  padding: 0 6px;
  border-radius: 3px;
  font-size: 12px;
}
.cf-cases__item:hover { background: #f4f8ff; }
.cf-cases__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--cf-text);
}
.cf-cases__due {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
</style>
