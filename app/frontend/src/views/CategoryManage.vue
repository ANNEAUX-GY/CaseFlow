<template>
  <div class="cf-page">
    <div class="cf-panel">
      <div class="cf-panel__head">
        <span>案件类别管理</span>
        <span class="cf-panel__head-tip">
          维护各大类下的小类（案由），案件管理处的「案件分类」级联选择实时生效；未立案不设小类
        </span>
        <div class="cf-panel__head-actions">
          <el-button type="primary" @click="openAdd">新增小类</el-button>
        </div>
      </div>

      <div class="cf-panel__body">
        <div class="cf-toolbar" style="margin-bottom: 12px">
          <el-radio-group v-model="filterType">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="CRIMINAL">刑事</el-radio-button>
            <el-radio-button value="ADMINISTRATIVE">行政</el-radio-button>
            <el-radio-button value="PRELIMINARY">未立案</el-radio-button>
          </el-radio-group>
        </div>

        <el-table :data="filteredRows" v-loading="loading" stripe>
          <el-table-column label="所属大类" width="120">
            <template #default="{ row }">
              <el-tag :type="TYPE_TAG[row.caseType] || 'info'" size="small">{{ row.caseTypeName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="小类名称" min-width="200" />
          <el-table-column prop="sort" label="排序" width="90" />
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <span class="cf-muted">{{ filterType === 'PRELIMINARY' ? '未立案不设小类' : '暂无小类，可点击右上角「新增小类」' }}</span>
          </template>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="dlg.visible" :title="dlg.id ? '编辑小类' : '新增小类'" width="440px">
      <el-form label-width="90px">
        <el-form-item label="所属大类" required>
          <el-select v-model="dlg.form.caseType" style="width: 100%">
            <el-option label="刑事" value="CRIMINAL" />
            <el-option label="行政" value="ADMINISTRATIVE" />
            <el-option label="未立案" value="PRELIMINARY" />
          </el-select>
        </el-form-item>
        <el-form-item label="小类名称" required>
          <el-input v-model="dlg.form.name" placeholder="如：电诈、殴打他人" maxlength="64" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="dlg.form.sort" :min="0" :max="999" />
          <span class="cf-muted" style="margin-left: 8px">数字越小越靠前（选填）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="dlg.loading" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { categoryApi } from '../api'
import { useCategoryStore } from '../store/category'
import PageFooter from '../components/PageFooter.vue'

const categoryStore = useCategoryStore()
const TYPE_TAG = { CRIMINAL: 'danger', ADMINISTRATIVE: 'warning', PRELIMINARY: 'info' }

const rows = ref([])
const loading = ref(false)
const filterType = ref('')

const filteredRows = computed(() => {
  if (!filterType.value) return rows.value
  return rows.value.filter((r) => r.caseType === filterType.value)
})

const load = async () => {
  loading.value = true
  try {
    rows.value = await categoryApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(load)

const dlg = reactive({ visible: false, loading: false, id: null, form: {} })
const resetForm = () => ({ caseType: 'CRIMINAL', name: '', sort: 0 })

const openAdd = () => {
  dlg.id = null
  dlg.form = resetForm()
  dlg.visible = true
}
const openEdit = (row) => {
  dlg.id = row.id
  dlg.form = { caseType: row.caseType, name: row.name, sort: row.sort || 0 }
  dlg.visible = true
}

const submit = async () => {
  if (!dlg.form.caseType) {
    ElMessage.warning('请选择所属大类')
    return
  }
  if (!dlg.form.name || !dlg.form.name.trim()) {
    ElMessage.warning('请输入小类名称')
    return
  }
  dlg.loading = true
  try {
    if (dlg.id) {
      await categoryApi.update(dlg.id, dlg.form)
      ElMessage.success('已保存')
    } else {
      await categoryApi.add(dlg.form)
      ElMessage.success('已新增')
    }
    dlg.visible = false
    await load()
    // 让案件管理处的级联选择器即时拿到最新分类树
    await categoryStore.load(true)
  } catch (e) {
    // 拦截器已提示
  } finally {
    dlg.loading = false
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除小类「${row.name}」吗？已有案件上保留的小类文字不受影响，仅从选择器移除。`,
      '删除小类',
      { type: 'warning', confirmButtonText: '删除' }
    )
  } catch (e) {
    return
  }
  try {
    await categoryApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
    await categoryStore.load(true)
  } catch (e) {
    // 拦截器已提示
  }
}
</script>
