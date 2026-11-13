<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="640px"
    append-to-body
    destroy-on-close
    @closed="onClosed"
  >
    <div v-loading="loading" class="cf-td">
      <!-- 概览 -->
      <div v-if="detail.id" class="cf-td__head">
        <el-tag :type="statusType" effect="dark" size="small">
          {{ detail.statusName || (detail.status === 'DONE' ? '已完成' : '待办') }}
        </el-tag>
        <span v-if="detail.doneAt" class="cf-muted">
          完成于 {{ fmt(detail.doneAt) }} · {{ detail.doneByName || '-' }}
        </span>
      </div>

      <!-- 反馈记录（累积，时间正序；带落实状态标签）。
           执行记录三要素（时间/系统/操作）独立成块展示并可逐项修改
           ——原先三者是拼在说明里的一句话，改一个字得整句重写（2026-10-08）。 -->
      <div class="cf-td__sec">
        <div class="cf-td__sec-head">
          <span>反馈记录</span>
          <span class="cf-muted">共 {{ feedbacks.length }} 条</span>
          <!-- 折叠：反馈多时默认只看最新 3 条（时间正序，最后 3 条即最新），
               全量撑开弹窗会盖住子任务和操作区（用户反馈太占版面） -->
          <span class="cf-spacer"></span>
          <el-button v-if="feedbacks.length > FB_PREVIEW" link type="primary" size="small"
            @click.stop="fbExpanded = !fbExpanded">
            {{ fbExpanded ? '收起' : `展开全部 ${feedbacks.length} 条` }}
          </el-button>
        </div>
        <ul v-if="feedbacks.length" class="cf-td__fb-list">
          <li v-for="f in visibleFeedbacks" :key="f.id" class="cf-td__fb">
            <div class="cf-td__fb-meta">
              <span class="cf-td__fb-time">{{ fmt(f.createdAt) }}</span>
              <span class="cf-td__fb-by">{{ f.creatorName || '-' }}</span>
              <!-- statusAt 是反馈当时的落实状态快照，不是当前状态 -->
              <el-tag v-if="f.statusAt" size="small" effect="plain"
                :type="(STATUS_META[f.statusAt] || {}).type">
                {{ (STATUS_META[f.statusAt] || {}).label }}
              </el-tag>
              <!-- 修订痕迹：改过就在块内常驻标注，别让「谁交的」与「谁改的」混淆 -->
              <span v-if="f.editedAt" class="cf-td__fb-edited">
                （{{ f.editedByName || '-' }} 于 {{ fmt(f.editedAt) }} 修改）
              </span>
              <span class="cf-spacer"></span>
              <!-- 修改反馈：提交人本人或管理层（后端二次校验，前端只控显隐） -->
              <el-button v-if="canEditFeedback(f)" link type="primary" size="small"
                class="cf-td__fb-op" @click.stop="openEditFeedback(f)">修改</el-button>
            </div>
            <div v-if="f.content" class="cf-td__fb-text">{{ f.content }}</div>
            <!-- 执行记录块：三要素分行展示，各自可改（改一项不影响其余两项）。
                 文案 2026-10-13 由「上传声明/文件」改为「执行记录/操作」，
                 与 FeedbackDialog 的录入口径一致；字段名沿用 upload* 不动。 -->
            <div v-if="f.uploadPlatform || f.uploadFile" class="cf-td__fb-declare">
              <span class="cf-td__fb-declare-k">执行记录</span>
              <!-- 标签与取值之间加全角冒号，读起来是「标签：值」而不是两个词挤一起 -->
              <span v-if="f.uploadTime" class="cf-td__fb-declare-i">时间：{{ f.uploadTime }}</span>
              <span v-if="f.uploadPlatform" class="cf-td__fb-declare-i">系统：{{ f.uploadPlatform }}</span>
              <span v-if="f.uploadFile" class="cf-td__fb-declare-i">操作：{{ f.uploadFile }}</span>
            </div>
          </li>
        </ul>
        <div v-else class="cf-muted cf-td__empty">
          暂无反馈记录{{ isParent ? '。完成本任务前需至少提交一条反馈说明' : '' }}
        </div>
      </div>

      <!-- 提交反馈入口：走落实反馈弹窗（状态+说明+执行记录）。
           仅普通用户（承办人）显示——反馈是承办人的汇报动作，
           管理层只查阅上方的「反馈记录」，不代提交（2026-10-06 需求） -->
      <div v-if="!isAdmin" class="cf-td__sec">
        <div class="cf-td__sec-head"><span>提交反馈</span></div>
        <div class="cf-td__actions">
          <el-button type="primary" size="small" @click="openRecord">
            提交反馈（选落实状态）
          </el-button>
          <span class="cf-muted cf-td__hint">
            {{ isParent ? '反馈与完成是两个动作：反馈由你提交，标记完成由领导或管理员确认' : '' }}
          </span>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <!-- 撤销完成仅管理层（后端 reopen 限管理员）；标记完成走落实反馈弹窗 -->
      <el-button
        v-if="isParent && isAdmin && done"
        type="warning"
        size="small"
        :loading="savingDone"
        @click="undoMain">
        撤销完成
      </el-button>
      <el-button
        v-if="isParent && !done && isAdmin"
        type="primary"
        size="small"
        :loading="savingDone"
        :disabled="!canDone"
        @click="openCompleteMain">
        标记完成
      </el-button>
    </template>

    <!-- 落实反馈弹窗（主任务/子任务/修改已有反馈共用，图二口径）：
         完成场景确认按钮叫「确认完成」，纯反馈场景叫「提交反馈」，
         修改已有反馈叫「保存修改」——三者语义不同，别都叫提交 -->
    <FeedbackDialog
      v-model="fb.visible"
      :title="fb.title"
      :quote="fb.quote"
      :default-status="fb.status"
      :confirm-text="fb.confirmText"
      :initial="fb.initial"
      :loading="fb.loading"
      @submit="submitFeedback"
    />
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { todoApi } from '../api'
import { useUserStore } from '../store/user'
import FeedbackDialog from './FeedbackDialog.vue'
import { dateKeyOf } from '../utils/format'

