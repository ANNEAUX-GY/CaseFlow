/**
 * 图表统一主题：与公安配色一致，所有图表共用，避免各页风格跑偏。
 * 颜色取自 src/styles/index.css 的 :root 变量。
 *
 * 每个 build 函数都接受 narrow 参数（手机端 = true）：
 * 手机屏幕只有 300 多像素宽，桌面那套边距、图例、字号会把绘图区压得只剩一条，
 * 所以窄屏下把留白和字号成比例收掉，绘图区优先。
 */
export const CHART = {
  primary: '#1b4a8c',
  primaryLight: '#5b82c4',
  navy: '#12294a',
  gold: '#c8a45c',
  danger: '#c62a2a',
  warn: '#d98a0b',
  ok: '#1e8e58',
  text: '#1b2430',
  text2: '#5a6472',
  text3: '#8a929e',
  border: '#dfe4ea',
  headBg: '#eff3f8',
  // 分类色板：警蓝打头，金色点缀，后续低饱和过渡
  palette: ['#1b4a8c', '#c8a45c', '#2a5da6', '#7d9bcd', '#d98a0b', '#1e8e58', '#8a929e', '#c62a2a']
}

/** 窄屏（手机）下的统一取值，集中一处便于整体微调 */
const NARROW = {
  grid: { left: 2, right: 8, top: 28, bottom: 2, containLabel: true },
  gridHorizontal: { left: 2, right: 18, top: 28, bottom: 2, containLabel: true },
  axisFont: 10,
  barMaxWidth: 16,
  labelFont: 10,
  symbolSize: 4
}

/** 公共底座：网格、坐标轴、提示框样式统一 */
export function baseOption(narrow = false) {
  return {
    color: CHART.palette,
    grid: narrow ? { ...NARROW.grid } : { left: 8, right: 16, top: 28, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(27,74,140,0.06)' } },
      backgroundColor: 'rgba(18,41,74,0.92)',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: narrow ? 11 : 12 },
      padding: narrow ? [5, 8] : [6, 10],
      // 不让提示框跑出卡片：手机上很容易贴着屏幕边缘被裁掉
      confine: true
    },
    legend: narrow
      ? {
          top: 0,
          left: 0,
          itemWidth: 9,
          itemHeight: 7,
          itemGap: 10,
          textStyle: { color: CHART.text2, fontSize: 10 }
        }
      : {
          top: 0,
          right: 0,
          itemWidth: 10,
          itemHeight: 8,
          itemGap: 12,
          textStyle: { color: CHART.text2, fontSize: 11 }
        },
    textStyle: { fontFamily: 'Microsoft YaHei, PingFang SC, Arial, sans-serif' },
    animationDuration: 380
  }
}

/** 纵轴（数值轴）统一 */
export function valueAxis(extra = {}, narrow = false) {
  return Object.assign(
    {
      type: 'value',
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: CHART.text3, fontSize: narrow ? NARROW.axisFont : 11 },
      splitLine: { lineStyle: { color: CHART.border, type: 'dashed' } }
    },
    extra
  )
}

/** 横轴（类目轴）统一 */
export function categoryAxis(data, extra = {}, narrow = false) {
  const cats = data || []
  return Object.assign(
    {
      type: 'category',
      data: cats,
      axisLine: { lineStyle: { color: CHART.border } },
      axisTick: { show: false },
      axisLabel: {
        color: CHART.text2,
        fontSize: narrow ? NARROW.axisFont : 11,
        hideOverlap: true,
        // 窄屏 + 类目多，横排一定重叠，斜着放才都看得见
        rotate: narrow && cats.length > 5 ? 32 : 0
      }
    },
    extra
  )
}

/** 折线图：支持多序列；area 为 true 时填充渐变 */
export function lineOption({ categories, series, area = false, smooth = true, yName = '', narrow = false }) {
  const opt = baseOption(narrow)
  opt.xAxis = categoryAxis(categories, { boundaryGap: false }, narrow)
  opt.yAxis = valueAxis({ name: yName }, narrow)
  opt.series = (series || []).map((s, i) => ({
    name: s.name,
    type: 'line',
    data: s.data,
    smooth,
    symbol: 'circle',
    symbolSize: narrow ? NARROW.symbolSize : 5,
    showSymbol: (s.data || []).length <= 40,
    lineStyle: { width: narrow ? 1.6 : 2, color: s.color || CHART.palette[i] },
    itemStyle: { color: s.color || CHART.palette[i] },
    areaStyle: area
      ? {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: hexAlpha(s.color || CHART.palette[i], 0.22) },
              { offset: 1, color: hexAlpha(s.color || CHART.palette[i], 0.02) }
            ]
          }
        }
      : undefined
  }))
  return opt
}

