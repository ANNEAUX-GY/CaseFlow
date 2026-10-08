import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/**
 * 案件类型选择器（统一入口门控，2026-10-04）。
 *
 * <p><b>为什么需要</b>：案件盯办 / 待办总览 / 案件管理 / 到期提醒这四个栏目
 * 面向的是同��批案件，但民警打开时往往还没想好今天要看哪一类。
 * 强制先选类型再进入，好处是：列表、看板、图表的统计口径从一开始就锁定，
 * 不会出现"刑事行政混在一张表里数不清"的情况。
 *
 * <p><b>存储位置与生命周期</b>：
 * <ul>
 *   <li>内存：Pinia（响应式，组件直接读）</li>
 *   <li>磁盘：localStorage键 `cf_case_type`（刷新不丢）</li>
 * </ul>
 * 生命周期 = 一次「进入 → 选类型 → 浏览」的过程，直到用户点右上角「退出」。
 * 因此<b>切栏目不重置</b>（切走再回来仍是原类型），
 * 而<b>刷新页面也不重置</b>（双写的意义就在这儿）。
 *
 * <p><b>为什么双写而不是只用 Pinia</b>：只在内存的话，用户按 F5 会重新看到
 * 类型选择框，等于变相重置了选择 —— 与"未手动退出就保持"的要求冲突。
 */
const KEY = 'cf_case_type'

/** 三个选项。scope 为传后端的查询值；OTHER 走后端 NOT IN 分支 */
export const CASE_TYPE_OPTIONS = [
  {
    key: 'CRIMINAL',
    label: '刑事案件',
    scope: 'CRIMINAL',
    type: 'danger',
    desc: '刑事案件 ·初查 / 刑拘在办 / 取保及监居'
  },
  {
    key: 'ADMINISTRATIVE',
    label: '行政案件',
    scope: 'ADMINISTRATIVE',
    type: 'warning',
    desc: '行政案件 · 呈批材料 / 行政处罚'
  },
  {
    key: 'OTHER',
    label: '其他案件',
    scope: 'OTHER',
    type: 'info',
    desc: '未立案及其他非刑事、非行政案件'
  }
]

/** 受门控的栏目：进入前必须先选类型 */
export const GATED_PATHS = ['/watch', '/todos', '/cases', '/reminders', '/case-boards']

/** 本地已保存的原始值 → 选项 key */
function readStored() {
  try {
    const raw = localStorage.getItem(KEY) || ''
    const hit = CASE_TYPE_OPTIONS.find(o => o.key === raw)
    return hit ? hit.key : ''
  } catch (e) {
    return ''
  }
}

export const useCaseTypeStore = defineStore('caseType', () => {
  /** 当前选中的类型 key（'' = 未选） */
  const current = ref(readStored())
  /** 上次浏览的栏目，用于退出后回到原处（而不是回工作台） */
  const lastGatedPath = ref('')
  /** 选择器是否处于"待选择"状态（退出后为 true，用于高亮侧栏提示） */
  const needSelect = ref(!current.value)

  const currentOption = computed(
    () => CASE_TYPE_OPTIONS.find(o => o.key === current.value) || null
  )
  /** 传给后端的 caseType 查询值；未选时返回 ''（后端不过滤） */
  const scope = computed(() => currentOption.value?.scope || '')
  const selected = computed(() => !!current.value)

  const persist = (v) => {
    try {
      if (v) localStorage.setItem(KEY, v)
      else localStorage.removeItem(KEY)
    } catch (e) {
      /* 隐私模式等场景写不进去也不影响本次会话 */
    }
  }

  /** 选择类型：写入内存 + 磁盘 */
  const select = (key) => {
    if (!CASE_TYPE_OPTIONS.some(o => o.key === key)) return false
    current.value = key
    needSelect.value = false
    persist(key)
    return true
  }

  /** 退出：清空选择，回到待选择状态 */
  const exit = () => {
    current.value = ''
    needSelect.value = true
    persist('')
  }

  /** 记住当前栏目（切换时调用，供退出后回跳） */
  const rememberPath = (p) => {
    if (GATED_PATHS.some(g => p.startsWith(g))) lastGatedPath.value = p
  }

  /** 跨账号或登出时清空——上一个用户的选择不该留给下一个 */
  const reset = () => {
    current.value = ''
    lastGatedPath.value = ''
    needSelect.value = true
    persist('')
  }

  return {
    current, currentOption, scope, selected, needSelect, lastGatedPath,
    select, exit, reset, rememberPath,
    isGated: (p) => GATED_PATHS.some(g => p.startsWith(g))
  }
})

/**
 * 把当前类型混入查询参数——**所有受门控栏目的请求都必须走这里**。
 *
 * <p>为什么不直接在各页面写 `params.caseType = store.scope`：
 * 那样每个页面都要记得加，漏一个就出现"列表锁了类型、图表没锁"的错位。
 * 集中成一个函数后，只要新增接口就调它，约束是**默认生效**的。
 *
 * <p>与后端的对应关系：
 * - CRIMINAL / ADMINISTRATIVE → 等值匹配
 * - OTHER → 后端走 NOT IN(刑事,行政) OR IS NULL
 * - 未选类型（''）→ 不传该参数。此时理论上已被守卫拦下，
 *   但留着做兜底：万一有漏网请求，至少不会凭空只显示某一类。
 *
 * @param {Object} params 原始查询参数
 * @returns {Object} 新对象（不改入参），带 caseType
 */
export function withCaseType(params) {
  const store = useCaseTypeStore()
  const out = { ...(params || {}) }
  const s = store.scope
  if (s) out.caseType = s
  else delete out.caseType
  return out
}

/**
 * 安全跳转到受门控的栏目（跨栏目跳转专用）。
 *
 * <p><b>解决什么问题</b>：工作台、员工图谱里有 4 处 `router.push('/cases', {...})`
 * （按状态跳、按经办人跳）。若此时用户还没选案件类型，裸跳会被路由守卫弹回
 * 类型选择器，**而原 query 里的 status / employeeId 会一起丢掉**——
 * 用户选完类型后落在一个"什么都没筛"的列表页，等于白点了一次。
 *
 * <p>做法：未选类型时把**完整目标路径（含 query）**编码进 from，选择器读完再还原。
 *
 * @param {import('vue-router').Router} router
 * @param {string} path 目标路径，如 '/cases'
 * @param {Object} [query] 目标查询参数
 */
export function gotoGated(router, path, query) {
  const store = useCaseTypeStore()
  store.rememberPath(path)
  if (store.selected) {
    router.push(query ? { path, query } : { path })
    return
  }
  // 用 URLSearchParams 拼完整地址，from 里同时带路径与筛选条件
  const qs = query ? new URLSearchParams(query).toString() : ''
  const full = qs ? path + '?' + qs : path
  router.push({ path: '/case-type', query: { from: full } })
}
