param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9.]+:[0-9]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$CapturePath
)
$ErrorActionPreference='Stop'
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '捕获目录必须全新'}
New-Item -ItemType Directory -Path $capture | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination "$capture/host.ps1"
$adb='C:/Dev/android-sdk/platform-tools/adb.exe'
$target=(& $adb -P 5042 -s $Serial shell getprop ro.product.device | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $target -cne 'hct6735_66_m0'){throw '目标不是D31'}
$commands=[ordered]@{
    'identity-private.txt'='date; getprop ro.build.fingerprint; cat /data/local/d31-remote/runtime/active.json'
    'health-private.json'='cat /data/local/d31-remote/runtime/state/health.json'
    'media-private.json'='cat /data/local/d31-remote/runtime/state/media-status.json'
    'work-private.json'='cat /data/local/d31-remote/runtime/state/work-status.json'
    'location-private.txt'='dumpsys location'
    'location-status-private.json'='cat /data/local/d31-remote/runtime/state/location-status.json'
    'services-private.txt'='dumpsys activity services net.elfradio.d31bootstrap'
    'camera-private.txt'='dumpsys media.camera'
    'audio-private.txt'='dumpsys audio; dumpsys media.audio_flinger; dumpsys media.audio_policy'
    'log-private.txt'='logcat -d -t 1200'
}
$commands | ConvertTo-Json | Out-File "$capture/commands-private.json" -Encoding utf8 -NoClobber
foreach($name in $commands.Keys){
    & $adb -P 5042 -s $Serial shell $commands[$name] 2>&1 | Out-File "$capture/$name" -Encoding utf8 -NoClobber
    if($LASTEXITCODE -ne 0){throw "读取失败：$name"}
}
$records=@(Get-ChildItem -LiteralPath $capture -File | ForEach-Object {
    [ordered]@{path=$_.Name;bytes=$_.Length;sha256=(Get-FileHash $_.FullName).Hash.ToLowerInvariant()}
})
$records | ConvertTo-Json | Out-File "$capture/artifacts.json" -Encoding utf8 -NoClobber
'业务只读捕获完成，原件仅保存在私有目录。'
