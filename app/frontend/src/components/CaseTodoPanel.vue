<template>
  <div class="cf-panel cf-todo">
    <div class="cf-panel__head">
      <span>案件待办<template v-if="opinionCount">（含领导意见 {{ opinionCount }} 条）</template></span>
      <span class="cf-panel__head-tip" v-if="todos.length">
        已完成 {{ doneCount }} / {{ todos.length }} 项
      </span>
      <div class="cf-panel__head-actions">
        <!-- 一个入口：提意见=生成待办，不再分「提意见 / 添加待办」两个按钮 -->
        <el-button v-if="isAdmin" link type="primary" @click="openAdd">＋ 添加</el-button>
      </div>
    </div>

    <!-- 空状态：把"这里是什么、接下来干什么"一次讲清 -->
    <div v-if="!todos.length" class="cf-todo__empty">
      <div class="cf-todo__empty-badge">待办</div>
      <div class="cf-todo__empty-title">本案件还没有待办事项</div>
      <ol class="cf-todo__empty-steps">
        <li v-if="isAdmin">点右上角「＋ 添加」，一次可以连着写好几条（可设截止时间和 A/B/C 重要级）</li>
        <li>添加的事项就是办案人的待办，干完活提交反馈说明</li>
        <li>反馈过后勾选前面的方框，该事项即标记完成</li>
      </ol>
      <el-button v-if="isAdmin" type="primary" @click="openAdd">＋ 添加第一条</el-button>
      <div v-else class="cf-muted" style="margin-top: 6px">领导提出意见后，这里会自动出现待办</div>
    </div>

    <template v-else>
      <!-- 新手引导：三步用法，可关闭（本会话不再出现） -->
      <el-alert v-if="showGuide" type="info" class="cf-todo__guide" :closable="true" @close="dismissGuide">
        <template #title>
          三步完成待办：① 先办 <b>A 级</b>、<b>临期/逾期</b> 的 → ② 干完后点「详情」提交一条<b>反馈说明</b>
          → ③ 再勾选前面的方框标记完成。有子任务的，先把子任务全部勾完。
        </template>
      </el-alert>

      <!-- 实时提醒条：领导加/改/删意见时自动弹出，8 秒后自隐 -->
      <transition name="cf-fade">
        <div v-if="liveNotice" class="cf-todo__notice">
          <el-icon><Bell /></el-icon>
          <span class="cf-todo__notice-text">{{ liveNotice }}</span>
          <el-button link size="small" @click="liveNotice = ''">知道了</el-button>
        </div>
      </transition>

      <!-- 进度与意见分布：一眼看清整体进度和紧急程度 -->
      <div class="cf-todo__summary">
        <div class="cf-todo__progress">
          <el-progress
            :percentage="percent"
            :stroke-width="8"
            :show-text="false"
            :color="percent === 100 ? 'var(--cf-ok)' : 'var(--cf-primary)'"
          />
          <span class="cf-todo__progress-text">{{ doneCount }}/{{ todos.length }}</span>
        </div>
        <div v-if="opinionCount" class="cf-todo__opstat">
          <span class="cf-todo__opstat-label">意见：</span>
          <span v-for="s in urgencyStats" :key="s.key" class="cf-todo__opstat-item">
            <i class="cf-todo__dot" :style="{ background: s.color }"></i>{{ s.label }} {{ s.n }}
          </span>
        </div>
      </div>

      <!-- 卡片列表：一张卡一件事，点击卡片任意空白处即可看详情/反馈 -->
      <ul ref="listEl" class="cf-todo__cards">
        <li v-for="(t, i) in todos" :key="t.id" class="cf-todo__card" :data-cf-todoid="t.id"
          :class="[
            { 'is-done': t.status === 'DONE', 'is-dragging': dragId === t.id },
            t.status !== 'DONE' ? 'is-importance-' + displayImpOf(t) : ''
          ]">
          <div class="cf-todo__card-main">
            <!-- 拖拽把手：仅管理层、桌面端；手机端用「管理 ▾」里的上移/下移 -->
            <span v-if="isAdmin" class="cf-todo__drag">⋮⋮</span>
            <!-- 勾选框：完成的主开关。
                 「为什么勾不动」不用悬浮提示（会挡住旁边的操作按钮，用户明确要求去掉），
                 改成紧跟在卡片里的内联文字——一直可见，不挡任何东西。 -->
            <el-checkbox
              class="cf-check cf-todo__check"
              :class="{ 'cf-check--locked': t.status === 'DONE' && !isAdmin }"
              :model-value="t.status === 'DONE'"
              :disabled="!canToggle(t)"
              @change="(v) => toggle(t, v)"
            />

            <!-- 卡片主体：点击打开详情 -->
            <div class="cf-todo__card-body" @click="openDetail(t)">
              <div class="cf-todo__card-title">
                <span class="cf-todo__no">{{ i + 1 }}</span>
                <span class="cf-todo__name" :class="{ 'is-done-text': t.status === 'DONE' }">{{ t.content }}</span>
                <!-- 「为什么勾不动」：放在标题行内（flex 自适应收缩），
                     不放主行 checkbox 旁——那里是固定槽位，插入长文字会把整行挤乱（踩过） -->
                <span v-if="!canToggle(t) && t.status !== 'DONE'" class="cf-todo__block">
                  {{ blockReason(t) }}
                </span>
                <!-- 状态徽章：一眼分清 已完成 / 待反馈 / 进行中 / 未完成 -->
                <el-tag v-if="t.status === 'DONE'" size="small" type="success" effect="dark">已完成</el-tag>
                <template v-else-if="t.opinionId">
                  <el-tag size="small" effect="dark"
                    :type="(FEEDBACK_META[t.opinionFeedbackStatus] || {}).type || 'info'">
                    {{ (FEEDBACK_META[t.opinionFeedbackStatus] || {}).label || '待反馈' }}
                  </el-tag>
                  <el-tag size="small" effect="dark" :class="['cf-todo__importance-tag', 'is-' + displayImpOf(t).toLowerCase()]">
                    {{ displayImpOf(t) }} 级
                  </el-tag>
                  <el-tag v-if="t.opinionDeadline" size="small" effect="plain"
                    :type="URGENCY_META[urgencyOf(t)].type">
                    {{ URGENCY_META[urgencyOf(t)].label }}
                  </el-tag>
                </template>
                <el-tag v-else size="small" type="warning" effect="plain">待办</el-tag>
              </div>

              <div class="cf-todo__card-meta">
                <template v-if="t.status === 'DONE'">
                  <span class="cf-todo__meta-item">
                    <el-icon><User /></el-icon>{{ t.doneByName || '-' }}
                  </span>
                  <span class="cf-todo__meta-item">
                    <el-icon><Clock /></el-icon>{{ fmtTime(t.doneAt) }} 完成
                  </span>
                  <span v-if="t.remark" class="cf-todo__meta-item is-note">{{ t.remark }}</span>
                </template>
                <template v-else-if="t.opinionId">
                  <span class="cf-todo__meta-item">
                    <el-icon><User /></el-icon>{{ t.opinionCreatorName || '管理层' }} 提出
                  </span>
                  <span class="cf-todo__meta-item">
                    <el-icon><Clock /></el-icon>{{ (t.opinionCreatedAt || '').slice(5, 16) }}
                  </span>
                  <span v-if="t.opinionDeadline" class="cf-todo__meta-item"
                    :class="'is-' + urgencyOf(t).toLowerCase()">
                    <el-icon><AlarmClock /></el-icon>截止 {{ deadlineTextOf(t.opinionDeadline) }}
                  </span>
                </template>
                <template v-else>
                  <span v-if="t.deadline" class="cf-todo__meta-item" :class="'is-' + urgencyOfFn(t.deadline).toLowerCase()">
                    <el-icon><AlarmClock /></el-icon>截止 {{ deadlineTextOf(t.deadline) }}
                  </span>
                  <span class="cf-todo__meta-item is-hint">
                    <el-icon><InfoFilled /></el-icon>{{ blockReason(t) || '提交一条反馈说明后即可勾选完成' }}
                  </span>
                </template>

                <!-- 管理层调整意见截止时间与重要性（2026-10-18 紧凑化）：
                     原来单独占一行，把每张卡抬高 34px；并到信息行右侧后卡片只由内容撑高，
                     虚线框也去掉，不再像一块独立区域。 -->
                <div v-if="isAdmin && t.opinionId && t.status !== 'DONE'" class="cf-todo__meta-edit" @click.stop>
                  <span class="cf-todo__meta-edit-label">意见设置</span>
                  <el-date-picker :model-value="t.opinionDeadline || ''" type="datetime" size="small"
                    format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="设截止时间"
                    :clearable="true" :disabled="!!savingOpinionMetaIds[t.opinionId]" style="width: 158px"
                    @change="(v) => saveOpinionMeta(t, { deadline: v || '' })" />
                  <div class="cf-todo__grade" role="group" aria-label="待办重要程度">
                    <button v-for="grade in ['A', 'B', 'C']" :key="grade" type="button"
                      class="cf-todo__grade-button" :class="['is-' + grade.toLowerCase(), { 'is-active': displayImpOf(t) === grade }]"
                      :aria-pressed="displayImpOf(t) === grade"
                      @click.stop="saveOpinionMeta(t, { importance: grade })">{{ grade }}</button>
                  </div>
                </div>
                <div v-else-if="isAdmin && !t.opinionId && t.status !== 'DONE'" class="cf-todo__meta-edit" @click.stop>
                  <span class="cf-todo__meta-edit-label">完成时限</span>
                  <el-date-picker :model-value="t.deadline || ''" type="datetime" size="small"
                    format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="单独设完成时限"
                    clearable style="width: 158px" @change="(v) => saveCustomDeadline(t, v || '')" />
                </div>
              </div>

              <!-- 子任务入口：有子任务才显示，点开就地展开，不用进弹窗 -->
              <div v-if="t.subtaskTotal" class="cf-todo__sub-toggle"
                :class="{ 'is-open': subOpen[t.id] }" @click.stop="toggleSubs(t)">
                <el-icon><component :is="subOpen[t.id] ? ArrowUp : ArrowDown" /></el-icon>
                <span>子任务 {{ t.subtaskDone || 0 }}/{{ t.subtaskTotal }}</span>
                <span v-if="t.subtaskDone === t.subtaskTotal" class="cf-todo__sub-all">已全部完成</span>
                <span v-else-if="t.status !== 'DONE'" class="cf-todo__sub-tip">全部勾完才能勾选主任务</span>
              </div>

              <!-- 子任务就地展开：勾选/新增都在这里，不用进弹窗 -->
              <div v-if="subOpen[t.id]" class="cf-todo__subs" @click.stop>
                <div v-if="subData[t.id]?.loading" class="cf-muted" style="font-size: 12px; padding: 4px 0">
                  加载中…
                </div>
                <template v-else>
                  <div v-for="s in (subData[t.id]?.list || [])" :key="s.id" class="cf-todo__sub"
                    :class="{ 'is-done': s.status === 'DONE' }">
                    <el-checkbox class="cf-check"
                      :model-value="s.status === 'DONE'"
                      :disabled="!isAdmin || savingSubId === s.id"
                      @change="(v) => toggleSub(t, s, v)" />
                    <span class="cf-todo__sub-name">{{ s.content }}</span>
                    <span v-if="s.status === 'DONE' && s.doneAt" class="cf-todo__sub-done">
                      {{ fmtTime(s.doneAt) }}
                    </span>
                    <!-- 子任务详情：普通用户与管理层都能点开看这条子任务自己的反馈记录。
                         不点就只能看到这一行字，看不到它到底交了什么。 -->
                    <el-button link type="primary" size="small" class="cf-todo__sub-detail"
                      @click="openSubDetail(t, s)">详情</el-button>
                  </div>
                  <div v-if="!(subData[t.id]?.list || []).length" class="cf-todo__sub-none cf-muted">
                    还没有子任务，在下面添加第一条
                  </div>
                  <div class="cf-todo__sub-add">
                    <el-input v-model="newSub[t.id]" size="small" maxlength="200"
                      :placeholder="'补充细节工作，如：打印询问笔录并送达'"
                      :disabled="(subData[t.id]?.list || []).length >= 50"
                      @keyup.enter="addSub(t)" />
                    <el-button type="primary" size="small" plain :loading="addingSubId === t.id"
                      :disabled="!(newSub[t.id] || '').trim() || (subData[t.id]?.list || []).length >= 50"
                      @click="addSub(t)">
                      添加子任务
                    </el-button>
                  </div>
                </template>
              </div>

            </div>
          </div>

          <!-- 操作区（2026-10-18 紧凑化）：原来竖排在卡片右侧，4 个按钮把整卡撑到 150px 高；
               改成卡片底部横排一行，竖向只占 32px，一屏能多看两三条 -->
          <div class="cf-todo__card-actions" @click.stop>
              <el-button size="small" type="primary" plain class="cf-todo__act" @click="openDetail(t)">
                <el-icon><View /></el-icon>详情·子任务
              </el-button>
              <!-- 反馈入口按角色分（2026-10-06 需求）：
                   普通用户（承办人）=「提交反馈」：独立入口，不必先开浮窗，
                     已完成的不再显示（没什么可提交的了）；
                   管理层 =「查看反馈」：反馈是承办人的汇报，领导只查阅不代提交，
                     点开详情浮窗（反馈记录就在浮窗顶部），已完成的历史记录也能翻。
                   悬浮提示已去掉：会盖住旁边的操作按钮，改用按钮文字本身表意。 -->
              <el-button v-if="isAdmin" size="small" class="cf-todo__act" @click="openDetail(t)">
                <el-icon><View /></el-icon>查看反馈
              </el-button>
              <el-button v-else-if="canSubmit && t.status !== 'DONE'"
                size="small" type="warning" plain class="cf-todo__act"
                :loading="submittingId === t.id" @click="openSubmit(t)">
                <el-icon><ChatLineSquare /></el-icon>提交反馈
              </el-button>
              <el-button size="small" class="cf-todo__act" :disabled="t.status === 'DONE'" @click="openSubsAndFocus(t)">
                <el-icon><Plus /></el-icon>子任务
              </el-button>
              <el-dropdown v-if="isAdmin" trigger="click" @command="(cmd) => onAdminCmd(t, i, cmd)">
                <el-button size="small" class="cf-todo__act">
                  管理<el-icon class="cf-todo__act-more"><ArrowDown /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">编辑内容</el-dropdown-item>
                    <el-dropdown-item command="up" :disabled="i === 0">上移</el-dropdown-item>
                    <el-dropdown-item command="down" :disabled="i === todos.length - 1">下移</el-dropdown-item>
                    <el-dropdown-item command="remove" divided class="cf-todo__menu-danger">
                      {{ t.opinionId ? '移除意见（连带待办）' : '删除待办' }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
          </div>
        </li>
      </ul>
    </template>

    <!-- 提交反馈弹窗：列表页直接提交用。
         **纯手动**——openSubmit 只开弹窗不写库，真正落库要等用户
         在弹窗里点「提交反馈」（emit submit）才发生。 -->
    <FeedbackDialog
      v-model="fbDlg.visible"
      :title="fbDlg.title"
      :quote="fbDlg.quote"
      :default-status="fbDlg.status"
      :loading="fbDlg.loading"
      @submit="submitFeedback"
    />

    <!-- 任务详情浮窗：反馈记录 + 落实反馈弹窗（图二口径） -->
    <TodoDetailDialog ref="detailRef" @changed="onDetailChanged" />

    <!-- ============ 添加待办：一次可填多条（批量） ============
         原来是「一条一弹窗」，领导一次交代三件事就得开关三次。现在改成表格式批量录入：
         列头只说一次列义，行里只放控件，十几条也不会每行重复一遍标签、把弹窗撑爆。
         提交走 POST /watch/cases/{id}/opinions/batch，整批一个事务（要么全成、要么全不留）。
         表格本体由 TodoBatchEditor 提供——指派 / 建案两个入口共用同一份，
         用户要求三处「添加待办」逻辑完全一致，抽出来才不会改一处漏一处。 -->
    <el-dialog
      v-model="addVisible"
      title="添加待办"
      class="cf-btodo-dlg"
      width="min(880px, 94vw)"
      append-to-body
      destroy-on-close
    >
      <TodoBatchEditor
        ref="batchRef"
        v-model:rows="rows"
        scope="cf-btodo"
        :max="MAX_BATCH"
        :presets="presets"
        hint="一次可以连着写多条：填完一行按 <b>Enter</b> 直接写下一行。截止时间与重要程度都是选填，不填按「C 一般」处理。"
      />

      <template #footer>
        <div class="cf-bt__foot cf-btodo__foot">
          <div class="cf-bt__foot-left cf-btodo__foot-left">
            <el-button link type="primary" :disabled="rows.length >= MAX_BATCH" @click="batchRef?.addRow()">
              <el-icon><Plus /></el-icon>再添一条
            </el-button>
            <span class="cf-bt__count cf-btodo__count">
              已填 <b>{{ filledCount }}</b> 条<template v-if="rows.length >= MAX_BATCH">（一次最多 {{ MAX_BATCH }} 条）</template>
            </span>
          </div>
          <div>
            <el-button @click="addVisible = false">取消</el-button>
            <el-button type="primary" :loading="batchSaving" @click="submitBatch">
              保存{{ filledCount ? `（${filledCount} 条）` : '' }}
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <!-- 编辑待办：只改内容，仍是单条 —— 批量改内容没有意义，也不该顺手改掉已登记的截止时间 -->
    <el-dialog
      v-model="editVisible"
      title="编辑待办"
      width="460px"
      append-to-body
      destroy-on-close
    >
      <el-form label-width="72px">
        <el-form-item label="内容" required>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="写清要办的事，如：72小时内完成受害人走访"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 案件待办（合并领导意见）：一张卡一件事，点击卡片看详情，
 * 子任务就地展开勾选，管理动作收进「管理」菜单。
 *
 * <p>面向不熟悉电脑的使用者的三处引导：
 * <ul>
 *   <li>顶部「三步完成待办」提示条（可关闭，本会话不再出现）；</li>
 *   <li>勾选框灰住时悬停即说明原因，不用反复试；</li>
 *   <li>空状态直接给出"第一步点哪里"。</li>
 * </ul>
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bell, View, Plus, ArrowDown, ArrowUp, User, Clock, AlarmClock, InfoFilled, ChatLineSquare } from '@element-plus/icons-vue'
import Sortable from 'sortablejs'
import { todoApi, watchApi } from '../api'
import { useUserStore } from '../store/user'
import { useEventStore } from '../store/events'
import {
  FEEDBACK_STATUS_META as FEEDBACK_META,
  URGENCY_META,
  importanceOf,
  urgencyOf as urgencyOfFn,
  deadlineTextOf as deadlineTextOfFn
} from '../utils/format'
import TodoDetailDialog from './TodoDetailDialog.vue'
import FeedbackDialog from './FeedbackDialog.vue'
import TodoBatchEditor from './TodoBatchEditor.vue'

