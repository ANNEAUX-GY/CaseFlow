<template>
  <el-dialog
    v-model="visible"
    :title="data?.name ? `${data.name} · 承办负荷` : '承办负荷'"
    :width="isMobile ? '96%' : '720px'"
    append-to-body
    class="cf-wl-dialog"
    @closed="onClosed"
  >
    <div v-if="loading" class="cf-wl__loading">加载中…</div>

    <template v-else-if="data && data.found">
      <!-- 基本信息 -->
      <div class="cf-wl__head">
        <span class="cf-wl__tag" :class="'is-' + (data.policeGroup || 'NONE')">
          {{ data.policeGroupName || '不限' }}
        </span>
        <span v-if="data.employeeNo" class="cf-muted">工号 {{ data.employeeNo }}</span>
        <span v-if="data.dept" class="cf-muted">{{ data.dept }}</span>
        <span v-if="data.title" class="cf-muted">{{ data.title }}</span>
      </div>

      <!-- 汇总数字：一眼看清在办规模与紧迫度 -->
      <div class="cf-wl__stats">
        <div class="cf-wl__stat">
          <div class="cf-wl__stat-num">{{ data.ownerCount || 0 }}</div>
          <div class="cf-wl__stat-label">主办</div>
        </div>
        <div class="cf-wl__stat">
          <div class="cf-wl__stat-num">{{ data.memberCount || 0 }}</div>
          <div class="cf-wl__stat-label">协办</div>
        </div>
        <div class="cf-wl__stat">
          <div class="cf-wl__stat-num">{{ data.totalCount || 0 }}</div>
          <div class="cf-wl__stat-label">合计在办</div>
        </div>
        <div class="cf-wl__stat" :class="{ 'is-danger': (data.overdueCount || 0) > 0 }">
          <div class="cf-wl__stat-num">{{ data.overdueCount || 0 }}</div>
          <div class="cf-wl__stat-label">已超期</div>
        </div>
        <div class="cf-wl__stat" :class="{ 'is-warn': (data.dueSoonCount || 0) > 0 }">
          <div class="cf-wl__stat-num">{{ data.dueSoonCount || 0 }}</div>
          <div class="cf-wl__stat-label">3 天内到期</div>
        </div>
      </div>

      <!-- 阶段分布 -->
      <div class="cf-wl__modules">
        <span class="cf-wl__mod">初查 <b>{{ data.initialCount || 0 }}</b></span>
        <span class="cf-wl__mod">刑拘在办 <b>{{ data.detentionCount || 0 }}</b></span>
        <span class="cf-wl__mod">取保及监居 <b>{{ data.bailCount || 0 }}</b></span>
      </div>

      <!-- 逐案明细：点案件行在本页直接打开案件详情抽屉（不跳转页面） -->
      <el-table v-if="(data.cases || []).length" :data="data.cases" size="small"
        :row-class-name="rowClass" style="width: 100%; margin-top: 10px" max-height="320"
        @row-click="openCase">
        <el-table-column label="案件编号" width="140">
          <template #default="{ row }">
            <span class="cf-wl__case-no">{{ row.caseNo }}</span>
            <el-tag v-if="row.current" size="small" type="primary" effect="dark"
              style="margin-left: 4px">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="案件名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'cf-muted': row.assignRole !== 'OWNER' }">{{ row.caseName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="66">
          <template #default="{ row }">
            <el-tag size="small" :type="row.assignRole === 'OWNER' ? 'primary' : 'info'" effect="plain">
              {{ row.assignRole === 'OWNER' ? '主办' : '协办' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="阶段" width="88">
          <template #default="{ row }">{{ row.moduleName }}</template>
        </el-table-column>
        <el-table-column label="任务进度" width="96">
          <template #default="{ row }">
            <span v-if="row.planTotal > 0">{{ row.planDone }}/{{ row.planTotal }}</span>
            <span v-else class="cf-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="期限" width="150">
          <template #default="{ row }">
            <span v-if="row.deadline" :class="['cf-wl__due', dueCls(row)]">
              {{ dueText(row) }}
            </span>
            <span v-else class="cf-muted">无期限</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-else class="cf-wl__empty">该民警当前没有在办案件</div>
    </template>

    <div v-else class="cf-wl__empty">未找到该员工档案</div>

    <template #footer>
      <span class="cf-muted" style="font-size: 12px">
        点击案件行可在本页查看案件详情 · 排序：主办优先 › 已超期优先 › 期限近的优先
      </span>
      <span class="cf-spacer"></span>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>

  <!-- 案件详情抽屉：盖在负荷弹窗上方，关闭后回到本弹窗（不跳转路由） -->
  <CaseDetailDrawer v-model="detailVisible" :case-id="detailCaseId" />
</template>

<script setup>
import { ref, watch } from 'vue'
import { caseApi } from '../api'
import { useDevice } from '../utils/device'
import CaseDetailDrawer from './CaseDetailDrawer.vue'

/**
 * 民警承办负荷详情（盯办详情点主办人/协办人时弹出）。
 *
 * <p>数据来自后端 GET /cases/staff/{id}/workload，已按本人可见范围收敛，
 * 且只返回案件概要（编号/名称/期限/阶段/进度），不含案情细节。
 *
 * <p>用 show(employeeId, caseId) 打开：父组件不用先改 props 再置visible，
 * 少一步状态同步，出错概率低。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  employeeId: { type: [Number, null], default: null },
  /** 当前正在查看的案件，后端会标记 isCurrent 便于高亮 */
  caseId: { type: [Number, null], default: null }
})
const emit = defineEmits(['update:modelValue', 'closed'])

const { isMobile } = useDevice()
const visible = ref(props.modelValue)
const loading = ref(false)
const data = ref(null)

/** 本次要查的员工与案件（show() 的入参存在这里，不走 props） */
let targetEmpId = null
let targetCaseId = null

const fetchData = async () => {
  if (targetEmpId == null) return
  loading.value = true
  try {
    data.value = await caseApi.staffWorkload(targetEmpId, targetCaseId)
  } catch (e) {
    data.value = null
  } finally {
    loading.value = false
  }
}

const show = async (employeeId, caseId) => {
  targetEmpId = employeeId
  targetCaseId = caseId ?? null
  visible.value = true
  await fetchData()
}

// 父组件也可能用 v-model + props 驱动，这里兼容一次
watch(() => props.modelValue, (v) => {
  if (!v) return
  if (props.employeeId != null) {
    targetEmpId = props.employeeId
    targetCaseId = props.caseId ?? null
  }
  fetchData()
})

// ---- 案件行点击 → 本页打开案件详情抽屉 ----
const detailVisible = ref(false)
const detailCaseId = ref(null)
const openCase = (row) => {
  if (!row || !row.caseId) return
  detailCaseId.value = row.caseId
  detailVisible.value = true
}

const dueText = (row) => {
  const dt = String(row.deadline).replace('T', ' ').slice(5, 16)
  if (row.overdue) return `${dt}（已超期 ${-row.daysLeft} 天）`
  if (row.daysLeft === 0) return `${dt}（今天）`
  return `${dt}（剩 ${row.daysLeft} 天）`
}

const dueCls = (row) => {
  if (row.overdue) return 'is-overdue'
  if (row.daysLeft != null && row.daysLeft <= 3) return 'is-soon'
  return ''
}

const rowClass = ({ row }) => (row.overdue ? 'cf-wl__row-overdue' : '')

const onClosed = () => {
  emit('closed')
  emit('update:modelValue', false)
}

defineExpose({ show })
</script>

<style>
.cf-wl__loading { padding: 30px 0; text-align: center; color: #8a929e }
.cf-wl__head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px }
.cf-wl__tag { padding: 2px 10px; border-radius: 3px; color: #fff; font-size: 12px; font-weight: 600 }
.cf-wl__tag.is-INITIAL { background: #1b4a8c }
.cf-wl__tag.is-CLEAR { background: #d98a0b }
.cf-wl__tag.is-NONE { background: #8a929e }
.cf-wl__stats { display: flex; gap: 8px; margin-bottom: 10px }
.cf-wl__stat {
  flex: 1; text-align: center; padding: 10px 4px;
  border: 1px solid #dfe4ea; border-radius: 4px; background: #fbfcfe;
}
.cf-wl__stat.is-danger { border-color: #c62a2a; background: #fdf6f6 }
.cf-wl__stat.is-warn { border-color: #d98a0b; background: #fffaf1 }
.cf-wl__stat-num { font-size: 20px; font-weight: 700; color: #1b2430; line-height: 1.2 }
.cf-wl__stat-label { font-size: 12px; color: #8a929e; margin-top: 2px }
.cf-wl__modules { display: flex; gap: 14px; font-size: 12px; color: #5a6472 }
.cf-wl__mod b { color: #1b4a8c; font-size: 13px; margin-left: 3px }
.cf-wl__case-no { font-family: Consolas, monospace; font-size: 12px }
.cf-wl__due { font-size: 12px; color: #5a6472 }
.cf-wl__due.is-soon { color: #d98a0b }
.cf-wl__due.is-overdue { color: #c62a2a; font-weight: 600 }
.cf-wl__row-overdue { background: #fdf6f6 }
/* 案件行可点击：指针 + 悬停提示色（限定本弹窗内，不影响其他表格） */
.cf-wl-dialog tbody .el-table__row { cursor: pointer }
.cf-wl-dialog tbody .el-table__row:hover > td { background: #eef3fa !important }
.cf-wl__empty { padding: 30px 0; text-align: center; color: #8a929e; font-size: 13px }

@media (max-width: 768px) {
  .cf-wl__stats { flex-wrap: wrap }
  .cf-wl__stat { min-width: 28%; }
}
</style>
