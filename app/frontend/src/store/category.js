import { defineStore } from 'pinia'
import { categoryApi } from '../api'

/**
 * 案件分类树（大类 → 小类）：由后端字典接口加载，管理权限账户增删改后自动生效。
 * 级联选择器（案件筛选、新建/编辑表单）都从这里取 options。
 */
export const useCategoryStore = defineStore('category', {
  state: () => ({
    tree: [],
    loaded: false
  }),
  actions: {
    async load(force = false) {
      if (this.loaded && !force) return this.tree
      this.tree = await categoryApi.tree()
      this.loaded = true
      return this.tree
    },
    /** 由 (大类, 小类) 反查级联路径；小类不在树中时只取大类 */
    pathOf(caseType, category) {
      if (!caseType) return []
      const node = this.tree.find((t) => t.value === caseType)
      if (!node) return []
      if (category && (node.children || []).some((c) => c.value === category)) {
        return [caseType, category]
      }
      return [caseType]
    },
    /** 只知道小类时反查所属大类 */
    typeOfCategory(category) {
      if (!category) return ''
      const node = this.tree.find((t) => (t.children || []).some((c) => c.value === category))
      return node ? node.value : ''
    }
  }
})
