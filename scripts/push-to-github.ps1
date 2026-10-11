#requires -Version 5.1
<#
  push-to-github.ps1 —— 把本项目推送到 GitHub（HTTPS）

  为什么需要脚本：本机 github.com 有时连不上（api.github.com 一直通），
  而且 Git Credential Manager 的交互式登录需要你在自己的终端里完成。
  这个脚本把"建仓库 / 配 remote / 推送"三步固定下来，并给出失败时的替代方案。

  三种用法（按推荐顺序）：

  1) 浏览器登录（最省事，推荐）
     .\scripts\push-to-github.ps1 -Repo fruit-shop
     首次推送会弹出 Git Credential Manager 的浏览器登录窗口，登录你自己的账号即可。

  2) 用 Personal Access Token（GitHub → Settings → Developer settings →
     Personal access tokens → 勾选 repo 权限）
     .\scripts\push-to-github.ps1 -Repo fruit-shop -Token ghp_xxxxxxxx
     Token 只用于本次调用，不写进 .git/config（remote URL 里不含 token）。

  3) 你自己在网页上建好空仓库（不要勾 Add README），然后：
     .\scripts\push-to-github.ps1 -Repo fruit-shop -SkipCreate

  参数
    -Repo      仓库名，默认 fruit-shop
    -Private   建成私有仓库（课程作业建议私有，避免被同学直接抄）
    -Token     可选：GitHub PAT（有 token 则用 API 建仓库，免浏览器）
    -SkipCreate 跳过"调 API 建仓库"，直接推（适用于你已在网页建好）
