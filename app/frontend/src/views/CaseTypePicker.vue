<template>
  <div class="cf-picker">
    <div class="cf-picker__head">
      <div class="cf-picker__title">请选择案件类型</div>
      <div class="cf-picker__sub">
        选择后，本次浏览的案件列表、看板与统计都只显示该类案件；
        如需更换类型，请点右上角「退出」
      </div>
    </div>

    <div class="cf-picker__cards">
      <div
        v-for="o in options" :key="o.key"
        class="cf-picker__card"
        :class="{ 'is-active': o.key === current }"
        role="button" tabindex="0"
        @click="choose(o)" @keyup.enter="choose(o)"
      >
        <div class="cf-picker__card-top">
          <span class="cf-picker__badge" :class="'is-' + o.type">{{ o.label }}</span>
          <el-icon v-if="o.key === current" class="cf-picker__ok"><CircleCheckFilled /></el-icon>
        </div>
        <div class="cf-picker__desc">{{ o.desc }}</div>
        <div class="cf-picker__count" v-if="counts[o.key] !== undefined">
          共 {{ counts[o.key] }} 件
        </div>
      </div>
    </div>

    <div class="cf-picker__foot cf-muted">
      提示：切换侧边栏栏目不会丢失当前选择，只有主动「退出」才会重置
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CASE_TYPE_OPTIONS, useCaseTypeStore } from '../store/caseType'
import { caseApi } from '../api'

const props = defineProps({
  /** 选完类型后跳到哪；默认回 store 里记住的上次栏目 */
  redirectTo: { type: String, default: '' }
})
const emit = defineEmits(['chosen'])

const router = useRouter()
const store = useCaseTypeStore()
const options = CASE_TYPE_OPTIONS
const current = ref('')
const counts = ref({})

/** 选中类型：写状态 → 跳目标栏目 */
const choose = async (o) => {
  if (!store.select(o.key)) {
    ElMessage.error('类型参数不合法')
    return
  }
  current.value = o.key
  emit('chosen', o.key)
  const target = props.redirectTo || store.lastGatedPath || '/cases'
  store.rememberPath(target)
  router.push(target)
}

/** 各类型案件数：让用户选择前就看到规模，避免盲选 */
const loadCounts = async () => {
  const out = {}
  for (const o of options) {
    try {
      const data = await caseApi.page({ page: 1, size: 1, caseType: o.scope })
      out[o.key] = data?.total ?? 0
    } catch (e) {
      out[o.key] = 0
    }
  }
  counts.value = out
}

onMounted(() => {
  current.value = store.current
  loadCounts()
})

defineExpose({ loadCounts })
</script>

<style>
.cf-picker { padding: 28px 24px 20px; max-width: 900px; margin: 0 auto }
.cf-picker__head { text-align: center; margin-bottom: 22px }
.cf-picker__title { font-size: 19px; font-weight: 600; color: #1b2430 }
.cf-picker__sub { font-size: 13px; color: #5a6472; margin-top: 8px; line-height: 1.7 }
.cf-picker__cards {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px;
}
.cf-picker__card {
  border: 1px solid #dfe4ea; border-radius: 6px; padding: 16px 14px;
  cursor: pointer; background: #fff; transition: all .15s;
  min-width: 0;
}
.cf-picker__card:hover { border-color: #1b4a8c; background: #f7faff; transform: translateY(-1px) }
.cf-picker__card:focus-visible { outline: 2px solid #1b4a8c; outline-offset: 2px }
.cf-picker__card.is-active { border-color: #1b4a8c; background: #f2f6fc; box-shadow: 0 0 0 1px #1b4a8c inset }
.cf-picker__card-top { display: flex; align-items: center; gap: 8px; margin-bottom: 8px }
.cf-picker__badge {
  display: inline-block; padding: 3px 10px; border-radius: 3px;
  font-size: 13px; font-weight: 600; color: #fff;
}
.cf-picker__badge.is-danger { background: #c62a2a }
.cf-picker__badge.is-warning { background: #d98a0b }
.cf-picker__badge.is-info { background: #5a6472 }
.cf-picker__ok { color: #1b4a8c; font-size: 17px; margin-left: auto }
.cf-picker__desc { font-size: 12px; color: #5a6472; line-height: 1.6; min-height: 38px }
.cf-picker__count { font-size: 12px; color: #8a929e; margin-top: 6px }
.cf-picker__foot { text-align: center; font-size: 12px; margin-top: 18px }

@media (max-width: 768px) {
  .cf-picker { padding: 16px 12px }
  .cf-picker__cards { grid-template-columns: 1fr }
  .cf-picker__title { font-size: 17px }
  .cf-picker__desc { min-height: 0 }
}
</style>
