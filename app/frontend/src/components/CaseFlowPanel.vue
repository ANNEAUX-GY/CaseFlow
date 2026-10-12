<template>
  <div class="cf-panel cf-flowpanel">
    <div class="cf-panel__head">
      <span>办案流程</span>
      <span class="cf-muted" style="font-weight: 400; font-size: 12px">
        {{ STAGE_META[flow.stage]?.desc || '' }}
      </span>
    </div>

    <div v-loading="loading" style="padding: 12px 16px 14px">
      <!-- 阶段条：当前阶段 + 进度 -->
      <div class="cf-flow__stage">
        <el-tag :type="(STAGE_META[flow.stage] || {}).type" effect="dark">{{ flow.stageLabel }}</el-tag>
        <el-progress
          :percentage="flow.progress?.percent || 0"
          :status="flow.progress?.finished ? 'success' : undefined"
          :stroke-width="10"
          style="flex: 1; min-width: 120px" />
        <span class="cf-flow__count">
          {{ flow.progress?.done || 0 }} / {{ flow.progress?.total || 0 }}
        </span>
      </div>

      <!-- 阶段做完：不自动跳，等领导拍板（2026-10-12 用户定的口径） -->
      <el-alert
        v-if="finished"
        class="cf-flow__alert"
        type="warning"
        :closable="false"
        show-icon>
        <template #title>
          「{{ flow.stageLabel }}」已全部完成
          <span v-if="!isFullAccess">，待领导确认后进入下一阶段</span>
        </template>
        <div v-if="canTransfer" class="cf-flow__branches">
          <span class="cf-muted">请领导确认转入：</span>
          <el-button
            v-for="t in flow.transitions"
            :key="t.action"
            size="small"
            :type="t.action === 'RELEASE' ? 'info' : 'primary'"
            plain
            @click="askTransfer(t)">
            {{ t.label }} → {{ t.targetStageLabel }}
          </el-button>
        </div>
      </el-alert>

      <!-- 还没做完也给流转入口，但明确提示「未做完」 -->
      <div v-else-if="canTransfer" class="cf-flow__branches" style="margin-top: 10px">
        <span class="cf-muted">
          尚有 {{ (flow.progress?.total || 0) - (flow.progress?.done || 0) }} 项未完成，也可提前流转：
        </span>
        <el-button
          v-for="t in flow.transitions"
          :key="t.action"
          size="small"
          type="info"
          plain
          @click="askTransfer(t)">
          {{ t.label }} → {{ t.targetStageLabel }}
        </el-button>
      </div>

      <!-- 环节 + 任务 -->
      <div v-if="flow.steps?.length" class="cf-flow__steps">
        <div v-for="(s, i) in flow.steps" :key="s.key" class="cf-flow__step">
          <div class="cf-flow__stephead">
            <span class="cf-flow__stepidx">{{ i + 1 }}</span>
            <span class="cf-flow__stepname">{{ s.label }}</span>
            <span class="cf-flow__stepcount" :class="{ 'is-done': s.total > 0 && s.done === s.total }">
              {{ s.done }} / {{ s.total }}
            </span>
            <el-progress
              v-if="s.total > 0"
              :percentage="Math.round((s.done / s.total) * 100)"
              :stroke-width="4"
              :show-text="false"
              style="flex: 1; min-width: 60px" />
          </div>
          <label v-for="t in s.tasks" :key="t.id" class="cf-flow__task">
            <el-checkbox
              :model-value="t.status === 'DONE'"
              :disabled="busyId === t.id"
              @change="(v) => toggle(t, v)" />
            <span :class="{ 'is-done': t.status === 'DONE' }">{{ t.content }}</span>
            <span v-if="t.doneAt" class="cf-muted cf-flow__taskat">
              {{ String(t.doneAt).slice(5, 16).replace('T', ' ') }}
            </span>
          </label>
          <div v-if="!s.tasks?.length" class="cf-flow__empty">本环节暂无标准任务</div>
        </div>
      </div>

      <div v-else-if="!loading" class="cf-flow__empty">
        该阶段流程尚未细化
        <el-button v-if="canTransfer && !flow.progress?.total" link type="primary" @click="seed">
          一键铺标准任务
        </el-button>
      </div>
    </div>

    <!-- 流转确认：说清"这一步会改什么"，不让人稀里糊涂点下去 -->
    <el-dialog v-model="ask.visible" :title="`确认流转到「${ask.targetLabel}」`" width="460px" append-to-body>
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>流转不是单纯换个名字，它会同时改动这些：</template>
        <ul class="cf-flow__warnlist">
          <li>本阶段「{{ flow.stageLabel }}」的 {{ flow.progress?.total || 0 }} 项任务将全部作废</li>
          <li v-if="ask.measureHint">{{ ask.measureHint }}</li>
          <li>「{{ ask.targetLabel }}」阶段将自动生成新的标准任务，进度从 0% 起算</li>
          <li v-if="ask.assignHint">{{ ask.assignHint }}</li>
        </ul>
      </el-alert>
      <el-input v-model="ask.remark" type="textarea" :rows="2" maxlength="200"
        placeholder="流转说明（选填，会写进案件日志）" />
      <template #footer>
        <el-button @click="ask.visible = false">取消</el-button>
        <el-button type="primary" :loading="ask.loading" @click="doTransfer">确认流转</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { watchApi } from '../api'
