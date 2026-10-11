<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { defaultBizDate } from '../utils/bizDate'

/**
 * 顾客端 H5 特价页（参考架构《系统架构》第 4 行的"顾客端 H5 特价页与消息"）。
 *
 * 三条设计纪律（答辩会问，都写在这里也写在 docs/07）：
 * 1) **只展示店主已确认的价格**：数据来自 /api/batches/on-sale，该接口只返回存在
 *    pricing_decision 的批次，且价格取 final_price（店主实际定的），不是系统建议价。
 *    系统是决策辅助，店主没点头的价钱不能对外露出。
 * 2) **不采集任何个人信息**：页面不登录、不埋点、不要求定位；会员消息走站内/群发链路。
 * 3) **门店口径**：单价按"份/斤/盒"展示，明确写出"数量有限、售完为止"，避免价格承诺纠纷。
 */

const loading = ref(false)
const error = ref('')
const bizDate = ref(defaultBizDate())
const items = ref([])

function discountText(row) {
  const rate = row.discountRate ?? (row.finalPrice && row.originalPrice ? row.finalPrice / row.originalPrice : null)
  if (!rate) return ''
  const tenths = Math.round(Number(rate) * 10)
  return tenths >= 10 ? '' : `${tenths} 折`
}

function priceText(v) {
  if (v === null || v === undefined || v === '') return '—'
  return Number(v).toFixed(2)
}

function stockText(row) {
  const q = Number(row.remainQty)
  if (!Number.isFinite(q)) return ''
  if (q <= 0) return '已售完'
  if (q < 5) return `仅剩 ${Math.round(q)} ${row.unit || '份'}`
  return `今日特价 · ${Math.round(q)} ${row.unit || '份'}`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.onSale({ bizDate: bizDate.value })
    items.value = Array.isArray(data) ? data : (data?.records || [])
  } catch (e) {
    error.value = e?.message || String(e)
    items.value = []
  } finally {
    loading.value = false
  }
}

const savedText = computed(() => {
  let sum = 0
  items.value.forEach((r) => {
    const orig = Number(r.originalPrice)
    const fin = Number(r.finalPrice)
    if (Number.isFinite(orig) && Number.isFinite(fin) && orig > fin) sum += orig - fin
  })
  return sum.toFixed(2)
})

onMounted(load)
</script>

<template>
  <div class="h5-wrap">
    <header class="h5-head">
      <div class="shop-name">街边水果店 · 今日特价</div>
      <div class="shop-sub">当日新鲜 · 数量有限 · 售完为止</div>
      <div class="shop-date">{{ bizDate }}</div>
    </header>

    <div v-if="loading" class="h5-state">正在加载今日特价…</div>
    <div v-else-if="error" class="h5-state h5-error">
      加载失败：{{ error }}
      <div><button class="h5-btn" @click="load">重试</button></div>
    </div>
    <div v-else-if="items.length === 0" class="h5-state">
      今天暂时没有特价品，欢迎看看当季水果～
    </div>

    <ul v-else class="h5-list">
      <li v-for="row in items" :key="row.batchId ?? row.batchNo" class="h5-card">
        <div class="card-main">
          <div class="name-line">
            <span class="name">{{ row.itemName }}</span>
            <span v-if="row.categoryName" class="cat">{{ row.categoryName }}</span>
            <span v-if="discountText(row)" class="badge">{{ discountText(row) }}</span>
          </div>
          <div class="price-line">
            <span class="final">¥{{ priceText(row.finalPrice) }}</span>
            <span class="unit">/{{ row.unit || '份' }}</span>
            <span class="origin">原价 ¥{{ priceText(row.originalPrice) }}</span>
          </div>
          <div class="meta-line">
            <span>{{ stockText(row) }}</span>
            <span v-if="row.expireDate" class="dot">·</span>
            <span v-if="row.expireDate">保质期至 {{ row.expireDate }}</span>
          </div>
        </div>
      </li>
    </ul>

    <footer v-if="items.length" class="h5-foot">
      <div>今日共 {{ items.length }} 款特价，合计可省约 ¥{{ savedText }}</div>
      <div class="fine">价格由店主确认后公布；页面不收集任何个人信息。</div>
    </footer>
  </div>
</template>

<style scoped>
.h5-wrap {
  max-width: 480px;
  margin: 0 auto;
  min-height: 100vh;
  background: #f7f8fa;
  font-family: "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
  padding-bottom: 24px;
}
.h5-head {
  background: linear-gradient(135deg, #ff9a3c, #ff6b35);
  color: #fff;
  padding: 18px 16px 20px;
}
.shop-name { font-size: 20px; font-weight: 700; }
.shop-sub { font-size: 12px; opacity: .92; margin-top: 4px; }
.shop-date { font-size: 12px; opacity: .8; margin-top: 6px; }
.h5-state { padding: 48px 20px; text-align: center; color: #909399; font-size: 14px; }
.h5-error { color: #f56c6c; }
.h5-btn {
  margin-top: 12px; padding: 8px 18px; border: none; border-radius: 16px;
  background: #ff6b35; color: #fff; font-size: 13px;
}
.h5-list { list-style: none; margin: 0; padding: 12px; }
.h5-card {
  background: #fff; border-radius: 12px; padding: 14px; margin-bottom: 10px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, .05);
}
.name-line { display: flex; align-items: center; gap: 6px; }
.name { font-size: 16px; font-weight: 600; color: #303133; }
.cat { font-size: 11px; color: #909399; background: #f2f3f5; border-radius: 8px; padding: 1px 6px; }
.badge {
  margin-left: auto; font-size: 12px; font-weight: 700; color: #fff;
  background: #f56c6c; border-radius: 10px; padding: 2px 8px;
}
.price-line { margin-top: 8px; display: flex; align-items: baseline; gap: 4px; }
.final { font-size: 22px; font-weight: 700; color: #f56c6c; }
.unit { font-size: 12px; color: #909399; }
.origin { margin-left: auto; font-size: 12px; color: #c0c4cc; text-decoration: line-through; }
.meta-line { margin-top: 6px; font-size: 12px; color: #909399; }
.dot { margin: 0 4px; }
.h5-foot { padding: 6px 18px; font-size: 12px; color: #606266; text-align: center; }
.fine { margin-top: 4px; color: #c0c4cc; font-size: 11px; }
</style>
