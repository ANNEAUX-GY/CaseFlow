import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 30000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('cf_token')
  if (token) {
    config.headers['X-Token'] = token
  }
  return config
})

/**
 * 登录态失效（401）的统一处理。
 *
 * <p>为什么必须集中处理、而且**只提示一次**：
 * 后端令牌是内存态的（`TokenStore`），后端一重启全部失效，而浏览器 localStorage 里
 * 那份 `cf_token` 还在。此时路由守卫只看"有没有 token"就放行，工作台一进去就并发打
 * 十几个请求，**每个都 401** —— 用户看到的就是一屏「Request failed with status code 401」
 * 加满屏的 0，点谁都不好用，必须先退出登录再重新登录。
 * 所以在响应层把 401 收口：清登录态、清掉堆出来的错误提示、回登录页，只留一句人话提示。
 */
let unauthorizedHandled = false

const unauthorizedError = (msg) => {
  const e = new Error(msg || '登录已过期，请重新登录')
  e.unauthorized = true
  e.response = { status: 401 }
  return e
}

/**
 * 同一句话在短时间内只弹一次。
 *
 * <p>页面渲染时是**并发**发请求的（工作台一次就是十几个），后端一旦整体不可用
 * （401 / 网络不通 / 服务没起来），每个请求都会各弹一次 —— 不去重就是一屏重复的红条，
 * 看着像"页面崩了"，其实只有一件事。这里按文案+时间窗去重，只把话说一遍。
 */
const lastToast = { msg: '', at: 0 }
const toastOnce = (msg, level = 'error') => {
  const now = Date.now()
  if (lastToast.msg === msg && now - lastToast.at < 3000) {
    return
  }
  lastToast.msg = msg
  lastToast.at = now
  ElMessage[level](msg)
}

/**
 * @param {object} opts
 * @param {boolean} opts.silent 静默模式（`config.silent = true` 的请求）：
 *        只清登录态，不弹提示、不自己跳转 —— 交给调用方决定怎么落地。
 *        路由守卫的启动探测就用这个：它自己会带着 query 跳登录页并在页面上说明原因，
 *        不需要拦截器抢着弹一句 toast、再抢着改 hash。
 */
const handleUnauthorized = (opts = {}) => {
  const silent = !!opts.silent
  localStorage.removeItem('cf_token')
  localStorage.removeItem('cf_user')
  if (silent) return
  if (unauthorizedHandled) return
  unauthorizedHandled = true
  // 并发请求会同时撞 401，先清掉已经弹出来的一堆红条，再只提示一句
  ElMessage.closeAll()
  toastOnce('登录已过期，请重新登录', 'warning')
  if (location.hash !== '#/login') {
    location.href = '#/login'
  }
  // 复位，便于重新登录后再次失效时还能提示
  setTimeout(() => { unauthorizedHandled = false }, 3000)
}

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      if (body.code === 401) {
        handleUnauthorized({ silent: res.config?.silent })
        return Promise.reject(unauthorizedError(body.msg))
      }
      if (!res.config?.silent) {
        toastOnce(body.msg || '操作失败')
      }
      return Promise.reject(new Error(body.msg || '操作失败'))
    }
    return body
  },
  (err) => {
    const status = err?.response?.status
    // 401（HTTP 态）= 未登录 / 登录已过期（后端 LoginInterceptor 直接回 401）。
    // 早先这里没接，401 落到最下面的兜底分支，只弹一句英文报错、不清登录态也不跳登录页，
    // 于是页面就"半死不活"地停在工作台上——这就是「每次启动都要先退出再登录」的根因。
    if (status === 401) {
      handleUnauthorized({ silent: err.config?.silent })
      return Promise.reject(unauthorizedError(err.response?.data?.msg))
    }
    if (err.config?.silent) {
      return Promise.reject(err)
    }
    // 403 = 角色权限不足（后端 @FullAccessOnly 拦截），提示语更具体一些
    if (status === 403) {
      const msg = err.response?.data?.msg || '当前角色没有该操作权限'
      toastOnce(msg)
      return Promise.reject(new Error(msg))
    }
    // 网络层失败（后端没起来 / 断网）：同样只提示一次。
    // 之前是每个失败请求弹一次，后端没就绪时页面会瞬间铺满"网络异常"。
    const netMsg = err?.response
      ? (err.message || '请求失败')
      : '无法连接服务，请确认后端已启动'
    toastOnce(netMsg)
    return Promise.reject(err)
  }
)

