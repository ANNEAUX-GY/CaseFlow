<template>
  <div class="cf-page">
    <div class="cf-panel">
      <div class="cf-panel__head">
        <span>账号管理</span>
        <span class="cf-panel__head-tip">
          自行注册的账号需在此审核通过后才能登录；只有所长、副所长、法制员可管理账号
        </span>
        <div class="cf-panel__head-actions">
          <el-button type="primary" @click="openCreate">新建账号</el-button>
        </div>
      </div>

      <div class="cf-panel__body">
        <div class="cf-toolbar" style="margin-bottom: 12px">
          <el-radio-group v-model="tab" @change="load">
            <el-radio-button value="pending">
              待审核<span v-if="pendingCount" class="cf-badge">{{ pendingCount }}</span>
            </el-radio-button>
            <el-radio-button value="all">全部账号</el-radio-button>
          </el-radio-group>
          <el-input
            v-model="keyword"
            placeholder="搜索登录名 / 姓名 / 手机号"
            clearable
            style="width: 260px"
            @keyup.enter="load"
            @clear="load"
          />
          <el-button @click="load">查询</el-button>
        </div>

        <el-table :data="rows" v-loading="loading" stripe>
          <el-table-column prop="username" label="登录名" width="140" />
          <el-table-column prop="displayName" label="姓名" width="110" />
          <el-table-column label="角色" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.auditStatus === 0" type="warning" size="small">
                申请：{{ row.applyRoleName || '—' }}
              </el-tag>
              <el-tag v-else :type="row.fullAccess ? 'danger' : 'info'" size="small">
                {{ row.roleName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="dept" label="部门" min-width="140" show-overflow-tooltip />
          <el-table-column prop="phone" label="手机号" width="130" />
          <el-table-column label="关联员工" width="150">
            <template #default="{ row }">
              <template v-if="row.employeeName">
                <span>{{ row.employeeName }}</span>
                <el-tag
                  v-if="row.employeeOrigin === 'SELF_REGISTER'"
                  type="warning"
                  size="small"
                  effect="plain"
                  style="margin-left: 4px"
                >
                  自建
                </el-tag>
              </template>
              <!-- 账号必须绑员工，未关联说明是升级前的老账号，需要补绑 -->
              <el-tag v-else type="danger" size="small" effect="plain">未关联</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.auditStatus === 0" type="warning" size="small">待审核</el-tag>
              <el-tag v-else-if="row.auditStatus === 2" type="info" size="small">已驳回</el-tag>
              <el-tag v-else-if="row.status === 1" type="success" size="small">正常</el-tag>
              <el-tag v-else type="danger" size="small">已停用</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="注册时间" width="160" />
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <template v-if="row.auditStatus === 0">
                <el-button link type="primary" @click="openApprove(row)">通过</el-button>
                <el-button link type="danger" @click="openReject(row)">驳回</el-button>
              </template>
              <template v-else>
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
                <el-button link type="warning" @click="toggleStatus(row)">
                  {{ row.status === 1 ? '停用' : '启用' }}
                </el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </template>
            </template>
          </el-table-column>
          <template #empty>
            <span class="cf-muted">{{ tab === 'pending' ? '没有待审核的注册申请' : '暂无账号' }}</span>
          </template>
        </el-table>
      </div>
    </div>

    <!-- 审核通过 / 编辑 / 新建 共用一个表单弹窗 -->
    <el-dialog v-model="dlg.visible" :title="dlg.title" width="480px">
      <el-form :model="dlg.form" label-width="90px">
        <el-form-item v-if="dlg.mode === 'create'" label="登录名" required>
          <el-input v-model="dlg.form.username" placeholder="至少 3 个字符" />
        </el-form-item>
        <el-form-item v-if="dlg.mode === 'create'" label="初始密码" required>
          <el-input v-model="dlg.form.password" placeholder="至少 6 位" show-password />
        </el-form-item>

        <el-form-item v-if="dlg.mode === 'approve'" label="申请人">
          <span>{{ dlg.row.displayName }}（{{ dlg.row.username }}）</span>
        </el-form-item>

        <el-form-item label="姓名">
          <el-input v-model="dlg.form.displayName" :disabled="dlg.mode === 'approve'" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="dlg.form.role" style="width: 100%">
            <el-option v-for="(name, code) in roles" :key="code" :label="name" :value="code" />
          </el-select>
          <div class="cf-form-tip">
            所长 / 副所长 / 法制员拥有全部权限（指派、员工维护、账号管理、撤回）
          </div>
        </el-form-item>
        <!-- 账号必须落到一名员工身上：案件按员工 ID 判定归属，没绑定就看不到自己的案件 -->
        <el-form-item label="关联员工" required>
          <div class="cf-bind">
            <el-alert
              v-if="dlg.mode === 'approve' && dlg.row.employeeOrigin === 'SELF_REGISTER'"
              type="warning"
              :closable="false"
              show-icon
              class="cf-bind__alert"
              title="核对后再通过"
              description="该员工档案由申请人注册时自行建立，请核对姓名与部门无误后再通过"
            />
            <el-select
              v-model="dlg.form.employeeId"
              filterable
              remote
              clearable
              reserve-keyword
              :remote-method="searchEmployee"
              placeholder="按姓名 / 工号 / 部门检索（已被其他账号占用的不会出现）"
              style="width: 100%"
              @change="onPickEmployee"
            >
              <el-option
                v-for="e in employeeOptions"
                :key="e.id"
                :label="`${e.name}${e.dept ? ' · ' + e.dept : ''}`"
                :value="e.id"
              />
              <template #empty>
                <div class="cf-bind__empty">
                  <span>没有可选员工</span>
                </div>
              </template>
            </el-select>

            <div class="cf-bind__tip">
              <span class="cf-form-tip">
                关联后该账号登录即可看到与自己相关的案件；一名员工只能绑定一个账号。
              </span>
              <el-button link type="primary" @click="toggleNewEmployee">
                {{ dlg.newEmpOpen ? '收起' : '＋ 新建员工档案' }}
              </el-button>
            </div>

            <div v-if="dlg.form.newEmployee" class="cf-bind__new">
              <el-tag type="warning" size="small" effect="plain">待新建</el-tag>
              <span class="cf-bind__new-name">
                {{ dlg.form.newEmployee.name }}
                <span class="cf-muted">
                  {{ dlg.form.newEmployee.dept || '未填部门' }}
                  <template v-if="dlg.form.newEmployee.title"> · {{ dlg.form.newEmployee.title }}</template>
                </span>
              </span>
              <el-button link type="danger" @click="dlg.form.newEmployee = null">取消</el-button>
            </div>

            <!-- 就地建档：不必跳出弹窗先去员工图谱建人 -->
            <div v-if="dlg.newEmpOpen" class="cf-bind__form">
              <el-form :model="dlg.newEmp" label-width="70px" size="small">
                <el-form-item label="姓名" required>
                  <el-input v-model="dlg.newEmp.name" placeholder="真实姓名" maxlength="64" />
                </el-form-item>
                <el-form-item label="部门">
                  <el-select
                    v-model="dlg.newEmp.dept"
                    filterable
                    allow-create
                    clearable
                    default-first-option
                    placeholder="从已有部门中选择"
                    style="width: 100%"
                  >
                    <el-option
                      v-for="d in deptOptions"
                      :key="d.name"
                      :label="`${d.name}（${d.count}人）`"
                      :value="d.name"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="职务">
                  <el-select v-model="dlg.newEmp.title" clearable placeholder="选填" style="width: 100%">
                    <el-option v-for="t in TITLES" :key="t" :label="t" :value="t" />
                  </el-select>
                </el-form-item>
                <el-form-item label="工号">
                  <el-input v-model="dlg.newEmp.employeeNo" placeholder="选填" maxlength="64" />
                </el-form-item>
                <el-form-item label="">
                  <el-button type="primary" size="small" @click="saveNewEmployee">加入待建立档案</el-button>
                  <el-button size="small" @click="dlg.newEmpOpen = false">取消</el-button>
                </el-form-item>
              </el-form>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="dlg.form.phone" />
        </el-form-item>
        <el-form-item label="部门">
          <el-select
            v-model="dlg.form.dept"
            filterable
            allow-create
            clearable
            default-first-option
            placeholder="从已有部门中选择"
            style="width: 100%"
          >
            <el-option
              v-for="d in deptOptions"
              :key="d.name"
              :label="`${d.name}（${d.count}人）`"
              :value="d.name"
            />
          </el-select>
        </el-form-item>
  <!-- 办案组别（2026-10-04）：只对普通民警有意义，管理层/领导不参与一线分工。
       管理员在这里可帮申请人纠正填错的组别。 -->
        <el-form-item label="办案组别" v-if="dlg.form.role === 'STAFF'">
          <el-radio-group v-model="dlg.form.policeGroup" style="width: 100%">
       <el-radio-button value="INITIAL">初查组</el-radio-button>
      <el-radio-button value="CLEAR">清案组</el-radio-button>
            <el-radio-button value="NONE">不限</el-radio-button>
</el-radio-group>
   <div class="cf-muted" style="font-size: 12px; margin-top: 4px">
            {{ groupHint }}
          </div>
        </el-form-item>
        <el-form-item v-if="dlg.mode !== 'create'" label="备注">
     <el-input v-model="dlg.form.remark" placeholder="审核意见（选填）" />
     </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="dlg.loading" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 驳回 -->
    <el-dialog v-model="rejectDlg.visible" title="驳回注册申请" width="440px">
      <el-form label-width="90px">
        <el-form-item label="申请人">
          <span>{{ rejectDlg.row.displayName }}（{{ rejectDlg.row.username }}）</span>
        </el-form-item>
        <el-form-item label="驳回原因">
          <el-input v-model="rejectDlg.remark" type="textarea" :rows="3" placeholder="将展示给申请人" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDlg.visible = false">取消</el-button>
        <el-button type="danger" :loading="rejectDlg.loading" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetDlg.visible" title="重置密码" width="440px">
      <el-form label-width="90px">
        <el-form-item label="账号">
          <span>{{ resetDlg.row.displayName }}（{{ resetDlg.row.username }}）</span>
        </el-form-item>
        <el-form-item label="新密码" required>
          <el-input v-model="resetDlg.password" placeholder="至少 6 位" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="resetDlg.loading" @click="submitReset">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authApi, userApi, employeeApi } from '../api'
import { useUserStore } from '../store/user'
import { usePendingStore } from '../store/pending'

const userStore = useUserStore()
// 手机端：账号表格改卡片、弹窗全屏

// 待审核数与侧栏红点共用同一份状态：这里审批完，侧栏红点会一起消失
const pendingStore = usePendingStore()
const pendingCount = computed(() => pendingStore.count)

const tab = ref('pending')
const keyword = ref('')
const rows = ref([])
const loading = ref(false)
const roles = ref({})
const employeeOptions = ref([])
/** 已有部门（部门下拉的选项）：从已有里选，避免同部门被填成好几种写法 */
const deptOptions = ref([])
const loadDepts = async () => {
  try {
    deptOptions.value = await employeeApi.depts()
  } catch (e) {
    deptOptions.value = []
  }
}

const load = async () => {
  loading.value = true
  try {
    rows.value = await userApi.list({
      keyword: keyword.value || undefined,
      auditStatus: tab.value === 'pending' ? 0 : undefined
    })
    // 走后端重新计数（而不是本地 -1）：并发审批、驳回、停用等场景下本地计数会算错
    await pendingStore.refresh()
    // 审核通过时可能刚建了员工档案，部门清单会变，跟着刷一次
    await loadDepts()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    roles.value = await authApi.roles()
  } catch (e) {
    roles.value = { STAFF: '普通民警' }
  }
  await loadDepts()
  await load()
})

