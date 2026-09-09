param([Parameter(Mandatory=$true)][string]$RuntimeRoot,
      [Parameter(Mandatory=$true)][string]$Package,
      [Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$sandbox = Join-Path $OutputDirectory 'recovery-admission'
if (Test-Path -LiteralPath $sandbox) { throw '证据目录已存在，禁止覆盖' }
New-Item -ItemType Directory -Path (Join-Path $sandbox 'tools') -Force | Out-Null
foreach ($name in @('flash_d31_recovery.ps1','approved-package.json','installed-files.json')) {
    Copy-Item -LiteralPath (Join-Path $RuntimeRoot $name) -Destination (Join-Path $sandbox $name)
}
& "$env:SystemRoot\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /reference:System.Web.Extensions.dll ("/out:" + (Join-Path $sandbox 'tools/adb.exe')) (Join-Path $PSScriptRoot 'BackendFakeAdb.cs')
if ($LASTEXITCODE) { throw '模拟ADB编译失败' }
$env:D31_TEST_CASE = 'bad-recovery'
$env:D31_TEST_TRANSCRIPT = Join-Path $sandbox 'commands.txt'
try {
    $output = & "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File (Join-Path $sandbox 'flash_d31_recovery.ps1') -Serial '192.0.2.31:5555' -PackagePath $Package -SkipBackup -PreflightOnly 2>&1
    $code = $LASTEXITCODE
    $output | Out-File (Join-Path $sandbox 'result.log') -Encoding UTF8
    $commands = Get-Content -LiteralPath $env:D31_TEST_TRANSCRIPT -Raw
    $queried = $commands -match 'sha256sum\s+\S*/recovery'
    if ($commands -match 'reboot|/cache/recovery/command|\spush\s') { throw '只读审计意外越过写入门' }
    $result = [ordered]@{ backendExitCode=$code; recoveryHashQueried=$queried; defectReproduced=($code -eq 0 -and -not $queried) }
    $result | ConvertTo-Json | Tee-Object -FilePath (Join-Path $sandbox 'audit.json')
} finally {
    Remove-Item Env:D31_TEST_CASE -ErrorAction SilentlyContinue
    Remove-Item Env:D31_TEST_TRANSCRIPT -ErrorAction SilentlyContinue
}
