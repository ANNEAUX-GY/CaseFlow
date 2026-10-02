import { createRouter, createWebHashHistory } from 'vue-router'
import { isFullAccessRole } from '../store/user'

/**
 * 普通民警（非全权限角色）只能进这两个页面：内容都只与本人民下案件相关。
 * 用白名单而不是逐个页面加标记 —— 以后新增的页面默认就是管理层专属，不会漏配。
 */
const STAFF_PAGES = ['/my-cases', '/reminders']

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
      { path: 'dashboard', name: 'Dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '工作台' } },
      { path: 'cases', name: 'Cases', component: () => import('../views/CaseList.vue'), meta: { title: '案件管理' } },
      { path: 'reminders', name: 'Reminders', component: () => import('../views/Reminder.vue'), meta: { title: '到期提醒' } },
      { path: 'org', name: 'Org', component: () => import('../views/EmployeeTree.vue'), meta: { title: '员工图谱' } },
      // 案件盯办（初查/刑拘/取保监居 三子模块 + 看板）
      { path: 'watch', name: 'Watch', component: () => import('../views/WatchView.vue'), meta: { title: '案件盯办' } },
      // 案件待办总览：管理者查看各待办完成状态与对应佐证材料
      { path: 'todos', name: 'Todos', component: () => import('../views/TodoOverview.vue'), meta: { title: '待办总览', fullAccessOnly: true } },
      // 账号管理只对全权限角色开放；普通民警即使手敲地址，后端接口也会返回 403
      { path: 'users', name: 'Users', component: () => import('../views/UserManage.vue'), meta: { title: '账号管理', fullAccessOnly: true } },
      // 案件类别（小类）字典维护，管理权限专属
      { path: 'categories', name: 'Categories', component: () => import('../views/CategoryManage.vue'), meta: { title: '类别管理', fullAccessOnly: true } }
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
  return true
})

export default router
