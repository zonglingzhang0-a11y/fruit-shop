#requires -Version 5.1
<#
  setup.ps1 - one-shot environment bootstrap (idempotent).

  What it does:
    1. locate JDK 21+, Node, Python; write .tools.json
    2. pick a reachable Maven mirror; write .mvn/settings-local.xml
    3. prepare the Maven wrapper (jar + properties) so builds never need mvn on PATH
    4. print a self-check summary (missing items are warnings, not hard stops)

  Usage:
    powershell -ExecutionPolicy Bypass -File scripts/setup.ps1
    powershell -ExecutionPolicy Bypass -File scripts/setup.ps1 -Mirror aliyun
#>
[CmdletBinding()]
param(
  [ValidateSet('auto', 'aliyun', 'tencent', 'huawei', 'central')]
  [string]$Mirror = 'auto',
  [string]$MavenVersion = '3.9.9',
  [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Step($m) { Write-Host ''; Write-Host ('==> ' + $m) -ForegroundColor Cyan }
function Ok($m)   { Write-Host ('    [OK]   ' + $m) -ForegroundColor Green }
function Warn($m) { Write-Host ('    [WARN] ' + $m) -ForegroundColor Yellow }
function Fail($m) { Write-Host ('    [FAIL] ' + $m) -ForegroundColor Red }

# ---------------------------------------------------------------------------
# 1. toolchain
# ---------------------------------------------------------------------------
Step 'Locate toolchain'

$javaExe = ''
$javaHome = ''
if ($env:JAVA_HOME) {
  $cand = Join-Path $env:JAVA_HOME 'bin\java.exe'
  if (Test-Path $cand) { $javaExe = $cand; $javaHome = $env:JAVA_HOME }
}
if (-not $javaExe) {
  $cmd = Get-Command java -ErrorAction SilentlyContinue
  if ($cmd) {
    $javaExe = $cmd.Source
    $javaHome = Split-Path -Parent (Split-Path -Parent $cmd.Source)
  }
}
if (-not $javaExe) {
  $patterns = @(
    'C:\Program Files\Android\openjdk\*\bin\java.exe',
    'C:\Program Files\Java\*\bin\java.exe',
    'C:\Program Files\Eclipse Adoptium\*\bin\java.exe',
    'C:\Program Files\Microsoft\jdk*\bin\java.exe'
  )
  foreach ($p in $patterns) {
    $hit = Get-ChildItem $p -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) {
      $javaExe = $hit.FullName
      $javaHome = Split-Path -Parent (Split-Path -Parent $hit.FullName)
      break
    }
  }
}
function Invoke-Tool($exe, $toolArgs) {
  # Capture native stdout+stderr without PowerShell turning stderr into a terminating error.
  # Note: do not touch $ErrorActionPreference here; `cmd /c` keeps the redirection out of PS.
  $tmp = [System.IO.Path]::GetTempFileName()
  try {
    $cmd = '"' + $exe + '"'
    foreach ($a in $toolArgs) {
      $s = [string]$a
      if ($s -match '[\s"]') { $cmd += ' "' + ($s -replace '"', '""') + '"' } else { $cmd += ' ' + $s }
    }
    $cmd += ' > "' + $tmp + '" 2>&1'
    & cmd.exe /c $cmd | Out-Null
    return (((Get-Content $tmp -Raw -ErrorAction SilentlyContinue) | Out-String)).Trim()
  } finally {
    Remove-Item $tmp -ErrorAction SilentlyContinue
  }
}

$javaVersion = ''
if ($javaExe) {
  $raw = Invoke-Tool $javaExe @('-version')
  $javaVersion = ($raw -split "`r?`n")[0]
  Ok ('JDK: ' + $javaExe)
  Ok $javaVersion
} else {
  Fail 'JDK not found; need JDK 21 (Spring Boot 3.5 requires 17+, project verified on 21)'
}

$nodeExe = ''
$cmd = Get-Command node -ErrorAction SilentlyContinue
if ($cmd) { $nodeExe = $cmd.Source }
if (-not $nodeExe) {
  $patterns = @(
    (Join-Path $env:USERPROFILE '.dsh\dsh-runtimes\*\dependencies\node\node.exe'),
    'C:\Program Files\nodejs\node.exe'
  )
  foreach ($p in $patterns) {
    $hit = Get-ChildItem $p -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) { $nodeExe = $hit.FullName; break }
  }
}
$nodeVersion = ''
if ($nodeExe) {
  $nodeVersion = Invoke-Tool $nodeExe @('-v')
  Ok ('Node: ' + $nodeExe + ' (' + $nodeVersion + ')')
} else {
  Warn 'Node not found; frontend (Vite) build unavailable, backend can proceed'
}

