<template>
  <div class="cf-stagebar">
    <!-- 阶段 + 进度：一行说清"这个案子走到哪、还差几件事" -->
    <div class="cf-stagebar__main">
      <el-tag size="small" :type="(STAGE_META[flow.stage] || {}).type" effect="dark">
        {{ flow.stageLabel || '初查' }}
      </el-tag>
      <el-progress
        v-if="total > 0"
        :percentage="percent"
        :status="finished ? 'success' : undefined"
        :stroke-width="8"
        style="flex: 1; min-width: 80px" />
      <span v-if="total > 0" class="cf-stagebar__count">{{ done }} / {{ total }}</span>
      <span v-else class="cf-muted cf-stagebar__count">本阶段暂无待办</span>

      <!-- 阶段做完不自动跳：明确告诉领导"该你拍板了"，并给出明确的下一步 -->
      <template v-if="canTransfer">
        <el-tooltip v-if="!finished" placement="top"
          :content="`本阶段还有 ${total - done} 项待办未完成，也可提前转入下一阶段`">
          <el-button v-if="branches.length === 1" size="small" type="info" plain
            @click="openAsk(branches[0])">转入下一阶段</el-button>
          <el-dropdown v-else trigger="click" @command="openAsk">
            <el-button size="small" type="info" plain>
              转入下一阶段<el-icon style="margin-left: 4px"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="t in branches" :key="t.action" :command="t">
                  {{ t.label }} → {{ t.targetStageLabel }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </el-tooltip>
        <el-button v-else-if="branches.length === 1" size="small" type="primary"
          @click="openAsk(branches[0])">待办已办完，确认转入</el-button>
        <el-dropdown v-else trigger="click" @command="openAsk">
          <el-button size="small" type="primary">
            待办已办完，确认转入<el-icon style="margin-left: 4px"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="t in branches" :key="t.action" :command="t">
                {{ t.label }} → {{ t.targetStageLabel }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <!-- 退回（2026-10-18）：确认过待办已办完、但领导认为没办到位时走这里。
             退回不改阶段，而是把意见派成一条新待办，进度自然从 100% 掉回来。 -->
        <el-button v-if="finished" size="small" type="danger" plain @click="openReturn">退回补办</el-button>
      </template>
      <el-tag v-else-if="finished" size="small" type="warning" effect="plain">
        待办已办完
      </el-tag>
      <span v-else-if="finished" class="cf-muted" style="font-size: 12px">待领导确认转入</span>
    </div>

    <!-- 流转确认：说清"这一步会改什么"，不让人稀里糊涂点下去 -->
    <el-dialog v-model="ask.visible" :title="askTitle" width="440px" append-to-body>
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>确认转入后会发生这些：</template>
        <ul class="cf-stagebar__warnlist">
          <li>案件阶段由「{{ flow.stageLabel }}」变为「{{ ask.targetLabel }}」</li>
          <li v-if="!finished">本阶段尚有 <b>{{ total - done }}</b> 项待办未完成，转入后不再计入进度</li>
          <li>本阶段已办完的 {{ done }} 项待办保留在案件里，作为办案留痕</li>
          <li v-if="ask.measureHint">{{ ask.measureHint }}</li>
          <li v-if="ask.assignHint">{{ ask.assignHint }}</li>
        </ul>
      </el-alert>
      <template #footer>
        <el-button @click="ask.visible = false">取消</el-button>
        <el-button type="primary" :loading="ask.loading" @click="doTransfer">确认转入</el-button>
      </template>
    </el-dialog>

    <!-- 退回补办（2026-10-18）：领导看过觉得没办到位，把案子留在本阶段并写明要补什么，
         不让人"只能确认、不能打回" -->
    <el-dialog v-model="ret.visible" title="退回补办" width="460px" append-to-body>
      <p class="cf-muted" style="margin: 0 0 10px; font-size: 13px; line-height: 1.7">
        退回后案件仍停留在「{{ flow.stageLabel || '本阶段' }}」，不会转入下一阶段。<br />
        这条意见会生成一条待办派给承办人，并在案件的意见列表里留痕。
      </p>
      <el-input v-model="ret.comment" type="textarea" :rows="4" maxlength="300" show-word-limit
        placeholder="请写明需要补办什么（必填），例如：缺少被害人询问笔录，补完再提交" />
      <template #footer>
        <el-button @click="ret.visible = false">取消</el-button>
        <el-button type="danger" :loading="ret.loading" :disabled="!ret.comment.trim()"
          @click="doReturn">确认退回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 办案阶段推进条（2026-10-12）。
 *
 * <p><b>判据</b>：一个阶段的<b>案件待办</b>全部完成，才提示领导确认转入下一阶段。
 * 这里只做「看进度 + 领导拍板」两件事，不再维护第二套标准任务清单。
 *
 * <p><b>阶段完成不自动跳</b>：转入哪一阶段是人的决定，
 * 必须领导点确认（后端 {@code FlowService.transfer} 也用 isFullAccess 兜底）。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
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

const done = computed(() => flow.value.progress?.done || 0)
const total = computed(() => flow.value.progress?.total || 0)
const percent = computed(() => flow.value.progress?.percent || 0)
const finished = computed(() => !!flow.value.progress?.finished)
/** 流转是管理层动作，普通民警只看到提示文案，点不到按钮 */
const canTransfer = computed(() => isFullAccess.value && flow.value.caseType !== 'ADMINISTRATIVE' && !!flow.value.transferable)
/** 可选出口：刑事主干只有一条（初查→刑拘在办、刑拘→取保），刑拘阶段另有通往终结的分支 */
const branches = computed(() => flow.value.transitions || [])

const ask = reactive({ visible: false, loading: false, action: '', targetLabel: '', measureHint: '', assignHint: '' })
const askTitle = computed(() => `确认转入「${ask.targetLabel}」`)

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

/** 把"这一步会改什么"提前说清楚：强制措施与指派组别都会跟着变 */
const openAsk = (t) => {
  if (!t) return
  ask.action = t.action
  ask.targetLabel = t.targetStageLabel
  if (t.action === 'DETAIN') {
    ask.measureHint = '强制措施将登记为「拘留」，案件自动归入「刑拘在办」板块'
    ask.assignHint = '本阶段要求承办人为「清案组」或「不限」，现有承办人可能不再符合，需重新指派'
  } else if (t.action === 'BAIL') {
    ask.measureHint = '强制措施将登记为「取保候审」，案件自动归入「取保监居」板块'
    ask.assignHint = ''
  } else {
    ask.measureHint = ''
    ask.assignHint = ''
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

// ---- 退回补办（2026-10-18）----
// 退回不改阶段：意见派成待办后，本阶段进度从 100% 掉下来，
// 界面自然回到「还有事没办完」，不需要额外的退回状态。
const ret = reactive({ visible: false, loading: false, comment: '' })

const openReturn = () => {
  ret.comment = ''
  ret.visible = true
}

const doReturn = async () => {
  const comment = ret.comment.trim()
  if (!comment) {
    ElMessage.warning('请填写退回意见，说明需要补办什么')
    return
  }
  ret.loading = true
  try {
    await watchApi.flowReturn(props.caseId, comment)
    ElMessage.success('已退回，承办人待办里已收到这条意见')
    ret.visible = false
    await load()
    emit('changed')
  } catch (e) { /* 拦截器已提示 */ } finally {
    ret.loading = false
  }
}

watch(() => props.caseId, () => { load() }, { immediate: true })

defineExpose({ reload: load })
</script>

<style scoped>
.cf-stagebar__main { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.cf-stagebar__count { font-size: 12px; color: #6b7480; min-width: 46px; font-variant-numeric: tabular-nums; }
.cf-stagebar__warnlist { margin: 4px 0 0; padding-left: 18px; font-size: 12px; line-height: 1.8; }
</style>
