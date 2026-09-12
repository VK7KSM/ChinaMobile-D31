#requires -Version 7.0
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$')][string]$Serial,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedSha256,
    [Parameter(Mandatory=$true)][ValidateRange(1,2147483647)][int]$ExpectedVersion,
    [Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$CapturePath
)
$ErrorActionPreference='Stop'
$adb='C:/Dev/android-sdk/platform-tools/adb.exe'
$address,$port=$Serial.Split(':')
if(@($address.Split('.') | Where-Object {[int]$_ -gt 255}).Count -ne 0 -or [int]$port -lt 1 -or [int]$port -gt 65535){throw 'D31地址或端口不符'}
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '捕获目录必须全新'}
New-Item -ItemType Directory -Path $capture | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination "$capture/host.ps1"
$script:sequence=0
function Read-Device([string]$Command){
    $script:sequence++
    $label='{0:D2}' -f $script:sequence
    $marker='D31_READ_'+[Guid]::NewGuid().ToString('N')
    $wrapped="( $Command`n); result=`$?; echo; echo $($marker)_`$result"
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$adb; $start.UseShellExecute=$false; $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
    foreach($item in @('-P','5042','-s',$Serial,'shell',$wrapped)){$start.ArgumentList.Add($item)}
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$start
    $stdout=$null; $stderr=$null; $timedOut=$false
    $watch=[Diagnostics.Stopwatch]::StartNew()
    $record=[ordered]@{command=$Command;serial=$Serial;time=[DateTimeOffset]::Now.ToString('o');completed=$false}
    try{
        if(-not $process.Start()){throw 'ADB客户端未启动'}
        $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit(20000)){
            $timedOut=$true
            $process.Kill()
            if(-not $process.WaitForExit(5000)){throw '只读ADB客户端未退出'}
        }
        if(-not $stdout.Wait(5000) -or -not $stderr.Wait(5000)){throw '输出未完整结束'}
        if($timedOut){throw '只读ADB客户端超时，未停止服务器'}
        $value=$stdout.Result.Replace("`r",'').TrimEnd()
        $record.hostExitCode=$process.ExitCode
        $markers=[regex]::Matches($value,'(?m)^'+$marker+'_[0-9]+$')
        if($process.ExitCode -ne 0 -or $markers.Count -ne 1 -or $value -notmatch ('(?s)^(.*?)\n'+$marker+'_([0-9]+)$')){throw '设备退出标记未通过'}
        $body=$Matches[1]; $record.deviceExitCode=[int]$Matches[2]
        if($record.deviceExitCode -ne 0){throw '设备命令返回失败'}
        $record.completed=$true
        return $body.Trim()
    }finally{
        $record.stdoutComplete=$null -ne $stdout -and $stdout.IsCompletedSuccessfully
        $record.stderrComplete=$null -ne $stderr -and $stderr.IsCompletedSuccessfully
        $record.timedOut=$timedOut
        $record.hostDurationMs=$watch.ElapsedMilliseconds
        if($record.stdoutComplete){$stdout.Result | Out-File "$capture/$label-stdout-private.txt" -NoClobber -NoNewline -Encoding utf8}
        if($record.stderrComplete){$stderr.Result | Out-File "$capture/$label-stderr-private.txt" -NoClobber -NoNewline -Encoding utf8}
        $record | ConvertTo-Json | Out-File "$capture/$label-invocation-private.json" -NoClobber
        $process.Dispose()
    }
}
if((Read-Device 'getprop ro.product.device') -ne 'hct6735_66_m0' -or
   (Read-Device 'getprop ro.product.model') -ne 'hct6737t_66_m0' -or
   (Read-Device 'getprop ro.build.version.sdk') -ne '23'){
    throw '目标不是D31的API23'
}
[void](Read-Device 'date; getprop ro.build.fingerprint; getprop sys.boot_completed; cat /proc/uptime')
$boot=Read-Device 'cat /proc/sys/kernel/random/boot_id'
if($boot -notmatch '^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$'){throw '启动标识不符'}
$active=(Read-Device 'cat /data/local/d31-remote/runtime/active.json') | ConvertFrom-Json
if($active.sha256 -cne $ExpectedSha256 -or $active.versionCode -ne $ExpectedVersion -or
   $active.path -cne "/data/local/d31-remote/releases/$ExpectedSha256/remote.apk"){throw '活动版本不符'}
