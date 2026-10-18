<template>
  <!-- 手机端整屏弹窗：这么宽的表单塞进 375px 会到处折行，全屏反而更好填。
       宽度用 min()：900px 是「三列并排都不截断」的最小值（见 H 组回归断言），
       再由 94vw 兜住窄屏，不至于在小屏上溢出。 -->
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑案件' : '新建案件'"
    width="min(960px, 94vw)"
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

      <!-- 第一行三列（2026-10-12 重排）：原先 12/6/6，来源类型只拿到 ~80px，
           「手工录入」被截成「手...」。按"控件需要多宽"重新分配：
           案件分类的占位文案最长（必选：先选大类…），来源类型次之（手工录入 4 字），
           优先级选项只有两字，可以最窄。 -->
      <el-row :gutter="12">
        <el-col :span="10" :xs="24">
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
        <el-col :span="8" :xs="24">
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
        <el-col :span="10" :xs="24">
          <el-form-item label="案件编号">
            <el-input v-model="form.filingNo" placeholder="案件编号（选填）" maxlength="64" />
          </el-form-item>
        </el-col>
        <!-- 报案时间（2026-10-18）：手工录入的警情通常还没受案，列表「报案/受案时间」列
             显示的就是这个时间（已受案的案件以受案时间为准）。留空 = 保存时按当前时间。 -->
        <el-col v-if="!isEdit" :span="8" :xs="24">
          <el-form-item label="报案时间">
            <el-date-picker v-model="form.reportTime" type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss" placeholder="默认当前时间" clearable
              style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="6" :xs="24">
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