/** 柱状图：horizontal 为 true 时成条形图（类目在 Y 轴）
 *  colors 支持三种写法：
 *    - 省缺          -> 用主题色板
 *    - ['#x', '#y']  -> 第 i 条序列用第 i 个色（整条同色）
 *    - [['#a','#b']] -> 第 i 条序列内部的每个数据点分别取色（按数据下标）
 */
export function barOption({ categories, series, horizontal = false, stack = false, colors, narrow = false }) {
  const opt = baseOption(narrow)
  const list = series || []
  if (horizontal) {
    opt.grid = narrow ? { ...NARROW.gridHorizontal } : { left: 8, right: 24, top: 28, bottom: 6, containLabel: true }
    opt.xAxis = valueAxis({}, narrow)
    opt.yAxis = categoryAxis(categories, { inverse: true }, narrow)
  } else {
    opt.yAxis = valueAxis({}, narrow)
    opt.xAxis = categoryAxis(categories, {}, narrow)
  }
  opt.series = list.map((s, i) => {
    const c = colors ? colors[i] : (s.color || CHART.palette[i])
    const perItem = Array.isArray(c)
    // 逐条配色必须落到 series.data[].itemStyle.color：
    // ECharts 不认 series.itemStyle.color 传数组，会整条回退成主题色——
    // 表现就是「逾期红 / 临期黄」全都变成一个灰蓝，颜色维度整块失效。
    const data = perItem
      ? (s.data || []).map((v, idx) => ({
          value: v,
          itemStyle: { color: c.length === 1 ? c[0] : (c[idx] || CHART.palette[i]) }
        }))
      : s.data
    return {
      name: s.name,
      type: 'bar',
      data,
      stack: stack ? 'total' : undefined,
      barMaxWidth: narrow ? NARROW.barMaxWidth : 26,
      itemStyle: {
        ...(perItem ? {} : { color: c }),
        // 白色系列（如「未立案」）在白底图上必须描边才可见
        ...(s.borderColor ? { borderColor: s.borderColor, borderWidth: 1.2 } : {}),
        borderRadius: horizontal ? [0, 2, 2, 0] : [2, 2, 0, 0]
      },
      label: {
        show: !stack && list.length === 1,
        position: horizontal ? 'right' : 'top',
        color: CHART.text2,
        fontSize: narrow ? NARROW.labelFont : 11
      }
    }
  })
  return opt
}

/** #RRGGBB -> rgba(...) */
export function hexAlpha(hex, alpha) {
  const h = hex.replace('#', '')
  const v = h.length === 3 ? h.split('').map((c) => c + c).join('') : h
  const r = parseInt(v.slice(0, 2), 16)
  const g = parseInt(v.slice(2, 4), 16)
  const b = parseInt(v.slice(4, 6), 16)
  return `rgba(${r},${g},${b},${alpha})`
}

/**
 * 案件类型分色规范（工作台「案件类型分析」栏专用，图例 / 环图 / 堆叠柱共用同一份）：
 *   红 = 刑事；蓝 = 行政；白 = 未立案（白底图上必须灰边描出才可见）；金 = 民事（补充色）。
 * order 同时决定图例与堆叠顺序。
 */
export const CASE_TYPE_COLORS = [
  { code: 'CRIMINAL', label: '刑事', color: '#c62a2a' },
  { code: 'ADMINISTRATIVE', label: '行政', color: '#1b4a8c' },
  { code: 'PRELIMINARY', label: '未立案', color: '#ffffff', border: '#8a929e' }
]

/** 类型分色查询：code -> {color, border?} */
export function caseTypeColor(code) {
  return CASE_TYPE_COLORS.find((t) => t.code === code) || { code, label: code, color: '#c0c4cc', border: '#8a929e' }
}
