<template>
  <div>
    <el-tabs v-model="mode">
      <!-- 检索：输入姓名/工号/部门/职务，直接命中到人 -->
      <el-tab-pane label="检索" name="search">
        <el-input
          v-model="keyword"
          placeholder="输入姓名 / 工号 / 部门 / 职务，回车检索"
          clearable
          @input="onKeywordInput"
          @keyup.enter="doSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <div v-if="isMobile" class="cf-tscroll-hint" style="text-align: right; margin-top: 4px">
          表格可左右滑动查看
        </div>

        <!-- 手机上表格列宽仍是桌面的（合计 570px），外面套一层可横滑的容器，
             否则只能看到前三列，而「主办 / 协办」两个关键按钮恰好被挤到屏幕外 -->
        <div class="cf-tscroll">
          <el-table :data="results" size="small" :height="isMobile ? 240 : 280" style="margin-top: 8px" v-loading="loading">
            <!-- 手机端把「部门/职务」并进姓名单元格，「归属链路 / 在手」这两列先收起来：
                 选人时真正要点的只有「主办 / 协办」，列挤到看不着按钮才是真麻烦 -->
            <el-table-column :label="isMobile ? '姓名 / 部门' : '姓名'" :width="isMobile ? 118 : 90">
              <template #default="{ row }">
                <div>{{ row.name }}</div>
                <div v-if="isMobile" class="cf-muted" style="font-size: 11px; line-height: 1.3">
                  {{ row.dept || '-' }}
                </div>
              </template>
            </el-table-column>
            <el-table-column v-if="!isMobile" label="部门 / 职务" min-width="140">
              <template #default="{ row }">
                <span>{{ row.dept || '-' }}</span>
                <span class="cf-muted"> · {{ row.title || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="!isMobile" prop="pathName" label="归属链路" min-width="160" />
            <el-table-column v-if="!isMobile" label="在手" width="60" align="center">
              <template #default="{ row }">
                <span :class="row.activeCaseCount > 0 ? 'cf-warn' : 'cf-muted'">{{ row.activeCaseCount }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" :width="isMobile ? 108 : 120" align="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="setOwner(row)">主办</el-button>
                <el-button link @click="toggleMember(row)">协办</el-button>
              </template>
            </el-table-column>
            <template #empty><span class="cf-muted">输入关键词开始检索</span></template>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 组织树：按 领导-副领导-组长-组员 逐级点选 -->
      <el-tab-pane label="组织树" name="tree">
        <el-input v-model="filterText" placeholder="过滤组织树" clearable />
        <el-tree
          ref="treeRef"
          :data="treeData"
          :props="{ label: 'name', children: 'children' }"
          :filter-node-method="filterNode"
          default-expand-all
          style="margin-top: 8px; height: 280px; overflow: auto"
        >
          <template #default="{ data }">
            <span style="display: flex; align-items: center; gap: 6px; width: 100%">
              <span>{{ data.name }}</span>
              <span class="cf-muted">{{ data.title || '' }}</span>
              <span class="cf-muted">{{ data.dept || '' }}</span>
              <span v-if="data.activeCaseCount > 0" class="cf-warn">在手{{ data.activeCaseCount }}</span>
              <span style="flex: 1" />
              <el-button link type="primary" size="small" @click.stop="setOwner(data)">主办</el-button>
              <el-button link size="small" @click.stop="toggleMember(data)">协办</el-button>
            </span>
          </template>
        </el-tree>
      </el-tab-pane>
    </el-tabs>

    <div style="margin-top: 10px">
      <span class="cf-muted">已选：</span>
      <el-tag v-if="owner" type="danger" style="margin-right: 6px" closable @close="owner = null">
        {{ owner.name }} · 主办
      </el-tag>
      <el-tag
        v-for="m in members"
        :key="m.id"
        style="margin-right: 6px"
        closable
        @close="removeMember(m.id)"
      >
        {{ m.name }} · 协办
      </el-tag>
      <span v-if="!owner && members.length === 0" class="cf-muted">（未选择，可从检索结果或组织树中点选）</span>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { employeeApi } from '../api'
import { useDevice } from '../utils/device'

// 手机端：精简列 + 降高，保证「主办 / 协办」按钮始终在屏内
const { isMobile } = useDevice()

const props = defineProps({
  ownerId: { type: [Number, null], default: null },
  memberIds: { type: Array, default: () => [] }
})
const emit = defineEmits(['change'])

const mode = ref('search')
const keyword = ref('')
const filterText = ref('')
const results = ref([])
const treeData = ref([])
const loading = ref(false)
const treeRef = ref()
const owner = ref(null)
const members = ref([])
let timer = null

const notify = () => {
  emit('change', {
    ownerId: owner.value ? owner.value.id : null,
    memberIds: members.value.map((m) => m.id)
  })
}

const setOwner = (row) => {
  owner.value = { id: row.id, name: row.name }
  members.value = members.value.filter((m) => m.id !== row.id)
  notify()
}

const toggleMember = (row) => {
  if (owner.value && owner.value.id === row.id) {
    owner.value = null
  }
  const idx = members.value.findIndex((m) => m.id === row.id)
  if (idx >= 0) {
    members.value.splice(idx, 1)
  } else {
    members.value.push({ id: row.id, name: row.name })
  }
  notify()
}

const removeMember = (id) => {
  members.value = members.value.filter((m) => m.id !== id)
  notify()
}

let searchVersion = 0
onBeforeUnmount(() => { clearTimeout(timer); searchVersion++ })
const doSearch = async () => {
  const version = ++searchVersion
  loading.value = true
  try {
    const data = await employeeApi.search({ keyword: keyword.value, limit: 50 })
    if (version === searchVersion) results.value = data
  } finally {
    if (version === searchVersion) loading.value = false
  }
}

const onKeywordInput = () => {
  searchVersion++
  clearTimeout(timer)
  timer = setTimeout(() => { doSearch().catch(() => {}) }, 250)
}

const loadTree = async () => {
  treeData.value = await employeeApi.tree({ status: 1 })
}

const filterNode = (value, data) => {
  if (!value) return true
  const kw = value.toLowerCase()
  return (data.name || '').toLowerCase().includes(kw)
    || (data.dept || '').toLowerCase().includes(kw)
    || (data.title || '').toLowerCase().includes(kw)
}

watch(filterText, (v) => treeRef.value?.filter(v))

// 回显：把已传入的 ID 还原成可展示的标签
onMounted(async () => {
  await loadTree()
  if (keyword.value) await doSearch()
  const all = []
  const walk = (list) => list.forEach((n) => { all.push(n); walk(n.children || []) })
  walk(treeData.value)
  if (props.ownerId) {
    const hit = all.find((n) => n.id === props.ownerId)
    if (hit) owner.value = { id: hit.id, name: hit.name }
  }
  props.memberIds.forEach((id) => {
    const hit = all.find((n) => n.id === id)
    if (hit) members.value.push({ id: hit.id, name: hit.name })
  })
})

defineExpose({ doSearch })
</script>
