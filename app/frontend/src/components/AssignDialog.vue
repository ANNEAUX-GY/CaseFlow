<template>
  <!-- 手机端整屏：900px 的指派面板 + 承办人选择表，窄屏下只能全屏才放得开 -->
  <el-dialog
    v-model="visible"
    :title="`指派：${caseName}`"
    width="900px"
    destroy-on-close
    class="cf-dialog"
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
      <el-form-item label="待办事项">
        <div class="cf-todo-editor">
          <div v-for="(t, i) in todos" :key="i" class="cf-todo-editor__row">
            <span class="cf-todo-editor__no">{{ i + 1 }}</span>
            <el-input v-model="todos[i]" size="small" maxlength="500" placeholder="例如：调取银行流水" />
            <div class="cf-todo-editor__ops">
              <el-button size="small" :disabled="i === 0" @click="moveTodo(i, -1)">上移</el-button>
              <el-button size="small" :disabled="i === todos.length - 1" @click="moveTodo(i, 1)">下移</el-button>
              <el-button size="small" type="danger" plain @click="todos.splice(i, 1)">删除</el-button>
            </div>
          </div>
          <el-button size="small" @click="todos.push('')">+ 添加一项</el-button>
          <div class="cf-form-tip">
            待办项由承办人在案件详情中逐项完成；<b>标记完成前必须先上传佐证材料</b>。
            留空或清空的项不会写入；已完成（含佐证）的项不会被清除。
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
      <el-button type="primary" :loading="loading" @click="submit">确认指派</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import EmployeePicker from './EmployeePicker.vue'
import { caseApi, todoApi } from '../api'


const props = defineProps({
  modelValue: { type: Boolean, default: false },
  caseId: { type: [Number, null], default: null },
  caseName: { type: String, default: '' },
  currentOwnerId: { type: [Number, null], default: null },
  currentMemberIds: { type: Array, default: () => [] },
  currentDeadline: { type: String, default: null }
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
// 待办清单：打开时从后端载入既有项，管理员可在这里增删改与排序
const todos = ref([])
const loading = ref(false)

watch(() => props.modelValue, async (v) => {
  visible.value = v
  if (v) {
    note.value = ''
    ownerId.value = props.currentOwnerId
    memberIds.value = [...props.currentMemberIds]
    // 后端带时分，日期选择器只认日期部分（期限按天记录）
    deadlineDay.value = (props.currentDeadline || '').slice(0, 10)
    originDeadline.value = props.currentDeadline || null
    todos.value = []
    // 载入既有待办，管理员在指派时可直接调整
    if (props.caseId) {
      try {
        const list = await todoApi.listOfCase(props.caseId)
        todos.value = (list || []).map((t) => t.content)
      } catch (e) {
        todos.value = []
      }
    }
  }
})
watch(visible, (v) => emit('update:modelValue', v))

const moveTodo = (i, delta) => {
  const target = i + delta
  if (target < 0 || target >= todos.value.length) return
  const list = [...todos.value]
  ;[list[i], list[target]] = [list[target], list[i]]
  todos.value = list
}

const onChange = (payload) => {
  ownerId.value = payload.ownerId
  memberIds.value = payload.memberIds
}

const submit = async () => {
  if (!ownerId.value && memberIds.value.length === 0) {
    ElMessage.warning('请至少选择一名承办人')
    return
  }
  loading.value = true
  try {
    const payload = {
      ownerId: ownerId.value,
      memberIds: memberIds.value,
      note: note.value,
      // 整份清单提交，后端做覆盖式同步（已完成/有佐证的项会保留）
      todos: todos.value.map((t) => (t || '').trim())
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
    ElMessage.success(
      payload.deadlineTouched
        ? `指派成功，期限${next ? '设为 ' + deadlineDay.value : '已清除'}`
        : '指派成功'
    )
    visible.value = false
    emit('done')
  } finally {
    loading.value = false
  }
}
</script>
