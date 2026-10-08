<template>
  <div class="cf-boards">
    <!-- 头部：与类型选择器同一套版式（cf-picker__head 是全局样式） -->
    <div class="cf-picker__head">
      <div class="cf-picker__title">{{ opt ? opt.label : '' }} · 按类别浏览</div>
      <div class="cf-picker__sub">
        当前类型下的案件按类别分成板块，点击类别卡片即可查看该类案件的汇总列表
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
        <div class="cf-picker__count">共 {{ b.count }} 件</div>
      </div>
    </div>

    <div v-if="!loading && !boards.length" class="cf-boards__empty cf-muted">
      当前类型暂无可浏览的案件类别，请先在「类别管理」里为该类型配置小类
    </div>

    <div class="cf-picker__foot cf-muted">
      提示：进入类别后如需返回，可点案件列表上方类型条里的「按类别浏览」
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'
import { caseApi } from '../api'
import { useCategoryStore } from '../store/category'
import { useCaseTypeStore, withCaseType } from '../store/caseType'

/**
 * 「按类别浏览」板块页（2026-10-08）。
 *
 * <p>类型选择器选完类型后先进这里：把当前类型下的案件按类别（case_category 字典小类）
 * 分成板块卡片，每张卡显示该类案件数；点击卡片 → 案件管理列表并自动带上类别筛选。
 * 卡片样式复用类型选择器的 cf-picker 系列（其 style 非 scoped，全局生效）。
 *
 * <p>口径约定：
 * <ul>
 *   <li>各类别案件数用 page 接口 size=1 取 total——与列表页完全同一套筛选链路，
 *       不会出现"板块上写 5 件、点进去 4 件"的口径错位（类型选择器数数也是这个先例）；</li>
 *   <li>「未分类」= category 为空（NULL/空串），走后端 category=NONE 特殊值；</li>
 *   <li>「其他案件」类型没有字典小类（未立案不设小类），退化为单张「全部案件」卡。</li>
 * </ul>
 */
const router = useRouter()
const categoryStore = useCategoryStore()
const caseTypeStore = useCaseTypeStore()

const opt = computed(() => caseTypeStore.currentOption)
const loading = ref(false)
const boards = ref([])

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

    if (o.key === 'OTHER' || !subs.length) {
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

    // 并发统计各类数量
    const counts = await Promise.all(out.map((b) => countOf(b.category)))
    out.forEach((b, i) => { b.count = counts[i] })
    boards.value = out
  } finally {
    loading.value = false
  }
}

/** 点击板块 → 案件管理列表（带类别筛选；全部案件不带） */
const go = (b) => {
  const query = b.category ? { category: b.category } : {}
  router.push({ path: '/cases', query })
}

// 类型被切换/退出后重进本页时重新统计（正常流程不会发生，兜底）
watch(() => caseTypeStore.current, () => load())

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
