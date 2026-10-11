import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发/预览期把 /api 代理到 Spring Boot，避免跨域。
//
// 端口来自环境变量 DSH_FRUIT_API_PORT（默认 8080），这样冒烟/截图脚本可以把后端
// 起在任意端口（例如 18080）而不必改代码：
//   $env:DSH_FRUIT_API_PORT=18080; npm run serve
const apiPort = process.env.DSH_FRUIT_API_PORT || '8080'
const proxy = {
  target: `http://localhost:${apiPort}`,
  changeOrigin: true
}

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: { '/api': proxy }
  },
  // vite preview 也走同一份代理，方便"构建产物 + 真后端"的端到端验证
  preview: {
    port: 5174,
    proxy: { '/api': proxy }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500
  }
})