const emit = defineEmits(['changed'])

const userStore = useUserStore()
const isAdmin = computed(() => userStore.isFullAccess)

const visible = ref(false)
const loading = ref(false)
const todoId = ref(null)
const data = ref({})

const savingDone = ref(false)

const STATUS_META = {
  DONE: { label: '已完成', type: 'success' },
  IN_PROGRESS: { label: '进行中', type: 'warning' },
  NOT_DONE: { label: '未完成', type: 'danger' },
  PENDING: { label: '待办', type: 'info' }
}

const fmt = (s) => (dateKeyOf(s) || '-')
const detail = computed(() => data.value || {})
const feedbacks = computed(() => detail.value.feedbacks || [])
const done = computed(() => detail.value?.status === 'DONE')

/* ---- 折叠（反馈多时默认收起，避免撑爆弹窗没法滚动看） ---- */
const FB_PREVIEW = 3   // 反馈默认预览条数（时间正序，最后 3 条即最新）
const fbExpanded = ref(false)
const visibleFeedbacks = computed(() =>
  fbExpanded.value ? feedbacks.value : feedbacks.value.slice(-FB_PREVIEW))

const isParent = computed(() => !detail.value?.parentId)

const title = computed(() => detail.value?.content || '任务详情')
const statusType = computed(() => (done.value ? 'success' : 'warning'))

/**
 * 能否标记完成——与后端两条规则保持一致，前端先拦一次给出即时反馈，
 * 后端再挡一次防绕过（前端 disabled 永远不能当校验用）。
 */
const canDone = computed(() => {
  const d = detail.value
  if (!d.id || done.value) return false
  // 规则：至少一条反馈说明
  return feedbacks.value.length > 0
})

const load = async () => {
  if (!todoId.value) return
  loading.value = true
  try {
    data.value = await todoApi.detail(todoId.value) || {}
  } catch (e) {
    data.value = {}
  } finally {
    loading.value = false
  }
}

const open = async (id, anchor) => {
  todoId.value = id
  fbExpanded.value = false
  visible.value = true
  await load()
  // 定位到具体内容（信箱跳转用）：等下一帧让列表渲染完再滚+高亮
  if (anchor && anchor.subtaskId) {
    await locateAnchor(anchor)
  }
}

/**
 * 定位到指定锚点并短暂高亮（2026-10-08 信箱精确定位）。
 *
 * <p><b>为什么高亮而不是只滚动</b>：任务详情里反馈/子任务可能有十几条，
 * 滚到位置只说明"大概在这"，用户还得自己找。短暂高亮一眼就能认出目标。
 *
 * <p><b>降级</b>：目标已被删除/查不到时什么都不做（浮窗照常打开），
 * 由调用方决定是否提示——不在这里报错，免得打断跳转。
 */
