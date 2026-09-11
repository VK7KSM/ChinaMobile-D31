param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
# 第五批已包含真实WebRTC依赖，旧入口复用完整独立测试，仍不运行共享Gradle。
& (Join-Path $PSScriptRoot 'Test-MicrophoneOffline.ps1') -OutputDirectory $OutputDirectory
