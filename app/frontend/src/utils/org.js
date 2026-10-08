/**
 * 组织层级（2026-10-08）：总 → 副总 → 组长 → 员工，四层。
 *
 * <p>与后端 `flow/OrgRank.java` 是同一套口径的镜像，**改规则必须两边一起改**，
 * 否则界面上算出来的层级和后端返回的对不上。
 */

/** 层级定义：数字越小越靠核心 */
export const RANK = {
  TOP: 1,      // 总 / 领导 / 所长
  DEPUTY: 2,   // 副总 / 副领导
  LEADER: 3,   // 组长 / 队长
  STAFF: 4     // 员工 / 组员 / 民警
}

/** 层级配色：与员工图谱的层级色保持一致，越靠核心越深 */
export const RANK_COLORS = {
  1: '#12294a',
  2: '#1b4a8c',
  3: '#2a5da6',
  4: '#4a76bd'
}

/** 部门待核的标记色（没填部门 / 独立部门）——刻意用橙，一眼区别于四级蓝 */
export const DEPT_ANOMALY_COLOR = '#d98a0b'

/** 层级中文名 */
export function rankLabel(rank) {
  if (rank === RANK.TOP) return '总/领导'
  if (rank === RANK.DEPUTY) return '副总'
  if (rank === RANK.LEADER) return '组长'
  return '员工'
}

/**
 * 职务 → 层级。
 *
 * <p>判定顺序与后端一致：先「副」再「组长」，否则「副组长」会被误判成副总层；
 * 「长」放最后兜底，否则「组长」「队长」会被当成总。空职务算员工层。
 */
export function rankOf(title) {
  const t = (title || '').trim()
  if (!t) return RANK.STAFF
  if (t.includes('副')) return RANK.DEPUTY
  if (t.includes('组长') || t.includes('队长')) return RANK.LEADER
  if (t.includes('组员') || t.includes('警员') || t.includes('民警')) return RANK.STAFF
  if (t.includes('领导') || t.includes('总') || t.includes('长')) return RANK.TOP
  return RANK.STAFF
}

/** 上级候选项：该填「哪一层」的人（总不选上级，副总选总，组长选副总，员工选组长） */
export function parentRankOptions(rank) {
  if (!rank || rank <= RANK.TOP) return []
  return [rank - 1]
}

/** 节点色：部门待核优先（要跳出来提醒人），否则按层级取色 */
export function nodeColor(node, fallbackRank) {
  if (node && node.deptAnomaly) return DEPT_ANOMALY_COLOR
  const r = (node && node.rank) || fallbackRank || RANK.STAFF
  return RANK_COLORS[r] || RANK_COLORS[RANK.STAFF]
}

/** 部门待核的中文说明，直接放在界面上（项目禁用悬浮提示） */
export function deptAnomalyText(node) {
  if (!node || !node.deptAnomaly) return ''
  if (node.deptMissing) return '未选部门'
  return '独立部门（仅本人）'
}

/** 手机端用的短文案：树节点一行放不下，长的会被截掉半个字 */
export function deptAnomalyTextShort(node) {
  if (!node || !node.deptAnomaly) return ''
  return node.deptMissing ? '未选部门' : '独立部门'
}
