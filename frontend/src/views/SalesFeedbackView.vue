<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { defaultBizDate } from '../utils/bizDate'

// 销售回流录入（Issue #4）：选单品 → 选批次（不选就按 FIFO 扣最早到期的那批）→ 数量、成交价、是否退货、会员 → 提交。
// 提交带业务流水号 requestNo：网络超时后重试用同一个号，后端识别为重复提交、原样返回首笔结果，不会重复扣库存。
const bizDate = ref(defaultBizDate())
const items = ref([])
const batches = ref([])
const confirmedPrice = ref({}) // batchId → 店主当天确认的价格（来自 /batches/on-sale）
const loadingBatches = ref(false)
const submitting = ref(false)
const result = ref(null)
const history = ref([])

const form = ref({
  itemId: null,
  batchId: null,
  quantity: 1,
  unitPrice: null,
  isReturn: false,
  memberId: null
})

// 同一笔销售的流水号：提交失败（例如超时）后原样重试仍用它；内容一改就换新号，避免被当成"同号不同内容"的冲突
let pendingRequestNo = null

function newRequestNo() {
  if (globalThis.crypto?.randomUUID) {
    return 'WEB-' + globalThis.crypto.randomUUID()
  }
  // 非 https 的局域网地址拿不到 randomUUID，退回时间戳 + 随机串
  return 'WEB-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12)
}

const selectedItem = computed(() => items.value.find((i) => i.id === form.value.itemId) || null)
const selectedBatch = computed(() => batches.value.find((b) => b.batchId === form.value.batchId) || null)
const fifoBatch = computed(() => batches.value[0] || null)

function defaultPrice() {
  const batch = selectedBatch.value || fifoBatch.value
  if (batch && confirmedPrice.value[batch.batchId] != null) {
    return Number(confirmedPrice.value[batch.batchId])
  }
  const base = batch?.basePrice ?? selectedItem.value?.basePrice
  return base == null ? null : Number(base)
}

const priceHint = computed(() => {
  const batch = selectedBatch.value || fifoBatch.value
  if (batch && confirmedPrice.value[batch.batchId] != null) {
    return '默认填店主当天确认的价格'
  }
  return '默认填原价（这批当天没有店主确认的折扣价）'
})

async function loadItems() {
  const data = await api.items()
  items.value = Array.isArray(data) ? data : (data?.records || data?.list || [])
}

async function loadConfirmedPrices() {
  try {
    const rows = await api.onSale({ bizDate: bizDate.value })
    const map = {}
    for (const r of rows || []) map[r.batchId] = r.finalPrice
    confirmedPrice.value = map
  } catch (e) {
    confirmedPrice.value = {}
  }
}

async function loadBatches() {
  batches.value = []
  if (!form.value.itemId) return
  loadingBatches.value = true
  try {
    const data = await api.inStock({ itemId: form.value.itemId, bizDate: bizDate.value })
    batches.value = Array.isArray(data) ? data : []
    if (form.value.batchId && !batches.value.some((b) => b.batchId === form.value.batchId)) {
      form.value.batchId = null
    }
  } finally {
    loadingBatches.value = false
  }
}

watch(() => form.value.itemId, async () => {
  form.value.batchId = null
  await loadBatches()
  form.value.unitPrice = defaultPrice()
})
watch(() => form.value.batchId, () => {
  form.value.unitPrice = defaultPrice()
})
watch(bizDate, async () => {
  await Promise.all([loadBatches(), loadConfirmedPrices()])
  form.value.unitPrice = defaultPrice()
})
// 表单任何一项变了，就是另一笔销售：换新流水号
watch([form, bizDate], () => { pendingRequestNo = null }, { deep: true })

function batchLabel(b) {
  const left = b.daysLeft < 0 ? `已过期 ${-b.daysLeft} 天` : `剩 ${b.daysLeft} 天`
  return `${b.batchNo} · ${left} · 库存 ${Number(b.remainQty)}${b.unit || ''}`
}

async function submit() {
  if (!bizDate.value) return ElMessage.warning('请先选择营业日')
  if (!form.value.itemId) return ElMessage.warning('请选择单品')
  if (!(Number(form.value.quantity) > 0)) return ElMessage.warning('数量必须大于 0（退货请打开"退货"开关，数量仍填正数）')
  if (form.value.unitPrice == null || Number(form.value.unitPrice) < 0) return ElMessage.warning('请填写成交价')

  if (!pendingRequestNo) pendingRequestNo = newRequestNo()
  const payload = {
    bizDate: bizDate.value,
    itemId: form.value.itemId,
    batchId: form.value.batchId || undefined,
    quantity: Number(form.value.quantity),
    unitPrice: Number(form.value.unitPrice),
    isReturn: form.value.isReturn,
    memberId: form.value.memberId || undefined,
    requestNo: pendingRequestNo
  }
  submitting.value = true
  try {
    const res = await api.feedback(payload)
    result.value = res
    history.value.unshift({
      at: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
      itemName: selectedItem.value?.itemName,
      ...res
    })
    if (res.duplicate) {
      ElMessage.info('这笔已经记过账（同一流水号的重复提交），未重复扣库存')
    } else if (res.overSell) {
      ElMessage.warning(`库存不足：只扣了 ${res.soldQty}，请核对实际出货`)
    } else {
      ElMessage.success(res.isReturn ? '退货已登记' : '销售已登记')
    }
    pendingRequestNo = null // 成功了：下一笔用新号
    await loadBatches()
  } catch (e) {
    // 错误提示由拦截器统一弹出；流水号保留，原样重试不会重复记账
  } finally {
    submitting.value = false
  }
}

function attributionTag(r) {
  if (r.isReturn) return { type: 'info', text: '退货不归因' }
  if (r.convertedByPush) return { type: 'success', text: '推送带来的成交' }
  return { type: 'info', text: '未归因到推送' }
}

