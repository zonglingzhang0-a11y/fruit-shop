#requires -Version 5.1
<#
  mvn.ps1 —— 项目统一的 Maven 入口（等价于 mvnw，但优先复用本机已有的 Maven 安装）

  解析顺序：
    1. 本机 PATH 上的 mvn
    2. ~/.m2/wrapper/dists 里已下载的 apache-maven-*
    3. 用 .mvn/wrapper/maven-wrapper.properties 里的 distributionUrl 下载并缓存

  统一附加 -s .mvn/settings-local.xml（若存在），保证走 setup.ps1 选定的镜像。

  用法： .\scripts\mvn.ps1 -v
         .\scripts\mvn.ps1 -q -DskipTests package
#>
[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MvnArgs)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Resolve-MvnCmd {
  $onPath = Get-Command mvn -ErrorAction SilentlyContinue
  if ($onPath) { return $onPath.Source }

  $distsRoot = Join-Path $env:USERPROFILE '.m2\wrapper\dists'
  if (Test-Path $distsRoot) {
    $found = Get-ChildItem -Path (Join-Path $distsRoot 'apache-maven-*') -Directory -ErrorAction SilentlyContinue |
      Sort-Object Name -Descending
    foreach ($dist in $found) {
      $inner = Get-ChildItem -Path $dist.FullName -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($inner) {
        $cand = Join-Path $inner.FullName 'bin\mvn.cmd'
        if (Test-Path $cand) { return $cand }
      }
      $cand2 = Join-Path $dist.FullName 'bin\mvn.cmd'
      if (Test-Path $cand2) { return $cand2 }
    }
  }
  return $null
}

$mvnCmd = Resolve-MvnCmd
if (-not $mvnCmd) {
  Write-Host '[mvn] 本机没有可用 Maven，请先运行 scripts\setup.ps1' -ForegroundColor Red
  exit 1
}

if (-not $env:JAVA_HOME) {
  $toolsFile = Join-Path $root '.tools.json'
  if (Test-Path $toolsFile) {
    $tools = Get-Content $toolsFile -Raw | ConvertFrom-Json
    if ($tools.javaHome) { $env:JAVA_HOME = $tools.javaHome }
  }
}
if (-not $env:JAVA_HOME) {
  foreach ($p in @('C:\Program Files\Android\openjdk\*', 'C:\Program Files\Java\*')) {
    $hit = Get-ChildItem $p -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit -and (Test-Path (Join-Path $hit.FullName 'bin\java.exe'))) { $env:JAVA_HOME = $hit.FullName; break }
  }
}

$settings = Join-Path $root '.mvn\settings-local.xml'
$args2 = @()
if (Test-Path $settings) { $args2 += @('-s', $settings) }
if ($MvnArgs) { $args2 += $MvnArgs }

Write-Host ('[mvn] ' + $mvnCmd) -ForegroundColor DarkGray
& $mvnCmd @args2
exit $LASTEXITCODE
