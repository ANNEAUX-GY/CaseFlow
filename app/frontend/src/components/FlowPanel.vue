<template>
  <div class="cf-flow">
    <div class="cf-opinion__head">
      <span>办理流程</span>
      <el-tag v-if="flow.stage" size="small" :type="(STAGE_META[flow.stage] || {}).type" effect="dark">
        {{ (STAGE_META[flow.stage] || {}).label }}
      </el-tag>
      <span class="cf-muted">阶段进度 {{ progress.percent }}%（{{ progress.done }}/{{ progress.total }} 项）</span>
      <span class="cf-spacer"></span>
      <!-- 流转需领导确认；未满 100% 时也能转（提前终止/分流是真实业务），但给出提醒 -->
      <el-button v-if="canTransfer" type="primary" size="small" @click="openTransfer">
        阶段流转
      </el-button>
    </div>

    <!-- 阶段进度条：已完成 100% 且可流转时提示下一步 -->
    <div class="cf-flow__bar">
      <el-progress
        :percentage="progress.percent"
        :status="progress.finished ? 'success' : undefined"
        :stroke-width="10" />
      <div v-if="flow.transferable && progress.finished" class="cf-flow__tip">
        本阶段任务已全部完成，可以流转到下一阶段
      </div>
      <div v-else-if="flow.stage === 'CLOSED'" class="cf-flow__tip is-done">
        案件已终结
      </div>
    </div>

    <!-- 环节 → 任务 -->
    <div v-if="flow.steps && flow.steps.length" class="cf-flow__steps">
      <div v-for="(s, si) in flow.steps" :key="s.key" class="cf-flow__step">
        <div class="cf-flow__step-head">
          <span class="cf-flow__step-no">{{ si + 1 }}</span>
          <span class="cf-flow__step-name">{{ s.label }}</span>
          <span class="cf-muted">{{ s.done }}/{{ s.total }}</span>
          <el-progress
            v-if="s.total"
            :percentage="Math.round((s.done / s.total) * 100)"
            :show-text="false"
            :stroke-width="4"
            class="cf-flow__step-bar" />
        </div>
        <div v-if="s.tasks && s.tasks.length" class="cf-flow__tasks">
          <label v-for="t in s.tasks" :key="t.id" class="cf-flow__task"
            :class="{ 'is-done': t.status === 'DONE', 'is-std': t.isStd === 1 }">
            <el-checkbox
              :model-value="t.status === 'DONE'"
              :disabled="!canEditTask"
              @change="(v) => toggleTask(t, v)" />
            <span class="cf-flow__task-text">{{ t.content }}</span>
            <el-tag v-if="t.isStd === 1" size="small" type="info" effect="plain" class="cf-flow__task-flag">标准</el-tag>
            <span v-if="t.plannedAt" class="cf-flow__task-due">{{ (t.plannedAt || '').slice(5, 16) }}</span>
          </label>
        </div>
        <div v-else class="cf-muted cf-flow__step-empty">
          本环节暂无任务{{ canEditTask ? '，可在上方「侦查计划」里新增' : '' }}
        </div>
      </div>
    </div>
    <div v-else class="cf-muted" style="padding: 8px 0">
      本阶段流程待细化，可在「侦查计划」中自行录入任务
    </div>

    <!-- 阶段流转弹窗：必须领导确认，且要显式选分支（刑拘/取保/释放 后果不同） -->
    <el-dialog v-model="dlg.visible" title="阶段流转" :width="isMobile ? '94%' : '480px'" append-to-body>
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px">
        流转后本阶段任务将归档，新阶段进度从 0% 重新计算。
      </el-alert>
      <el-form label-width="88px">
        <el-form-item label="当前阶段">
          <span>{{ (STAGE_META[flow.stage] || {}).label }}
            <span class="cf-muted">（{{ progress.done }}/{{ progress.total }} 项，{{ progress.percent }}%）</span>
          </span>
        </el-form-item>
        <el-form-item v-if="!progress.finished && flow.stage !== 'CLOSED'" label="进度未满">
          <span class="cf-muted" style="font-size: 12px">
            仍有 {{ progress.total - progress.done }} 项未完成，确认要现在流转吗？
          </span>
        </el-form-item>
        <el-form-item label="流转到" required>
          <el-radio-group v-model="dlg.action">
            <el-radio-button v-for="t in (flow.transitions || [])" :key="t.action" :value="t.action">
              {{ t.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="current" label="目标阶段">
          <span>{{ current.targetStageLabel }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="dlg.loading" :disabled="!dlg.action" @click="doTransfer">
          确认流转
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { watchApi } from '../api'
import { STAGE_META, stageLabel } from '../utils/format'
import { useDevice } from '../utils/device'

const props = defineProps({
  caseId: { type: [Number, null], default: null },
  /** 案件类型（刑事/行政），仅用于展示说明 */
  caseType: { type: String, default: '' },
  /** 是否管理层：阶段流转需领导确认 */
  isFullAccess: { type: Boolean, default: false },
  /** 是否本案承办人：决定任务能否勾选 */
  isAssignee: { type: Boolean, default: false }
})
const emit = defineEmits(['changed'])

const { isMobile } = useDevice()

const flow = ref({})
const loading = ref(false)

const load = async () => {
  if (!props.caseId) { flow.value = {}; return }
  loading.value = true
  try {
    flow.value = await watchApi.flow(props.caseId) || {}
  } finally {
    loading.value = false
  }
}
watch(() => props.caseId, load, { immediate: true })

/** 进度对象兜底，避免接口异常时模板报undefined */
const progress = computed(() => flow.value.progress || {
  total: 0, done: 0, percent: 0, finished: false, stage: 'INITIAL'
})

const canTransfer = computed(() => !!flow.value.transferable && props.isFullAccess)
/** 任务勾选：承办人可勾；标准任务（isStd=1）也允许自建任务同样处理，避免标准任务无法落地 */
const canEditTask = computed(() => props.isAssignee || props.isFullAccess)

const toggleTask = async (task, done) => {
  if (!props.caseId) return
  try {
    // 勾选 = 完成（可留空说明）；取消勾选 = 撤销完成。两个动作都留痕可撤回
    if (done) {
      await watchApi.donePlan(task.id, '')
    } else {
      await watchApi.revertPlan(task.id)
    }
    await load()
    emit('changed')
  } catch (e) {
    await load()   // 失败时以服务端为准回滚本地状态
    ElMessage.error('更新任务状态失败')
  }
}

const dlg = reactive({ visible: false, action: '', loading: false })
const current = computed(() =>
  (flow.value.transitions || []).find(t => t.action === dlg.action) || null)

const openTransfer = () => {
  dlg.action = (flow.value.transitions || [])[0]?.action || ''
  dlg.visible = true
}

const doTransfer = async () => {
  if (!dlg.action) { ElMessage.warning('请选择流转到哪个阶段'); return }
  const t = current.value
  try {
    await ElMessageBox.confirm(
      `确认将案件从「${stageLabel(flow.value.stage)}」流转到「${t?.targetStageLabel}」？` +
      `流转后新阶段进度从 0% 起算。`,
      '阶段流转确认',
      { type: 'warning', confirmButtonText: '确认流转', cancelButtonText: '再想想' }
    )
  } catch (e) { return }   // 用户取消
  dlg.loading = true
  try {
    await watchApi.flowTransfer(props.caseId, dlg.action)
    ElMessage.success('已流转')
    dlg.visible = false
    await load()
    emit('changed')
  } catch (e) {
    // 后端返回的 BizException 会带中文提示
  } finally {
    dlg.loading = false
  }
}

defineExpose({ load })
onBeforeUnmount(() => { flow.value = {} })
</script>

<style>
.cf-flow__bar { padding: 4px 16px 10px }
.cf-flow__tip { font-size: 12px; color: #1e8e58; margin-top: 6px }
.cf-flow__tip.is-done { color: #1e8e58 }
.cf-flow__steps { padding: 0 16px 12px }
.cf-flow__step { padding: 8px 0; border-bottom: 1px dashed #dfe4ea }
.cf-flow__step:last-child { border-bottom: none }
.cf-flow__step-head { display: flex; align-items: center; gap: 8px; font-size: 13px }
.cf-flow__step-no {
  flex: none; width: 18px; height: 18px; line-height: 18px; text-align: center;
  background: #eef3fa; color: #1b4a8c; border-radius: 50%; font-size: 11px; font-weight: 600;
}
.cf-flow__step-name { font-weight: 600; color: #1b2430 }
.cf-flow__step-bar { flex: 1; min-width: 60px }
.cf-flow__step-empty { font-size: 12px; padding: 4px 0 0 26px }
.cf-flow__tasks { padding: 6px 0 0 26px }
.cf-flow__task {
  display: flex; align-items: center; gap: 8px; padding: 3px 0; font-size: 13px;
  color: #1b2430; min-width: 0;
}
.cf-flow__task.is-done .cf-flow__task-text { color: #8a929e; text-decoration: line-through }
.cf-flow__task-text { flex: 1; min-width: 0; word-break: break-all }
.cf-flow__task-flag { flex: none }
.cf-flow__task-due { flex: none; font-size: 12px; color: #8a929e }
</style>
