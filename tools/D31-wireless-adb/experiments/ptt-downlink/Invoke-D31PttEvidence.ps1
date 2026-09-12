#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Plan','Before','During','After')][string]$Phase='Plan',
    [string]$Serial,
    [string]$Adb='C:/Dev/android-sdk/platform-tools/adb.exe',
    [string]$CaptureRoot=(Join-Path $PSScriptRoot 'daytime-captures'),
    [switch]$Execute
)
$ErrorActionPreference='Stop'
if($Phase -eq 'Plan' -or !$Execute){
    Write-Output '仅显示计划；未连接设备。目标D31，电脑ADB服务器固定5042，不接触其它ADB服务器。'
    Write-Output '白天主线授权并完成预登记后，显式指定 -Serial IPv4:端口，分别用 -Phase Before/During/After -Execute 保全原件。'
    Write-Output '本脚本无安装、设置、播放、录音、连接服务器、开启能力或恢复路由命令。'
    Write-Output 'During必须由已审核的本地静音入口触发后调用；当时142未提供这个入口，执行前须重新核对当前版本，不能套用未知入口。'
    return
}
if([string]::IsNullOrWhiteSpace($Serial)){throw '执行读取必须显式指定 -Serial IPv4:端口；没有默认设备或端口。'}
if($Serial -cnotmatch '\A((?:[0-9]{1,3}\.){3}[0-9]{1,3}):([0-9]{1,5})\z'){
    throw 'Serial必须为完整IPv4:端口。'
}
$addressParts=$Matches[1].Split('.')
$devicePort=[int]$Matches[2]
if(($addressParts | Where-Object { [int]$_ -gt 255 -or ($_.Length -gt 1 -and $_.StartsWith('0')) }).Count -gt 0 -or $devicePort -lt 1 -or $devicePort -gt 65535){
    throw 'Serial必须包含有效IPv4地址及1到65535的端口。'
}
if(!(Test-Path -LiteralPath $Adb -PathType Leaf)){throw '找不到明确指定的ADB客户端。'}
$capture=Join-Path $CaptureRoot ((Get-Date -Format 'yyyyMMdd-HHmmss-fff')+'-'+$Phase)
New-Item -ItemType Directory -Path $capture | Out-Null
$records=[System.Collections.Generic.List[object]]::new()
function Read-AdbArtifact([string]$Name,[string[]]$Arguments){
    $stdout=Join-Path $capture ($Name+'.bin')
    $stderr=Join-Path $capture ($Name+'.stderr.bin')
    $info=[System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName=(Resolve-Path -LiteralPath $Adb).Path
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($arg in @('-P','5042','-s',$Serial,'exec-out')+$Arguments){$info.ArgumentList.Add($arg)}
    $process=[System.Diagnostics.Process]::new();$process.StartInfo=$info
    $out=[System.IO.File]::Open($stdout,[System.IO.FileMode]::CreateNew)
    $err=[System.IO.File]::Open($stderr,[System.IO.FileMode]::CreateNew)
    $began=[DateTimeOffset]::Now;$timedOut=$false;$exitCode=$null
    try{
        if(!$process.Start()){throw '启动ADB读取客户端失败'}
        $outCopy=$process.StandardOutput.BaseStream.CopyToAsync($out)
        $errCopy=$process.StandardError.BaseStream.CopyToAsync($err)
        if(!$process.WaitForExit(12000)){
            $timedOut=$true
            # 只结束本脚本的ADB读取客户端，不结束ADB服务器或机内服务。
            $process.Kill();$process.WaitForExit()
        }
        [System.Threading.Tasks.Task]::WaitAll(@($outCopy,$errCopy))
        $exitCode=$process.ExitCode
    }finally{
        $out.Dispose();$err.Dispose();$process.Dispose()
        $records.Add([pscustomobject]@{name=$Name;arguments=$Arguments;began=$began.ToString('o');ended=[DateTimeOffset]::Now.ToString('o');timeout=$timedOut;exit_code=$exitCode})
        $records | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $capture 'commands-private.json') -Encoding utf8
    }
    if($timedOut -or $exitCode -ne 0){throw "读取失败，已保存部分原件：$Name"}
    return $stdout
}
try{
    $platform=Read-AdbArtifact '01-device' @('getprop','ro.product.device')
    $sdk=Read-AdbArtifact '02-sdk' @('getprop','ro.build.version.sdk')
    if([IO.File]::ReadAllText($platform).Trim() -ne 'hct6735_66_m0' -or [IO.File]::ReadAllText($sdk).Trim() -ne '23'){
        throw '目标不符合D31已登记平台，停止后续读取。'
    }
    $null=Read-AdbArtifact '03-fingerprint' @('getprop','ro.build.fingerprint')
    $null=Read-AdbArtifact '04-reader-identity' @('id')
    $null=Read-AdbArtifact '05-audio-route-and-focus' @('dumpsys','audio')
    $null=Read-AdbArtifact '06-audio-flinger' @('dumpsys','media.audio_flinger')
    $null=Read-AdbArtifact '07-audio-policy' @('dumpsys','media.audio_policy')
    $null=Read-AdbArtifact '08-processes' @('ps')
    $null=Read-AdbArtifact '09-logcat' @('logcat','-d','-v','threadtime','-t','1200')
}finally{
    Get-ChildItem -LiteralPath $capture -File | Where-Object Name -ne 'artifacts-sha256.csv' | ForEach-Object {
        [pscustomobject]@{file=$_.Name;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash;modified=$_.LastWriteTimeUtc.ToString('o')}
    } | Export-Csv -LiteralPath (Join-Path $capture 'artifacts-sha256.csv') -NoTypeInformation -Encoding utf8
    Write-Output "本地私有原件目录：$capture"
}