const props = defineProps({
  caseId: { type: [Number, String], required: true },
  /**
   * 定位锚点（2026-10-08 信箱「查看案件」用）。
   * 由路由 query 透传下来：{ todoId, subtaskId }。
   * 挂载后自动打开对应任务的详情浮窗并高亮目标；目标已删除则静默降级（仅开案件详情）。
   */
  anchor: { type: Object, default: null }
})
const emit = defineEmits(['changed'])

const userStore = useUserStore()
const isAdmin = computed(() => userStore.isFullAccess)

const urgencyOf = (t) => urgencyOfFn(t.opinionId ? t.opinionDeadline : t.deadline)
const deadlineTextOf = deadlineTextOfFn
/** 意见用 A/B/C；旧的独立待办用 KEY/MEDIUM/NORMAL，整卡统一换算为 A/B/C。 */
const impOf = (t) => t.opinionId
  ? importanceOf({ importance: t.opinionImportance })
  : ({ KEY: 'A', MEDIUM: 'B', NORMAL: 'C' }[t.importance] || importanceOf(t))
const displayImpOf = (t) => (t.opinionId && importanceDrafts[t.opinionId]) || impOf(t)

const todos = ref([])
const saving = ref(false)
const editVisible = ref(false)
const form = ref({ id: null, content: '' })
const detailRef = ref(null)

