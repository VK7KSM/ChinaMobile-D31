#requires -Version 7.0
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9.]+:[0-9]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$HelperJar,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$ActiveSha256,
    [Parameter(Mandatory=$true)][ValidatePattern('^net\.elfradio\.d31bootstrap\.[A-Za-z0-9_.]+$')][string]$EntryClass,
    [Parameter(Mandatory=$true)][string]$CapturePath,
    [ValidateSet('app_process','app_process32')][string]$AppProcess='app_process',
    [string[]]$Arguments = @(),
    [ValidateNotNullOrEmpty()][string]$ChangeDescription = '仅新增独立诊断JAR，不安装APK或修改系统设置；原件保留'
)
$ErrorActionPreference='Stop'
$adb='C:/Dev/android-sdk/platform-tools/adb.exe'
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '证据目录必须全新'}
if(-not(Test-Path -LiteralPath $HelperJar -PathType Leaf)){throw '缺少本轮只读诊断JAR'}
foreach($arg in $Arguments){if($arg -notmatch '^[A-Za-z0-9_.-]{1,96}$'){throw '诊断参数不在简单只读参数合同内'}}
$sha=(Get-FileHash -LiteralPath $HelperJar).Hash.ToLowerInvariant()
$remote='/data/local/tmp/d31-readcheck-'+[Guid]::NewGuid().ToString('N')+'.jar'
$apk='/data/local/d31-remote/releases/'+$ActiveSha256+'/remote.apk'
New-Item -ItemType Directory -Path $capture | Out-Null
Copy-Item -LiteralPath $HelperJar -Destination (Join-Path $capture 'helper.jar')
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $capture 'host.ps1')
$script:adbSequence=0
function Invoke-BoundedAdb([string[]]$AdbArguments,[int]$TimeoutSeconds=30){
    $script:adbSequence++
    $label='{0:D2}' -f $script:adbSequence
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$adb; $start.UseShellExecute=$false; $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
    foreach($item in @('-P','5042','-s',$Serial)+$AdbArguments){$start.ArgumentList.Add($item)}
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$start
    $timedOut=$false; $stdout=$null; $stderr=$null; $phase='start'; $exitCode=$null; $failure=$null
    try{
        if(-not $process.Start()){throw 'ADB宿主未启动'}
        $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync()
        $phase='wait'
        if(-not $process.WaitForExit($TimeoutSeconds*1000)){
            $timedOut=$true
            $phase='stop_client'
            $process.Kill()
            if(-not $process.WaitForExit(5000)){throw 'ADB宿主超时且未退出，未操作ADB服务器'}
        }
        $phase='drain_output'; $exitCode=$process.ExitCode
        if(-not $stdout.Wait(5000) -or -not $stderr.Wait(5000)){throw 'ADB输出未在期限内收尾'}
        if($timedOut -or $exitCode -ne 0){throw 'ADB宿主失败或超时，原始输出已保存；未重启ADB服务器'}
        $phase='completed'
        return [pscustomobject]@{Stdout=$stdout.Result;Stderr=$stderr.Result}
    } catch { $failure=$_.Exception.Message; throw }
    finally {
        $hasOut=$null -ne $stdout -and $stdout.IsCompletedSuccessfully
        $hasErr=$null -ne $stderr -and $stderr.IsCompletedSuccessfully
        if($hasOut){$stdout.Result | Out-File -LiteralPath (Join-Path $capture "$label-stdout-private.txt") -Encoding utf8 -NoClobber}
        if($hasErr){$stderr.Result | Out-File -LiteralPath (Join-Path $capture "$label-stderr-private.txt") -Encoding utf8 -NoClobber}
        [ordered]@{phase=$phase;failure=$failure;timeout=$timedOut;hostExit=$exitCode;timeoutSeconds=$TimeoutSeconds;
            stdoutComplete=$hasOut;stderrComplete=$hasErr;remoteProcessMayContinue=($phase -ne 'completed')} |
            ConvertTo-Json | Out-File -LiteralPath (Join-Path $capture "$label-adb-result.json") -Encoding utf8 -NoClobber
        $process.Dispose()
    }
}
function Read-Device([string]$Command){
    $marker='D31_CHECK_'+[Guid]::NewGuid().ToString('N')
    $wrapped=$Command+"`nresult=`$?; echo; echo $($marker)_`$result"
    $text=(Invoke-BoundedAdb -AdbArguments @('shell',$wrapped)).Stdout.Replace("`r",'').TrimEnd()
    if($text -notmatch ('(?s)^(.*?)\n'+$marker+'_0$')){throw '设备未返回本次唯一成功退出标记'}
    return $Matches[1].Trim()
}
if((Read-Device 'getprop ro.product.device') -ne 'hct6735_66_m0' -or (Read-Device 'id -u') -ne '0'){
    throw '设备或只读检查身份不符'
}
$active=(Read-Device 'cat /data/local/d31-remote/runtime/active.json') | ConvertFrom-Json
if($active.sha256 -cne $ActiveSha256){throw '活动核心与已验证摘要不符'}
$baseline=Read-Device 'date; getprop ro.build.fingerprint; getprop sys.boot_completed; cat /proc/uptime'
$baseline | Out-File -LiteralPath (Join-Path $capture 'baseline-private.txt') -Encoding utf8 -NoClobber
[void](Invoke-BoundedAdb -AdbArguments @('push',$HelperJar,$remote))
if(-not(Read-Device "busybox sha256sum '$remote'").StartsWith($sha)){throw '诊断JAR实际摘要不符'}
$command="CLASSPATH='$($remote):$apk' /system/bin/$AppProcess /system/bin $EntryClass " + ($Arguments -join ' ')
[ordered]@{time=[DateTimeOffset]::Now.ToString('o');serial=$Serial;command=$command;helperSha256=$sha;
    change=$ChangeDescription} |
    ConvertTo-Json | Out-File -LiteralPath (Join-Path $capture 'invocation-private.json') -Encoding utf8 -NoClobber
$output=Read-Device $command
$output | Out-File -LiteralPath (Join-Path $capture 'output-private.txt') -Encoding utf8 -NoClobber
[ordered]@{exitCode=0;deviceExitMarkerVerified=$true;helperSha256=$sha;activeSha256=$ActiveSha256} |
    ConvertTo-Json | Out-File -LiteralPath (Join-Path $capture 'result.json') -Encoding utf8 -NoClobber
'诊断完成，实际操作范围及原始输出已保存。'