const TITLES = ['领导', '副领导', '组长', '组员']

/* ---------- 通过 / 编辑 / 新建 ---------- */
const dlg = reactive({
  visible: false,
  mode: 'approve',
  title: '',
  loading: false,
  row: {},
  form: {},
  // 就地建档的小表单
  newEmpOpen: false,
  newEmp: { name: '', dept: '', title: '', employeeNo: '' }
})

const resetNewEmp = () => {
  dlg.newEmpOpen = false
  dlg.newEmp = { name: '', dept: '', title: '', employeeNo: '' }
}

const resetForm = () => ({
  username: '',
  password: '',
  displayName: '',
  role: 'STAFF',
  employeeId: null,
  newEmployee: null,
  phone: '',
  dept: '',
  remark: ''
})

/**
 * 关联员工下拉：后端只返回「还没被别的账号占用」的员工，
 * 编辑时把自己排除掉，否则当前已绑的那个人会被过滤掉看不见。
 */
const searchEmployee = async (kw) => {
  employeeOptions.value = await userApi.bindableEmployees({
    keyword: kw || undefined,
    limit: 30,
    excludeUserId: dlg.mode === 'edit' || dlg.mode === 'approve' ? dlg.row.id : undefined
  })
}

// 把当前已绑定的员工塞进候选，保证回显时能显示出名字
const withCurrentEmployee = (row) => {
  if (row && row.employeeId && row.employeeName) {
    return [{ id: row.employeeId, name: row.employeeName, dept: row.employeeDept }, ...employeeOptions.value]
  }
  return employeeOptions.value
}

