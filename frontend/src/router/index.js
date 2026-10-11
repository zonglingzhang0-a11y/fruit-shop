import { createRouter, createWebHashHistory } from 'vue-router'

// 用 hash 路由：打包后可直接静态托管，演示省事。
//
// 注意：这里刻意用**静态 import**，不用 `() => import('./views/Xxx.vue')` 懒加载。
// 原因：本机 Vite 6.4.4 + Rollup 4.64.3 组合下，动态 import .vue 文件会在 build 阶段
// 报 "Could not resolve ... .vue"（静态 import 正常，dev 模式也正常）。
// 课程项目页面数量少，静态引入带来的体积代价可以忽略，稳定优先。
// 若将来升级 Vite/Rollup 后要改回懒加载，请先跑 `npm run build` 验证。
import DashboardView from '../views/DashboardView.vue'
import NearExpiryView from '../views/NearExpiryView.vue'
import PricingView from '../views/PricingView.vue'
import TrafficView from '../views/TrafficView.vue'
import PushView from '../views/PushView.vue'
import H5SpecialsView from '../views/H5SpecialsView.vue'

const routes = [
  { path: '/', name: 'dashboard', component: DashboardView },
  { path: '/near-expiry', name: 'nearExpiry', component: NearExpiryView },
  { path: '/pricing', name: 'pricing', component: PricingView },
  { path: '/traffic', name: 'traffic', component: TrafficView },
  { path: '/push', name: 'push', component: PushView },
  // 顾客端 H5 特价页：同一套构建产物、独立手机版式（演示与截图都走这条路由）
  { path: '/h5', name: 'h5Specials', component: H5SpecialsView, meta: { standalone: true } }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
