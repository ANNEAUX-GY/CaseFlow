<template>
  <!-- 手机端整屏：900px 的指派面板 + 承办人选择表，窄屏下只能全屏才放得开 -->
  <el-dialog
    v-model="visible"
    :title="`指派：${caseName}`"
    width="960px"
    destroy-on-close
    class="cf-dialog cf-assign-dlg"
  >
    <el-form label-width="80px">
      <!-- 期限按天（2026-10-09）：与建案表单同一口径。
            原来的 +1/+3/+7 快捷键去掉——随手一点填出来的日期多半不准，
            而且"提前多久提醒"已经是案件自己的字段了，快捷键没有存在必要。 -->
      <el-form-item label="截止期限">
        <el-date-picker
          v-model="deadlineDay"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期（不改则留空保持原值）"
          style="width: 240px"
        />
        <el-button size="small" style="margin-left: 8px" @click="deadlineDay = ''">清除</el-button>
        <span class="cf-muted" style="margin-left: 8px" v-if="originDeadline">
          原期限 {{ String(originDeadline).slice(0, 10) }}
        </span>
        <span class="cf-muted" style="margin-left: 8px" v-else>当前未设期限</span>
      </el-form-item>
      <el-form-item label="指派要求">
        <el-input v-model="note" type="textarea" :rows="2" placeholder="办理要求 / 注意事项（选填）" />
      </el-form-item>

      <!-- 待办事项（2026-10-13 改造）：与案件详情「添加待办」完全同一套编辑器。
           原来这里是一份"纯文本清单 + 上移/下移/删除"，只能写字、不能设截止时间与
           重要程度，落库还走另一条链路（opinion_id 为 NULL 的手工待办），
           于是同一个"待办"概念在两处产生两种数据形态。
           现在统一走 TodoBatchEditor + 意见派生，
           落库路径与案件详情一字不差（见 OpinionService.addDraftsQuietly）。

           语义是**只追加**：既有待办不再在这里列出来让人删——待办带反馈、佐证与
           办案阶段，删除一条必须走案件详情里「移除意见（连带待办）」那条有日志可撤的路。
           要改既有待办，去案件详情操作。 -->
      <el-form-item label="待办事项">
        <div class="cf-assign-todo">
          <TodoBatchEditor
            ref="todoRef"
            v-model:rows="todoRows"
            scope="cf-assign-todo"
            preset-mode="fill"
            :max="MAX_BATCH"
            :presets="presets"
            :preset-saving="presetSaving"
            hint="可以顺手把要办的事写下来：填完一行按 <b>Enter</b> 直接写下一行，最多 10 条。写几条就在指派后生成几条待办，<b>不写也不影响指派</b>。"
          />
          <div class="cf-form-tip">
            写下的每一条都会成为承办人的待办，<b>标记完成前必须先提交反馈说明</b>。
            只新增、不改动案件里已有的待办；已有的待办要去
            <b>案件详情 → 案件待办</b>里增删改。
          </div>
          <div class="cf-bt__foot cf-assign-todo__foot">
            <el-button link type="primary" :disabled="todoRows.length >= MAX_BATCH" @click="todoRef?.addRow()">
              <el-icon><Plus /></el-icon>再添一条
            </el-button>
            <span class="cf-bt__count cf-assign-todo__count">
              已填 <b>{{ filledCount }}</b> 条<template v-if="todoRows.length >= MAX_BATCH">（一次最多 {{ MAX_BATCH }} 条）</template>
            </span>
          </div>
        </div>
      </el-form-item>

      <el-form-item label="承办人">
        <!-- 传requiredGroup：不匹配组别的员工置灰不可选，并说明原因 -->
        <EmployeePicker :owner-id="ownerId" :member-ids="memberIds"
          :required-group="requiredGroup" @change="onChange" />
        <div v-if="requiredGroup !== 'NONE'" class="cf-muted" style="font-size: 12px; margin-top: 4px">
          本案为刑拘在办，只能指派给<b>{{ policeGroupLabel(requiredGroup) }}</b>人员
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="loading" @click="submit">
        确认指派{{ filledCount ? `（含 ${filledCount} 条待办）` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import EmployeePicker from './EmployeePicker.vue'
import TodoBatchEditor from './TodoBatchEditor.vue'
import { caseApi, watchApi } from '../api'
import { policeGroupLabel } from '../utils/format'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  caseId: { type: [Number, null], default: null },
  caseName: { type: String, default: '' },
  currentOwnerId: { type: [Number, null], default: null },
  currentMemberIds: { type: Array, default: () => [] },
  currentDeadline: { type: String, default: null },
  /**
   * 本案强制措施（2026-10-13 补声明）。
   *
   * <p>原先 {@code requiredGroup} 读的是 {@code props.caseMeasure}，而 props 里
   * <b>根本没有声明它</b>——恒为 undefined，刑拘案件的组别约束整条失效，
   * 表现为「该置灰的人还能选，选完提交才被后端拒」。
   * 只有 CaseList 传了这个值（Dashboard / Reminder 没传，见下），
   * 所以补齐声明后刑拘约束才真正生效。
   */
  caseMeasure: { type: String, default: null }
})
const emit = defineEmits(['update:modelValue', 'done'])