<!-- 重要时间节点（2026-10-13）：一份案件不止一个关键时间点。
           原先只有一个「时间节点 + 期限日期 + 提前提醒」三件套，
           实际办案要同时盯受案、立案、拘留期限届满、补充侦查期限…装不下。
           表格栅格录入：末行 Enter 续行、点「+ 添加」加行、逐行删除。
           **只新建时可填**——已建案件走详情页的时间节点面板增删改（有单条撤回）。 -->
      <div v-if="!isEdit" class="cf-form-block">
        <div class="cf-form-block-head">
          <span>重要时间节点<span class="cf-muted">（选填，可多个）</span></span>
          <el-button link type="primary" @click="addNodeRow">+ 添加节点</el-button>
        </div>
        <div v-if="!nodeRows.length" class="cf-muted cf-form-block-empty">
          暂无节点。未设置期限的案件在列表里显示「未设期限」，也不进到期提醒。
        </div>
        <div v-for="(row, i) in nodeRows" :key="row._k" class="cf-form-node-row"
          @keydown.enter.prevent="addNodeRow">
          <el-select v-model="row.nodeKey" placeholder="节点类型" style="width: 210px">
            <el-option v-for="p in TIME_NODE_PRESETS" :key="p.value"
              :label="p.label" :value="p.value" />
          </el-select>
          <el-input v-if="!row.nodeKey" v-model="row.nodeName" placeholder="节点名称，如：移送起诉期限"
            maxlength="64" style="width: 220px" />
          <el-date-picker v-model="row.day" type="date" value-format="YYYY-MM-DD"
            placeholder="选择日期" style="width: 150px" />
          <el-select v-model="row.remindDays" placeholder="不提醒" clearable style="width: 130px">
            <el-option v-for="d in REMIND_DAY_PRESETS" :key="d" :label="`提前 ${d} 天`" :value="d" />
          </el-select>
          <el-button link type="danger" class="cf-form-node-del" @click="removeNodeRow(i)">删除</el-button>
        </div>
        <div class="cf-form-tip">
          列表「截止期限」与到期提醒只认<b>最近的一个</b>节点，其余节点在案件详情里查看。
        </div>
      </div>

      <!-- 嫌疑人（2026-10-13 建案时一并录入，选填）。
           后端建案后可用详情页面板逐条增删改；这里一次录完省得建完案再补。 -->
      <div v-if="!isEdit" class="cf-form-block">
        <div class="cf-form-block-head">
          <span>嫌疑人<span class="cf-muted">（选填，可多个）</span></span>
          <el-button link type="primary" @click="addSuspectRow">+ 添加嫌疑人</el-button>
        </div>
        <div v-if="!suspectRows.length" class="cf-muted cf-form-block-empty">
          暂无嫌疑人。可在建案后于案件详情里补充。
        </div>
        <div v-for="(row, i) in suspectRows" :key="row._k" class="cf-form-suspect-row">
          <el-input v-model="row.name" placeholder="姓名（必填）" maxlength="64" style="width: 140px" />
          <el-select v-model="row.gender" placeholder="性别" clearable style="width: 92px">
            <el-option label="男" value="MALE" />
            <el-option label="女" value="FEMALE" />
          </el-select>
          <el-input v-model="row.idCard" placeholder="身份证号" maxlength="32" style="width: 180px" />
          <el-input v-model="row.phone" placeholder="联系电话" maxlength="32" style="width: 140px" />
          <el-input v-model="row.address" placeholder="住址（选填）" maxlength="255" style="width: 200px" />
          <el-button link type="danger" @click="removeSuspectRow(i)">删除</el-button>
        </div>
      </div>

      <!-- 待办事项（2026-10-13 建案时一并录入，选填）。
           用户要求：建案时可以自行决定是否添加待办，且录入体验与案件详情
           「添加待办」完全一致——同一个 TodoBatchEditor、同一套 Enter 续行 /
           截止时间 / A-B-C 重要程度 / 常用待办，提交后落库路径也一致
           （写 case_leader_opinion 再派生 case_todo，见 OpinionService.addDraftsQuietly）。

           为什么要"建案时就能填"：立案当场就知道要办什么，等建完案再切到详情页
           补一遍，等于让人把同一批话打两遍。表格默认折叠、空着不占空间，
           不填就完全不传（建案不带待办是完全合法的）。 -->
      <div v-if="!isEdit" class="cf-form-block cf-form-todo">
        <div class="cf-form-block-head">
          <span>待办事项<span class="cf-muted">（选填，可多条）</span></span>
          <el-button v-if="!todoRows.length" link type="primary" @click="openTodo">
            + 添加待办
          </el-button>
        </div>

        <div v-if="!todoRows.length" class="cf-muted cf-form-block-empty">
          暂无待办。不填也可以——建案后到<b>案件详情 → 案件待办</b>随时添加。
        </div>

        <template v-else>
          <TodoBatchEditor
            ref="todoRef"
            v-model:rows="todoRows"
            scope="cf-form-todo"
            preset-mode="fill"
            :max="MAX_TODO_BATCH"
            :presets="todoPresets"
            :preset-saving="presetSaving"
            hint="填完一行按 <b>Enter</b> 直接写下一行，最多 10 条。截止时间与重要程度都是选填，不填按「C 一般」处理。"
          />
          <div class="cf-bt__foot cf-form-todo__foot">
            <el-button link type="primary" :disabled="todoRows.length >= MAX_TODO_BATCH" @click="todoRef?.addRow()">
              <el-icon><Plus /></el-icon>再添一条
            </el-button>
            <span class="cf-bt__count cf-form-todo__count">
              已填 <b>{{ filledTodoCount }}</b> 条<template v-if="todoRows.length >= MAX_TODO_BATCH">（一次最多 {{ MAX_TODO_BATCH }} 条）</template>
            </span>
          </div>
          <div class="cf-form-tip">
            写下的每一条都会成为承办人的待办，<b>标记完成前必须先提交反馈说明</b>。
            建案后可在案件详情里逐条增删改。
          </div>
        </template>
      </div>

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
import { Plus } from '@element-plus/icons-vue'
import EmployeePicker from './EmployeePicker.vue'
import TodoBatchEditor from './TodoBatchEditor.vue'
import { caseApi, watchApi } from '../api'
import { useCategoryStore } from '../store/category'
import {
  MEASURE_CHOICES, REMIND_DAY_PRESETS, TIME_NODE_PRESETS
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
// 注意：这一块必须写在节点行模型（emptyNodeRow / addNodeRow）之前——
// addNodeRow 里会调 defaultRemindDays()，而 `const` 声明存在暂时性死区，
// 写在后面会报 "Cannot access 'readUsed' before initialization"。
// 2026-10-13：原先是 emptyForm() 在调，改为节点表后调用点变成 addNodeRow。
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
  // 报案时间（2026-10-18）：仅新建时填（编辑改时间走详情页的「时间节点」面板）。
  // 留空 = 后端按保存时刻兜底，列表「报案/受案时间」列读的就是它。
  reportTime: '',
  // 强制措施：'NONE'/空 = 未采取；具体取值见 MEASURE_CHOICES
  caseMeasure: 'NONE',
  description: '',
  ownerId: null,
  memberIds: [],
  assignNote: ''
})
const form = ref(emptyForm())
const isEdit = computed(() => props.caseId != null)

// ---- 重要时间节点（2026-10-13）----
// 行模型：_k 是前端唯一键，后端不认它，提交前剔掉。
let nodeSeq = 0
const nodeRows = ref([])
const emptyNodeRow = () => ({ _k: ++nodeSeq, nodeKey: '', nodeName: '', day: '', remindDays: null })
const addNodeRow = () => {
  // 新行沿用上一行的提醒档位：填提醒天数的动作通常是连续的几行
  const prev = nodeRows.value[nodeRows.value.length - 1]
  nodeRows.value.push({ ...emptyNodeRow(), remindDays: prev ? prev.remindDays : defaultRemindDays() })
}
const removeNodeRow = (i) => { nodeRows.value.splice(i, 1) }

