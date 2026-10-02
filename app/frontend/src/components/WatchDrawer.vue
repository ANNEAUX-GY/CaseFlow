<template>
  <el-drawer v-model="visible" :title="`侦查盯办 · ${detail.caseNo || ''}`" size="620px" destroy-on-close>
    <div v-loading="loading" style="padding: 4px 4px 20px">
      <div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap; margin-bottom: 14px">
        <b style="font-size: 15px">{{ detail.name }}</b>
        <el-tag v-if="detail.investigationStatus" size="small"
          :type="(INVEST_STATUS_META[detail.investigationStatus] || {}).type">
          {{ (INVEST_STATUS_META[detail.investigationStatus] || {}).label }}
        </el-tag>
        <el-tag v-else size="small" type="info">未开始</el-tag>
        <el-tag v-if="detail.caseMeasure && detail.caseMeasure !== 'NONE'" size="small"
          :type="(MEASURE_META[detail.caseMeasure] || {}).type" effect="plain">
          {{ (MEASURE_META[detail.caseMeasure] || {}).label }}
        </el-tag>
        <span class="cf-spacer"></span>
        <!-- 开始侦查：办案人本人的动作（表示"我开始干了"），管理层不代点 -->
        <el-button v-if="isAssignee && canStart" size="small" type="primary" @click="doTransition('START')">开始侦查</el-button>
        <el-button v-if="canSubmit" size="small" type="warning" @click="doTransition('SUBMIT')">提请审批</el-button>
        <template v-if="detail.investigationStatus === 'PENDING_APPROVAL' && isFullAccess">
          <el-button size="small" type="success" @click="doApprove('APPROVE')">同意终结</el-button>
          <el-button size="small" type="danger" plain @click="doApprove('REJECT')">退回补侦</el-button>
        </template>
        <span v-if="canStart && !isAssignee" class="cf-muted" style="font-size: 12px">
          由办案人本人点击开始侦查
        </span>
      </div>

      <!-- 强制措施卡 -->
      <div class="cf-panel">
        <div class="cf-panel__head">
          <span>强制措施与期限</span>
          <el-button v-if="isFullAccess" size="small" @click="measureDlg.visible = true">登记 / 变更</el-button>
        </div>
        <div style="padding: 12px 16px; font-size: 13px">
          <template v-if="detail.caseMeasure && detail.caseMeasure !== 'NONE'">
            <div>措施：<b>{{ (MEASURE_META[detail.caseMeasure] || {}).label }}</b>
              <span class="cf-muted">（{{ (detail.measureDate || '').slice(0, 10) }} 采取）</span></div>
            <div style="margin-top:4px">期限届满：<b :class="deadlineClass">{{ deadlineText }}</b></div>
          </template>
          <span v-else class="cf-muted">无强制措施（属初查案件）</span>
        </div>
      </div>

      <!-- 侦查计划 checklist：办案人自己给自己制定的计划；管理层只读 -->
      <div class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head">
          <span>侦查计划</span>
          <span class="cf-muted">已完成 {{ doneCount }}/{{ activeCount }}</span>
          <el-progress v-if="activeCount > 0" :percentage="Math.round((doneCount / activeCount) * 100)"
            :stroke-width="6" style="width:120px" />
          <el-button v-if="isAssignee" size="small" type="primary" @click="openPlanAdd">新增计划</el-button>
          <span v-else class="cf-muted" style="font-size: 12px">由办案人自行制定，管理层仅可查看</span>
        </div>
        <div style="padding: 10px 16px">
          <div v-for="p in plans" :key="p.id" class="cf-plan">
            <el-checkbox :model-value="p.status === 'DONE'" :disabled="p.status !== 'PENDING' || !isAssignee"
              @change="p.status === 'PENDING' && openDone(p)" />
            <div class="cf-plan__body">
              <div :class="{ 'cf-muted': p.status === 'CANCELLED', 'cf-plan-done': p.status === 'DONE' }">
                {{ p.content }}
                <el-tag v-if="p.status === 'CANCELLED'" size="small" type="info">已取消</el-tag>
                <el-tag v-else-if="planOverdue(p)" size="small" type="danger" effect="dark">逾期</el-tag>
              </div>
              <div class="cf-muted" style="font-size: 12px">
                时限 {{ (p.plannedAt || '未设').slice(0, 16) }}
                <template v-if="p.doneAt"> · 完成于 {{ p.doneAt.slice(0, 16) }}</template>
                <template v-if="p.doneNote"> · {{ p.doneNote }}</template>
              </div>
            </div>
            <div v-if="p.status === 'PENDING' && isAssignee" class="cf-plan__ops">
              <el-button link type="primary" size="small" @click="openPlanEdit(p)">编辑</el-button>
              <el-button link type="danger" size="small" @click="cancelPlan(p)">取消</el-button>
            </div>
          </div>
          <div v-if="!plans.length" class="cf-muted" style="padding: 8px 0">
            {{ isAssignee ? '暂无侦查计划，点击右上角「新增计划」自行制定' : '暂无侦查计划（由办案人自行制定）' }}
          </div>
        </div>
      </div>

      <!-- 审批记录 -->
      <div class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head"><span>审批记录</span><span class="cf-muted">共 {{ approvals.length }} 条</span></div>
        <div style="padding: 12px 16px">
          <el-timeline v-if="approvals.length">
            <el-timeline-item v-for="a in approvals" :key="a.id" :timestamp="(a.createdAt || '').slice(0, 16)"
              placement="top" :type="a.result === 'RETURNED' ? 'danger' : 'success'">
              <div>
                <b>{{ a.approveType === 'MEASURE' ? '强制措施确认' : (a.result === 'RETURNED' ? '退回补侦' : '同意侦查终结') }}</b>
                　{{ a.approverName }}
              </div>
              <div v-if="a.comment" class="cf-muted" style="font-size: 12px">{{ a.comment }}</div>
            </el-timeline-item>
          </el-timeline>
          <span v-else class="cf-muted">暂无审批记录</span>
        </div>
      </div>

      <!-- 领导意见与落实反馈：管理层在此提意见，办案人的落实情况同步展示 -->
      <div class="cf-panel" style="margin-top: 12px">
        <OpinionPanel :case-id="detail.id" :detail="detail" :is-full-access="isFullAccess" @changed="after" />
      </div>
    </div>

    <!-- 计划新增/编辑 -->
    <el-dialog v-model="planDlg.visible" :title="planDlg.id ? '编辑侦查计划' : '新增侦查计划'" width="440px" append-to-body>
      <el-form label-width="80px">
        <el-form-item label="计划内容" required>
          <el-input v-model="planDlg.content" type="textarea" :rows="2" placeholder="如：调取案发现场周边监控" maxlength="500" />
        </el-form-item>
        <el-form-item label="完成时限">
          <el-date-picker v-model="planDlg.plannedAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选填，逾期将预警" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="planDlg.loading" @click="submitPlan">确定</el-button>
      </template>
    </el-dialog>

    <!-- 完成计划 -->
    <el-dialog v-model="doneDlg.visible" title="完成侦查计划" width="440px" append-to-body>
      <el-form label-width="80px">
        <el-form-item label="计划"><span>{{ doneDlg.row?.content }}</span></el-form-item>
        <el-form-item label="完成情况">
          <el-input v-model="doneDlg.note" type="textarea" :rows="2" placeholder="选填，如：已调取 3 处监控并刻盘" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="doneDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="doneDlg.loading" @click="submitDone">确认完成</el-button>
      </template>
    </el-dialog>

    <!-- 审批意见（退回必填） -->
    <el-dialog v-model="approveDlg.visible" :title="approveDlg.action === 'REJECT' ? '退回补侦（须填意见）' : '同意侦查终结'"
      width="440px" append-to-body>
      <el-form label-width="80px">
        <el-form-item :label="approveDlg.action === 'REJECT' ? '退回意见' : '审批意见'"
          :required="approveDlg.action === 'REJECT'">
          <el-input v-model="approveDlg.comment" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approveDlg.visible = false">取消</el-button>
        <el-button :type="approveDlg.action === 'REJECT' ? 'danger' : 'success'" :loading="approveDlg.loading"
          @click="submitApprove">确定</el-button>
      </template>
    </el-dialog>

    <!-- 强制措施登记 -->
    <el-dialog v-model="measureDlg.visible" title="登记 / 变更强制措施" width="460px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="措施类型" required>
          <el-select v-model="measureDlg.measure" style="width: 100%">
            <el-option label="无（回到初查）" value="NONE" />
            <el-option label="刑拘" value="DETENTION" />
            <el-option label="取保候审" value="BAIL" />
            <el-option label="监视居住" value="RESIDENCE" />
          </el-select>
        </el-form-item>
        <el-form-item label="措施日期">
          <el-date-picker v-model="measureDlg.measureDate" type="date" value-format="YYYY-MM-DD"
            :disabled="measureDlg.measure === 'NONE'" style="width: 100%" />
        </el-form-item>
        <el-form-item label="期限届满">
          <el-date-picker v-model="measureDlg.detainDeadline" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
            :disabled="measureDlg.measure === 'NONE'" placeholder="留空按默认推算（刑拘+30天/取保+12月/监居+6月）"
            style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="measureDlg.comment" placeholder="选填" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="measureDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="measureDlg.loading" @click="submitMeasure">确定</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { caseApi, watchApi } from '../api'