const doneCount = computed(() => todos.value.filter((t) => t.status === 'DONE').length)
const percent = computed(() =>
  todos.value.length ? Math.round((doneCount.value / todos.value.length) * 100) : 0
)
const opinionCount = computed(() => todos.value.filter((t) => t.opinionId).length)

/** 意见紧急性统计（已逾期/临期/正常），口径与卡片角标一致 */
const urgencyStats = computed(() => {
  const n = { OVERDUE: 0, URGENT: 0, NORMAL: 0 }
  for (const t of todos.value) {
    if (t.opinionId && t.status !== 'DONE') n[urgencyOf(t)]++
  }
  return [
    { key: 'OVERDUE', label: '已逾期', n: n.OVERDUE, color: '#c62a2a' },
    { key: 'URGENT', label: '临期', n: n.URGENT, color: '#d98a0b' },
    { key: 'NORMAL', label: '正常', n: n.NORMAL, color: '#8a929e' }
  ]
})

// ---- 新手引导（本会话内关闭后不再出现） ----
const GUIDE_KEY = 'cf_todo_guide_dismissed'
const guideDismissed = ref(false)
try { guideDismissed.value = sessionStorage.getItem(GUIDE_KEY) === '1' } catch (e) { /* 忽略 */ }
const showGuide = computed(() => !guideDismissed.value && todos.value.length > 0)
const dismissGuide = () => {
  guideDismissed.value = true
  try { sessionStorage.setItem(GUIDE_KEY, '1') } catch (e) { /* 忽略 */ }
}