/**
 * 本案要求的办案组别：
 * - 刑拘在办（DETENTION）→ 清案组；
 * - 其余（无措施 / 取保监居 / 行政）→ 不限。
 * 与后端 CaseService.moduleOf + PoliceGroup.requiredOf 完全一致，
 * 不一致会出现「前端不置灰、后端却拒收」的错位。
 */
const requiredGroup = computed(() => {
  if (props.caseMeasure === 'DETENTION') return 'CLEAR'
  return 'NONE'
})

const visible = ref(false)
const note = ref('')
const ownerId = ref(null)
const memberIds = ref([])
/** 期限按天选择（提交时补 23:59:59）；原值单独存一份用来判断"有没有改过" */
const deadlineDay = ref('')
const originDeadline = ref(null)
const loading = ref(false)

/* ============ 待办（2026-10-13，与案件详情「添加待办」同一套） ============ */
const MAX_BATCH = 10
const todoRef = ref(null)
const todoRows = ref([{ key: 1, content: '', deadline: '', importance: 'C' }])
const presets = ref([])
const presetSaving = ref(false)

const filledCount = computed(() => todoRows.value.filter((r) => (r.content || '').trim()).length)

const loadPresets = async () => {
  try {
    presets.value = await watchApi.todoPresets(8)
  } catch (e) {
    presets.value = []   // 拉不到就当没有：快捷入口是锦上添花
  }
}

watch(() => props.modelValue, async (v) => {
  visible.value = v
  if (v) {
    note.value = ''
    ownerId.value = props.currentOwnerId
    memberIds.value = [...props.currentMemberIds]
    // 后端带时分，日期选择器只认日期部分（期限按天记录）
    deadlineDay.value = (props.currentDeadline || '').slice(0, 10)
    originDeadline.value = props.currentDeadline || null
    // 待办每次都从空白开始：这里是"顺带新增几条"，不是编辑既有清单
    todoRows.value = [{ key: Date.now(), content: '', deadline: '', importance: 'C' }]
    loadPresets()
  }
})
watch(visible, (v) => emit('update:modelValue', v))

const onChange = (payload) => {
  ownerId.value = payload.ownerId
  memberIds.value = payload.memberIds
}

const submit = async () => {
  if (!ownerId.value && memberIds.value.length === 0) {
    ElMessage.warning('请至少选择一名承办人')
    return
  }
  const todos = todoRef.value
    ? todoRef.value.items()
    : todoRows.value
      .filter((r) => (r.content || '').trim())
      .map((r) => ({ content: r.content.trim(), deadline: r.deadline || '', importance: r.importance || 'C' }))
  if (todos.length > MAX_BATCH) {
    ElMessage.warning(`一次最多添加 ${MAX_BATCH} 条待办，请删掉多余的再提交`)
    return
  }
  loading.value = true
  try {
    const payload = {
      ownerId: ownerId.value,
      memberIds: memberIds.value,
      note: note.value,
      // 待办随指派一并写入，后端落库路径与案件详情「添加待办」一致：
      // 写 case_leader_opinion 再派生 case_todo（带截止时间、A/B/C、常用待办记忆）。
      todos
    }
    // 只有改过期限才提交，避免误清空。
    // 比较的是「日期部分」：库里带时分，而选择框只给到天，
    // 不切片的话每次打开都会被当成"改过了"，白白把期限重写成 23:59:59。
    const next = deadlineDay.value ? `${deadlineDay.value} 23:59:59` : null
    const originDay = (originDeadline.value || '').slice(0, 10)
    if (deadlineDay.value !== originDay) {
      payload.deadline = next
      payload.deadlineTouched = true
    }
    await caseApi.assign(props.caseId, payload)
    const parts = ['指派成功']
    if (payload.deadlineTouched) {
      parts.push(next ? `期限设为 ${deadlineDay.value}` : '期限已清除')
    }
    if (todos.length) {
      parts.push(`已一并添加 ${todos.length} 条待办`)
    }
    ElMessage.success(parts.join('，'))
    visible.value = false
    emit('done')
  } finally {
    loading.value = false
  }
}
</script>

<style>
/* 指派弹窗里的待办区块：表格本体样式在 TodoBatchEditor.vue，
   这里只管它在表单里的落位与页脚条。 */
.cf-assign-todo { width: 100% }
.cf-assign-todo .cf-bt__rows { max-height: 30vh }
.cf-assign-todo__foot {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  margin-top: 10px; padding-top: 10px; border-top: 1px dashed #e4e8ee;
}
</style>
