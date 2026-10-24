import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readdirSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

// 空 CSS chunk 兜底（2026-10-24 踩坑修复）：
// 组件里残留的空 <style> 块会让 vite 产出 0 字节的 CSS 文件，
// 而 Spring Boot 的静态资源处理器对 0 字节文件返回 404，
// 浏览器按 index.html 的 preload 链接去取就直接报错（控制台一片红）。
// 这里构建收尾时把 0 字节 .css 补一行注释，保证任何文件都非空。
function noEmptyCssPlugin() {
  return {
    name: 'caseflow-no-empty-css',
    closeBundle() {
      const dir = join(process.cwd(), 'dist', 'assets')
      let fixed = 0
      try {
        for (const f of readdirSync(dir)) {
          if (!f.endsWith('.css')) continue
          const p = join(dir, f)
          if (statSync(p).size === 0) {
            writeFileSync(p, '/* 占位：原 chunk 样式为空（Spring 对 0 字节静态资源返回 404） */\n')
            fixed++
            console.log(`[no-empty-css] ${f}: 0 字节 -> 已补占位内容`)
          }
        }
      } catch { /* dist/assets 不存在（无 chunk 构建）时忽略 */ }
      if (fixed > 0) console.log(`[no-empty-css] 共修复 ${fixed} 个空 CSS chunk`)
    }
  }
}

// 开发环境把 /api 代理到 Spring Boot，避免跨域配置
// 后端端口非 8080 时用环境变量覆盖：VITE_BACKEND=http://127.0.0.1:8095
//
// 关于 base：
// 生产打包后前端由后端 jar 一起托管，而后端的 context-path 是 /api，
// 所以页面地址是 http://内网IP:8080/api/，静态资源也必须挂在 /api/ 下，
// 否则 index.html 里引用的 /assets/xxx.js 会 404（页面白屏）。
// 构建时可用 VITE_BASE 覆盖；若改用 Nginx 托管（页面在根路径），构建时传 VITE_BASE=/
export default defineConfig(({ command }) => ({
  base: command === 'build' ? (process.env.VITE_BASE || '/api/') : '/',
  plugins: [vue(), noEmptyCssPlugin()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: false,
    proxy: {
      '/api': {
        target: process.env.VITE_BACKEND || 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500
  }
}))
