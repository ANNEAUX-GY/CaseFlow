<template>
  <div class="cf-picker">
    <div class="cf-picker__head">
      <div class="cf-picker__title">刑事案件 · 选择办案阶段</div>
      <div class="cf-picker__sub">初查可继续按小类浏览；刑拘在办、取保监居直接进入案件盯办列表</div>
    </div>
    <div class="cf-picker__cards">
      <div v-for="stage in stages" :key="stage.key" class="cf-picker__card"
        role="button" tabindex="0" @click="choose(stage)" @keyup.enter="choose(stage)">
        <div class="cf-picker__card-top">
          <span class="cf-picker__badge is-danger">{{ stage.label }}</span>
          <el-icon class="cf-stage__arrow"><ArrowRight /></el-icon>
        </div>
        <div class="cf-picker__desc">{{ stage.desc }}</div>
        <div v-if="counts[stage.key] != null" class="cf-picker__count">共 {{ counts[stage.key] }} 件</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'
import { watchApi } from '../api'
import { boardsUrl, useCaseTypeStore, withCaseType } from '../store/caseType'

const router = useRouter()
const store = useCaseTypeStore()
const counts = ref({})
const stages = [
  { key: 'INITIAL', label: '初查', desc: '继续按小类浏览初查案件' },
  { key: 'DETENTION', label: '刑拘在办', desc: '直接查看刑拘在办案件' },
  { key: 'BAIL_RESIDENCE', label: '取保监居', desc: '直接查看取保及监居案件' }
]
const choose = (stage) => {
  if (stage.key === 'INITIAL') {
    router.push(boardsUrl('/watch?module=INITIAL'))
  } else {
    router.push({ path: '/watch', query: { module: stage.key } })
  }
}
onMounted(async () => {
  if (store.current !== 'CRIMINAL') {
    router.replace('/case-type')
    return
  }
  try {
    const board = await watchApi.board(withCaseType({}))
    counts.value = { INITIAL: board.initialTotal ?? 0, DETENTION: board.detentionTotal ?? 0, BAIL_RESIDENCE: board.bailTotal ?? 0 }
  } catch (e) {
    counts.value = {}
  }
})
</script>

<style scoped>
.cf-stage__arrow { margin-left: auto; color: #b7c0cc; font-size: 18px }
.cf-picker__card:hover .cf-stage__arrow { color: #1b4a8c }
</style>
