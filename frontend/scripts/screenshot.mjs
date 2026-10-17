/**
 * 前端页面截图 + 真实后端联调验证（证据用，不是装饰）。
 *
 * 做法：把后端起在 18080、前端构建产物用 vite preview 起在 5174（/api 由 vite 代理到 18080），
 * 然后用 Playwright 打开工作台各页面：拦截控制台错误与失败请求，逐页截图。
 *
 * 用法（在 frontend/ 下）：
 *   npm i -D playwright && npx playwright install chromium
 *   node scripts/screenshot.mjs
 * 产出：release/screenshots/*.png 与终端上的"每页是否有请求失败/控制台报错"报告。
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = resolve(here, '..', '..', 'release', 'screenshots')
mkdirSync(outDir, { recursive: true })

const BASE = process.env.WEB_BASE || 'http://localhost:5174'
// action：截图前先做的交互，模拟真实演示流程。
// 定价建议页默认没有数据（建议由后端"生成"，这正是要演示的链路），
// 所以先点「生成建议」，等表格出现再截图；点不了就如实记为问题。
const PAGES = [
  // 看板：等图表画出来（数据来自 /api/sales/effect；种子数据带三策略日统计）
  { hash: '#/', name: '01-dashboard', title: '效果看板', waitFor: '.line-chart svg' },
  { hash: '#/near-expiry', name: '02-near-expiry', title: '临期清单', waitFor: '.el-table' },
  {
    hash: '#/pricing',
    name: '03-pricing',
    title: '定价建议',
    waitFor: '.el-table',
    action: async (p) => {
      await p.getByRole('button', { name: '生成建议' }).click({ timeout: 10000 })
      await p.waitForTimeout(2500)
      // 完整走一遍"店主确认"：打开首条建议的确认框并提交。
      // 这一步很关键——顾客端 H5 只展示"店主已确认价格"的批次，
      // 没有决策就不会有特价品，H5 页面会是空的（这正是设计意图）。
      await p.getByRole('button', { name: '确认/改价' }).first().click({ timeout: 10000 })
      await p.waitForTimeout(800)
      await p.getByRole('button', { name: '提交决策' }).click({ timeout: 10000 })
      await p.waitForTimeout(2000)
    }
  },
  { hash: '#/traffic', name: '04-traffic', title: '客流时段', waitFor: '.bars' },
  // 推送页：等"推送计划与效果"的统计卡片出来（数据来自 /api/push/stats）
  { hash: '#/push', name: '05-push', title: '推送消息', waitFor: '.effect-card .kpi-value' },
  // 销售回流（Issue #4）：选一个单品，等批次下拉加载出 FIFO 默认批次再截图（只读到这一步，不提交，避免改动演示数据）
  {
    hash: '#/sales',
    name: '07-sales-feedback',
    title: '销售回流',
    waitFor: '.form',
    action: async (p) => {
      await p.locator('.form .el-select').first().click({ timeout: 10000 })
      await p.locator('.el-select-dropdown__item').first().click({ timeout: 10000 })
      await p.waitForTimeout(1500)
    }
  },
  // 顾客端 H5：切手机视口（375×812，iPhone X 尺寸）截图
  {
    hash: '#/h5',
    name: '06-h5-specials',
    title: '顾客端 H5 特价页',
    waitFor: '.h5-wrap',
    // 特价列表是异步加载的。判据必须是"加载完成"而不是"元素存在"——
    // 只等 .h5-state 会命中"正在加载今日特价…"那一帧（本机 IO 慢时特别容易）。
    // 所以直接等真正的数据行/空态文案，超时给足（后端首次查询较慢）。
    extraWait: '.h5-list li, .h5-state:not(:has-text("正在加载"))',
    extraWaitTimeout: 90000,
    viewport: { width: 375, height: 812 },
    isMobile: true
  }
]

const problems = []

// 可选：PLAYWRIGHT_CHROMIUM_PATH 指向已装好的 Chromium（例如云端/CI 预装的浏览器），不设就用 Playwright 自己下载的
const browser = await chromium.launch(
  process.env.PLAYWRIGHT_CHROMIUM_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH } : {}
)
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
const page = await context.newPage()

const consoleErrors = []
const failedRequests = []
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text())
})
page.on('requestfailed', (req) => {
  failedRequests.push(`${req.method()} ${req.url()} -> ${req.failure()?.errorText}`)
})
page.on('response', async (res) => {
  if (res.url().includes('/api/') && !res.ok()) {
    failedRequests.push(`${res.status()} ${res.url()}`)
  }
})

for (const p of PAGES) {
  consoleErrors.length = 0
  failedRequests.length = 0
  // 每页可以有自己的视口/设备形态（H5 用手机视口）
  if (p.viewport) {
    await page.setViewportSize(p.viewport)
  }
  const url = `${BASE}/${p.hash}`
  process.stdout.write(`\n=== ${p.title}  ${url}\n`)
  await page.goto(url, { waitUntil: 'networkidle', timeout: 60000 })
  // 先做页面专属交互（例如定价页要先点「生成建议」把数据造出来）
  if (p.action) {
    try {
      await p.action(page)
      console.log('  [OK]   页面交互完成')
    } catch (e) {
      problems.push(`${p.title}: 交互失败 ${e.message.split('\n')[0]}`)
      console.log(`  [WARN] 交互失败：${e.message.split('\n')[0]}`)
    }
  }
  // 等关键元素出现；出现不了就说明页面渲染有问题（记录但不中断，仍截图留证）
  try {
    await page.waitForSelector(p.waitFor, { timeout: 15000 })
    console.log('  [OK]   关键元素已渲染')
  } catch {
    problems.push(`${p.title}: 未渲染出 ${p.waitFor}`)
    console.log(`  [WARN] 未等到 ${p.waitFor}`)
  }
  // 页面专属的"数据到位"判据（异步加载的列表/空态）
  if (p.extraWait) {
    try {
      await page.waitForSelector(p.extraWait, { timeout: p.extraWaitTimeout || 12000 })
      console.log('  [OK]   数据已就绪')
    } catch {
      problems.push(`${p.title}: 未等到数据判据 ${p.extraWait}`)
      console.log(`  [WARN] 未等到 ${p.extraWait}`)
    }
  }
  // 表格类页面等数据行出现（后端有数据时应出现）
  if (p.waitFor === '.el-table') {
    try {
      await page.waitForSelector('.el-table__row', { timeout: 10000 })
      const rows = await page.locator('.el-table__row').count()
      console.log(`  [OK]   表格渲染 ${rows} 行`)
    } catch {
      problems.push(`${p.title}: 表格没有数据行`)
      console.log('  [WARN] 表格没有数据行（后端可能未就绪或无数据）')
    }
  }
  const bodyText = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 160)
  console.log(`  正文摘要：${bodyText}`)
  // 截图前等页面"稳定"：
  // 1) 等 Element Plus 的浮动提示消失（否则会飘在页面顶部，H5 截图尤其难看）；
  // 2) 再等一小会儿让异步渲染落定（曾拍到"正在加载今日特价…"这种瞬时态）。
  try {
    await page.waitForSelector('.el-message', { state: 'detached', timeout: 6000 })
  } catch { /* 没有提示或超时都继续 */ }
  await page.waitForTimeout(600)
  if (consoleErrors.length) {
    problems.push(`${p.title}: 控制台报错 ${consoleErrors.length} 条`)
    consoleErrors.slice(0, 5).forEach((e) => console.log(`  [ERR]  ${e.slice(0, 200)}`))
  }
  if (failedRequests.length) {
    problems.push(`${p.title}: 失败请求 ${failedRequests.length} 条`)
    failedRequests.slice(0, 5).forEach((e) => console.log(`  [ERR]  ${e}`))
  }
  const file = resolve(outDir, `${p.name}.png`)
  await page.screenshot({ path: file, fullPage: true })
  console.log(`  截图：${file}`)
}

await browser.close()

console.log('\n' + '='.repeat(70))
if (problems.length) {
  console.log(`截图完成，但有 ${problems.length} 个问题：`)
  problems.forEach((p) => console.log('  - ' + p))
  process.exit(1)
}
console.log(`截图完成：${PAGES.length} 个页面全部渲染成功，无控制台报错、无失败请求`)