export default http

export const authApi = {
  login: (data) => http.post('/auth/login', data),
  register: (data) => http.post('/auth/register', data),
  roles: () => http.get('/auth/roles'),
  // 注册页还没登录，认领员工档案只能走这个公开接口（只回传姓名/部门/链路，不含联系方式）
  registerEmployees: (params) => http.get('/auth/register/employees', { params }),
  // 注册页「部门」下拉的选项：组织架构里已存在的部门
  registerDepts: () => http.get('/auth/register/depts'),
  /** 当前登录人。传 { silent: true } 表示"只探测登录态"：失败不弹提示、不自动跳转（路由守卫用） */
  info: (config) => http.get('/auth/info', config),
  logout: () => http.post('/auth/logout'),
  dict: () => http.get('/auth/dict')
}

export const userApi = {
  list: (params) => http.get('/users', { params }),
  pendingCount: () => http.get('/users/pending-count'),
  // 关联员工下拉：后端已排除被其他账号占用的员工，编辑时传 excludeUserId 排除自己
  bindableEmployees: (params) => http.get('/users/bindable-employees', { params }),
  approve: (id, data) => http.post(`/users/${id}/approve`, data),
  reject: (id, data) => http.post(`/users/${id}/reject`, data),
  create: (data) => http.post('/users', data),
  update: (id, data) => http.put(`/users/${id}`, data),
  resetPassword: (id, data) => http.post(`/users/${id}/reset-password`, data),
  changeStatus: (id, status) => http.post(`/users/${id}/status`, { status }),
  remove: (id) => http.delete(`/users/${id}`)
}

export const caseApi = {
  page: (params) => http.get('/cases', { params }),
  detail: (id) => http.get(`/cases/${id}`),
  create: (data) => http.post('/cases', data),
  update: (id, data) => http.put(`/cases/${id}`, data),
  remove: (id) => http.delete(`/cases/${id}`),
  assign: (id, data) => http.post(`/cases/${id}/assign`, data),
  status: (id, data) => http.post(`/cases/${id}/status`, data),
  reminders: (params) => http.get('/cases/reminders', { params }),
  /** 一键重点关注（2026-10-09）：focus=1 标注 / 0 取消，列表里直接点星，不开详情 */
  focus: (id, focus) => http.post(`/cases/${id}/focus`, { focus: focus ? 1 : 0 }),
  stats: (params) => http.get('/cases/stats', { params }),
  dashboard: (params) => http.get('/cases/dashboard', { params }),
  /** 民警承办负荷详情（点主办人/协办人时用）；caseId 传入会标记 isCurrent */
  staffWorkload: (employeeId, caseId) => http.get(`/cases/staff/${employeeId}/workload`, { params: { caseId } })
}

export const suspectApi = {
  list: (caseId) => http.get(`/cases/${caseId}/suspects`),
  add: (caseId, data) => http.post(`/cases/${caseId}/suspects`, data),
  update: (id, data) => http.put(`/cases/suspects/${id}`, data),
  remove: (id) => http.delete(`/cases/suspects/${id}`)
}

export const categoryApi = {
  tree: () => http.get('/case-categories/tree'),
  list: () => http.get('/case-categories'),
  add: (data) => http.post('/case-categories', data),
  update: (id, data) => http.put(`/case-categories/${id}`, data),
  remove: (id) => http.delete(`/case-categories/${id}`)
}