import OpinionPanel from './OpinionPanel.vue'
import { useUserStore } from '../store/user'
import { INVEST_STATUS_META, MEASURE_META } from '../utils/format'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  caseId: { type: [Number, null], default: null }
})
const emit = defineEmits(['update:modelValue', 'done'])
const userStore = useUserStore()
const isFullAccess = computed(() => userStore.isFullAccess)

const visible = ref(false)
const loading = ref(false)
const detail = ref({})
const plans = ref([])
const approvals = ref([])

watch(() => props.modelValue, async (v) => {
  visible.value = v
  if (v && props.caseId) await reload()
})
watch(visible, (v) => emit('update:modelValue', v))

const reload = async () => {
  loading.value = true
  try {
    detail.value = await caseApi.detail(props.caseId)
    plans.value = await watchApi.plans(props.caseId)
    approvals.value = await watchApi.approvals(props.caseId)
  } finally { loading.value = false }
}

const activeCount = computed(() => plans.value.filter((p) => p.status !== 'CANCELLED').length)
const doneCount = computed(() => plans.value.filter((p) => p.status === 'DONE').length)
const planOverdue = (p) => p.status === 'PENDING' && p.plannedAt && p.plannedAt.slice(0, 19) < new Date().toISOString().slice(0, 19).replace('T', ' ')
const canStart = computed(() => (!detail.value.investigationStatus || detail.value.investigationStatus === 'PENDING_INITIAL'))
const canSubmit = computed(() => detail.value.investigationStatus === 'INVESTIGATING')

