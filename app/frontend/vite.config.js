import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

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
  plugins: [vue()],
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
