<template>
  <!-- 手机端整屏弹窗：760px 的表单塞进 375px 会到处折行，全屏反而更好填 -->
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑案件' : '新建案件'"
    width="760px"
    destroy-on-close
    class="cf-dialog"
  >
    <el-form :model="form" label-width="92px">
      <el-form-item label="案件名称" required>
        <el-input v-model="form.name" placeholder="可手打案件名，例如：2026-XX 涉嫌诈骗案" maxlength="120" show-word-limit />
      </el-form-item>

      <el-form-item label="案件材料">
        <el-upload
          :action="uploadAction"
          :headers="uploadHeaders"
          :limit="1"
          :on-success="onUploadSuccess"
          :on-remove="onUploadRemove"
          :file-list="fileList"
        >
          <el-button size="small">选择 PDF / Word / Excel</el-button>
          <template #tip>
            <div class="cf-muted">上传后系统自动识别来源类型（不传即为手工录入）</div>
          </template>
        </el-upload>
      </el-form-item>

      <el-row :gutter="12">
        <el-col :span="12" :xs="24">
          <el-form-item label="案件分类" required>
            <el-cascader
              v-model="formCascade"
              :options="categoryStore.tree"
              :props="{ checkStrictly: true }"
              placeholder="必选：先选大类（刑事/行政/未立案）"
              clearable
              filterable
              style="width: 100%"
            />
            <div class="cf-form-tip">案件类型为必填项，选定大类后保存才有效</div>
          </el-form-item>
        </el-col>
        <el-col :span="6" :xs="24">
          <el-form-item label="来源类型">
            <el-select v-model="form.sourceType" style="width: 100%">
              <el-option label="手工录入" value="MANUAL" />
              <el-option label="PDF" value="PDF" />
              <el-option label="Word" value="WORD" />
              <el-option label="Excel" value="EXCEL" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="6" :xs="24">
          <el-form-item label="优先级">
            <el-select v-model="form.priority" style="width: 100%">
              <el-option label="特急" value="URGENT" />
              <el-option label="紧急" value="HIGH" />
              <el-option label="普通" value="NORMAL" />
              <el-option label="低" value="LOW" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="12">
        <el-col :span="12" :xs="24">
          <el-form-item label="案件编号">
            <el-input v-model="form.filingNo" placeholder="案件编号（选填）" maxlength="64" />
          </el-form-item>
        </el-col>
        <el-col :span="12" :xs="24">
          <!-- 是否采取强制措施（2026-10-09）：选「是」才出现具体措施下拉。
               值就写进 case_info.case_measure——与盯办模块共用同一个字段，
               两个入口各存一份只会互相打架（这边写"逮捕"、盯办还停在"未采取措施"）。 -->
          <el-form-item label="强制措施">
            <el-radio-group v-model="measureTaken" @change="onMeasureToggle">
              <el-radio-button :value="false">否</el-radio-button>
              <el-radio-button :value="true">是</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row v-if="measureTaken" :gutter="12">
        <el-col :span="12" :xs="24">
          <el-form-item label="措施种类" required>
            <el-select v-model="form.caseMeasure" placeholder="请选择采取的强制措施" style="width: 100%">
              <el-option v-for="m in MEASURE_CHOICES" :key="m.value" :label="m.label" :value="m.value" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12" :xs="24">
          <div class="cf-form-tip" style="margin-top: 6px">
            措施期限在「案件盯办」里登记（系统按措施类型自动推算届满日）
          </div>
        </el-col>
      </el-row>

      <!-- 期限三件套（2026-10-09）：节点叫什么 + 哪一天 + 提前多久提醒。
            原来的「+1天 / +3天 / +7天」按钮取消——期限该是哪天就填哪天，
            快捷键只会引诱人随手一点填个不准的日期。 -->
      <el-row :gutter="12">
        <el-col :span="8" :xs="24">
          <el-form-item label="时间节点">
            <el-autocomplete
              v-model="form.deadlineLabel"
              :fetch-suggestions="suggestLabel"
              placeholder="如：受案时间（选填）"
              maxlength="64"
              clearable
              style="width: 100%"
            />
            <div class="cf-form-tip">填了就按这个名字显示；不填默认叫「截止期限」</div>
          </el-form-item>
        </el-col>
        <el-col :span="8" :xs="24">
          <el-form-item label="期限日期">
            <el-date-picker
              v-model="deadlineDay"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择日期（精确到天）"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8" :xs="24">
          <el-form-item label="提前提醒">
            <el-select v-model="form.remindDays" placeholder="不提醒" clearable style="width: 100%">
              <el-option v-for="d in REMIND_DAY_PRESETS" :key="d" :label="`提前 ${d} 天`" :value="d" />
            </el-select>
            <div class="cf-form-tip">{{ remindHint }}</div>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="备注">
        <el-input v-model="form.description" type="textarea" :rows="2" placeholder="案件要点（选填）" />
      </el-form-item>

      <el-form-item label="立即指派">
        <el-switch v-model="assignNow" />
        <span class="cf-muted" style="margin-left: 8px">开启后可一步完成「建案 + 指派」</span>
      </el-form-item>

      <el-form-item v-if="assignNow" label="承办人">
        <EmployeePicker :owner-id="null" :member-ids="[]" @change="onPick" />
      </el-form-item>
      <el-form-item v-if="assignNow" label="指派要求">
        <el-input v-model="form.assignNote" type="textarea" :rows="2" placeholder="办理要求（选填）" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="loading" @click="submit">{{ isEdit ? '保存' : '创建' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import EmployeePicker from './EmployeePicker.vue'
import { caseApi } from '../api'
import { useCategoryStore } from '../store/category'
import {
  MEASURE_CHOICES, DEADLINE_LABEL_PRESETS, REMIND_DAY_PRESETS
} from '../utils/format'

const categoryStore = useCategoryStore()

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  caseId: { type: [Number, null], default: null }
})
const emit = defineEmits(['update:modelValue', 'done'])