onMounted(async () => {
  await Promise.all([loadItems(), loadConfirmedPrices()])
})
</script>

<template>
  <div>
    <h2 class="page-title">销售回流</h2>
    <p class="page-desc">
      把每笔实际成交录进系统：扣减批次库存、按实际生效的定价策略记效果、并判断是不是推送带来的成交（同一会员、同一单品、推送后 3 小时内）。
      不选批次时按"最早到期先卖"自动挑批次。
    </p>

    <el-card shadow="never">
      <el-form label-width="96px" class="form" @submit.prevent>
        <el-form-item label="营业日">
          <el-date-picker v-model="bizDate" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 180px" />
        </el-form-item>
        <el-form-item label="单品">
          <el-select v-model="form.itemId" filterable placeholder="输入名称搜索" style="width: 280px">
            <el-option v-for="i in items" :key="i.id" :label="`${i.itemName}（${i.categoryName || '未分类'}）`" :value="i.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="批次">
          <el-select v-model="form.batchId" clearable :loading="loadingBatches" :disabled="!form.itemId"
                     :placeholder="fifoBatch ? `自动：${fifoBatch.batchNo}（最早到期）` : '该单品当前没有在售库存'"
                     style="width: 420px">
            <el-option v-for="b in batches" :key="b.batchId" :label="batchLabel(b)" :value="b.batchId" />
          </el-select>
          <div v-if="form.itemId && !loadingBatches && batches.length === 0" class="hint warn">
            该单品当前没有在售库存：仍可登记（只记成交、不扣库存），请核对是否漏登了入库
          </div>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="form.quantity" :min="0.01" :precision="2" :step="1" />
          <span class="unit">{{ selectedItem?.unit || '' }}</span>
          <el-switch v-model="form.isReturn" active-text="退货" style="margin-left: 16px" />
        </el-form-item>
        <el-form-item label="成交价">
          <el-input-number v-model="form.unitPrice" :min="0" :precision="2" :step="0.5" />
          <span class="unit">元 / {{ selectedItem?.unit || '单位' }}</span>
          <span v-if="form.itemId" class="hint">{{ priceHint }}</span>
        </el-form-item>
        <el-form-item label="会员 ID">
          <el-input-number v-model="form.memberId" :min="1" :controls="false" placeholder="可不填" style="width: 160px" />
          <span class="hint">填了才能判断是不是推送带来的成交；会员 ID 可在「推送消息」页的发送记录里查到</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submit">
            {{ form.isReturn ? '登记退货' : '登记销售' }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="result" shadow="never" style="margin-top: 16px" class="result">
      <template #header>本次结果</template>
      <el-alert v-if="result.overSell" type="warning" :closable="false" show-icon style="margin-bottom: 12px"
                :title="`库存不足：请求 ${result.requestedQty}，实际只扣了 ${result.soldQty}（不会扣成负数）`" />
      <el-alert v-if="result.duplicate" type="info" :closable="false" show-icon style="margin-bottom: 12px"
                title="同一流水号的重复提交：返回的是首笔结果，库存、流水和统计都没有重复写" />
      <el-descriptions :column="2" border>
        <el-descriptions-item label="批次">
          {{ result.batchNo || '（没有可扣的批次）' }}
          <el-tag v-if="result.batchAutoPicked" size="small" effect="plain" style="margin-left: 6px">自动挑选</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="扣减数量">{{ result.isReturn ? '退回 ' : '' }}{{ result.soldQty }}</el-descriptions-item>
        <el-descriptions-item label="批次剩余库存">
          {{ result.remainQty ?? '-' }}
          <el-tag v-if="result.batchStatus" size="small" :type="result.batchStatus === 'ON_SALE' ? 'success' : 'info'" style="margin-left: 6px">
            {{ result.batchStatus === 'ON_SALE' ? '在售' : result.batchStatus === 'SOLD_OUT' ? '已售罄' : '已报废' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="计入策略">{{ result.strategy || '（该批次没有定价建议，不计入策略统计）' }}</el-descriptions-item>
        <el-descriptions-item label="推送归因" :span="2">
          <el-tag size="small" :type="attributionTag(result).type">{{ attributionTag(result).text }}</el-tag>
          <span class="muted" style="margin-left: 8px">{{ result.attributionNote }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="流水号" :span="2"><span class="muted">{{ result.requestNo }}</span></el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card v-if="history.length" shadow="never" style="margin-top: 16px">
      <template #header>本页已登记（{{ history.length }} 笔）</template>
      <el-table :data="history" border stripe size="small">
        <el-table-column prop="at" label="登记时间" width="100" />
        <el-table-column prop="itemName" label="单品" min-width="110" />
        <el-table-column prop="batchNo" label="批次" min-width="170" />
        <el-table-column label="数量" width="90">
          <template #default="{ row }">{{ row.isReturn ? '-' : '' }}{{ row.soldQty }}</template>
        </el-table-column>
        <el-table-column prop="remainQty" label="剩余库存" width="90" />
        <el-table-column label="推送归因" width="140">
          <template #default="{ row }">
            <el-tag size="small" :type="attributionTag(row).type">{{ attributionTag(row).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="160">
          <template #default="{ row }">
            <el-tag v-if="row.duplicate" size="small" type="info">重复提交</el-tag>
            <el-tag v-if="row.overSell" size="small" type="warning">库存不足</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.form { max-width: 920px; }
.unit { margin-left: 8px; color: #606266; font-size: 13px; }
.hint { margin-left: 12px; color: #909399; font-size: 12px; }
.hint.warn { margin-left: 0; color: #e6a23c; width: 100%; }
.muted { color: #909399; font-size: 12px; }
</style>
