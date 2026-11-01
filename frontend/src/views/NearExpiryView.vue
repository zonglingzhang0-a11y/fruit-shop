<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { defaultBizDate } from '../utils/bizDate'
import { notifyGenerateResult } from '../utils/generateResult'

const loading = ref(false)
const rows = ref([])
const error = ref('')
const days = ref(2)
// 默认用种子数据的最后一天：种子数据是历史区间，用"今天"会把所有批次判成已过期
const bizDate = ref(defaultBizDate())

const form = ref({
  itemId: null,
  arriveDate: defaultBizDate(),
  shelfLifeDays: 3,
  qty: 10,
  costPrice: 5
})
const submitting = ref(false)

// FR-06 报废人工登记：系统不自动淘汰，过期/腐烂的货由人在这里登记（整批报废后移出临期清单）
const SCRAP_REASONS = ['已过期', '腐烂变质', '磕碰损伤', '运输压坏']
const scrapVisible = ref(false)
const scrapping = ref(false)
const scrapRow = ref(null)
const scrapForm = ref({ whole: true, qty: 1, reason: '', operator: '店员' })

function openScrap(row) {
  scrapRow.value = row
  scrapForm.value = { whole: true, qty: Number(row.remainQty) || 1, reason: row.daysLeft < 0 ? '已过期' : '', operator: '店员' }
  scrapVisible.value = true
}

