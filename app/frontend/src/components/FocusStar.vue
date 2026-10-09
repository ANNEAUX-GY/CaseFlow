<template>
  <!--
    一键重点关注（2026-10-09）。
    需求原话：案件管理 / 案件盯办 / 待办总览 / 到期提醒 这四个栏目，
    不用打开案件详情就能点击标注该案件为重点案件。

    四个栏目各有一张表，星标的交互必须一模一样（否则"这页能点那页不能点"最招骂），
    所以收成一个组件：自己调接口、自己改这一行的值做即时反馈。
    待办总览传的对象不是案件而是待办（待办总览按待办列行），用 focusProp / idProp
    指一下字段名即可——星标打的都是背后的那个案件。
  -->
  <el-tooltip :content="on ? '取消重点关注' : '标注为重点关注'" placement="top">
    <el-button link class="cf-star" :class="{ 'is-on': on }" :loading="busy" @click="toggle">
      <el-icon :size="17"><StarFilled v-if="on" /><Star v-else /></el-icon>
    </el-button>
  </el-tooltip>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Star, StarFilled } from '@element-plus/icons-vue'
import { caseApi } from '../api'

const props = defineProps({
  /** 案件行（或待办行）；组件会就地改它的 focus/caseFocus 字段 */
  row: { type: Object, required: true },
  /** 星标状态字段名：案件行=focus，待办行=caseFocus */
  focusProp: { type: String, default: 'focus' },
  /** 案件 id 字段名：案件行=id，待办行=caseId */
  idProp: { type: String, default: 'id' }
})
const emit = defineEmits(['changed'])

const on = computed(() => Number(props.row?.[props.focusProp] || 0) === 1)
const busy = ref(false)

const toggle = async () => {
  const id = props.row?.[props.idProp]
  if (id == null || busy.value) return
  const next = on.value ? 0 : 1
  busy.value = true
  try {
    await caseApi.focus(id, next)
    // 就地改这一行：整表刷新会让滚动位置和展开状态跳一下，点个星不值得
    props.row[props.focusProp] = next
    emit('changed', { id, focus: next })
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '标注失败，请重试')
  } finally {
    busy.value = false
  }
}
</script>
