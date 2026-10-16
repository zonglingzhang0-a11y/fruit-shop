<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { defaultBizDate } from '../utils/bizDate'

const loading = ref(false)
const rows = ref([])
const error = ref('')
// 默认用种子数据的最后一天（见 utils/bizDate.js 的说明）
const bizDate = ref(defaultBizDate())
const strategy = ref('RULE')

const dialogVisible = ref(false)
const current = ref(null)
const decisionForm = ref({ finalPrice: 0, accepted: true, reason: '' })
const submitting = ref(false)

const STRATEGY_LABEL = {
  FIXED: '固定价格',
  RULE: '规则折扣',
  MODEL: '模型定价'
}

// 模型产物状态：选 MODEL 时告诉店主"这一天的建议是不是真的来自模型"，不可用会回退规则版
const modelStatus = ref(null)

async function loadModelStatus() {
  if (strategy.value !== 'MODEL') {
    modelStatus.value = null
    return
  }
  try {
    modelStatus.value = await api.modelStatus({ bizDate: bizDate.value })
  } catch (e) {
    modelStatus.value = { usable: false, message: '模型状态获取失败：' + (e?.message || e) }
  }
}

watch([strategy, bizDate], loadModelStatus)

const pendingRows = computed(() => rows.value.filter((r) => !r.decided))
const acceptedCount = computed(() => rows.value.filter((r) => r.decided && r.accepted !== false).length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.suggestions({ bizDate: bizDate.value, strategy: strategy.value })
    rows.value = Array.isArray(data) ? data : (data?.records || data?.list || [])
  } catch (e) {
    error.value = '建议获取失败：' + (e?.message || e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function generate() {
  const res = await api.generateSuggestions({ bizDate: bizDate.value, strategy: strategy.value })
  if (res?.strategyNote) {
    // 例如请求 MODEL 但部分批次回退了规则版：如实提示，不当成"模型定价成功"
    ElMessage.warning({ message: res.strategyNote, duration: 6000 })
  } else {
    ElMessage.success('建议已生成')
  }
  // 产物可能在打开页面之后才重新导出：生成完顺手刷新模型状态提示，和刚生成的建议对得上
  await Promise.all([load(), loadModelStatus()])
}

function openDecision(row) {
  current.value = row
  decisionForm.value = {
    finalPrice: Number(row.suggestedPrice),
    accepted: true,
    reason: ''
  }
  dialogVisible.value = true
}

async function submitDecision() {
  if (!current.value) return
  const suggested = Number(current.value.suggestedPrice)
  const finalPrice = Number(decisionForm.value.finalPrice)
  submitting.value = true
  try {
    await api.decide({
      suggestionId: current.value.id,
      finalPrice,
      accepted: Math.abs(finalPrice - suggested) < 0.005 && decisionForm.value.accepted,
      reason: decisionForm.value.reason || (Math.abs(finalPrice - suggested) < 0.005 ? '采纳系统建议' : '店主改价')
    })
    ElMessage.success('决策已记录')
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

function discountText(rate) {
  if (rate === null || rate === undefined) return '-'
  const r = Number(rate)
  // 与后端理由里的写法一致：1.0 显示"原价"（而不是"10.0 折"），0.8 显示"8 折"，0.85 显示"8.5 折"
  if (r >= 0.995) return '原价'
  return Number((r * 10).toFixed(1)) + ' 折'
}

function isFallback(row) {
  return row.effectiveStrategy && row.effectiveStrategy !== row.strategy
}

onMounted(() => {
  load()
  loadModelStatus()
})
</script>

<template>
  <div>
    <h2 class="page-title">定价建议</h2>
    <p class="page-desc">
      系统给出候选折扣档中最优的一档（不涨价，折扣系数 ≤ 1.0），并说明理由；店主可以原价采纳、改价或忽略，
      所有动作都会记录，用于事后比较「采纳建议」与「店主经验」的效果差异。
    </p>

    <el-card shadow="never">
      <div class="toolbar">
        <span>营业日</span>
        <el-date-picker v-model="bizDate" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
        <span>策略</span>
        <el-select v-model="strategy" style="width: 190px" @change="load">
          <el-option label="规则折扣（基线）" value="RULE" />
          <el-option label="模型定价（本项目）" value="MODEL" />
          <el-option label="固定价格（基线）" value="FIXED" />
        </el-select>
        <el-button :loading="loading" @click="load">查询</el-button>
        <el-button type="primary" @click="generate">生成建议</el-button>
      </div>

      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />

      <el-alert
        v-if="strategy === 'MODEL' && modelStatus"
        :type="modelStatus.usable ? 'success' : 'warning'"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      >
        <template #title>
          <span v-if="modelStatus.usable">
            模型可用：{{ modelStatus.artifactBizDate }} 的产物（{{ modelStatus.modelVersion }}，{{ modelStatus.generatedAt }} 生成，
            {{ modelStatus.suggestionCount }} 条建议）
          </span>
          <span v-else>{{ modelStatus.message }}；此时生成的 MODEL 建议会回退规则版，并在表格里标出</span>
        </template>
      </el-alert>

      <div v-if="rows.length" class="summary">
        共 {{ rows.length }} 条，待决策 {{ pendingRows.length }} 条，已采纳 {{ acceptedCount }} 条
      </div>

      <el-empty v-if="!loading && !error && rows.length === 0" description="暂无定价建议，点击「生成建议」" />

      <el-table v-if="rows.length" :data="rows" border stripe>
        <el-table-column prop="itemName" label="单品" min-width="110" />
        <el-table-column prop="batchNo" label="批次" width="140" />
        <el-table-column label="剩余天数" width="90">
          <template #default="{ row }">{{ row.daysLeft }}</template>
        </el-table-column>
        <el-table-column label="现存" width="80">
          <template #default="{ row }">{{ row.remainQty }}</template>
        </el-table-column>
        <el-table-column label="原价" width="90">
          <template #default="{ row }">¥{{ Number(row.originalPrice).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="建议价" width="100">
          <template #default="{ row }">
            <strong>¥{{ Number(row.suggestedPrice).toFixed(2) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="折扣" width="90">
          <template #default="{ row }">{{ discountText(row.discountRate) }}</template>
        </el-table-column>
        <el-table-column label="实际策略" width="136">
          <template #default="{ row }">
            <el-tag v-if="isFallback(row)" type="warning" size="small">回退{{ STRATEGY_LABEL[row.effectiveStrategy] || row.effectiveStrategy }}</el-tag>
            <span v-else>{{ STRATEGY_LABEL[row.effectiveStrategy || row.strategy] || row.strategy }}</span>
          </template>
        </el-table-column>
        <el-table-column label="期望销量" width="100">
          <template #default="{ row }">
            {{ row.expectedQty === null || row.expectedQty === undefined ? '-' : Number(row.expectedQty).toFixed(1) }}
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="理由（给店主看）" min-width="240" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.decided" type="success" size="small">已决策</el-tag>
            <el-tag v-else type="info" size="small">待决策</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" :disabled="row.decided" @click="openDecision(row)">
              确认/改价
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="确认定价（最终售价由店主决定）" width="460px">
      <div v-if="current" class="dialog-body">
        <p>
          <strong>{{ current.itemName }}</strong>（批次 {{ current.batchNo }}，剩余 {{ current.daysLeft }} 天）
        </p>
        <p class="hint">系统建议：¥{{ Number(current.suggestedPrice).toFixed(2) }}（{{ discountText(current.discountRate) }}）</p>
        <p class="hint">建议理由：{{ current.reason || '—' }}</p>
        <el-form label-width="90px">
          <el-form-item label="最终售价">
            <el-input-number v-model="decisionForm.finalPrice" :min="0.01" :step="0.5" :precision="2" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="decisionForm.reason" placeholder="例如：今天客流少，直接打 7 折" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitDecision">提交决策</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
.summary { font-size: 13px; color: #606266; margin-bottom: 8px; }
.dialog-body p { margin: 4px 0 8px; }
.hint { font-size: 13px; color: #909399; }
</style>