export const watchApi = {
  cases: (params) => http.get('/watch/cases', { params }),
  board: (params) => http.get('/watch/board', { params }),
  /** 侦查进度流转：action = START/SUBMIT/APPROVE/REJECT（盯办抽屉顶部按钮） */
  transition: (caseId, data) => http.post(`/watch/cases/${caseId}/transition`, data),
  /** 强制措施登记 / 变更（管理层） */
  measure: (caseId, data) => http.post(`/watch/cases/${caseId}/measure`, data),
  // ---- 办理进度批注（写=管理层，读=所有能看案件的人） ----
  comments: (caseId) => http.get(`/watch/cases/${caseId}/comments`),
  addComment: (logId, content) => http.post(`/watch/logs/${logId}/comments`, { content }),
  updateComment: (id, content) => http.put(`/watch/comments/${id}`, { content }),
  removeComment: (id) => http.delete(`/watch/comments/${id}`),
  // ---- 领导意见（合并面板用：提意见/定级/移除；落实状态由待办反馈单向同步，不再单独反馈） ----
  opinions: (caseId) => http.get(`/watch/cases/${caseId}/opinions`),
  /** 新增意见。deadline 可空（'' 或 null），importance 缺省由后端落C */
  addOpinion: (caseId, data) => http.post(`/watch/cases/${caseId}/opinions`,
    typeof data === 'string' ? { content: data } : data),
  /**
   * 批量新增意见（一次多条，整批一个事务）。
   * items: [{ content, deadline, importance }]，空内容的行由后端跳过。
   */
  addOpinions: (caseId, items) => http.post(`/watch/cases/${caseId}/opinions/batch`, { items }),
  /** 修改截止时间与重要性（仅管理层）；deadline 传 '' 即清空 */
  updateOpinionMeta: (id, data) => http.post(`/watch/opinions/${id}/meta`, data),
  /** 移除意见（仅管理层）；后端软删并连带清理派生待办 */
  removeOpinion: (id) => http.post(`/watch/opinions/${id}/remove`),

  // ---- 意见收件箱（2026-10-04，邮件式新增领导意见） ----
  /** 我的未读意见（与欢迎弹窗数字同口径） */
  unreadOpinions: () => http.get('/watch/opinions/unread'),
  /** 点开一条即标已读（幂等） */
  markOpinionRead: (id) => http.post(`/watch/opinions/${id}/read`),
  /** 全部标为已读 */
  markAllOpinionsRead: () => http.post('/watch/opinions/read-all')
}

export const logApi = {
  page: (params) => http.get('/logs', { params }),
  recent: (params) => http.get('/logs/recent', { params }),
  /** 按业务类型取最近日志（type=case 案件相关 / other 其他操作），工作台页签用 */
  recentByType: (type, limit = 20) => http.get('/logs/recent-by-type', { params: { type, limit } }),
  detail: (id) => http.get(`/logs/${id}`),
  caseLogs: (caseId) => http.get(`/logs/case/${caseId}`),
  undo: (id) => http.post(`/logs/${id}/undo`),
  undoLatest: () => http.post('/logs/undo-latest')
}

