# 仅离线运行真实宿主脚本与真实shell；ADB和Windows上的原生派生由无网络替身提供。
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$PreparedStage,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$BashPath,
    [switch]$CrcrlfOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
function Assert([bool]$ok,[string]$reason) { if(-not $ok){throw $reason} }
Assert (-not (Test-Path -LiteralPath $OutputDirectory)) '测试证据目录已存在'
$root=(New-Item -ItemType Directory -Path $OutputDirectory).FullName
$PreparedStage=(Get-Item -LiteralPath $PreparedStage).FullName
$utf8=[Text.UTF8Encoding]::new($false)
$syntax=@('Prepare-SystemMigration.ps1','Invoke-SystemMigration.ps1','Test-SystemMigrationHost.ps1') | ForEach-Object {
    $file=Join-Path $PSScriptRoot $_;$tokens=$null;$errors=$null
    [void][System.Management.Automation.Language.Parser]::ParseFile($file,[ref]$tokens,[ref]$errors)
    Assert (@($errors).Count -eq 0) "PowerShell语法错误：$_"
    [ordered]@{file=$_;errors=0;sha256=(Get-FileHash -LiteralPath $file).Hash}
}
$syntax | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'syntax.json') -Encoding UTF8
$fixture=Join-Path $root 'fake-adb.exe'
$csc=Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
& $csc /nologo /reference:System.Web.Extensions.dll "/out:$fixture" (Join-Path $PSScriptRoot 'SystemMigrationHostFixture.cs') *> (Join-Path $root 'compile.txt')
Assert ($LASTEXITCODE -eq 0) '测试替身编译失败'
$script:checks=0;$script:caseRoot=$null;$script:stage=$null;$script:count=0
function New-Case([string]$name) {
    $script:caseRoot=(New-Item -ItemType Directory -Path (Join-Path $root $name)).FullName
    $script:stage=Join-Path $script:caseRoot 'stage'
    Copy-Item -LiteralPath $PreparedStage -Destination $script:stage -Recurse
    $script:count=0
    $env:D31_HOST_TEST_ROOT=$script:caseRoot;$env:D31_HOST_TEST_STAGE=$script:stage;$env:D31_HOST_TEST_MODE=''
}
function Calls { $f=Join-Path $script:caseRoot 'calls.jsonl';if(Test-Path $f){return @(Get-Content $f).Count};return 0 }
function Run([string]$action,[int]$expected=0,[string]$serial='192.0.2.10:5555',[string]$hash='') {
    $script:count++
    if(-not $hash){$hash=(Get-FileHash -LiteralPath (Join-Path $script:stage 'stage.json')).Hash.ToLowerInvariant()}
    $argsList=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $script:stage 'Invoke-SystemMigration.ps1'),
        '-StageDirectory',$script:stage,'-AdbPath',$fixture,'-StageManifestSha256',$hash,'-EvidenceDirectory',(Join-Path $script:caseRoot "run-$script:count"))
    if($action){$argsList+=@('-Action',$action)}
    if($serial){$argsList+=@('-Serial',$serial)}
    $previous=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        & powershell.exe @argsList *> (Join-Path $script:caseRoot "output-$script:count.txt")
        $code=$LASTEXITCODE
    } finally { $ErrorActionPreference=$previous }
    Assert (($expected -eq 0 -and $code -eq 0) -or ($expected -ne 0 -and $code -ne 0)) "宿主返回不符：$action，用例$script:caseRoot"
    $script:checks++
}
$saved=@{};foreach($key in @('D31_HOST_TEST_ROOT','D31_HOST_TEST_STAGE','D31_HOST_TEST_MODE','D31_HOST_TEST_SHELL')){$saved[$key]=[Environment]::GetEnvironmentVariable($key)}
try {
    if(-not $CrcrlfOnly) {
    New-Case 'offline-default';Run '';Assert ((Calls) -eq 0) '默认动作调用了设备'
    New-Case 'missing-serial';Run 'Preflight' 1 '';Assert ((Calls) -eq 0) '缺序列号仍调用设备'
    New-Case 'wrong-manifest';Run 'Deploy' 1 '192.0.2.10:5555' ('0'*64);Assert ((Calls) -eq 0) '错清单仍调用设备'
    New-Case 'changed-payload';[IO.File]::AppendAllText((Join-Path $script:stage 'start.sh'),'changed');Run 'Deploy' 1;Assert ((Calls) -eq 0) '载荷改变仍调用设备'
    New-Case 'preflight';Run 'Preflight';$calls=Get-Content (Join-Path $script:caseRoot 'calls.jsonl') -Raw
    Assert ($calls -notmatch '"push"|"pull"|mkdir|install-remote-system\.sh') '只读预检发生写入'
    New-Case 'unsupported-fork';$env:D31_HOST_TEST_MODE='no-fork';Run 'Deploy' 1
    Assert (-not (Test-Path (Join-Path $script:caseRoot 'device'))) '不支持派生时已经上传'
    New-Case 'host0-remote-failure';$env:D31_HOST_TEST_MODE='host0-remote-failure';Run 'Deploy' 1
    Assert (-not (Test-Path (Join-Path $script:caseRoot 'device'))) '远端非零被宿主0掩盖'
    New-Case 'missing-sentinel';$env:D31_HOST_TEST_MODE='no-sentinel';Run 'Deploy' 1
    Assert (-not (Test-Path (Join-Path $script:caseRoot 'device'))) '退出标记缺失后发生写入'
    New-Case 'deploy-finalize-rollback';Run 'Deploy'
    $result=Get-Content (Join-Path $script:caseRoot 'run-1/result.json') -Raw | ConvertFrom-Json
    Assert ($result.transactionPassed -and -not $result.migrationVerified) '暂存冒充迁移完成'
    $before=Calls;Run 'Deploy' 1;Assert ((Calls) -eq $before) '重放首次部署调用了设备'
    Run 'Finalize' 1 '192.0.2.11:5555';Assert ((Calls) -eq $before) '跨设备续接调用了设备'
    Run 'Finalize';Run 'Rollback'
    $calls=Get-Content (Join-Path $script:caseRoot 'calls.jsonl') -Raw
    Assert ([regex]::Matches($calls,'"push"').Count -eq 8 -and $calls -notmatch 'reboot|"connect"|kill-server') '续接重传或执行了禁止动作'
    Assert (Test-Path (Join-Path $script:caseRoot 'run-5/device-stage/backup/model-only.txt')) '未回收备份证据'
    New-Case 'finalize-failure';Run 'Deploy';$env:D31_HOST_TEST_MODE='fail-finalize';Run 'Finalize' 1
    $r=Get-Content (Join-Path $script:caseRoot 'run-2/result.json') -Raw | ConvertFrom-Json
    Assert (-not $r.migrationVerified -and -not $r.transactionPassed) '身份失败仍报迁移完成'
    New-Case 'nonzero-with-success-text';$env:D31_HOST_TEST_MODE='stale-success';Run 'Deploy' 1
    $r=Get-Content (Join-Path $script:caseRoot 'run-1/result.json') -Raw | ConvertFrom-Json
    Assert (-not $r.transactionPassed) '非零退出码被旧成功文本掩盖'

    New-Case 'hook-early-exit';$env:D31_HOST_TEST_SHELL=$BashPath
    $original=[IO.File]::ReadAllText((Join-Path $script:stage 'original-hook.sh'),$utf8)
    $derived=[IO.File]::ReadAllText((Join-Path $script:stage 'install-recovery.sh'),$utf8)
    $end="# D31_ELFREMOTE_HOST_END`n";$endAt=$derived.IndexOf($end,[StringComparison]::Ordinal)+$end.Length
    Assert ($endAt -ge $end.Length -and $derived.Substring($endAt) -ceq $original.Substring("#!/system/bin/sh`n".Length)) '派生钩子没有保留原始剩余字节'
    $prefix=$derived.Substring(0,$endAt)
    Assert ($prefix -notmatch '(?m)&\s*$' -and $prefix.Contains('start-stop-daemon -S -b -x /system/bin/d31-elfremote-start')) '启动段未复用原生派生或匹配了通用shell'
    function Unix([string]$p) { return '/'+$p.Substring(0,1).ToLowerInvariant()+$p.Substring(2).Replace('\','/') }
    $signal=Join-Path $script:caseRoot 'child-started'
    $child=Join-Path $script:caseRoot 'start.sh'
    [IO.File]::WriteAllText($child,"#!/bin/sh`nsleep 0.3`nprintf ready > '$(Unix $signal)'`n",$utf8)
    $marker=Join-Path $script:caseRoot 'marker';[IO.File]::WriteAllText($marker,"1`n",$utf8)
    # 只重定位绝对路径；真实启动段后放早期exit和不可达命令，验证父钩子及时退出。
    $code=$prefix.Replace('/system/bin/busybox',"'$(Unix $fixture)'").Replace('/system/bin/d31-elfremote-start',"'$(Unix $child)'").Replace('/system/etc/d31-elfremote.system',"'$(Unix $marker)'").Replace('/system/bin/sh','/bin/sh')
    $hook=Join-Path $script:caseRoot 'hook.sh'
    [IO.File]::WriteAllText($hook,$code+"exit 7`nprintf unreachable > '$(Unix (Join-Path $script:caseRoot 'unreachable'))'`n",$utf8)
    & $BashPath -c "chmod 755 '$(Unix $child)'";Assert ($LASTEXITCODE -eq 0) '隔离入口权限设置失败'
    $timer=[Diagnostics.Stopwatch]::StartNew()
    & $BashPath (Unix $hook) *> (Join-Path $script:caseRoot 'hook-output.txt')
    Assert ($LASTEXITCODE -eq 7 -and $timer.Elapsed.TotalSeconds -lt 3) '原钩子exit改变或启动段阻塞'
    for($i=0;$i -lt 60 -and -not (Test-Path $signal);$i++){Start-Sleep -Milliseconds 50}
    Assert ((Test-Path $signal) -and -not (Test-Path (Join-Path $script:caseRoot 'unreachable'))) '原钩子早期exit后没有派生或执行了不可达代码'
    $script:checks++

    # 用真实bash执行已记录的生产退出包装，替换的仅是设备业务命令。
    $command=Get-Content (Join-Path $root 'preflight/run-1/command-002.json') -Raw | ConvertFrom-Json
    $wrapped=[string]$command.arguments[-1]
    $tail=$wrapped.Substring($wrapped.LastIndexOf("`n)`nd31_migration_exit=",[StringComparison]::Ordinal))
    $envelope=Join-Path $script:caseRoot 'exit-envelope.sh'
    [IO.File]::WriteAllText($envelope,"(`nprintf no-final-newline; exit 7"+$tail,$utf8)
    $info=[Diagnostics.ProcessStartInfo]::new($BashPath,('"'+(Unix $envelope)+'"'))
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $process=[Diagnostics.Process]::Start($info)
    $raw=$process.StandardOutput.ReadToEnd();$err=$process.StandardError.ReadToEnd();$process.WaitForExit()
    [IO.File]::WriteAllText((Join-Path $script:caseRoot 'exit-envelope-output.txt'),$raw,$utf8)
    [IO.File]::WriteAllText((Join-Path $script:caseRoot 'exit-envelope-error.txt'),$err,$utf8)
    Assert ($process.ExitCode -eq 0 -and $raw -cmatch '\Ano-final-newline\nD31_DEVICE_EXIT_[a-f0-9]{32}_7\n\z') '真实shell未保留远端非零或未强制分隔无换行输出'
    $process.Dispose();$script:checks++
    }
    New-Case 'crcrlf-preflight';$env:D31_HOST_TEST_MODE='crcrlf';Run 'Preflight'
    $raw=Get-Content (Join-Path $script:caseRoot 'run-1/command-002.json') -Raw | ConvertFrom-Json
    Assert ($raw.output -cmatch '\AD31_HOST_TARGET_READY_V1\r\r\n\r\r\nD31_DEVICE_EXIT_[a-f0-9]{32}_0\r\r\n\z') '原始CRCRLF输出被改变'
    $parsed=Get-Content (Join-Path $script:caseRoot 'run-1/device-exit-002.json') -Raw | ConvertFrom-Json
    Assert ($parsed.markerValid -and $parsed.deviceExit -eq 0 -and $parsed.hostExit -eq 0) 'CRCRLF标记未正确解析'
    foreach($mode in @('crcrlf-remote-failure','crcrlf-missing','crcrlf-duplicate')) {
        New-Case $mode;$env:D31_HOST_TEST_MODE=$mode;Run 'Deploy' 1
        Assert (-not (Test-Path (Join-Path $script:caseRoot 'device'))) 'CRCRLF失败输出导致设备写入'
    }
    [ordered]@{passed=$true;checks=$script:checks;realDeviceCommands=0;realHostScripts=$true;realShellHook=(-not $CrcrlfOnly);androidServiceStartupVerified=$false} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'result.json') -Encoding UTF8
    Get-Content (Join-Path $root 'result.json')
} finally {foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key])}}
