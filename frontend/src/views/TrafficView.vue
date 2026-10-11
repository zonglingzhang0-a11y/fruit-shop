<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { defaultBizDate } from '../utils/bizDate'

const loading = ref(false)
const error = ref('')
// 默认用种子数据的最后一天（见 utils/bizDate.js 的说明）
const date = ref(defaultBizDate())
const hours = ref([])
const peaks = ref(null)

const maxOrders = computed(() => Math.max(1, ...hours.value.map((h) => Number(h.orderCount) || 0)))

function barWidth(row) {
  const v = Number(row.orderCount) || 0
  return Math.max(2, Math.round((v / maxOrders.value) * 100)) + '%'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.trafficHourly({ date: date.value })
    hours.value = Array.isArray(data) ? data : (data?.records || data?.list || [])
  } catch (e) {
    error.value = '客流数据获取失败：' + (e?.message || e)
    hours.value = []
  }
  try {
    peaks.value = await api.trafficPeaks({ date: date.value })
  } catch (e) {
    peaks.value = null
  } finally {
    loading.value = false
  }
}

const peakHourList = computed(() => peaks.value?.peakHours || peaks.value?.peaks || [])
const pushHourList = computed(() => peaks.value?.suggestPushHours || [])

onMounted(load)
</script>

<template>
  <div>
    <h2 class="page-title">客流时段</h2>
    <p class="page-desc">
      不装摄像头也不买数据：用历史成交笔数代替真实人流，识别高峰时段，并在高峰前 1 小时把临期折扣推给合适的人。
      这是「动态感知」在课程条件下站得住的实现方式。
    </p>

    <el-card shadow="never">
      <div class="toolbar">
        <span>日期</span>
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
      </div>

      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />

      <el-descriptions v-if="peakHourList.length" :column="2" border style="margin-bottom: 16px">
        <el-descriptions-item label="高峰时段">
          <el-tag v-for="h in peakHourList" :key="h" type="danger" style="margin-right: 6px">{{ h }}:00</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="建议推送时间（高峰前 1 小时）">
          <el-tag v-for="h in pushHourList" :key="h" type="success" style="margin-right: 6px">{{ h }}:00</el-tag>
        </el-descriptions-item>
        <el-descriptions-item v-if="peaks?.note" label="说明" :span="2">{{ peaks.note }}</el-descriptions-item>
      </el-descriptions>

      <el-empty v-if="!loading && !error && hours.length === 0" description="暂无客流数据" />

      <div v-if="hours.length" class="bars">
        <div v-for="row in hours" :key="row.hourOfDay" class="bar-row">
          <div class="label">{{ String(row.hourOfDay).padStart(2, '0') }}:00</div>
          <div class="track">
            <div class="fill" :class="{ peak: row.isPeak }" :style="{ width: barWidth(row) }" />
          </div>
          <div class="value">{{ row.orderCount }} 笔 / {{ row.customerCount ?? '-' }} 人</div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 16px; }
.bars { display: flex; flex-direction: column; gap: 6px; }
.bar-row { display: flex; align-items: center; gap: 10px; font-size: 12px; }
.label { width: 52px; color: #606266; text-align: right; }
.track { flex: 1; background: #f0f2f5; border-radius: 3px; height: 16px; overflow: hidden; }
.fill { height: 100%; background: #409eff; border-radius: 3px; transition: width .3s; }
.fill.peak { background: #f56c6c; }
.value { width: 130px; color: #909399; }
</style>