const locateAnchor = async (anchor) => {
  await nextTick()
  await new Promise((r) => setTimeout(r, 120))
  const key = String(anchor.subtaskId)
  // CSS.escape：id 虽是数字，但选择器拼接仍走属性选择器更稳
  const el = document.querySelector(`[data-cf-subid="${key}"]`)
  if (!el) return    // 目标已删除：静默降级
  el.scrollIntoView({ block: 'center', behavior: 'smooth' })
  el.classList.add('is-cf-flash')
  setTimeout(() => el.classList.remove('is-cf-flash'), 2600)
}

/** 管理层确认主任务完成时直接落库，不再重复填写落实状态弹窗。 */
const openComplete = async (id) => {
  await open(id)
  await openCompleteMain()
}

/** 关闭浮窗时清掉标题缓存 */
const onClosed = () => {
  todoId.value = null
  data.value = {}
}

/** 带定位锚点打开（信箱「查看案件」用）：打开后滚动到目标并短暂高亮 */
const openAt = async (id, anchor) => { await open(id, anchor) }

defineExpose({ open, openAt, openComplete })

/** 确认按钮文案：完成 / 提交 / 修改是三种语义，不能都叫「提交」 */
const CONFIRM_TEXT = {
  complete: '确认完成',
  submit: '提交反馈',
  edit: '保存修改'
}

// ---- 落实反馈弹窗状态 ----
const fb = reactive({
  visible: false, title: '反馈落实情况', quote: '', status: 'DONE',
  loading: false,
  // complete = 提交后要标记完成
  complete: false,
  // editId 非空 = 修改已有反馈（2026-10-08）；initial 传给 FeedbackDialog 回填
  editId: null, initial: null,
  confirmText: CONFIRM_TEXT.submit
})

const openFeedbackDlg = ({ complete = false }) => {
  fb.complete = complete
  fb.editId = null
  fb.initial = null
  fb.title = complete ? '标记完成' : '反馈落实情况'
  fb.quote = detail.value?.content || ''
  fb.status = complete ? 'DONE' : 'IN_PROGRESS'
  fb.confirmText = complete ? CONFIRM_TEXT.complete : CONFIRM_TEXT.submit
  fb.visible = true
}

/**
 * 打开「修改这条反馈」弹窗（2026-10-08）。
 *
 * <p>回填四项：落实状态、落实说明、上传时间/平台/文件名。
 * 三要素各自独立，用户只改平台名就只改平台名。
 *
 * <p><b>操作时间留空不补当前时间</b>：这条反馈本来就没有执行记录，
 * 硬塞一个「现在」进去会让人以为当时真在那个时刻传的。
 * FeedbackDialog 里只在「新建」场景才默认当前时间。
 */
const openEditFeedback = (f) => {
  fb.complete = false
  fb.editId = f.id
  fb.initial = {
    status: f.statusAt || 'IN_PROGRESS',
    note: f.content || '',
    uploadTime: f.uploadTime || '',
    uploadPlatform: f.uploadPlatform || '',
    uploadFile: f.uploadFile || ''
  }
  fb.title = '修改反馈'
  fb.quote = detail.value?.content || ''
  fb.status = f.statusAt || 'IN_PROGRESS'
  fb.confirmText = CONFIRM_TEXT.edit
  fb.visible = true
}

/** 谁能改这条反馈：提交人本人或管理层（与后端 checkFeedbackEditable 同口径） */
const canEditFeedback = (f) =>
  isAdmin.value || String(f.creatorId) === String(userStore.userInfo?.userId)

const openRecord = () => openFeedbackDlg({ complete: false })
const openCompleteMain = async () => {
  if (!canDone.value || savingDone.value) return
  savingDone.value = true
  try {
    await todoApi.done(todoId.value, '')
    ElMessage.success('已标记完成')
    await load()
    emit('changed')
  } catch (e) {
    await load()
  } finally {
    savingDone.value = false
  }
}

const undoMain = async () => {
  savingDone.value = true
  try {
    await todoApi.reopen(todoId.value)
    ElMessage.success('已撤销完成')
    await load()
    emit('changed')
  } catch (e) {
    await load()
  } finally {
    savingDone.value = false
  }
}

/**
 * 反馈弹窗提交。三条路径：
 * 1) fb.editId 非空 → 改已有那条（不动任务状态）
 * 2) fb.complete → 标记完成（主/子任务）；按弹窗里实际选的落实状态分派
 * 3) 其余 → 只记一条带状态的反馈
 */