async function submitScrap() {
  const reason = (scrapForm.value.reason || '').trim()
  if (!reason) {
    ElMessage.warning('请写明报废原因')
    return
  }
  scrapping.value = true
  try {
    const res = await api.scrapBatch(scrapRow.value.batchId, {
      bizDate: bizDate.value,
      qty: scrapForm.value.whole ? undefined : scrapForm.value.qty,
      reason,
      operator: scrapForm.value.operator
    })
    ElMessage.success(res?.message || '已登记报废')
    scrapVisible.value = false
    await load()
  } catch (e) {
    // 错误提示由拦截器统一处理（例如报废量超过现存）
  } finally {
    scrapping.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.nearExpiry({ bizDate: bizDate.value, days: days.value })
    rows.value = Array.isArray(data) ? data : (data?.records || data?.list || [])
  } catch (e) {
    error.value = '临期清单获取失败：' + (e?.message || e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function submitBatch() {
  if (!form.value.itemId) {
    ElMessage.warning('请填写单品 id（可先在商品档案中确认）')
    return
  }
  submitting.value = true
  try {
    await api.createBatch(form.value)
    ElMessage.success('批次已登记')
    await load()
  } finally {
    submitting.value = false
  }
}

/** 新鲜度配色：<0.2 很危险，<0.4 需关注，其余正常（口径见 docs/07 第 1 节 A） */
function freshnessType(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return 'info'
  if (n < 0.2) return 'danger'
  if (n < 0.4) return 'warning'
  return 'success'
}

async function generate(strategy) {
  try {
    const res = await api.generateSuggestions({ bizDate: bizDate.value, strategy })
    notifyGenerateResult(res, strategy)
  } catch (e) {
    // 错误提示由拦截器统一处理
  }
}

onMounted(load)
</script>

<template>
  <div>
    <h2 class="page-title">临期清单</h2>
    <p class="page-desc">
      按批次记录到货日与保质期，剩余可售天数 ≤ {{ days }} 天进入临期清单。
      真实店主不会逐箱登记，因此系统按「品类 + 到货日」口径简化。
    </p>

    <el-card shadow="never">
      <div class="toolbar">
        <span>营业日</span>
        <el-date-picker v-model="bizDate" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 150px" />
        <span>临期阈值（天）</span>
        <el-input-number v-model="days" :min="0" :max="10" />
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
        <el-divider direction="vertical" />
        <el-button @click="generate('RULE')">生成规则版建议</el-button>
        <el-button @click="generate('MODEL')">生成模型版建议</el-button>
      </div>

      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />

      <el-empty v-if="!loading && !error && rows.length === 0" description="暂无临期批次" />

      <el-table v-if="rows.length" :data="rows" border stripe>
        <el-table-column prop="itemName" label="单品" min-width="120" />
        <el-table-column prop="categoryName" label="品类" width="110" />
        <el-table-column prop="batchNo" label="批次号" width="150" />
        <el-table-column prop="expireDate" label="到期日" width="110" />
        <el-table-column label="剩余天数" width="90">
          <template #default="{ row }">
            <el-tag :type="row.daysLeft <= 1 ? 'danger' : 'warning'">{{ row.daysLeft }}</el-tag>
          </template>
        </el-table-column>
        <!-- 临期判定指标（参考架构《临期判定》）：新鲜度 = 剩余天数 ÷ 标准保质期 -->
        <el-table-column label="新鲜度" width="100">
          <template #default="{ row }">
            <span v-if="row.freshness === null || row.freshness === undefined">-</span>
            <el-tag v-else :type="freshnessType(row.freshness)" effect="plain">
              {{ (Number(row.freshness) * 100).toFixed(0) }}%
            </el-tag>
          </template>
        </el-table-column>
        <!-- 预计可售天数 = 剩余库存 ÷ 近 7 天日均销量 -->
        <el-table-column label="预计可售(天)" width="115">
          <template #default="{ row }">
            <span v-if="row.estimatedSellableDays === null || row.estimatedSellableDays === undefined"
                  title="日均销量为 0：按现价卖不动">卖不动</span>
            <span v-else>{{ Number(row.estimatedSellableDays).toFixed(1) }}</span>
          </template>
        </el-table-column>
        <!-- 有风险 = 预计可售天数 > 剩余天数 -->
        <el-table-column label="有风险" width="95">
          <template #default="{ row }">
            <el-tag v-if="row.atRisk === true" type="danger" size="small">有风险</el-tag>
            <el-tag v-else-if="row.atRisk === false" type="success" size="small">可卖完</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="预计报废" width="100">
          <template #default="{ row }">
            <span v-if="row.expectedScrapQty === null || row.expectedScrapQty === undefined">-</span>
            <span v-else :class="{ 'warn-num': Number(row.expectedScrapQty) > 0 }">
              {{ Number(row.expectedScrapQty).toFixed(1) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="remainQty" label="现存" width="85" />
        <el-table-column label="成本/原价" width="110">
          <template #default="{ row }">
            {{ row.costPrice ?? '-' }} / {{ row.basePrice ?? '-' }}
          </template>
        </el-table-column>
        <el-table-column label="测算口径" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="muted">{{ row.metricsReason || '标准口径（近 7 天日均销量）' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="danger" plain @click="openScrap(row)">登记报废</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="scrapVisible" title="登记报废" width="460px">
      <template v-if="scrapRow">
        <p class="page-desc" style="margin-top: 0">
          {{ scrapRow.itemName }} · 批次 {{ scrapRow.batchNo }} · 现存 {{ scrapRow.remainQty }} · 剩余 {{ scrapRow.daysLeft }} 天
        </p>
        <el-form :model="scrapForm" label-width="80px">
          <el-form-item label="报废量">
            <el-radio-group v-model="scrapForm.whole">
              <el-radio :value="true">整批（{{ scrapRow.remainQty }}）</el-radio>
              <el-radio :value="false">部分</el-radio>
            </el-radio-group>
            <el-input-number v-if="!scrapForm.whole" v-model="scrapForm.qty" :min="0.01"
                             :max="Number(scrapRow.remainQty)" :precision="2" :step="1" style="margin-top: 8px" />
          </el-form-item>
          <el-form-item label="原因" required>
            <el-select v-model="scrapForm.reason" filterable allow-create default-first-option
                       placeholder="选择或输入原因" style="width: 100%">
              <el-option v-for="r in SCRAP_REASONS" :key="r" :label="r" :value="r" />
            </el-select>
          </el-form-item>
          <el-form-item label="登记人">
            <el-input v-model="scrapForm.operator" maxlength="32" />
          </el-form-item>
        </el-form>
        <p class="muted">整批报废后批次移出临期清单；报废量计入给这批定价的策略的当日损耗，看板的损耗率随之更新。</p>
      </template>
      <template #footer>
        <el-button @click="scrapVisible = false">取消</el-button>
        <el-button type="danger" :loading="scrapping" @click="submitScrap">确认报废</el-button>
      </template>
    </el-dialog>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header>登记到货批次（演示用）</template>
      <el-form :model="form" inline label-width="90px">
        <el-form-item label="单品 id">
          <el-input-number v-model="form.itemId" :min="1" />
        </el-form-item>
        <el-form-item label="到货日">
          <el-date-picker v-model="form.arriveDate" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
        </el-form-item>
        <el-form-item label="保质期(天)">
          <el-input-number v-model="form.shelfLifeDays" :min="1" :max="30" />
        </el-form-item>
        <el-form-item label="到货量">
          <el-input-number v-model="form.qty" :min="1" />
        </el-form-item>
        <el-form-item label="进货价">
          <el-input-number v-model="form.costPrice" :min="0.01" :step="0.5" :precision="2" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submitBatch">登记</el-button>
        </el-form-item>
      </el-form>
      <p class="page-desc" style="margin: 0">
        提示：单品 id 需已存在于 fruit_item 表（由种子数据或商品档案提供）。
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 16px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 12px; }
.warn-num { color: #e6a23c; font-weight: 600; }
</style>
