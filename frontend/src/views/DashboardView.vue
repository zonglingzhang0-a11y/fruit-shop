<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { defaultBizDate, shiftDate } from '../utils/bizDate'
import BarCompare from '../components/BarCompare.vue'
import LineChart from '../components/LineChart.vue'

const loading = ref(false)
const effect = ref(null)
const error = ref('')
const range = ref([shiftDate(defaultBizDate(), -29), defaultBizDate()])
const preset = ref(30)

// 策略颜色跟着策略走（固定映射，不随筛选或名次变化）；三色已用 dataviz 校验脚本验过色盲可分辨性
const STRATEGIES = [
  { key: 'FIXED', label: '固定价格（基线）', color: '#2a78d6' },
  { key: 'RULE', label: '规则折扣（基线）', color: '#eb6834' },
  { key: 'MODEL', label: '模型定价（本项目）', color: '#1baf7a' }
]
const STRATEGY_LABEL = Object.fromEntries(STRATEGIES.map((s) => [s.key, s.label]))
const REAL = { key: 'REAL', label: '实际销售（未按策略区分）', color: '#2a78d6' }

function pct(v) {
  if (v === null || v === undefined) return '-'
  return (Number(v) * 100).toFixed(1) + '%'
}
function money(v) {
  if (v === null || v === undefined) return '-'
  return '¥' + Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function moneyCompact(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '-'
  if (Math.abs(n) >= 10000) return '¥' + (n / 10000).toFixed(1) + '万'
  return '¥' + n.toFixed(0)
}
function qty(v) {
  return v === null || v === undefined ? '-' : Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 1 })
}

function usePreset(days) {
  preset.value = days
  const end = range.value?.[1] || defaultBizDate()
  range.value = [shiftDate(end, -(days - 1)), end]
  load()
}

