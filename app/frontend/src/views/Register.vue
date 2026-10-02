<template>
  <div class="cf-login">
    <div class="cf-login__emblem">
      <svg viewBox="0 0 24 24" width="52" height="52" aria-hidden="true">
        <path d="M12 2l8 3v7c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V5l8-3z" fill="#1b4a8c" stroke="#c8a45c" stroke-width="1.2" />
        <path d="M12 7l1.6 3.3 3.6.5-2.6 2.6.6 3.6L12 15.6 8.8 17l.6-3.6L6.8 10.8l3.6-.5L12 7z" fill="#c8a45c" />
      </svg>
    </div>
    <div class="cf-login__brand">
      <h1>案件指派系统</h1>
      <p>PUBLIC SECURITY CASE ASSIGNMENT</p>
    </div>

    <div class="cf-login__box cf-register">
      <h2 class="cf-login__title">注册账号</h2>
      <div class="cf-register__notice">
        提交后需<strong>所长或法制员审核通过</strong>才能登录。
        账户密码将以加密形式保存在数据库中。
      </div>

      <!-- 手机端把标签挪到输入框上方：96px 的左侧标签在 375px 卡片里会吃掉三分之一 -->
      <el-form
        :model="form"
        size="large"
        :label-width="isMobile ? 'auto' : '96px'"
        :label-position="isMobile ? 'top' : 'left'"
      >
        <el-form-item label="手机号" required>
          <el-input v-model="form.phone" placeholder="11 位手机号，可用来登录" maxlength="11" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="form.password" type="password" placeholder="至少 6 位" show-password />
        </el-form-item>
        <el-form-item label="确认密码" required>
          <el-input v-model="form.confirm" type="password" placeholder="再输入一次" show-password />
        </el-form-item>
        <el-form-item label="真实姓名" required>
          <el-input v-model="form.displayName" placeholder="请填写姓名" maxlength="64" />
        </el-form-item>
        <el-form-item label="登录名">
          <el-input v-model="form.username" placeholder="可留空，默认用手机号登录" maxlength="64" />
        </el-form-item>
        <el-form-item label="所属部门">
          <el-input v-model="form.dept" placeholder="如：刑侦大队一中队（选填）" maxlength="128" />
        </el-form-item>
        <el-form-item label="申请角色">
          <el-select v-model="form.applyRole" style="width: 100%" placeholder="请选择">
            <el-option v-for="(name, code) in roles" :key="code" :label="name" :value="code" />
          </el-select>
        </el-form-item>

        <!-- 账号必须落到一名员工身上：案件是按员工 ID 判定归属的，没有绑定就看不到自己的案件 -->
        <el-form-item label="关联员工" required>
          <div class="cf-bind">
            <el-select
              v-model="form.employeeId"
              filterable
              remote
              clearable
              reserve-keyword
              :remote-method="searchEmployee"
              :loading="empLoading"
              placeholder="输入姓名 / 工号 / 部门，认领本人在组织架构中的档案"
              style="width: 100%"
              @change="onPickEmployee"
            >
              <el-option
                v-for="e in employeeOptions"
                :key="e.id"
                :label="`${e.name}${e.dept ? ' · ' + e.dept : ''}`"
                :value="e.id"
              >
                <span>{{ e.name }}</span>
                <span class="cf-muted"> {{ e.dept || '未填部门' }}</span>
              </el-option>
              <template #empty>
                <div class="cf-bind__empty">
                  <span>{{ empLoaded ? '没有匹配的员工档案' : '正在读取组织架构…' }}</span>
                  <el-button v-if="empLoaded" link type="primary" @click="openNewEmployee">立即新建档案</el-button>
                </div>
              </template>
            </el-select>

            <div class="cf-bind__tip">
              <span class="cf-form-tip">
                必须是<strong>本人</strong>的档案，一名员工只能绑定一个账号。
              </span>
              <el-button link type="primary" @click="openNewEmployee">
                {{ empLoaded && !employeeOptions.length ? '组织架构里还没有人，点此新建' : '找不到我的档案？新建一条' }}
              </el-button>
            </div>

            <!-- 现场建档的预览：提交注册时和后端一起创建，不会留下没人用的孤儿档案 -->
            <div v-if="form.newEmployee" class="cf-bind__new">
              <el-tag type="warning" size="small" effect="plain">待新建</el-tag>
              <span class="cf-bind__new-name">
                {{ form.newEmployee.name }}
                <span class="cf-muted">
                  {{ form.newEmployee.dept || '未填部门' }}
                  <template v-if="form.newEmployee.title"> · {{ form.newEmployee.title }}</template>
                </span>
              </span>
              <el-button link type="danger" @click="form.newEmployee = null">取消</el-button>
            </div>
          </div>
        </el-form-item>

        <el-button type="primary" style="width: 100%" :loading="loading" @click="onSubmit">提交注册</el-button>
      </el-form>

      <div class="cf-login__more">
        <span>已有账号？</span>
        <el-button link type="primary" @click="router.push('/login')">返回登录</el-button>
      </div>
    </div>

    <!-- 现场建档：组织架构里还没有这个人时，连人带号一起提交 -->
    <el-dialog
      v-model="empDlg.visible"
      title="新建员工档案"
      :width="isMobile ? '94%' : '480px'"
      :fullscreen="isMobile"
      append-to-body
      class="cf-bind-dlg"
    >
      <div class="cf-register__notice" style="margin-bottom: 12px">
        档案会随注册申请一并创建，审核通过后正式生效。
        上级不确定可以留空，管理员后续会在员工图谱里调整归属。
      </div>
      <el-form :model="empDlg.form" label-width="80px">
        <el-form-item label="姓名" required>
          <el-input v-model="empDlg.form.name" placeholder="本人的真实姓名" maxlength="64" />
        </el-form-item>
        <el-form-item label="工号">
          <el-input v-model="empDlg.form.employeeNo" placeholder="警号 / 工号（选填）" maxlength="64" />
        </el-form-item>
        <el-form-item label="部门">
          <el-input v-model="empDlg.form.dept" placeholder="如：刑侦大队一中队" maxlength="128" />
        </el-form-item>
        <el-form-item label="职务">
          <el-select v-model="empDlg.form.title" clearable placeholder="选填" style="width: 100%">
            <el-option v-for="t in TITLES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="上级">
          <el-select
            v-model="empDlg.form.parentId"
            filterable
            remote
            clearable
            reserve-keyword
            placeholder="检索上级（选填，留空为顶层）"
            :remote-method="searchParent"
            style="width: 100%"
          >
            <el-option
              v-for="e in parentOptions"
              :key="e.id"
              :label="`${e.name}${e.dept ? ' · ' + e.dept : ''}`"
              :value="e.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="empDlg.form.phone" placeholder="选填" maxlength="11" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="empDlg.visible = false">取消</el-button>
        <el-button type="primary" @click="saveNewEmployee">确定</el-button>
      </template>
    </el-dialog>

    <div class="cf-login__copyright">{{ FOOTER_BRAND.name }} · {{ FOOTER_BRAND.version }}</div>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authApi } from '../api'
