<template>
  <div class="cf-boards">
    <!-- 头部：与类型选择器同一套版式（cf-picker__head 是全局样式） -->
    <div class="cf-picker__head">
      <div class="cf-picker__title">{{ opt ? opt.label : '' }} · 选择小类</div>
      <div class="cf-picker__sub">
        <template v-if="categoryAware">
          当前大类下的案件按小类分成卡片，点击卡片即可查看该小类的案件
        </template>
        <template v-else>
          该栏目没有小类维度，直接进入即可；案件列表与案件盯办才支持按小类筛选
        </template>
        <br />
        退回上一级（重选大类）请点顶部类型条右侧的按钮
      </div>
    </div>

    <div v-if="loading" class="cf-boards__loading">正在统计各类案件数…</div>

    <div v-else class="cf-boards__grid">
      <div
        v-for="b in boards" :key="b.key"
        class="cf-picker__card cf-boards__card"
        role="button" tabindex="0"
        @click="go(b)" @keyup.enter="go(b)"
      >
        <div class="cf-picker__card-top">
          <span class="cf-picker__badge" :class="'is-' + b.tone">{{ b.name }}</span>
          <el-icon class="cf-boards__arrow"><ArrowRight /></el-icon>
        </div>
        <div class="cf-boards__desc">{{ b.desc }}</div>
        <div v-if="b.count != null" class="cf-picker__count">共 {{ b.count }} 件</div>
      </div>
    </div>

    <div v-if="!loading && !boards.length" class="cf-boards__empty cf-muted">
      当前类型暂无可浏览的案件类别，请先在「类别管理」里为该类型配置小类
    </div>

    <div class="cf-picker__foot cf-muted">
      提示：进入小类后如需退回本页或重选大类，请用顶部类型条右侧的按钮
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'
import { caseApi } from '../api'
import { useCategoryStore } from '../store/category'
import { CATEGORY_AWARE_PATHS, parseLevel3, useCaseTypeStore, withCaseType } from '../store/caseType'

/**
 * 第 2 级「选小类」页（小类卡片页，2026-10-09 三级浏览改造）。
 *
 * <p>流程：大类卡片页选完大类 → 进本页选小类 → 落到第 3 级的栏目页。
 * 落点由大类页用 to 参数带过来（如 `/cases?status=IN_PROGRESS`、`/todos`），
 * 本页只负责往上加一个小类筛选，**不改变原栏目与原有筛选**。
 *
 * <p>口径约定：
 * <ul>
 *   <li>各类别案件数用 page 接口 size=1 取 total——与列表页完全同一套筛选链路，
 *       不会出现"卡片上写 5 件、点进去 4 件"的口径错位（类型选择器数数也是这个先例）；</li>
 *   <li>「未分类」= category 为空（NULL/空串），走后端 category=NONE 特殊值；</li>
 *   <li>「其他案件」类型没有字典小类（未立案不设小类），退化为单张「全部」卡；</li>
 *   <li>待办总览 / 到期提醒没有小类维度（见 CATEGORY_AWARE_PATHS），
 *       也只给一张「全部」卡——流程照样走两级，但不假装能筛。</li>
 * </ul>
 */
const router = useRouter()
const route = useRoute()
const categoryStore = useCategoryStore()
const caseTypeStore = useCaseTypeStore()

const opt = computed(() => caseTypeStore.currentOption)
const loading = ref(false)
const boards = ref([])

/** 最终落点栏目（大类页用 to 带过来；非法值 parseLevel3 会退回案件管理） */
const target = computed(() => parseLevel3(String(route.query.to || '/cases')))
/** 落点栏目是否支持小类筛选 */
const categoryAware = computed(() => CATEGORY_AWARE_PATHS.includes(target.value.path))

/**
 * 门控类型（store scope）→ 类别字典树节点 value。
 * OTHER 在字典里对应「未立案(PRELIMINARY)」，而未立案不设小类，故单独处理。
 */
