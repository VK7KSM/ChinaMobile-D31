param([Parameter(Mandatory=$true)][string]$RuntimeRoot, [Parameter(Mandatory=$true)][string]$Package, [Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$powershell = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$csc = "$env:SystemRoot\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$sandbox = Join-Path $OutputDirectory '中文 模拟设备'
New-Item -ItemType Directory -Path (Join-Path $sandbox 'tools') -Force | Out-Null
foreach ($name in @('flash_d31_recovery.ps1','approved-package.json','installed-files.json')) {
    Copy-Item -LiteralPath (Join-Path $RuntimeRoot $name) -Destination (Join-Path $sandbox $name)
}
& $csc /nologo /reference:System.Web.Extensions.dll ("/out:" + (Join-Path $sandbox 'tools/adb.exe')) (Join-Path $PSScriptRoot 'BackendFakeAdb.cs')
if ($LASTEXITCODE) { throw '模拟ADB编译失败' }
$backend = Join-Path $sandbox 'flash_d31_recovery.ps1'
$results = @()
function Run-Case([string]$Name, [string[]]$Arguments, [bool]$Pass, [bool]$MayReboot) {
    $env:D31_TEST_CASE = $Name
    $env:D31_TEST_TRANSCRIPT = Join-Path $OutputDirectory ($Name + '.commands.txt')
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $powershell -NoProfile -ExecutionPolicy Bypass -File $backend @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = 'Stop' }
    $output | Out-File (Join-Path $OutputDirectory ($Name + '.log')) -Encoding UTF8
    if (($code -eq 0) -ne $Pass) { throw "测试结果不符：$Name，退出码$code" }
    $commands = if(Test-Path $env:D31_TEST_TRANSCRIPT) { Get-Content -Raw $env:D31_TEST_TRANSCRIPT } else { '' }
    if (-not $MayReboot -and $commands -match 'reboot|/cache/recovery/command|\spush\s') { throw "失败测试越过写入门：$Name" }
    if ($Name -eq 'success' -and ($commands -notmatch 'reboot recovery' -or ($output -join "`n") -notmatch '完整Recovery刷机和首次启动验证全部完成')) { throw '完整模拟流程未完成' }
    Write-Output "通过：$Name"
}
try {
    Run-Case 'package-only' @('-PackagePath',$Package,'-PackagePreflightOnly') $true $false
    $bad = Join-Path $sandbox '损坏固件.zip'
    [IO.File]::WriteAllBytes($bad, [byte[]](1,2,3))
    Run-Case 'truncated' @('-PackagePath',$bad,'-PackagePreflightOnly') $false $false
    $stream = [IO.File]::Open($bad,[IO.FileMode]::Open)
    $stream.SetLength((Get-Item $Package).Length)
    $stream.Dispose()
    Run-Case 'same-size-corrupt' @('-PackagePath',$bad,'-PackagePreflightOnly') $false $false
    $base = @('-Serial','192.0.2.31:5555','-PackagePath',$Package,'-SkipBackup')
    foreach($name in @('wrong-network','bad-logo','bad-boot','no-space')) { Run-Case $name $base $false $false }
    Run-Case 'preflight' ($base + @('-PreflightOnly')) $true $false
    Run-Case 'bad-remote-hash' $base $false $false
    Run-Case 'missing-backup' @('-Serial','192.0.2.31:5555','-PackagePath',$Package) $false $false
    Run-Case 'success' $base $true $true
    Run-Case 'upload-resume' $base $true $true
    Run-Case 'bad-installed' $base $false $true
    Run-Case 'bad-handover' $base $false $true
    foreach($name in @('bad-initialization','bad-storage-support','bad-tcp-default','old-app-page')) { Run-Case $name $base $false $true }
} finally {
    Remove-Item Env:D31_TEST_CASE -ErrorAction SilentlyContinue
    Remove-Item Env:D31_TEST_TRANSCRIPT -ErrorAction SilentlyContinue
}
$global:LASTEXITCODE = 0