const visible = ref(false)
const loading = ref(false)
const assignNow = ref(false)
const fileList = ref([])
const uploadAction = '/api/files/upload'
const uploadHeaders = { 'X-Token': localStorage.getItem('cf_token') || '' }

// ---- 提前提醒：记住本人常用档位 ----
// 注意：这一块必须写在 emptyForm() 之前——emptyForm 里会调 defaultRemindDays()，
// 而 `const` 声明存在暂时性死区，写在后面会报 "Cannot access 'readUsed' before initialization"。
const REMIND_USED_KEY = 'cf_remind_days_used'
const readUsed = () => {
  try {
    const raw = JSON.parse(localStorage.getItem(REMIND_USED_KEY) || '[]')
    return Array.isArray(raw) ? raw.filter((x) => Number.isFinite(Number(x))) : []
  } catch (e) {
    return []
  }
}
const usedDays = ref(readUsed())
/** 建新案时的默认值：本人最常用的一档；没历史就给 3 天（临期提醒最常用的一档） */
function defaultRemindDays() {
  const used = readUsed()
  if (!used.length) return 3
  const count = {}
  used.forEach((d) => { count[d] = (count[d] || 0) + 1 })
  return Number(Object.keys(count).sort((a, b) => count[b] - count[a])[0]) || 3
}

const emptyForm = () => ({
  name: '',
  sourceType: 'MANUAL',
  sourceFileId: null,
  priority: 'NORMAL',
  caseType: '',
  category: '',
  filingNo: '',
  // 强制措施：'NONE'/空 = 未采取；具体取值见 MEASURE_CHOICES
  caseMeasure: 'NONE',
  // 期限三件套：节点名称 / 日期（只到天）/ 提前提醒天数
  deadlineLabel: '',
  deadline: null,
  remindDays: defaultRemindDays(),
  description: '',
  ownerId: null,
  memberIds: [],
  assignNote: ''
})
const form = ref(emptyForm())
const isEdit = computed(() => props.caseId != null)

// ---- 期限：按天记录 ----
/** 日期部分单独用一个 ref（type=date），提交时补上 23:59:59 再交给后端 */
const deadlineDay = ref('')
/** 常用提示：把你自己的常用档位排在前面（用得多的先出现） */
const remindHint = computed(() => {
  const top = [...new Set(usedDays.value)].slice(-2).reverse()
  if (!top.length) return '常用：提前 3 天 / 7 天'
  return `你常用：${top.map((d) => '提前 ' + d + ' 天').join(' / ')}`
})
const recordRemindDays = (d) => {
  if (d == null) return
  usedDays.value = [...readUsed(), d].slice(-20)
  localStorage.setItem(REMIND_USED_KEY, JSON.stringify(usedDays.value))
}

/** 时间节点的输入建议：常用节点名称 + 允许自己手打 */
const suggestLabel = (kw, cb) => {
  const k = (kw || '').trim()
  const list = DEADLINE_LABEL_PRESETS
    .filter((t) => !k || t.includes(k))
    .map((t) => ({ value: t }))
  cb(list)
}