/** 记住本次用过的提醒档位，下次建案直接给到（"根据用户的常用选择"） */
const recordRemindDays = (d) => {
  if (d == null) return
  usedDays.value = [...readUsed(), d].slice(-20)
  localStorage.setItem(REMIND_USED_KEY, JSON.stringify(usedDays.value))
}

/** 把行模型转成后端要的形状：日期补 23:59:59、自定义名兜底、剔掉前端键 */
const buildTimeNodes = () => nodeRows.value
  .filter((r) => r.day)                       // 没选日期的半行跳过，不打断建案
  .map((r) => ({
    nodeKey: r.nodeKey || null,
    nodeName: r.nodeKey ? null : (r.nodeName || '').trim(),
    nodeTime: `${r.day} 23:59:59`,
    remindDays: r.remindDays || null
  }))

// ---- 嫌疑人（2026-10-13 建案时一并录入）----
let suspectSeq = 0
const suspectRows = ref([])
const addSuspectRow = () => suspectRows.value.push({
  _k: ++suspectSeq, name: '', gender: '', idCard: '', phone: '', address: ''
})
const removeSuspectRow = (i) => { suspectRows.value.splice(i, 1) }

/** 只取填了姓名的行——后端也这么收，但前端先滤一遍，少传垃圾 */
const buildSuspects = () => suspectRows.value
  .filter((r) => (r.name || '').trim())
  .map((r) => ({
    name: (r.name || '').trim(),
    gender: r.gender || null,
    idCard: (r.idCard || '').trim() || null,
    phone: (r.phone || '').trim() || null,
    address: (r.address || '').trim() || null
  }))

// ---- 待办（2026-10-13 建案时可选录入）----
// 与案件详情「添加待办」共用 TodoBatchEditor，所以上限与后端 MAX_BATCH 一致（10）。
// 建案与详情两处各存一份上限是这类"魔法数字"的经典漂移方式，改动必须同步。
const MAX_TODO_BATCH = 10
const todoRef = ref(null)
const todoRows = ref([])
const todoPresets = ref([])
const presetSaving = ref(false)

const filledTodoCount = computed(() => todoRows.value.filter((r) => (r.content || '').trim()).length)

/** 点「+ 添加待办」才展开表格——保持"要填才占地方"，建案表单本来就很长 */
const openTodo = async () => {
  todoRows.value = [{ key: 1, content: '', deadline: '', importance: 'C' }]
  await todoRef.value?.focusFirst()
  loadTodoPresets()
}

const loadTodoPresets = async () => {
  try {
    todoPresets.value = await watchApi.todoPresets(8)
  } catch (e) {
    todoPresets.value = []   // 拉不到就当没有：快捷入口是锦上添花
  }
}

/** 提交用：只取填了内容的行（空行跳过，不打断建案） */
const buildTodos = () => (todoRef.value
  ? todoRef.value.items()
  : todoRows.value
    .filter((r) => (r.content || '').trim())
    .map((r) => ({
      content: (r.content || '').trim(),
      deadline: r.deadline || '',
      importance: r.importance || 'C'
    })))

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
  nodeRows.value = []
  suspectRows.value = []
  todoRows.value = []
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
    // 报案时间留空就不传：空串反序列化成 LocalDateTime 会直接报错，
    // 后端对 null 的处理是「按保存时刻兜底」，正是我们要的默认行为
    if (!payload.reportTime) delete payload.reportTime
    if (!assignNow.value) {
      payload.ownerId = null
      payload.memberIds = []
    }
    if (props.caseId) {
      // 编辑：期限与嫌疑人一律不动。
      // 时间节点改成独立面板管理（有单条快照与撤回），这里再提交会覆盖。
      // 同时把期限三列显式清空——节点表已是唯一真源，
      // 留着旧值会让「列表显示的期限」与「节点面板里的节点」各说各话。
      payload.deadline = null
      payload.deadlineLabel = null
      payload.remindDays = null
      payload.clearDeadline = true
      await caseApi.update(props.caseId, payload)
    } else {
      // 新建：把节点、嫌疑人、待办随案件一并落库
      const nodes = buildTimeNodes()
      // 记住这次选的提醒档位，下次建案直接给到（"根据用户的常用选择"）
      nodeRows.value.forEach((r) => recordRemindDays(r.remindDays))
      // 待办：没点「+ 添加待办」就是空数组，后端直接跳过（建案不带待办合法）
      const todos = buildTodos()
      if (todos.length > MAX_TODO_BATCH) {
        ElMessage.warning(`待办一次最多 ${MAX_TODO_BATCH} 条，请删掉多余的再提交`)
        return
      }
      await caseApi.create({
        ...payload,
        timeNodes: nodes,
        suspects: buildSuspects(),
        todos
      })
      if (todos.length) {
        ElMessage.success(`案件已创建，并生成 ${todos.length} 条待办`)
      }
    }
    ElMessage.success(props.caseId ? '已保存' : '案件已创建')
    visible.value = false
    emit('done')
  } finally {
    loading.value = false
  }
}
</script>
