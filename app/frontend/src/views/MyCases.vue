<template>
  <div class="cf-page">
    <!-- 实名范围提示：先说清「这些案子为什么是你的」，避免用户以为系统漏了案件 -->
    <div class="cf-mine-banner" :class="{ 'cf-mine-banner--warn': !bound }">
      <span class="cf-mine-banner__name">{{ userStore.userInfo?.displayName || userStore.userInfo?.username || '' }}</span>
      <span class="cf-mine-banner__role">{{ userStore.roleName }}</span>
      <span class="cf-mine-banner__desc">{{ scopeDesc }}</span>
      <span class="cf-mine-banner__grow" />
      <el-button size="small" @click="refreshAll">刷新</el-button>
    </div>

    <!-- 本人在手概览：数字全部只统计本人名下案件（后端已按角色收敛） -->
    <div class="cf-kpi-grid cf-kpi-grid--mine">
      <div class="cf-kpi" @click="pick({ status: '' })">
        <div class="cf-kpi__value">{{ data.totalCase || 0 }}</div>
        <div class="cf-kpi__label">名下案件</div>
      </div>
      <div class="cf-kpi" @click="pick({ status: 'OPEN' })">
        <div class="cf-kpi__value">{{ openCount }}</div>
        <div class="cf-kpi__label">在办中</div>
      </div>
      <div class="cf-kpi cf-kpi--danger" @click="pick({ status: 'OPEN', dueBucket: 'OVERDUE' })">
        <div class="cf-kpi__value">{{ data.overdue || 0 }}</div>
        <div class="cf-kpi__label">已逾期</div>
      </div>
      <div class="cf-kpi cf-kpi--warn" @click="pick({ status: 'OPEN', dueBucket: 'D3' })">
        <div class="cf-kpi__value">{{ data.dueIn3Days || 0 }}</div>
        <div class="cf-kpi__label">3天内到期</div>
      </div>
      <!-- 措施到期（2026-10-24）：民警看不到工作台，但"我名下哪些案子措施要到期了"
           恰恰是承办人最该知道的，所以在自己的案件页也给一格。 -->
      <div class="cf-kpi cf-kpi--danger" @click="scrollToMeasure">
        <div class="cf-kpi__value">{{ (data.measureExpired || 0) + (data.measureExpiring || 0) }}</div>
        <div class="cf-kpi__label">措施到期</div>
      </div>
    </div>

    <!-- 措施到期明细：只在真有到期/临期时出现，没数据不占版面 -->
    <div v-if="(data.measureAlerts || []).length" id="cf-mine-measure" class="cf-panel">
      <div class="cf-panel__head">
        <span :class="(data.measureExpired || 0) > 0 ? 'cf-danger' : ''">我名下到期的强制措施</span>
        <span class="cf-panel__head-tip">到期即该措施结束，需登记下一个措施（变更要经领导批准）</span>
      </div>
      <div class="cf-measure-list">
        <div v-for="a in data.measureAlerts" :key="a.caseId" class="cf-measure-list__row" @click="openDetail({ id: a.caseId })">
          <span class="cf-measure-list__name">{{ a.caseName }}</span>
          <span class="cf-muted">{{ a.measureName }}</span>
          <span class="cf-muted">届满 {{ (a.deadline || '').slice(0, 10) }}</span>
          <el-tag size="small" :type="a.expired ? 'danger' : 'warning'">
            {{ a.expired ? `已到期 ${-a.daysLeft} 天` : (a.daysLeft === 0 ? '今天到期' : `${a.daysLeft} 天后到期`) }}
          </el-tag>
          <span class="cf-spacer" />
          <!-- 到期后要"由领导或主办/经办人登记下一个措施"：按钮直接开登记弹窗（提交后按角色走审批） -->
          <el-button v-if="a.expired" link type="primary" size="small" @click.stop="openMeasure(a)">登记下一措施</el-button>
          <el-button v-else link type="primary" size="small" @click.stop="openDetail({ id: a.caseId })">查看</el-button>
        </div>
      </div>
    </div>

    <div class="cf-toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="案件名 / 编号 / 备注"
        clearable
        style="width: 220px"
        @keyup.enter="reload"
      />
      <el-select v-model="query.status" placeholder="办理状态" clearable style="width: 130px" @change="reload">
        <el-option label="在办（未办结）" value="OPEN" />
        <el-option label="已指派" value="ASSIGNED" />
        <el-option label="处理中" value="IN_PROGRESS" />
        <el-option label="已办结" value="DONE" />
        <el-option label="已撤销" value="CANCELLED" />
      </el-select>
      <el-select v-model="query.dueBucket" placeholder="到期情况" clearable style="width: 130px" @change="reload">
        <el-option label="已逾期" value="OVERDUE" />
        <el-option label="今天到期" value="TODAY" />
        <el-option label="3天内到期" value="D3" />
        <el-option label="7天内到期" value="D7" />
        <el-option label="未设期限" value="NONE" />
      </el-select>
      <el-button @click="reset">重置</el-button>
      <span class="cf-spacer" />
      <span class="cf-muted">共 {{ total }} 条</span>
    </div>

    <div class="cf-panel" style="padding: 4px">
      <CaseTable
        :rows="rows"
        :loading="loading"
        :min-body="360"
        show-case-time
        @open="openDetail"
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

    <CaseDetailDrawer v-model="detailVisible" :case-id="currentId" :anchor="locateAnchor" @done="refreshAll" />
    <MeasureDialog
      v-model="measureVisible"
      :case-id="measureTarget.id"
      :case-type="measureTarget.caseType"
      :current-measure="measureTarget.measure"
      @done="refreshAll"
    />

    <PageFooter />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { caseApi } from '../api'
