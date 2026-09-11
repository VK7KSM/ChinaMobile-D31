param([Parameter(Mandatory=$true)][string]$RuntimeRoot, [Parameter(Mandatory=$true)][string]$Package, [Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$powershell = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$csc = "$env:SystemRoot\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$sandbox = Join-Path $OutputDirectory '中文 模拟设备'
New-Item -ItemType Directory -Path (Join-Path $sandbox 'tools') -Force | Out-Null
foreach ($name in @('flash_d31_recovery.ps1','approved-package.json','installed-files.json')) {
    Copy-Item -LiteralPath (Join-Path $RuntimeRoot $name) -Destination (Join-Path $sandbox $name)
}
& $csc /nologo /reference:System.Web.Extensions.dll ("/out:" + (Join-Path $sandbox 'tools/adb.exe')) (Join-Path $PSScriptRoot 'BackendFakeAdb.cs')
if ($LASTEXITCODE) { throw '模拟ADB编译失败' }
$backend = Join-Path $sandbox 'flash_d31_recovery.ps1'
# 只在隔离副本替换HTTP传输并跳过真实等待；启动恢复时序由TestPostBoot单独验证。
$backendText = [IO.File]::ReadAllText($backend)
$tokens=$null; $parseErrors=$null
$backendAst = [System.Management.Automation.Language.Parser]::ParseInput($backendText,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count) { throw '模拟后端语法错误' }
$probeDefinition = $backendAst.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-D31Probe'},$true)
if (-not $probeDefinition) { throw '未找到可隔离的HTTP传输入口' }
$backendText = $backendText.Remove($probeDefinition.Extent.StartOffset,$probeDefinition.Extent.EndOffset-$probeDefinition.Extent.StartOffset).Insert($probeDefinition.Extent.StartOffset,"function Invoke-D31Probe { throw 'OFFLINE_HTTP_DISABLED' }")
$bodyOffset = $backendAst.ParamBlock.Extent.EndOffset
$backendText = $backendText.Insert($bodyOffset,"`r`nfunction Start-Sleep { param([int]`$Seconds) }`r`n")
[IO.File]::WriteAllText($backend,$backendText,(New-Object Text.UTF8Encoding($true)))
$results = @()
function Run-Case([string]$Name, [string[]]$Arguments, [bool]$Pass, [bool]$MayReboot) {
    $env:D31_TEST_CASE = $Name
    $env:D31_TEST_TRANSCRIPT = Join-Path $OutputDirectory ($Name + '.commands.txt')
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $powershell -NoProfile -ExecutionPolicy Bypass -File $backend @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = 'Stop' }
    $output | Out-File (Join-Path $OutputDirectory ($Name + '.log')) -Encoding UTF8
    if (($code -eq 0) -ne $Pass) { throw "测试结果不符：$Name，退出码$code" }
    $commands = if(Test-Path $env:D31_TEST_TRANSCRIPT) { Get-Content -Raw $env:D31_TEST_TRANSCRIPT } else { '' }
    if (-not $MayReboot -and $commands -match 'reboot|/cache/recovery/command|\spush\s') { throw "失败测试越过写入门：$Name" }
    if ($commands -and ($commands -match '(?m)^(?!-P 5042 ).+' -or $commands -match 'kill-server|start-server')) { throw "模拟命令越过5042服务器边界：$Name" }
    if ($Name -match '^invalid-' -and $commands) { throw "非法目标触发了ADB：$Name" }
    if ($Name -eq 'success-5654' -and ($commands -notmatch 'connect 192\.0\.2\.31:5654' -or $commands -match ':5555')) { throw '实际端口未贯穿模拟流程' }
    if ($commands -match '/cache/recovery/command') {
        $guards = [regex]::Matches($commands, 'D31_MAINTENANCE_ABSENT_V1')
        if ($guards.Count -ne 2 -or $guards[1].Index -gt $commands.IndexOf('mkdir -p /cache/recovery')) { throw "未在写入前复核维护状态：$Name" }
        if ($commands.LastIndexOf('busybox sha256sum /data/local/tmp/') -gt $guards[1].Index) { throw "维护末门早于上传校验：$Name" }
    }
    if ($Name -eq 'repair-late' -and ([regex]::Matches($commands, 'D31_MAINTENANCE_ABSENT_V1').Count -ne 2 -or $commands -notmatch 'busybox sha256sum /data/local/tmp/')) { throw '未模拟上传后的修复竞争' }
    if ($Name -in @('success','legacy95') -and $commands -match 'RemoteWindowsMaintenance') { throw '旧设备不应调用新维护协议' }
    if ($Name -in @('full96-success','full96-system','full96-reboot-failed','full96-command-mismatch','full96-command-remote-failed','full96-command-marker-missing')) {
        $reserve = [regex]::Match($commands, 'RemoteWindowsMaintenance reserve ([a-f0-9]{32})')
        if (-not $reserve.Success -or $reserve.Index -gt $commands.IndexOf('mkdir -p /cache/recovery')) { throw "未先取得预留：$Name" }
        $release = [regex]::Match($commands, 'RemoteWindowsMaintenance release ([a-f0-9]{32})')
        if ($release.Success) { throw 'Recovery交接后不应自动释放跨步骤预留' }
        if (-not $Pass -and ($output -join "`n") -notmatch '保留本次维护预留') { throw "交接失败未明确保留预留：$Name" }
        if ($Name -in @('full96-command-mismatch','full96-command-remote-failed','full96-command-marker-missing') -and $commands -match 'reboot recovery') { throw '回读或设备退出未确认仍触发重启' }
    }
    if ($Name -eq 'success' -and ($commands -notmatch 'reboot recovery' -or ($output -join "`n") -notmatch '完整Recovery刷机和首次启动验证全部完成')) { throw '完整模拟流程未完成' }
    Write-Output "通过：$Name"
    $script:results += [pscustomobject]@{name=$Name;passed=$true;exitCode=$code}
}
try {
    Run-Case 'package-only' @('-PackagePath',$Package,'-PackagePreflightOnly') $true $false
    $bad = Join-Path $sandbox '损坏固件.zip'
    [IO.File]::WriteAllBytes($bad, [byte[]](1,2,3))
    Run-Case 'truncated' @('-PackagePath',$bad,'-PackagePreflightOnly') $false $false
    $stream = [IO.File]::Open($bad,[IO.FileMode]::Open)
    $stream.SetLength((Get-Item $Package).Length)
    $stream.Dispose()
    Run-Case 'same-size-corrupt' @('-PackagePath',$bad,'-PackagePreflightOnly') $false $false
    $base = @('-Serial','192.0.2.31:5555','-PackagePath',$Package,'-SkipBackup')
    foreach($name in @('wrong-network','bad-logo','bad-boot','bad-recovery','no-space')) { Run-Case $name $base $false $false }
    Run-Case 'preflight' ($base + @('-PreflightOnly')) $true $false
    foreach($name in @('repair-present','repair-read-failed','repair-unknown')) { Run-Case $name ($base + @('-DevicePreflightOnly')) $false $false }
    foreach($port in @('1','5654','65535')) { Run-Case ('port-' + $port) @('-Serial',("192.0.2.31:" + $port),'-DevicePreflightOnly') $true $false }
    foreach($serial in @('192.0.2.31:0','192.0.2.31:65536','192.0.2.31:abc','192.0.2.999:5654','192.0.2.31:5654;id')) {
        Run-Case ('invalid-' + [array]::IndexOf(@('192.0.2.31:0','192.0.2.31:65536','192.0.2.31:abc','192.0.2.999:5654','192.0.2.31:5654;id'),$serial)) @('-Serial',$serial,'-DevicePreflightOnly') $false $false
    }
    Run-Case 'invalid-server' @('-Serial','192.0.2.31:5654','-AdbPort','5041','-DevicePreflightOnly') $false $false
    Run-Case 'bad-remote-hash' $base $false $false
    foreach($name in @('repair-late','health-invalid','full96-no-protocol','full96-bad-path','full96-reserve-failed','full96-reserve-unknown')) { Run-Case $name $base $false $false }
    Run-Case 'missing-backup' @('-Serial','192.0.2.31:5555','-PackagePath',$Package) $false $false
    Run-Case 'success' $base $true $true
    Run-Case 'success-5654' @('-Serial','192.0.2.31:5654','-PackagePath',$Package,'-SkipBackup') $true $true
    foreach($name in @('legacy95','full96-success','full96-system')) { Run-Case $name $base $true $true }
    foreach($name in @('full96-reboot-failed','full96-command-mismatch','full96-command-remote-failed','full96-command-marker-missing')) { Run-Case $name $base $false $true }
    Run-Case 'upload-resume' $base $true $true
    Run-Case 'bad-installed' $base $false $true
    Run-Case 'bad-handover' $base $false $true
    foreach($name in @('bad-initialization','bad-storage-support','bad-tcp-default','old-app-page')) { Run-Case $name $base $false $true }
} finally {
    Remove-Item Env:D31_TEST_CASE -ErrorAction SilentlyContinue
    Remove-Item Env:D31_TEST_TRANSCRIPT -ErrorAction SilentlyContinue
}
[pscustomobject]@{cases=$results;count=$results.Count;passed=$true;sourceSha256=(Get-FileHash (Join-Path $RuntimeRoot 'flash_d31_recovery.ps1')).Hash;httpDisabled=$true;realAdb=$false} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'backend-results.json') -Encoding UTF8
$global:LASTEXITCODE = 0
