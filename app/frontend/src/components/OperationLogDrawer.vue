<template>
  <el-drawer v-model="visible" size="520px" :with-header="false" destroy-on-close>
    <div v-if="detail.id" class="cf-logd">
      <!-- 概览 -->
      <div class="cf-logd__top">
        <span class="cf-logd__tag" :class="tagClass">{{ detail.actionName }}</span>
        <span v-if="detail.undone" class="cf-logd__state cf-logd__state--muted">已被撤回</span>
        <span v-else-if="detail.undoable" class="cf-logd__state cf-logd__state--ok">可撤回</span>
        <span v-else class="cf-logd__state cf-logd__state--muted">{{ detail.undoHint }}</span>
      </div>

      <div class="cf-logd__content">{{ detail.content }}</div>

      <el-descriptions :column="1" border size="small" class="cf-logd__desc">
        <el-descriptions-item label="操作时间">{{ detail.createdAtText || '-' }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ detail.operatorName || '系统' }}</el-descriptions-item>
        <el-descriptions-item label="操作类型">{{ detail.actionName }}</el-descriptions-item>
        <el-descriptions-item label="涉及案件">
          <template v-if="detail.caseName">
            <el-button link type="primary" @click="emit('open-case', detail.targetId)">
              {{ detail.caseNo }} · {{ detail.caseName }}
            </el-button>
            <span v-if="detail.caseExists === false" class="cf-muted">（案件当前不存在，可撤回恢复）</span>
          </template>
          <span v-else class="cf-muted">与具体案件无关</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.undoOfContent" label="撤回的对象">
          {{ detail.undoOfContent }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- 变更明细 -->
      <div class="cf-logd__section">
        <span>变更明细</span>
        <span class="cf-muted">{{ changes.length }} 项</span>
      </div>
      <table v-if="changes.length" class="cf-logd__diff">
        <thead>
          <tr>
            <th style="width: 108px">字段</th>
            <th>变更前</th>
            <th>变更后</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in changes" :key="c.field">
            <td class="cf-logd__diff-label">{{ c.label }}</td>
            <td :class="['cf-logd__diff-cell', c.type === 'ADD' && 'is-empty']">
              {{ c.before === null || c.before === '' ? '—' : c.before }}
            </td>
            <td :class="['cf-logd__diff-cell', c.type === 'REMOVE' && 'is-empty', c.type !== 'REMOVE' && 'is-after']">
              {{ c.after === null || c.after === '' ? '—' : c.after }}
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="cf-logd__empty">这次操作没有产生字段变化</div>

      <div class="cf-logd__foot">
        <el-button @click="visible = false">关闭</el-button>
        <el-button
          v-if="detail.undoable && userStore.isFullAccess"
          type="primary"
          :loading="undoing"
          @click="doUndo"
        >
          <el-icon><RefreshLeft /></el-icon>
          <span style="margin-left: 4px">撤回此操作</span>
        </el-button>
        <span v-else-if="detail.undoable" class="cf-muted">撤回需要所长 / 副所长 / 法制员权限</span>
      </div>
    </div>
    <div v-else class="cf-logd__empty">加载中…</div>
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshLeft } from '@element-plus/icons-vue'
import { logApi } from '../api'
import { useUserStore } from '../store/user'

const userStore = useUserStore()

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  logId: { type: [Number, null], default: null }
})
const emit = defineEmits(['update:modelValue', 'done', 'open-case'])

const visible = ref(false)
const detail = ref({})
const undoing = ref(false)

const changes = computed(() => detail.value.changes || [])

const tagClass = computed(() => `is-${(detail.value.action || '').toLowerCase()}`)

watch(() => props.modelValue, async (v) => {
  visible.value = v
  if (v && props.logId) await reload()
})
watch(visible, (v) => emit('update:modelValue', v))
watch(() => props.logId, async (v) => {
  if (v && visible.value) await reload()
})

const reload = async () => {
  detail.value = await logApi.detail(props.logId)
}

const doUndo = async () => {
  const n = changes.value.length
  await ElMessageBox.confirm(
    `将把这一步操作撤销，相关数据回到操作之前的状态${n ? `（涉及 ${n} 项变更）` : ''}。撤回本身也会记入操作记录，可以再撤回一次做恢复。`,
    `撤回「${detail.value.actionName}」`,
    { type: 'warning', confirmButtonText: '确认撤回', cancelButtonText: '取消' }
  )
  undoing.value = true
  try {
    const undoLog = await logApi.undo(detail.value.id)
    ElMessage.success('已撤回')
    emit('done')
    if (undoLog && undoLog.id) {
      detail.value = undoLog
    } else {
      await reload()
    }
  } finally {
    undoing.value = false
  }
}
</script>

<style scoped>
.cf-logd { display: flex; flex-direction: column; gap: 12px; }

.cf-logd__top { display: flex; align-items: center; gap: 8px; }

.cf-logd__tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 9px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  background: var(--cf-primary);
}
.cf-logd__tag.is-assign { background: var(--cf-gold); color: #3a2c0c; }
.cf-logd__tag.is-delete,
.cf-logd__tag.is-undo { background: var(--cf-danger); }
.cf-logd__tag.is-update,
.cf-logd__tag.is-status { background: var(--cf-text-2); }

.cf-logd__state { font-size: 12px; }
.cf-logd__state--ok { color: var(--cf-ok); }
.cf-logd__state--muted { color: var(--cf-text-3); }

.cf-logd__content {
  padding: 10px 12px;
  border-left: 3px solid var(--cf-gold);
  background: #f7f9fc;
  border-radius: 0 3px 3px 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--cf-navy);
  word-break: break-all;
}

.cf-logd__desc :deep(.el-descriptions__label) { width: 88px; }

.cf-logd__section {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 2px;
  font-size: 13px;
  font-weight: 600;
}
.cf-logd__section > span:first-child {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.cf-logd__section > span:first-child::before {
  content: "";
  width: 3px;
  height: 12px;
  background: var(--cf-gold);
  border-radius: 1px;
}

.cf-logd__diff {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}
.cf-logd__diff th {
  background: var(--cf-head-bg);
  color: var(--cf-navy);
  font-weight: 600;
  text-align: left;
  padding: 7px 10px;
  border: 1px solid var(--cf-border);
}
.cf-logd__diff td {
  padding: 7px 10px;
  border: 1px solid var(--cf-border);
  vertical-align: top;
  word-break: break-all;
}
.cf-logd__diff-label { color: var(--cf-text-2); white-space: nowrap; }
.cf-logd__diff-cell { color: var(--cf-text-2); }
.cf-logd__diff-cell.is-after { color: var(--cf-navy); font-weight: 600; }
.cf-logd__diff-cell.is-empty { color: var(--cf-text-3); }

.cf-logd__empty {
  padding: 18px 0;
  text-align: center;
  color: var(--cf-text-3);
  font-size: 12px;
}

.cf-logd__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 4px;
  border-top: 1px solid var(--cf-border);
  margin-top: 4px;
}

/* 手机端：抽屉已占满整屏，把内边距和字号收一档，
   变更明细那张三列表格才不至于挤成竖条 */
</style>