$health=(Read-Device 'cat /data/local/d31-remote/runtime/state/health.json') | ConvertFrom-Json
if($health.apk_sha256 -cne $ExpectedSha256 -or $health.version_code -ne $ExpectedVersion -or -not $health.local_ready){throw '核心健康不符'}
$corePid=Read-Device 'cat /data/local/d31-remote/runtime/state/remote.pid'
if($corePid -notmatch '^[1-9][0-9]*$'){throw '核心PID不符'}
$deviceNow=[long](Read-Device 'date +%s')*1000
$healthAge=$deviceNow-[long]$health.time_ms
if([long]$health.pid -ne [long]$corePid -or $healthAge -lt -1000 -or $healthAge -ge 20000){throw '当前核心PID或健康新鲜度不符'}
$coreMaps=Read-Device "cat /proc/$corePid/maps"
$coreMapped=$false
$expectedArt='/data/dalvik-cache/arm64/data@local@d31-remote@releases@'+$ExpectedSha256+'@remote.apk@classes.dex'
foreach($line in $coreMaps -split "`n"){
    $columns=$line.Trim() -split '\s+',6
    if($columns.Count -eq 6 -and $columns[5] -ceq $expectedArt){$coreMapped=$true}
}
$installed=(Read-Device 'pm path net.elfradio.d31bootstrap') -creplace '^package:',''
if($installed -cnotmatch '^/data/app/net\.elfradio\.d31bootstrap-[0-9]+/base\.apk$' -and
   $installed -cne '/system/priv-app/D31ElfRemote/D31ElfRemote.apk'){throw '实际APP安装路径不符'}
$hash=Read-Device "busybox sha256sum '$installed'"
if($hash -cnotmatch ('^'+$ExpectedSha256+'\s+'+[regex]::Escape($installed)+'$')){throw '实际APP安装摘要不符'}
$package=Read-Device 'dumpsys package net.elfradio.d31bootstrap'
# 原系统APK保留，不能把Hidden system packages中的旧版本当当前安装版本。
$currentPackage=($package -split '(?m)^\s*Hidden system packages:',2)[0]
$versions=[regex]::Matches($currentPackage,'(?m)^\s*versionCode=([0-9]+)(?:\s|$)')
if($versions.Count -ne 1 -or [int]$versions[0].Groups[1].Value -ne $ExpectedVersion){throw '实际APP安装版本不符'}
$appPidCommand='busybox pidof net.elfradio.d31bootstrap; code=$?; test "$code" -eq 0 -o "$code" -eq 1'
$appPid=Read-Device $appPidCommand
if($appPid -and $appPid -notmatch '^[1-9][0-9]*$'){throw 'APP进程不是唯一实例'}
$appMaps='';$appThreads='';$appMapped=$null
if($appPid){
    $appMaps=Read-Device "cat /proc/$appPid/maps"
    $appThreads=Read-Device ('for f in /proc/'+$appPid+'/task/*/comm; do cat "$f" || exit 1; done')
    $appMapped=($appMaps -cmatch ('(?m)\s'+[regex]::Escape($installed)+'\s*$'))
}
$services=Read-Device 'dumpsys activity services net.elfradio.d31bootstrap'
$log=Read-Device 'logcat -d -t 1200'
$sameBoot=(Read-Device 'cat /proc/sys/kernel/random/boot_id') -ceq $boot
$sameCorePid=(Read-Device 'cat /data/local/d31-remote/runtime/state/remote.pid') -ceq $corePid
$sameAppPid=(Read-Device $appPidCommand) -ceq $appPid
$sameInstalled=(Read-Device 'pm path net.elfradio.d31bootstrap') -ceq ('package:'+$installed)
$result=[ordered]@{
    version=$ExpectedVersion;coreActiveArtMapped=$coreMapped
    appProcessPresent=[bool]$appPid;appApkMapped=$appMapped
    installedApkHashMatched=$true;installedVersionMatched=$true
    sameBoot=$sameBoot;sameCorePid=$sameCorePid;coreHealthFresh=$true;sameAppPid=$sameAppPid;sameInstalledPath=$sameInstalled
    servicesEmpty=($services -match '\(nothing\)')
    mediaThreadsPresent=($appThreads -match '(?m)^d31-(audio-occup|app-media|local-audio)')
    matchedFaults=[regex]::Matches($log,'FATAL EXCEPTION|ANR in (?:net\.elfradio\.d31bootstrap|com\.[^\s]*nexui)','IgnoreCase').Count
    logScope='LAST_1200_ENTRIES_NOT_FULL_HISTORY'
    scope='SEQUENTIAL_RUNTIME_OBSERVATIONS_NOT_ATOMIC_OR_LONG_TERM'
}
$result | ConvertTo-Json | Out-File "$capture/result.json" -NoClobber
if(-not $result.coreActiveArtMapped -or ($result.appProcessPresent -and -not $result.appApkMapped) -or -not $result.servicesEmpty -or
   $result.mediaThreadsPresent -or -not $sameBoot -or -not $sameCorePid -or -not $sameAppPid -or -not $sameInstalled){
    throw '实际映射或按需线程退出未通过，保留现场'
}
$result | ConvertTo-Json