const TREE_KEY = { CRIMINAL: 'CRIMINAL', ADMINISTRATIVE: 'ADMINISTRATIVE' }

/** 统计某类别在当前类型下的案件数（口径与案件管理列表一致） */
const countOf = async (category) => {
  const params = withCaseType({ page: 1, size: 1 })
  if (category) params.category = category
  Object.keys(params).forEach((k) => {
    if (params[k] === '' || params[k] == null) delete params[k]
  })
  try {
    const data = await caseApi.page(params)
    return data?.total ?? 0
  } catch (e) {
    return 0
  }
}

const load = async () => {
  const o = opt.value
  if (!o) return
  loading.value = true
  try {
    const out = []
    const treeKey = TREE_KEY[o.key]
    const node = treeKey
      ? categoryStore.tree.find((t) => t.value === treeKey)
      : null
    const subs = ((node && node.children) || []).slice()
      .sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))

    if (!categoryAware.value) {
      // 落点栏目本身没有小类维度（待办总览 / 到期提醒）：只给一张「全部」卡，
      // 保证三级流程一致，同时不假装这里有筛选能力。
      // noCount：这张卡通往的是待办/提醒，不是案件列表——
      // 挂个"共 N 件案件"会让人以为是待办条数，含义对不上，索性不显示数字。
      out.push({
        key: '__all__', name: '全部', tone: o.type, category: '', noCount: true,
        desc: '该栏目不按小类划分，直接进入'
      })
    } else if (o.key === 'OTHER' || !subs.length) {
      // 其他类型 / 尚未配置小类：只给「全部案件」一张卡，避免空板块页
      out.push({
        key: '__all__', name: '全部案件', tone: 'info', category: '',
        desc: '查看该类型下全部案件，不做类别筛选'
      })
    } else {
      for (const c of subs) {
        out.push({
          key: 'cat:' + c.value, name: c.label || c.value, tone: o.type, category: c.value,
          desc: `${c.label || c.value}类案件汇总`
        })
      }
      out.push({
        key: '__none__', name: '未分类', tone: 'info', category: 'NONE',
        desc: '录入时未选择类别的案件自动归入此处'
      })
    }

    // 并发统计各类数量（标了 noCount 的卡不算——它指向的不是案件列表）
    const counts = await Promise.all(out.map((b) => (b.noCount ? null : countOf(b.category))))
    out.forEach((b, i) => { b.count = counts[i] })
    boards.value = out
  } finally {
    loading.value = false
  }
}

/** 点击卡片 → 落点栏目（带上小类筛选；「全部」则不带，并清掉旧的小类） */
const go = (b) => {
  const t = target.value
  const query = { ...t.query }
  // 换小类等于重新筛选，分页必须归 1，否则会停在上一次的页码上出现空页
  delete query.page
  if (b.category) query.category = b.category
  else delete query.category
  router.push({ path: t.path, query })
}

// 类型被切换/退出、或落点栏目变了（不同栏目有无小类维度不同）都要重算卡片
watch(() => [caseTypeStore.current, route.query.to], () => load())

onMounted(async () => {
  await categoryStore.load()
  await load()
})
</script>

<style>
.cf-boards { padding: 40px 32px 28px; max-width: 1180px; margin: 0 auto }
/* 类别数量比类型选择器多，用自适应网格：每张卡最小 240px，放得下 6~8 个小类 */
.cf-boards__grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 20px;
}
.cf-boards__card { min-height: 150px }
.cf-boards__arrow { margin-left: auto; color: #b7c0cc; font-size: 18px; transition: all .15s }
.cf-boards__card:hover .cf-boards__arrow { color: #1b4a8c; transform: translateX(3px) }
.cf-boards__desc { font-size: 14px; color: #5a6472; line-height: 1.75; flex: 1 }
.cf-boards__loading, .cf-boards__empty { text-align: center; padding: 48px 0; font-size: 15px }
</style>