# Python: prefer an interpreter that already has pandas/numpy/openpyxl
$pyCandidates = @()
$cmd = Get-Command python -ErrorAction SilentlyContinue
if ($cmd) { $pyCandidates += $cmd.Source }
$pyCandidates += @('C:\Python314\python.exe', 'C:\Python313\python.exe', 'C:\Python312\python.exe')
$hit = Get-ChildItem (Join-Path $env:USERPROFILE '.dsh\dsh-runtimes\*\dependencies\python\python.exe') -ErrorAction SilentlyContinue | Select-Object -First 1
if ($hit) { $pyCandidates += $hit.FullName }

$pyExe = ''
foreach ($cand in $pyCandidates) {
  if ($cand -and (Test-Path $cand)) {
    $probe = Invoke-Tool $cand @('-c', 'import pandas, numpy, openpyxl; print(1)')
    if ($probe -eq '1') { $pyExe = $cand; break }
  }
}
if (-not $pyExe) {
  foreach ($cand in $pyCandidates) {
    if ($cand -and (Test-Path $cand)) { $pyExe = $cand; break }
  }
}

$deps = [ordered]@{}
$pyVersion = ''
if ($pyExe) {
  Ok ('Python: ' + $pyExe)
  $check = Join-Path $PSScriptRoot '_pycheck.py'
  $lines = @()
  if (Test-Path $check) {
    $text = Invoke-Tool $pyExe @($check)
    $lines = $text -split "`r?`n"
  }
  foreach ($line in $lines) {
    $text = ([string]$line).Trim()
    if ($text -like 'PYTHON *') {
      $pyVersion = $text.Substring(7).Trim()
      Ok ('python version ' + $pyVersion)
    } elseif ($text -match '^([A-Za-z0-9_]+)=(.*)$') {
      $deps[$Matches[1]] = $Matches[2]
      if ($Matches[2] -eq 'MISSING') { Warn ('py:' + $Matches[1] + ' missing') }
      else { Ok ('py:' + $Matches[1] + ' ' + $Matches[2]) }
    }
  }
} else {
  Warn 'Python not found; data cleaning / simulator unavailable'
}

# ---------------------------------------------------------------------------
# 2. Maven wrapper + mirror
# ---------------------------------------------------------------------------
Step 'Prepare Maven wrapper and mirror'

New-Item -ItemType Directory -Force -Path '.mvn/wrapper' | Out-Null

$mirrorDefs = [ordered]@{
  aliyun  = 'https://maven.aliyun.com/repository/public'
  tencent = 'https://mirrors.cloud.tencent.com/nexus/repository/maven-public'
  huawei  = 'https://repo.huaweicloud.com/repository/maven'
  central = 'https://repo1.maven.org/maven2'
}

function Test-Mirror($base) {
  $probe = $base + '/org/springframework/boot/spring-boot-starter-parent/3.5.0/spring-boot-starter-parent-3.5.0.pom'
  $code = (& curl.exe -s -o NUL -w '%{http_code}' --max-time 12 $probe 2>$null | Out-String).Trim()
  return ($code -eq '200')
}

