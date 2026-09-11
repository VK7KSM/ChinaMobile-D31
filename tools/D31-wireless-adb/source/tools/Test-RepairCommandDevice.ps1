#requires -Version 7.2
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9.]+:[0-9]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedApkSha256,
    [Parameter(Mandatory=$true)][ValidateRange(96,2147483647)][int]$ExpectedVersion,
    [Parameter(Mandatory=$true)][string]$CheckJar,
    [Parameter(Mandatory=$true)][string]$CapturePath,
    [string]$AdbPath='C:/Dev/android-sdk/platform-tools/adb.exe'
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
Set-StrictMode -Version Latest
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '捕获目录必须全新，禁止覆盖旧验收'}
foreach($file in @($ApkPath,$CheckJar,$AdbPath)){if(!(Test-Path -LiteralPath $file -PathType Leaf)){throw '缺少明确本地输入文件'}}
if((Get-FileHash -LiteralPath $ApkPath).Hash -ine $ExpectedApkSha256){throw '最终APK摘要不符'}
$jarHash=(Get-FileHash -LiteralPath $CheckJar).Hash.ToLowerInvariant()
$nonce=[guid]::NewGuid().ToString('N')
$task='reject-'+$nonce
$windowsId=[guid]::NewGuid().ToString('N')
$wrongId=[guid]::NewGuid().ToString('N')
$stage='/data/local/tmp/d31-repair-acceptance-'+$nonce
$apk='/data/local/d31-remote/releases/'+$ExpectedApkSha256+'/remote.apk'
$target='/data/local/d31-system-support/start.sh'
$sentinel='D31_ACCEPT_EXIT_'+$nonce+'_'
New-Item -ItemType Directory -Path $capture | Out-Null

