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
      <el-table-column v-if="showCaseNo && !compact" prop="caseNo" label="编号" width="150" />
      <el-table-column label="案件名称" :min-width="compact ? 170 : 220" show-overflow-tooltip>
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
      <el-table-column v-if="!compact" label="案件类型" width="140">
        <template #default="{ row }">
          <el-tag v-if="row.caseType" size="small" :type="(CASE_TYPE_META[row.caseType] || {}).type || 'info'" effect="plain">
            {{ (CASE_TYPE_META[row.caseType] || {}).label }}
          </el-tag>
          <span v-else class="cf-muted">—</span>
          <div v-if="row.category" class="cf-muted" style="font-size: 12px">{{ row.category }}</div>
        </template>
      </el-table-column>
      <el-table-column v-if="!compact" label="嫌疑人" width="70" align="center">
        <template #default="{ row }">
          <span v-if="row.suspectCount" style="font-weight: 600">{{ row.suspectCount }}</span>
          <span v-else class="cf-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="主办 / 协办" :min-width="compact ? 110 : 150" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.owner ? row.owner.employeeName : '未指派' }}</span>
          <span v-if="row.members && row.members.length" class="cf-muted">
            （协办 {{ row.members.length }} 人）
          </span>
        </template>
      </el-table-column>
      <el-table-column label="截止期限" width="190">
        <template #default="{ row }">
          <span :class="dueClass(row)">{{ dueText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="86">
        <template #default="{ row }">
          <el-tag size="small" :type="(STATUS_META[row.status] || {}).type || 'info'">
            {{ (STATUS_META[row.status] || {}).label || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="showActions" label="操作" :width="canManage ? 150 : 90" align="right">
        <template #default="{ row }">
          <el-button v-if="canManage" link type="primary" @click="emit('assign', row)">指派</el-button>
          <el-button link @click="emit('open', row)">详情</el-button>
          <el-button v-if="canManage" link type="danger" @click="emit('remove', row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty><span class="cf-muted">暂无数据</span></template>
    </el-table>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { DUE_META, STATUS_META, PRIORITY_META, SOURCE_META, CASE_TYPE_META, dueText, rowClassOf } from '../utils/format'
import { useUserStore } from '../store/user'

const userStore = useUserStore()
/** 指派与删除属于高级功能，只有所长/副所长/法制员可见（后端同样有拦截，这里只是不给误点） */
const canManage = computed(() => userStore.isFullAccess)


const props = defineProps({
  rows: { type: Array, default: () => [] },
  height: { type: [String, Number], default: undefined },
  showCaseNo: { type: Boolean, default: false },
  showActions: { type: Boolean, default: true },
  /**
   * 精简模式（2026-10-08）：工作台等窄栏位用。
   * 全部列宽合计约 1156px，窄栏放不下会被挤到「截止期限」被截断，
   * 所以窄栏只留最关键的：案件名称 / 主办协办 / 截止期限 / 状态 / 操作。
   * 编号、案件类型、嫌疑人在完整列表（案件管理）里仍可看到。
   */
  compact: { type: Boolean, default: false },
  /* 表格区最小高度：数据少时也不塌陷，翻页/筛选不会把下方内容往上拽 */
  minBody: { type: Number, default: 300 },
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['open', 'assign', 'remove'])

// 传了固定 height 时表格自身已锁定高度，不再额外撑最小高度
const wrapMin = computed(() => (props.height ? 0 : props.minBody))

const priorityColor = (row) => (PRIORITY_META[row.priority] || {}).color || '#1b2430'
const dueClass = (row) => {
  const c = (DUE_META[row.dueLevel] || DUE_META.NONE).color
  return c === 'danger' ? 'cf-danger' : c === 'warn' ? 'cf-warn' : 'cf-muted'
}
</script>
