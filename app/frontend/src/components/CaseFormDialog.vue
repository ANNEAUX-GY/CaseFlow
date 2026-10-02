<template>
  <!-- 手机端整屏弹窗：760px 的表单塞进 375px 会到处折行，全屏反而更好填 -->
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑案件' : '新建案件'"
    :width="isMobile ? '96%' : '760px'"
    :fullscreen="isMobile"
    destroy-on-close
    class="cf-dialog"
  >
    <el-form :model="form" :label-width="isMobile ? '84px' : '92px'">
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
          <el-form-item label="立案登记表">
            <el-input v-model="form.filingNo" placeholder="立案登记表编号 / 受案号（选填）" maxlength="64" />
          </el-form-item>
        </el-col>
        <el-col :span="12" :xs="24">
          <el-form-item label="调解书">
            <el-input v-model="form.mediationNo" placeholder="调解书编号（选填）" maxlength="64" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="截止期限">
        <el-date-picker
          v-model="form.deadline"
          type="datetime"
          value-format="YYYY-MM-DD HH:mm:ss"
          placeholder="选择截止日期时间"
          :style="{ width: isMobile ? '100%' : '240px' }"
        />
        <el-button-group style="margin-left: 8px">
          <el-button size="small" @click="quickDeadline(1)">+1天</el-button>
          <el-button size="small" @click="quickDeadline(3)">+3天</el-button>
          <el-button size="small" @click="quickDeadline(7)">+7天</el-button>
          <el-button size="small" @click="form.deadline = null">清空</el-button>
        </el-button-group>
      </el-form-item>

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
import { useDevice } from '../utils/device'
import { useCategoryStore } from '../store/category'

const { isMobile } = useDevice()
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

const emptyForm = () => ({
  name: '',
  sourceType: 'MANUAL',
  sourceFileId: null,
  priority: 'NORMAL',
  caseType: '',
  category: '',
  filingNo: '',
  mediationNo: '',
  deadline: null,
  description: '',
  ownerId: null,
  memberIds: [],
  assignNote: ''
})
const form = ref(emptyForm())
const isEdit = computed(() => props.caseId != null)

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
    form.value.mediationNo = d.mediationNo || ''
    form.value.deadline = d.deadline || null
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

const quickDeadline = (days) => {
  const d = new Date(Date.now() + days * 86400000)
  const pad = (n) => String(n).padStart(2, '0')
  form.value.deadline = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} 18:00:00`
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
  loading.value = true
  try {
    const payload = { ...form.value }
    if (!assignNow.value) {
      payload.ownerId = null
      payload.memberIds = []
    }
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
