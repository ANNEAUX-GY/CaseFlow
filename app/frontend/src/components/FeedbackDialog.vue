<template>
  <el-dialog
    v-model="visible"
    :title="title"
    :width="isMobile ? '96%' : '560px'"
    append-to-body
    destroy-on-close
  >
    <div v-if="quote" class="cf-fb__quote">{{ quote }}</div>

    <el-form label-width="92px" style="margin-top: 12px">
      <el-form-item label="落实状态" required>
        <el-radio-group v-model="status">
          <el-radio-button value="DONE">完成</el-radio-button>
          <el-radio-button value="IN_PROGRESS">进行中</el-radio-button>
          <el-radio-button value="NOT_DONE">未完成</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="落实说明">
        <el-input v-model="note" type="textarea" :rows="2"
          placeholder="选填，补充说明落实情况" maxlength="600" show-word-limit />
      </el-form-item>
    </el-form>

    <!-- 上传声明：替代原"佐证材料"上传。填了平台/文件即视为作出声明，
         提交时自动拼成一句"于…在…上传了…。"并入落实说明 -->
    <div class="cf-fb__declare">
      <div class="cf-fb__declare-title">上传声明<span class="cf-muted">（代替上传佐证材料，选填）</span></div>
      <div class="cf-fb__declare-tip">
        请输入：于<el-date-picker v-model="declareTime" type="datetime" size="small"
          format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DD HH:mm:ss"
          placeholder="点击选择时间" :clearable="false"
          class="cf-fb__slot-time" style="width: 190px" />（不选则默认当前时间，精确到秒）在
        <el-input v-model="declarePlatform" class="cf-fb__slot-input" size="small"
          placeholder="平台名称，如：一体化办案平台" maxlength="100" style="width: 180px" /> 上传了
        <el-input v-model="declareFile" class="cf-fb__slot-input" size="small"
          placeholder="文件名称，如：调取监控情况说明.docx" maxlength="200" style="width: 200px" /> 。
      </div>
      <div class="cf-fb__declare-quick">
        常用平台：
        <el-link v-for="p in COMMON_PLATFORMS" :key="p" type="primary" :underline="false"
          style="font-size: 12px; margin-right: 10px" @click="declarePlatform = p">{{ p }}</el-link>
      </div>
      <div v-if="declareSentence" class="cf-fb__declare-preview">
        将并入说明：{{ declareSentence }}
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <!-- 确认文案随场景变：纯反馈=「提交反馈」；勾选完成进来的=「确认完成」
           （同一弹窗复用，但后者语义是完成汇报，别再叫提交反馈） -->
      <el-button type="primary" :loading="loading" @click="submit">{{ confirmText }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 落实反馈弹窗（图二口径，主任务/子任务共用）：
 * 落实状态（完成/进行中/未完成）+ 落实说明 + 上传声明句。
 *
 * 弹窗本身不调接口：submit 事件把 { status, note } 交给父组件，
 * 由父组件决定走「标记完成」还是「仅记录反馈」。
 */
import { computed, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useDevice } from '../utils/device'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '反馈落实情况' },
  /** 弹窗顶部引用的原文（意见内容 / 任务标题） */
  quote: { type: String, default: '' },
  /** 打开时默认选中的落实状态 */
  defaultStatus: { type: String, default: 'DONE' },
  /** 确认按钮文案：反馈场景「提交反馈」/ 完成场景「确认完成」 */
  confirmText: { type: String, default: '提交反馈' },
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'submit'])

const { isMobile } = useDevice()

const COMMON_PLATFORMS = ['一体化办案平台', '全国公安信息平台', '电子卷宗系统', '执法办案系统']

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const state = reactive({
  status: 'DONE', note: '', declareTime: '', declarePlatform: '', declareFile: ''
})
const status = computed({
  get: () => state.status,
  set: (v) => { state.status = v }
})
const note = computed({
  get: () => state.note,
  set: (v) => { state.note = v }
})
const declareTime = computed({
  get: () => state.declareTime,
  set: (v) => { state.declareTime = v }
})
const declarePlatform = computed({
  get: () => state.declarePlatform,
  set: (v) => { state.declarePlatform = v }
})
const declareFile = computed({
  get: () => state.declareFile,
  set: (v) => { state.declareFile = v }
})

const pad = (n) => String(n).padStart(2, '0')
const nowText = () => {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// 打开时重置：状态取 defaultStatus，说明留空，声明时间默认当前（精确到秒）
watch(visible, (v) => {
  if (v) {
    state.status = props.defaultStatus || 'DONE'
    state.note = ''
    state.declareTime = nowText()
    state.declarePlatform = ''
    state.declareFile = ''
  }
})

/** 声明句：平台/文件任一填写即生成；时间留空则取提交时刻 */
const declareSentence = computed(() => {
  const platform = (state.declarePlatform || '').trim()
  const file = (state.declareFile || '').trim()
  if (!platform && !file) return ''
  const time = state.declareTime || nowText()
  return `于 ${time} 在 ${platform || '（待填平台）'} 上传了 ${file || '（待填文件）'}。`
})

const submit = () => {
  // 平台与文件必须成对：声明句里出现"（待填）"就提交不出去，避免留下残缺声明
  const platform = (state.declarePlatform || '').trim()
  const file = (state.declareFile || '').trim()
  if ((platform && !file) || (!platform && file)) {
    ElMessage.warning('上传声明的平台名称与文件名称需填写完整，或两项都留空')
    return
  }
  const parts = []
  if (state.note.trim()) parts.push(state.note.trim())
  if (declareSentence.value) parts.push(declareSentence.value)
  if (!state.status) {
    ElMessage.warning('请选择落实状态')
    return
  }
  if (!parts.length) {
    ElMessage.warning('请填写落实说明或上传声明')
    return
  }
  emit('submit', { status: state.status, note: parts.join('\n') })
}
</script>

<style>
.cf-fb__quote {
  padding: 8px 10px; background: #f7f9fc; border-left: 3px solid #1b4a8c;
  font-size: 13px; color: #1b2430; white-space: pre-wrap; word-break: break-all;
}
.cf-fb__declare {
  margin: 4px 16px 0; padding: 10px 12px; border: 1px dashed #c9d4e3; border-radius: 4px; background: #fbfcfe;
}
.cf-fb__declare-title { font-size: 13px; font-weight: 600; color: #1b2430; margin-bottom: 8px }
.cf-fb__declare-tip { font-size: 13px; color: #3d4654; line-height: 2.2 }
.cf-fb__slot-time { vertical-align: middle; margin: 0 2px }
.cf-fb__slot-input { display: inline-block; vertical-align: middle; margin: 0 2px; width: 180px !important }
.cf-fb__declare-quick { font-size: 12px; color: #8a929e; margin-top: 6px }
.cf-fb__declare-preview {
  margin-top: 8px; font-size: 12px; color: #1b4a8c;
  background: #eef3fa; border-radius: 3px; padding: 6px 8px; word-break: break-all;
}
.cf-muted { color: #8a929e }
</style>
