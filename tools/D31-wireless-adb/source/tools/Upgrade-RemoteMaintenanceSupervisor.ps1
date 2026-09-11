param(
 [Parameter(Mandatory=$true)][ValidatePattern('^[0-9.]+:[0-9]+$')][string]$Serial,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedSha256,
 [ValidateRange(96,2099999999)][int]$ExpectedVersion=96,
 [switch]$VerifyOnly,
 [Parameter(Mandatory=$true)][string]$CapturePath
)
$ErrorActionPreference='Stop'
$adb='C:/Dev/android-sdk/platform-tools/adb.exe'
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path $capture){throw '捕获目录必须全新'}
New-Item -ItemType Directory -Path $capture | Out-Null
Copy-Item $PSCommandPath "$capture/host.ps1"
function Read-Device([string]$Command){
 $raw=(& $adb -P 5042 -s $Serial shell ($Command+"`nresult=`$?; echo; echo D31_DEVICE_EXIT_`$result") 2>&1 | Out-String).Replace("`r",'').Trim()
 if($LASTEXITCODE -ne 0 -or $raw -notmatch '(?s)^(?:(.*)\n)?D31_DEVICE_EXIT_0$'){throw "设备命令未完成：$raw"}
 return ([string]$Matches[1]).Trim()
}
function Save-Device([string]$Name,[string]$Command){
 $value=Read-Device $Command
 $value | Out-File "$capture/$Name" -Encoding utf8 -NoClobber
 return $value
}
function Test-ArchiveMapping([string]$Maps,[string]$Apk){
 $encoded=$Apk.TrimStart('/').Replace('/','@')+'@classes.dex'
 foreach($line in $Maps -split "`n"){
  $columns=$line.Trim() -split '\s+',6
  if($columns.Count -ne 6){continue}
  $file=$columns[5]
  if($file -ceq $Apk -or $file -cmatch ('^/data/dalvik-cache/(arm|arm64)/'+[regex]::Escape($encoded)+'$')){return $true}
 }
 return $false
}
function Get-SystemMountMode([string]$Mounts){
 $entries=@($Mounts -split "`n" | Where-Object {$_ -match '^\S+ /system \S+ \S+ '})
 if($entries.Count -ne 1){throw '系统挂载项不唯一'}
 $options=($entries[0] -split '\s+')[3] -split ','
 $modes=@($options | Where-Object {$_ -ceq 'ro' -or $_ -ceq 'rw'})
 if($modes.Count -ne 1){throw '系统挂载模式不明确'}
 return $modes[0]
}
if((Read-Device 'getprop ro.product.device') -ne 'hct6735_66_m0'){throw '设备类型不符'}
$active=(Save-Device 'active-before-private.json' 'cat /data/local/d31-remote/runtime/active.json') | ConvertFrom-Json
if($active.versionCode -ne $ExpectedVersion -or $active.sha256 -cne $ExpectedSha256){throw '目标完整候选尚未接替，不升级监督'}
$apk="/data/local/d31-remote/releases/$ExpectedSha256/remote.apk"
if($active.path -cne $apk){throw '活动原件路径不符'}
[void](Save-Device 'supervisor-before-private.json' 'cat /data/local/d31-remote/runtime/updates/supervisor.json')
[void](Save-Device 'before-private.txt' 'date; getprop ro.build.fingerprint; ps; mount; ls -lZ /system/priv-app/D31ElfRemote/D31ElfRemote.apk')
$beforeMode=Get-SystemMountMode (Save-Device 'mount-before-private.txt' 'cat /proc/mounts')
& $adb -P 5042 -s $Serial pull /system/priv-app/D31ElfRemote/D31ElfRemote.apk "$capture/system-before.apk" *> "$capture/pull-before.txt"
if($LASTEXITCODE -ne 0){throw '原系统APK未备份'}
$beforeHash=(Read-Device '/system/bin/busybox sha256sum /system/priv-app/D31ElfRemote/D31ElfRemote.apk').Split(' ')[0]
if($beforeHash -ine (Get-FileHash "$capture/system-before.apk").Hash){throw '原像摘要不符'}
if(!$VerifyOnly -and $beforeHash -ceq $ExpectedSha256){throw '系统基线已是候选，不重放升级；使用VerifyOnly只读核验'}
if($VerifyOnly -and $beforeHash -cne $ExpectedSha256){throw '仅核验模式的系统基线不是指定候选'}
$command="CLASSPATH='$apk' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteManualBootstrap maintenance"
[ordered]@{目标='已验证D31开发板';设备序列号=$Serial;模式=$(if($VerifyOnly){'只读核验'}else{'显式监督升级'});命令=$(if($VerifyOnly){'仅回读已有接替结果、心跳、进程映射和系统属性'}else{$command});系统原像摘要=$beforeHash;目标摘要=$ExpectedSha256;时间=[DateTimeOffset]::Now.ToString('o');回退='机内保留原件和结果；失败先回读，原件恢复后重启独立监督；不写boot/recovery'} | ConvertTo-Json | Out-File "$capture/preregister-private.json" -Encoding utf8 -NoClobber
if(!$VerifyOnly){[void](Save-Device 'upgrade-output.txt' $command)}
$upgrade=(Save-Device 'upgrade-result-private.json' 'cat /data/local/d31-remote/runtime/updates/manual-bootstrap/result.json') | ConvertFrom-Json
if($upgrade.state -ne 'supervisor_updated' -or $upgrade.version_code -ne $ExpectedVersion -or $upgrade.startup.state -ne 'supervisor_confirmed'){throw '尚未取得此次真实监督启动握手'}
$startedAt=[long]$upgrade.startup.started_at_ms
$until=[DateTime]::UtcNow.AddSeconds(120);$n=0;$ok=$false
while([DateTime]::UtcNow -lt $until){
 $n++
 $supervisor=(Save-Device "supervisor-$n-private.json" 'cat /data/local/d31-remote/runtime/updates/supervisor.json') | ConvertFrom-Json
 $health=(Save-Device "health-$n-private.json" 'cat /data/local/d31-remote/runtime/state/health.json') | ConvertFrom-Json
 $now=[long](Read-Device 'date +%s')*1000
 $supervisorAge=$now-[long]$supervisor.time_ms
 $coreAge=$now-[long]$health.time_ms
 if($supervisor.version_code -eq $ExpectedVersion -and $supervisor.pid -eq $upgrade.startup.pid -and $supervisor.maintenance_protocol -eq 1 -and $health.version_code -eq $ExpectedVersion -and $health.apk_sha256 -ceq $ExpectedSha256 -and $health.maintenance_protocol -eq 1 -and $health.local_ready -and [long]$health.time_ms -ge $startedAt -and $supervisorAge -ge -1000 -and $supervisorAge -lt 20000 -and $coreAge -ge -1000 -and $coreAge -lt 20000){$ok=$true;break}
 Start-Sleep -Seconds 2
}
[void](Save-Device 'after-private.txt' 'ps; mount; ls -lZ /system/priv-app/D31ElfRemote/D31ElfRemote.apk; logcat -d -t 300')
$actual=(Read-Device '/system/bin/busybox sha256sum /system/priv-app/D31ElfRemote/D31ElfRemote.apk').Split(' ')[0]
if(!$ok -or $actual -cne $ExpectedSha256){throw '监督接替未确认，保留原件与结果，不继续修复验收'}
$proc=[int]$health.pid
$stat=Save-Device 'core-stat-private.txt' "cat /proc/$proc/stat"
$fields=$stat.Substring($stat.LastIndexOf(')')+2) -split '\s+'
if([int]$fields[1] -ne [int]$supervisor.pid){throw '当前核心不属于本次监督'}
$maps=Save-Device 'core-maps-private.txt' "cat /proc/$proc/maps"
if(!(Test-ArchiveMapping $maps $apk)){throw '实际核心映射不符'}
$supervisorMaps=Save-Device 'supervisor-maps-private.txt' "cat /proc/$([int]$supervisor.pid)/maps"
if(!(Test-ArchiveMapping $supervisorMaps '/system/priv-app/D31ElfRemote/D31ElfRemote.apk')){throw '实际监督映射不符'}
[void](Read-Device 'test ! -e /data/local/d31-remote/runtime/updates/stop-supervisor')
$afterMode=Get-SystemMountMode (Save-Device 'mount-after-private.txt' 'cat /proc/mounts')
if($afterMode -cne $beforeMode){throw '系统挂载模式与本次操作前不符'}
[ordered]@{通过=$true;仅只读核验=[bool]$VerifyOnly;监督版本=$ExpectedVersion;核心版本=$ExpectedVersion;系统原像摘要=$beforeHash;候选摘要=$actual;原挂载模式=$beforeMode;当前挂载模式=$afterMode;重启整机=$false} | ConvertTo-Json | Out-File "$capture/result.json" -Encoding utf8 -NoClobber
'固定监督及活动核心核验通过，系统挂载模式与本次操作前一致。'
