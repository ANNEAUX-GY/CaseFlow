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
  return `${row.deadlineText}（剩 ${row.daysLeft} 天）`
}

export function rowClassOf(row) {
  const meta = DUE_META[row.dueLevel] || DUE_META.NONE
  if (['DONE', 'CANCELLED'].includes(row.status)) return ''
  return meta.rowClass
}

// ============ 阶段→环节→任务 流程（2026-10）============

/** 流程阶段。flow_stage=NULL 的存量案件按 INITIAL 处理，与后端 CaseFlowTemplate 一致 */
export const STAGE_META = {
  INITIAL: { label: '初查', type: 'primary', desc: '接收材料 → 立案 → 侦查 → 判断 → 刑拘/处罚' },
  DETAIN: { label: '刑拘在办', type: 'warning', desc: '指派清案民警 → 逮捕；或取保、释放' },
  BAIL: { label: '取保及监居', type: 'info', desc: '取保/监居执行与盯办（流程后续细化）' },
  CLOSED: { label: '已终结', type: 'success', desc: '清案结束 / 释放 / 处罚决定作出' }
}

export function stageLabel(stage) {
  return (STAGE_META[stage] || STAGE_META.INITIAL).label
}

/** 环节 key → 中文名（与后端 CaseFlowTemplate.stepLabel 保持一致） */
export const STEP_LABEL = {
  RECEIVE: '接收材料',
  CASE_FILL: '立案',
  INVESTIGATE: '侦查',
  REVIEW: '是否符合刑拘条件',
  DETAIN: '刑拘',
  ASSIGN_CLEAR: '指派清案民警',
  ARREST: '逮捕',
  EXECUTE_BAIL: '执行取保/监居',
  PRESENT: '呈批材料',
  PUNISH: '批准行政处罚',
  RELEASE: '释放',
  CLOSE: '清案结束',
  CUSTOM: '其他事项'
}

export function stepLabel(key) {
  return STEP_LABEL[key] || '侦查'
}

/** 流转动作 → 中文名 */
export const ACTION_LABEL = {
  DETAIN: '刑拘',
  BAIL: '取保候审',
  RELEASE: '释放',
  ARREST: '逮捕',
  PUNISH: '批准行政处罚',
  CLOSE: '解除收案'
}

// ============ 民警端待办（2026-10-04） ============
// 命名加 TODO_ 前缀，与上面意见模块的 URGENCY_META（已逾期/临期/正常）区分：
// 那是按截止时间实时算的紧急性，这是民警手动标的紧急程度，语义不同不可混用。
/** 紧急程度：手动三档，不随时间变化（与截止时间刻意分开） */
export const TODO_URGENCY_META = {
  URGENT: { label: '紧急', type: 'danger' },
  HIGH: { label: '较急', type: 'warning' },
  NORMAL: { label: '一般', type: 'info' }
}

/** 重点程度：手动三档 */
export const TODO_IMPORTANCE_META = {
  KEY: { label: '重点', type: 'danger' },
  MEDIUM: { label: '次重点', type: 'warning' },
  NORMAL: { label: '一般', type: 'info' }
}

/** 待办状态 */
export const TODO_STATUS_META = {
  PENDING: { label: '待办', type: 'warning' },
  DONE: { label: '已完成', type: 'success' },
  CANCELLED: { label: '已取消', type: 'info' }
}

/** 紧急程度归一：NULL（历史数据）视为一般 */
export function todoUrgencyOf(row) {
  const v = row && row.urgency
  return TODO_URGENCY_META[v] ? v : 'NORMAL'
}

/** 重点程度归一：NULL（历史数据）视为一般 */
export function todoImportanceOf(row) {
  const v = row && row.importance
  return TODO_IMPORTANCE_META[v] ? v : 'NORMAL'
}

/** 距截止天数 → 展示文案。负数=已超期，0=今天到期 */
export function todoDueTextOf(row) {
  if (!row || !row.deadline) return { text: '', cls: '', overdue: false }
  const d = row.daysLeft
  const dt = String(row.deadline).replace('T', ' ').slice(5, 16)
  if (d == null) return { text: dt, cls: '', overdue: false }
  if (d < 0) return { text: dt + '（已超期 ' + (-d) + ' 天）', cls: 'is-overdue', overdue: true }
  if (d === 0) return { text: dt + '（今天到期）', cls: 'is-today', overdue: false }
  if (d <= 3) return { text: dt + '（剩 ' + d + ' 天）', cls: 'is-soon', overdue: false }
  return { text: dt, cls: '', overdue: false }
}

// ============ 嫌疑人展示（2026-10-09） ============
// 列表接口的 row.suspects 由后端批量填充，只含 id / 姓名 / 性别（不含身份证、手机号）。
// 展示口径：1 人显示姓名，多人显示「张三等N人」（N = 总人数，含张三本人），
// 这样与列/卡片上的「嫌疑人数」永远对得上，不会出现「显示 2 人实际 3 人」。

/**
 * 列表里的嫌疑人摘要。
 * @returns {string} 无嫌疑人返回空串，由调用方决定占位（各列表统一用「—」）
 */
export function suspectLabel(row) {
  const list = row?.suspects || []
  if (list.length) {
    const first = String(list[0]?.name || '').trim()
    if (list.length === 1) return first || '1 人'
    return (first || '未具名') + '等' + list.length + '人'
  }
  // 兜底：老接口只给计数（没有姓名列表）时，至少别显示成「—」
  const n = row?.suspectCount || 0
  return n > 0 ? n + ' 人' : ''
}

/** 完整名单（「、」分隔），给列表列挂 tooltip 用；单人或无嫌疑人返回空串 */
export function suspectNamesText(row) {
  const list = row?.suspects || []
  if (list.length < 2) return ''
  return list.map((s) => String(s?.name || '').trim()).filter(Boolean).join('、')
}

// ============ 办案组别（2026-10-04） ============
export const POLICE_GROUP_META = {
  INITIAL: { label: '初查组', type: 'primary', color: '#1b4a8c' },
  CLEAR: { label: '清案组', type: 'warning', color: '#d98a0b' },
  NONE: { label: '不限', type: 'info', color: '#8a929e' }
}

export function policeGroupLabel(g) {
  return (POLICE_GROUP_META[g] || POLICE_GROUP_META.NONE).label
}

/** 某案件要求什么组别：module 是盯办子模块。
 *  与后端 PoliceGroup.requiredOf 保持一致 */
export function requiredGroupOfModule(module) {
  if (module === 'INITIAL') return 'INITIAL'
  if (module === 'DETENTION') return 'CLEAR'
  return 'NONE'
}