// ---- 强制措施：是否采取 ----
const measureTaken = ref(false)
const onMeasureToggle = (v) => {
  if (!v) {
    form.value.caseMeasure = 'NONE'
  } else if (form.value.caseMeasure === 'NONE') {
    form.value.caseMeasure = ''
  }
}

// 案件分类级联（大类 -> 小类逐层选择）；form.caseType/category 由级联路径拆出
const formCascade = ref([])
watch(formCascade, (val) => {
  form.value.caseType = val?.[0] || ''
  form.value.category = val?.[1] || ''
})

watch(() => props.modelValue, async (v) => {
  visible.value = v
  if (!v) return
  await categoryStore.load()
  form.value = emptyForm()
  formCascade.value = []
  fileList.value = []
  assignNow.value = false
  deadlineDay.value = ''
  measureTaken.value = false
  if (props.caseId) {
    const d = await caseApi.detail(props.caseId)
    form.value.name = d.name
    form.value.sourceType = d.sourceType
    form.value.priority = d.priority
    form.value.caseType = d.caseType || ''
    form.value.category = d.category || ''
    // 兼容旧数据：只有小类没有大类时按小类反查所属大类
    if (!form.value.caseType && form.value.category) {
      form.value.caseType = categoryStore.typeOfCategory(form.value.category)
    }
    formCascade.value = categoryStore.pathOf(form.value.caseType, form.value.category)
    form.value.filingNo = d.filingNo || ''
    // 强制措施：盯办模块登记过的值原样回显（空/NONE 都算「未采取」）
    const m = (d.caseMeasure || '').toUpperCase()
    measureTaken.value = !!m && m !== 'NONE'
    form.value.caseMeasure = measureTaken.value ? m : 'NONE'
    // 期限：按天回显（后端带时分，这里只取日期部分）
    deadlineDay.value = (d.deadline || '').slice(0, 10)
    form.value.deadlineLabel = d.deadlineLabel || ''
    form.value.remindDays = d.remindDays || null
    form.value.description = d.description || ''
  }
})
watch(visible, (v) => emit('update:modelValue', v))

const onUploadSuccess = (res) => {
  // el-upload 使用原生 XHR，这里拿到的是原始响应体 {code, msg, data}
  const file = res && res.data ? res.data : null
  if (file && file.id) {
    form.value.sourceFileId = file.id
    const map = { pdf: 'PDF', doc: 'WORD', docx: 'WORD', wps: 'WORD', xls: 'EXCEL', xlsx: 'EXCEL', csv: 'EXCEL' }
    const ext = (file.fileName || '').split('.').pop().toLowerCase()
    if (map[ext]) form.value.sourceType = map[ext]
    ElMessage.success('材料已上传')
  }
}
const onUploadRemove = () => {
  form.value.sourceFileId = null
}

const onPick = (payload) => {
  form.value.ownerId = payload.ownerId
  form.value.memberIds = payload.memberIds
}

const submit = async () => {
  if (!form.value.name || !form.value.name.trim()) {
    ElMessage.warning('请填写案件名称')
    return
  }
  // 案件类型（大类）强制必选：未选无法提交
  if (!form.value.caseType) {
    ElMessage.warning('请选择案件类型（案件分类）')
    return
  }
  // 选了「已采取强制措施」就必须选到具体是哪一种，
  // 否则会存出一个"说了采取、却没说是哪种"的空值。
  if (measureTaken.value && !form.value.caseMeasure) {
    ElMessage.warning('请选择采取的强制措施')
    return
  }
  loading.value = true
  try {
    const payload = { ...form.value }
    // 期限按天：把选到的日期落在该日 23:59:59。
    // 不补这一下的话，当天 00:00 就已经小于此刻，整天都会被算成"已逾期"。
    payload.deadline = deadlineDay.value ? `${deadlineDay.value} 23:59:59` : null
    if (!assignNow.value) {
      payload.ownerId = null
      payload.memberIds = []
    }
    // 记住这次选的提醒档位，下次建案直接给到（"根据用户的常用选择"）
    recordRemindDays(payload.remindDays)
    if (props.caseId) {
      await caseApi.update(props.caseId, payload)
    } else {
      await caseApi.create(payload)
    }
    ElMessage.success(props.caseId ? '已保存' : '案件已创建')
    visible.value = false
    emit('done')
  } finally {
    loading.value = false
  }
}
</script>