const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '-')

/**
 * 勾选前置条件（与后端一致）：
 * 1) 主任务至少要有一条反馈说明
 * 2) 子任务全完成才能完成主任务
 */
const blockReason = (t) => {
  // 标记完成只归领导/管理员（2026-10-14 用户要求）：民警只管干活和交反馈，
  // 勾掉任务的最后一步由领导拍板（后端 done() 有同样的校验，这里是第一道门）
  if (!isAdmin.value) return '完成需由领导或管理员确认'
  const total = t.subtaskTotal || 0
  const dn = t.subtaskDone || 0
  if (total > 0 && dn < total) {
    return `还有 ${total - dn} 个子任务未完成，全部完成后才能勾选`
  }
  if (t.feedbackCount === 0 && !t.remark) {
    return '需先提交一条反馈说明，才能勾选完成'
  }
  return ''
}

const canToggle = (t) => {
  if (!isAdmin.value) return false
  if (t.status === 'DONE') return isAdmin.value   // 撤销完成仅管理层
  return !blockReason(t)
}

const load = async () => {
  // 父容器可能在案件详情就绪前挂载（如盯办抽屉先开壳再拉数据），caseId 未就绪时先空着
  if (!props.caseId) {
    todos.value = []
    return
  }
  todos.value = await todoApi.listOfCase(props.caseId)
  // DOM 更新后再挂拖拽，Sortable 才能正确量高度
  nextTick(setupSortable)
}

const loadAndConsumeAnchor = async () => {
  await load()
  if (props.caseId) await consumeAnchor()
}
onMounted(loadAndConsumeAnchor)
watch(() => props.caseId, loadAndConsumeAnchor)

/**
 * 消费定位锚点：打开对应任务的详情浮窗并高亮目标（2026-10-08 信箱跳转）。
 *
 * <p><b>降级策略</b>（锚点里的目标可能已被删除）：
 * <ul>
 *   <li>无 todoId → 不做任何事（调用方已开了案件详情，抽窗本身就是落点）；</li>
 *   <li>todoId 存在但查不到 → 提示一句「该任务已被删除」，停在案件详情；</li>
 *   <li>subtaskId 查不到 → 浮窗照常打开，只是不高亮（TodoDetailDialog 内处理）。</li>
 * </ul>
 */
const consumeAnchor = async () => {
  const a = props.anchor
  if (!a || !a.todoId) return
  const hit = (todos.value || []).find((t) => String(t.id) === String(a.todoId))
  if (!hit) {
    ElMessage.warning('该任务已被删除，已打开案件详情')
    return
  }
  if (a.highlightTodo) {
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 180))
    const card = [...document.querySelectorAll('.cf-todo__card')]
      .find((el) => el.dataset.cfTodoid === String(hit.id))
    if (card) {
      card.scrollIntoView({ block: 'center', behavior: 'smooth' })
      card.classList.remove('is-cf-todo-target')
      void card.offsetWidth
      card.classList.add('is-cf-todo-target')
      clearTimeout(taskFlashTimer)
      taskFlashTimer = setTimeout(() => card.classList.remove('is-cf-todo-target'), 2600)
    }
    return
  }
  await nextTick()
  detailRef.value?.openAt(hit.id, a)
}

// ---- 拖拽排序（仅管理层、桌面端；复用原来的 sortablejs 交互） ----
const listEl = ref(null)
let sortable = null
const dragId = ref(null)

const setupSortable = () => {
  sortable?.destroy()
  sortable = null
  if (!isAdmin.value || !listEl.value) return
  if (!listEl.value.children.length) return
  sortable = Sortable.create(listEl.value, {
    handle: '.cf-todo__drag',
    animation: 150,
    onStart: (e) => { dragId.value = todos.value[e.oldIndex]?.id ?? null },
    onEnd: async (e) => {
      dragId.value = null
      if (e.oldIndex === e.newIndex) return
      // 先按新下标重排本地数组，序号立刻重算（不等后端）
      const next = todos.value.slice()
      const [moved] = next.splice(e.oldIndex, 1)
      next.splice(e.newIndex, 0, moved)
      todos.value = next
      try {
        await todoApi.reorder(props.caseId, next.map((t) => t.id))
        ElMessage.success('顺序已保存')
        emit('changed')
      } catch (err) {
        // 保存失败回滚到服务端顺序
        await load()
        ElMessage.error('顺序保存失败，已恢复原顺序')
      }
    }
  })
}
onBeforeUnmount(() => sortable?.destroy())
watch(isAdmin, () => nextTick(setupSortable))

