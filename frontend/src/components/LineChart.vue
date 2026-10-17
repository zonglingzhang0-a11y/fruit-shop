<script setup>
// 轻量折线图（手写 SVG，不引入图表库）：多条序列共用一个 y 轴；悬停时竖线对齐最近的日期，
// 一个提示框列出该日所有序列的值。图例常驻，序列 ≤ 4 条且末端不挤时在线尾直接标值。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  // [{ key, label, color, values: { [x]: number|null } }]
  series: { type: Array, required: true },
  // 有序的 x（日期字符串）
  xs: { type: Array, required: true },
  format: { type: Function, default: (v) => String(v) },
  height: { type: Number, default: 220 },
  // 纵轴是否从 0 开始（比率、金额默认从 0 开始，避免夸大差异）
  zeroBased: { type: Boolean, default: true }
})

const wrap = ref(null)
const width = ref(640)
let observer = null
onMounted(() => {
  observer = new ResizeObserver((entries) => {
    const w = Math.round(entries[0].contentRect.width)
    if (w > 0) width.value = w
  })
  observer.observe(wrap.value)
})
onBeforeUnmount(() => observer?.disconnect())

const pad = { top: 12, right: 64, bottom: 26, left: 56 }
const innerW = computed(() => Math.max(width.value - pad.left - pad.right, 50))
const innerH = computed(() => props.height - pad.top - pad.bottom)

const allValues = computed(() => props.series.flatMap((s) => props.xs.map((x) => s.values[x]))
  .filter((v) => v !== null && v !== undefined && Number.isFinite(Number(v))).map(Number))

function niceStep(span, count) {
  const raw = span / count
  const mag = Math.pow(10, Math.floor(Math.log10(raw)))
  const norm = raw / mag
  const step = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10
  return step * mag
}

const yScale = computed(() => {
  const vals = allValues.value
  let lo = vals.length ? Math.min(...vals) : 0
  let hi = vals.length ? Math.max(...vals) : 1
  if (props.zeroBased) { lo = Math.min(lo, 0); hi = Math.max(hi, 0) }
  if (hi === lo) { hi = lo + 1 }
  const step = niceStep(hi - lo, 4)
  const min = Math.floor(lo / step) * step
  const max = Math.ceil(hi / step) * step
  const ticks = []
  for (let t = min; t <= max + step / 2; t += step) ticks.push(Number(t.toFixed(10)))
  return { min, max, ticks }
})

function x(i) {
  const n = props.xs.length
  return pad.left + (n <= 1 ? innerW.value / 2 : (i / (n - 1)) * innerW.value)
}
function y(v) {
  const { min, max } = yScale.value
  return pad.top + innerH.value - ((Number(v) - min) / (max - min)) * innerH.value
}

