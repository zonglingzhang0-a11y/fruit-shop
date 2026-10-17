<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { defaultBizDate, shiftDate } from '../utils/bizDate'

const loading = ref(false)
const sending = ref(false)
const error = ref('')
// 默认用种子数据的最后一天（见 utils/bizDate.js 的说明）
const bizDate = ref(defaultBizDate())
const items = ref([])
const itemId = ref(null)
const discountRate = ref(0.7)

const preview = ref(null)
// 合规约束（docs/05）以后端实际配置为准：发送响应里带回单会员每日上限与静默时段
const policy = ref(null)

// 预览 = 建计划（push_task）。单品、日期或折扣一改，旧计划就不再对应页面上的内容，
// 必须作废、重新预览，避免"预览的是 A、发出去的是 B"
watch([itemId, bizDate, discountRate], () => {
  preview.value = null
})

async function loadItems() {
  try {
    const data = await api.items()
    items.value = Array.isArray(data) ? data : (data?.records || data?.list || [])
  } catch (e) {
    items.value = []
  }
}

async function doPreview() {
  if (!itemId.value) {
    ElMessage.warning('请选择单品')
    return
  }
  loading.value = true
  error.value = ''
  try {
    preview.value = await api.previewPush({
      itemId: itemId.value,
      bizDate: bizDate.value,
      discountRate: discountRate.value,
      targetCount: 20
    })
    // 预览会建一条"待发送"计划：刷新下方的计划列表（营业日不在当前区间里就把区间挪过去）
    if (bizDate.value < range.value[0] || bizDate.value > range.value[1]) {
      range.value = [shiftDate(bizDate.value, -6), bizDate.value]
    }
    await loadStats()
  } catch (e) {
    error.value = '文案预览失败：' + (e?.message || e)
    preview.value = null
  } finally {
    loading.value = false
  }
}

function sendSummary(res) {
  return `已发送 ${res?.sent ?? 0} 条；因频次上限跳过 ${res?.skippedByLimit ?? 0} 条，` +
    `因静默时段跳过 ${res?.skippedByQuietHours ?? 0} 条` +
    (res?.skippedByUnsubscribed ? `，已退订 ${res.skippedByUnsubscribed} 人` : '')
}

function notifySend(res) {
  policy.value = { dailyLimitPerMember: res?.dailyLimitPerMember, quietHours: res?.quietHours }
  if (res?.alreadySent) {
    ElMessage.info('这条计划之前已经发送过，没有重复发送')
  } else if ((res?.sent ?? 0) > 0) {
    ElMessage.success(sendSummary(res))
  } else {
    // 一条都没送达（无在售库存、静默时段、全部限频等）：用警告并把原因说出来，计划保持"待发送"可重发
    const reasons = [...new Set((res?.skipped || []).map((s) => s.reason).filter(Boolean))].join('；')
    ElMessage.warning({ message: `${sendSummary(res)}${reasons ? '。原因：' + reasons : ''}`, duration: 6000 })
  }
}

async function sendTask(taskId, taskBizDate) {
  sending.value = true
  try {
    // 发送预览时建好的那条计划（taskId），而不是再现拼一条新计划
    const res = await api.sendPush({ taskId, bizDate: taskBizDate || bizDate.value, discountRate: discountRate.value })
    notifySend(res)
    if (preview.value?.taskId === taskId && res?.taskStatus === 'SENT') {
      // 计划已发送，再点也只会命中幂等，直接收起预览
      preview.value = null
    }
    await loadStats()
  } catch (e) {
    // 错误提示由拦截器统一弹出
  } finally {
    sending.value = false
  }
}

function doSend() {
  if (preview.value?.taskId) sendTask(preview.value.taskId, preview.value.bizDate)
}

function copyText(text) {
  navigator.clipboard?.writeText(text || '')
  ElMessage.success('已复制，可直接粘贴到顾客微信群')
}

