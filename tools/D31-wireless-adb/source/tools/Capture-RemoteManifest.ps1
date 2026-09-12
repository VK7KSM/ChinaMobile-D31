#requires -Version 7.0
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$')][string]$Serial,
    [Parameter(Mandatory=$true)][int]$ExpectedVersion,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedSha256,
    [Parameter(Mandatory=$true)][ValidateSet('/data/local/d31-recovery-entry','/data/local/d31-system-support')][string]$Scope,
    [Parameter(Mandatory=$true)][string]$CapturePath
)
$ErrorActionPreference='Stop'
$address,$port=$Serial.Split(':')
if(@($address.Split('.') | Where-Object {[int]$_ -gt 255}).Count -or [int]$port -lt 1 -or [int]$port -gt 65535){throw '设备地址无效'}
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '捕获目录必须全新'}
New-Item -ItemType Directory -Path $capture | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination "$capture/host.ps1"
function Quote-Sh([string]$Value){return "'"+$Value.Replace("'", "'"+'\'+"''")+"'"}
function Read-Device([string]$Name,[string]$Command){
    $marker='D31_MANIFEST_'+[Guid]::NewGuid().ToString('N')
    $record=[ordered]@{time=[DateTimeOffset]::Now.ToString('o');serial=$Serial;command=$Command;completed=$false}
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName='C:/Dev/android-sdk/platform-tools/adb.exe';$start.UseShellExecute=$false;$start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    foreach($arg in @('-P','5042','-s',$Serial,'shell',"( $Command`n); result=`$?; echo; echo $($marker)_`$result")){$start.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start;$stdout=$null;$stderr=$null
    try{
        if(-not $process.Start()){throw 'ADB客户端未启动'}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit(40000)){$process.Kill();[void]$process.WaitForExit(5000);throw '清单调用超时，保留设备报告，不重试'}
        if(-not $stdout.Wait(5000) -or -not $stderr.Wait(5000)){throw '输出不完整'}
        $record.hostExitCode=$process.ExitCode
        $raw=$stdout.Result.Replace("`r",'').TrimEnd()
        if($process.ExitCode -ne 0 -or [regex]::Matches($raw,'(?m)^'+$marker+'_[0-9]+$').Count -ne 1 -or
            $raw -notmatch ('(?s)^(.*?)\n'+$marker+'_([0-9]+)$')){throw '远端退出标记缺失'}
        $record.deviceExitCode=[int]$Matches[2];$body=$Matches[1].Trim()
        if($record.deviceExitCode -ne 0){throw '远端清单命令失败'}
        $record.completed=$true;return $body
    }finally{
        if($null -ne $stdout -and $stdout.IsCompletedSuccessfully){$stdout.Result | Out-File "$capture/$Name-stdout-private.txt" -NoNewline -NoClobber}
        if($null -ne $stderr -and $stderr.IsCompletedSuccessfully){$stderr.Result | Out-File "$capture/$Name-stderr-private.txt" -NoNewline -NoClobber}
        $record | ConvertTo-Json | Out-File "$capture/$Name-command-private.json" -NoClobber
        $process.Dispose()
    }
}
$device=Read-Device 'identity' 'id -u; getprop ro.product.device; getprop ro.product.model; getprop ro.build.version.sdk'
if(($device.Split("`n") -join ',') -cne '0,hct6735_66_m0,hct6737t_66_m0,23'){throw '目标非D31/API23/root'}
$build=Read-Device 'build' 'getprop ro.build.fingerprint'
$active=Read-Device 'active' 'cat /data/local/d31-remote/runtime/active.json' | ConvertFrom-Json
if($active.versionCode -ne $ExpectedVersion -or $active.sha256 -cne $ExpectedSha256 -or
    $active.path -cne "/data/local/d31-remote/releases/$ExpectedSha256/remote.apk"){throw '活动身份不符'}
$actual=Read-Device 'apk-digest' ('busybox sha256sum '+(Quote-Sh $active.path))
if($actual.Split(' ')[0] -cne $ExpectedSha256){throw '活动文件摘要不符'}
$context=@{model='D31';hardwareClass='NOT_CHECKED';firmwareFamily='D31-factory';stage='RUNNING';network='NOT_CHECKED';sim='NOT_CHECKED';storage='NOT_CHECKED'}
$identity=@{snapshotId='b10-'+[Guid]::NewGuid().ToString('N');baselineId='d31-b10-unapproved';baselineRevision='0';firmwareId='D31-factory-1.4.3';build=$build;context=$context}
$request=@{operation='manifest';scope=$Scope;identity=$identity} | ConvertTo-Json -Depth 6 -Compress
$task=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N')
$command='CLASSPATH='+(Quote-Sh $active.path)+' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteDiagnosticCommand '+$task+' '+(Quote-Sh $request)
$receipt=Read-Device 'collect' $command | ConvertFrom-Json
if($receipt.state -ne 'completed' -or $receipt.path -cne "/data/local/d31-remote/diagnostics/$task/report.json" -or $receipt.sha256 -notmatch '^[a-f0-9]{64}$'){throw '回执路径或摘要无效'}
& 'C:/Dev/android-sdk/platform-tools/adb.exe' -P 5042 -s $Serial pull $receipt.path "$capture/report-private.json" *> "$capture/pull.txt"
if($LASTEXITCODE -ne 0 -or (Get-FileHash "$capture/report-private.json").Hash -ine $receipt.sha256){throw '报告原件校验失败'}
$report=Get-Content "$capture/report-private.json" -Raw | ConvertFrom-Json
$after=Read-Device 'active-after' 'cat /data/local/d31-remote/runtime/active.json' | ConvertFrom-Json
if($after.sha256 -cne $ExpectedSha256){throw '采集期间活动版本变化，不能用于本版本核验'}
$result=[ordered]@{collected=$true;version=$ExpectedVersion;scope=$Scope;entries=$report.manifest.entries.Count;collectionState=$report.index.state;systemConsistency='NOT_ASSESSED';targetFilesChanged=$false;privateDiagnosticReportCreated=$true}
$result | ConvertTo-Json | Out-File "$capture/result.json" -NoClobber
$result | ConvertTo-Json
