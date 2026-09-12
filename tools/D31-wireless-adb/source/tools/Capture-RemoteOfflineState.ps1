#requires -Version 7.0
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$CapturePath
)
$ErrorActionPreference='Stop'
$address,$port=$Serial.Split(':')
if(@($address.Split('.') | Where-Object {[int]$_ -gt 255}).Count -or [int]$port -lt 1 -or [int]$port -gt 65535){throw 'D31地址或端口无效'}
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '捕获目录必须全新'}
New-Item -ItemType Directory -Path $capture | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination "$capture/host.ps1"
$script:sequence=0
function Read-Device([string]$Name,[string]$Command){
    $script:sequence++
    $prefix=('{0:D2}-{1}' -f $script:sequence,$Name)
    $marker='D31_OFFLINE_'+[Guid]::NewGuid().ToString('N')
    $wrapped="( $Command`n); result=`$?; echo; echo $($marker)_`$result"
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName='C:/Dev/android-sdk/platform-tools/adb.exe'
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    foreach($arg in @('-P','5042','-s',$Serial,'shell',$wrapped)){$start.ArgumentList.Add($arg)}
    $record=[ordered]@{command=$Command;serial=$Serial;time=[DateTimeOffset]::Now.ToString('o');completed=$false}
    $record | ConvertTo-Json | Out-File "$capture/$prefix-request-private.json" -NoClobber
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start
    $stdout=$null;$stderr=$null
    try{
        if(-not $process.Start()){throw 'ADB客户端未启动'}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit(20000)){
            $process.Kill()
            if(-not $process.WaitForExit(5000)){throw '只读客户端未退出'}
            throw '只读请求超时，未停止ADB服务器'
        }
        if(-not $stdout.Wait(5000) -or -not $stderr.Wait(5000)){throw '输出未完整结束'}
        $raw=$stdout.Result.Replace("`r",'').TrimEnd()
        $record.hostExitCode=$process.ExitCode
        if($process.ExitCode -ne 0 -or [regex]::Matches($raw,'(?m)^'+$marker+'_[0-9]+$').Count -ne 1 -or
           $raw -notmatch ('(?s)^(.*?)\n'+$marker+'_([0-9]+)$')){throw '设备退出标记未确认'}
        $record.deviceExitCode=[int]$Matches[2];$body=$Matches[1].Trim()
        if($record.deviceExitCode -ne 0){throw '设备只读命令失败，原始输出已保留'}
        $record.completed=$true
        return $body
    }finally{
        if($null -ne $stdout -and $stdout.IsCompletedSuccessfully){$stdout.Result | Out-File "$capture/$prefix-stdout-private.txt" -Encoding utf8 -NoNewline -NoClobber}
        if($null -ne $stderr -and $stderr.IsCompletedSuccessfully){$stderr.Result | Out-File "$capture/$prefix-stderr-private.txt" -Encoding utf8 -NoNewline -NoClobber}
        $record | ConvertTo-Json | Out-File "$capture/$prefix-result-private.json" -NoClobber
        $process.Dispose()
    }
}
$identity=(Read-Device 'identity' 'id -u; getprop ro.product.device; getprop ro.product.model; getprop ro.build.version.sdk').Split("`n")
if(($identity -join ',') -cne '0,hct6735_66_m0,hct6737t_66_m0,23'){throw '目标不是已验证D31'}
[void](Read-Device 'build' 'date; getprop ro.build.fingerprint; cat /proc/sys/kernel/random/boot_id; cat /proc/uptime')
$active=Read-Device 'active' 'cat /data/local/d31-remote/runtime/active.json' | ConvertFrom-Json
$health=Read-Device 'health' 'cat /data/local/d31-remote/runtime/state/health.json' | ConvertFrom-Json
$work=Read-Device 'work' 'cat /data/local/d31-remote/runtime/state/work-status.json' | ConvertFrom-Json
$status=Read-Device 'status' 'cat /data/local/d31-remote/runtime/state/status.json' | ConvertFrom-Json
$faults=Read-Device 'faults' 'cat /data/local/d31-remote/faults/scan.json' | ConvertFrom-Json
[void](Read-Device 'processes' @'
ps
for name in d31-elfremote d31-remote-supervisor com.starnet.nexui net.elfradio.d31phone.debug; do
    for p in $(busybox pidof "$name"); do
        echo PROCESS:$name
        cat /proc/$p/stat || exit 9
        cat /proc/$p/status || exit 9
        ls /proc/$p/fd | busybox wc -l
    done
done
cat /proc/meminfo
'@)
[void](Read-Device 'pending-digests' @'
for f in /data/local/d31-remote/runtime/state/pending-report.json /data/local/d31-remote/runtime/state/receipts/*.json /data/local/d31-remote/runtime/state/incoming-files/*/state.json; do
    if [ -f "$f" ]; then busybox sha256sum "$f" || exit 9; fi
done
true
'@)
[void](Read-Device 'runtime-files' 'ls -l /data/local/d31-remote/runtime/state; ls -l /data/local/d31-remote/runtime/updates; cat /proc/mounts')
[void](Read-Device 'log-window' 'logcat -d -t 1200')
$summary=[ordered]@{version=$active.versionCode;localReady=$health.local_ready;healthVersion=$health.version_code;
    cloudPhase=$status.phase;cloudHttpStatus=$status.http_status;mqtt=$work.mqtt;stages=$work.stages;
    faultState=$faults.state;faultCollecting=$faults.collectingEvents;faultAwaitingArchive=$faults.awaitingArchiveEvents;
    retainedEvents=$faults.retainedEvents;retainedBytes=$faults.retainedBytes;
    cloudRequestsIssuedByHost=0;deviceSettingsChanged=$false;scope='单次顺序只读观察，非长期稳定性结论'}
$summary | ConvertTo-Json -Depth 6 | Out-File "$capture/result.json" -NoClobber
$summary | ConvertTo-Json -Depth 6
