<template>
  <div class="cf-table-wrap" :style="{ minHeight: `${wrapMin}px` }">
    <el-table
      v-loading="loading"
      :data="rows"
      size="small"
      :height="height"
      :row-class-name="({ row }) => rowClassOf(row)"
      style="width: 100%"
      @row-dblclick="(row) => emit('open', row)"
    >
      <!-- 一键重点关注（2026-10-09）：列表里直接点星标注，不用打开案件详情。
           星标打在案件上是全局的（领导一眼要看到），所以只给管理层，
           普通民警这里不显示（后端 /cases/{id}/focus 也是 @FullAccessOnly）。 -->
      <el-table-column v-if="canManage" label="重点" width="54" align="center">
        <template #default="{ row }">
          <FocusStar :row="row" @changed="(p) => emit('focus', p)" />
        </template>
      </el-table-column>
      <!-- 报案 / 受案时间（2026-10-18 用户要求）：原来的「编号」（CA-xxx）业务意义不大，
           换成业务上真正要看的那个时间点。已受案的取受案时间，未受案的手工警情取报案时间，
           两者在后端都已汇总到 caseTime，这里只负责按有无 acceptTime 决定标签。
           2026-10-25：标签与日期改同一行（原来上下两行既占宽又抬高行高，一屏少看两条）。 -->
      <el-table-column v-if="showCaseTime && !compact" label="报案/受案时间" width="132">
        <template #default="{ row }">
          <template v-if="caseSortTimeText(row)">
            <el-tag size="small" :type="row.acceptTime || row.flowStage === 'DETAIN' || row.flowStage === 'BAIL' ? 'primary' : 'info'" effect="plain">
              {{ caseSortTimeLabel(row) }}
            </el-tag>
            <span class="cf-muted" style="font-size: 12px; margin-left: 4px">{{ caseSortTimeText(row) }}</span>
          </template>
          <span v-else class="cf-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="案件名称" :width="compact ? 150 : 172" show-overflow-tooltip>
        <template #default="{ row }">
          <span :style="{ color: priorityColor(row), fontWeight: row.priority === 'URGENT' ? 600 : 400 }">
            {{ row.name }}
          </span>
          <el-tag v-if="row.priority === 'URGENT' || row.priority === 'HIGH'" size="small" type="danger" effect="plain" style="margin-left: 6px">
            {{ PRIORITY_META[row.priority].label }}
          </el-tag>
          <span class="cf-muted" style="margin-left: 6px">{{ SOURCE_META[row.sourceType] }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="!compact" label="案件类型" :min-width="80">
        <template #default="{ row }">
          <el-tag v-if="row.caseType" size="small" :type="(CASE_TYPE_META[row.caseType] || {}).type || 'info'" effect="plain">
            {{ (CASE_TYPE_META[row.caseType] || {}).label }}
          </el-tag>
          <span v-else class="cf-muted">—</span>
          <div v-if="row.category" class="cf-muted" style="font-size: 12px">{{ row.category }}</div>
        </template>
      </el-table-column>
      <el-table-column v-if="!compact" label="嫌疑人" :min-width="88" class-name="cf-col-suspect">
        <template #default="{ row }">
          <el-tooltip v-if="suspectLabel(row)" :content="suspectNamesText(row)"
            placement="top" :disabled="!suspectNamesText(row)">
            <span class="cf-suspect">{{ suspectLabel(row) }}</span>
          </el-tooltip>
          <span v-else class="cf-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="!compact" label="办案阶段" :min-width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="(STAGE_META[row.flowStage] || {}).type" effect="plain">
            {{ row.flowStageName || stageLabel(row.flowStage) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="主办 / 协办" :min-width="compact ? 90 : 96" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.owner ? row.owner.employeeName : '未指派' }}</span>
          <span v-if="row.members && row.members.length" class="cf-muted">
            （协办 {{ row.members.length }} 人）
          </span>
        </template>
      </el-table-column>
      <el-table-column label="截止期限" :min-width="114" show-overflow-tooltip>
        <template #default="{ row }">
          <!-- 2026-10-14：列表只显示剩余/已逾期天数，具体哪天截止点详情看
               （详情抽屉 CaseDetailDrawer 用 dueText，那里带日期） -->
          <span :class="dueClass(row)">{{ dueDaysText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" :min-width="68">
        <template #default="{ row }">
          <el-tag size="small" :type="row.caseType === 'ADMINISTRATIVE' && row.status !== 'DONE' ? 'primary' : (STATUS_META[row.status] || {}).type || 'info'">
            {{ row.caseType === 'ADMINISTRATIVE' && row.status !== 'DONE' ? '已受案' : (STATUS_META[row.status] || {}).label || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <!-- 操作列：编辑是 2026-10-24 加的（管理员新增/导入案件后常要改案件名，
           原来只能「详情 → 右上角编辑案件」，隔了两层）。只给显式传 editable 的页面，
           否则工作台/到期提醒这些没接 @edit 的页面会出现点了没反应的死按钮。
           2026-10-25：fixed="right"——列总宽超过容器时右侧按钮会被推出视口，
           固定在右边后无论窗口多窄都能点到（用户反馈"操作栏未显示完全"）。 -->
      <el-table-column v-if="showActions" label="操作" :width="actionWidth" align="right" header-align="center" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canManage && !detailsOnly" link type="primary" @click="emit('assign', row)">指派</el-button>
          <el-button v-if="editable && canManage && !detailsOnly" link type="primary" @click="emit('edit', row)">编辑</el-button>
          <el-button link @click="emit('open', row)">详情</el-button>
          <el-button v-if="canManage && !detailsOnly" link type="danger" @click="emit('remove', row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty><span class="cf-muted">暂无数据</span></template>
    </el-table>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { DUE_META, STATUS_META, PRIORITY_META, SOURCE_META, CASE_TYPE_META, STAGE_META, caseSortTimeLabel, caseSortTimeText, dueDaysText, rowClassOf, stageLabel, suspectLabel, suspectNamesText } from '../utils/format'
import { useUserStore } from '../store/user'
import FocusStar from './FocusStar.vue'

const userStore = useUserStore()
/** 指派与删除属于高级功能，只有所长/副所长/法制员可见（后端同样有拦截，这里只是不给误点） */
const canManage = computed(() => userStore.isFullAccess)


const props = defineProps({
  rows: { type: Array, default: () => [] },
  height: { type: [String, Number], default: undefined },
  showCaseTime: { type: Boolean, default: false },
  showActions: { type: Boolean, default: true },
  detailsOnly: { type: Boolean, default: false },
  /**
   * 精简模式（2026-10-08）：工作台等窄栏位用。
   * 全部列宽合计约 1156px，窄栏放不下会被挤到「截止期限」被截断，
   * 所以窄栏只留最关键的：案件名称 / 主办协办 / 截止期限 / 状态 / 操作。
   * 编号、案件类型、嫌疑人在完整列表（案件管理）里仍可看到。
   */
  compact: { type: Boolean, default: false },
  /* 表格区最小高度：数据少时也不塌陷，翻页/筛选不会把下方内容往上拽 */
  minBody: { type: Number, default: 300 },
  loading: { type: Boolean, default: false },
  /* 操作列是否出「编辑」（改案件名等）：父组件必须同时接 @edit，否则是死按钮 */
  editable: { type: Boolean, default: false }
})
const emit = defineEmits(['open', 'assign', 'remove', 'focus', 'edit'])

// 操作列宽按实际按钮数算：每个中文链接按钮约 28px + 之间 12px 间距 + 单元格左右 padding
// 2026-10-25 修：这里原来写的是 props.canManage，但 canManage 是本组件的 computed（不在 props 里），
// 取到 undefined → 永远走 84px 分支，四个按钮全挤在一起，用户看到的就是「操作」列显示不全。
const actionWidth = computed(() => {
  if (!props.showActions) return 0
  if (!(canManage.value && !props.detailsOnly)) return 84
  return props.editable ? 196 : 140
})

// 传了固定 height 时表格自身已锁定高度，不再额外撑最小高度
const wrapMin = computed(() => (props.height ? 0 : props.minBody))

const priorityColor = (row) => (PRIORITY_META[row.priority] || {}).color || '#1b2430'
const dueClass = (row) => {
  const c = (DUE_META[row.dueLevel] || DUE_META.NONE).color
  return c === 'danger' ? 'cf-danger' : c === 'warn' ? 'cf-warn' : 'cf-muted'
}
</script>