const submitFeedback = async ({ status, note, uploadTime, uploadPlatform, uploadFile }) => {
  fb.loading = true
  try {
    // ---- 路径 1：修改已有反馈 ----
    if (fb.editId) {
      await todoApi.updateFeedback(todoId.value, fb.editId, {
        status, content: note, uploadTime, uploadPlatform, uploadFile
      })
      ElMessage.success('反馈已修改')
      fb.visible = false
      await load()
      emit('changed')
      return
    }
    const payload = { status, content: note, uploadTime, uploadPlatform, uploadFile }
    if (fb.complete) {
      // 按用户在弹窗里**实际选的落实状态**分派，而不是勾选时的意图：
      // 选「完成」才标记完成；选「进行中/未完成」说明用户改主意了，
      // 只记一条反馈、不动完成状态——否则弹窗里的状态选择形同虚设。
      const targetId = todoId.value
      // 弹窗里填的落实说明是用户**主动提交的汇报内容**，要进反馈流留痕——
      // 这不是「系统自动提交」（自动指的是 done 时替用户凭空造一条），
      // 而是用户在汇报弹窗里亲手填写并点了提交。先记反馈再标记完成，
      // 顺序也满足主任务「至少一条反馈才能完成」的规则校验。
      if (note || uploadPlatform) {
        await todoApi.addFeedback(targetId, payload)
      }
      if (status === 'DONE') {
        await todoApi.done(targetId, note)
        ElMessage.success('已标记完成')
      } else {
        ElMessage.success('反馈已记录（未标记完成）')
      }
    } else {
      await todoApi.addFeedback(todoId.value, payload)
      ElMessage.success('反馈已记录')
    }
    fb.visible = false
    await load()
    emit('changed')
  } catch (e) {
    await load()
  } finally {
    fb.loading = false
  }
}

watch(visible, (v) => { if (!v) onClosed() })
</script>

<style>
.cf-td { padding: 2px 4px }
.cf-td__head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px }
.cf-td__sec { margin-top: 16px; padding-top: 12px; border-top: 1px dashed #e4e8ee }
.cf-td__sec:first-of-type { border-top: none }
.cf-td__sec-head {
  display: flex; align-items: center; gap: 8px; margin-bottom: 8px;
  font-size: 13px; font-weight: 600; color: #1b2430;
}
.cf-td__empty { font-size: 12px; padding: 6px 0 }
.cf-td__fb-list { list-style: none; margin: 0; padding: 0 }
.cf-td__fb { padding: 6px 0; border-bottom: 1px dotted #eef1f5 }
.cf-td__fb:last-child { border-bottom: none }
.cf-td__fb-meta { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #8a929e; flex-wrap: wrap }
/* 信箱跳转定位高亮：短暂闪一下帮用户认出目标（2.6s 后由 JS 移除） */
.is-cf-flash {
  animation: cf-flash 2.6s ease-out;
  border-radius: 4px;
}
@keyframes cf-flash {
  0%, 12%   { background: #fff3cd; box-shadow: 0 0 0 2px #f0c36d inset; }
  30%, 70%  { background: #fff9e6; box-shadow: 0 0 0 2px #f5d78e inset; }
  100%      { background: transparent;   box-shadow: 0 0 0 2px transparent inset; }
}
.cf-td__block { flex: 0 1 auto; min-width: 0; font-size: 12px; line-height: 1.4;
  color: #a8620a; background: #fdf6ec; border: 1px solid #f0dcc0;
  padding: 1px 7px; border-radius: 3px; overflow-wrap: anywhere }
.cf-td__fb-time { font-variant-numeric: tabular-nums }
/* 反馈行内的操作按钮（修改） */
.cf-td__fb-op { font-size: 12px; padding: 0 2px; margin-left: 4px }
/* 修订痕迹：改过反馈就常驻标注（禁悬浮提示），区分「谁交的」与「谁改的」 */
.cf-td__fb-edited { color: #a8620a; font-size: 12px }
/* 执行记录块：三要素分行。独立成块而不是拼进说明里，
   用户才能一眼看出「时间/系统/操作」各自是什么，改单项时也知道在改哪一项。 */
.cf-td__fb-declare {
  margin-top: 5px; padding: 5px 8px;
  background: #f4f8fd; border-left: 3px solid #7d9bcd; border-radius: 3px;
  font-size: 12px; color: #3d4654; display: flex; flex-wrap: wrap; gap: 4px 12px;
}
.cf-td__fb-declare-k { font-weight: 600; color: #1b4a8c }
.cf-td__fb-declare-i { word-break: break-all }
.cf-td__fb-text { font-size: 13px; color: #1b2430; margin-top: 3px; line-height: 1.6; white-space: pre-wrap }
.cf-td__actions { display: flex; align-items: center; gap: 10px; margin-top: 8px; flex-wrap: wrap }
.cf-td__hint { font-size: 12px }
</style>
