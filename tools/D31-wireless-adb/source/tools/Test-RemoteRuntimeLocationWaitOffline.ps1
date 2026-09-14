#requires -Version 7.0
param(
    [string]$HostPath=(Join-Path $PSScriptRoot 'Verify-RemoteRuntime.ps1'),
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$out=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $out){throw '离线输出必须全新'}
$null=New-Item -ItemType Directory -Path $out
$HostPath=(Resolve-Path $HostPath).Path
$before=(Get-FileHash $HostPath).Hash
$source=[IO.File]::ReadAllText($HostPath)
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
if($errors.Count){throw '宿主语法失败'}
$read=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Read-Device'},$true))
if($read.Count -ne 1){throw '未找到唯一只读后端'}
$clock=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -ceq '$locationWatch'},$true))
if($clock.Count -gt 1){throw '时钟赋值不唯一'}
$edits=@(@{start=$read[0].Extent.StartOffset;end=$read[0].Extent.EndOffset;text='function Read-Device([string]$Command,[int]$TimeoutMilliseconds=20000){ & $global:RuntimeOfflineRead $Command $TimeoutMilliseconds }'})
if($clock.Count){$edits+=@{start=$clock[0].Extent.StartOffset;end=$clock[0].Extent.EndOffset;text='$global:RuntimeOfflineCase.started=$global:RuntimeOfflineCase.elapsed; $locationWatch=$global:RuntimeOfflineClock'}}
foreach($edit in ($edits | Sort-Object start -Descending)){$source=$source.Substring(0,$edit.start)+$edit.text+$source.Substring($edit.end)}
[IO.File]::WriteAllText("$out/instrumented-host.ps1",$source,[Text.UTF8Encoding]::new($false))
Copy-Item $HostPath "$out/host-input.ps1"
Copy-Item $PSCommandPath "$out/test-input.ps1"
$saved=@{}
foreach($name in @('RuntimeOfflineRead','RuntimeOfflineClock','RuntimeOfflineCase')){$saved[$name]=Get-Variable $name -Scope Global -ErrorAction SilentlyContinue}
$global:RuntimeOfflineClock=[pscustomobject]@{}
$global:RuntimeOfflineClock | Add-Member ScriptProperty ElapsedMilliseconds { $global:RuntimeOfflineCase.elapsed-$global:RuntimeOfflineCase.started }
# 仅替换当前测试作用域的睡眠，固定预算按虚拟单调时间完整走到边界。
function Start-Sleep([int]$Milliseconds){$global:RuntimeOfflineCase.elapsed+=$Milliseconds;$global:RuntimeOfflineCase.sleeps++}
$global:RuntimeOfflineRead={
    param([string]$Command,[int]$TimeoutMilliseconds)
    $m=$global:RuntimeOfflineCase
    $m.commands.Add([ordered]@{command=$Command;timeMs=$m.elapsed;timeoutMs=$TimeoutMilliseconds})
    $m.elapsed+=10
    $sha='a'*64;$installed='/data/app/net.elfradio.d31bootstrap-1/base.apk'
    $location='  * ServiceRecord{abc u0 net.elfradio.d31bootstrap/.telemetry.LocationCacheService}'
    $media='  * ServiceRecord{def u0 net.elfradio.d31bootstrap/.media.RemoteMediaService}'
    switch -Regex ($Command){
        '^getprop ro.product.device$' {return 'hct6735_66_m0'}
        '^getprop ro.product.model$' {return 'hct6737t_66_m0'}
        '^getprop ro.build.version.sdk$' {return '23'}
        '^date;' {return 'synthetic'}
        '^cat /proc/sys/kernel/random/boot_id$' {$m.bootReads++; return $(if($m.name -eq 'boot-changed' -and $m.bootReads -gt 1){'bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee'}else{'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'})}
        '^cat .*/active.json$' {return (@{sha256=$sha;versionCode=154;path="/data/local/d31-remote/releases/$sha/remote.apk"}|ConvertTo-Json -Compress)}
        '^cat .*/health.json$' {return (@{apk_sha256=$sha;version_code=154;local_ready=$true;pid=88;time_ms=$(if($m.name -eq 'stale-health'){900000}else{1000000})}|ConvertTo-Json -Compress)}
        '^cat .*/remote.pid$' {$m.coreReads++;return $(if($m.name -eq 'core-changed' -and $m.coreReads -gt 1){'89'}else{'88'})}
        '^date \+%s$' {return '1000'}
        '^cat /proc/88/maps$' {return "100-200 r--p 00000000 00:01 1 /data/dalvik-cache/arm64/data@local@d31-remote@releases@$sha@remote.apk@classes.dex$(if($m.name -eq 'core-map-wrong'){' (deleted)'})"}
        '^pm path ' {return "package:$installed"}
        '^busybox sha256sum ' {return "$(if($m.name -eq 'hash-wrong'){'b'*64}else{$sha})  $installed"}
        '^dumpsys package ' {return "Packages:`n versionCode=154 targetSdk=23`nHidden system packages:`n versionCode=85"}
        '^busybox pidof ' {
            if($m.name -eq 'pid-multiple' -and !$m.idle){return '99 100'}
            if($m.name -eq 'empty-no-app'){return ''}
            return '99'
        }
        '^cat /proc/99/maps$' {return "100-200 r--p 00000000 00:01 1 $(if($m.name -eq 'app-map-wrong'){'/wrong.apk'}else{$installed})"}
        '^for f in /proc/99/task/' {
            if($m.name -eq 'media-initial' -or ($m.name -eq 'media-later' -and $m.serviceReads -ge 2) -or ($m.name -eq 'media-after-exit' -and $m.idle)){return "main`nd31-app-media"}
            return "main`nd31-app-location-cache"
        }
        '^dumpsys activity services ' {
            $m.serviceReads++
            if($m.name.StartsWith('empty-')){$m.idle=$true;return '(nothing)'}
            if($m.name -eq 'media-service'){return $media}
            if($m.name -eq 'mixed-services'){return "$location`n$media"}
            if($m.name -eq 'spoofed-name'){return ($location -replace 'LocationCacheService','LocationCacheServiceExtra')}
            if($m.name -eq 'wrong-user'){return ($location -replace 'u0 ','u10 ')}
            if($m.name -eq 'duplicate-record'){return "$location`n$location"}
            if($m.name -eq 'mixed-nothing'){return "$location`n(nothing)"}
            if($m.name -eq 'unknown-output'){return 'Permission Denial'}
            if($m.name -eq 'transport-failure'){throw '合成只读传输失败'}
            if($m.name -eq 'other-during-wait' -and $m.serviceReads -gt 1){return $media}
            if($m.name -eq 'never-exits'){return $location}
            if($m.name -eq 'deadline-empty' -and $m.serviceReads -gt 1){$m.elapsed=$m.started+60000;return '(nothing)'}
            if($m.name -eq 'near-deadline' -and $m.serviceReads -eq 2){$m.elapsed=$m.started+59999;$m.idle=$true;return '(nothing)'}
            if($m.name -eq 'reappears' -and $m.idle){return $location}
            if($m.serviceReads -lt 3){return "ACTIVITY MANAGER SERVICES (dumpsys activity services)`n$location`n    createTime=-24s324ms startingBgTimeout=--`n    startRequested=true"}
            $m.idle=$true;return '(nothing)'
        }
        '^logcat ' {return ''}
        default {throw '未预登记的离线命令，禁止设备访问'}
    }
}
$names=@('empty-app','empty-no-app','location-exits','near-deadline','never-exits','deadline-empty','media-initial','media-later','media-after-exit',
    'media-service','mixed-services','spoofed-name','wrong-user','duplicate-record','mixed-nothing','unknown-output','transport-failure',
    'other-during-wait','pid-multiple','reappears','stale-health','core-map-wrong','app-map-wrong','hash-wrong','boot-changed','core-changed')
