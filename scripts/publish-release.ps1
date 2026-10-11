#requires -Version 5.1
<#
  publish-release.ps1 —— 创建 GitHub Release 并上传附件

  为什么需要 token：GitHub Release 的创建与附件上传要走 REST API，
  而 git 的凭据助手（GCM）只服务 git 协议、不把 token 交出来。
  所以这一步需要你提供一个 Personal Access Token（可随时吊销）。

  两种用法：

  1) 用 PAT（脚本自动全程完成）
     $env:GH_TOKEN = 'ghp_xxxxxxxx'          # 或在弹出的提示里输入
     .\scripts\publish-release.ps1 -Version v1.0.0
     Token 权限只需 **repo**（私有仓库）或 **public_repo**（公开仓库即可）；
     本脚本不会把 token 写进任何文件或 git 配置。

  2) 手动（推荐给只用一次的场景）
     - 打开 https://github.com/<你>/fruit-shop/releases/new?tag=v1.0.0
     - 标题：v1.0.0 课程设计定稿版
     - 说明：直接复制 CHANGELOG.md 里 v1.0.0 那一节
     - 附件：上传 release/dist/ 下的两个 zip
     - 点 Publish release

  参数
    -Version  版本号（默认取 CHANGELOG 第一个版本）
    -Owner    仓库所有者（默认取 git config user.name）
    -Repo     仓库名（默认 fruit-shop）
    -NoUpload 只创建 Release，不上传附件
    -Draft    建成草稿（可先在网页上检查再发布）
