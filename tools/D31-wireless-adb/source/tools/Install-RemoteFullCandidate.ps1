param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9.]+:[0-9]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedSha256,
    [Parameter(Mandatory=$true)][int]$ExpectedVersion,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$PriorSha256,
    [Parameter(Mandatory=$true)][string]$CapturePath
)
$ErrorActionPreference='Stop'
$adb='C:/Dev/android-sdk/platform-tools/adb.exe'
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '捕获目录必须全新'}
if((Get-FileHash -LiteralPath $ApkPath).Hash -ine $ExpectedSha256){throw '候选摘要不符'}
New-Item -ItemType Directory -Path $capture | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination "$capture/host.ps1"
function Read-Device([string]$Command){
    $wrapped=$Command+"`nresult=`$?; echo; echo D31_DEVICE_EXIT_`$result"
    $raw=(& $adb -P 5042 -s $Serial shell $wrapped 2>&1 | Out-String).Replace("`r",'').Trim()
    if($LASTEXITCODE -ne 0 -or $raw -notmatch '(?s)^(?:(.*)\n)?D31_DEVICE_EXIT_0$'){throw "设备命令未成功：$raw"}
    return ([string]$Matches[1]).Trim()
}
function Save-Device([string]$Name,[string]$Command){
    $value=Read-Device $Command
    $value | Out-File -LiteralPath "$capture/$Name" -Encoding utf8 -NoClobber
    return $value
}
if((Read-Device 'getprop ro.product.device') -ne 'hct6735_66_m0' -or
   (Read-Device 'getprop ro.product.model') -ne 'hct6737t_66_m0'){throw '目标不是已验证D31'}
[void](Save-Device 'baseline-private.txt' 'date; getprop ro.build.fingerprint; dumpsys package net.elfradio.d31bootstrap; ps; mount')
$active=(Save-Device 'active-before-private.json' 'cat /data/local/d31-remote/runtime/active.json') | ConvertFrom-Json
if($active.sha256 -cne $PriorSha256){throw '活动原像与预登记不符'}
$installed=(Read-Device 'pm path net.elfradio.d31bootstrap') -replace '^package:',''
if($installed -notmatch '^/data/app/net\.elfradio\.d31bootstrap-[0-9]+/base\.apk$' -and
   $installed -cne '/system/priv-app/D31ElfRemote/D31ElfRemote.apk'){throw '原安装路径未经确认'}
& $adb -P 5042 -s $Serial pull $installed "$capture/installed-before.apk" *> "$capture/pull-installed.log"
if($LASTEXITCODE -ne 0 -or (Get-FileHash "$capture/installed-before.apk").Hash -ine $PriorSha256){throw '原APK备份失败'}
& $adb -P 5042 -s $Serial pull /system/priv-app/D31ElfRemote/D31ElfRemote.apk "$capture/system-before.apk" *> "$capture/pull-system.log"
if($LASTEXITCODE -ne 0){throw '系统原件备份失败'}
[void](Read-Device 'test ! -e /data/local/d31-remote/runtime/updates/manual/pending.json && test ! -e /data/local/d31-remote/runtime/updates/stop-supervisor && test ! -e /data/local/d31-remote/runtime/maintenance/repair.json')
$cloud=Save-Device 'cloud-before-private.txt' 'for d in /data/local/d31-remote/runtime/updates/jobs/*; do if [ -f "$d/offer.json" ]; then cat "$d/state.json" || exit 9; echo; fi; done; true'
foreach($line in $cloud -split "`n"){
    if($line.Trim() -and ($line | ConvertFrom-Json).phase -notin @('success','recovered','rejected')){throw '云更新尚未结束'}
}
[void](Save-Device 'health-before-private.json' 'cat /data/local/d31-remote/runtime/state/health.json')
[void](Save-Device 'log-before-private.txt' 'logcat -d -t 300')
& $adb -P 5042 -s $Serial install -r $ApkPath *> "$capture/install.txt"
if($LASTEXITCODE -ne 0 -or (Get-Content "$capture/install.txt" -Raw) -notmatch '(?m)^Success\s*$'){throw '安装未成功，保留原件，不自动重装'}
$until=[DateTime]::UtcNow.AddSeconds(180)
$passed=$false; $n=0
while([DateTime]::UtcNow -lt $until){
    $n++
    $health=(Save-Device "health-$n-private.json" 'cat /data/local/d31-remote/runtime/state/health.json') | ConvertFrom-Json
    if($health.version_code -eq $ExpectedVersion -and $health.apk_sha256 -ceq $ExpectedSha256 -and $health.local_ready){
        $manual=(Save-Device "manual-$n-private.json" "cat /data/local/d31-remote/runtime/updates/manual/$ExpectedSha256.json") | ConvertFrom-Json
        if($manual.phase -eq 'success'){$passed=$true;break}
    }
    Start-Sleep -Seconds 2
}
[void](Save-Device 'active-after-private.json' 'cat /data/local/d31-remote/runtime/active.json')
[void](Save-Device 'after-private.txt' 'dumpsys package net.elfradio.d31bootstrap; ps; mount')
[void](Save-Device 'log-after-private.txt' 'logcat -d -t 500')
if(!$passed){throw '未取得手动交接成功，停止后续变更并保留现场'}
$active=Get-Content "$capture/active-after-private.json" -Raw | ConvertFrom-Json
if($active.sha256 -cne $ExpectedSha256 -or $active.versionCode -ne $ExpectedVersion){throw '实际活动指针不符'}
$inventory=(Save-Device 'inventory-private.json' ("CLASSPATH='"+$active.path+"' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteRuntimeInventory")) | ConvertFrom-Json
if($inventory.installed.metadata.versionCode -ne $ExpectedVersion -or $inventory.assessment.installedVsActive -ne 'MATCH' -or !$inventory.installation.metadata.privileged){throw '系统身份或实际安装回读不符'}
$systemHash=(Read-Device '/system/bin/busybox sha256sum /system/priv-app/D31ElfRemote/D31ElfRemote.apk').Split(' ')[0]
if($systemHash -ine (Get-FileHash "$capture/system-before.apk").Hash){throw '普通更新意外改变系统原件'}
[ordered]@{通过=$true;版本=$ExpectedVersion;同包保留数据=$true;系统原件未替换=$true;实际加载='另行核对进程映射'} | ConvertTo-Json | Out-File "$capture/result.json" -Encoding utf8 -NoClobber
'完整候选同包接替与系统身份检查通过，未重启整机。'
