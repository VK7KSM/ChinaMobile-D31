param(
    [Parameter(Mandatory=$true)][string]$AdbPath,
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][uri]$ProbeUri,
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedSha256,
    [Parameter(Mandatory=$true)][string]$CapturePath,
    [string]$SystemScope='/data/local/d31-recovery-entry'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($Serial -notmatch '^[0-9.]+:[0-9]+$'){throw '仅接受明确D31局域网序列号'}
if($ProbeUri.Scheme -ne 'http' -or $ProbeUri.Host -ne $Serial.Split(':')[0] -or $ProbeUri.Port -ne 8765){throw '探针必须指向同一D31的8765端口'}
if($SystemScope -notmatch '^/(system|data/local/d31-recovery-entry)[A-Za-z0-9_./-]*$' -or $SystemScope.Contains('..')){throw '系统范围无效'}
if((Get-FileHash -LiteralPath $ApkPath).Hash -ine $ExpectedSha256){throw '候选APK摘要不符'}
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '捕获目录必须全新'}
New-Item -ItemType Directory -Path $capture | Out-Null
$run='diagnostic-'+[guid]::NewGuid().ToString('N')
$stage='/data/local/tmp/'+$run
$fixture='/data/local/d31-diagnostic-input/'+$run
$apk="$stage/candidate.apk"
function Quote-Sh([string]$Value){ return "'"+$Value.Replace("'", "'"+'\'+"''")+"'" }
function Invoke-Probe([string]$Name,[string]$Command){
    $id=$run+'-'+$Name
    $body=@{id=$id;command=$Command;timeout=120} | ConvertTo-Json -Compress
    $body | Out-File "$capture/$Name-request-private.json" -Encoding utf8 -NoClobber
    $result=Invoke-RestMethod -Uri ([uri]::new($ProbeUri,'/exec')) -Method Post -ContentType application/json -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 10
    $watch=[Diagnostics.Stopwatch]::StartNew()
    while($result.state -eq 'running' -and $watch.Elapsed.TotalSeconds -lt 125){
        Start-Sleep -Milliseconds 500
        $result=Invoke-RestMethod -Uri ([uri]::new($ProbeUri,"/jobs/$id")) -TimeoutSec 10
    }
    $result | ConvertTo-Json -Depth 20 | Out-File "$capture/$Name-result-private.json" -Encoding utf8 -NoClobber
    if($result.state -ne 'completed' -or $result.exit_code -ne 0){throw "设备任务失败，保留原始回执：$Name"}
    return $result
}
function Collect-Report([string]$Name,$Request,[string]$TaskId){
    $json=$Request | ConvertTo-Json -Depth 10 -Compress
    $command='CLASSPATH='+(Quote-Sh $apk)+' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteDiagnosticCommand '+(Quote-Sh $TaskId)+' '+(Quote-Sh $json)
    $outer=Invoke-Probe $Name $command
    $receipt=$outer.output.Trim() | ConvertFrom-Json
    if($receipt.state -ne 'completed' -or $receipt.path -cne "/data/local/d31-remote/diagnostics/$TaskId/report.json"){throw '诊断回执异常'}
    & $AdbPath -P 5042 -s $Serial pull $receipt.path "$capture/$Name-report-private.json" *> "$capture/$Name-pull.log"
    if($LASTEXITCODE -ne 0 -or (Get-FileHash "$capture/$Name-report-private.json").Hash -ine $receipt.sha256){throw '取回报告摘要不符'}
    return Get-Content "$capture/$Name-report-private.json" -Raw | ConvertFrom-Json
}
$health=Invoke-RestMethod -Uri ([uri]::new($ProbeUri,'/health')) -TimeoutSec 10
$health | ConvertTo-Json | Out-File "$capture/health-before.json" -Encoding utf8 -NoClobber
$pre=Invoke-Probe 'preflight' 'test "$(getprop ro.product.model)" = hct6737t_66_m0 && test "$(getprop ro.product.device)" = hct6735_66_m0 && test "$(getprop ro.build.version.sdk)" = 23 && test -x /system/bin/busybox && getprop ro.build.fingerprint'
$build=$pre.output.Trim()
$setup='umask 077; test ! -e '+(Quote-Sh $stage)+' && test ! -e '+(Quote-Sh $fixture)+' && mkdir -p '+(Quote-Sh $stage)+' '+(Quote-Sh $fixture)
$null=Invoke-Probe 'prepare' $setup
& $AdbPath -P 5042 -s $Serial push $ApkPath $apk *> "$capture/push.log"
if($LASTEXITCODE -ne 0){throw '候选载荷传输失败'}
$verify=Invoke-Probe 'verify-payload' ('/system/bin/busybox sha256sum '+(Quote-Sh $apk))
if($verify.output.Split(' ')[0] -ine $ExpectedSha256){throw '机内载荷摘要不符'}
$setup='printf %s fixture-evidence > '+(Quote-Sh "$fixture/sample.txt")+' && ln -s sample.txt '+(Quote-Sh "$fixture/sample-link")
$null=Invoke-Probe 'fixture' $setup
$context=@{model='D31';hardwareClass='SVP3390';firmwareFamily='D31-factory';stage='RUNNING';network='NOT_CHECKED';sim='NOT_CHECKED';storage='NOT_CHECKED'}
$identity=@{snapshotId=$run+'-fixture';baselineId='d31-batch2';baselineRevision='1';firmwareId='D31-factory-1.4.3';build=$build;context=$context}
$request=@{operation='manifest';scope=$fixture;identity=$identity}
$task=([guid]::NewGuid().ToString('N'))+([guid]::NewGuid().ToString('N'))
$manifest=Collect-Report 'fixture-manifest' $request $task
if($manifest.index.state -ne 'COMPLETE' -or $manifest.manifest.entries.Count -ne 3){throw '隔离清单不完整'}
$sample=@($manifest.manifest.entries | Where-Object path -EQ "$fixture/sample.txt")
$link=@($manifest.manifest.entries | Where-Object path -EQ "$fixture/sample-link")
if($sample.Count -ne 1 -or $sample[0].fields.sha256.value -ne '7f227db1653b6b723b07c8f2f6eb488f1f09e2f083ca7a3f5e02bbb274f5ff2e' -or
    $sample[0].fields.uid.value -ne 0 -or $sample[0].fields.mode.value -ne '0600' -or
    $link.Count -ne 1 -or $link[0].fields.type.value -ne 'symlink' -or
    $link[0].fields.link.value -ne 'sample.txt' -or $link[0].fields.sha256.state -ne 'NOT_APPLICABLE'){throw '真实文件属性或不跟随链接合同不符'}
$repeat=Collect-Report 'fixture-retry' $request $task
if((Get-FileHash "$capture/fixture-manifest-report-private.json").Hash -ne (Get-FileHash "$capture/fixture-retry-report-private.json").Hash){throw '同任务重试未复用原件'}
$task=([guid]::NewGuid().ToString('N'))+([guid]::NewGuid().ToString('N'))
$fault=Collect-Report 'fault-evidence' @{operation='fault_files';eventId=$run;sources=@(@{id='sample';category='CUSTOM';path="$fixture/sample.txt"})} $task
if($fault.items.Count -ne 1 -or $fault.items[0].state -ne 'COMPLETE' -or $fault.items[0].capturedBytes -ne 16){throw '合成故障证据未完整保存'}
$artifact=$fault.items[0].artifact
if($artifact -notmatch '^[A-Za-z0-9_.-]+$'){throw '故障原件索引无效'}
& $AdbPath -P 5042 -s $Serial pull "/data/local/d31-remote/diagnostics/$task/evidence/$artifact" "$capture/fault-original.bin" *> "$capture/fault-original-pull.log"
if($LASTEXITCODE -ne 0 -or (Get-FileHash "$capture/fault-original.bin").Hash -ine $fault.items[0].storedSha256){throw '故障原件取回摘要不符'}
$task=([guid]::NewGuid().ToString('N'))+([guid]::NewGuid().ToString('N'))
$identity.snapshotId=$run+'-system'
$system=Collect-Report 'system-manifest' @{operation='manifest';scope=$SystemScope;identity=$identity} $task
$after=Invoke-RestMethod -Uri ([uri]::new($ProbeUri,'/health')) -TimeoutSec 10
$after | ConvertTo-Json | Out-File "$capture/health-after.json" -Encoding utf8 -NoClobber
if($after.version_code -ne $health.version_code -or $after.uptime_ms -lt $health.uptime_ms){throw '原探针状态发生非预期变化'}
[ordered]@{通过=$true;隔离清单=$manifest.index;真实范围=$system.index;故障证据=$fault;已安装候选=$false;整体一致性='NOT_ASSESSED'} | ConvertTo-Json -Depth 25 | Out-File "$capture/result-private.json" -Encoding utf8 -NoClobber
Write-Output '按需清单、故障证据和同号重试已执行；真实范围状态见私有报告。未安装APK或改变活动核心。'
