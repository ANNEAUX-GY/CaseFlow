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
        <div class="cf-tscroll">
          <el-table :data="results" size="small" :height="280" style="margin-top: 8px" v-loading="loading">
            <el-table-column label="姓名" :width="90">
              <template #default="{ row }">
                <div>{{ row.name }}</div>
              </template>
            </el-table-column>
            <el-table-column label="部门 / 职务" min-width="140">
              <template #default="{ row }">
                <span>{{ row.dept || '-' }}</span>
                <span class="cf-muted"> · {{ row.title || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="pathName" label="归属链路" min-width="160" />
            <el-table-column label="在手" width="60" align="center">
              <template #default="{ row }">
                <span :class="row.activeCaseCount > 0 ? 'cf-warn' : 'cf-muted'">{{ row.activeCaseCount }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" :width="120" align="right">
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
import { POLICE_GROUP_META, policeGroupLabel } from '../utils/format'

// 手机端：精简列 + 降高，保证「主办 / 协办」按钮始终在屏内

const props = defineProps({
  ownerId: { type: [Number, null], default: null },
  memberIds: { type: Array, default: () => [] },
  /** 本案要求的办案组别（INITIAL/CLEAR/NONE）。给了就把不匹配的人置灰不可选 */
  requiredGroup: { type: String, default: '' }
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

/**
 * 该员工是否可承接本案件。
 *
 * <p>不匹配的人**置灰而非隐藏** —— 隐藏会让人以为系统里没有别人，
 * 以为流程走不通；置灰+ 说明原因才能让人知道"该找谁"。
 * 与后端 PoliceGroup.canTake 同一套口径。
 */
const groupOf = (row) => {
  const g = row && row.policeGroup
  return POLICE_GROUP_META[g] ? g : 'NONE'
}

const disabledReason = (row) => {
  if (!props.requiredGroup || props.requiredGroup === 'NONE') return ''
  if (groupOf(row) === props.requiredGroup) return ''
  return `只能由${policeGroupLabel(props.requiredGroup)}人员承办`
}

const isDisabled = (row) => !!disabledReason(row)

const setOwner = (row) => {
  if (isDisabled(row)) return
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
