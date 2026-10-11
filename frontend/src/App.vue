<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const activeMenu = computed(() => route.path)
// 顾客端 H5 是独立版式：不带后台侧边栏与顶栏，直接铺满手机屏（演示时按 F12 切手机视口）
const standalone = computed(() => route.meta?.standalone === true)
</script>

<template>
  <!-- 顾客端 H5：独立布局 -->
  <router-view v-if="standalone" />

  <!-- 店长工作台 -->
  <el-container v-else class="layout">
    <el-header class="header">
      <div class="brand">
        <span class="logo">🍊</span>
        <div>
          <div class="title">临期果品定价与推送工作台</div>
          <div class="subtitle">建议由系统给出，定价权在店主手里</div>
        </div>
      </div>
      <el-tag type="success" effect="plain">决策辅助模式 · 不自动改价</el-tag>
    </el-header>

    <el-container>
      <el-aside width="210px" class="aside">
        <el-menu :default-active="activeMenu" router>
          <el-menu-item index="/">
            <span>效果看板</span>
          </el-menu-item>
          <el-menu-item index="/near-expiry">
            <span>临期清单</span>
          </el-menu-item>
          <el-menu-item index="/pricing">
            <span>定价建议</span>
          </el-menu-item>
          <el-menu-item index="/traffic">
            <span>客流时段</span>
          </el-menu-item>
          <el-menu-item index="/push">
            <span>推送消息</span>
          </el-menu-item>
          <el-menu-item index="/h5">
            <span>顾客端 H5 特价页 ↗</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style>
html, body, #app { height: 100%; margin: 0; }
body { background: #f5f7fa; font-family: "Microsoft YaHei", system-ui, sans-serif; }
.layout { height: 100%; }
.header {
  display: flex; align-items: center; justify-content: space-between;
  background: #fff; border-bottom: 1px solid #e4e7ed; height: 64px;
}
.brand { display: flex; align-items: center; gap: 12px; }
.logo { font-size: 28px; }
.title { font-size: 17px; font-weight: 600; color: #303133; }
.subtitle { font-size: 12px; color: #909399; }
.aside { background: #fff; border-right: 1px solid #e4e7ed; }
.main { padding: 18px; }
.page-title { font-size: 16px; font-weight: 600; margin: 0 0 4px; }
.page-desc { font-size: 13px; color: #909399; margin: 0 0 16px; }
</style>