/** 当前登录人是否本案现职承办人（办案人）：决定开始侦查 / 计划维护等"工作者动作" */
const isAssignee = computed(() => {
  const myEmp = userStore.userInfo?.employeeId
  if (!myEmp) return false
  return (detail.value.assignHistory || []).some(
    (a) => a.status === 'ACTIVE' && String(a.employeeId) === String(myEmp))
})

const deadlineText = computed(() => {
  const d = detail.value.detainDaysLeft
  if (d == null) return '未登记期限'
  if (d < 0) return `已超期 ${Math.abs(d)} 天`
  return `剩 ${d} 天（${(detail.value.detainDeadlineText || '').slice(0, 10)}）`
})
const deadlineClass = computed(() => {
  const d = detail.value.detainDaysLeft
  if (d == null) return 'cf-muted'
  if (d < 0) return 'cf-danger'
  if (d <= 7) return 'cf-warn'
  return 'cf-muted'
})

const doTransition = async (action) => {
  await watchApi.transition(props.caseId, { action })
  ElMessage.success('操作成功')
  await after()
}
const approveDlg = reactive({ visible: false, action: 'APPROVE', comment: '', loading: false })
const doApprove = (action) => {
  approveDlg.action = action
  approveDlg.comment = ''
  approveDlg.visible = true
}
const submitApprove = async () => {
  if (approveDlg.action === 'REJECT' && !approveDlg.comment.trim()) {
    ElMessage.warning('退回补侦必须填写意见')
    return
  }
  approveDlg.loading = true
  try {
    await watchApi.transition(props.caseId, { action: approveDlg.action, comment: approveDlg.comment })
    ElMessage.success(approveDlg.action === 'REJECT' ? '已退回补侦' : '已同意侦查终结')
    approveDlg.visible = false
    await after()
  } finally { approveDlg.loading = false }
}

