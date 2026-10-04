<template>
  <div class="cf-foot">
    <!-- 留白 + 徽标分隔线 -->
    <div class="cf-foot__space">
      <span class="cf-foot__line" />
      <span class="cf-foot__seal">
        <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
          <path d="M12 2l8 3v7c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V5l8-3z" fill="none" stroke="currentColor" stroke-width="1.6" />
          <path d="M12 7l1.6 3.3 3.6.5-2.6 2.6.6 3.6L12 15.6 8.8 17l.6-3.6L6.8 10.8l3.6-.5L12 7z" fill="currentColor" />
        </svg>
      </span>
      <span class="cf-foot__line" />
    </div>

    <!-- 标语条。原 title 悬浮提示已去掉（会弹出遮挡），
         改为一直可见的浅色小字，不打断操作。 -->
    <div class="cf-foot__band">
      <span class="cf-foot__slogan" @click="next">
        {{ slogan.text }}
      </span>
      <span class="cf-foot__slogan-tip">点击切换（共 {{ SLOGANS.length }} 条）</span>

      <span class="cf-foot__divider" />

      <div class="cf-foot__col">
        <div class="cf-foot__sub">{{ slogan.sub }}</div>
        <div v-if="hint" class="cf-foot__hint">{{ hint }}</div>
      </div>

      <span class="cf-foot__grow" />

      <div class="cf-foot__meta">
        <div>{{ FOOTER_BRAND.name }} · {{ FOOTER_BRAND.dept }}</div>
        <div>{{ today }} · {{ FOOTER_BRAND.version }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { SLOGANS, PAGE_HINT, FOOTER_BRAND } from '../config/slogans'

const route = useRoute()

// 进入页面时按「日期 + 路由」取一条，保证同一天同一页稳定，不随刷新乱跳
const seed = computed(() => {
  const d = new Date()
  const day = Math.floor(d.getTime() / 86400000)
  const path = route.path || '/'
  let h = 0
  for (let i = 0; i < path.length; i++) h = (h * 31 + path.charCodeAt(i)) >>> 0
  return (day + h) % SLOGANS.length
})

const offset = ref(0)
const slogan = computed(() => SLOGANS[(seed.value + offset.value) % SLOGANS.length])
const hint = computed(() => PAGE_HINT['/' + (route.path.split('/')[1] || '')] || '')

const next = () => { offset.value = (offset.value + 1) % SLOGANS.length }

const today = computed(() => {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
})
</script>
