// 把 SVG 渲染成 2 倍分辨率 PNG（答辩 PPT 用）。尺寸取自 SVG 自己的 width/height。
// 用法：node scripts/mis/render_png.mjs <in.svg> <out.png>
// 需要 Playwright 能找到 Chromium；也可以用环境变量 PLAYWRIGHT_CHROMIUM_PATH 指定已装好的浏览器。
import { readFileSync } from 'fs'
import { createRequire } from 'module'

const require = createRequire(import.meta.url)
let chromium
try {
  ({ chromium } = require('playwright'))
} catch {
  // 从 frontend/ 的依赖里找 playwright
  ({ chromium } = require(new URL('../../frontend/node_modules/playwright/index.js', import.meta.url).pathname))
}

const [svgPath, pngPath] = process.argv.slice(2)
const svg = readFileSync(svgPath, 'utf-8')
const w = Number(/width="(\d+)"/.exec(svg)?.[1] || 1200)
const h = Number(/height="(\d+)"/.exec(svg)?.[1] || 800)
const browser = await chromium.launch(process.env.PLAYWRIGHT_CHROMIUM_PATH
  ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH, args: ['--no-sandbox'] }
  : { args: ['--no-sandbox'] })
const page = await (await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 2 })).newPage()
await page.setContent(`<html><body style="margin:0">${svg}</body></html>`)
await page.locator('svg').first().screenshot({ path: pngPath })
await browser.close()
console.log(`[render] ${svgPath} → ${pngPath} (${w}x${h} @2x)`)