const measureDlg = reactive({ visible: false, measure: 'DETENTION', measureDate: '', detainDeadline: '', comment: '', loading: false })
const submitMeasure = async () => {
  measureDlg.loading = true
  try {
    await watchApi.measure(props.caseId, {
      measure: measureDlg.measure,
      measureDate: measureDlg.measureDate || undefined,
      detainDeadline: measureDlg.detainDeadline || undefined,
      comment: measureDlg.comment
    })
    ElMessage.success('已登记')
    measureDlg.visible = false
    await after()
  } finally { measureDlg.loading = false }
}

const planDlg = reactive({ visible: false, id: null, content: '', plannedAt: '', loading: false })
const openPlanAdd = () => { planDlg.id = null; planDlg.content = ''; planDlg.plannedAt = ''; planDlg.visible = true }
const openPlanEdit = (p) => { planDlg.id = p.id; planDlg.content = p.content; planDlg.plannedAt = p.plannedAt || ''; planDlg.visible = true }
const submitPlan = async () => {
  if (!planDlg.content.trim()) { ElMessage.warning('请填写计划内容'); return }
  planDlg.loading = true
  try {
    if (planDlg.id) await watchApi.updatePlan(planDlg.id, { content: planDlg.content, plannedAt: planDlg.plannedAt })
    else await watchApi.addPlan(props.caseId, { content: planDlg.content, plannedAt: planDlg.plannedAt })
    ElMessage.success('已保存')
    planDlg.visible = false
    await after()
  } finally { planDlg.loading = false }
}

const doneDlg = reactive({ visible: false, row: null, note: '', loading: false })
const openDone = (p) => { doneDlg.row = p; doneDlg.note = ''; doneDlg.visible = true }
const submitDone = async () => {
  doneDlg.loading = true
  try {
    await watchApi.donePlan(doneDlg.row.id, doneDlg.note)
    ElMessage.success('已完成该计划')
    doneDlg.visible = false
    await after()
  } finally { doneDlg.loading = false }
}
const cancelPlan = async (p) => {
  try {
    await ElMessageBox.confirm(`确定取消计划「${p.content.slice(0, 20)}」吗？`, '取消计划', { type: 'warning' })
  } catch { return }
  await watchApi.cancelPlan(p.id)
  ElMessage.success('已取消')
  await after()
}

const after = async () => { await reload(); emit('done') }
</script>

<style>
.cf-plan { display: flex; align-items: flex-start; gap: 10px; padding: 8px 0; border-bottom: 1px dashed #dfe4ea }
.cf-plan:last-child { border-bottom: none }
.cf-plan__body { flex: 1 }
.cf-plan__ops { white-space: nowrap }
.cf-plan-done { text-decoration: line-through; color: #8a929e }
.cf-danger { color: #c62a2a }
.cf-warn { color: #d98a0b }
</style>
