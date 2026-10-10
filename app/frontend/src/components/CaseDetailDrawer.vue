<template>
  <!-- 手机端占满整屏：46% 的宽度在手机上只剩一百多像素 -->
  <el-drawer
    v-model="visible"
    size="46%"
    destroy-on-close
  >
    <template #header>
      <div class="cf-drawer__head">
        <span>案件详情 · {{ detail.caseNo || '' }}</span>
        <!-- 重点关注：列表是主入口，但详情里也要能改（否则得退出去再点星）。
             星标是打在案件上的全局标记，只给管理层。 -->
        <template v-if="canManage && detail.id">
          <FocusStar :row="detail" />
          <span class="cf-muted" style="font-size: 12px">{{ detail.focus === 1 ? '重点关注中' : '未关注' }}</span>
        </template>
      </div>
    </template>

    <template v-if="detail.id">
      <!-- 手机上两列描述会挤成竖排的碎字，直接改单列 -->
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="案件名称" :span="2">{{ detail.name }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusMeta.type" size="small">{{ statusMeta.label }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="优先级">
          <span :style="{ color: priorityMeta.color, fontWeight: 600 }">{{ priorityMeta.label }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="案件类别">{{ detail.category || '-' }}</el-descriptions-item>
        <el-descriptions-item label="案卷类型">
          <el-tag v-if="detail.caseType" size="small" :type="(CASE_TYPE_META[detail.caseType] || {}).type || 'info'" effect="plain">
            {{ detail.caseTypeName || detail.caseType }}
          </el-tag>
          <span v-else class="cf-muted">未分类</span>
        </el-descriptions-item>
        <el-descriptions-item label="来源">{{ sourceLabel }}</el-descriptions-item>
        <el-descriptions-item label="案件编号">{{ detail.filingNo || '-' }}</el-descriptions-item>
        <!-- 强制措施（2026-10-09）：建案表单与盯办共用同一个字段，这里如实显示 -->
        <el-descriptions-item label="强制措施">
          <el-tag v-if="detail.caseMeasure && detail.caseMeasure !== 'NONE'" size="small"
            :type="(MEASURE_META[detail.caseMeasure] || {}).type || 'info'" effect="plain">
            {{ (MEASURE_META[detail.caseMeasure] || {}).label || detail.caseMeasure }}
          </el-tag>
          <span v-else class="cf-muted">未采取</span>
        </el-descriptions-item>
        <!-- 期限：节点叫什么 + 哪天 + 提前多久提醒 -->
        <el-descriptions-item :label="detail.deadlineLabel || '截止期限'">
          <span :class="dueClass">{{ dueText(detail) }}</span>
          <el-tag v-if="detail.reminding" type="warning" size="small" effect="dark" style="margin-left: 6px">
            提醒中
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="提前提醒">
          <span v-if="detail.remindDays">提前 {{ detail.remindDays }} 天</span>
          <span v-else class="cf-muted">不提醒</span>
        </el-descriptions-item>
        <el-descriptions-item label="嫌疑人">
          <b>{{ detail.suspects?.length || 0 }}</b> 人
        </el-descriptions-item>
        <el-descriptions-item label="待办进度">
          <span v-if="detail.todoTotal">
            <b>{{ detail.todoDone || 0 }}</b> / {{ detail.todoTotal }} 项
          </span>
          <span v-else class="cf-muted">未设置</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建人">{{ detail.createdByName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail.createdAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ detail.description || '-' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 案件待办（含领导意见）：提意见/定级/落实反馈都在这一个面板，避免同一事项两处展示 -->
      <CaseTodoPanel :case-id="detail.id" :anchor="anchor" style="margin-top: 12px" @changed="reload" />

      <div class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head">
          <span>嫌疑人（{{ detail.suspects?.length || 0 }} 人）</span>
          <el-button link type="primary" @click="openSuspect()">新增嫌疑人</el-button>
        </div>
        <div class="cf-tscroll">
          <el-table :data="detail.suspects || []" size="small">
            <el-table-column prop="name" label="姓名" width="90" />
            <el-table-column label="性别" width="60">
              <template #default="{ row }">{{ row.genderName || '-' }}</template>
            </el-table-column>
            <el-table-column prop="idCard" label="身份证号" min-width="170" show-overflow-tooltip />
            <el-table-column prop="phone" label="联系电话" width="120" />
            <el-table-column prop="address" label="住址" min-width="160" show-overflow-tooltip />
            <el-table-column label="操作" width="60" align="right">
              <template #default="{ row }">
                <el-button link type="danger" @click="removeSuspect(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty><span class="cf-muted">暂无嫌疑人信息</span></template>
          </el-table>
        </div>
      </div>

      <div class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head">
          <span>现任承办人</span>
        </div>
        <div class="cf-tscroll">
          <el-table :data="currentAssignees" size="small">
            <el-table-column label="角色" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.assignRole === 'OWNER' ? 'danger' : 'info'">
                  {{ row.assignRole === 'OWNER' ? '主办' : '协办' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="employeeName" label="姓名" width="90" />
            <el-table-column prop="pathName" label="归属链路" min-width="200" />
            <el-table-column prop="note" label="指派要求" min-width="140" />
          </el-table>
        </div>
      </div>

      <div class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head">
          <span>指派历史（改派留痕）</span>
          <span class="cf-muted">共 {{ detail.assignHistory?.length || 0 }} 条</span>
        </div>
        <div class="cf-tscroll">
          <el-table :data="detail.assignHistory || []" size="small" max-height="180">
            <el-table-column prop="employeeName" label="姓名" width="90" />
            <el-table-column label="角色" width="70">
              <template #default="{ row }">{{ row.assignRole === 'OWNER' ? '主办' : '协办' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <span :class="row.status === 'ACTIVE' ? 'cf-ok' : 'cf-muted'">
                  {{ row.status === 'ACTIVE' ? '现行' : '已改派' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="assignedAt" label="指派时间" width="160" />
          </el-table>
        </div>
      </div>

      <!-- 办理进度 = 全所操作留痕，仅管理层可见（后端 /logs/** 已同步拦截） -->
      <div v-if="canManage" class="cf-panel" style="margin-top: 12px">
        <!-- 办理进度默认收起：老案件动辄上百步，全部铺开会把详情抽屉撑得很长；
             需要追溯时点头部（或按钮）手动展开 -->
        <div class="cf-panel__head cf-progress__toggle" @click="progressExpanded = !progressExpanded">
          <span>办理进度</span>
          <span class="cf-muted">共 {{ progress.length }} 步</span>
          <span class="cf-spacer"></span>
          <el-button link type="primary" size="small">
            {{ progressExpanded ? '收起' : '展开查看' }}
          </el-button>
        </div>
        <el-collapse-transition>
          <div v-show="progressExpanded" style="padding: 14px 16px">
          <el-timeline v-if="progress.length">
            <el-timeline-item
              v-for="p in progress"
              :key="p.id"
              :timestamp="p.createdAtText"
              placement="top"
              :type="progressType(p.action)"
              :hollow="!!p.undone"
            >
              <div><b>{{ p.actionName }}</b>　{{ p.content }}</div>
              <div class="cf-muted">
                {{ p.operatorName }}<span v-if="p.undone">（此步已撤回）</span>
              </div>

              <!-- 批注（类似 Word 批注）：挂在进度下，展示批注人 / 批注时间 -->
              <div v-for="cmt in (commentsByLog[p.id] || [])" :key="cmt.id" class="cf-pcomment">
                <div class="cf-pcomment__head">
                  <span class="cf-pcomment__author">{{ cmt.creatorName }}</span>
                  <span class="cf-muted">{{ (cmt.createdAt || '').slice(0, 16) }} 批注</span>
                  <span class="cf-spacer"></span>
                  <template v-if="canManage">
                    <el-button link type="primary" size="small" @click="editComment(cmt)">编辑</el-button>
                    <el-button link type="danger" size="small" @click="removeComment(cmt)">删除</el-button>
                  </template>
                </div>
                <div class="cf-pcomment__body">{{ cmt.content }}</div>
                <div v-if="cmt.updaterName" class="cf-muted" style="font-size: 12px; margin-top: 2px">
                  最后编辑：{{ cmt.updaterName }} · {{ (cmt.updatedAt || '').slice(0, 16) }}
                </div>
              </div>

              <!-- 批注入口：仅管理层（管理员/领导）；办案人与普通用户只读 -->
              <el-button
                v-if="canManage && !p.undone"
                link type="primary" size="small" style="margin-top: 4px; padding: 0"
                @click="addComment(p)">
                {{ (commentsByLog[p.id] || []).length ? '追加批注' : '批注' }}
              </el-button>
            </el-timeline-item>
          </el-timeline>
          <span v-else class="cf-muted">暂无进度记录</span>
        </div>
        </el-collapse-transition>
      </div>
      <div class="cf-toolbar" style="margin-top: 14px">
        <el-button v-if="detail.status === 'ASSIGNED'" type="primary" @click="changeStatus('IN_PROGRESS')">
          开始处理
        </el-button>
        <el-button
          v-if="canManage && !['DONE', 'CANCELLED'].includes(detail.status)"
          type="success"
          @click="changeStatus('DONE')"
        >
          办结
        </el-button>
        <el-button
          v-if="canManage && !['DONE', 'CANCELLED'].includes(detail.status)"
          @click="changeStatus('CANCELLED')"
        >
          撤销
        </el-button>
        <span v-if="!canManage" class="cf-muted" style="font-size: 13px">办结 / 撤销为管理层权限</span>
        <span class="cf-spacer" />
        <el-button @click="visible = false">关闭</el-button>
      </div>
    </template>
  </el-drawer>

  <!-- 嫌疑人录入：手机端全屏，避免窄屏下表单折行错位 -->
  <el-dialog
    v-model="suspectVisible"
    title="新增嫌疑人"
    width="520px"
    destroy-on-close
    class="cf-dialog"
  >
    <el-form :model="suspectForm" label-width="92px">
      <el-form-item label="姓名" required>
        <el-input v-model="suspectForm.name" placeholder="必填" maxlength="64" />
      </el-form-item>
      <el-row :gutter="12">
        <el-col :span="12" :xs="24">
          <el-form-item label="性别">
            <el-select v-model="suspectForm.gender" clearable style="width: 100%">
              <el-option label="男" value="MALE" />
              <el-option label="女" value="FEMALE" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12" :xs="24">
          <el-form-item label="联系电话">
            <el-input v-model="suspectForm.phone" maxlength="32" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="身份证号">
        <el-input v-model="suspectForm.idCard" maxlength="32" />
      </el-form-item>
      <el-form-item label="住址">
        <el-input v-model="suspectForm.address" maxlength="255" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="suspectForm.remark" type="textarea" :rows="2" maxlength="512" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="suspectVisible = false">取消</el-button>
      <el-button type="primary" :loading="suspectSaving" @click="submitSuspect">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElCollapseTransition } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { caseApi, fileApi, suspectApi, logApi, watchApi } from '../api'
import CaseTodoPanel from './CaseTodoPanel.vue'
import FocusStar from './FocusStar.vue'
import { STATUS_META, PRIORITY_META, SOURCE_META, DUE_META, CASE_TYPE_META, MEASURE_META, dueText } from '../utils/format'
import { useUserStore } from '../store/user'
import { useEventStore } from '../store/events'


const props = defineProps({
  modelValue: { type: Boolean, default: false },
  caseId: { type: [Number, null], default: null },
  /**
   * 定位锚点（2026-10-08 信箱「查看案件」用）。
   * { todoId, questionId, subtaskId }——由路由 query 透传，逐层下传到待办面板去定位高亮。
   */
  anchor: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'done'])

const userStore = useUserStore()
// 办结 / 撤销 = 结案动作，仅所长 / 副所长 / 法制员可操作
const canManage = computed(() => userStore.isFullAccess)

const visible = ref(false)
const detail = ref({})

const statusMeta = computed(() => STATUS_META[detail.value.status] || { label: '-', type: 'info' })
const priorityMeta = computed(() => PRIORITY_META[detail.value.priority] || { label: '-', color: '#646a73' })
const sourceLabel = computed(() => SOURCE_META[detail.value.sourceType] || detail.value.sourceType || '-')
const dueClass = computed(() => {
  const meta = DUE_META[detail.value.dueLevel] || DUE_META.NONE
  return meta.color === 'danger' ? 'cf-danger' : meta.color === 'warn' ? 'cf-warn' : 'cf-muted'
})
const currentAssignees = computed(() => {
  const list = []
  if (detail.value.owner) list.push(detail.value.owner)
  list.push(...(detail.value.members || []))
  return list
})

watch(() => props.modelValue, async (v) => {
  visible.value = v
  if (v) progressExpanded.value = false   // 每次打开默认收起
  if (v && props.caseId) await reload()
})
watch(visible, (v) => emit('update:modelValue', v))

const reload = async () => {
  if (!props.caseId) return
  try {
    detail.value = await caseApi.detail(props.caseId)
  } catch (e) {
    // 案件已被删除，或当前登录人已经看不到它（转手后不再承办）：
    // 抽屉继续开着只会是一张空白卡，不如关掉并说清原因。
    // 信箱里留着的旧信件指到已删案件时，走的就是这条路径——
    // 不接住会变成控制台里一条没人管的 Promise 异常（2026-10-11 修）。
    detail.value = {}
    visible.value = false
    ElMessage.warning('该案件已被删除或无权查看')
    return
  }
  // 办理进度：仅管理层加载（普通员工面板已隐藏，接口也在后端拦截）
  if (canManage.value) {
    progress.value = await logApi.caseLogs(props.caseId)
  }
  // 批注随详情一并刷新（待办面板自己订阅 SSE 刷新，失败不阻塞主信息展示）
  try { comments.value = await watchApi.comments(props.caseId) } catch { /* 忽略 */ }
}

// ---- 全局监听：抽屉打开期间，其他入口推进本案件时进度时间线自动刷新 ----
const eventStore = useEventStore()
let progressTimer = null
let unsubscribe = null
const onCaseEvent = (e) => {
  if (!visible.value || !props.caseId) return
  if (e.module !== 'CASE' || String(e.targetId) !== String(props.caseId)) return
  // 1 秒内连续事件合并成一次刷新（批量指派/撤回等场景）
  if (progressTimer) return
  progressTimer = setTimeout(async () => {
    progressTimer = null
    if (!visible.value || !props.caseId) return
    const caseId = props.caseId
    try {
      await reload()
    } catch (err) { /* 案件可能已被删除，忽略 */ }
  }, 800)
}
onMounted(() => { unsubscribe = eventStore.subscribe(onCaseEvent) })
onBeforeUnmount(() => {
  clearTimeout(progressTimer)
  if (unsubscribe) unsubscribe()
})

// 办理进度时间线（操作日志按时间正序）；默认收起，展开才渲染
const progress = ref([])
const progressExpanded = ref(false)
const PROGRESS_TYPE = {
  CREATE: 'primary', UPDATE: 'info', ASSIGN: 'warning',
  STATUS: 'success', DELETE: 'danger', UNDO: 'info'
}
const progressType = (action) => PROGRESS_TYPE[action] || 'info'

// ---- 批注（功能1）：挂在进度时间线上，写操作仅管理层 ----
const comments = ref([])
const commentsByLog = computed(() => {
  const map = {}
  for (const c of comments.value) {
    (map[c.logId] = map[c.logId] || []).push(c)
  }
  return map
})

const addComment = async (logEntry) => {
  let content
  try {
    ({ value: content } = await ElMessageBox.prompt(
      `对进度「${logEntry.actionName}：${(logEntry.content || '').slice(0, 60)}」添加批注`,
      '添加批注', {
        inputType: 'textarea', inputPlaceholder: '批注内容，如：该步骤材料需补充完整后再推进',
        inputValidator: (v) => (v && v.trim()) ? true : '请填写批注内容',
        confirmButtonText: '提交批注', cancelButtonText: '取消'
      }))
  } catch { return }
  await watchApi.addComment(logEntry.id, content)
  ElMessage.success('批注已添加')
  await reload()
}

const editComment = async (cmt) => {
  let content
  try {
    ({ value: content } = await ElMessageBox.prompt('编辑批注内容', '编辑批注', {
      inputType: 'textarea', inputValue: cmt.content,
      inputValidator: (v) => (v && v.trim()) ? true : '请填写批注内容',
      confirmButtonText: '保存', cancelButtonText: '取消'
    }))
  } catch { return }
  await watchApi.updateComment(cmt.id, content)
  ElMessage.success('批注已更新')
  await reload()
}

const removeComment = async (cmt) => {
  try {
    await ElMessageBox.confirm(`确认删除这条批注？`, '删除批注', { type: 'warning' })
  } catch { return }
  await watchApi.removeComment(cmt.id)
  ElMessage.success('批注已删除')
  await reload()
}

// ---- 嫌疑人 ----
const suspectVisible = ref(false)
const suspectSaving = ref(false)
const emptySuspect = () => ({ name: '', gender: '', idCard: '', phone: '', address: '', remark: '' })
const suspectForm = ref(emptySuspect())

const openSuspect = () => {
  suspectForm.value = emptySuspect()
  suspectVisible.value = true
}

const submitSuspect = async () => {
  if (!suspectForm.value.name || !suspectForm.value.name.trim()) {
    ElMessage.warning('请填写嫌疑人姓名')
    return
  }
  suspectSaving.value = true
  try {
    await suspectApi.add(detail.value.id, { ...suspectForm.value })
    ElMessage.success('嫌疑人已录入')
    suspectVisible.value = false
    await reload()
    emit('done')
  } finally {
    suspectSaving.value = false
  }
}

const removeSuspect = async (row) => {
  await ElMessageBox.confirm(`确认删除嫌疑人「${row.name}」？`, '提示', { type: 'warning' })
  await suspectApi.remove(row.id)
  ElMessage.success('已删除')
  await reload()
  emit('done')
}
</script>

<style>
/* 办理进度折叠头：整行可点，热区大一点方便手抖的用户 */
.cf-progress__toggle { cursor: pointer; user-select: none }
.cf-progress__toggle:hover .cf-muted { color: #1b4a8c }

/* 批注卡（类似 Word 批注）：左竖线 + 浅底，挂在对应进度下方 */
.cf-pcomment {
  margin-top: 8px; padding: 8px 10px;
  background: #fbf7ec; border-left: 3px solid #d98a0b; border-radius: 3px;
}
.cf-pcomment__head { display: flex; align-items: center; gap: 8px; font-size: 12px }
.cf-pcomment__author { font-weight: 600; color: #1b2430 }
.cf-pcomment__body { font-size: 13px; color: #3d4654; margin-top: 3px; white-space: pre-wrap; word-break: break-all }
</style>
