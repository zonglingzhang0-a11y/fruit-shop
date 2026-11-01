/**
 * 业务日期口径（重要，别在页面里直接 new Date()）。
 *
 * 背景：种子数据由 scripts/simulator.py 生成，业务日期是一段**历史区间**（默认
 * 2026-03-02 ~ 2026-06-29），而系统运行日期可能是几个月后。如果页面默认用"今天"，
 * 所有批次都会被判定为已过期（days_left 为负），临期清单会异常长、生成定价建议会
 * 一次性刷出几千条 5 折建议——这是演示时最容易翻车的地方（backend 已提醒）。
 *
 * 口径：
 *  - 后端列表类接口不传 bizDate 时取"数据里最新的一天"；
 *  - 前端默认就跟着后端走：不传 bizDate（用 getLatestBizDate()），而不是传今天；
 *  - 用户显式选了日期，才把该日期传下去。
 */

const SEED_END_DATE = '2026-06-29'

/** 今天（仅用于展示与"手动选日期"的场景，不建议作为默认业务日） */
export function today() {
  return new Date().toISOString().slice(0, 10)
}

/**
 * 默认业务日：优先问后端"最新有数据的一天"，拿不到就用种子数据的最后一天。
 * @param {object} params
 * @param {number} [params.fallbackDaysAgo] 兜底：今天往前推几天（没有种子数据时用）
 */
export function defaultBizDate() {
  return SEED_END_DATE
}

/** 'YYYY-MM-DD' 加减天数（按本地日历算，不经过 UTC，避免时区把日期挪一天） */
export function shiftDate(date, days) {
  const [y, m, d] = String(date).split('-').map(Number)
  const t = new Date(y, m - 1, d + days)
  const pad = (n) => String(n).padStart(2, '0')
  return `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())}`
}

/** 给日期选择器用的默认值：与 defaultBizDate 一致，语义更清晰 */
export function defaultDateRange(days = 30) {
  const end = new Date(SEED_END_DATE)
  const start = new Date(end)
  start.setDate(start.getDate() - (days - 1))
  return [start.toISOString().slice(0, 10), SEED_END_DATE]
}
