import { createRouter, createWebHashHistory } from 'vue-router'
import { isFullAccessRole } from '../store/user'
import { GATED_PATHS, useCaseTypeStore } from '../store/caseType'

/**
 * 普通民警（非全权限角色）只能进这两个页面：内容都只与本人民下案件相关。
 * 用白名单而不是逐个页面加标记 —— 以后新增的页面默认就是管理层专属，不会漏配。
 */
const STAFF_PAGES = ['/my-cases', '/reminders', '/my-todos', '/case-type']
// /case-type 必须对所有登录用户开放：普通民警没选过案件类型时，
// 点「我的案件/到期提醒」等门控页会被引导到类型选择页；
// 若此页不在白名单，会被上面那条规则弹回 /my-cases，形成
// 「点菜单 → 跳选择页 → 被弹回」的循环，表现为菜单点不动（实测踩过）。

/** 登录后的落地页：管理层进工作台，普通民警进我的案件 */
export const homePathOf = (role) => (isFullAccessRole(role) ? '/dashboard' : '/my-cases')

/** 路由守卫里拿不到 Pinia 实例，只能从 localStorage 读角色 */
const storedRole = () => {
  try {
    return JSON.parse(localStorage.getItem('cf_user') || '{}').role || ''
  } catch (e) {
    return ''
  }
}

/** 取一级路径：/my-cases/detail -> /my-cases */
const firstSeg = (path) => '/' + (path.split('/')[1] || '')

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue'), meta: { public: true } },
  { path: '/register', name: 'Register', component: () => import('../views/Register.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../layout/Layout.vue'),
    redirect: () => homePathOf(storedRole()),
    children: [
      // 普通民警主页：系统按实名（绑定的员工档案）匹配出的本人名下案件
      { path: 'my-cases', name: 'MyCases', component: () => import('../views/MyCases.vue'), meta: { title: '我的案件' } },
      // 我的待办（普通民警端）：领导意见自动派生，可按紧急/重点排序
      { path: 'my-todos', name: 'MyTodos', component: () => import('../views/MyTodos.vue'), meta: { title: '我的待办' } },
      { path: 'dashboard', name: 'Dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '工作台' } },
      { path: 'cases', name: 'Cases', component: () => import('../views/CaseList.vue'), meta: { title: '案件管理' } },
      // 按类别浏览（板块页）：选完案件类型先进这里，按类别分卡片下钻到案件列表
      { path: 'case-boards', name: 'CaseBoards', component: () => import('../views/CaseBoards.vue'), meta: { title: '按类别浏览', fullAccessOnly: true } },
      { path: 'reminders', name: 'Reminders', component: () => import('../views/Reminder.vue'), meta: { title: '到期提醒' } },
      { path: 'org', name: 'Org', component: () => import('../views/EmployeeTree.vue'), meta: { title: '员工图谱' } },
      // 案件盯办（初查/刑拘/取保监居 三子模块 + 看板）
      { path: 'watch', name: 'Watch', component: () => import('../views/WatchView.vue'), meta: { title: '案件盯办' } },
      // 案件待办总览：管理者查看各待办完成状态与对应佐证材料
      { path: 'todos', name: 'Todos', component: () => import('../views/TodoOverview.vue'), meta: { title: '待办总览', fullAccessOnly: true } },
      // 账号管理只对全权限角色开放；普通民警即使手敲地址，后端接口也会返回 403
      { path: 'users', name: 'Users', component: () => import('../views/UserManage.vue'), meta: { title: '账号管理', fullAccessOnly: true } },
      // 案件类别（小类）字典维护，管理权限专属
      { path: 'categories', name: 'Categories', component: () => import('../views/CategoryManage.vue'), meta: { title: '类别管理', fullAccessOnly: true } },
      // 案件类型选择器（统一入口门控）：受门控的 4 个栏目在未选类型前先进这里。
      // 不是 public 页——仍需登录，只是免除 fullAccessOnly 与类型门控。
      { path: 'case-type', name: 'CaseType', component: () => import('../views/CaseTypePicker.vue'), meta: { title: '选择案件类型' } }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  if (!to.meta.public && !localStorage.getItem('cf_token')) {
    return { path: '/login' }
  }
  if (to.meta.public) {
    return true
  }

  const role = storedRole()
  // 普通民警：不在白名单里的一律回落「我的案件」。
  // 后端接口另有数据范围强制收敛，这里只是体验层，不留白屏和看不懂的页面。
  if (!isFullAccessRole(role) && !STAFF_PAGES.includes(firstSeg(to.path))) {
    return { path: '/my-cases' }
  }
  // 整页级权限：普通民警手敲 /users 也不让进（后端接口另有 403 兜底）
  if (to.meta.fullAccessOnly && !isFullAccessRole(role)) {
    return { path: homePathOf(role) }
  }

  // ---- 案件类型门控（2026-10 统一入口）----
  // 守卫阶段拿不到 Pinia 实例（应用尚未挂载），直接读 localStorage；
  // store 初始化时读的是同一个键，两边口径一致。
  const seg = firstSeg(to.path)
  if (GATED_PATHS.includes(seg) && !localStorage.getItem('cf_case_type')) {
    // 带上from 便于选完类型后跳回原栏目，而不是一律回案件管理
    return { path: '/case-type', query: { from: to.fullPath } }
  }
  return true
})

export default router