// 连点预设时，慢的旧请求可能后回来：只采用最后一次请求的结果
let loadSeq = 0
async function load() {
  if (!range.value?.[0] || !range.value?.[1]) return
  const seq = ++loadSeq
  loading.value = true
  error.value = ''
  loadTodos(seq)
  try {
    const data = await api.effect({ from: range.value[0], to: range.value[1] })
    if (seq === loadSeq) effect.value = data
  } catch (e) {
    if (seq === loadSeq) {
      error.value = '效果数据获取失败：' + (e?.message || e)
      effect.value = null
    }
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

const strategyRows = computed(() => {
  const rows = effect.value?.byStrategy || []
  const order = STRATEGIES.map((s) => s.key)
  return [...rows].sort((a, b) => order.indexOf(a.strategy) - order.indexOf(b.strategy))
})
const byStrategy = computed(() => strategyRows.value.length > 0)

function compareRows(field, transform = (v) => v) {
  return strategyRows.value.map((r) => {
    const meta = STRATEGIES.find((s) => s.key === r.strategy) || { key: r.strategy, label: r.strategy, color: '#909399' }
    return { key: meta.key, label: meta.label, color: meta.color, value: transform(r[field], r),
      note: `${r.days} 天：销量 ${qty(r.soldQty)}，报废 ${qty(r.scrapQty)}，营收 ${money(r.revenue)}` }
  })
}
// 售出率 = 售出 ÷（售出 + 报废），与损耗率互补；单独画出来是为了直接回答"卖掉了多少"
const sellThrough = (v, r) => {
  const sold = Number(r.soldQty) || 0
  const scrap = Number(r.scrapQty) || 0
  return sold + scrap > 0 ? sold / (sold + scrap) : null
}

const days = computed(() => [...new Set((effect.value?.daily || []).map((d) => d.bizDate))].sort())
function trendSeries(field) {
  const daily = effect.value?.daily || []
  const keys = byStrategy.value ? STRATEGIES.filter((s) => daily.some((d) => d.strategy === s.key)) : [REAL]
  return keys.map((s) => {
    const values = {}
    for (const d of daily) {
      if (d.strategy === s.key || (!byStrategy.value && d.strategy === 'REAL')) values[d.bizDate] = Number(d[field])
    }
    return { key: s.key, label: s.label, color: s.color, values }
  })
}
const profitSeries = computed(() => trendSeries('grossProfit'))
const scrapSeries = computed(() => trendSeries('scrapRate'))

// ------------------------------------------------------------------ 待办入口（以区间最后一天为营业日）
const todoDate = computed(() => range.value?.[1] || defaultBizDate())
const todos = ref({ pending: null, atRisk: null, atRiskScrap: null, planned: null })

async function loadTodos(seq) {
  const bizDate = todoDate.value
  const [suggestions, atRisk, push] = await Promise.allSettled([
    api.suggestions({ bizDate }),
    api.atRisk({ bizDate }),
    api.pushStats({ from: shiftDate(bizDate, -6), to: bizDate })
  ])
  const list = suggestions.status === 'fulfilled' && Array.isArray(suggestions.value) ? suggestions.value : null
  const risk = atRisk.status === 'fulfilled' && Array.isArray(atRisk.value) ? atRisk.value : null
  if (seq !== loadSeq) return
  todos.value = {
    pending: list ? list.filter((s) => !s.decided).length : null,
    atRisk: risk ? risk.length : null,
    atRiskScrap: risk ? risk.reduce((sum, r) => sum + (Number(r.expectedScrapQty) || 0), 0) : null,
    planned: push.status === 'fulfilled' ? push.value?.plannedTasks ?? null : null
  }
}

onMounted(load)
</script>

<template>
  <div class="dashboard">
    <h2 class="page-title">效果看板</h2>
    <p class="page-desc">
      对比不同定价策略的毛利、损耗与售出情况。系统只给建议，最终售价由店主在「定价建议」页确认。
    </p>

    <div class="filters">
      <el-button-group>
        <el-button :type="preset === 7 ? 'primary' : ''" @click="usePreset(7)">近 7 天</el-button>
        <el-button :type="preset === 30 ? 'primary' : ''" @click="usePreset(30)">近 30 天</el-button>
        <el-button :type="preset === 90 ? 'primary' : ''" @click="usePreset(90)">近 90 天</el-button>
      </el-button-group>
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" :clearable="false"
                      range-separator="至" style="width: 260px" @change="preset = null; load()" />
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="todos">
      <router-link to="/pricing" class="todo">
        <div class="todo-label">待确认定价建议</div>
        <div class="todo-value">{{ todos.pending ?? '-' }}<span class="todo-unit"> 条</span></div>
        <div class="todo-sub">营业日 {{ todoDate }} · 去「定价建议」确认 →</div>
      </router-link>
      <router-link to="/near-expiry" class="todo">
        <div class="todo-label">高风险库存</div>
        <div class="todo-value">{{ todos.atRisk ?? '-' }}<span class="todo-unit"> 批</span></div>
        <div class="todo-sub">预计到期前卖不完，约报废 {{ qty(todos.atRiskScrap) }} · 去「临期清单」→</div>
      </router-link>
      <router-link to="/push" class="todo">
        <div class="todo-label">待发送推送计划</div>
        <div class="todo-value">{{ todos.planned ?? '-' }}<span class="todo-unit"> 个</span></div>
        <div class="todo-sub">近 7 天已预览未发送 · 去「推送消息」→</div>
      </router-link>
    </div>

    <el-card shadow="never" :class="{ refetching: loading && effect }">
      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
      <el-empty v-if="!loading && !error && effect && !effect.daily?.length"
                description="这段时间暂无效果数据：请先生成定价建议并回流销售数据" />

      <template v-if="effect && effect.daily?.length">
        <div class="hero">
          <div class="hero-label">区间毛利</div>
          <div class="hero-value">{{ money(effect.grossProfit) }}</div>
          <div class="hero-sub">{{ effect.from }} 至 {{ effect.to }} · 营收 {{ money(effect.revenue) }} · 成本 {{ money(effect.cost) }}</div>
        </div>
        <div class="tiles">
          <div class="tile">
            <div class="tile-label">损耗率</div>
            <!-- 没有策略日统计时按销售流水汇总：流水里没有报废数据，不能把 0 当成真实损耗率 -->
            <div class="tile-value">{{ byStrategy ? pct(effect.scrapRate) : '-' }}</div>
            <div class="tile-sub">{{ byStrategy ? `报废 ${qty(effect.scrapQty)} ÷（售出 + 报废）` : '销售流水不含报废数据' }}</div>
          </div>
          <div class="tile">
            <div class="tile-label">售出</div>
            <div class="tile-value">{{ qty(effect.soldQty) }}</div>
            <div class="tile-sub">报废 {{ qty(effect.scrapQty) }}</div>
          </div>
          <div class="tile">
            <div class="tile-label">缺货率</div>
            <div class="tile-value">{{ pct(effect.stockoutRate) }}</div>
            <div class="tile-sub">各日缺货率的平均</div>
          </div>
        </div>
        <p v-if="effect.note" class="note">{{ effect.note }}</p>
      </template>
    </el-card>

    <el-card v-if="effect && effect.daily?.length" shadow="never" class="section" :class="{ refetching: loading }">
      <template #header>策略对比</template>
      <div v-if="byStrategy" class="compare">
        <div class="compare-item">
          <div class="chart-title">毛利</div>
          <BarCompare :rows="compareRows('grossProfit', (v) => Number(v))" :format="moneyCompact" />
        </div>
        <div class="compare-item">
          <div class="chart-title">损耗率（越低越好）</div>
          <BarCompare :rows="compareRows('scrapRate', (v) => Number(v))" :format="pct" />
        </div>
        <div class="compare-item">
          <div class="chart-title">售出率 = 售出 ÷（售出 + 报废）</div>
          <BarCompare :rows="compareRows('soldQty', sellThrough)" :format="pct" />
        </div>
      </div>
      <el-alert v-else type="info" :closable="false" show-icon
                title="这段时间没有按策略记的统计（销售回流未关联到定价建议），下方趋势按实际销售流水汇总" />
    </el-card>

    <el-card v-if="effect && effect.daily?.length" shadow="never" class="section" :class="{ refetching: loading }">
      <template #header>每日趋势</template>
      <div class="trends">
        <div>
          <div class="chart-title">每日毛利</div>
          <LineChart :series="profitSeries" :xs="days" :format="moneyCompact" />
        </div>
        <div v-if="byStrategy">
          <div class="chart-title">每日损耗率</div>
          <LineChart :series="scrapSeries" :xs="days" :format="pct" />
        </div>
      </div>
    </el-card>

    <el-card v-if="effect && effect.daily?.length" shadow="never" class="section">
      <template #header>明细</template>
      <el-table v-if="byStrategy" :data="strategyRows" border stripe>
        <el-table-column label="策略" min-width="150">
          <template #default="{ row }">
            <span class="swatch" :style="{ background: (STRATEGIES.find((s) => s.key === row.strategy) || {}).color }" />
            {{ STRATEGY_LABEL[row.strategy] || row.strategy }}
          </template>
        </el-table-column>
        <el-table-column label="毛利"><template #default="{ row }">{{ money(row.grossProfit) }}</template></el-table-column>
        <el-table-column label="营收"><template #default="{ row }">{{ money(row.revenue) }}</template></el-table-column>
        <el-table-column label="损耗率"><template #default="{ row }">{{ pct(row.scrapRate) }}</template></el-table-column>
        <el-table-column label="售出率"><template #default="{ row }">{{ pct(sellThrough(null, row)) }}</template></el-table-column>
        <el-table-column label="缺货率"><template #default="{ row }">{{ pct(row.stockoutRate) }}</template></el-table-column>
        <el-table-column label="销量"><template #default="{ row }">{{ qty(row.soldQty) }}</template></el-table-column>
        <el-table-column label="报废量"><template #default="{ row }">{{ qty(row.scrapQty) }}</template></el-table-column>
        <el-table-column prop="days" label="天数" width="70" />
      </el-table>
      <el-collapse style="margin-top: 12px">
        <el-collapse-item :title="`逐日明细（${effect.daily.length} 行）`" name="daily">
          <el-table :data="effect.daily" border stripe size="small" max-height="420">
            <el-table-column prop="bizDate" label="日期" width="120" />
            <el-table-column label="策略" width="170">
              <template #default="{ row }">{{ STRATEGY_LABEL[row.strategy] || (row.strategy === 'REAL' ? REAL.label : row.strategy) }}</template>
            </el-table-column>
            <el-table-column label="毛利"><template #default="{ row }">{{ money(row.grossProfit) }}</template></el-table-column>
            <el-table-column label="营收"><template #default="{ row }">{{ money(row.revenue) }}</template></el-table-column>
            <el-table-column label="损耗率"><template #default="{ row }">{{ pct(row.scrapRate) }}</template></el-table-column>
            <el-table-column label="缺货率"><template #default="{ row }">{{ pct(row.stockoutRate) }}</template></el-table-column>
            <el-table-column label="销量"><template #default="{ row }">{{ qty(row.soldQty) }}</template></el-table-column>
            <el-table-column label="报废量"><template #default="{ row }">{{ qty(row.scrapQty) }}</template></el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </el-card>
  </div>
</template>

<style scoped>
.filters { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; }
.todos { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; margin-bottom: 14px; }
.todo { display: block; text-decoration: none; background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 12px 14px; transition: border-color 0.15s; }
.todo:hover, .todo:focus-visible { border-color: #409eff; }
.todo-label { font-size: 13px; color: #606266; }
.todo-value { font-size: 26px; font-weight: 600; color: #303133; margin: 4px 0; }
.todo-unit { font-size: 13px; font-weight: 400; color: #909399; }
.todo-sub { font-size: 12px; color: #909399; }
.hero-label, .tile-label { font-size: 13px; color: #606266; }
.hero-value { font-size: 48px; font-weight: 600; color: #303133; line-height: 1.2; margin: 2px 0; }
.hero-sub, .tile-sub { font-size: 12px; color: #909399; }
.tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-top: 16px; }
.tile { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 12px; }
.tile-value { font-size: 22px; font-weight: 600; color: #303133; margin: 4px 0; }
.note { font-size: 12px; color: #909399; margin: 12px 0 0; }
.section { margin-top: 16px; }
.compare { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 24px; }
.trends { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 24px; }
.chart-title { font-size: 13px; font-weight: 600; color: #303133; margin-bottom: 10px; }
.swatch { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 6px; vertical-align: -1px; }
.refetching { opacity: 0.6; transition: opacity 0.15s; }
</style>
