<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { defaultDateRange } from '../utils/bizDate'

const loading = ref(false)
const effect = ref(null)
const error = ref('')
const range = ref([])

const STRATEGY_LABEL = {
  FIXED: '固定价格（基线）',
  RULE: '规则折扣（基线）',
  MODEL: '模型定价（本项目）'
}

function pct(v) {
  if (v === null || v === undefined) return '-'
  return (Number(v) * 100).toFixed(1) + '%'
}

function money(v) {
  if (v === null || v === undefined) return '-'
  return '¥' + Number(v).toFixed(2)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const params = {}
    if (range.value && range.value.length === 2) {
      params.from = range.value[0]
      params.to = range.value[1]
    }
    effect.value = await api.effect(params)
  } catch (e) {
    error.value = '效果数据获取失败：' + (e?.message || e)
    effect.value = null
  } finally {
    loading.value = false
  }
}

/** 把后端可能返回的 {strategy:{...}} 或数组统一成表格行 */
function strategyRows() {
  const data = effect.value
  if (!data) return []
  if (Array.isArray(data.byStrategy)) return data.byStrategy
  if (data.byStrategy && typeof data.byStrategy === 'object') {
    return Object.entries(data.byStrategy).map(([strategy, v]) => ({ strategy, ...v }))
  }
  // 单策略扁平结构
  if (data.scrapRate !== undefined || data.grossProfit !== undefined) {
    return [{ strategy: data.strategy || 'CURRENT', ...data }]
  }
  return []
}

onMounted(() => {
  range.value = defaultDateRange(30)
  load()
})
</script>

<template>
  <div>
    <h2 class="page-title">效果看板</h2>
    <p class="page-desc">
      对比不同定价策略的损耗率与毛利。系统只给建议，最终售价由店主在「定价建议」页确认。
    </p>

    <el-card shadow="never">
      <div class="toolbar">
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
        />
        <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
      </div>

      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />

      <el-empty v-if="!loading && !error && strategyRows().length === 0"
                description="暂无效果数据：请先生成定价建议并回流销售数据" />

      <el-table v-if="strategyRows().length" :data="strategyRows()" border stripe>
        <el-table-column label="策略">
          <template #default="{ row }">
            {{ STRATEGY_LABEL[row.strategy] || row.strategy }}
          </template>
        </el-table-column>
        <el-table-column label="损耗率">
          <template #default="{ row }">{{ pct(row.scrapRate) }}</template>
        </el-table-column>
        <el-table-column label="缺货率">
          <template #default="{ row }">{{ pct(row.stockoutRate) }}</template>
        </el-table-column>
        <el-table-column label="毛利">
          <template #default="{ row }">{{ money(row.grossProfit) }}</template>
        </el-table-column>
        <el-table-column label="营收">
          <template #default="{ row }">{{ money(row.revenue) }}</template>
        </el-table-column>
        <el-table-column label="销量">
          <template #default="{ row }">{{ row.soldQty ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="报废量">
          <template #default="{ row }">{{ row.scrapQty ?? '-' }}</template>
        </el-table-column>
      </el-table>

      <el-table v-if="effect?.daily?.length" :data="effect.daily" border stripe style="margin-top: 16px">
        <el-table-column prop="bizDate" label="日期" width="130" />
        <el-table-column label="策略" width="150">
          <template #default="{ row }">{{ STRATEGY_LABEL[row.strategy] || row.strategy }}</template>
        </el-table-column>
        <el-table-column label="损耗率">
          <template #default="{ row }">{{ pct(row.scrapRate) }}</template>
        </el-table-column>
        <el-table-column label="毛利">
          <template #default="{ row }">{{ money(row.grossProfit) }}</template>
        </el-table-column>
        <el-table-column label="营收">
          <template #default="{ row }">{{ money(row.revenue) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 16px; }
</style>