/* ============ 提交工作反馈（纯手动，2026-10-04） ============ */
/**
 * 提交入口只给普通用户；管理层不代承办人写反馈，改为「查看反馈」
 * （打开详情浮窗查阅反馈记录，2026-10-06 需求）。
 *
 * <p>本组件拿不到案件的 assignHistory（没接 detail prop），所以**不在前端判定
 * 「是否本案承办人」**，而是交给后端 addFeedback 里的 checkOperate：
 * 非承办人会被拦下并返回「只有案件承办人或管理层可以…」的中文原因。
 * 按钮对所有普通用户显示——非承办人点了会得到明确提示，
 * 比按钮凭空消失更好（后者会让人以为系统坏了）。
 */
const canSubmit = computed(() => !isAdmin.value)

const fbDlg = reactive({ visible: false, title: '', quote: '', status: '', loading: false })
const submittingId = ref(null)

/**
 * 打开提交反馈弹窗（主任务，纯反馈不完成）。
 *
 * <p><b>这里只开弹窗，不写任何数据。</b>真正落库要等用户在弹窗里主动点
 * 「提交反馈」按钮（FeedbackDialog emit submit）才会发生——
 * 这就是「纯手动」的全部含义：系统没有任何自动提交/自动流转路径。
 */
const openSubmit = (t) => {
  submittingId.value = t.id
  fbDlg.title = '提交工作反馈'
  fbDlg.quote = t.content || ''
  fbDlg.status = 'IN_PROGRESS'
  fbDlg.visible = true
}

/**
 * 用户在弹窗里主动点了「提交反馈」才走到这里——这是唯一的提交入口。
 * 纯反馈不改完成状态；勾选完成走 detailRef.openComplete 的弹窗（同样的汇报形式）。
 */
const submitFeedback = async ({ status, note, uploadTime, uploadPlatform, uploadFile }) => {
  if (!submittingId.value) return
  const t = todos.value.find((x) => x.id === submittingId.value)
  if (!t) return
  fbDlg.loading = true
  try {
    // 字段名必须是 content（后端 TodoSaveRequest），传 note 后端收不到——上一轮踩过
    // 执行记录三要素单独传（2026-10-08 拆列），不再拼成一句话
    await todoApi.addFeedback(t.id, { status, content: note, uploadTime, uploadPlatform, uploadFile })
    ElMessage.success('反馈已提交')
    fbDlg.visible = false
    await load()
    emit('changed')
  } catch (e) {
    // 失败时保留弹窗，让用户改内容重试，不用重新填一遍
  } finally {
    fbDlg.loading = false
    submittingId.value = null
  }
}

/** 打开详情浮窗（反馈记录 + 落实反馈弹窗） */
const openDetail = (t) => detailRef.value?.open(t.id)

/**
 * 打开某条子任务自己的详情浮窗（2026-10-08）。
 *
 * <p>普通用户与管理层都点得到——需求明确要求两端都能查看子任务详情。
 * 主任务标题一并传过去：切到子任务后浮窗里不再持有主任务对象，
 * 返回条上要显示「退回去是哪一个」。
 */
const openSubDetail = (t, s) => detailRef.value?.openSubDetail(s.id, t.content)

const toggle = async (t, checked) => {
  if (checked) {
    // 勾选 = 打开落实反馈弹窗（选「完成」才真正完成，完成情况要留落实状态）
    const why = blockReason(t)
    if (why) { ElMessage.warning(why); return }
    detailRef.value?.openComplete(t.id)
    return
  }
  if (!isAdmin.value) return
  try {
    await todoApi.reopen(t.id)
    ElMessage.success('已撤销完成')
    await load()
    emit('changed')
  } catch (e) {
    await load()   // 失败时以服务端为准回滚本地状态
  }
}

// ---- 子任务就地展开 ----
const subOpen = ref({})
const subData = ref({})          // todoId -> { loading, list }
const newSub = reactive({})      // todoId -> 输入内容
const savingSubId = ref(null)
const addingSubId = ref(null)

const fetchSubs = async (t) => {
  subData.value[t.id] = { loading: true, list: [] }
  try {
    const d = await todoApi.detail(t.id)
    subData.value[t.id] = { loading: false, list: d.subtasks || [] }
  } catch (e) {
    subData.value[t.id] = { loading: false, list: [] }
  }
}

const toggleSubs = async (t) => {
  if (subOpen.value[t.id]) {
    subOpen.value[t.id] = false
    return
  }
  subOpen.value[t.id] = true
  if (!subData.value[t.id]) await fetchSubs(t)
}

/** 「子任务」按钮：展开该卡的子任务区并把输入框聚焦 */
const openSubsAndFocus = async (t) => {
  subOpen.value[t.id] = true
  if (!subData.value[t.id]) await fetchSubs(t)
  nextTick(() => {
    const card = [...document.querySelectorAll('.cf-todo__card')].find(
      (el) => el.textContent.includes(t.content.slice(0, 12)))
    ;(card && card.querySelector('.cf-todo__sub-add input'))?.focus()
  })
}

const toggleSub = async (t, s, checked) => {
  if (checked) {
    // 勾选子任务 = 打开与主任务同一套的汇报弹窗（落实状态+说明+执行记录），
    // 选「完成」才真正勾上——不能在列表里直接勾掉（用户明确的交互要求）。
    detailRef.value?.openCompleteSub(t.id, s.id)
    return
  }
  savingSubId.value = s.id
  try {
    await todoApi.toggleSubtask(s.id, false)
    ElMessage.success('已撤销子任务完成')
    await fetchSubs(t)
    await load()
    emit('changed')
  } catch (e) {
    await fetchSubs(t)
  } finally {
    savingSubId.value = null
  }
}

const addSub = async (t) => {
  const content = (newSub[t.id] || '').trim()
  if (!content) return
  addingSubId.value = t.id
  try {
    await todoApi.addSubtask(t.id, content)
    newSub[t.id] = ''
    ElMessage.success('子任务已添加')
    await fetchSubs(t)
    await load()
    emit('changed')
  } finally {
    addingSubId.value = null
  }
}

/** 详情弹窗内变动后，若某卡的子任务区开着，同步刷新 */
const onDetailChanged = async () => {
  const openId = Object.keys(subOpen.value).find((k) => subOpen.value[k])
  if (openId) {
    const t = todos.value.find((x) => String(x.id) === String(openId))
    if (t) await fetchSubs(t)
  }
  await load()
  emit('changed')
}

