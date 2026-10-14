<template>
  <el-drawer v-model="visible" :title="`侦查盯办 · ${detail.caseNo || ''}`" size="620px" destroy-on-close>
    <div v-if="detail.id" v-loading="loading" style="padding: 4px 4px 20px">
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
        <el-button v-if="canSubmit" size="small" type="warning" @click="doTransition('SUBMIT')">申请已提交审核</el-button>
        <template v-if="detail.investigationStatus === 'PENDING_APPROVAL' && isFullAccess">
          <el-button size="small" type="success" @click="doApprove('APPROVE')">同意终结</el-button>
          <el-button size="small" type="danger" plain @click="doApprove('REJECT')">退回补侦</el-button>
        </template>
        <span v-if="canStart && !isAssignee" class="cf-muted" style="font-size: 12px">
          由办案人本人点击开始侦查
        </span>
      </div>

      <div class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head"><span>具体时限与提醒</span></div>
        <div style="padding: 12px 16px">
          <template v-if="detail.caseMeasure === 'DETENTION'">
            <div style="margin-bottom: 8px">当前：刑拘 {{ deadlineView.detentionTerm || '3' }} 天案
              <el-tag v-if="deadlineView.paused" type="warning" size="small">停止计算中</el-tag>
            </div>
            <div v-if="isFullAccess" style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px">
              <el-button size="small" @click="changeTerm('7')">变更为7天案</el-button>
              <el-button size="small" @click="changeTerm('30')">变更为30天案</el-button>
              <el-button v-if="!deadlineView.paused" size="small" @click="changeTerm('PAUSE')">停止计算（精神病鉴定）</el-button>
              <el-button v-else size="small" @click="changeTerm('RESUME')">恢复计算</el-button>
            </div>
            <div v-if="deadlineView.canSubmit && detail.investigationStatus !== 'PENDING_APPROVAL'" class="cf-muted" style="margin-bottom:8px">预审或法制已收件，可以申请已提交审核。</div>
          </template>
          <div v-if="detail.caseType === 'ADMINISTRATIVE' && !detail.acceptTime" style="margin-bottom:10px">
            <span class="cf-muted">请先登记受案时间，系统再计算第3/10/20/30/60天节点。</span>
            <el-button v-if="isFullAccess" link type="primary" @click="openRecord('ACCEPT')">登记受案时间</el-button>
          </div>
          <el-table v-if="deadlineView.items?.length" :data="deadlineView.items" size="small" max-height="350">
            <el-table-column prop="label" label="办理节点" min-width="220" />
            <el-table-column label="截止/提醒" width="160">
              <template #default="{ row }">
                <div>{{ dayText(row.dueAt) }}</div>
                <div class="cf-muted">{{ dayText(row.remindAt) }} 起提醒</div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="95">
              <template #default="{ row }">
                <el-tag v-if="row.completedAt" size="small" type="success">已登记</el-tag>
                <el-tag v-else-if="deadlineView.paused" size="small" type="info">已暂停</el-tag>
                <el-tag v-else-if="row.overdue" size="small" type="danger">已逾期</el-tag>
                <el-tag v-else-if="row.reminding" size="small" type="warning">提醒中</el-tag>
                <span v-else class="cf-muted">未到期</span>
              </template>
            </el-table-column>
            <el-table-column v-if="isFullAccess" label="操作" width="80">
              <template #default="{ row }"><el-button v-if="recordKey(row.key)" link type="primary" @click="openRecord(recordKey(row.key))">录入时间</el-button></template>
            </el-table-column>
          </el-table>
          <span v-else-if="detail.caseType !== 'ADMINISTRATIVE'" class="cf-muted">登记强制措施日期后显示具体时限。</span>
          <div v-if="deadlineView.state?.lastSummonsAt" class="cf-muted" style="margin-top:8px">上次传讯：{{ dayText(deadlineView.state.lastSummonsAt) }}</div>
        </div>
      </div>

      <!-- 主办人 / 盯办人（2026-10-04）：可点名字看该人的承办负荷 -->
      <div class="cf-panel" style="margin-bottom: 12px">
        <div class="cf-panel__head">
          <span>主办人与盯办人</span>
          <span class="cf-muted">点名字可查看该人正在主办/经办多少案子</span>
        </div>
        <div style="padding: 12px 16px; font-size: 13px">
          <div v-if="assignees.length" class="cf-watch__owners">
            <div v-for="a in assignees" :key="a.id" class="cf-watch__owner">
              <span class="cf-watch__owner-role"
                :class="a.assignRole === 'OWNER' ? 'is-owner' : 'is-member'">
                {{ a.assignRole === 'OWNER' ? '主办人' : '协办人' }}
              </span>
              <a class="cf-watch__owner-name" @click="openWorkload(a)">{{ a.employeeName }}</a>
              <el-tag v-if="a.policeGroup" size="small"
                :type="(POLICE_GROUP_META[a.policeGroup] || {}).type" effect="plain">
                {{ a.policeGroupName || policeGroupLabel(a.policeGroup) }}
              </el-tag>
            </div>
          </div>
          <span v-else class="cf-muted">尚未指派承办人</span>
        </div>
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


            <!-- 办案阶段（2026-10-12）：阶段任务 = 案件待办。
           这一条阶段做完了不自动跳，由领导在这里确认转入下一阶段。 -->
      <div class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head">
          <span>办案阶段</span>
          <span class="cf-panel__head-tip">本阶段待办全部办完后，由领导确认转入下一阶段</span>
        </div>
        <div style="padding: 12px 16px">
          <CaseStageBar :case-id="detail.id" @changed="after" />
        </div>
      </div>

      <!-- 案件待办（含领导意见）：与案件详情抽屉同一个合并面板，落实状态单一来源 -->
      <div class="cf-panel" style="margin-top: 12px">
        <CaseTodoPanel :case-id="detail.id" @changed="after" />
      </div>
    </div>

    <!-- 民警承办负荷详情：点「主办人 / 协办人」名字打开 -->
    <StaffWorkloadDialog ref="workloadRef" />

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
    <el-dialog v-model="recordDlg.visible" title="录入办理时间" width="410px" append-to-body>
      <el-date-picker v-model="recordDlg.at" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
      <template #footer>
        <el-button @click="recordDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="recordDlg.loading" @click="submitRecord">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="measureDlg.visible" title="登记 / 变更强制措施" width="460px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="措施类型" required>
          <!-- 取值与建案表单一致（法定五种 + 无），别在这边另起一套：
               两边不一样会出现"建案选了逮捕，盯办下拉里却没有逮捕可改"。 -->
          <el-select v-model="measureDlg.measure" style="width: 100%">
            <el-option label="无（回到初查）" value="NONE" />
            <el-option v-for="m in MEASURE_CHOICES" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="措施日期">
          <el-date-picker v-model="measureDlg.measureDate" type="date" value-format="YYYY-MM-DD"
            :disabled="measureDlg.measure === 'NONE'" style="width: 100%" />
        </el-form-item>
        <el-form-item label="期限届满">
          <el-date-picker v-model="measureDlg.detainDeadline" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
            :disabled="measureDlg.measure === 'NONE'" placeholder="留空按默认推算（拘留先3天/逮捕2月/取保1年/监居6月）"
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
import { ElMessage } from 'element-plus'
import { caseApi, watchApi } from '../api'
import CaseStageBar from './CaseStageBar.vue'
import CaseTodoPanel from './CaseTodoPanel.vue'
import StaffWorkloadDialog from './StaffWorkloadDialog.vue'
import { useUserStore } from '../store/user'
import { INVEST_STATUS_META, MEASURE_META, MEASURE_CHOICES, POLICE_GROUP_META, policeGroupLabel } from '../utils/format'

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
const deadlineView = ref({ items: [], state: {}, detentionTerm: '3' })
const dayText = (v) => (v || '').slice(0, 10) || '—'
const recordKey = (key) => ({ PRETRIAL: 'PRETRIAL', LEGAL: 'LEGAL', SUMMONS: 'SUMMONS', SUMMONS_MONTH: 'SUMMONS', SUMMONS_MAX: 'SUMMONS', ADMIN_30: 'ADMIN_30', ADMIN_60: 'ADMIN_60', ADMIN_CHECK_3: 'ADMIN_CHECK_3', ADMIN_CHECK_10: 'ADMIN_CHECK_10', ADMIN_CHECK_20: 'ADMIN_CHECK_20' })[key]
/** 现役承办人（主办/协办），供「主办人与盯办人」区块展示 */
const assignees = computed(() =>
  (detail.value.assignHistory || []).filter(a => a.status === 'ACTIVE')
)
const workloadRef = ref(null)
const openWorkload = (a) => {
  if (a.employeeId) workloadRef.value?.show(a.employeeId, detail.value.id)
}

