<template>
  <div class="cf-opinion">
    <!-- 面板头：计数（管理端的录入入口在下方接龙区，不再用弹窗） -->
    <div class="cf-opinion__head">
      <span>领导意见</span>
      <span class="cf-muted">共 {{ opinions.length }} 条</span>
      <span class="cf-spacer"></span>
    </div>

    <!-- 已提交意见：接龙式编号列表，每条独占一行 -->
    <div v-if="opinions.length" class="cf-opinion__list">
      <div v-for="(o, i) in opinions" :key="o.id" class="cf-opinion__item">
        <span class="cf-opinion__no">{{ i + 1 }}</span>
        <div class="cf-opinion__item-body">
          <div class="cf-opinion__content">{{ o.content }}</div>
          <div class="cf-opinion__meta">
            {{ o.creatorName || '管理层' }} · {{ (o.createdAt || '').slice(0, 16) }} 提出
          </div>

          <!-- 反馈区：未反馈 / 已反馈两种态 -->
          <div class="cf-opinion__feedback">
            <template v-if="o.feedbackStatus">
              <el-tag size="small" :type="(FB_META[o.feedbackStatus] || {}).type" effect="dark">
                {{ (FB_META[o.feedbackStatus] || {}).label }}
              </el-tag>
              <span class="cf-opinion__feedback-meta">
                {{ o.feedbackByName }} · {{ (o.feedbackAt || '').slice(0, 19) }} 反馈
              </span>
            </template>
            <template v-else>
              <el-tag size="small" type="info" effect="plain">待反馈</el-tag>
              <span class="cf-opinion__feedback-meta cf-muted">办案人尚未反馈落实情况</span>
            </template>
            <span class="cf-spacer"></span>
            <!-- 反馈入口：仅本案办案人（管理员/领导不代反馈，落实是办案人的事） -->
            <el-button v-if="isAssignee" link type="primary" size="small" @click="openFeedback(o)">
              {{ o.feedbackStatus ? '更新反馈' : '反馈落实情况' }}
            </el-button>
          </div>
          <div v-if="o.feedbackNote" class="cf-opinion__note">{{ o.feedbackNote }}</div>
        </div>
      </div>
    </div>
    <div v-else class="cf-muted" style="padding: 4px 0 8px">
      {{ isFullAccess ? '暂无意见，点下方 ＋ 逐条输入' : '暂无意见' }}
    </div>

    <!-- 接龙式录入（管理端）：点 ＋ 生成一行「序号 + 输入框」，每条意见独占一行 -->
    <div v-if="isFullAccess" class="cf-opinion__drafts">
      <div v-for="(d, i) in drafts" :key="'d' + i" class="cf-opinion__draft">
        <span class="cf-opinion__no">{{ opinions.length + i + 1 }}</span>
        <el-input
          v-model="drafts[i]"
          size="small"
          maxlength="500"
          placeholder="输入意见内容…"
          @keyup.enter="submitDrafts"
        />
        <el-button link type="danger" size="small" class="cf-opinion__draft-del"
          @click="drafts.splice(i, 1)">移除</el-button>
      </div>

      <div class="cf-opinion__draft-add">
        <el-button class="cf-opinion__plus" circle size="small" @click="addDraft">＋</el-button>
        <span class="cf-muted cf-opinion__plus-hint">
          {{ drafts.length ? '继续点 ＋ 依次添加' : '点 ＋ 开始输入意见，每条一行' }}
        </span>
        <span class="cf-spacer"></span>
        <el-button v-if="filledCount > 0" type="primary" size="small" :loading="saving"
          @click="submitDrafts">
          提交 {{ filledCount }} 条意见
        </el-button>
      </div>
    </div>
    <div v-else-if="!opinions.length" style="padding: 0 0 8px"></div>

    <!-- 反馈落实情况（办案人）。功能3：取消佐证材料上传，改为结构化"上传声明"提示语 -->
    <el-dialog v-model="fbDlg.visible" title="反馈意见落实情况" :width="isMobile ? '96%' : '560px'" append-to-body>
      <div class="cf-opinion__quote">{{ fbDlg.row?.content }}</div>

      <el-form label-width="92px" style="margin-top: 12px">
        <el-form-item label="落实状态" required>
          <el-radio-group v-model="fbDlg.status">
            <el-radio-button value="DONE">完成</el-radio-button>
            <el-radio-button value="IN_PROGRESS">进行中</el-radio-button>
            <el-radio-button value="NOT_DONE">未完成</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="落实说明">
          <el-input v-model="fbDlg.note" type="textarea" :rows="2"
            placeholder="选填，补充说明落实情况" maxlength="600" show-word-limit />
        </el-form-item>
      </el-form>

      <!-- 上传声明：替代原"佐证材料"上传。填了平台/文件即视为作出声明，
           提交时自动拼成一句"于…在…上传了…。"并入反馈说明 -->
      <div class="cf-opinion__declare">
        <div class="cf-opinion__declare-title">上传声明<span class="cf-muted">（代替上传佐证材料，选填）</span></div>
        <div class="cf-opinion__declare-tip">
          请输入：于<el-date-picker v-model="fbDlg.declareTime" type="datetime" size="small"
            format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="点击选择时间" :clearable="false"
            class="cf-opinion__slot-time" style="width: 190px" />（不选则默认当前时间，精确到秒）在
          <el-input v-model="fbDlg.declarePlatform" class="cf-opinion__slot-input" size="small"
            placeholder="平台名称，如：一体化办案平台" maxlength="100" style="width: 180px" /> 上传了
          <el-input v-model="fbDlg.declareFile" class="cf-opinion__slot-input" size="small"
            placeholder="文件名称，如：调取监控情况说明.docx" maxlength="200" style="width: 200px" /> 。
        </div>
        <!-- 常用平台一键填入 -->
        <div class="cf-opinion__declare-quick">
          常用平台：
          <el-link v-for="p in COMMON_PLATFORMS" :key="p" type="primary" :underline="false"
            style="font-size: 12px; margin-right: 10px" @click="fbDlg.declarePlatform = p">{{ p }}</el-link>
        </div>
        <!-- 实时预览：最终并入说明的声明句 -->
        <div v-if="declareSentence" class="cf-opinion__declare-preview">
          将并入说明：{{ declareSentence }}
        </div>
      </div>

      <template #footer>
        <el-button @click="fbDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="fbDlg.loading" @click="submitFeedback">提交反馈</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { watchApi } from '../api'