const onPickEmployee = () => {
  if (dlg.form.employeeId) dlg.form.newEmployee = null
}

const toggleNewEmployee = () => {
  dlg.newEmpOpen = !dlg.newEmpOpen
  if (dlg.newEmpOpen) {
    dlg.newEmp = {
      name: dlg.form.displayName || '',
      dept: dlg.form.dept || '',
      title: '组员',
      employeeNo: ''
    }
  }
}

const saveNewEmployee = () => {
  const name = (dlg.newEmp.name || '').trim()
  if (!name) {
    ElMessage.warning('请填写员工姓名')
    return
  }
  dlg.form.employeeId = null
  dlg.form.newEmployee = {
    name,
    dept: (dlg.newEmp.dept || '').trim() || null,
    title: dlg.newEmp.title || null,
    employeeNo: (dlg.newEmp.employeeNo || '').trim() || null
  }
  dlg.newEmpOpen = false
}

const openApprove = async (row) => {
  dlg.mode = 'approve'
  dlg.title = '审核通过'
  dlg.row = row
  resetNewEmp()
  dlg.form = {
    ...resetForm(),
    displayName: row.displayName,
    role: row.applyRole || 'STAFF',
    // 沿用申请人注册时认领的档案；管理员可以在下拉里改绑
    employeeId: row.employeeId || null,
    phone: row.phone || '',
    dept: row.dept || ''
  }
  employeeOptions.value = []
  dlg.visible = true
  // 先在员工图谱里检索一次，把申请人认领的那份档案回显出来
  await searchEmployee('')
  employeeOptions.value = withCurrentEmployee(row)
}

