import { createApp } from 'vue'
// Element Plus 改为按需引入（见 vite.config.js）：组件与 v-loading 指令连同样式由插件按模板用量自动引入。
// 例外是在 script 里命令式调用的 ElMessage（各页面与 src/api 拦截器都是手写 import），
// 插件不会替它们带样式，这里显式引入一次，否则提示框会没有样式。
import 'element-plus/es/components/message/style/css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(router)
app.mount('#app')