import { FOOTER_BRAND } from '../config/slogans'
import { useDevice } from '../utils/device'

const { isMobile } = useDevice()
const router = useRouter()
const loading = ref(false)
const roles = ref({})

const TITLES = ['领导', '副领导', '组长', '组员']

const form = reactive({
  username: '',
  password: '',
  confirm: '',
  displayName: '',
  phone: '',
  dept: '',
  applyRole: 'STAFF',
  employeeId: null,
  newEmployee: null
})

/* ---------- 认领员工档案 ---------- */
const employeeOptions = ref([])
const empLoading = ref(false)
// 区分「还没查」和「查了但没有」：前者不能说"没有你的档案"，后者才引导去新建
const empLoaded = ref(false)

const searchEmployee = async (kw) => {
  empLoading.value = true
  try {
    employeeOptions.value = await authApi.registerEmployees({ keyword: kw || undefined, limit: 20 })
    empLoaded.value = true
  } catch (e) {
    employeeOptions.value = []
  } finally {
    empLoading.value = false
  }
}

// 选了已有档案就把「待新建」清掉：两者只能有一个生效，否则后端以 employeeId 优先
const onPickEmployee = () => {
  if (form.employeeId) form.newEmployee = null
}

/* ---------- 现场建档 ---------- */
const empDlg = reactive({
  visible: false,
  form: { name: '', employeeNo: '', dept: '', title: '', parentId: null, phone: '' }
})
const parentOptions = ref([])