watch(() => props.modelValue, async (v) => {
  visible.value = v
  if (v && props.caseId) await reload()
})
watch(visible, (v) => emit('update:modelValue', v))

const reload = async () => {
  loading.value = true
  try {
    detail.value = await caseApi.detail(props.caseId)
    deadlineView.value = await watchApi.deadlines(props.caseId)
  } finally { loading.value = false }
}

const canStart = computed(() => (!detail.value.investigationStatus || detail.value.investigationStatus === 'PENDING_INITIAL'))
const canSubmit = computed(() => detail.value.investigationStatus === 'INVESTIGATING' &&
  (detail.value.caseMeasure !== 'DETENTION' || deadlineView.value.canSubmit))

/** 当前登录人是否本案现职承办人（办案人）：决定开始侦查 / 计划维护等"工作者动作" */
const isAssignee = computed(() => {
  const myEmp = userStore.userInfo?.employeeId
  if (!myEmp) return false
  return (detail.value.assignHistory || []).some(
    (a) => a.status === 'ACTIVE' && String(a.employeeId) === String(myEmp))
})

const deadlineText = computed(() => {
  // 详情层：带具体届满日期（列表层只给剩余/已逾期天数，2026-10-14）
  const d = detail.value.detainDaysLeft
  if (d == null) return '未登记期限'
  if (d < 0) return `已逾期 ${Math.abs(d)} 天（${(detail.value.detainDeadlineText || '').slice(0, 10)} 届满）`
  return `剩余 ${d} 天（${(detail.value.detainDeadlineText || '').slice(0, 10)} 届满）`
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
const changeTerm = async (action) => {
  await watchApi.changeTerm(props.caseId, action)
  ElMessage.success(action === 'PAUSE' ? '已停止计算' : action === 'RESUME' ? '已恢复计算' : `已录入刑拘${action}天案`)
  await after()
}
const recordDlg = reactive({ visible: false, key: '', at: '', loading: false })
const openRecord = (key) => {
  recordDlg.key = key
  recordDlg.at = ''
  recordDlg.visible = true
}
const submitRecord = async () => {
  if (!recordDlg.at) { ElMessage.warning('请选择实际办理时间'); return }
  recordDlg.loading = true
  try {
    await watchApi.recordDeadline(props.caseId, recordDlg.key, recordDlg.at)
    recordDlg.visible = false
    ElMessage.success('办理时间已记录')
    await after()
  } finally { recordDlg.loading = false }
}
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

const after = async () => { await reload(); emit('done') }
</script>

<style>
.cf-danger { color: #c62a2a }
.cf-warn { color: #d98a0b }

.cf-watch__owners { display: flex; flex-wrap: wrap; gap: 10px 18px }
.cf-watch__owner { display: inline-flex; align-items: center; gap: 6px }
.cf-watch__owner-role { font-size: 12px; padding: 1px 7px; border-radius: 3px }
.cf-watch__owner-role.is-owner { background: #1b4a8c; color: #fff }
.cf-watch__owner-role.is-member { background: #eef3fa; color: #5a6472 }
/* 名字可点：下划线提示可交互 */
.cf-watch__owner-name {
  color: #1b4a8c; cursor: pointer; text-decoration: underline dotted;
}
.cf-watch__owner-name:hover { color: #c62a2a; text-decoration: underline solid }
</style>