if ($Mirror -eq 'auto') { $order = @('aliyun', 'tencent', 'huawei', 'central') } else { $order = @($Mirror) }

$chosen = ''
foreach ($m in $order) {
  if (Test-Mirror $mirrorDefs[$m]) { $chosen = $m; break }
  Warn ('mirror unreachable: ' + $m)
}

$settingsPath = '.mvn/settings-local.xml'
if ($chosen) {
  Ok ('Maven mirror: ' + $chosen + ' -> ' + $mirrorDefs[$chosen])
  $lines = @()
  $lines += '<?xml version="1.0" encoding="UTF-8"?>'
  $lines += '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"'
  $lines += '          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"'
  $lines += '          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">'
  $lines += '  <mirrors>'
  $lines += '    <mirror>'
  $lines += ('      <id>' + $chosen + '</id>')
  $lines += ('      <name>' + $chosen + ' mirror</name>')
  $lines += ('      <url>' + $mirrorDefs[$chosen] + '</url>')
  $lines += '      <mirrorOf>*</mirrorOf>'
  $lines += '    </mirror>'
  $lines += '  </mirrors>'
  $lines += '</settings>'
  Set-Content -Path $settingsPath -Value $lines -Encoding UTF8
  Ok ('wrote ' + $settingsPath)
} else {
  Fail 'no reachable Maven mirror; dependencies cannot be downloaded'
}

$wrapperJar = '.mvn/wrapper/maven-wrapper.jar'
if ((-not (Test-Path $wrapperJar)) -or $Force) {
  $jarBase = $mirrorDefs['central']
  if ($chosen) { $jarBase = $mirrorDefs[$chosen] }
  $jarUrl = $jarBase + '/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar'
  & curl.exe -sS -L --max-time 120 -o $wrapperJar $jarUrl 2>$null
  if ((Test-Path $wrapperJar) -and ((Get-Item $wrapperJar).Length -gt 10000)) {
    Ok ('wrapper jar ' + (Get-Item $wrapperJar).Length + ' bytes')
  } else {
    Warn 'wrapper jar download failed; scripts/mvn.ps1 falls back to local .m2 Maven'
  }
}

if ($chosen) {
  $props = @()
  $props += ('distributionUrl=' + $mirrorDefs[$chosen] + '/org/apache/maven/apache-maven/' + $MavenVersion + '/apache-maven-' + $MavenVersion + '-bin.zip')
  $props += ('wrapperUrl=' + $mirrorDefs[$chosen] + '/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar')
  Set-Content -Path '.mvn/wrapper/maven-wrapper.properties' -Value $props -Encoding ASCII
  Ok ('maven-wrapper.properties -> apache-maven-' + $MavenVersion)
}

# ---------------------------------------------------------------------------
# 3. .tools.json
# ---------------------------------------------------------------------------
Step 'Write .tools.json'
$tools = [ordered]@{
  generatedAt   = (Get-Date).ToString('s')
  root          = $root
  javaExe       = $javaExe
  javaHome      = $javaHome
  javaVersion   = $javaVersion
  nodeExe       = $nodeExe
  nodeVersion   = $nodeVersion
  pythonExe     = $pyExe
  pythonVersion = $pyVersion
  pythonDeps    = $deps
  mavenMirror   = $chosen
  mavenVersion  = $MavenVersion
  settingsXml   = $settingsPath
}
$tools | ConvertTo-Json -Depth 5 | Set-Content '.tools.json' -Encoding UTF8
Ok '.tools.json'

Step 'Self-check summary'
if (-not $javaExe) { Fail 'no JDK 21 -> backend cannot build' }
if (-not $chosen)  { Fail 'no reachable Maven mirror -> dependencies unavailable' }
if (-not $nodeExe) { Warn 'no Node -> frontend cannot build yet' }
if (-not $pyExe)   { Warn 'no Python -> data phase unavailable' }
Write-Host ''
Write-Host 'Done. Next:  .\scripts\mvn.ps1 -v' -ForegroundColor Cyan
