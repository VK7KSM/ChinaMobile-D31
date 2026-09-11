param([Parameter(Mandatory=$true)][string]$Apk,[Parameter(Mandatory=$true)][string]$CapturePath,[Parameter(Mandatory=$true)][int]$ExpectedVersion,[string]$Serial)
$ErrorActionPreference='Stop'
if([string]::IsNullOrWhiteSpace($Serial) -or $Serial -match '[\s\x00]'){throw '必须通过-Serial显式传入完整目标设备序列号'}
$adb='C:/Dev/android-sdk/platform-tools/adb.exe'
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '捕获目录必须是新目录'}
New-Item -ItemType Directory -Path $capture | Out-Null
function Read-Device([string]$command){
    $result=& $adb -P 5042 -s $serial shell $command 2>&1
    if($LASTEXITCODE -ne 0){throw '设备命令未完成'}
    return ($result -join "`n")
}
function Save-Device([string]$name,[string]$command){
    Read-Device $command | Out-File -LiteralPath (Join-Path $capture $name) -Encoding utf8
}
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $capture 'host.ps1')
Save-Device 'before-private.txt' 'date; getprop ro.build.fingerprint; dumpsys package net.elfradio.d31bootstrap; ps; mount; iptables-save; ip6tables-save'
Save-Device 'active-before-private.json' 'cat /data/local/d31-remote/runtime/active.json'
$installed=(Read-Device 'pm path net.elfradio.d31bootstrap').Trim() -replace '^package:',''
& $adb -P 5042 -s $serial pull $installed (Join-Path $capture 'installed-before.apk') *> (Join-Path $capture 'pull-installed.log')
if($LASTEXITCODE -ne 0){throw '旧APK备份失败'}
& $adb -P 5042 -s $serial pull /system/priv-app/D31ElfRemote/D31ElfRemote.apk (Join-Path $capture 'baseline-before.apk') *> (Join-Path $capture 'pull-baseline.log')
if($LASTEXITCODE -ne 0){throw '系统基线备份失败'}
$tag=[DateTime]::UtcNow.ToString('HHmmssfff')
$chain='D31_MANUAL_'+$tag
$cleanupPath='/data/local/tmp/d31-manual-cleanup-'+$tag+'.sh'
$cleanup=@'
for tool in iptables ip6tables; do
  $tool -D OUTPUT -m owner --uid-owner 0 -j __CHAIN__ 2>/dev/null
  $tool -F __CHAIN__ 2>/dev/null
  $tool -X __CHAIN__ 2>/dev/null
done
'@
$cleanup=$cleanup.Replace('__CHAIN__',$chain)
$cleanup64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($cleanup))
$guard='printf %s '+$cleanup64+' | /system/bin/busybox base64 -d > '+$cleanupPath+'; /system/bin/busybox setsid /system/bin/sh -c ''sleep 180; sh '+$cleanupPath+''' </dev/null >/dev/null 2>&1 &'
Save-Device 'cleanup-armed.txt' $guard
try {
    $block=@'
set -e
iptables -N D31_MANUAL_TEST
iptables -A D31_MANUAL_TEST -d 127.0.0.0/8 -j RETURN
iptables -A D31_MANUAL_TEST -d 10.0.0.0/8 -j RETURN
iptables -A D31_MANUAL_TEST -d 172.16.0.0/12 -j RETURN
iptables -A D31_MANUAL_TEST -d 192.168.0.0/16 -j RETURN
iptables -A D31_MANUAL_TEST -p tcp --dport 443 -j REJECT
iptables -A D31_MANUAL_TEST -p tcp --dport 8883 -j REJECT
iptables -I OUTPUT 1 -m owner --uid-owner 0 -j D31_MANUAL_TEST
ip6tables -N D31_MANUAL_TEST
ip6tables -A D31_MANUAL_TEST -d ::1/128 -j RETURN
ip6tables -A D31_MANUAL_TEST -d fc00::/7 -j RETURN
ip6tables -A D31_MANUAL_TEST -d fe80::/10 -j RETURN
ip6tables -A D31_MANUAL_TEST -p tcp --dport 443 -j REJECT
ip6tables -A D31_MANUAL_TEST -p tcp --dport 8883 -j REJECT
ip6tables -I OUTPUT 1 -m owner --uid-owner 0 -j D31_MANUAL_TEST
'@
    $block=$block.Replace('D31_MANUAL_TEST',$chain)
    Save-Device 'block-installed.txt' $block
    & $adb -P 5042 -s $serial install -r $Apk *> (Join-Path $capture 'install.txt')
    if($LASTEXITCODE -ne 0){throw 'APK安装失败'}
    $until=[DateTime]::UtcNow.AddSeconds(100)
    $done=$false
    while([DateTime]::UtcNow -lt $until){
        $raw=Read-Device 'cat /data/local/d31-remote/runtime/state/health.json'
        $health=$raw | ConvertFrom-Json
        $raw | Out-File -LiteralPath (Join-Path $capture ('health-'+[DateTime]::UtcNow.ToString('HHmmssfff')+'.json')) -Encoding utf8
        if($health.version_code -eq $ExpectedVersion -and $health.local_ready -eq $true -and $health.report_acknowledged -eq $false){
            $record=Read-Device ('cat /data/local/d31-remote/runtime/updates/manual/'+$health.apk_sha256+'.json')
            if(($record | ConvertFrom-Json).phase -eq 'success'){$done=$true;break}
        }
        Start-Sleep -Seconds 2
    }
    Save-Device 'manual-result-private.txt' 'cat /data/local/d31-remote/runtime/updates/manual-bootstrap/result.json; cat /data/local/d31-remote/runtime/updates/manual/*.json; cat /data/local/d31-remote/runtime/updates/last-error.json'
    Save-Device 'offline-state-private.txt' ('ps; mount; dumpsys package net.elfradio.d31bootstrap; iptables -L '+$chain+' -nv; ip6tables -L '+$chain+' -nv')
    if(!$done){throw '离线核心交接成功证据未取得'}
    'OFFLINE_CORE_HEALTH_OBSERVED'
} finally {
    Save-Device 'cleanup-result.txt' $cleanup
    Save-Device 'after-cleanup-private.txt' 'iptables-save; ip6tables-save; ps'
}