const openEdit = async (row) => {
  dlg.mode = 'edit'
  dlg.title = '编辑账号'
  dlg.row = row
  resetNewEmp()
  dlg.form = {
    ...resetForm(),
    displayName: row.displayName || '',
    role: row.role || 'STAFF',
    employeeId: row.employeeId || null,
    phone: row.phone || '',
    dept: row.dept || ''
  }
  employeeOptions.value = []
  dlg.visible = true
  await searchEmployee('')
  employeeOptions.value = withCurrentEmployee(row)
}

const openCreate = () => {
  dlg.mode = 'create'
  dlg.title = '新建账号（免审核）'
  dlg.row = {}
  resetNewEmp()
  dlg.form = resetForm()
  employeeOptions.value = []
  dlg.visible = true
}

const groupHint = computed(() => {
  if (dlg.form.policeGroup === 'INITIAL') return '只能承接初查任务'
  if (dlg.form.policeGroup === 'CLEAR') return '只能承接刑拘在办案件'
  return '可承接各类案件（不受组别限制）'
})

const submit = async () => {
  // 普通民警必须定组别：组别决定他能接哪类案件，指派时要按它校验
  if (dlg.form.role === 'STAFF' && !dlg.form.policeGroup) {
    ElMessage.warning('请选择办案组别')
    return
  }
  if (!dlg.form.role) {
    ElMessage.warning('请选择角色')
    return
  }
  if (dlg.mode === 'create') {
    if (!dlg.form.username || dlg.form.username.trim().length < 3) {
      ElMessage.warning('登录名至少 3 个字符')
      return
    }
    if (!dlg.form.password || dlg.form.password.length < 6) {
      ElMessage.warning('密码至少 6 位')
      return
    }
  }
  // 账号必须关联员工：新建和审核通过都卡在这里，编辑时留空表示不改绑
  if (dlg.mode !== 'edit' && !dlg.form.employeeId && !dlg.form.newEmployee) {
    ElMessage.warning('请为账号关联一名员工：在「关联员工」中检索选择，或点「新建员工档案」现场建立')
    return
  }
  dlg.loading = true
  try {
    if (dlg.mode === 'approve') {
      await userApi.approve(dlg.row.id, dlg.form)
      ElMessage.success('已审核通过')
    } else if (dlg.mode === 'edit') {
      await userApi.update(dlg.row.id, dlg.form)
      ElMessage.success('已保存')
    } else {
      await userApi.create(dlg.form)
      ElMessage.success('账号已创建')
    }
    dlg.visible = false
    await load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    dlg.loading = false
  }
}