// ------------------------------------------------------------------ 推送计划与效果
const range = ref([shiftDate(defaultBizDate(), -6), defaultBizDate()])
const stats = ref(null)
const statsLoading = ref(false)
const statsError = ref('')
const attributing = ref(false)
const logs = ref({}) // taskId → 逐条发送记录
const logsLoading = ref({})
const opening = ref({})

const tasks = computed(() => [...(stats.value?.byTask || [])].reverse()) // 新的在上

async function loadStats() {
  if (!range.value?.[0] || !range.value?.[1]) return
  statsLoading.value = true
  statsError.value = ''
  try {
    stats.value = await api.pushStats({ from: range.value[0], to: range.value[1] })
    logs.value = {}
  } catch (e) {
    statsError.value = '推送效果获取失败：' + (e?.message || e)
    stats.value = null
  } finally {
    statsLoading.value = false
  }
}

async function loadLogs(taskId) {
  logsLoading.value = { ...logsLoading.value, [taskId]: true }
  try {
    const rows = await api.pushTasks({ taskId })
    logs.value = { ...logs.value, [taskId]: rows?.[0]?.logs || [] }
  } catch (e) {
    logs.value = { ...logs.value, [taskId]: [] }
  } finally {
    logsLoading.value = { ...logsLoading.value, [taskId]: false }
  }
}

function onExpand(row, expanded) {
  if (expanded.some((r) => r.taskId === row.taskId) && !logs.value[row.taskId]) {
    loadLogs(row.taskId)
  }
}

async function markOpened(log) {
  opening.value = { ...opening.value, [log.logId]: true }
  try {
    const res = await api.markPushOpened({ logId: log.logId })
    ElMessage.success(res?.alreadyOpened ? '之前已标记过打开' : '已标记为打开')
    await loadStats()
    await loadLogs(log.taskId)
  } catch (e) {
    // 错误提示由拦截器统一弹出
  } finally {
    opening.value = { ...opening.value, [log.logId]: false }
  }
}

async function reattribute() {
  attributing.value = true
  try {
    const res = await api.attributePush({ from: range.value[0], to: range.value[1] })
    ElMessage.success(`扫描 ${res?.scanned ?? 0} 笔会员成交：新归因 ${res?.newlyAttributed ?? 0} 笔，` +
      `此前已归因 ${res?.alreadyAttributed ?? 0} 笔，未匹配 ${res?.unmatched ?? 0} 笔`)
    await loadStats()
  } catch (e) {
    // 错误提示由拦截器统一弹出
  } finally {
    attributing.value = false
  }
}

function pct(v) {
  return v === null || v === undefined ? '-' : (Number(v) * 100).toFixed(1) + '%'
}

const STATUS = { PLANNED: ['待发送', 'warning'], SENT: ['已发送', 'success'], CANCELLED: ['已取消', 'info'] }
function statusLabel(s) { return (STATUS[s] || [s])[0] }
function statusType(s) { return (STATUS[s] || [null, 'info'])[1] }

function skippedText(row) {
  const parts = []
  if (row.skippedLimit) parts.push(`限频 ${row.skippedLimit}`)
  if (row.skippedQuiet) parts.push(`静默 ${row.skippedQuiet}`)
  if (row.skippedOptOut) parts.push(`退订 ${row.skippedOptOut}`)
  return parts.join('、') || '-'
}

onMounted(() => {
  loadItems()
  loadStats()
})
</script>