function Save-Text([string]$Name,[string]$Text){
    $file=Join-Path $capture $Name
    $stream=[IO.File]::Open($file,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
    try {$bytes=[Text.UTF8Encoding]::new($false).GetBytes($Text);$stream.Write($bytes,0,$bytes.Length)} finally {$stream.Dispose()}
}
function Save-Json([string]$Name,$Value){Save-Text $Name ($Value | ConvertTo-Json -Depth 40)}
function Quote-Sh([string]$Value){return "'"+$Value.Replace("'","'\''")+"'"}
function Invoke-Device([string]$Name,[string]$Command,[int]$Expected=0){
    Save-Text "$Name-command-private.txt" $Command
    $wrapped=$Command+"`n"+'code=$?; printf ''\n'+$sentinel+'%s\n'' "$code"'
    $raw=(& $AdbPath -P 5042 -s $Serial shell $wrapped 2>&1 | Out-String).Replace("`r",'')
    $hostExit=$LASTEXITCODE
    Save-Text "$Name-output-private.txt" $raw
    if($hostExit -ne 0){throw "ADB命令未确认，不自动重发：$Name"}
    $match=[regex]::Match($raw,'(?s)^(.*?)\n'+[regex]::Escape($sentinel)+'([0-9]+)\s*$')
    if(!$match.Success){throw "缺少设备退出码：$Name"}
    $code=[int]$match.Groups[2].Value
    Save-Json "$Name-exit.json" @{host_exit=$hostExit;device_exit=$code;expected=$Expected}
    if($code -ne $Expected){throw "设备退出码不符：$Name，期望$Expected，实得$code"}
    return $match.Groups[1].Value.Trim()
}
function Helper([string]$Name,[string]$Operation,[string]$TaskId=''){
    $command='CLASSPATH='+(Quote-Sh ($apk+':'+$stage+'/check.jar'))+' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RepairCommandAcceptanceMain '+$Operation+' '+(Quote-Sh $ExpectedApkSha256)+' '+$ExpectedVersion
    if($TaskId){$command+=' '+(Quote-Sh $TaskId)}
    return (Invoke-Device $Name $command) | ConvertFrom-Json
}
function Repair([string]$Name,$Request,[int]$Expected=0){
    $json=$Request | ConvertTo-Json -Depth 40 -Compress
    Save-Json "$Name-request.json" $Request
    $raw=Invoke-Device $Name ('CLASSPATH='+(Quote-Sh $apk)+' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteRepairCommand '+(Quote-Sh $json)) $Expected
    if($Expected -eq 0){return $raw | ConvertFrom-Json}
    return $raw
}
function Windows([string]$Name,[string]$Operation,[string]$Id,[int]$Expected=0){
    $raw=Invoke-Device $Name ('CLASSPATH='+(Quote-Sh $apk)+' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteWindowsMaintenance '+$Operation+' '+(Quote-Sh $Id)) $Expected
    if($Expected -eq 0){
        $marker=if($Operation -eq 'reserve'){'D31_WINDOWS_RESERVED_V1'}else{'D31_WINDOWS_RELEASED_V1'}
        if($raw -cne $marker){throw "Windows维护回执不符：$Name"}
    }
}
function Assert-Original($Snapshot){
    foreach($field in @('path','sha256','bytes','device','inode','uid','gid','mode','modified','changed','selinux')){
        if([string]$Snapshot.target.$field -cne [string]$baseline.target.$field){throw "生产原件发生变化：$field"}
    }
}
function Assert-Receipt($Receipt,[string]$Phase){
    if($Receipt.task_id -cne $task -or $Receipt.plan_sha256 -cne $plan.submit.plan_sha256 -or
        $Receipt.state.phase -cne $Phase -or $Receipt.runtime_effect -cne 'NOT_CHECKED'){
        throw "修复回执合同不符，期望$Phase"
    }
}
function Assert-Rejection($Receipt){
    Assert-Receipt $Receipt 'REJECTED'
    $observations=@($Receipt.state.observations | Where-Object {
        $_.path -ceq 'system-support/start.sh' -and $_.kind -ceq 'REGULAR' -and $_.sha256 -ceq $baseline.target.sha256
    })
    if($Receipt.state.reason -cne 'PRECHECK_FAILED' -or $Receipt.state.attempted -ne -1 -or $observations.Count -ne 1){
        throw '没有证明实际原像hash不匹配导致前检拒绝'
    }
    if($observations[0].sha256 -ceq $plan.submit.plan.changes[0].original_sha256){throw '未命中TARGET_MISMATCH'}
}
function Pull-File([string]$Name,[string]$Remote){
    & $AdbPath -P 5042 -s $Serial pull $Remote (Join-Path $capture $Name) *> (Join-Path $capture "$Name-pull.log")
    if($LASTEXITCODE -ne 0){throw "原件归档失败：$Name"}
}

Save-Text 'host.ps1' ([IO.File]::ReadAllText($PSCommandPath))
Save-Json 'inputs-private.json' @{serial=$Serial;apk_sha256=$ExpectedApkSha256;expected_version=$ExpectedVersion;jar_sha256=$jarHash;task_id=$task;windows_id=$windowsId;wrong_id=$wrongId;time=[DateTimeOffset]::Now.ToString('o')}
$plan=$null;$baseline=$null;$windowsMayBeOwned=$false;$repairMayBeOwned=$false;$rejected=$false
$holder=$null;$holderError=$null;$holderLines=[Collections.Generic.List[string]]::new()
$passed=$false;$failure=$null;$cleanupErrors=[Collections.Generic.List[string]]::new()
try {
    [void](Invoke-Device 'preflight' 'test "$(id -u)" = 0 && test "$(getprop ro.product.device)" = hct6735_66_m0 && test "$(getprop ro.product.model)" = hct6737t_66_m0 && test "$(getprop ro.build.version.sdk)" = 23')
    $remoteHash=Invoke-Device 'apk-hash' ('/system/bin/busybox sha256sum '+(Quote-Sh $apk))
    if(($remoteHash -split '\s+')[0] -cne $ExpectedApkSha256){throw '设备冻结APK与最终候选不一致'}
    [void](Invoke-Device 'stage' ('umask 077; mkdir '+(Quote-Sh $stage)))
    & $AdbPath -P 5042 -s $Serial push $CheckJar "$stage/check.jar" *> (Join-Path $capture 'push-check.log')
    if($LASTEXITCODE -ne 0){throw '独立验收入口暂存失败'}
    $remoteJar=Invoke-Device 'jar-hash' ('/system/bin/busybox sha256sum '+(Quote-Sh "$stage/check.jar"))
    if(($remoteJar -split '\s+')[0] -cne $jarHash){throw '独立入口摘要不符'}
    $plan=Helper 'plan' 'plan' $task
    $baseline=$plan.baseline
    Save-Json 'plan-private.json' $plan
    if($baseline.repair -ne $null -or $baseline.windows_reserved){throw '已有维护预留，未开始验收'}
    if($plan.apk -cne $apk -or $baseline.apk.versionCode -ne $ExpectedVersion -or $plan.submit.plan.task_id -cne $task -or
        $plan.submit.plan.changes.Count -ne 1 -or $plan.submit.plan.changes[0].path -cne 'system-support/start.sh' -or
        $plan.submit.plan.changes[0].original_sha256 -ceq $baseline.target.sha256){throw '实际RepairPlan拒绝方案不符'}
    Pull-File 'before-start.sh' $target
    if((Get-FileHash (Join-Path $capture 'before-start.sh')).Hash -ine $baseline.target.sha256){throw '归档原件与快照不符'}
    Save-Json 'preregister-private.json' @{目标='明确版本完整D31';版本=$ExpectedVersion;变量='真实命令拒绝及维护互斥';任务=$task;计划摘要=$plan.submit.plan_sha256;目标文件=$target;预期='只执行一次前检命中TARGET_MISMATCH并REJECTED，目标内容及元数据保持';恢复='仅释放本测试Windows预留，不删除事务记录';APK=$apk;时间=[DateTimeOffset]::Now.ToString('o')}
    $inputRoot='/data/local/d31-remote/repair-input'
    [void](Invoke-Device 'prepare-empty-input' ('umask 077; test ! -e '+(Quote-Sh "$inputRoot/$task")+' && mkdir -p '+(Quote-Sh $inputRoot)+' && mkdir '+(Quote-Sh "$inputRoot/$task")+' && mkdir '+(Quote-Sh "$inputRoot/$task/artifacts")))

    $windowsMayBeOwned=$true
    Windows 'windows-reserve' 'reserve' $windowsId
    Windows 'windows-reserve-same' 'reserve' $windowsId
    Windows 'windows-reserve-wrong' 'reserve' $wrongId 1
    Windows 'windows-release-wrong' 'release' $wrongId 1
    $reserved=Helper 'windows-still-owned' 'snapshot';Assert-Original $reserved
    if(!$reserved.windows_reserved -or $reserved.windows_id -cne $windowsId){throw '错误id改变了Windows预留'}
    $blocked=Repair 'repair-blocked-by-windows' $plan.submit 1
    if(!$blocked.Contains('Windows刷机交接尚未结束')){throw 'repair不是因Windows预留被拒绝'}
    [void](Repair 'blocked-submit-not-created' $plan.query 1)
    [void](Invoke-Device 'blocked-task-directory-absent' ('test ! -e '+(Quote-Sh "/data/local/d31-remote/repairs/$task")))
    Windows 'windows-release' 'release' $windowsId
    $windowsMayBeOwned=$false
    $released=Helper 'windows-released' 'snapshot';Assert-Original $released
    if($released.windows_reserved -or $released.repair -ne $null){throw 'Windows释放后仍有非预期预留'}

    $repairMayBeOwned=$true
    $submitted=Repair 'repair-submit' $plan.submit;Assert-Receipt $submitted 'PENDING'
    $pending=Helper 'repair-reserved' 'snapshot';Assert-Original $pending
    if($pending.repair.task_id -cne $task -or $pending.repair.plan_sha256 -cne $plan.submit.plan_sha256){throw '修复未预留本任务'}
    Windows 'windows-blocked-by-repair' 'reserve' $wrongId 1

    $holdCommand='CLASSPATH='+(Quote-Sh ($apk+':'+$stage+'/check.jar'))+' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RepairCommandAcceptanceMain hold '+(Quote-Sh $ExpectedApkSha256)+' '+$ExpectedVersion
    Save-Text 'hold-command-private.txt' $holdCommand
    $start=[Diagnostics.ProcessStartInfo]::new();$start.FileName=[IO.Path]::GetFullPath($AdbPath)
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    foreach($argument in @('-P','5042','-s',$Serial,'shell',$holdCommand)){$start.ArgumentList.Add($argument)}
    $holder=[Diagnostics.Process]::new();$holder.StartInfo=$start
    if(!$holder.Start()){throw '无法启动独立有界持锁入口'}
    $holderError=$holder.StandardError.ReadToEndAsync()
    $ready=$false;$watch=[Diagnostics.Stopwatch]::StartNew()
    while($watch.Elapsed.TotalSeconds -lt 25){
        $lineTask=$holder.StandardOutput.ReadLineAsync()
        if(!$lineTask.Wait(25000)){throw '持锁就绪等待超时'}
        $line=$lineTask.Result
        if($null -eq $line){break}
        $holderLines.Add($line)
        if($line -ceq 'D31_REPAIR_LOCK_HELD_V1'){$ready=$true;break}
    }
    if(!$ready){throw '真实维护锁未确认取得'}
    $blocked=Repair 'repair-blocked-by-lock' $plan.step 1
    if(!$blocked.Contains('维护正在进行')){throw 'repair不是因真实维护锁被拒绝'}
    $during=Repair 'query-during-lock' $plan.query;Assert-Receipt $during 'PENDING'
    if($during.next_event -ne $submitted.next_event){throw '持锁期间查询或失败step推进了日志'}
    if(!$holder.WaitForExit(30000)){throw '有界维护锁入口未退出'}
    $holderLines.Add($holder.StandardOutput.ReadToEnd())
    if($holder.ExitCode -ne 0 -or !($holderLines -join "`n").Contains('D31_REPAIR_LOCK_RELEASED_V1')){throw '维护锁未确认正常释放'}
    Save-Text 'hold-stdout.txt' ($holderLines -join "`n")
    Save-Text 'hold-stderr.txt' ($holderError.GetAwaiter().GetResult())
    $holder.Dispose();$holder=$null

    Assert-Original (Helper 'before-reject' 'snapshot')
    $result=Repair 'repair-precheck' $plan.step;Assert-Rejection $result
    $rejected=$true
    $query=Repair 'repair-query' $plan.query;Assert-Rejection $query
    $after=Helper 'after-reject' 'snapshot';Assert-Original $after
    if($after.repair -ne $null -or $after.windows_reserved){throw 'REJECTED后维护预留未释放'}
    [void](Invoke-Device 'no-repair-artifacts' ('test ! -e '+(Quote-Sh "/data/local/d31-remote/repairs/$task/backup")+' && test ! -e '+(Quote-Sh "/data/local/d31-remote/repairs/$task/stage")))
    $windowsMayBeOwned=$true
    Windows 'windows-after-reject' 'reserve' $windowsId
    Windows 'windows-final-release' 'release' $windowsId
    $windowsMayBeOwned=$false
    $final=Helper 'final-snapshot' 'snapshot';Assert-Original $final
    if($final.repair -ne $null -or $final.windows_reserved){throw '验收结束仍有维护预留'}
    Pull-File 'after-start.sh' $target
    if((Get-FileHash (Join-Path $capture 'after-start.sh')).Hash -ine (Get-FileHash (Join-Path $capture 'before-start.sh')).Hash){throw '前后原件不一致'}
    Save-Json 'web-query.json' @{apk=$apk;request=$plan.query;expected_phase='REJECTED'}
    Save-Json 'result.json' @{passed=$true;expected_version=$ExpectedVersion;phase=$result.state.phase;precheck_gate='TARGET_MISMATCH';task_id=$task;plan_sha256=$plan.submit.plan_sha256;original_unchanged=$true;repair_reservation_released=$true;windows_wrong_id_rejected=$true;repair_blocked_by_windows=$true;repair_blocked_by_lock=$true;query_during_lock=$true;runtime_effect='NOT_CHECKED';web_executed=$false}
    $passed=$true
} catch {
    $failure=$_
    Save-Json 'failure.json' @{passed=$false;reason=$_.Exception.Message;task_id=$task;rejected=$rejected}
} finally {
    if($null -ne $holder){
        try {
            if(!$holder.WaitForExit(30000)){throw '独立持锁入口退出未确认'}
            $holderLines.Add($holder.StandardOutput.ReadToEnd())
            Save-Text 'hold-finally-stdout.txt' ($holderLines -join "`n")
            if($null -ne $holderError){Save-Text 'hold-finally-stderr.txt' ($holderError.GetAwaiter().GetResult())}
        } catch {$cleanupErrors.Add($_.Exception.Message)} finally {$holder.Dispose()}
    }
    if($windowsMayBeOwned){try {Windows 'cleanup-own-windows' 'release' $windowsId} catch {$cleanupErrors.Add($_.Exception.Message)}}
    # 只收尾本次仍在PENDING的拒绝方案，最多一次前检；不跨入备份或切换。
    if($repairMayBeOwned -and !$rejected -and $null -ne $plan){
        try {
            $state=Repair 'cleanup-query' $plan.query
            if($state.state.phase -ceq 'PENDING'){
                Assert-Original (Helper 'cleanup-original' 'snapshot')
                Assert-Rejection (Repair 'cleanup-precheck-only' $plan.step)
            }
        } catch {$cleanupErrors.Add($_.Exception.Message)}
    }
    Save-Json 'cleanup.json' @{errors=$cleanupErrors.ToArray();passed=$passed}
    # 先冻结文件列表再创建清单，清单不包含自身。
    $files=@(Get-ChildItem -LiteralPath $capture -File -Recurse | Where-Object Name -ne 'artifacts-private.json')
    $manifest=@($files | ForEach-Object {[ordered]@{path=[IO.Path]::GetRelativePath($capture,$_.FullName);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
    Save-Json 'artifacts-private.json' $manifest
}
if($null -ne $failure){throw $failure}
if($cleanupErrors.Count -ne 0){throw '清理结果未确认，查看cleanup.json'}
Write-Output '生产原像不匹配拒绝、原件保持及维护互斥验收通过；web-query.json仅供后续只读Web查询。'
