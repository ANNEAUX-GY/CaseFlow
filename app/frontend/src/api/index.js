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
  dashboard: (params) => http.get('/cases/dashboard', { params })
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
  board: () => http.get('/watch/board'),
  plans: (caseId) => http.get(`/watch/cases/${caseId}/plans`),
  addPlan: (caseId, data) => http.post(`/watch/cases/${caseId}/plans`, data),
  updatePlan: (id, data) => http.put(`/watch/plans/${id}`, data),
  donePlan: (id, doneNote) => http.post(`/watch/plans/${id}/done`, { doneNote }),
  cancelPlan: (id) => http.post(`/watch/plans/${id}/cancel`),
  transition: (caseId, data) => http.post(`/watch/cases/${caseId}/transition`, data),
  measure: (caseId, data) => http.post(`/watch/cases/${caseId}/measure`, data),
  approvals: (caseId) => http.get(`/watch/cases/${caseId}/approvals`),
  // ---- 办理进度批注（写=管理层，读=所有能看案件的人） ----
  comments: (caseId) => http.get(`/watch/cases/${caseId}/comments`),
  addComment: (logId, content) => http.post(`/watch/logs/${logId}/comments`, { content }),
  updateComment: (id, content) => http.put(`/watch/comments/${id}`, { content }),
  removeComment: (id) => http.delete(`/watch/comments/${id}`),
  // ---- 领导意见与落实反馈（提=管理层，反馈=本案办案人） ----
  opinions: (caseId) => http.get(`/watch/cases/${caseId}/opinions`),
  addOpinion: (caseId, content) => http.post(`/watch/cases/${caseId}/opinions`, { content }),
  feedbackOpinion: (id, data) => http.post(`/watch/opinions/${id}/feedback`, data)
}

export const logApi = {
  page: (params) => http.get('/logs', { params }),
  recent: (params) => http.get('/logs/recent', { params }),
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
  overview: (params) => http.get('/todos/overview', { params }),
  summary: () => http.get('/todos/overview/summary')
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
