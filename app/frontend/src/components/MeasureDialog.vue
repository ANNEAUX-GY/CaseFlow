<template>
  <!-- 登记 / 变更强制措施（2026-10-24 抽出独立组件）
       为什么要抽：领导在「案件盯办」页用，承办人在「我的案件」页也要用（他没有盯办页权限）。
       两处共用同一份表单，避免"领导这边能选逮捕、承办人那边不能"这类口径分裂。
       提交后按后端返回区分：pending=true 表示只生成了待审批申请，措施并未改变。 -->
  <el-dialog
    :model-value="modelValue"
    title="登记 / 变更强制措施"
    width="480px"
    append-to-body
    destroy-on-close
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <el-form label-width="90px">
      <el-form-item label="措施类型" required>
        <el-select v-model="form.measure" style="width: 100%" @change="onMeasureChange">
          <el-option label="无（结束当前措施）" value="NONE" />
          <el-option v-for="m in MEASURE_CHOICES" :key="m.value" :label="m.label" :value="m.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="措施日期" :required="needDate">
        <el-date-picker v-model="form.measureDate" type="date" value-format="YYYY-MM-DD"
          :disabled="form.measure === 'NONE'" style="width: 100%" />
      </el-form-item>
      <el-form-item label="期限届满">
        <el-date-picker v-model="form.detainDeadline" type="date" value-format="YYYY-MM-DD"
          :disabled="form.measure === 'NONE'"
          placeholder="留空按默认推算"
          style="width: 100%" />
        <div v-if="form.measure !== 'NONE'" class="cf-muted" style="font-size: 12px; line-height: 1.5">
          默认：拘留 3 天 / 逮捕 2 个月 / 取保 1 年 / 监居 6 个月
        </div>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.comment" placeholder="选填；提交审批时会一并带给领导" maxlength="200" />
      </el-form-item>
      <div class="cf-muted" style="font-size: 12px; line-height: 1.6; padding-left: 90px">
        <template v-if="isLeader">你是领导，保存后立即生效。</template>
        <template v-else>保存后生成待审批申请，<b>措施暂不变</b>，需领导批准后才生效。</template>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="submit">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { watchApi } from '../api'
import { MEASURE_CHOICES } from '../utils/format'
import { useUserStore } from '../store/user'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  caseId: { type: [Number, String], default: null },
  caseType: { type: String, default: '' },
  /** 当前措施（打开时预填，变更时一眼看到从哪改到哪） */
  currentMeasure: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'done'])

const userStore = useUserStore()
const isLeader = computed(() => userStore.isFullAccess)

const form = reactive({ measure: '', measureDate: '', detainDeadline: '', comment: '' })
const loading = ref(false)

watch(() => props.modelValue, (v) => {
  if (!v) return
  form.measure = props.currentMeasure && props.currentMeasure !== 'NONE' ? props.currentMeasure : 'DETENTION'
  form.measureDate = ''
  form.detainDeadline = ''
  form.comment = ''
})

// 拘捕必须有实际执行日期（后端也会校验，这里先挡一道，省得提交才报错）
const needDate = computed(() => props.caseType === 'CRIMINAL'
  && ['DETENTION', 'ARREST'].includes(form.measure)
  && form.measure !== props.currentMeasure)

const onMeasureChange = () => {
  form.measureDate = ''
  form.detainDeadline = ''
}

const submit = async () => {
  if (needDate.value && !form.measureDate) {
    ElMessage.warning('请填写拘捕实际执行日期')
    return
  }
  loading.value = true
  try {
    const res = await watchApi.measure(props.caseId, {
      measure: form.measure,
      measureDate: form.measureDate || undefined,
      detainDeadline: form.detainDeadline || undefined,
      comment: form.comment
    })
    if (res && res.pending) {
      ElMessage.warning(res.message || '变更申请已提交，待领导批准后生效')
    } else {
      ElMessage.success('已登记')
    }
    emit('update:modelValue', false)
    emit('done')
  } catch (e) {
    // 请求拦截器已经弹过错误提示（如"已有一条待审批"），这里只兜住 promise，
    // 否则会变成 unhandled rejection 冒到页面报错里，看着像页面崩了。
  } finally {
    loading.value = false
  }
}
</script>