#>
[CmdletBinding()]
param(
  [string]$Version = '',
  [string]$Owner = '',
  [string]$Repo = 'fruit-shop',
  [switch]$NoUpload,
  [switch]$Draft
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Info($m) { Write-Host "[rel] $m" -ForegroundColor Cyan }
function Good($m) { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Warn2($m) { Write-Host "  [WARN] $m" -ForegroundColor Yellow }
function Bad($m)  { Write-Host "  [FAIL] $m" -ForegroundColor Red }

if (-not $Version) {
  $m = Select-String -Path 'CHANGELOG.md' -Pattern '^## (v[\d\.]+)' | Select-Object -First 1
  $Version = if ($m) { $m.Matches[0].Groups[1].Value } else { throw '无法从 CHANGELOG 取版本号，请用 -Version 指定' }
}
if (-not $Owner) {
  $Owner = (& cmd.exe /c 'git config --global user.name 2>&1' | Out-String).Trim()
}
$tag = $Version
Info "发布 $Owner/$Repo 的 $tag"

# ---------------------------------------------------------------- token
$token = $env:GH_TOKEN
if (-not $token) { $token = $env:GITHUB_TOKEN }
if (-not $token) {
  Write-Host '需要 GitHub Token（权限：repo / public_repo）。' -ForegroundColor Yellow
  Write-Host '生成地址：https://github.com/settings/tokens  → Generate new token (classic)' -ForegroundColor Yellow
  $secure = Read-Host '把 token 粘进来（不会回显、不会写盘）' -AsSecureString
  $token = [System.Net.NetworkCredential]::new('', $secure).Password
}
if (-not $token) { Bad '没有 token，退出'; exit 2 }

$headers = @{
  'User-Agent'    = 'dsh'
  'Accept'        = 'application/vnd.github+json'
  'Authorization' = "Bearer $token"
}

# ---------------------------------------------------------------- 校验 token 与 tag
try {
  $me = Invoke-RestMethod 'https://api.github.com/user' -Headers $headers -TimeoutSec 20
  Good "token 有效，身份：$($me.login)"
  if ($Owner -ne $me.login) { Warn2 "token 身份($($me.login)) 与 Owner($Owner) 不一致，按 token 身份继续" ; $Owner = $me.login }
} catch {
  Bad "token 校验失败（可能过期或权限不足）：$($_.Exception.Message)"
  exit 3
}

$tagExists = $true
try { Invoke-RestMethod "https://api.github.com/repos/$Owner/$Repo/git/refs/tags/$tag" -Headers $headers -TimeoutSec 20 | Out-Null }
catch { $tagExists = $false }
if (-not $tagExists) {
  Warn2 "远程没有 tag $tag。先执行： git push origin $tag"
  exit 4
}
Good "远程 tag $tag 存在"

# ---------------------------------------------------------------- release notes
$notes = ''
if (Test-Path 'CHANGELOG.md') {
  $lines = Get-Content 'CHANGELOG.md' -Encoding UTF8
  $start = ($lines | Select-String -Pattern "^## $Version" | Select-Object -First 1).LineNumber
  if ($start) {
    $rest = $lines[$start..($lines.Count - 1)]
    $end = ($rest | Select-String -Pattern '^## v' | Select-Object -First 1).LineNumber
    $body = if ($end) { $rest[0..($end - 2)] } else { $rest }
    $notes = ($body -join "`n").Trim()
  }
}
if (-not $notes) { $notes = "见 CHANGELOG.md 的 $Version 一节。" }
$notes += "`n`n---`n`n**附件说明**：`n- ``$Repo-$tag-demo.zip`` 演示整包（含预构建前后端 + 120 天种子数据，装 JDK 21 即可跑）`n- ``$Repo-$tag-source.zip`` 纯源码包"

# ---------------------------------------------------------------- 创建 release
$payload = @{
  tag_name         = $tag
  target_commitish = 'main'
  name             = "$tag 课程设计定稿版"
  body             = $notes
  draft            = [bool]$Draft
  prerelease       = $false
} | ConvertTo-Json -Depth 4

$release = $null
try {
  $release = Invoke-RestMethod "https://api.github.com/repos/$Owner/$Repo/releases" -Method Post `
    -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($payload)) -TimeoutSec 40
  Good "Release 已创建：$($release.html_url)"
} catch {
  $msg = $_.Exception.Message
  if ($msg -match 'already_exists') {
    Warn2 "该 tag 已有 Release，改为复用"
    $release = Invoke-RestMethod "https://api.github.com/repos/$Owner/$Repo/releases/tags/$tag" -Headers $headers -TimeoutSec 30
    Good "复用已有 Release：$($release.html_url)"
  } else {
    Bad "创建 Release 失败：$msg"
    exit 5
  }
}

# ---------------------------------------------------------------- 上传附件
if (-not $NoUpload) {
  $distDir = Join-Path $root 'release/dist'
  $assets = Get-ChildItem $distDir -Filter '*.zip' -ErrorAction SilentlyContinue
  if (-not $assets) {
    Warn2 "没有找到附件（先跑 scripts/package-release.ps1）：$distDir"
  }
  foreach ($a in $assets) {
    Info "上传 $($a.Name)（$([math]::Round($a.Length/1MB,2)) MB）"
    $uploadUrl = ($release.upload_url -replace '\{.*\}', '') + "?name=" + [uri]::EscapeDataString($a.Name)
    try {
      $upHeaders = @{
        'User-Agent'    = 'dsh'
        'Accept'        = 'application/vnd.github+json'
        'Authorization' = "Bearer $token"
        'Content-Type'  = 'application/zip'
      }
      $res = Invoke-RestMethod $uploadUrl -Method Post -Headers $upHeaders -InFile $a.FullName -TimeoutSec 600
      Good "已上传：$($res.browser_download_url)"
    } catch {
      Bad "上传 $($a.Name) 失败：$($_.Exception.Message)"
      Warn2 '可以改用网页上传：Release 页面 → Edit → 拖拽 zip 到附件区'
    }
  }
}

Write-Host ''
Good "完成：$($release.html_url)"
Write-Host '  建议顺手做的：' -ForegroundColor Cyan
Write-Host '    1) 到仓库 Settings 补一句描述（脚本无权改仓库元信息）'
Write-Host '    2) 需要私有就把可见性改成 Private（Settings → General → Danger Zone）'
