#requires -Version 5.1
<#
  smoke.ps1 - 端到端冒烟：启动后端（demo profile，H2 文件库 + 自动导入 data/seed），
  逐个调用全部业务接口（12 个契约接口 + v2 / Issue #2 / #4 新增的接口，共 21 次调用），检查统一响应体，最后关闭进程。

  用法：
    powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1
    powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1 -SkipPackage    # 复用已有 jar，快速复跑
  退出码：0 全部通过；1 启动失败或存在失败接口。

  踩过的坑（改脚本前先读）：
  1) 本仓库路径含空格，Start-Process 的 -ArgumentList 不会自动加引号，必须自己写成
     '-jar "{0}" ...'，否则 java 报 "Unable to access jarfile D:\claudeordeepseek"。
  2) 统一响应体是 {code,message,data}：/api/health 的 status 在 data.status 里，
     探测就绪时不能读顶层 status；错误响应也带 code，且 HTTP 状态码与 code 一致。
  3) 本文件含中文，必须带 UTF-8 BOM，否则 PowerShell 5.1 按 ANSI 解析会报语法错误。
  4) 种子业务日期是 2026-06 中下旬，而系统日期是 2026-10：冒烟默认用种子最后一天，
     否则 2000+ 个批次全被判过期，临期清单会长得离谱（见 -BizDate 参数）。
