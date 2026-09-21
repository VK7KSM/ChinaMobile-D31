<#
把 macOS 命令行版打包成一个可分发目录与 zip。

刻意不把后端脚本复制成第二份长期存在的副本：打包时从 factory_package 取当前版本，
这样 Windows 图形版和 macOS 命令行版永远是同一份刷机逻辑，不会各自漂移。
#>

[CmdletBinding()]
param(
    [string]$Backend,
    [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'

# 不在 param 默认值里用 $PSScriptRoot：Windows PowerShell 5.1 下那里可能还是空的。
$ToolRoot = Split-Path -Parent $PSCommandPath
if (-not $Backend) { $Backend = Join-Path $ToolRoot '../../d31/factory_package' }
if (-not $OutputRoot) { $OutputRoot = Join-Path $ToolRoot 'dist-mac' }

$backendPath = (Resolve-Path -LiteralPath $Backend).Path
$manifestPath = Join-Path $backendPath 'approved-package.json'
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json

$backendFiles = @('flash_d31_recovery.ps1', 'approved-package.json', 'installed-files.json')
foreach ($name in $backendFiles) {
    $source = Join-Path $backendPath $name
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "后端缺少 $name" }
}

# 刷机脚本必须能在 PowerShell 7 上跑：这里只做静态检查，真正的验收在真机。
$errors = $null
$tokens = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $backendPath 'flash_d31_recovery.ps1'), [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count) { throw "刷机脚本语法错误 $($errors.Count) 处，拒绝打包" }

# 后端在 macOS 上取 adb 要用无扩展名的可执行文件；这行改错会让工具找不到 adb。
$adbLine = Select-String -LiteralPath (Join-Path $backendPath 'flash_d31_recovery.ps1') -Pattern "if \(\`$OnWindows\) \{ 'adb\.exe' \} else \{ 'adb' \}"
if (-not $adbLine) { throw "刷机脚本没有按平台选择 adb 可执行文件名，拒绝打包" }

$name = "D31-Flash-Tool-mac-v$($manifest.version)"
$stage = Join-Path $OutputRoot $name
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'backend') | Out-Null

foreach ($file in @('d31-flash.sh', 'd31-flash.ps1', '使用说明.md')) {
    Copy-Item -LiteralPath (Join-Path $ToolRoot $file) -Destination (Join-Path $stage $file)
}
$backendStage = Join-Path $stage 'backend'
foreach ($file in $backendFiles) {
    Copy-Item -LiteralPath (Join-Path $backendPath $file) -Destination (Join-Path $backendStage $file)
}

# 入口脚本用 LF 与 UTF-8：CRLF 会让 macOS 的 bash 报 "\r: command not found"。
$shPath = Join-Path $stage 'd31-flash.sh'
$shText = [System.IO.File]::ReadAllText($shPath) -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($shPath, $shText, [System.Text.UTF8Encoding]::new($false))

$zip = Join-Path $OutputRoot "$name.zip"
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip

Write-Host "已打包：$stage"
Write-Host "压缩包：$zip"
Write-Host ""
Write-Host "在 Mac 上解压后运行（zip 不保留可执行位，所以用 bash 起）："
Write-Host "  bash d31-flash.sh --device 192.168.2.62:5555"