import CaseTable from '../components/CaseTable.vue'
import CaseDetailDrawer from '../components/CaseDetailDrawer.vue'
import MeasureDialog from '../components/MeasureDialog.vue'
import PageFooter from '../components/PageFooter.vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../store/user'

const userStore = useUserStore()

/** 是否已绑定员工档案：绑定后按「本人经办」取案件，未绑定只能退回「本人录入」 */
const bound = computed(() => !!userStore.userInfo?.employeeId)
const scopeDesc = computed(() =>
  bound.value
    ? '以下为系统按实名匹配到的本人名下案件（本人主办或协办）'
    : '当前账号未关联员工档案，暂只显示本人录入的案件；请联系管理员在「账号管理」中关联档案'
)

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const data = ref({})

const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  status: '',
  dueBucket: ''
})

const openCount = computed(() => data.value.openCase || 0)

const load = async () => {
  loading.value = true
  try {
    const params = { ...query, onlyMine: true }
    Object.keys(params).forEach((k) => {
      if (params[k] === '' || params[k] == null) delete params[k]
    })
    const page = await caseApi.page(params)
    rows.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

// 概览数字走 dashboard 的「只看本人」口径，与列表同源，不会出现两处对不上
const loadOverview = async () => {
  data.value = await caseApi.dashboard({ onlyMine: true })
}

const refreshAll = () => {
  load()
  loadOverview()
}

/** KPI 点「措施到期」滚到明细条（明细就在本页，跳页反而多一步） */
const scrollToMeasure = () => {
  const el = document.getElementById('cf-mine-measure')
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// 措施登记（2026-10-24）：承办人没有「案件盯办」页权限，所以在自己的案件页给入口。
// 领导点保存直接生效；承办人点保存只生成待审批申请（表单内部按角色提示）。
const measureVisible = ref(false)
const measureTarget = ref({ id: null, caseType: '', measure: '' })
const openMeasure = (a) => {
  measureTarget.value = { id: a.caseId, caseType: a.caseType || '', measure: a.measure || '' }
  measureVisible.value = true
}

const pick = (patch) => {
  Object.assign(query, { page: 1, keyword: '', status: '', dueBucket: '' }, patch)
  load()
}

const reset = () => {
  Object.assign(query, { page: 1, keyword: '', status: '', dueBucket: '' })
  load()
}

const reload = () => {
  query.page = 1
  load()
}

const detailVisible = ref(false)
const currentId = ref(null)
const openDetail = (row) => {
  currentId.value = row.id
  detailVisible.value = true
}

const route = useRoute()
const router = useRouter()

/**
 * 从「我的待办」点进来时自动打开该案件详情（需求：点待办直接跳详情并自动打开）。
 *
 * <p>两个细节：
 * 1. 刷新后仍要生效——所以不只在 onMounted 判一次，还 watch query，
 *    这样用户在本页再点另一条待办（或浏览器前进后退）也能正确响应。
 * 2. 打开后把 caseId 从地址栏抹掉——否则刷新会重复打开，
 *    而且用户手动点别的案件时地址栏还残留着旧 id 会引发误判。
 */
/** 信箱跳转的定位锚点；null=无定位（普通打开案件详情） */
const locateAnchor = ref(null)

/**
 * 信箱跳转的定位锚点（2026-10-08）。
 * 路由 query：?caseId=12&todoId=34&subtaskId=9
 * 锚点用完即从地址栏抹掉（与 caseId 同一约定），否则刷新会重复定位。
 */
const consumeOpenCase = () => {
  const q0 = route.query || {}
  const cid = q0.caseId
  if (!cid) return
  currentId.value = Number(cid)
  const anchor = {
    todoId: q0.todoId ? Number(q0.todoId) : null,
    subtaskId: q0.subtaskId ? Number(q0.subtaskId) : null
  }
  locateAnchor.value = anchor.todoId || anchor.subtaskId ? anchor : null
  detailVisible.value = true
  // 抹掉定位参数（caseId 一并抹，避免刷新重复弹抽屉）
  const q = { ...q0 }
  delete q.caseId; delete q.todoId; delete q.subtaskId
  router.replace({ path: '/my-cases', query: q })
}

onMounted(() => {
  refreshAll()
  consumeOpenCase()
})
watch(() => route.query.caseId, consumeOpenCase)

</script>