import { useUserStore } from '../store/user'
import { STAGE_META } from '../utils/format'

const props = defineProps({
  caseId: { type: [Number, String], default: null }
})
const emit = defineEmits(['changed'])

const userStore = useUserStore()
const isFullAccess = computed(() => userStore.isFullAccess)

const flow = ref({})
const loading = ref(false)
const busyId = ref(0)

const finished = computed(() => !!flow.value.progress?.finished)
const canTransfer = computed(() => isFullAccess.value && !!flow.value.transferable)

const ask = reactive({ visible: false, loading: false, action: '', targetLabel: '', remark: '', measureHint: '', assignHint: '' })

const load = async () => {
  if (!props.caseId) return
  loading.value = true
  try {
    flow.value = await watchApi.flow(props.caseId)
  } catch (e) {
    flow.value = {}
  } finally {
    loading.value = false
  }
}

const seed = async () => {
  try {
    const n = await watchApi.flowSeed(props.caseId)
    ElMessage.success(n > 0 ? `已生成 ${n} 项标准任务` : '标准任务已齐备')
    await load()
    emit('changed')
  } catch (e) { /* 拦截器已提示 */ }
}

/** 勾选/取消：标准任务双向切换，不弹完成说明（勾选框不是表单） */
const toggle = async (task, val) => {
  busyId.value = task.id
  try {
    if (val) {
      await watchApi.planDone(task.id)
    } else {
      await watchApi.planRevert(task.id)
    }
    await load()
    emit('changed')
  } catch (e) { /* 拦截器已提示；失败时重载回真值 */ } finally {
    busyId.value = 0
    await load()
  }
}

/** 把"这一步会改什么"提前说清楚：强制措施与指派组别都会跟着变 */
const askTransfer = (t) => {
  ask.action = t.action
  ask.targetLabel = t.targetStageLabel
  ask.remark = ''
  if (t.action === 'DETAIN') {
    ask.measureHint = '强制措施将登记为「拘留」，案件自动归入「刑拘在办」板块'
    ask.assignHint = '本阶段要求承办人为「清案组」或「不限」，现有承办人可能不再符合，需重新指派'
  } else if (t.action === 'BAIL') {
    ask.measureHint = '强制措施将登记为「取保候审」，案件自动归入「取保监居」板块'
    ask.assignHint = ''
  } else if (t.action === 'RELEASE' || t.action === 'ARREST' || t.action === 'PUNISH' || t.action === 'CLOSE') {
    ask.measureHint = t.action === 'RELEASE' ? '强制措施与期限将被清空（案件已终结）' : ''
    ask.assignHint = '案件进入「已终结」，之后不再出现在在办看板'
  }
  ask.visible = true
}

const doTransfer = async () => {
  ask.loading = true
  try {
    await watchApi.flowTransfer(props.caseId, ask.action)
    ElMessage.success(`已转入「${ask.targetLabel}」`)
    ask.visible = false
    await load()
    emit('changed')
  } catch (e) { /* 拦截器已提示 */ } finally {
    ask.loading = false
  }
}

watch(() => props.caseId, () => {
  load()
}, { immediate: true })

defineExpose({ reload: load })
</script>

<style scoped>
.cf-flow__stage { display: flex; align-items: center; gap: 10px; }
.cf-flow__count { font-size: 12px; color: #6b7480; min-width: 42px; text-align: right; }

.cf-flow__alert { margin-top: 12px; }
.cf-flow__branches { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 8px; font-size: 12px; }

.cf-flow__steps { margin-top: 12px; }
.cf-flow__step { padding: 8px 0; border-top: 1px dashed var(--cf-border); }
.cf-flow__step:first-child { border-top: none; }
.cf-flow__stephead { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.cf-flow__stepidx {
  width: 18px; height: 18px; line-height: 18px; text-align: center;
  border-radius: 50%; background: #eef2f7; color: #55606d;
  font-size: 11px; flex: none;
}
.cf-flow__stepname { font-size: 13px; font-weight: 600; }
.cf-flow__stepcount { font-size: 12px; color: #6b7480; }
.cf-flow__stepcount.is-done { color: #2f9e6e; font-weight: 600; }

.cf-flow__task { display: flex; align-items: center; gap: 8px; padding: 3px 0 3px 26px; font-size: 13px; cursor: pointer; }
.cf-flow__task .is-done { color: #98a1ad; text-decoration: line-through; }
.cf-flow__taskat { margin-left: auto; font-size: 11px; }

.cf-flow__empty { font-size: 12px; color: #8a929e; padding: 8px 0; }
.cf-flow__warnlist { margin: 4px 0 0; padding-left: 18px; font-size: 12px; line-height: 1.8; }
</style>