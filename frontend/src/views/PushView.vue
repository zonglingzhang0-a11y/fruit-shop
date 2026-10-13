<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { defaultBizDate } from '../utils/bizDate'

const loading = ref(false)
const sending = ref(false)
const error = ref('')
// 默认用种子数据的最后一天（见 utils/bizDate.js 的说明）
const bizDate = ref(defaultBizDate())
const itemId = ref(null)
const discountRate = ref(0.7)

const preview = ref(null)
const history = ref([])

// 预览 = 建计划（push_task）。单品、日期或折扣一改，旧计划就不再对应页面上的内容，
// 必须作废、重新预览，避免"预览的是 A、发出去的是 B"
watch([itemId, bizDate, discountRate], () => {
  preview.value = null
})

// 合规约束（docs/05）：单会员每日上限、静默时段、可退订
const dailyLimit = 2
const quietHours = '22:00-08:00'

async function doPreview() {
  if (!itemId.value) {
    ElMessage.warning('请填写单品 id')
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
  } catch (e) {
    error.value = '文案预览失败：' + (e?.message || e)
    preview.value = null
  } finally {
    loading.value = false
  }
}

async function doSend() {
  if (!preview.value?.taskId) return
  sending.value = true
  try {
    // 发送预览时建好的那条计划（taskId），而不是再现拼一条新计划
    const res = await api.sendPush({
      taskId: preview.value.taskId,
      bizDate: preview.value.bizDate || bizDate.value,
      discountRate: discountRate.value
    })
    const summary = `已发送 ${res?.sent ?? 0} 条；因频次上限跳过 ${res?.skippedByLimit ?? 0} 条，因静默时段跳过 ${res?.skippedByQuietHours ?? 0} 条`
    if ((res?.sent ?? 0) > 0) {
      ElMessage.success(summary)
    } else {
      // 一条都没送达（无在售库存、静默时段、全部限频等）：用警告并把原因说出来，计划保持 PLANNED 可重发
      const reasons = [...new Set((res?.skipped || []).map((s) => s.reason).filter(Boolean))].join('；')
      ElMessage.warning({ message: `${summary}${reasons ? '。原因：' + reasons : ''}`, duration: 6000 })
    }
    history.value.unshift({ bizDate: bizDate.value, ...res, preview: preview.value })
    if (res?.taskStatus === 'SENT') {
      // 计划已发送，再点也只会命中幂等，直接收起预览
      preview.value = null
    }
  } catch (e) {
    ElMessage.error('发送失败：' + (e?.message || e))
  } finally {
    sending.value = false
  }
}

function copyText(text) {
  navigator.clipboard?.writeText(text || '')
  ElMessage.success('已复制，可直接粘贴到顾客微信群')
}

onMounted(() => {})
</script>

<template>
  <div>
    <h2 class="page-title">推送消息</h2>
    <p class="page-desc">
      只做站内消息与「可复制到微信群」的文案，不接真实短信/微信通道。选人规则：买过该品类的会员优先，
      每人每天最多 {{ dailyLimit }} 条，{{ quietHours }} 静默不打扰，可退订。
    </p>

    <el-card shadow="never">
      <div class="toolbar">
        <span>营业日</span>
        <el-date-picker v-model="bizDate" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
        <span>单品 id</span>
        <el-input-number v-model="itemId" :min="1" />
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
        </el-card>

        <div class="targets">
          <div class="summary">预计触达 {{ preview.targetCount ?? (preview.targets || []).length }} 人</div>
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

      <el-empty v-if="!preview && !error" description="填写单品与折扣后生成预览" />
    </el-card>

    <el-card v-if="history.length" shadow="never" style="margin-top: 16px">
      <template #header>本次会话发送记录</template>
      <el-table :data="history" border size="small">
        <el-table-column prop="bizDate" label="日期" width="120" />
        <el-table-column prop="taskId" label="计划" width="80" />
        <el-table-column label="发送" width="80">
          <template #default="{ row }">{{ row.sent ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="限频跳过" width="100">
          <template #default="{ row }">{{ row.skippedByLimit ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="静默跳过" width="100">
          <template #default="{ row }">{{ row.skippedByQuietHours ?? '-' }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 16px; flex-wrap: wrap; }
.preview { display: grid; grid-template-columns: 1.2fr 1fr; gap: 16px; }
.copy-header { display: flex; align-items: center; justify-content: space-between; }
.title-line { font-weight: 600; margin: 0 0 8px; }
.content-line { color: #606266; line-height: 1.7; white-space: pre-wrap; margin: 0; }
.summary { font-size: 13px; color: #606266; margin-bottom: 8px; }
</style>