// ---- 管理层维护 ----
/* ============ 批量添加待办（2026-10-11 建，2026-10-13 抽成公共组件） ============
 * 一次可以填多条，提交走 addOpinions（整批一个事务）。
 * 空行不发；到上限就停住「再添一条」并给出说明，不做无限追加。
 *
 * 行的新增/续行/删行都在 TodoBatchEditor 内部（Enter 续行、新行沿用上一行重要程度、
 * 上限 10、只剩一行时删除=清空），这里只管「打开时给一行」和「提交」。
 * ======================================================================== */
const MAX_BATCH = 10
const addVisible = ref(false)
const batchSaving = ref(false)
const rows = ref([])
const batchRef = ref(null)

const filledCount = computed(() => rows.value.filter((r) => (r.content || '').trim()).length)

/** 打开「添加待办」：先给一行并把光标放好，少点一次鼠标 */
const openAdd = () => {
  rows.value = [{ key: 1, content: '', deadline: '', importance: 'C' }]
  addVisible.value = true
  batchRef.value?.focusFirst()
  loadPresets()
}

/* ============ 常用待办（后台记忆，2026-10-11） ============
 * 服务端按「本人写过几次」排好序给回来，这里只管展示与点击。
 * 只在打开弹窗时拉一次——它是慢变量，没必要跟着每次输入刷新。 */
const presets = ref([])

const loadPresets = async () => {
  try {
    presets.value = await watchApi.todoPresets(8)
  } catch (e) {
    // 拉不到就当没有：快捷入口是锦上添花，不该因为它弹个错把弹窗搞得没法用
    presets.value = []
  }
}

/** Enter = 接着写下一行；已在最后一行就追加一行（到上限则原地不动） */
// ↑ 已移入 TodoBatchEditor（2026-10-13）：指派 / 建案两个新入口也要同一套续行行为，
//   留一份在这里就会出现"详情能连着写、指派不能"的不一致。

/**
 * 提交批量添加。
 *
 * <p>失败时不关弹窗、不清已填内容：整批事务意味着要么全进去要么一条没进，
 * 用户改正后直接再点一次即可，不用重填（这也是把校验放在提交前的原因）。
 */
const submitBatch = async () => {
  const items = batchRef.value
    ? batchRef.value.items()
    : rows.value
      .filter((r) => (r.content || '').trim())
      .map((r) => ({
        content: r.content.trim(),
        deadline: r.deadline || '',
        importance: r.importance || 'C'
      }))
  if (!items.length) {
    ElMessage.warning('请至少填写一条待办内容')
    return
  }
  batchSaving.value = true
  try {
    await watchApi.addOpinions(props.caseId, items)
    ElMessage.success(`已添加 ${items.length} 条待办`)
    addVisible.value = false
    rows.value = []
    loadPresets()   // 刚写进去的内容会进「常用待办」，下次打开就能一键添加
    await load()
    emit('changed')
  } finally {
    batchSaving.value = false
  }
}

const openEdit = (t) => {
  form.value = { id: t.id, content: t.content }
  editVisible.value = true
}

const submitEdit = async () => {
  if (!form.value.content || !form.value.content.trim()) {
    ElMessage.warning('请填写待办内容')
    return
  }
  saving.value = true
  try {
    await todoApi.update(form.value.id, { content: form.value.content })
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
    emit('changed')
  } finally {
    saving.value = false
  }
}

const removeTodo = async (t) => {
  if (t.opinionId) {
    // 意见派生条目：移除意见（后端会连带删掉派生待办，两边不会留孤儿）
    await ElMessageBox.confirm(
      `确认移除这条领导意见？「${t.content}」及其派生待办会一起删除。`,
      '移除意见', { type: 'warning', confirmButtonText: '确认移除', cancelButtonText: '取消' }
    )
    await watchApi.removeOpinion(t.opinionId)
    ElMessage.success('意见已移除')
  } else {
    await ElMessageBox.confirm(`确认删除待办「${t.content}」？`, '提示', { type: 'warning' })
    await todoApi.remove(t.id)
    ElMessage.success('已删除')
  }
  await load()
  emit('changed')
}

const move = async (index, delta) => {
  const list = [...todos.value]
  const target = index + delta
  if (target < 0 || target >= list.length) return
  ;[list[index], list[target]] = [list[target], list[index]]
  todos.value = list
  await todoApi.reorder(props.caseId, list.map((t) => t.id))
  await load()
  emit('changed')
}

/** 管理▾ 菜单命令分发 */
const onAdminCmd = (t, i, cmd) => {
  if (cmd === 'edit') openEdit(t)
  else if (cmd === 'up') move(i, -1)
  else if (cmd === 'down') move(i, 1)
  else if (cmd === 'remove') removeTodo(t)
}

// ---- 管理层就地调整意见元信息（截止时间/重要性，同步回意见与待办） ----
const savingOpinionMetaIds = reactive({})
const importanceDrafts = reactive({})
const queuedImportance = {}
const saveOpinionMeta = async (row, patch) => {
  const key = String(row.opinionId)
  if (patch.importance !== undefined) importanceDrafts[key] = patch.importance
  // 保存过程中仍允许改 A/B/C；把最后一次选择排队，避免连续点击被禁用态吞掉。
  if (savingOpinionMetaIds[key]) {
    if (patch.importance !== undefined) queuedImportance[key] = patch.importance
    return
  }
  savingOpinionMetaIds[key] = true
  try {
    let nextPatch = patch
    while (nextPatch) {
      const payload = {
        deadline: nextPatch.deadline !== undefined ? nextPatch.deadline : (row.opinionDeadline || ''),
        importance: nextPatch.importance !== undefined ? nextPatch.importance : (importanceDrafts[key] || impOf(row))
      }
      nextPatch = null
      await watchApi.updateOpinionMeta(row.opinionId, payload)
      await load()
      const pending = queuedImportance[key]
      delete queuedImportance[key]
      if (pending !== undefined && pending !== payload.importance) nextPatch = { importance: pending }
    }
    delete importanceDrafts[key]
    emit('changed')
  } catch (e) {
    delete queuedImportance[key]
    delete importanceDrafts[key]
    const message = e?.response?.data?.message || e?.response?.data?.msg || e?.message
    ElMessage.error(message ? '保存失败：' + message : '保存失败，请检查网络后重试')
  } finally {
    delete savingOpinionMetaIds[key]
  }
}
const saveCustomDeadline = async (row, deadline) => {
  await todoApi.updateDeadline(row.id, deadline)
  await load()
  emit('changed')
}

// ---- 实时推送：领导的意见操作即时同步（SSE） ----
const eventStore = useEventStore()
const liveNotice = ref('')
let noticeTimer = null
let reloadTimer = null

