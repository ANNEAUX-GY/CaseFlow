<template>
  <!-- 手机端：竖排卡片。375px 宽下表格只剩横向滚动，读一行要来回搓屏，索性换版式 -->
  <div v-if="isMobile" v-loading="loading" class="cf-ccards">
    <div
      v-for="row in rows"
      :key="row.id"
      class="cf-ccard"
      :class="cardClassOf(row)"
      @click="emit('open', row)"
    >
      <div class="cf-ccard__top">
        <span class="cf-ccard__name" :style="{ color: priorityColor(row) }">{{ row.name }}</span>
        <span class="cf-ccard__tags">
          <el-tag v-if="row.priority === 'URGENT' || row.priority === 'HIGH'" size="small" type="danger" effect="plain">
            {{ PRIORITY_META[row.priority].label }}
          </el-tag>
          <el-tag size="small" :type="(STATUS_META[row.status] || {}).type || 'info'">
            {{ (STATUS_META[row.status] || {}).label || row.status }}
          </el-tag>
        </span>
      </div>

      <div class="cf-ccard__meta">
        <span v-if="showCaseNo">{{ row.caseNo }}</span>
        <span>
          <el-tag v-if="row.caseType" size="small" :type="(CASE_TYPE_META[row.caseType] || {}).type || 'info'" effect="plain">
            {{ (CASE_TYPE_META[row.caseType] || {}).label }}
          </el-tag>
          <span v-if="row.category" style="margin-left: 4px">{{ row.category }}</span>
        </span>
        <span>主办 <b>{{ row.owner ? row.owner.employeeName : '未指派' }}</b><template v-if="row.members && row.members.length">· 协办 {{ row.members.length }} 人</template></span>
        <span>{{ SOURCE_META[row.sourceType] }}<template v-if="row.suspectCount">· 嫌疑人 {{ row.suspectCount }}</template></span>
      </div>

      <div class="cf-ccard__foot">
        <span class="cf-ccard__due" :class="dueClass(row)">{{ dueText(row) }}</span>
        <span class="cf-ccard__ops">
          <template v-if="showActions">
            <el-button v-if="canManage" link type="primary" @click.stop="emit('assign', row)">指派</el-button>
            <el-button link @click.stop="emit('open', row)">详情</el-button>
            <el-button v-if="canManage" link type="danger" @click.stop="emit('remove', row)">删除</el-button>
          </template>
        </span>
      </div>
    </div>

    <div v-if="!rows.length && !loading" class="cf-ccards__empty">暂无数据</div>
  </div>

  <div v-else class="cf-table-wrap" :style="{ minHeight: `${wrapMin}px` }">
    <el-table
      v-loading="loading"
      :data="rows"
      size="small"
      :height="height"
      :row-class-name="({ row }) => rowClassOf(row)"
      style="width: 100%"
      @row-dblclick="(row) => emit('open', row)"
    >
      <el-table-column v-if="showCaseNo" prop="caseNo" label="编号" width="150" />
      <el-table-column label="案件名称" min-width="220" show-overflow-tooltip>
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
      <el-table-column label="案件类型" width="140">
        <template #default="{ row }">
          <el-tag v-if="row.caseType" size="small" :type="(CASE_TYPE_META[row.caseType] || {}).type || 'info'" effect="plain">
            {{ (CASE_TYPE_META[row.caseType] || {}).label }}
          </el-tag>
          <span v-else class="cf-muted">—</span>
          <div v-if="row.category" class="cf-muted" style="font-size: 12px">{{ row.category }}</div>
        </template>
      </el-table-column>
      <el-table-column label="嫌疑人" width="70" align="center">
        <template #default="{ row }">
          <span v-if="row.suspectCount" style="font-weight: 600">{{ row.suspectCount }}</span>
          <span v-else class="cf-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="主办 / 协办" min-width="150" show-overflow-tooltip>
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
import { useDevice } from '../utils/device'

const userStore = useUserStore()
/** 指派与删除属于高级功能，只有所长/副所长/法制员可见（后端同样有拦截，这里只是不给误点） */
const canManage = computed(() => userStore.isFullAccess)

const { isMobile } = useDevice()

const props = defineProps({
  rows: { type: Array, default: () => [] },
  height: { type: [String, Number], default: undefined },
  showCaseNo: { type: Boolean, default: false },
  showActions: { type: Boolean, default: true },
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

/** 卡片左侧色条：逾期红、临期黄、已办结/已撤销灰 */
const cardClassOf = (row) => {
  if (['DONE', 'CANCELLED'].includes(row.status)) return 'cf-row--closed'
  return rowClassOf(row)
}
</script>
