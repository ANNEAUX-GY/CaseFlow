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