const OPINION_ACTIONS = ['OPINION_ADD', 'OPINION_UPDATE_CONTENT', 'OPINION_UPDATE_META', 'OPINION_REMOVE']
const TODO_ACTIONS = ['TODO_ADD', 'TODO_UPDATE', 'TODO_DELETE', 'TODO_DONE', 'TODO_REOPEN',
  'TODO_FEEDBACK', 'TODO_SUBTASK_ADD', 'TODO_SUBTASK_REOPEN']

const showNotice = (text) => {
  liveNotice.value = text
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { liveNotice.value = '' }, 8000)
}

const onCaseEvent = (e) => {
  if (!e || !props.caseId) return
  if (String(e.targetId) !== String(props.caseId)) return
  const mine = userStore.userInfo?.displayName || ''
  if (OPINION_ACTIONS.includes(e.action)) {
    if (!(e.operatorName && mine && e.operatorName === mine)) {
      showNotice(`${e.operatorName || '领导'} ${e.content || '更新了意见'}，请尽快查看`)
    }
    scheduleReload()
  } else if (TODO_ACTIONS.includes(e.action)) {
    scheduleReload()
  }
}

const scheduleReload = () => {
  clearTimeout(reloadTimer)
  reloadTimer = setTimeout(() => load(), 400)
}

let unsubscribe = null
let taskFlashTimer = null
onMounted(() => { unsubscribe = eventStore.subscribe(onCaseEvent) })
onBeforeUnmount(() => {
  if (unsubscribe) unsubscribe()
  clearTimeout(noticeTimer)
  clearTimeout(reloadTimer)
  clearTimeout(taskFlashTimer)
})

</script>

<style>
/* ============ 案件待办（合并领导意见）卡片化设计 ============ */