const paths = computed(() => props.series.map((s) => {
  let d = ''
  let pen = false
  props.xs.forEach((xv, i) => {
    const v = s.values[xv]
    if (v === null || v === undefined || !Number.isFinite(Number(v))) { pen = false; return }
    d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`
    pen = true
  })
  let lastIdx = -1
  props.xs.forEach((xv, i) => { const v = s.values[xv]; if (v !== null && v !== undefined) lastIdx = i })
  return { ...s, d, lastIdx, last: lastIdx >= 0 ? Number(s.values[props.xs[lastIdx]]) : null }
}))

// 线尾直接标值：任意两条线尾标签挤在 14px 以内就整体不标（不硬挪，交给图例与提示框）
const endLabels = computed(() => {
  const ends = paths.value.filter((p) => p.lastIdx >= 0)
  if (ends.length === 0 || ends.length > 4) return []
  const ys = ends.map((p) => y(p.last))
  for (let i = 0; i < ys.length; i++) {
    for (let j = i + 1; j < ys.length; j++) {
      if (Math.abs(ys[i] - ys[j]) < 14) return []
    }
  }
  return ends.map((p, i) => ({ key: p.key, x: x(p.lastIdx) + 8, y: ys[i] + 4, text: props.format(p.last) }))
})

const xLabels = computed(() => {
  const n = props.xs.length
  if (n === 0) return []
  const want = Math.max(2, Math.min(6, Math.floor(innerW.value / 90)))
  const step = Math.max(1, Math.round((n - 1) / (want - 1)))
  const out = []
  for (let i = 0; i < n; i += step) out.push(i)
  if (out[out.length - 1] !== n - 1) out.push(n - 1)
  return out.map((i) => ({ x: x(i), text: String(props.xs[i]).slice(5) }))
})

const hover = ref(-1)
function onMove(evt) {
  const rect = evt.currentTarget.getBoundingClientRect()
  const px = evt.clientX - rect.left
  const n = props.xs.length
  if (n === 0) return
  const i = n <= 1 ? 0 : Math.round(((px - pad.left) / innerW.value) * (n - 1))
  hover.value = Math.min(Math.max(i, 0), n - 1)
}
function onKey(evt) {
  const n = props.xs.length
  if (n === 0) return
  if (evt.key === 'ArrowRight') hover.value = Math.min((hover.value < 0 ? -1 : hover.value) + 1, n - 1)
  else if (evt.key === 'ArrowLeft') hover.value = Math.max((hover.value < 0 ? n : hover.value) - 1, 0)
}
const tip = computed(() => {
  if (hover.value < 0) return null
  const xv = props.xs[hover.value]
  const left = x(hover.value)
  return {
    x: xv,
    left: Math.min(left + 12, width.value - 190),
    rows: props.series.map((s) => ({ key: s.key, label: s.label, color: s.color, value: s.values[xv] }))
  }
})
</script>

<template>
  <div ref="wrap" class="line-chart">
    <div class="legend">
      <span v-for="s in series" :key="s.key" class="legend-item">
        <span class="key" :style="{ background: s.color }" />{{ s.label }}
      </span>
    </div>
    <div class="plot">
      <svg :width="width" :height="height" role="img" tabindex="0"
           @pointermove="onMove" @pointerleave="hover = -1" @focus="hover = xs.length - 1" @blur="hover = -1" @keydown="onKey">
        <g class="grid">
          <line v-for="t in yScale.ticks" :key="'g' + t" :x1="pad.left" :x2="pad.left + innerW" :y1="y(t)" :y2="y(t)" />
        </g>
        <g class="axis">
          <text v-for="t in yScale.ticks" :key="'t' + t" :x="pad.left - 8" :y="y(t) + 4" text-anchor="end">{{ format(t) }}</text>
          <text v-for="l in xLabels" :key="'x' + l.x" :x="l.x" :y="height - 6" text-anchor="middle">{{ l.text }}</text>
        </g>
        <path v-for="p in paths" :key="p.key" :d="p.d" :stroke="p.color" class="series-line" />
        <circle v-for="p in paths.filter((q) => q.lastIdx >= 0)" :key="'e' + p.key"
                :cx="x(p.lastIdx)" :cy="y(p.last)" r="4" :fill="p.color" class="end-dot" />
        <text v-for="l in endLabels" :key="'l' + l.key" :x="l.x" :y="l.y" class="end-label">{{ l.text }}</text>
        <g v-if="hover >= 0">
          <line :x1="x(hover)" :x2="x(hover)" :y1="pad.top" :y2="pad.top + innerH" class="crosshair" />
          <template v-for="p in paths" :key="'h' + p.key">
            <circle v-if="p.values[xs[hover]] !== null && p.values[xs[hover]] !== undefined"
                    :cx="x(hover)" :cy="y(p.values[xs[hover]])" r="4" :fill="p.color" class="end-dot" />
          </template>
        </g>
      </svg>
      <div v-if="tip" class="tooltip" :style="{ left: tip.left + 'px' }">
        <div class="tip-x">{{ tip.x }}</div>
        <div v-for="r in tip.rows" :key="r.key" class="tip-row">
          <span class="line-key" :style="{ background: r.color }" />
          <strong>{{ r.value === null || r.value === undefined ? '-' : format(r.value) }}</strong>
          <span class="tip-label">{{ r.label }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.line-chart { width: 100%; }
.legend { display: flex; gap: 16px; flex-wrap: wrap; font-size: 12px; color: #606266; margin-bottom: 6px; }
.legend-item { display: inline-flex; align-items: center; gap: 6px; }
.key { display: inline-block; width: 14px; height: 2px; border-radius: 1px; }
.plot { position: relative; }
svg { display: block; outline: none; }
.grid line { stroke: #ebeef5; stroke-width: 1; }
.axis text { font-size: 11px; fill: #909399; font-variant-numeric: tabular-nums; }
.series-line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
.end-dot { stroke: #fff; stroke-width: 2; }
.end-label { font-size: 11px; fill: #606266; }
.crosshair { stroke: #c0c4cc; stroke-width: 1; }
.tooltip { position: absolute; top: 4px; pointer-events: none; background: #fff; border: 1px solid #e4e7ed;
  border-radius: 6px; padding: 6px 10px; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08); font-size: 12px; min-width: 150px; }
.tip-x { color: #909399; margin-bottom: 4px; }
.tip-row { display: flex; align-items: center; gap: 6px; line-height: 20px; }
.tip-row strong { color: #303133; font-variant-numeric: tabular-nums; }
.tip-label { color: #909399; }
.line-key { display: inline-block; width: 12px; height: 2px; border-radius: 1px; }
</style>
