import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

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
  plugins: [
    vue(),
    // Element Plus 按需引入（Issue #4）：模板里用到哪个 <el-xxx> / v-loading，就只打包哪个组件和它的样式，
    // 不再整包引入 element-plus 与 element-plus/dist/index.css。
    // 注意：源码里手写的 `import { ElMessage } from 'element-plus'` 插件不会处理，样式不会自动带上，
    // 所以 ElMessage 的样式在 src/main.js 里显式引入；不写 import 直接用 ElMessage/ElMessageBox 等时，
    // 由 AutoImport 连同样式一起注入。
    AutoImport({
      resolvers: [ElementPlusResolver()],
      dts: false
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      // 只解析 Element Plus；src/components 下的自有组件仍由各页面显式 import
      dirs: [],
      dts: false
    })
  ],
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