const searchParent = async (kw) => {
  parentOptions.value = await authApi.registerEmployees({ keyword: kw || undefined, limit: 20 })
}

const openNewEmployee = () => {
  empDlg.form = {
    // 用已经填过的姓名/部门预填，少打一遍
    name: (form.displayName || '').trim(),
    employeeNo: '',
    dept: (form.dept || '').trim(),
    title: '组员',
    parentId: null,
    phone: (form.phone || '').trim()
  }
  empDlg.visible = true
  if (!parentOptions.value.length) searchParent('')
}

const saveNewEmployee = () => {
  const name = (empDlg.form.name || '').trim()
  if (!name) {
    ElMessage.warning('请填写员工姓名')
    return
  }
  form.employeeId = null
  form.newEmployee = {
    name,
    employeeNo: (empDlg.form.employeeNo || '').trim() || null,
    dept: (empDlg.form.dept || '').trim() || null,
    title: empDlg.form.title || null,
    parentId: empDlg.form.parentId || null,
    phone: (empDlg.form.phone || '').trim() || null
  }
  // 建档里填的姓名/部门回填到账号本身，两处保持一致
  if (!form.displayName) form.displayName = name
  if (!form.dept && form.newEmployee.dept) form.dept = form.newEmployee.dept
  empDlg.visible = false
}

onMounted(async () => {
  try {
    roles.value = await authApi.roles()
  } catch (e) {
    roles.value = { STAFF: '普通民警' }
  }
  // 预读一次组织架构：既能让用户直接看到自己，也能判断系统里到底有没有员工档案
  await searchEmployee('')
})

const onSubmit = async () => {
  const phone = (form.phone || '').trim()
  if (!phone) {
    ElMessage.warning('请填写手机号，手机号可直接用于登录')
    return
  }
  if (!/^1[3-9]\d{9}$/.test(phone)) {
    ElMessage.warning('手机号格式不正确，应为 11 位数字')
    return
  }
  if (!form.password || form.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  if (form.password !== form.confirm) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  if (!form.displayName || !form.displayName.trim()) {
    ElMessage.warning('请填写真实姓名')
    return
  }
  const username = (form.username || '').trim()
  if (username && username.length < 3) {
    ElMessage.warning('登录名至少 3 个字符，或留空直接用手机号')
    return
  }
  // 关键校验：没有员工档案就没有归属，账号建出来也看不到自己的案件
  if (!form.employeeId && !form.newEmployee) {
    ElMessage.warning('请先关联员工档案：在组织架构中选中本人档案，或点「新建一条」现场建立')
    return
  }
  loading.value = true
  try {
    await authApi.register({
      // 留空时后端会自动用手机号当登录名
      username: username || null,
      password: form.password,
      displayName: form.displayName.trim(),
      phone,
      dept: (form.dept || '').trim(),
      applyRole: form.applyRole,
      employeeId: form.employeeId || null,
      newEmployee: form.newEmployee || null
    })
    const empText = form.newEmployee
      ? `已同时为其建立员工档案「${form.newEmployee.name}」。`
      : ''
    await ElMessageBox.alert(
      `注册申请已提交。登录名：${username || phone}（也可用手机号登录）。\n${empText}`
        + '请等待所长或法制员审核，审核通过后即可登录。',
      '提交成功',
      { confirmButtonText: '返回登录', type: 'success' }
    )
    router.push('/login')
  } catch (e) {
    // 错误提示由 axios 拦截器统一弹出（如「手机号已被注册」）
  } finally {
    loading.value = false
  }
}
</script>
