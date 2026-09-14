# 独立宿主：默认离线验证；设备动作固定5042，显式指定完整TCP序列号，绝不自动重启。
[CmdletBinding()]
param(
    [ValidateSet('Validate','Preflight','Deploy','Finalize','Rollback')][string]$Action='Validate',
    [string]$StageDirectory,
    [string]$StageManifestSha256,
    [string]$Serial,
    [string]$AdbPath,
    [string]$EvidenceDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
function Require([bool]$ok,[string]$message) { if (-not $ok) { throw $message } }
function Hash([string]$path) { return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Write-NewJson([string]$path,$value) {
    $file=[IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    $writer=[IO.StreamWriter]::new($file,[Text.UTF8Encoding]::new($false))
    try { $writer.Write(($value | ConvertTo-Json -Depth 20)) } finally { $writer.Dispose() }
}
if (-not $StageDirectory) { $StageDirectory=$PSScriptRoot }
$stage=(Get-Item -LiteralPath $StageDirectory).FullName
Require ((Get-Item -LiteralPath $stage).PSIsContainer -and -not ((Get-Item -LiteralPath $stage).Attributes -band [IO.FileAttributes]::ReparsePoint)) '暂存目录无效或为链接'
$manifest=Join-Path $stage 'stage.json'
Require (-not ((Get-Item -LiteralPath $manifest).Attributes -band [IO.FileAttributes]::ReparsePoint)) '暂存清单不能为链接'
$manifestHash=Hash $manifest
if ($StageManifestSha256) { Require ($StageManifestSha256 -cmatch '^[a-f0-9]{64}$' -and $manifestHash -ceq $StageManifestSha256) '暂存清单摘要不匹配' }
$index=Get-Content -LiteralPath $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
Require ($index.schema -eq 1 -and $index.operation -ceq 'system_migration_stage' -and $index.remoteStage -cmatch '^/data/local/d31-remote/deploy-host-[a-f0-9]{32}$') '暂存清单合同无效'
$required=@('remote.apk','remote-variants.json','migration-check.jar','original-hook.sh','install-remote-system.sh','replace-remote-system.sh',
    'remote-system-transaction.sh','start.sh','install-recovery.sh','marker','Invoke-SystemMigration.ps1')
Require (@($index.files).Count -eq $required.Count) '暂存文件数量不符'
$entries=@{}
foreach ($name in $required) {
    $matches=@($index.files | Where-Object name -CEQ $name)
    Require ($matches.Count -eq 1) '暂存清单存在缺项或重复项'
    $entry=$matches[0]; $file=Get-Item -LiteralPath (Join-Path $stage $name)
    Require (-not $file.PSIsContainer -and -not ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) '暂存文件不是普通非链接文件'
    Require ($entry.sha256 -cmatch '^[a-f0-9]{64}$' -and $file.Length -eq $entry.bytes -and (Hash $file.FullName) -ceq $entry.sha256) "暂存内容改变：$name"
    $entries[$name]=$entry
}
Require ($index.fullSha256 -ceq $entries['remote.apk'].sha256 -and $index.checkerSha256 -ceq $entries['migration-check.jar'].sha256 -and
    $index.originalHookSha256 -ceq $entries['original-hook.sh'].sha256 -and $index.fullVersionCode -ge 96) '暂存摘要绑定不符'
Require ((Hash $manifest) -ceq $manifestHash) '读取期间暂存清单改变'
if ($Action -eq 'Validate') {
    [pscustomobject]@{ OfflineValidated=$true; StageManifestSha256=$manifestHash; MigrationVerified=$false } | ConvertTo-Json
    return
}
# 所有设备动作的参数门均位于第一次ADB调用之前。
Require ($StageManifestSha256 -cmatch '^[a-f0-9]{64}$') '设备动作必须显式绑定暂存清单摘要'
Require ($Serial -cmatch '^([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$') '必须显式指定完整IPv4:端口序列号'
$parts=$Serial.Split(':'); $address=$null
Require ([Net.IPAddress]::TryParse($parts[0],[ref]$address) -and $address.ToString() -ceq $parts[0] -and [int]$parts[1] -ge 1 -and [int]$parts[1] -le 65535) '序列号地址或端口无效'
Require ($AdbPath -and (Test-Path -LiteralPath $AdbPath -PathType Leaf)) '设备动作必须显式提供ADB程序'
Require ($EvidenceDirectory -and -not (Test-Path -LiteralPath $EvidenceDirectory)) '必须提供尚不存在的私有证据目录'
$bindingPath=Join-Path $stage 'device-binding-private.json'
if ($Action -in @('Finalize','Rollback')) {
    $binding=Get-Content -LiteralPath $bindingPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Require ($binding.serial -ceq $Serial -and $binding.stageSha256 -ceq $manifestHash -and $binding.remoteStage -ceq $index.remoteStage) '不能续接其他设备或另一暂存事务'
}
if ($Action -eq 'Deploy') { Require (-not (Test-Path -LiteralPath $bindingPath)) '此暂存事务已经绑定；请续接或另建离线暂存目录' }
$AdbPath=(Get-Item -LiteralPath $AdbPath).FullName
$evidence=(New-Item -ItemType Directory -Path $EvidenceDirectory -ErrorAction Stop).FullName
$script:sequence=0
function Quote-WindowsArgument([string]$value) {
    # Windows PowerShell 5.1的原生命令绑定会丢失内嵌引号；直接按CRT规则传给ProcessStartInfo。
    $escaped=[regex]::Replace($value,'(\\*)"','$1$1\"')
    $escaped=[regex]::Replace($escaped,'(\\+)$','$1$1')
    return '"'+$escaped+'"'
}
function Adb([string[]]$arguments) {
    $script:sequence++
    $all=@('-P','5042','-s',$Serial)+$arguments
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$AdbPath
    $info.Arguments=(@($all | ForEach-Object {Quote-WindowsArgument $_}) -join ' ')
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $info.StandardOutputEncoding=[Text.UTF8Encoding]::new($false)
    $info.StandardErrorEncoding=[Text.UTF8Encoding]::new($false)
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    $code=-1;$text='';$errorText=''
    try {
        Require ($process.Start()) 'ADB客户端未启动'
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit(300000)) {
            $process.Kill();$process.WaitForExit();$errorText='宿主等待超时；仅结束本次ADB客户端，设备事务可能仍在运行'
        } else { $code=$process.ExitCode }
        $text=$stdout.GetAwaiter().GetResult();$errorText+=$stderr.GetAwaiter().GetResult()
    } catch { $errorText+=$_.Exception.Message }
    finally { $process.Dispose() }
    Write-NewJson (Join-Path $evidence ('command-{0:D3}.json' -f $script:sequence)) ([ordered]@{arguments=$all;exitCode=$code;output=$text;stderr=$errorText})
    return [pscustomobject]@{ Code=$code; Text=$text }
}
function Shell-Result([string]$command) {
    # 旧ADB不可靠传播远端exit；子shell隔离exit，末尾先换行，再输出唯一调用标记。
    $nonce=[guid]::NewGuid().ToString('N')
    $prefix='D31_DEVICE_EXIT_'+$nonce+'_'
    $wrapped="(`n"+$command+"`n)`nd31_migration_exit=`$?`nprintf '\n"+$prefix+"%s\n' `"`$d31_migration_exit`""
    $result=Adb @('shell',$wrapped)
    # D31旧ADB可能输出CRCRLF；仅规范化解析副本，Adb已落盘的原始JSON保持原字节内容。
    $parseText=$result.Text.Replace("`r",'')
    $pattern='(?s)\A(?<body>.*)\r?\n'+[regex]::Escape($prefix)+'(?<code>[0-9]{1,3})\r?\n?\z'
    $match=[regex]::Match($parseText,$pattern)
    $valid=$match.Success -and [regex]::Matches($parseText,[regex]::Escape($prefix)).Count -eq 1
    $deviceCode=if($valid){[int]$match.Groups['code'].Value}else{$null}
    Write-NewJson (Join-Path $evidence ('device-exit-{0:D3}.json' -f $script:sequence)) ([ordered]@{nonce=$nonce;hostExit=$result.Code;markerValid=$valid;deviceExit=$deviceCode})
    Require ($result.Code -eq 0 -and $valid -and $deviceCode -le 255) 'ADB失败或唯一设备退出标记缺失，拒绝继续；原始输出已保留'
    return [pscustomobject]@{Code=$deviceCode;HostCode=$result.Code;Text=$match.Groups['body'].Value}
}
function Shell([string]$command) {
    $result=Shell-Result $command
    Require ($result.Code -eq 0) '设备真实退出码非零，拒绝继续；原始输出已保留'
    return $result.Text
}
$remote=[string]$index.remoteStage
$payload=@('remote.apk','migration-check.jar','install-remote-system.sh','replace-remote-system.sh','remote-system-transaction.sh','start.sh','marker','install-recovery.sh')
$pathGuard='for p in /data /data/local /data/local/d31-remote '+$remote+'; do [ -d "$p" ] && [ ! -L "$p" ] || exit 72; done; '
function Verify-Remote {
    $command=$pathGuard
    foreach ($name in $payload) {
        $path="$remote/$name"
        $command+='[ -f '+$path+' ] && [ ! -L '+$path+' ] || exit 73; echo "'+$entries[$name].sha256+'  '+$path+'" | busybox sha256sum -c - || exit 74; '
    }
    $command+='[ -f '+$remote+'/stage-manifest.sha256 ] && [ ! -L '+$remote+'/stage-manifest.sha256 ] || exit 75; [ "$(cat '+$remote+'/stage-manifest.sha256)" = "'+$manifestHash+'" ] || exit 76'
    $null=Shell $command
}
$capture=$false; $success=$false; $status='NOT_STARTED'; $failure=$null; $captureFailure=$null
try {
    $connected=Adb @('get-state')
    Require ($connected.Code -eq 0 -and $connected.Text.Trim() -ceq 'device') '指定设备未就绪；不会自动连接或重启ADB服务器'
    $null=Shell '[ "$(id -u)" = 0 ] && [ "$(getprop ro.build.version.sdk)" = 23 ] && [ "$(getprop ro.product.device)" = hct6735_66_m0 ] && [ "$(getprop ro.product.model)" = hct6737t_66_m0 ] || exit 70; echo D31_HOST_TARGET_READY_V1'
    if ($Action -in @('Preflight','Deploy')) {
        $help=Shell '/system/bin/busybox start-stop-daemon --help 2>&1; :'
        Require ($help -cmatch '(?m)^\s*-b\b' -and $help -cmatch '(?m)^\s*-x\b') '未确认start-stop-daemon的-b/-x；不上传、不改钩子'
    }
    if ($Action -eq 'Preflight') {
        $null=Shell 'ls -ldZ /system/bin/install-recovery.sh || exit 71; busybox sha256sum /system/bin/install-recovery.sh || exit 72; pm path net.elfradio.d31bootstrap || exit 73; dumpsys package net.elfradio.d31bootstrap || exit 74'
        $status='READ_ONLY_PREFLIGHT_RECORDED_NOT_MIGRATION_ACCEPTANCE'; $success=$true
    } else {
        if ($Action -eq 'Deploy') {
            $null=Shell ('[ -f /system/bin/install-recovery.sh ] && [ ! -L /system/bin/install-recovery.sh ] || exit 71; echo "'+$index.originalHookSha256+'  /system/bin/install-recovery.sh" | busybox sha256sum -c -')
            Write-NewJson $bindingPath ([ordered]@{serial=$Serial;stageSha256=$manifestHash;remoteStage=$remote})
            $null=Shell ('umask 077; for p in /data /data/local; do [ -d "$p" ] && [ ! -L "$p" ] || exit 72; done; if [ -e /data/local/d31-remote ] || [ -L /data/local/d31-remote ]; then [ -d /data/local/d31-remote ] && [ ! -L /data/local/d31-remote ] || exit 73; else mkdir /data/local/d31-remote || exit 74; fi; [ ! -e '+$remote+' ] && [ ! -L '+$remote+' ] || exit 75; mkdir '+$remote)
            $capture=$true
            foreach ($name in $payload) {
                $sent=Adb @('push',(Join-Path $stage $name),"$remote/$name")
                Require ($sent.Code -eq 0) '上传失败，保留部分暂存及原始输出'
            }
            $null=Shell ($pathGuard+'printf ''%s\n'' '+$manifestHash+' > '+$remote+'/stage-manifest.sha256')
        } else { $capture=$true }
        Verify-Remote
        $argsText="$remote $($index.fullSha256) $($index.originalHookSha256) $($index.checkerSha256)"
        $command=if ($Action -eq 'Deploy') { "sh $remote/install-remote-system.sh $argsText" }
            else { "sh $remote/remote-system-transaction.sh $($Action.ToLowerInvariant()) $argsText" }
        $result=Shell-Result $command
        $status=(Shell ($pathGuard+'[ -f '+$remote+'/status ] && [ ! -L '+$remote+'/status ] || exit 77; cat '+$remote+'/status')).Trim()
        $expected=@{Deploy='SYSTEM_COMPONENT_STAGED_FINALIZE_REQUIRED';Finalize='INSTALLED_SYSTEM_IDENTITY_AND_CORE_MAPPING_VERIFIED';Rollback='RESTORED_APK_AND_SETTINGS_RUNTIME_NOT_VERIFIED'}[$Action]
        Require ($result.Code -eq 0 -and $status -ceq $expected -and ($result.Text -split '\r?\n') -ccontains $expected) '事务未取得本次对应通过标记；保留现场，不自动重启或重试'
        $success=$true
    }
} catch { $failure=$_.Exception.Message }
finally {
    if ($capture) {
        try {
            $null=Shell ($pathGuard+'links=$(busybox find '+$remote+' -type l -print) || exit 78; [ -z "$links" ] || exit 79')
            $pulled=Adb @('pull',$remote,(Join-Path $evidence 'device-stage'))
            Require ($pulled.Code -eq 0) '设备暂存和原像拉取失败'
        } catch { $captureFailure=$_.Exception.Message }
    }
    Write-NewJson (Join-Path $evidence 'result.json') ([ordered]@{action=$Action;status=$status;transactionPassed=$success;
        evidenceComplete=($null -eq $captureFailure);failure=$failure;captureFailure=$captureFailure;
        migrationVerified=($success -and $Action -eq 'Finalize');rebootExecuted=$false;adbPort=5042})
}
Require ($success -and $null -eq $captureFailure) '宿主动作或证据回收未完成，详见私有证据result.json'
Write-Output $status
