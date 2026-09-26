param([string]$Serial, [int]$AdbPort, [string]$PackagePath, [string]$RescueDirectory,
    [string]$OutputBase, [switch]$DevicePreflightOnly, [switch]$SkipBackup)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$mode=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'mode.txt')).Trim()
$trace=Join-Path $PSScriptRoot 'trace.txt'
if ($Serial -ne '192.0.2.31:5654' -or $AdbPort -ne 5042) { throw '测试目标不匹配' }
if ($DevicePreflightOnly) {
    [IO.File]::AppendAllText($trace,"检查`n")
    if ($mode -eq 'preflight-fail') { Write-Output '设备只读检查通过'; exit 1 }
    if ($mode -eq 'no-marker') { Write-Output '[3/3]'; exit 0 }
    Write-Output '设备只读检查通过'
} elseif ($OutputBase) {
    [IO.File]::AppendAllText($trace,"备份`n")
    if ($mode -eq 'backup-fail') { exit 1 }
    if ($mode -eq 'backup-no-marker') { exit 0 }
    $directory=Join-Path $OutputBase '离线备份'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    Write-Output "D31本机急救包创建通过：$directory"
} else {
    if (-not $PackagePath -or ($SkipBackup -eq [bool]$RescueDirectory)) { throw '刷机参数错误' }
    if ($RescueDirectory -and -not (Test-Path -LiteralPath $RescueDirectory -PathType Container)) { throw '备份目录不存在' }
    [IO.File]::AppendAllText($trace, $(if ($SkipBackup) { "刷机无备份`n" } else { "刷机有备份`n" }))
    Write-Output '离线刷机替身完成；没有连接设备'
}