import { FEEDBACK_STATUS_META as FB_META } from '../utils/format'
import { useUserStore } from '../store/user'
import { useDevice } from '../utils/device'

const { isMobile } = useDevice()
const userStore = useUserStore()

const props = defineProps({
  caseId: { type: [Number, null], default: null },
  /** 案件详情（用于判断当前用户是否本案办案人） */
  detail: { type: Object, default: () => ({}) },
  /** 是否管理层（管理员/领导）：决定接龙式录入区是否显示 */
  isFullAccess: { type: Boolean, default: false }
})
const emit = defineEmits(['changed'])

// 常用办案平台建议（一键填入，可自由输入其他平台）
const COMMON_PLATFORMS = ['一体化办案平台', '全国公安信息平台', '电子卷宗系统', '执法办案系统']

const isAssignee = computed(() => {
  const myEmp = userStore.userInfo?.employeeId
  if (!myEmp) return false
  return (props.detail.assignHistory || []).some(
    (a) => a.status === 'ACTIVE' && String(a.employeeId) === String(myEmp))
})

const opinions = ref([])
const reload = async () => {
  if (!props.caseId) { opinions.value = []; return }
  opinions.value = await watchApi.opinions(props.caseId)
}
watch(() => props.caseId, reload, { immediate: true })

// ---- 接龙式录入（管理端）：点 ＋ 生成一行序号+输入框，一次可写多条，批量提交 ----
const drafts = ref([])
const saving = ref(false)
const filledCount = computed(() => drafts.value.filter((d) => d.trim()).length)

const addDraft = () => {
  drafts.value.push('')
  // 自动聚焦最新一行，输入不断手
  requestAnimationFrame(() => {
    const inputs = document.querySelectorAll('.cf-opinion__draft .el-input__inner')
    inputs[inputs.length - 1]?.focus()
  })
}

const submitDrafts = async () => {
  const contents = drafts.value.map((d) => d.trim()).filter(Boolean)
  if (!contents.length) {
    ElMessage.warning('请先输入意见内容')
    return
  }
  saving.value = true
  try {
    for (const c of contents) {
      await watchApi.addOpinion(props.caseId, c)
    }
    ElMessage.success(`已提交 ${contents.length} 条意见`)
    drafts.value = []
    await reload()
    emit('changed')
  } finally {
    saving.value = false
  }
}

