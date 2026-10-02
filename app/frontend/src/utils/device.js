/**
 * 设备识别：判断用户是用「手机网页」还是「电脑网页」打开的。
 *
 * 判定走两个来源，任一命中即按移动端渲染：
 *   1) User-Agent —— 手机、平板、微信内置浏览器都能识别出来；
 *   2) 视口宽度   —— 电脑上把浏览器窗口拖窄、平板竖屏，也会自动切到移动布局。
 *
 * 允许人工覆盖：平板、折叠屏、投屏演示这些场景 UA 和宽度都可能骗人，
 * 用户可以在界面上手动选「手机版 / 电脑版」，选择存在 localStorage，刷新后依然生效。
 *
 * 识别结果同步写到 <html> 的 class 上（cf-device-mobile / cf-device-desktop），
 * 这样纯 CSS 场景（不能引入 JS 的地方）也能跟着变。
 */
import { ref, computed, readonly } from 'vue'

/** 移动端断点：必须与 index.css 里的 @media (max-width: 768px) 保持一致 */
export const MOBILE_MAX_WIDTH = 768
/** 平板竖屏也按移动布局处理（iPad 竖屏 768~834） */
export const TABLET_MAX_WIDTH = 1024

const STORAGE_KEY = 'cf_device_mode'

/* 平板要排在手机前面判：iPad 的 UA 里也可能带 Mobile 字样 */
const UA_TABLET = /iPad|Tablet|PlayBook|Silk|Kindle|Android(?!.*Mobile)/i
const UA_PHONE =
  /iPhone|iPod|Windows Phone|IEMobile|BlackBerry|Opera Mini|Opera Mobi|webOS|Symbian|Android.*Mobile/i

const readMode = () => {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return v === 'mobile' || v === 'desktop' ? v : 'auto'
  } catch (e) {
    return 'auto'
  }
}

/** 当前用户手动指定的模式：auto = 交给自动识别 */
const mode = ref(readMode())
const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth)

/** 只看 UA 得出的机型 */
const uaKind = () => {
  const ua = (typeof navigator !== 'undefined' && navigator.userAgent) || ''
  if (UA_TABLET.test(ua)) return 'tablet'
  if (UA_PHONE.test(ua)) return 'phone'
  return 'desktop'
}

/** 触摸设备：用来决定是否放大按钮点击热区 */
const isTouch = computed(() => {
  if (typeof window === 'undefined') return false
  return (navigator.maxTouchPoints || 0) > 0 || 'ontouchstart' in window
})

/** 识别依据，界面上可以解释给用户看 */
const detectedBy = computed(() => {
  if (mode.value !== 'auto') return 'manual'
  const kind = uaKind()
  if (kind === 'phone') return 'ua'
  if (kind === 'tablet') return viewportWidth.value < TABLET_MAX_WIDTH ? 'width' : 'ua'
  return viewportWidth.value < MOBILE_MAX_WIDTH ? 'width' : 'none'
})

/** 最终是否用移动端布局 */
const isMobile = computed(() => {
  if (mode.value === 'mobile') return true
  if (mode.value === 'desktop') return false
  const kind = uaKind()
  if (kind === 'phone') return true
  const w = viewportWidth.value
  if (w < MOBILE_MAX_WIDTH) return true
  if (kind === 'tablet' && w < TABLET_MAX_WIDTH) return true
  return false
})

/** 机型中文名（给人看的） */
const deviceKindLabel = computed(() => {
  const kind = uaKind()
  return kind === 'phone' ? '手机' : kind === 'tablet' ? '平板' : '电脑'
})

/** 当前生效的版式说明，例如「手机端」/「电脑端」 */
const layoutLabel = computed(() => (isMobile.value ? '手机端' : '电脑端'))

/** 把识别结果落到 <html> 上，供纯 CSS 使用 */
const applyClass = () => {
  if (typeof document === 'undefined') return
  const el = document.documentElement
  el.classList.toggle('cf-device-mobile', isMobile.value)
  el.classList.toggle('cf-device-desktop', !isMobile.value)
  el.classList.toggle('cf-device-touch', isTouch.value)
  el.dataset.device = isMobile.value ? 'mobile' : 'desktop'
}

const sync = () => {
  viewportWidth.value = window.innerWidth
  applyClass()
}

let bound = false
/** 只在 main.js 调一次：绑定视口变化监听 + 首次应用类名 */
export function initDevice() {
  if (typeof window === 'undefined') return
  sync()
  if (bound) return
  bound = true
  window.addEventListener('resize', sync, { passive: true })
  // 手机横竖屏切换、地址栏收起，resize 有时不触发，补一道 orientationchange
  window.addEventListener('orientationchange', () => setTimeout(sync, 120), { passive: true })
}

/** 手动指定版式；传 'auto' 恢复自动识别 */
export function setDeviceMode(next) {
  const v = next === 'mobile' || next === 'desktop' ? next : 'auto'
  mode.value = v
  try {
    if (v === 'auto') localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, v)
  } catch (e) {
    /* 隐私模式下写不了，忽略即可，本次会话仍然生效 */
  }
  applyClass()
}

/**
 * 组件里用这个：
 *   const { isMobile } = useDevice()
 * isMobile 是响应式的，窗口变宽变窄时会自动重新渲染。
 */
export function useDevice() {
  return {
    isMobile,
    isTouch,
    mode: readonly(mode),
    deviceMode: mode,
    setDeviceMode,
    viewportWidth: readonly(viewportWidth),
    detectedBy,
    deviceKindLabel,
    layoutLabel
  }
}
