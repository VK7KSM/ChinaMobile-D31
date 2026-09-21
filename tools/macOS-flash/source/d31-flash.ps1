<#
D31（星网锐捷 SVP3390 / 中国移动云视讯）刷机工具 macOS 命令行版 —— 主流程。

只做两件事：把签名刷机包取到本机，然后交给既有的 flash_d31_recovery.ps1。
刷机逻辑一行不改，Windows 图形版和本命令行版跑的是同一份后端，不会分叉。

不做的事（所有者 2026-09-22 决定）：图形界面、原厂 uptool 救砖通道、刷机前备份。
备份省掉的风险有限：安装器本来就不写 nvram、nvdata、protect1/2、proinfo 这些身份与校准分区。

前提：D31 插着网线。后端会核对所连地址属于设备 eth0，这条保留——刷机会清空 userdata，
Wi-Fi 密码随之消失，只有有线才能在重启后自动回到网上让脚本完成最终校验。
本机（Mac）用什么上网无所谓，和 D31 同一个局域网即可。
#>

[CmdletBinding()]
param(
    # D31 的有线地址与设备侧 ADB 端口，例如 192.168.2.62:5555
    [Parameter(Mandatory = $true)][string]$Device,
    # 已经下好的签名刷机包；不给就按清单下载
    [string]$PackagePath,
    [ValidateSet('github', 'cloudflare')][string]$Source = 'github',
    # 刷机包与运行时的存放目录，默认放在本工具目录下
    [string]$DownloadDirectory,
    # 引导脚本传进来的 adb 路径
    [string]$AdbPath,
    # 只做只读检查，不写设备任何分区
    [switch]$PreflightOnly,
    # 跳过“会清空全部数据”的二次确认，用于无人值守
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$ToolRoot = Split-Path -Parent $PSCommandPath
$DefaultDownloads = Join-Path $ToolRoot 'downloads'
if (-not $DownloadDirectory) { $DownloadDirectory = $DefaultDownloads }

function Write-Step { param([string]$Text) Write-Host "==> $Text" }
function Fail { param([string]$Text) Write-Host "错误：$Text" -ForegroundColor Red; exit 1 }

# 后端目录：环境变量优先，其次是分发布局，最后是仓库内布局。
function Resolve-Backend {
    $candidates = @()
    if ($env:D31_BACKEND) { $candidates += $env:D31_BACKEND }
    $candidates += (Join-Path $ToolRoot 'backend')
    $candidates += (Join-Path $ToolRoot '../../d31/factory_package')
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $script = Join-Path $candidate 'flash_d31_recovery.ps1'
        if (Test-Path -LiteralPath $script -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    Fail "找不到刷机后端目录（需要 flash_d31_recovery.ps1）。可用 D31_BACKEND 环境变量指定。"
}

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

<#
用 curl 下载而不是 Invoke-WebRequest：macOS 自带 curl，断点续传只要 -C -，
1.7 GB 的包在 Wi-Fi 上中断重来的代价太大，续传是必需的，顺带还有进度条。
#>
function Get-Firmware {
    param([string]$Url, [string]$Destination, [int64]$ExpectedBytes, [string]$ExpectedHash)

    if (Test-Path -LiteralPath $Destination -PathType Leaf) {
        $item = Get-Item -LiteralPath $Destination
        if ($item.Length -eq $ExpectedBytes -and (Get-Sha256 $Destination) -eq $ExpectedHash) {
            Write-Step "本地已有完整且校验正确的刷机包，跳过下载。"
            return
        }
        Write-Step "本地刷机包不完整或校验不符，继续断点续传。"
    }

    Write-Step "下载刷机包（$([math]::Round($ExpectedBytes / 1GB, 2)) GB，可中断后重跑本命令续传）"
    & curl -fL --progress-bar -C - -o $Destination $Url
    if ($LASTEXITCODE -ne 0) { Fail "刷机包下载失败（curl 退出码 $LASTEXITCODE）。重跑本命令会从断点继续。" }

    $actualBytes = (Get-Item -LiteralPath $Destination).Length
    if ($actualBytes -ne $ExpectedBytes) {
        Fail "刷机包长度不符：期望 $ExpectedBytes 字节，实际 $actualBytes 字节。"
    }
    $actualHash = Get-Sha256 $Destination
    if ($actualHash -ne $ExpectedHash) {
        Fail "刷机包 SHA-256 不匹配，已拒绝使用。期望 $ExpectedHash，实际 $actualHash"
    }
    Write-Step "刷机包校验通过。"
}

$backend = Resolve-Backend
$flashScript = Join-Path $backend 'flash_d31_recovery.ps1'
$manifestPath = Join-Path $backend 'approved-package.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { Fail "后端目录缺少 approved-package.json：$backend" }
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json

Write-Host "D31 刷机工具 macOS 命令行版"
Write-Host "后端目录：$backend"
Write-Host "固件版本：$($manifest.version)    $($manifest.bytes) 字节"
Write-Host "目标设备：$Device"
Write-Host ""

if (-not $PackagePath) {
    $url = if ($Source -eq 'cloudflare') { $manifest.cloudflareUrl } else { $manifest.githubUrl }
    New-Item -ItemType Directory -Force -Path $DownloadDirectory | Out-Null
    $PackagePath = Join-Path $DownloadDirectory $manifest.fileName
    Get-Firmware -Url $url -Destination $PackagePath `
        -ExpectedBytes ([int64]$manifest.bytes) -ExpectedHash ([string]$manifest.sha256).ToUpperInvariant()
} else {
    if (-not (Test-Path -LiteralPath $PackagePath -PathType Leaf)) { Fail "指定的刷机包不存在：$PackagePath" }
    $PackagePath = (Resolve-Path -LiteralPath $PackagePath).Path
    Write-Step "使用本地刷机包：$PackagePath"
}

# ADB 服务器端口固定 5042：后端强制要求，为的是不去碰你自己那个默认 5037 的 adb。
$adbArguments = @(
    '-Serial', $Device
    '-AdbPort', '5042'
    '-PackagePath', $PackagePath
    '-SkipBackup'
)
if ($AdbPath) { $adbArguments += @('-AdbPath', $AdbPath) }

if ($PreflightOnly) {
    Write-Step "只读检查：不写设备任何分区。"
    & $flashScript @adbArguments -DevicePreflightOnly
    exit $LASTEXITCODE
}

if (-not $Yes) {
    Write-Host ""
    Write-Host "刷机会清空这台 D31 的全部数据：账号、应用、通信录、邮件、Wi-Fi 与 SIP 配置。" -ForegroundColor Yellow
    Write-Host "触发 Recovery 之后请勿断电、拔网线或让电脑休眠。" -ForegroundColor Yellow
    $answer = Read-Host "确认继续请输入 YES"
    if ($answer -cne 'YES') { Write-Host "已取消，未对设备做任何写入。"; exit 1 }
}

Write-Step "开始刷机。"
& $flashScript @adbArguments
exit $LASTEXITCODE
