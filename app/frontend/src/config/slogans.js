// 页脚标语配置：改这里即可，四个页面自动生效
// text = 主标语（建议 12~20 字，字间用全角空格分隔更舒展）
// sub  = 英文副标语

export const SLOGANS = [
  { text: '对党忠诚　服务人民　执法公正　纪律严明', sub: 'LOYALTY · SERVICE · JUSTICE · DISCIPLINE' },
  { text: '人民公安为人民', sub: 'POLICE FOR THE PEOPLE' },
  { text: '守一方平安　护万家灯火', sub: 'GUARD THE PEACE · PROTECT EVERY HOME' },
  { text: '执法为民　公正廉洁', sub: 'LAW ENFORCEMENT FOR THE PEOPLE' },
  { text: '忠诚铸警魂　担当护平安', sub: 'LOYALTY FORGES THE POLICE SOUL' },
  { text: '案件不隔夜　责任不落空', sub: 'NO CASE OVERNIGHT · NO DUTY DROPPED' }
]

// 各页面底部的一句业务提示（按路由 path 匹配，留空则不显示）
export const PAGE_HINT = {
  '/my-cases': '这里只显示系统按实名匹配到的本人名下案件，随时可进详情办理待办',
  '/dashboard': '逾期案件优先处置，临期案件提前 3 天跟进',
  '/cases': '支持按状态 / 优先级 / 来源定位，改派自动留痕可追溯',
  '/reminders': '建议每日到岗先清空「已逾期」，再处理 3 日内到期',
  '/org': '员工图谱支持 Excel 批量导入，按工号幂等覆盖'
}

// 页脚右侧的落款信息
export const FOOTER_BRAND = {
  name: '案件指派系统',
  version: 'CaseFlow v1.0',
  dept: '案件管理中心'
}
