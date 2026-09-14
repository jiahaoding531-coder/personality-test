import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],

  server: {
    port: 5173,

    // ---------------------------------------------------------------
    // 开发代理：把 /api 开头的请求转发到后端。
    //
    // 为什么需要它：前端跑在 localhost:5173，后端在 localhost:8080，
    // 端口不同就是不同的「源」，浏览器会拦截跨域请求。
    //
    // 有了这个代理，前端代码里写 fetch('/api/questions') 就行——
    // 浏览器看到的是同源请求（都发往 5173），由 Vite 在服务端转发给 8080。
    // 这样开发时既不用管 CORS，代码也和将来部署到同域时完全一致。
    //
    // 后端其实也配了 CORS（见 CorsConfig.java），那是给「前端独立部署
    // 到别的域名」这种情况兜底的。但开发时走代理更干净。
    // ---------------------------------------------------------------
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // AI 生成需要 3~30 秒，默认超时不够，这里放宽到 2 分钟
        timeout: 120_000,
        proxyTimeout: 120_000,
      },
    },
  },
})