#>
[CmdletBinding()]
param(
  [string]$Repo = 'fruit-shop',
  [string]$Owner = '',
  [switch]$Private,
  [string]$Token = '',
  [switch]$SkipCreate,
  [string]$Branch = 'main'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Info($m) { Write-Host "[push] $m" -ForegroundColor Cyan }
function Good($m) { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Warn2($m) { Write-Host "  [WARN] $m" -ForegroundColor Yellow }
function Bad($m)  { Write-Host "  [FAIL] $m" -ForegroundColor Red }

# ---------------------------------------------------------------- 前置检查
Info '检查本地仓库状态'
if (-not (Test-Path (Join-Path $root '.git'))) {
  Bad '当前目录不是 git 仓库。先执行：git init -b main && git add -A && git commit -m "init"'
  exit 1
}
if (-not $Owner) {
  $Owner = (git config --global user.name 2>$null)
  if (-not $Owner) { Bad '拿不到 GitHub 用户名，请用 -Owner 指定'; exit 1 }
}
$branchNow = (git rev-parse --abbrev-ref HEAD 2>$null)
Good "仓库：$root"
Good "当前分支：$branchNow　目标：https://github.com/$Owner/$Repo.git"
$dirty = git status --porcelain
if ($dirty) {
  Warn2 '有未提交的改动，它们不会被推送：'
  $dirty | Select-Object -First 8 | ForEach-Object { "         $_" }
  Warn2 '要一起推的话先执行： git add -A  然后  git commit -m "说明"'
}
$commits = (git rev-list --count HEAD 2>$null)
Good "本地提交数：$commits"

# ---------------------------------------------------------------- 建远程仓库
if (-not $SkipCreate) {
  Info '创建 GitHub 仓库（若已存在则复用）'
  $headers = @{ 'User-Agent' = 'dsh'; 'Accept' = 'application/vnd.github+json' }
  if ($Token) { $headers['Authorization'] = "Bearer $Token" }

  $exists = $false
  try {
    Invoke-RestMethod "https://api.github.com/repos/$Owner/$Repo" -Headers $headers -TimeoutSec 20 | Out-Null
    $exists = $true
  } catch { $exists = $false }

  if ($exists) {
    Good "仓库已存在：https://github.com/$Owner/$Repo"
  } elseif (-not $Token) {
    Warn2 '没有 token：无法调 API 建仓库。两条路二选一：'
    Write-Host '         ① 加 -Token <你的 PAT> 让脚本建；'
    Write-Host "         ② 打开 https://github.com/new 建名为 $Repo 的空仓库（不要勾 Add README），"
    Write-Host '            然后重跑本脚本加 -SkipCreate。'
    exit 2
  } else {
    $body = @{
      name        = $Repo
      private     = [bool]$Private
      description = '高校课程设计：街边水果店临期果品动态定价与到店客流适配推送系统（Spring Boot + Vue3 + Python 评测）'
      has_issues  = $true
      has_wiki    = $false
    } | ConvertTo-Json
    try {
      $created = Invoke-RestMethod "https://api.github.com/user/repos" -Method Post -Headers $headers `
        -ContentType 'application/json' -Body $body -TimeoutSec 30
      Good "已创建：$($created.html_url)（private=$($created.private)）"
    } catch {
      Bad "建仓库失败：$($_.Exception.Message)"
      Write-Host '         常见原因：token 缺少 repo 权限、或 token 已过期。'
      Write-Host "         也可以手动建：https://github.com/new?name=$Repo"
      exit 3
    }
  }
}

# ---------------------------------------------------------------- 配 remote 并推送
$remoteUrl = "https://github.com/$Owner/$Repo.git"
# 注意：PowerShell 会把原生命令写到 stderr 的内容变成 error record（"No such remote"），
# 所以这里用 cmd /c 包一层把 stderr 收进管道，避免脚本在中途被打断。
$existingRemote = ''
try {
  $existingRemote = (& cmd.exe /c 'git remote get-url origin 2>&1' | Out-String).Trim()
  if ($existingRemote -match 'No such remote|error:') { $existingRemote = '' }
} catch { $existingRemote = '' }
if ($existingRemote) {
  if ($existingRemote -ne $remoteUrl) {
    Warn2 "origin 已存在且指向 $existingRemote，改为 $remoteUrl"
    git remote set-url origin $remoteUrl
  } else { Good "origin 已指向 $remoteUrl" }
} else {
  git remote add origin $remoteUrl
  Good "已添加 origin → $remoteUrl"
}

Info "推送 $branchNow → origin/$Branch（首次可能弹出 GitHub 登录窗口，请在弹出的浏览器里完成授权）"
Write-Host '      提示：登录窗口是本机的 Git Credential Manager，不会把密码/token 交给本项目。'
& git push -u origin "$branchNow`:$Branch"
$code = $LASTEXITCODE

Write-Host ''
if ($code -eq 0) {
  Good "推送成功：https://github.com/$Owner/$Repo"
  Write-Host '  建议顺手做的两件事：' -ForegroundColor Cyan
  Write-Host '    1) 仓库页 → Settings → 确认默认分支是 main'
  Write-Host '    2) 如果需要别人能看：Settings → Collaborators 添加同学/老师'
  exit 0
}

Bad "推送失败（git 退出码 $code）"
Write-Host ''
Write-Host '按错误信息对症处理：' -ForegroundColor Cyan
Write-Host '  · Could not resolve host / Failed to connect  → github.com 连不上：'
Write-Host '      先测：curl.exe -s -o NUL -w "%{http_code}" https://github.com'
Write-Host '      不行就用手机热点/代理重试；api.github.com 通但 github.com 不通时，'
Write-Host '      可以改用 SSH：ssh-keygen -t ed25519，把公钥贴到 GitHub → Settings → SSH keys，'
Write-Host '      然后 git remote set-url origin git@github.com:<你>/<仓库>.git'
Write-Host '  · Authentication failed / 403  → 凭据不对：'
Write-Host '      清掉缓存重试：git credential-manager erase（或 控制面板→凭据管理器→Windows 凭据 删 github.com）'
Write-Host '  · Support for password authentication was removed  → 用 PAT 当密码：'
Write-Host '      重跑本脚本加 -Token <PAT>，或把 PAT 粘到弹出窗口的密码框'
Write-Host '  · remote contains work that you do not have  → 远程非空（建仓库时勾了 README）：'
Write-Host '      git pull --rebase origin ' -NoNewline; Write-Host $Branch
exit $code
