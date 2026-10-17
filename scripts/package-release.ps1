#requires -Version 5.1
<#
  package-release.ps1 —— 打包课程设计交付版 zip

  产物（放在 release/dist/ 下）：
    1) fruit-shop-v<版本>-demo.zip   演示整包：源码 + 文档 + 评测报告/图表 + 截图 + 种子数据 + 预构建前端
    2) fruit-shop-v<版本>-source.zip 纯源码包（git archive，不含种子数据与截图）

  刻意不打包的东西（附件的体积要可控，且都能重新生成）：
    node_modules / target / frontend/dist 之外的本机构建产物 / H2 数据库 / 原始竞赛附件 / 清洗中间产物

  用法：
    powershell -ExecutionPolicy Bypass -File scripts/package-release.ps1
    powershell -ExecutionPolicy Bypass -File scripts/package-release.ps1 -Version v1.0.0
#>
[CmdletBinding()]
param(
  [string]$Version = '',
  [string]$OutDir = 'release/dist'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Info($m) { Write-Host "[pkg] $m" -ForegroundColor Cyan }
function Good($m) { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Warn2($m) { Write-Host "  [WARN] $m" -ForegroundColor Yellow }

if (-not $Version) {
  # 从 CHANGELOG 的第一个版本号取，保证"版本号只有一个来源"
  $m = Select-String -Path 'CHANGELOG.md' -Pattern '^## (v[\d\.]+)' | Select-Object -First 1
  $Version = if ($m) { $m.Matches[0].Groups[1].Value } else { 'v0.0.0' }
}
Info "打包版本：$Version"

$stage = Join-Path $env:TEMP ("fruit-shop-stage-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$pkgName = "fruit-shop-$Version"
$pkgRoot = Join-Path $stage $pkgName
New-Item -ItemType Directory -Force -Path $pkgRoot | Out-Null
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# ---------------------------------------------------------------- 1) 构建
Info '构建后端（跳过测试；测试单独跑）'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'mvn.ps1') -B -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
$jar = Get-ChildItem (Join-Path $root 'target') -Filter '*.jar' |
  Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
Good "jar: $($jar.Name)"

Info '构建前端'
Push-Location (Join-Path $root 'frontend')
& node 'node_modules/vite/bin/vite.js' build 2>&1 | Select-Object -Last 2
if ($LASTEXITCODE -ne 0) { Pop-Location; throw '前端构建失败' }
Pop-Location
Good '前端构建完成'

# ---------------------------------------------------------------- 2) 拷贝内容
Info '收集文件'

# 源码/脚本/文档/报告（按 .gitignore 的规则来：用 git ls-files 拿"该进仓库的文件"，
# 这样打包内容与 GitHub 上的内容天然一致，不会漏也不会多）
#
# 注意：必须带 -c core.quotepath=false。git 默认把含中文的路径输出成八进制转义
# （如 docs/\350\277\233...），PowerShell 拿到后会报 "Illegal characters in path"。
$tracked = & cmd.exe /c 'git -c core.quotepath=false ls-files' | Where-Object { $_ -ne '' }
$copied = 0
foreach ($f in $tracked) {
  $dest = Join-Path $pkgRoot $f
  $destDir = Split-Path -Parent $dest
  if (-not (Test-Path -LiteralPath $destDir)) { New-Item -ItemType Directory -Force -Path $destDir | Out-Null }
  Copy-Item -LiteralPath (Join-Path $root $f) -Destination $dest -Force
  $copied++
}
Good "跟踪文件 $copied 个（与 GitHub 仓库内容一致）"

# 预构建前端 + 后端 jar（演示要能直接跑，不必装 npm/maven）
$dest = Join-Path $pkgRoot 'frontend/dist'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Copy-Item (Join-Path $root 'frontend/dist/*') $dest -Recurse -Force
Good '预构建前端 frontend/dist'
$dest = Join-Path $pkgRoot 'dist'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Copy-Item $jar.FullName $dest -Force
Good "预构建后端 dist/$($jar.Name)"

# release 下带下划线前缀的对照备份不进包（同 .gitignore 的判断）
Get-ChildItem (Join-Path $pkgRoot 'release') -Filter '_*' -ErrorAction SilentlyContinue |
  ForEach-Object { Remove-Item $_.FullName -Force; Warn2 "排除对照备份 $($_.Name)" }

# ---------------------------------------------------------------- 3) 快速开始 + 版本文件
Info '写入快速开始与版本信息'

$quickstart = @"
# 快速开始（$Version 演示整包）

这个包是**开箱即用**的：不用装 Node、不用联网下依赖，只要本机有 **JDK 21** 就能起系统。

## 一、起后端（约 1 分钟）

``````powershell
java -jar dist\fruit-shop-1.0.0.jar --spring.profiles.active=demo
``````

启动时会自动建表并导入 ``data/seed/`` 里的 120 天演示数据（38 单品 / 2,083 批次 / 53,823 条流水）。
看到 ``Started FruitShopApplication`` 后，浏览器打开：

- http://localhost:8080/api/health —— 自检
- http://localhost:8080/swagger-ui.html —— 全部接口文档
- http://localhost:8080/h2-console —— 数据库（JDBC URL 见 application-demo.yml）

> JDK 版本：Spring Boot 3.5 需要 **JDK 17+**，本项目在 **21** 上验证。
> 端口被占用时加 ``--server.port=18080``。

## 二、看前端页面（两种方式）

**方式 A：看预构建产物（需要 Node，最省事）**

``````powershell
cd frontend
npx vite preview --port 5174
``````
> 纯静态预览不带 /api 代理，页面能打开但取不到数据。要看真实数据用方式 B。

**方式 B：完整跑起来（Node + npm）**

``````powershell
cd frontend
npm install
npm run dev            # http://localhost:5173，已配好 /api 代理到后端
``````

后端若改了端口：``````powershell
\$env:DSH_FRUIT_API_PORT='18080'; npm run dev
``````

## 三、页面上看什么（建议顺序）

1. **临期清单** —— 21 条临期批次，每行有「新鲜度 / 预计可售天数 / 有风险 / 预计报废」和测算口径说明
2. **定价建议** —— 点「生成建议」→ 21 条建议（只在 6 个候选档里选、绝不涨价、附中文理由）；
   点「确认/改价」可以体验店主确认流程（低于最低折扣线会被拒绝，这是有意设计的）
3. **客流时段** —— 真实数据校准出来的时段分布（峰值 10 点）→ 建议推送时间 9 点
4. **推送消息** —— 预览只建计划、不发送；「发送站内消息」才写 push_log
5. **效果看板** —— 三策略对比（FIXED / RULE / MODEL）+ 60 天逐日明细
6. **顾客端 H5 特价页**（左侧菜单最后一项，或直接访问 ``#/h5``）—— 手机版式，
   **只显示店主已确认的价格**

> ⚠️ 演示日期口径：种子数据是 **2026-03-02 ~ 06-29** 的历史区间，页面业务日期默认取 **2026-06-29**。
> 不要改成"今天"，否则 2000+ 个批次会全被判成过期。

## 四、想自己复现数据与评测（可选）

``````powershell
python scripts\prepare_raw_data.py         # 原始附件 → CSV（需先按 README 下载附件）
python scripts\calibrate_from_public.py    # 真实数据校准
python scripts\simulator.py run --seed 20260701 --days 120 --warmup-days 60
python scripts\verify_seed_data.py         # 独立核验
python scripts\evaluate_strategies.py --seeds 20 --min-discount-rate 0.60   # 三策略对照（约 4 分钟）
``````
> Python 脚本的依赖固定在 ``requirements.txt``：``python -m pip install -r requirements.txt``（Python 3.12+）。

## 五、这个包里有什么

| 目录 | 内容 |
| --- | --- |
| ``dist/`` ``frontend/dist/`` | 预构建的后端 jar 与前端页面（直接可用） |
| ``src/`` | 后端源码（124 个 Java 类，含 94 个测试用例） |
| ``frontend/src/`` | 前端源码（6 个页面） |
| ``docs/`` | 8 篇文档（启动流程 / 技术方案 / 仿真器 / 定价设计 / 需求调研 / 合规 / 演示手册 / 参考架构差异）+ 参考架构原件 |
| ``release/`` | 评测报告、敏感性报告、9 张图表、6 张页面截图 |
| ``data/seed/`` | 120 天种子数据 + 真实数据校准产物 |
| ``scripts/`` | 全部可复现脚本（环境 / 清洗 / 校准 / 仿真 / 评测 / 冒烟 / 浏览器验证 / 打包） |
| ``CHANGELOG.md`` | 版本说明与核心结论 |
| ``docs/06-演示与答辩手册.md`` | **答辩用**：8 分钟演示脚本 + 预设问答 |

## 六、包里没有的（能重新生成）

原始竞赛附件（38MB）、清洗中间产物（54MB）、H2 数据库、``node_modules``、Maven 的 ``target``。
下载与生成方法见 ``README.md``「快速开始」。
"@
Set-Content -Path (Join-Path $pkgRoot '快速开始.md') -Value $quickstart -Encoding UTF8
Good '快速开始.md'

$versionInfo = [ordered]@{
  version     = $Version
  packagedAt  = (Get-Date).ToString('s')
  gitCommit   = (& cmd.exe /c 'git rev-parse HEAD 2>&1' | Out-String).Trim()
  gitDescribe = (& cmd.exe /c "git describe --tags --always 2>&1" | Out-String).Trim()
  backend     = 'Spring Boot 3.5.0 + MyBatis-Plus 3.5.7 + H2 2.3.232（Java 21）'
  frontend    = 'Vue 3.5 + Vite 6 + Element Plus 2.9'
  tests       = '94 个用例全绿（scripts/mvn.ps1 -B clean test）'
  dataSeed    = '120 天 / 38 单品 / 2083 批次 / 53823 条销售流水'
  note        = '演示整包：含预构建产物与种子数据，开箱即用；原始竞赛附件与清洗中间产物未包含（可重新生成）'
}
$versionInfo | ConvertTo-Json -Depth 3 | Set-Content -Path (Join-Path $pkgRoot 'VERSION.json') -Encoding UTF8
Good 'VERSION.json'

# ---------------------------------------------------------------- 4) 压缩
Info '生成 zip'
$demoZip = Join-Path (Resolve-Path $OutDir) "$pkgName-demo.zip"
if (Test-Path $demoZip) { Remove-Item $demoZip -Force }
Compress-Archive -Path $pkgRoot -DestinationPath $demoZip -CompressionLevel Optimal
Good "$([math]::Round((Get-Item $demoZip).Length/1MB,2)) MB  $demoZip"

$srcZip = Join-Path (Resolve-Path $OutDir) "$pkgName-source.zip"
if (Test-Path $srcZip) { Remove-Item $srcZip -Force }
& cmd.exe /c "git archive --format=zip -o `"$srcZip`" HEAD" | Out-Null
if (Test-Path $srcZip) { Good "$([math]::Round((Get-Item $srcZip).Length/1MB,2)) MB  $srcZip" }
else { Warn2 'git archive 失败（源码包跳过）' }

Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- 5) 汇总
Write-Host ''
Info '打包完成'
Get-ChildItem $OutDir -File | Select-Object Name, @{n='MB';e={[math]::Round($_.Length/1MB,2)}}, LastWriteTime |
  Format-Table -AutoSize
Write-Host '下一步（创建 GitHub Release 并上传附件）：' -ForegroundColor Cyan
Write-Host "  powershell -ExecutionPolicy Bypass -File scripts/publish-release.ps1 -Version $Version"