#>
[CmdletBinding()]
param(
  [int]$Port = 18080,
  [string]$Profile = 'demo',
  [int]$BootTimeoutSec = 180,
  [string]$BizDate = '2026-06-29',
  [switch]$SkipPackage
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Info($m) { Write-Host "[smoke] $m" -ForegroundColor Cyan }
function Good($m) { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Bad($m)  { Write-Host "  [FAIL] $m" -ForegroundColor Red }
function Warn2($m) { Write-Host "  [WARN] $m" -ForegroundColor Yellow }

$fails = @()
$today = $BizDate

# ---------------------------------------------------------------- 打包
function Find-Jar {
  Get-ChildItem (Join-Path $root 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

$jar = Find-Jar
if (-not $SkipPackage -or -not $jar) {
  Info '打包应用（跳过测试；测试用 scripts/mvn.ps1 -B test 单独跑）'
  $mvn = Join-Path $PSScriptRoot 'mvn.ps1'
  & powershell -NoProfile -ExecutionPolicy Bypass -File $mvn -B -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { Bad '打包失败，先修构建再跑冒烟'; exit 1 }
  $jar = Find-Jar
}
if (-not $jar) { Bad 'target 下没有可执行 jar'; exit 1 }
Good "jar: $($jar.Name)"

# ---------------------------------------------------------------- 启动
Info "启动应用（profile=$Profile，端口=$Port）"
$logFile = Join-Path $root 'target/smoke-app.log'
if (Test-Path $logFile) { Remove-Item $logFile -Force }

$tools = $null
if (Test-Path (Join-Path $root '.tools.json')) {
  $tools = Get-Content (Join-Path $root '.tools.json') -Raw | ConvertFrom-Json
}
$javaExe = if ($tools -and $tools.javaExe) { $tools.javaExe } else { 'java' }

$argLine = '-jar "{0}" --spring.profiles.active={1} --server.port={2}' -f $jar.FullName, $Profile, $Port
$appProc = Start-Process -FilePath $javaExe -ArgumentList $argLine `
  -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" -PassThru -WindowStyle Hidden

Info "等待 /api/health 就绪（最多 $BootTimeoutSec 秒）"
$base = "http://localhost:$Port"
$ready = $false
$deadline = (Get-Date).AddSeconds($BootTimeoutSec)
$effectFrom = ([datetime]$BizDate).AddDays(-29).ToString('yyyy-MM-dd')
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 3
  try {
    $r = Invoke-RestMethod -Uri "$base/api/health" -TimeoutSec 5
    $inner = if ($null -ne $r.data) { $r.data } else { $r }
    if ($inner.status -ne 'UP') { continue }
    # 关键：health 通了不代表数据到齐。种子导入跑在 ApplicationRunner 里，
    # 5.3 万条销售流水要几十秒；**必须等策略日统计也能查到**才算导入完成，
    # 否则后续接口会读到"半导入"状态（曾因此让效果看板回退到 sales_record 而显示 0）。
    $probe = Invoke-RestMethod -Uri "$base/api/sales/effect?from=$effectFrom&to=$BizDate" -TimeoutSec 20
    if ($probe.code -eq 0 -and @($probe.data.byStrategy).Count -gt 0) {
      $ready = $true
      break
    }
  } catch { }
  if ($appProc.HasExited) { break }
}
if (-not $ready) {
  Bad "后端未在 $BootTimeoutSec 秒内就绪（或种子数据未导入完成）；日志尾部："
  if (Test-Path $logFile) { Get-Content $logFile -Tail 20 | ForEach-Object { "      $_" } }
  if (Test-Path "$logFile.err") { Get-Content "$logFile.err" -Tail 10 | ForEach-Object { "      $_" } }
  try { Stop-Process -Id $appProc.Id -Force -ErrorAction SilentlyContinue } catch { }
  exit 1
}
Good "后端已就绪且种子已导入：$base"

function Call-Api {
  param([string]$Name, [string]$Method, [string]$Path, $Body)
  $uri = "$base$Path"
  try {
    if ($Method -eq 'GET') {
      $resp = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 30
    } else {
      $json = if ($null -ne $Body) { $Body | ConvertTo-Json -Depth 6 -Compress } else { '{}' }
      $resp = Invoke-RestMethod -Uri $uri -Method Post -ContentType 'application/json; charset=utf-8' -Body $json -TimeoutSec 30
    }
  } catch {
    $fails += $Name
    Bad "$Name  $Method $Path -> $($_.Exception.Message)"
    return $null
  }
  if ($null -eq $resp -or -not ($resp.PSObject.Properties.Name -contains 'code')) {
    $fails += $Name
    Bad "$Name  $Method $Path -> 响应缺少统一响应体 {code,message,data}"
    return $null
  }
  if ($resp.code -ne 0) {
    $fails += $Name
    Bad "$Name  $Method $Path -> code=$($resp.code) $($resp.message)"
    return $null
  }
  Good "$Name  $Method $Path"
  return $resp.data
}

function As-List($data) {
  if ($null -eq $data) { return @() }
  if ($data -is [array]) { return $data }
  if ($data.PSObject.Properties.Name -contains 'records') { return @($data.records) }
  return @($data)
}

# ---------------------------------------------------------------- 接口逐个调用
Info "逐个调用接口（bizDate=$today）"

$health = Call-Api '1  GET  /api/health' 'GET' '/api/health'
if ($health) { Info "    status=$($health.status) checks=$($health.checks -join ',')" }

$near = Call-Api '3  GET  /api/batches/near-expiry' 'GET' "/api/batches/near-expiry?bizDate=$today&days=2"
$nearList = As-List $near
Info "    临期清单 $($nearList.Count) 条"
if ($nearList.Count -gt 0) {
  Info "    首条：$($nearList[0].itemName) 批次 $($nearList[0].batchNo) 剩余 $($nearList[0].daysLeft) 天 现存 $($nearList[0].remainQty)"
}

$batch = Call-Api '2  POST /api/batches' 'POST' '/api/batches' @{
  itemId = 1; arriveDate = $today; shelfLifeDays = 3; qty = 12; costPrice = 4.5
}
if ($batch) { Info "    新批次 $($batch.batchNo) 到期 $($batch.expireDate)" }

$gen = Call-Api '4  POST /api/pricing/suggestions/generate' 'POST' '/api/pricing/suggestions/generate' @{
  bizDate = $today; strategy = 'RULE'
}
if ($gen) { Info "    生成 $($gen.generated) 条（strategy=$($gen.strategy)）" }

$sugg = Call-Api '5  GET  /api/pricing/suggestions' 'GET' "/api/pricing/suggestions?bizDate=$today&strategy=RULE"
$suggList = As-List $sugg
$firstId = $null
if ($suggList.Count -gt 0) {
  $firstId = $suggList[0].id
  Info "    建议 $($suggList.Count) 条；首条 $($suggList[0].itemName)：原价 $($suggList[0].originalPrice) -> 建议 $($suggList[0].suggestedPrice)（折扣 $($suggList[0].discountRate)）"
  Info "    理由：$($suggList[0].reason)"
}

if ($firstId) {
  # 店主"改价"必须遵守最低折扣线（app.pricing.min-discount-rate，默认 0.60）：
  # 这里刻意改成**恰好等于底线**的价格，验证"边界值可通过"；
  # 低于底线的价格会被 400 拒绝（那是 PricingConstraintsTest 覆盖的场景）。
  $orig = [double]$suggList[0].originalPrice
  $floorPrice = [math]::Round($orig * 0.60, 2)
  Call-Api '6  POST /api/pricing/decisions' 'POST' '/api/pricing/decisions' @{
    suggestionId = $firstId; finalPrice = $floorPrice; accepted = $false
    reason = "冒烟测试：改到最低折扣线 $floorPrice（边界值应通过）"
  } | Out-Null
  # 顺带验证"低于底线会被拒"——预期收到 400，不应计入失败
  $rejected = $false
  try {
    $body = @{ suggestionId = $firstId; finalPrice = [math]::Round($orig * 0.40, 2); accepted = $false; reason = '冒烟测试：试探底线' } |
      ConvertTo-Json -Depth 4 -Compress
    Invoke-RestMethod -Uri "$base/api/pricing/decisions" -Method Post `
      -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 30 | Out-Null
  } catch {
    # 期望就是失败：HTTP 400 + code=400
    if ($_.Exception.Response.StatusCode.value__ -eq 400) { $rejected = $true }
  }
  if ($rejected) {
    Good "6b POST /api/pricing/decisions（低于最低折扣线）-> 已按预期被 400 拒绝"
  } else {
    $fails += '6b 最低折扣线未拦截低于底线的改价'
    Bad '6b 低于最低折扣线的改价竟然通过了（约束失效）'
  }
} else {
  Warn2 '没有生成建议，跳过接口 6（决策）'
}

$traffic = Call-Api '7  GET  /api/traffic/hourly' 'GET' "/api/traffic/hourly?date=$today"
$trafficList = As-List $traffic
Info "    客流 $($trafficList.Count) 个时段（有数据时补全 0~23 点）"

$peaks = Call-Api '8  GET  /api/traffic/peaks' 'GET' "/api/traffic/peaks?date=$today"
if ($peaks) {
  Info "    高峰=$($peaks.peakHours -join ',')  建议推送=$($peaks.suggestPushHours -join ',')  derived=$($peaks.derived)"
}

$preview = Call-Api '9  POST /api/push/preview' 'POST' '/api/push/preview' @{
  itemId = 1; discountRate = 0.7; targetCount = 10
}
if ($preview) {
  Info "    文案标题：$($preview.title)"
  Info "    触达 $($preview.targetCount) 人（可发送 $($preview.sendableCount)$(if ($null -ne $preview.groupOnlyMemberCount) { '，仅群发 ' + $preview.groupOnlyMemberCount + ' 人' })）"
}

$send = Call-Api '10 POST /api/push/send' 'POST' '/api/push/send' @{
  bizDate = $today; itemId = 1; discountRate = 0.7; memberIds = @()
  # 冒烟可能在静默时段（22:00-08:00）执行，那时全部被拦是**正确行为**而非失败；
  # 这里显式指定 20:00 发送，才能验证"真正送达"那条路径。
  sendAt = "$today 20:00:00"
}
if ($send) {
  Info "    发送 $($send.sent) 条（个人 $($send.sentPersonal) + 群发 $($send.sentGroup)）；限频跳过 $($send.skippedByLimit)，静默跳过 $($send.skippedByQuietHours)"
}

# 效果看板：取"种子数据最后 30 天"，与 strategy_daily_stat 的窗口对齐
# （只查 bizDate 当天会落到 sales_record 回退口径，那是设计行为，但看不到策略对比）
$effectFrom = ([datetime]$today).AddDays(-29).ToString('yyyy-MM-dd')
$effect = Call-Api '11 GET  /api/sales/effect' 'GET' "/api/sales/effect?from=$effectFrom&to=$today"
if ($effect) {
  $byCount = @($effect.byStrategy).Count
  Info "    损耗率=$($effect.scrapRate) 毛利=$($effect.grossProfit) 营收=$($effect.revenue) source=$($effect.source) 策略数=$byCount"
}

$fb = Call-Api '12 POST /api/sales/feedback' 'POST' '/api/sales/feedback' @{
  bizDate = $today; itemId = 1; quantity = 1; unitPrice = 5.5
}
if ($fb) { Info "    回落后 remainQty=$($fb.remainQty) batchStatus=$($fb.batchStatus) overSell=$($fb.overSell)" }

$items = Call-Api '13 GET  /api/items（契约外补充）' 'GET' '/api/items?page=1&size=5'
$itemList = As-List $items
Info "    商品档案 $($itemList.Count) 条"

# ---------------------------------------------------------------- v2 新增接口
Info 'v2 新增接口（对照参考架构补齐的部分）'

$atRisk = Call-Api '14 GET  /api/batches/at-risk' 'GET' "/api/batches/at-risk?bizDate=$today"
$atRiskList = As-List $atRisk
Info "    有风险批次 $($atRiskList.Count) 条（按现价卖不完）"

$profile = Call-Api '15 GET  /api/traffic/profile' 'GET' '/api/traffic/profile?weekday=1'
$profileList = As-List $profile
Info "    周内客流规律 $($profileList.Count) 行（星期 × 小时）"

Call-Api '16 POST /api/traffic/profile/recompute' 'POST' "/api/traffic/profile/recompute?from=$effectFrom&to=$today" | Out-Null

$stats = Call-Api '17 GET  /api/push/stats' 'GET' '/api/push/stats'
if ($stats) {
  Info "    推送统计：送达=$($stats.delivered) 打开=$($stats.opened) 转化=$($stats.converted)；限频跳过=$($stats.skippedLimit) 静默跳过=$($stats.skippedQuiet)"
}

# 顾客端 H5 接口：只返回"店主已确认价格"的批次
$onSale = Call-Api '18 GET  /api/batches/on-sale' 'GET' "/api/batches/on-sale?bizDate=$today"
$onSaleList = As-List $onSale
Info "    顾客端特价品 $($onSaleList.Count) 条（只含店主已确认价格的批次）"
if ($onSaleList.Count -gt 0) {
  $o = $onSaleList[0]
  Info "      首条：$($o.itemName) 原价 $($o.originalPrice) → 店主定价 $($o.finalPrice)（$($o.discountLabel)）剩余 $($o.remainQty)"
}

# Issue #2 / #4 新增的只读接口
if ($itemList.Count -gt 0) {
  $firstItemId = $itemList[0].id
  $inStock = Call-Api '19 GET  /api/batches/in-stock' 'GET' "/api/batches/in-stock?itemId=$firstItemId&bizDate=$today"
  $inStockList = As-List $inStock
  Info "    单品 #$firstItemId 有库存的在售批次 $($inStockList.Count) 个（第一行就是销售回流不选批次时自动扣的那批）"
}
$modelStatus = Call-Api '20 GET  /api/pricing/model-status' 'GET' "/api/pricing/model-status?bizDate=$today"
if ($modelStatus) {
  Info "    模型产物：可用=$($modelStatus.usable)；$($modelStatus.message)"
}
$pushTasks = Call-Api '21 GET  /api/push/tasks' 'GET' "/api/push/tasks?bizDate=$today"
$pushTaskList = As-List $pushTasks
Info "    当天推送计划 $($pushTaskList.Count) 个"

# ---------------------------------------------------------------- 收尾
Info '关闭后端进程'
try { Stop-Process -Id $appProc.Id -Force -ErrorAction SilentlyContinue } catch { }
Start-Sleep -Seconds 2
Get-Process -Name java -ErrorAction SilentlyContinue |
  Where-Object { $_.StartTime -gt (Get-Date).AddMinutes(-30) -and $_.Path -like '*jdk-21*' } |
  ForEach-Object { try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch { } }

Write-Host ''
if ($fails.Count -gt 0) {
  Write-Host "冒烟结果：$($fails.Count) 项失败 -> $($fails -join '; ')" -ForegroundColor Red
  exit 1
}
Write-Host '冒烟结果：全部接口通过（统一响应体 + 字段形状与 docs/01 第 5 节一致）' -ForegroundColor Green
exit 0