/* ---------- 驳回 ---------- */
const rejectDlg = reactive({ visible: false, loading: false, row: {}, remark: '' })

const openReject = (row) => {
  rejectDlg.row = row
  rejectDlg.remark = ''
  rejectDlg.visible = true
}

const submitReject = async () => {
  rejectDlg.loading = true
  try {
    await userApi.reject(rejectDlg.row.id, { remark: rejectDlg.remark })
    ElMessage.success('已驳回')
    rejectDlg.visible = false
    await load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    rejectDlg.loading = false
  }
}

/* ---------- 重置密码 ---------- */
const resetDlg = reactive({ visible: false, loading: false, row: {}, password: '' })

const openReset = (row) => {
  resetDlg.row = row
  resetDlg.password = ''
  resetDlg.visible = true
}

const submitReset = async () => {
  if (!resetDlg.password || resetDlg.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  resetDlg.loading = true
  try {
    await userApi.resetPassword(resetDlg.row.id, { password: resetDlg.password })
    ElMessage.success('密码已重置')
    resetDlg.visible = false
  } catch (e) {
    // 拦截器已提示
  } finally {
    resetDlg.loading = false
  }
}

/* ---------- 启停 / 删除 ---------- */
const toggleStatus = async (row) => {
  const next = row.status === 1 ? 0 : 1
  try {
    await ElMessageBox.confirm(
      next === 0
        ? `确定停用「${row.displayName}」吗？停用后该账号将无法登录。`
        : `确定启用「${row.displayName}」吗？`,
      next === 0 ? '停用账号' : '启用账号',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await userApi.changeStatus(row.id, next)
    ElMessage.success(next === 1 ? '已启用' : '已停用')
    await load()
  } catch (e) {
    // 拦截器已提示
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除账号「${row.displayName}（${row.username}）」吗？此操作不可恢复。`,
      '删除账号',
      { type: 'warning', confirmButtonText: '删除' }
    )
  } catch (e) {
    return
  }
  try {
    await userApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    // 拦截器已提示
  }
}
</script>
