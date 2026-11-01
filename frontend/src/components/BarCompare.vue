<script setup>
// 策略对比横条（手写，不引入图表库）：每个策略一根细条，长度按该指标在各策略中的最大值归一，值标在条尾。
// 颜色跟着策略走（固定映射），不跟名次走；文字一律用正文色，颜色只在条上。
import { computed, ref } from 'vue'

const props = defineProps({
  // [{ key, label, color, value, note? }]
  rows: { type: Array, required: true },
  format: { type: Function, default: (v) => String(v) }
})

const max = computed(() => Math.max(...props.rows.map((r) => Math.abs(Number(r.value) || 0)), 0) || 1)
function width(v) {
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return '0%'
  return Math.max((n / max.value) * 100, 1) + '%'
}
const hover = ref(null)
</script>

<template>
  <div class="bars">
    <div v-for="r in rows" :key="r.key" class="bar-row" tabindex="0"
         @pointerenter="hover = r.key" @pointerleave="hover = null" @focus="hover = r.key" @blur="hover = null">
      <div class="label">{{ r.label }}</div>
      <!-- 条的可用长度统一留出 72px 给条尾数值：各条按同一比例尺，数值贴在各自条尾 -->
      <div class="track">
        <div class="fill" :class="{ lifted: hover === r.key }" :style="{ width: width(r.value), background: r.color }" />
        <span class="value" :style="{ left: `calc(${width(r.value)} + 6px)` }">
          {{ r.value === null || r.value === undefined ? '-' : format(r.value) }}
        </span>
      </div>
      <div v-if="hover === r.key && r.note" class="tip">{{ r.note }}</div>
    </div>
  </div>
</template>

<style scoped>
.bars { display: flex; flex-direction: column; gap: 10px; }
.bar-row { position: relative; display: grid; grid-template-columns: 112px 1fr; align-items: center; gap: 8px; outline: none; }
.label { font-size: 12px; color: #606266; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.track { position: relative; width: calc(100% - 72px); height: 16px; }
.fill { height: 16px; border-radius: 0 4px 4px 0; transition: filter 0.15s; }
.fill.lifted { filter: brightness(1.12); }
.value { position: absolute; top: 0; line-height: 16px; font-size: 12px; color: #303133; font-variant-numeric: tabular-nums; white-space: nowrap; }
.tip { position: absolute; left: 120px; top: 22px; z-index: 2; background: #fff; border: 1px solid #e4e7ed; border-radius: 6px;
  padding: 4px 8px; font-size: 12px; color: #606266; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08); pointer-events: none; }
</style>