// ---- 反馈落实情况（办案人） ----
const pad = (n) => String(n).padStart(2, '0')
const nowText = () => {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const fbDlg = reactive({
  visible: false, row: null, status: 'DONE', note: '',
  declareTime: '', declarePlatform: '', declareFile: '', loading: false
})
const openFeedback = (o) => {
  fbDlg.row = o
  // 已反馈过 → 带出上次状态便于增量更新；声明字段每次留空由办案人现填
  fbDlg.status = o.feedbackStatus || 'DONE'
  fbDlg.note = ''
  // 时间默认当前（精确到秒），可改；提交时若被清空也兜底取当下
  fbDlg.declareTime = nowText()
  fbDlg.declarePlatform = ''
  fbDlg.declareFile = ''
  fbDlg.visible = true
}

/** 声明句：平台/文件任一填写即生成；时间留空则取提交时刻 */
const declareSentence = computed(() => {
  const platform = (fbDlg.declarePlatform || '').trim()
  const file = (fbDlg.declareFile || '').trim()
  if (!platform && !file) return ''
  const time = fbDlg.declareTime || nowText()
  return `于 ${time} 在 ${platform || '（待填平台）'} 上传了 ${file || '（待填文件）'}。`
})

const submitFeedback = async () => {
  // 平台与文件必须成对：声明句里出现"（待填）"就提交不出去，避免留下残缺声明
  const platform = (fbDlg.declarePlatform || '').trim()
  const file = (fbDlg.declareFile || '').trim()
  if ((platform && !file) || (!platform && file)) {
    ElMessage.warning('上传声明的平台名称与文件名称需填写完整，或两项都留空')
    return
  }
  const parts = []
  if (fbDlg.note.trim()) parts.push(fbDlg.note.trim())
  if (declareSentence.value) parts.push(declareSentence.value)
  const note = parts.join('\n')
  if (!fbDlg.status) { ElMessage.warning('请选择落实状态'); return }
  if (!note) { ElMessage.warning('请填写落实说明或上传声明'); return }

  fbDlg.loading = true
  try {
    await watchApi.feedbackOpinion(fbDlg.row.id, { status: fbDlg.status, note })
    ElMessage.success('反馈已提交')
    fbDlg.visible = false
    await reload()
    emit('changed')
  } finally { fbDlg.loading = false }
}

defineExpose({ reload })
</script>

<style>
.cf-opinion__head { display: flex; align-items: center; gap: 8px; padding: 12px 16px 6px; font-weight: 600 }
.cf-opinion__list { padding: 0 16px }
.cf-opinion__item { display: flex; gap: 10px; padding: 10px 0; border-bottom: 1px dashed #dfe4ea }
.cf-opinion__item:last-child { border-bottom: none }
/* 接龙式序号：圆形徽标，与微信接龙的行首编号一致 */
.cf-opinion__no {
  flex: none; width: 20px; height: 20px; line-height: 20px; text-align: center;
  background: #eef3fa; color: #1b4a8c; border-radius: 50%;
  font-size: 12px; font-weight: 600; margin-top: 1px;
}
.cf-opinion__item-body { flex: 1; min-width: 0 }
.cf-opinion__content { font-size: 13px; color: #1b2430; white-space: pre-wrap; word-break: break-all }
.cf-opinion__meta { font-size: 12px; color: #8a929e; margin-top: 4px }
.cf-opinion__feedback { display: flex; align-items: center; gap: 8px; margin-top: 8px; flex-wrap: wrap }
.cf-opinion__feedback-meta { font-size: 12px; color: #5a6472 }
.cf-opinion__note {
  margin-top: 8px; padding: 8px 10px; background: #f7f9fc; border-left: 3px solid #b9c6d8;
  font-size: 12px; color: #3d4654; white-space: pre-wrap; word-break: break-all;
}
/* 接龙式录入区：＋ 按钮 + 序号行输入，简单直接 */
.cf-opinion__drafts { padding: 6px 16px 14px }
.cf-opinion__draft { display: flex; align-items: center; gap: 10px; padding: 4px 0 }
.cf-opinion__draft .el-input { flex: 1 }
.cf-opinion__draft-del { flex: none }
.cf-opinion__draft-add { display: flex; align-items: center; gap: 10px; padding: 8px 0 0 }
.cf-opinion__plus {
  width: 28px !important; height: 28px !important; padding: 0 !important;
  font-size: 17px; font-weight: 600; color: #1b4a8c !important;
  border-color: #b9cbe6 !important; background: #f2f6fc !important;
}
.cf-opinion__plus:hover { background: #e3edfa !important }
.cf-opinion__plus-hint { font-size: 12px }
/* 反馈弹窗里的意见原文引用块 */
.cf-opinion__quote {
  padding: 8px 10px; background: #f7f9fc; border-left: 3px solid #1b4a8c;
  font-size: 13px; color: #1b2430; white-space: pre-wrap; word-break: break-all;
}
/* 上传声明区：替代原佐证材料上传 */
.cf-opinion__declare {
  margin: 4px 16px 0; padding: 10px 12px; border: 1px dashed #c9d4e3; border-radius: 4px; background: #fbfcfe;
}
.cf-opinion__declare-title { font-size: 13px; font-weight: 600; color: #1b2430; margin-bottom: 8px }
.cf-opinion__declare-tip { font-size: 13px; color: #3d4654; line-height: 2.2 }
.cf-opinion__slot-time { vertical-align: middle; margin: 0 2px }
.cf-opinion__slot-input { display: inline-block; vertical-align: middle; margin: 0 2px; width: 180px !important }
.cf-opinion__declare-quick { font-size: 12px; color: #8a929e; margin-top: 6px }
.cf-opinion__declare-preview {
  margin-top: 8px; font-size: 12px; color: #1b4a8c;
  background: #eef3fa; border-radius: 3px; padding: 6px 8px; word-break: break-all;
}
.cf-muted { color: #8a929e }
</style>
