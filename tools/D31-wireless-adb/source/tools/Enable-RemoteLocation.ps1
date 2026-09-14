param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9.]+:[0-9]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][int]$ExpectedVersion,
    [Parameter(Mandatory=$true)][string]$CapturePath
)
$ErrorActionPreference='Stop'
$adb='C:/Dev/android-sdk/platform-tools/adb.exe'
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '必须使用全新私有目录'}
New-Item -ItemType Directory -Path $capture | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination "$capture/host.ps1"
function Read-Device([string]$Command){
    $raw=(& $adb -P 5042 -s $Serial shell $Command 2>&1 | Out-String).Trim()
    if($LASTEXITCODE -ne 0){throw 'D31命令失败，保留原件'}
    return $raw
}
function Save-Device([string]$Name,[string]$Command){
    $value=Read-Device $Command
    $value | Out-File "$capture/$Name" -Encoding utf8 -NoClobber
    return $value
}
if((Read-Device 'getprop ro.product.device') -cne 'hct6735_66_m0'){throw '设备型号不符'}
$health=(Save-Device 'health-before-private.json' 'cat /data/local/d31-remote/runtime/state/health.json') | ConvertFrom-Json
if($health.version_code -ne $ExpectedVersion -or !$health.local_ready){throw '不是已验收的候选核心'}
[void](Save-Device 'baseline-private.txt' 'date; getprop ro.build.fingerprint; dumpsys location; appops get net.elfradio.d31bootstrap; dumpsys package net.elfradio.d31bootstrap')
$before=[ordered]@{
    mode=Read-Device 'settings get secure location_mode'
    providers=Read-Device 'settings get secure location_providers_allowed'
    wifi=Read-Device 'settings get global wifi_on'
    mobile=Read-Device 'settings get global mobile_data'
}
$before | ConvertTo-Json | Out-File "$capture/settings-before-private.json" -Encoding utf8 -NoClobber
# 仅启用系统定位；不打开Wi-Fi、移动数据，不改变默认网络或账号。
[void](Save-Device 'enable-mode.txt' 'settings put secure location_mode 3')
[void](Save-Device 'enable-gps.txt' 'settings put secure location_providers_allowed +gps')
[void](Save-Device 'enable-network.txt' 'settings put secure location_providers_allowed +network')
$after=[ordered]@{
    mode=Read-Device 'settings get secure location_mode'
    providers=Read-Device 'settings get secure location_providers_allowed'
    wifi=Read-Device 'settings get global wifi_on'
    mobile=Read-Device 'settings get global mobile_data'
}
$after | ConvertTo-Json | Out-File "$capture/settings-after-private.json" -Encoding utf8 -NoClobber
[void](Save-Device 'location-after-private.txt' 'dumpsys location')
if($after.mode -ne '3' -or $after.providers -notmatch '(^|,)gps(,|$)'){throw '定位设置未生效，不宣称定位成功'}
if($after.wifi -cne $before.wifi -or $after.mobile -cne $before.mobile){throw '网络开关发生非预期变化'}
'系统定位已启用，网络开关保持原值；GPS取点及云定位来源另行验收。'
