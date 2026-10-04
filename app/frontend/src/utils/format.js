/** 到期等级 -> 展示文本 / 颜色 / 行样式 */
export const DUE_META = {
  OVERDUE: { text: '已逾期', color: 'danger', rowClass: 'cf-row--overdue' },
  TODAY: { text: '今天到期', color: 'warn', rowClass: 'cf-row--soon' },
  SOON: { text: '3天内', color: 'warn', rowClass: 'cf-row--soon' },
  NORMAL: { text: '', color: 'ok', rowClass: '' },
  NONE: { text: '未设期限', color: 'muted', rowClass: '' }
}

export const STATUS_META = {
  PENDING_ASSIGN: { label: '待指派', type: 'danger' },
  ASSIGNED: { label: '已指派', type: 'warning' },
  IN_PROGRESS: { label: '处理中', type: 'primary' },
  DONE: { label: '已办结', type: 'success' },
  CANCELLED: { label: '已撤销', type: 'info' }
}

export const PRIORITY_META = {
  URGENT: { label: '特急', color: '#c62a2a' },
  HIGH: { label: '紧急', color: '#d98a0b' },
  NORMAL: { label: '普通', color: '#5a6472' },
  LOW: { label: '低', color: '#8a929e' }
}

export const SOURCE_META = {
  MANUAL: '手工',
  PDF: 'PDF',
  WORD: 'Word',
  EXCEL: 'Excel'
}

/** 案卷类型（大类）：未立案 / 刑事 / 行政 */
export const CASE_TYPE_META = {
  PRELIMINARY: { label: '未立案', type: 'info' },
  CRIMINAL: { label: '刑事', type: 'danger' },
  ADMINISTRATIVE: { label: '行政', type: 'warning' }
}

/** 侦查进度（案件盯办） */
export const INVEST_STATUS_META = {
  PENDING_INITIAL: { label: '待初查', type: 'info' },
  INVESTIGATING: { label: '侦查中', type: 'primary' },
  PENDING_APPROVAL: { label: '待审批', type: 'warning' },
  INVESTIGATION_DONE: { label: '侦查终结', type: 'success' }
}

/** 领导意见的落实反馈状态（办案人对每条意见标记） */
export const FEEDBACK_STATUS_META = {
  DONE: { label: '已完成', type: 'success' },
  IN_PROGRESS: { label: '进行中', type: 'warning' },
  NOT_DONE: { label: '未完成', type: 'danger' }
}

// ============ 领导意见（2026-10 改造）============

/** 重要性分级。A=最重要 / B=重要 / C=一般（默认档） */
export const IMPORTANCE_META = {
  A: { label: 'A 最重要', short: 'A', type: 'danger' },
  B: { label: 'B 重要', short: 'B', type: 'warning' },
  C: { label: 'C 一般', short: 'C', type: 'info' }
}

/** 旧数据 importance 为 null 时一律按 C（一般）处理，这里做归一 */
export function importanceOf(row) {
  const v = (row?.importance || '').toUpperCase()
  return IMPORTANCE_META[v] ? v : 'C'
}

/**
 * 紧急性由「截止时间 + 当前时刻」实时算出，**不落库**。
 * 无截止时间 → 正常。
 * 阈值 URGENT_HOURS：距截止不足该小时数视为临期。
 */
export const URGENT_HOURS = 48

export const URGENCY_META = {
  OVERDUE: { label: '已逾期', type: 'danger' },
  URGENT: { label: '临期', type: 'warning' },
  NORMAL: { label: '正常', type: 'info' }
}

/**
 * 判定单条意见的紧急性。
 * @param {string} deadline 后端返回的 'YYYY-MM-DDTHH:mm:ss' 或 'YYYY-MM-DD HH:mm:ss'
 * @returns 'OVERDUE' | 'URGENT' | 'NORMAL'
 */
export function urgencyOf(deadline) {
  if (!deadline) return 'NORMAL'
  // 兼容 T 分隔与空格分隔；Safari 不认 'YYYY-MM-DD HH:mm:ss'，统一换成 T
  const d = new Date(String(deadline).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return 'NORMAL'
  const diffH = (d.getTime() - Date.now()) / 3600000
  if (diffH < 0) return 'OVERDUE'
  if (diffH <= URGENT_HOURS) return 'URGENT'
  return 'NORMAL'
}

/** 截止时间展示文案；无值返回空串（调用方自行决定是否显示） */
export function deadlineTextOf(deadline) {
  if (!deadline) return ''
  return String(deadline).replace('T', ' ').slice(0, 16)
}

/** 强制措施（案件盯办） */
export const MEASURE_META = {
  DETENTION: { label: '刑拘', type: 'danger' },
  BAIL: { label: '取保候审', type: 'warning' },
  RESIDENCE: { label: '监视居住', type: 'warning' }
}

export function dueText(row) {
  if (!row.deadlineText) return '未设期限'
  if (row.dueLevel === 'OVERDUE') {
    return `${row.deadlineText}（逾期 ${Math.abs(row.daysLeft || 0)} 天）`
  }
  if (row.dueLevel === 'TODAY') return `${row.deadlineText}（今天到期）`
  if (row.dueLevel === 'SOON') return `${row.deadlineText}（剩 ${row.daysLeft} 天）`
  return `${row.deadlineText}（剩 ${row.daysLeft} 天）`
}

export function rowClassOf(row) {
  const meta = DUE_META[row.dueLevel] || DUE_META.NONE
  if (['DONE', 'CANCELLED'].includes(row.status)) return ''
  return meta.rowClass
}