export const employeeApi = {
  tree: (params) => http.get('/employees/tree', { params }),
  search: (params) => http.get('/employees/search', { params }),
  /** 已有部门清单（部门下拉的选项，带人数） */
  depts: () => http.get('/employees/depts'),
  detail: (id) => http.get(`/employees/${id}`),
  create: (data) => http.post('/employees', data),
  update: (id, data) => http.put(`/employees/${id}`, data),
  remove: (id) => http.delete(`/employees/${id}`),
  importExcel: (formData) =>
    http.post('/employees/import', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
  templateUrl: () => `${import.meta.env.VITE_API_BASE || '/api'}/employees/template`
}

/** 疑问问答（独立于待办与任务）：员工提问、管理层回答 */
export const questionApi = {
  listOfCase: (caseId) => http.get(`/questions/case/${caseId}`),
  ask: (caseId, todoId, content) => http.post('/questions', { caseId, todoId, content }),
  answer: (id, content) => http.post(`/questions/${id}/answer`, { content }),
  update: (id, content) => http.put(`/questions/${id}`, { content }),
  /** 修订已给出的回答（仅管理层，答错了要能改） */
  updateAnswer: (id, content) => http.put(`/questions/${id}/answer`, { content }),
  remove: (id) => http.delete(`/questions/${id}`)
}

/** 统一信箱（2026-10-04）：与自己有关的操作变更归集 */
export const notificationApi = {
  unread: () => http.get('/notifications/unread'),
  unreadCount: () => http.get('/notifications/unread-count'),
  /** 信箱列表（新→旧）：box=unread|read|all，已读历史从这里取 */
  list: (box) => http.get('/notifications/list', { params: { box } }),
  /** 点开一条即标已读（幂等）。注意 URL 不能省——此前写成 http.post() 漏了地址，
   *  标已读从未真正落到服务端，信件重开又变未读（2026-10-06 修复） */
  markRead: (id) => http.post(`/notifications/${id}/read`),
  markAllRead: () => http.post('/notifications/read-all')
}

export const todoApi = {
  listOfCase: (caseId) => http.get(`/todos/case/${caseId}`),
  add: (caseId, data) => http.post(`/todos/case/${caseId}`, data),
  update: (id, data) => http.put(`/todos/${id}`, data),
  remove: (id) => http.delete(`/todos/${id}`),
  reorder: (caseId, ids) => http.post(`/todos/case/${caseId}/reorder`, { caseId, ids }),
  done: (id, remark) => http.post(`/todos/${id}/done`, { remark }),
  reopen: (id) => http.post(`/todos/${id}/reopen`),
  evidence: (todoId) => http.get(`/todos/${todoId}/evidence`),
  uploadEvidence: (todoId, formData) =>
    http.post(`/todos/${todoId}/evidence`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    }),
  rules: () => http.get('/todos/rules'),
  // ---- 任务详情 / 子任务 / 反馈（2026-10-04）----
  /** 一次取主任务 + 子任务 + 全部反馈记录 */
  detail: (todoId) => http.get(`/todos/${todoId}/detail`),
  /** 添加子任务（普通用户与管理员均可） */
  addSubtask: (todoId, content) => http.post(`/todos/${todoId}/subtasks`, { content }),
  /** 提交反馈（累积一条，不改状态）。data: { status, content, uploadTime, uploadPlatform, uploadFile } */
  addFeedback: (todoId, data) => http.post(`/todos/${todoId}/feedbacks`, data),
  /**
   * 修改一条反馈（2026-10-08）：能改落实说明、上传平台、上传文件名、时间。
   * 权限 = 提交人本人或管理层（后端校验）。
   */
  updateFeedback: (todoId, feedbackId, data) => http.put(`/todos/${todoId}/feedbacks/${feedbackId}`, data),
  /** 删除子任务（普通用户与管理员均可） */
  removeSubtask: (subId) => http.delete(`/todos/${subId}`),
  /** 勾选/撤销子任务完成 */
  toggleSubtask: (todoId, done) => http.post(`/todos/${todoId}/subtasks/toggle?done=${done}`),
  overview: (params) => http.get('/todos/overview', { params }),
  summary: (params) => http.get('/todos/overview/summary', { params }),
  // ---- 民警端待办（2026-10-04）----
  /** 本人待办列表。sortBy 支持多字段组合，如 'urgency,importance' */
  myTodos: (params) => http.get('/todos/mine', { params }),
  /** 民警调整待办的紧急/重点程度 */
  updateMyTodoGrade: (id, data) => http.post(`/todos/mine/${id}/grade`, data),
  /** 登录欢迎弹窗汇总：今日需完成 / 即将超期 / 新增领导意见 */
  welcomeSummary: () => http.get('/todos/welcome-summary')
}

export const fileApi = {
  upload: (formData, caseId) =>
    http.post(caseId ? `/files/upload?caseId=${caseId}` : '/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    }),
  downloadUrl: (id) => `${import.meta.env.VITE_API_BASE || '/api'}/files/${id}/download`,
  remove: (id) => http.delete(`/files/${id}`),
  listOfCase: (caseId) => http.get(`/files/case/${caseId}`)
}