/* 空状态引导 */
.cf-todo__empty { text-align: center; padding: 26px 16px 22px }
.cf-todo__empty-badge {
  display: inline-block; padding: 3px 14px; border-radius: 999px;
  background: #eef3fa; color: #1b4a8c; font-weight: 700; font-size: 13px; margin-bottom: 10px;
}
.cf-todo__empty-title { font-size: 15px; font-weight: 700; color: #1b2430; margin-bottom: 8px }
.cf-todo__empty-steps {
  display: inline-block; text-align: left; margin: 0 auto 12px; padding-left: 20px;
  color: #5a6472; font-size: 13px; line-height: 1.9;
}

/* 新手引导条 */
.cf-todo__guide { margin: 0 16px 10px }
.cf-todo__guide .el-alert__title { font-size: 12.5px; line-height: 1.7 }
.cf-todo__guide b { color: #1b4a8c }

/* 进度 + 意见分布 */
.cf-todo__summary { padding: 2px 16px 8px }
.cf-todo__progress { display: flex; align-items: center; gap: 10px }
.cf-todo__progress .el-progress { flex: 1 }
.cf-todo__progress-text {
  flex: none; font-size: 13px; font-weight: 700; color: #1b4a8c;
  font-variant-numeric: tabular-nums;
}
.cf-todo__opstat {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  padding-top: 6px; font-size: 12px; color: #5a6472;
}
.cf-todo__opstat-label { color: #8a929e }
.cf-todo__opstat-item { display: inline-flex; align-items: center; gap: 5px }
.cf-todo__dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block }

/* 接龙式提意见区 */
.cf-todo__drafts { padding: 6px 16px 10px; border-bottom: 1px dashed #dfe4ea }
.cf-todo__draft { display: flex; align-items: center; gap: 10px; padding: 4px 0 }
.cf-todo__draft .el-input { flex: 1 }
.cf-todo__draft-add { display: flex; align-items: center; gap: 10px; padding: 8px 0 0 }
.cf-todo__plus {
  width: 28px !important; height: 28px !important; padding: 0 !important;
  font-size: 17px; font-weight: 600; color: #1b4a8c !important;
  border-color: #b9cbe6 !important; background: #f2f6fc !important;
}
.cf-todo__plus:hover { background: #e3edfa !important }
.cf-todo__no {
  flex: none; width: 22px; height: 22px; line-height: 22px; text-align: center;
  background: #eef3fa; color: #1b4a8c; border-radius: 50%;
  font-size: 12px; font-weight: 700; margin-top: 1px;
}

/* 卡片列表 */
.cf-todo__cards { list-style: none; margin: 4px 0 0; padding: 0 12px 12px }
.cf-todo__card {
  border: 1px solid #e4e8ee; border-left: 4px solid #c9d4e3; border-radius: 8px;
  background: #fff; margin-top: 10px; transition: box-shadow .15s, border-color .15s;
}
.cf-todo__card:hover {
  box-shadow: 0 2px 10px rgba(18, 41, 74, .08);
  border-top-color: #b9cbe6; border-right-color: #b9cbe6; border-bottom-color: #b9cbe6;
}
/* 整张主任务卡片按 A/B/C 重要程度着色；临期/逾期由截止时间徽标单独提示。 */
.cf-todo__card.is-importance-A { border-left-color: #c62a2a; background: #ffe2e2 }
.cf-todo__card.is-importance-B { border-left-color: #e0ad16; background: #fff0b3 }
.cf-todo__card.is-importance-C { border-left-color: #cfd4dc; background: #fff }
.cf-todo__card.is-done { border-left-color: var(--cf-ok, #1e8e58); background: #dff3e5; opacity: .96 }
.cf-todo__card.is-cf-todo-target { animation: cf-todo-target-flash 2.6s ease-out }
@keyframes cf-todo-target-flash {
  0%, 35%, 70% { background-color: #fff0a8; box-shadow: 0 0 0 4px rgba(200, 164, 92, .42) }
  18%, 53%, 100% { background-color: #fff; box-shadow: 0 0 0 0 rgba(200, 164, 92, 0) }
}
/* 拖拽把手：仅管理层桌面端渲染；竖排双点更像"可抓握" */
.cf-todo__drag {
  flex: none; align-self: center; cursor: grab; user-select: none;
  color: #b9c6d8; font-size: 13px; letter-spacing: -1px; line-height: 1;
  padding: 6px 0 6px 2px;
}
.cf-todo__drag:hover { color: #1b4a8c }
.cf-todo__drag:active { cursor: grabbing }
.cf-todo__card.is-dragging { opacity: .5 }
.cf-todo__card-main { display: flex; gap: 10px; padding: 10px 12px 4px 12px }
/* 勾选框尺寸/配色统一在 index.css 的 .cf-check；这里只管与序号圆点的对齐。
   Element 的 .el-checkbox 盒子高 32px（行高撑的），方框落在盒子中下部，
   于是比并排的 22px 序号圆点低 4~5px。这里把盒子也压到 22px 并顶对齐，
   让勾与序号落在同一条水平线上（实测 diff 0）。 */
.cf-todo__check {
  align-self: flex-start;
  height: 22px;
  margin-top: 1px;
}

.cf-todo__card-body { flex: 1; min-width: 0; cursor: pointer }
.cf-todo__card-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap }
.cf-todo__name { font-size: 14px; font-weight: 600; color: #1b2430; word-break: break-all }
.cf-todo__name.is-done-text { color: #8a929e; text-decoration: line-through; font-weight: 500 }

.cf-todo__card-meta {
  display: flex; align-items: center; gap: 4px 14px; flex-wrap: wrap;
  margin-top: 5px; font-size: 12px; color: #5a6472;
}
.cf-todo__meta-item { display: inline-flex; align-items: center; gap: 4px }
.cf-todo__meta-item .el-icon { font-size: 13px; color: #8a929e }
.cf-todo__meta-item.is-OVERDUE, .cf-todo__meta-item.is-overdue { color: #c62a2a; font-weight: 600 }
.cf-todo__meta-item.is-URGENT, .cf-todo__meta-item.is-urgent { color: #d98a0b; font-weight: 600 }
.cf-todo__meta-item.is-note {
  color: #3d4654; background: #f7f9fc; border-radius: 3px; padding: 1px 8px;
  max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cf-todo__meta-item.is-hint { color: #8a929e }

/* 子任务开合与列表 */
.cf-todo__sub-toggle {
  display: inline-flex; align-items: center; gap: 6px; margin-top: 7px;
  font-size: 12px; color: #1b4a8c; background: #eef3fa; border-radius: 999px;
  padding: 3px 12px; cursor: pointer; user-select: none;
}
.cf-todo__sub-toggle:hover { background: #e3edfa }
.cf-todo__sub-toggle .el-icon { font-size: 12px }
.cf-todo__sub-all { color: var(--cf-ok, #1e8e58); font-weight: 600 }
.cf-todo__sub-tip { color: #8a929e }
.cf-todo__subs {
  margin-top: 8px; padding: 8px 10px; background: rgba(255, 255, 255, .28);
  border: 1px dashed #d6dee9; border-radius: 6px;
}
.cf-todo__sub { display: flex; align-items: center; gap: 8px; padding: 4px 0; font-size: 13px }
.cf-todo__sub.is-done .cf-todo__sub-name { color: #8a929e; text-decoration: line-through }
.cf-todo__sub-name { flex: 1; min-width: 0; word-break: break-all; color: #1b2430 }
.cf-todo__sub-done { flex: none; font-size: 12px; color: #8a929e }
/* 子任务「详情」入口：与勾选框分开，点了只看反馈不改状态 */
.cf-todo__sub-detail { flex: none; font-size: 12px; padding: 0 2px }
.cf-todo__sub-none { font-size: 12px; padding: 2px 0 }
.cf-todo__sub-add { display: flex; gap: 8px; margin-top: 6px }
.cf-todo__sub-add .el-input { flex: 1 }

/* 管理层意见设置（2026-10-18 紧凑化）：并进信息行、靠右对齐。
   原来是独立一行（虚线框 + 34px 高），把每张卡白白抬高一段。 */
.cf-todo__meta-edit {
  display: inline-flex; align-items: center; gap: 6px; margin-left: auto;
}
/* 「为什么勾不动」的内联说明：放在标题行内，常驻可见不悬浮。
   位置在 .cf-todo__card-title（flex 行）里，需允许收缩换行，
   不能像固定槽位那样占死宽度——放主行 checkbox 旁曾把整行挤乱（踩过）。 */
.cf-todo__block {
  flex: 0 1 auto; min-width: 0; font-size: 12px; line-height: 1.4;
  color: #a8620a; background: #fdf6ec; border: 1px solid #f0dcc0;
  padding: 1px 7px; border-radius: 3px;
  max-width: 100%; overflow-wrap: anywhere;
}
.cf-todo__meta-edit-label { font-size: 12px; color: #8a929e; flex: none }

.cf-todo__grade { display: inline-flex; align-items: stretch }
.cf-todo__grade-button {
  min-width: 30px; height: 22px; border: 1px solid #dcdfe6; border-right: 0;
  background: #fff; color: #606266; cursor: pointer; font-size: 12px;
}
.cf-todo__grade-button:first-child { border-radius: 4px 0 0 4px }
.cf-todo__grade-button:last-child { border-right: 1px solid #dcdfe6; border-radius: 0 4px 4px 0 }
.cf-todo__grade-button.is-a.is-active { background: #c62a2a; border-color: #c62a2a; color: #fff }
.cf-todo__grade-button.is-b.is-active { background: #f4c542; border-color: #f4c542; color: #303133 }
.cf-todo__grade-button.is-c.is-active { background: #fff; border-color: #9da6b3; color: #303133 }
.cf-todo__grade-button:focus-visible { outline: 2px solid #409eff; outline-offset: 2px; z-index: 1 }

/* 操作区（2026-10-18 紧凑化）：卡片底部一行横排。
   竖排在右侧时 4 个按钮把每张卡撑到约 150px，一屏只能看三条；
   横排后竖向只占 26px，卡片高度由内容决定。 */
.cf-todo__card-actions {
  flex: none; display: flex; flex-direction: row; align-items: center;
  justify-content: flex-end; gap: 6px; flex-wrap: wrap;
  padding: 0 12px 8px;
}
.cf-todo__act {
  width: auto; height: 26px; margin: 0 !important;
  padding: 0 8px !important; font-size: 12px;
}
.cf-todo__act .el-icon { margin-right: 3px }
.cf-todo__act-more { margin-left: 2px !important; margin-right: 0 !important }
.cf-todo__menu-danger { color: #c62a2a }

/* ============ 添加待办：批量录入弹窗 ============
   表格本体的样式（.cf-bt__*）已随编辑器本体搬到 TodoBatchEditor.vue，
   三处共用一份，避免"改了一处忘了另一处"。
   本面板特有的只剩下面这条实时提醒条。 */

/* 实时提醒条 */
.cf-todo__notice {
  display: flex; align-items: center; gap: 8px;
  margin: 6px 16px; padding: 8px 12px; border-radius: 4px;
  border-left: 4px solid var(--cf-gold, #c8a45c);
  background: #12294a; color: #fff; font-size: 13px;
}
.cf-todo__notice-text { flex: 1; line-height: 1.5 }
.cf-todo__notice .el-button { color: #c8a45c !important }
.cf-todo__notice .el-button:hover { color: #e8c87c !important }

.cf-fade-enter-active, .cf-fade-leave-active { transition: opacity .25s }
.cf-fade-enter-from, .cf-fade-leave-to { opacity: 0 }
</style>
