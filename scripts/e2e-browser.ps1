#requires -Version 5.1
<#
  e2e-browser.ps1 - 浏览器端到端验证 + 页面截图

  流程：
    1. 打包（可跳过）→ 启动后端（demo profile，自动导入 data/seed）
    2. **轮询等待数据真正就绪**（不只是 /api/health 通——种子导入发生在 ApplicationRunner 里，
       health 先通、数据后到；这一点踩过一次坑，见下）
    3. 启动前端构建产物的预览服务（vite preview，/api 代理到后端端口）
    4. 用 Playwright 打开 5 个页面：记录控制台错误与失败请求，逐页截图
    5. 关闭两个进程

  用法： powershell -ExecutionPolicy Bypass -File scripts/e2e-browser.ps1 [-SkipPackage]
  产出： release/screenshots/*.png；退出码 0 = 全部页面渲染正常且无失败请求。
#>
[CmdletBinding()]
param(
  [int]$ApiPort = 18080,
  [int]$WebPort = 5174,
  [switch]$SkipPackage
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Info($m) { Write-Host "[e2e] $m" -ForegroundColor Cyan }
function Good($m) { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Bad($m)  { Write-Host "  [FAIL] $m" -ForegroundColor Red }

<#
  端口冲突防护：本机同时跑着别的课程项目（曾遇到 5174 被另一个应用占用，
  结果把别人的页面当成本项目截图）。所以这里**自动挑空闲端口**，
  并且验证"起起来的确实是我们自己的服务"。
#>
function Test-PortFree([int]$Port) {
  # 用"能否连上"判断占用：比 TcpListener.Start() 可靠
  # （实测本机别的应用以非回环方式绑定，listener 试绑成功但端口其实已被占用）
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $iar = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
    if (-not $iar.AsyncWaitHandle.WaitOne(300)) { return $true }   # 连不上 = 空闲
    $client.EndConnect($iar)
    return $false                                                   # 连上了 = 被占用
  } catch {
    return $true
  } finally {
    $client.Close()
  }
}

function Get-RandomFreePort {
  # 在动态端口段里随机挑，并叠加 Test-PortFree 判断。
  # 之前用固定端口 5174 时被本机另一个课程项目占用（连页面标题都不是本项目），
  # 所以改成随机端口 + 起服务后校验 title，必要时报错而不是截错页面。
  for ($i = 0; $i -lt 30; $i++) {
    $candidate = Get-Random -Minimum 20000 -Maximum 60000
    if (Test-PortFree $candidate) { return $candidate }
  }
  throw '找不到空闲端口'
}

$ApiPort = Get-RandomFreePort
$WebPort = Get-RandomFreePort
Info "选用端口：后端 $ApiPort / 前端 $WebPort（避开被其他应用占用的端口）"

# ---------------------------------------------------------------- 打包
$jar = Get-ChildItem (Join-Path $root 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notlike '*sources*' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $SkipPackage -or -not $jar) {
  Info '打包后端'
  & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'mvn.ps1') -B -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { Bad '打包失败'; exit 1 }
  $jar = Get-ChildItem (Join-Path $root 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*sources*' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
Info "jar: $($jar.Name)"

Info '构建前端（含最新改动）'
Push-Location (Join-Path $root 'frontend')
& node 'node_modules/vite/bin/vite.js' build 2>&1 | Select-Object -Last 3
if ($LASTEXITCODE -ne 0) { Pop-Location; Bad '前端构建失败'; exit 1 }
Pop-Location

# ---------------------------------------------------------------- 启动后端
$javaExe = (Get-Content (Join-Path $root '.tools.json') -Raw | ConvertFrom-Json).javaExe
$appLog = Join-Path $root 'target/e2e-app.log'
Remove-Item $appLog, "$appLog.err" -ErrorAction SilentlyContinue
$argLine = '-jar "{0}" --spring.profiles.active=demo --server.port={1}' -f $jar.FullName, $ApiPort
$app = Start-Process -FilePath $javaExe -ArgumentList $argLine `
  -RedirectStandardOutput $appLog -RedirectStandardError "$appLog.err" -PassThru -WindowStyle Hidden
Info "后端 pid=$($app.Id)，等待数据就绪（health 通 ≠ 数据到齐：种子导入在 ApplicationRunner 里）"

$base = "http://localhost:$ApiPort"
$dataReady = $false
for ($i = 0; $i -lt 80; $i++) {
  Start-Sleep -Seconds 3
  if ($app.HasExited) { break }
  try {
    $h = Invoke-RestMethod "$base/api/health" -TimeoutSec 5
    if ($h.data.status -ne 'UP') { continue }
    # 真正判据：临期清单非空（说明批次已导入）
    $near = Invoke-RestMethod "$base/api/batches/near-expiry?bizDate=2026-06-29&days=2" -TimeoutSec 10
    if ($near.code -eq 0 -and $null -ne $near.data -and @($near.data).Count -gt 0) {
      $dataReady = $true
      Good "数据就绪：临期清单 $((@($near.data)).Count) 条"
      break
    }
  } catch { }
}
if (-not $dataReady) {
  Bad '数据未就绪（临期清单一直为空）'
  Get-Content $appLog -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { "      $_" }
  try { Stop-Process -Id $app.Id -Force } catch { }
  exit 1
}

# ---------------------------------------------------------------- 启动前端预览
Info "启动前端预览（端口 $WebPort，/api → $ApiPort）"
$env:DSH_FRUIT_API_PORT = "$ApiPort"
$webLog = Join-Path $root 'target/e2e-web.log'
Remove-Item $webLog, "$webLog.err" -ErrorAction SilentlyContinue
# 注意：不能写成 -ArgumentList '...{0}...' -f $WebPort——那会把 -f 绑到 Start-Process 的参数上（报 FilePath 重复绑定）。
$webArgs = 'node_modules/vite/bin/vite.js preview --port {0} --strictPort' -f $WebPort
$web = Start-Process -FilePath 'node' `
  -ArgumentList $webArgs `
  -WorkingDirectory (Join-Path $root 'frontend') `
  -RedirectStandardOutput $webLog -RedirectStandardError "$webLog.err" -PassThru -WindowStyle Hidden

$webReady = $false
for ($i = 0; $i -lt 25; $i++) {
  Start-Sleep -Seconds 2
  try {
    $r = Invoke-WebRequest "http://localhost:$WebPort/" -UseBasicParsing -TimeoutSec 5
    if ($r.StatusCode -eq 200) {
      # 关键校验：确认是我们自己的应用（避免把本机其它项目的页面当成本项目截图）。
      # 用 ASCII 判据（Vite 构建产物引用 + 挂载点），不要比对中文标题：
      # PowerShell 5.1 拿到的 .Content 是 latin-1 解码的，中文比对会误判。
      $html = $r.Content
      $looksOurs = ($html -match '/assets/index-') -and ($html -match 'id="app"')
      if (-not $looksOurs) {
        Bad "端口 $WebPort 上响应的不是本项目页面（未匹配到 Vite 构建产物引用），可能被其他应用占用"
        try { Stop-Process -Id $app.Id -Force } catch { }
        try { Stop-Process -Id $web.Id -Force } catch { }
        exit 1
      }
      $webReady = $true
      break
    }
  } catch { }
}
if (-not $webReady) {
  Bad '前端预览未就绪'
  Get-Content "$webLog.err" -Tail 10 -ErrorAction SilentlyContinue | ForEach-Object { "      $_" }
  try { Stop-Process -Id $app.Id -Force } catch { }
  try { Stop-Process -Id $web.Id -Force } catch { }
  exit 1
}
Good '前端预览已就绪'

# 代理连通性
try {
  $p = Invoke-RestMethod "http://localhost:$WebPort/api/health" -TimeoutSec 8
  Good "代理连通：/api/health code=$($p.code) status=$($p.data.status)"
} catch {
  Bad "代理不通：$($_.Exception.Message)"
}

# ---------------------------------------------------------------- 截图
Info 'Playwright 逐页验证 + 截图'
Push-Location (Join-Path $root 'frontend')
$env:WEB_BASE = "http://localhost:$WebPort"
& node 'scripts/screenshot.mjs'
$shotExit = $LASTEXITCODE
Pop-Location

Info '关闭进程'
foreach ($p in @($app, $web)) { try { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } catch { } }
Start-Sleep -Seconds 2
Get-Process -Name java -ErrorAction SilentlyContinue |
  Where-Object { $_.StartTime -gt (Get-Date).AddMinutes(-40) -and $_.Path -like '*jdk-21*' } |
  ForEach-Object { try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch { } }

Write-Host ''
if ($shotExit -ne 0) { Write-Host '浏览器验证：存在问题（见上方报告）' -ForegroundColor Red; exit 1 }
Write-Host '浏览器验证：5 个页面全部正常渲染，无控制台报错、无失败请求' -ForegroundColor Green
exit 0
