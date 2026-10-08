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

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      if (body.code === 401) {
        localStorage.removeItem('cf_token')
        localStorage.removeItem('cf_user')
        if (location.hash !== '#/login') {
          location.href = '#/login'
        }
      }
      ElMessage.error(body.msg || '操作失败')
      return Promise.reject(new Error(body.msg || '操作失败'))
    }
    return body
  },
  (err) => {
    // 403 = 角色权限不足（后端 @FullAccessOnly 拦截），提示语更具体一些
    if (err?.response?.status === 403) {
      const msg = err.response?.data?.msg || '当前角色没有该操作权限'
      ElMessage.error(msg)
      return Promise.reject(new Error(msg))
    }
    ElMessage.error(err?.message || '网络异常')
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
  info: () => http.get('/auth/info'),
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
  /** 提交反馈（累积一条，不改状态）。data: { status: DONE|IN_PROGRESS|NOT_DONE, content } */
  addFeedback: (todoId, data) => http.post(`/todos/${todoId}/feedbacks`, data),
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