<template>
  <div>
    <h2 class="page-title">推送消息</h2>
    <p class="page-desc">
      只做站内消息与「可复制到微信群」的文案，不接真实短信/微信通道。选人规则：买过该品类的会员优先，
      每人每天有条数上限、夜间静默时段不打扰，可退订<span v-if="policy?.dailyLimitPerMember">
      （当前配置：每人每天最多 {{ policy.dailyLimitPerMember }} 条，{{ policy.quietHours }} 静默）</span>。
    </p>

    <el-card shadow="never">
      <div class="toolbar">
        <span>营业日</span>
        <el-date-picker v-model="bizDate" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 150px" />
        <span>单品</span>
        <el-select v-model="itemId" filterable placeholder="输入名称搜索" style="width: 220px">
          <el-option v-for="i in items" :key="i.id" :label="i.itemName" :value="i.id" />
        </el-select>
        <span>折扣系数</span>
        <el-input-number v-model="discountRate" :min="0.5" :max="1" :step="0.1" :precision="2" />
        <el-button type="primary" :loading="loading" @click="doPreview">生成文案预览</el-button>
      </div>

      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />

      <el-alert
        v-if="preview && preview.priceNote"
        :title="preview.priceNote"
        :type="preview.priceSource === 'CONFIRMED' ? 'success' : 'warning'"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />

      <div v-if="preview" class="preview">
        <el-card shadow="never" class="copy-card">
          <template #header>
            <div class="copy-header">
              <span>群发文案（大模型生成位，可替换为真实 LLM）</span>
              <el-button size="small" @click="copyText(preview.content)">复制文案</el-button>
            </div>
          </template>
          <p class="title-line">{{ preview.title }}</p>
          <p class="content-line">{{ preview.content }}</p>
          <p v-if="preview.sendTimeHint" class="hint">{{ preview.sendTimeHint }}</p>
        </el-card>

        <div class="targets">
          <div class="summary">
            预计触达 {{ preview.targetCount ?? (preview.targets || []).length }} 人
            <span v-if="preview.taskId">（已建计划 #{{ preview.taskId }}，计划时间 {{ preview.planTime || '-' }}）</span>
          </div>
          <el-table :data="preview.targets || []" border size="small" max-height="300">
            <el-table-column prop="nickname" label="会员" width="120" />
            <el-table-column prop="categoryName" label="偏好品类" width="120" />
            <el-table-column prop="reason" label="入选理由" min-width="200" />
          </el-table>
          <el-button type="success" style="margin-top: 12px" :loading="sending" @click="doSend">
            发送站内消息
          </el-button>
        </div>
      </div>

      <el-empty v-if="!preview && !error" description="选择单品与折扣后生成预览" />
    </el-card>

    <el-card shadow="never" style="margin-top: 16px" class="effect-card">
      <template #header>
        <div class="copy-header">
          <span>推送计划与效果</span>
          <div class="toolbar inline">
            <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" :clearable="false"
                            range-separator="至" style="width: 260px" @change="loadStats" />
            <el-button :loading="statsLoading" @click="loadStats">刷新</el-button>
            <el-button :loading="attributing" @click="reattribute">重新归因</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="statsError" :title="statsError" type="warning" :closable="false" show-icon />

      <div v-if="stats" class="kpis">
        <div class="kpi">
          <div class="kpi-label">推送计划</div>
          <div class="kpi-value">{{ stats.taskCount }}</div>
          <div class="kpi-sub">已发送 {{ stats.sentTasks }} · 待发送 {{ stats.plannedTasks }}</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">送达（人次）</div>
          <div class="kpi-value">{{ stats.delivered }}</div>
          <div class="kpi-sub">另有群发文案 {{ stats.groupRows }} 条（不计入分母）</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">打开率</div>
          <div class="kpi-value">{{ pct(stats.openRate) }}</div>
          <div class="kpi-sub">打开 {{ stats.opened }} / 送达 {{ stats.delivered }}</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">转化率</div>
          <div class="kpi-value">{{ pct(stats.convertRate) }}</div>
          <div class="kpi-sub">{{ stats.convertWindowHours }} 小时内同单品成交 {{ stats.converted }}</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">被拦下</div>
          <div class="kpi-value">{{ stats.skippedLimit + stats.skippedQuiet + stats.skippedOptOut }}</div>
          <div class="kpi-sub">限频 {{ stats.skippedLimit }} · 静默 {{ stats.skippedQuiet }} · 退订 {{ stats.skippedOptOut }}</div>
        </div>
      </div>
      <p v-if="stats?.note" class="hint note">{{ stats.note }}</p>

      <el-table v-if="stats" :data="tasks" border stripe size="small" row-key="taskId" @expand-change="onExpand"
                empty-text="这段时间没有推送计划">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="logs">
              <el-table :data="logs[row.taskId] || []" size="small" border v-loading="logsLoading[row.taskId]"
                        empty-text="还没有发送记录（计划未发送）">
                <el-table-column label="会员" min-width="140">
                  <template #default="{ row: log }">
                    {{ log.memberLabel }}<span v-if="log.memberId" class="muted">（ID {{ log.memberId }}）</span>
                  </template>
                </el-table-column>
                <el-table-column prop="sentAt" label="发送时间" width="160" />
                <el-table-column label="送达" width="110">
                  <template #default="{ row: log }">
                    <el-tag v-if="log.delivered" size="small" type="success">送达</el-tag>
                    <el-tag v-else-if="log.skipReason" size="small" type="info">跳过：{{ log.skipReason }}</el-tag>
                    <!-- 全店群发文案行：没有具体会员，不算送达也不算被拦下（不计入打开率/转化率的分母） -->
                    <el-tag v-else size="small" effect="plain">群发文案</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="打开" width="200">
                  <template #default="{ row: log }">
                    <span v-if="log.opened">{{ log.openedAt }}</span>
                    <el-button v-else-if="log.delivered && log.memberId" size="small" link type="primary"
                               :loading="opening[log.logId]" @click="markOpened(log)">标记已打开</el-button>
                    <span v-else class="muted">-</span>
                  </template>
                </el-table-column>
                <el-table-column label="转化" min-width="160">
                  <template #default="{ row: log }">
                    <span v-if="log.converted">成交 #{{ log.relatedSaleId }}（{{ log.convertedAt }}）</span>
                    <span v-else class="muted">-</span>
                  </template>
                </el-table-column>
              </el-table>
              <p class="hint">"标记已打开"用于没有接真实通道时模拟会员打开了消息；转化由销售回流自动归因，也可点上方"重新归因"补算。</p>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="taskId" label="计划" width="70" />
        <el-table-column prop="bizDate" label="营业日" width="105" />
        <el-table-column prop="itemName" label="单品" min-width="100" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="计划 / 发送时间" width="160">
          <template #default="{ row }">{{ row.sentAt || row.planTime || '-' }}</template>
        </el-table-column>
        <el-table-column prop="delivered" label="送达" width="65" />
        <el-table-column label="打开率" width="80">
          <template #default="{ row }">{{ row.delivered ? pct(row.openRate) : '-' }}</template>
        </el-table-column>
        <el-table-column label="转化率" width="80">
          <template #default="{ row }">{{ row.delivered ? pct(row.convertRate) : '-' }}</template>
        </el-table-column>
        <el-table-column label="被拦下" width="140">
          <template #default="{ row }">{{ skippedText(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PLANNED'" size="small" type="primary" link :loading="sending"
                       @click="sendTask(row.taskId, row.bizDate)">发送</el-button>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 16px; flex-wrap: wrap; }
.toolbar.inline { margin-bottom: 0; }
.preview { display: grid; grid-template-columns: 1.2fr 1fr; gap: 16px; }
.copy-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.title-line { font-weight: 600; margin: 0 0 8px; }
.content-line { color: #606266; line-height: 1.7; white-space: pre-wrap; margin: 0; }
.summary { font-size: 13px; color: #606266; margin-bottom: 8px; }
.hint { font-size: 12px; color: #909399; margin: 8px 0 0; }
.hint.note { margin: 0 0 12px; }
.muted { color: #909399; font-size: 12px; }
.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin-bottom: 12px; }
.kpi { border: 1px solid #e4e7ed; border-radius: 6px; padding: 10px 12px; background: #fff; }
.kpi-label { font-size: 12px; color: #909399; }
.kpi-value { font-size: 22px; font-weight: 600; color: #303133; margin: 4px 0; font-variant-numeric: tabular-nums; }
.kpi-sub { font-size: 12px; color: #606266; }
.logs { padding: 4px 16px 8px 48px; }
</style>