$results=[Collections.Generic.List[object]]::new()
try {
    foreach($name in $names){
        $m=@{name=$name;elapsed=0;started=0;sleeps=0;serviceReads=0;bootReads=0;coreReads=0;idle=$false;commands=[Collections.Generic.List[object]]::new()}
        $global:RuntimeOfflineCase=$m
        $accepted=$false;$failure=$null
        try{& "$out/instrumented-host.ps1" -Serial '192.0.2.1:5555' -ExpectedSha256 ('a'*64) -ExpectedVersion 154 -CapturePath "$out/$name" *> "$out/$name.txt"; $accepted=$true}catch{$failure=$_.Exception.Message}
        $expected=$name -in @('empty-app','empty-no-app','location-exits','near-deadline')
        $passed=$accepted -eq $expected
        $wait=if(Test-Path "$out/$name/location-wait.json"){Get-Content "$out/$name/location-wait.json" -Raw|ConvertFrom-Json}else{$null}
        if($clock.Count){
            $passed=$passed -and $null -ne $wait -and $wait.elapsedMs -le 60000
            if($name -in @('media-initial','media-service','mixed-services','spoofed-name','wrong-user','duplicate-record','mixed-nothing','unknown-output','pid-multiple')){$passed=$passed -and $m.sleeps -eq 0}
            if($name -eq 'never-exits'){$passed=$passed -and $wait.elapsedMs -eq 60000 -and $m.serviceReads -le 31}
            if($name -eq 'deadline-empty'){$passed=$passed -and $failure -ceq '定位服务自然退出等待超时'}
            if($expected){
                $result=Get-Content "$out/$name/result.json" -Raw|ConvertFrom-Json
                $passed=$passed -and $result.servicesEmpty -and !$result.mediaThreadsPresent
                # 证明健康、身份和映射均在等待结束之后新鲜读取，不是复用旧快照。
                foreach($pattern in @('active.json$','health.json$','remote.pid$','/maps$','^pm path ','^busybox sha256sum ','^dumpsys package ','^logcat ')){
                    $reads=@($m.commands | Where-Object command -Match $pattern)
                    $passed=$passed -and $reads.Count -gt 0 -and @($reads | Where-Object timeMs -LT ($m.started+$wait.elapsedMs)).Count -eq 0
                }
                $passed=$passed -and $m.serviceReads -eq $wait.polls+1
            }
            $waitCalls=@($m.commands | Where-Object { $_.timeMs -ge $m.started -and $_.timeMs -lt ($m.started+$wait.elapsedMs) })
            foreach($call in $waitCalls){$passed=$passed -and $call.timeoutMs -le [Math]::Min(20000,60000-($call.timeMs-$m.started))}
        }
        $m.commands.ToArray()|ConvertTo-Json -Depth 4|Out-File "$out/$name/commands.json" -NoClobber
        $results.Add([ordered]@{name=$name;passed=[bool]$passed;expectedAccepted=$expected;accepted=$accepted;reason=$failure;virtualElapsedMs=$m.elapsed;sleeps=$m.sleeps;serviceReads=$m.serviceReads})
    }
} finally {
    foreach($name in $saved.Keys){if($null -ne $saved[$name]){Set-Variable $name $saved[$name].Value -Scope Global}else{Remove-Variable $name -Scope Global -ErrorAction SilentlyContinue}}
}
$after=(Get-FileHash $HostPath).Hash
$failed=@($results|Where-Object passed -EQ $false)
$results.ToArray()|ConvertTo-Json -Depth 5|Out-File "$out/cases.json" -NoClobber
$summary=[ordered]@{tests=$results.Count;passed=$results.Count-$failed.Count;failed=$failed.Count;hostSha256=$before;inputUnchanged=$before -ceq $after;adbUsed=$false;productionRequests=0;realSleeps=0}
$summary|ConvertTo-Json|Out-File "$out/result.json" -NoClobber
$summary|ConvertTo-Json
if($failed.Count -or $before -cne $after){throw '运行态离线边界测试失败，查看原始记录'}
