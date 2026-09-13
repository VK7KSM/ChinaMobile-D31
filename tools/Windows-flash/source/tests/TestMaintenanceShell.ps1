param([Parameter(Mandatory=$true)][string]$OutputDirectory,[string]$BashPath='C:/Program Files/Git/bin/bash.exe')
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputDirectory){throw '测试输出已存在，拒绝覆盖'}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$source=Join-Path $PSScriptRoot '../../d31/factory_package/flash_d31_recovery.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput([IO.File]::ReadAllText($source),[ref]$tokens,[ref]$errors)
if($errors.Count){throw '后端语法错误'}
$definition=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Assert-LegacyFlashDeployment'},$true)
$assignment=$definition.Find({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$probe'},$true)
Invoke-Expression $assignment.Extent.Text
# 仅把遍历根替换成隔离夹具，原始逐级ls/grep/退出语义保持不变。
$command=$probe.Replace('p=; remaining=', 'p="$fixture"; remaining=')
if($command -ceq $probe){throw '未替换夹具根，禁止执行'}
$results=@()
foreach($case in @('absent','marker','system-apk-directory','active','releases','supervisor','prefix-only','unreadable')) {
    $caseRoot=Join-Path $OutputDirectory $case
    $tree=Join-Path $caseRoot 'tree'
    New-Item -ItemType Directory -Path (Join-Path $tree 'system/etc'),(Join-Path $tree 'system/priv-app'),(Join-Path $tree 'data/local/d31-remote/runtime/updates') -Force | Out-Null
    $target=switch($case){
        'marker' {'system/etc/d31-elfremote.system'}
        'system-apk-directory' {'system/priv-app/D31ElfRemote'}
        'active' {'data/local/d31-remote/runtime/active.json'}
        'releases' {'data/local/d31-remote/releases'}
        'supervisor' {'data/local/d31-remote/runtime/updates/supervisor.json'}
        'prefix-only' {'system/priv-app/D31ElfRemote-old'}
    }
    if($target){New-Item -ItemType Directory -Path (Join-Path $tree $target) -Force | Out-Null}
    $scriptFile=Join-Path $caseRoot 'probe.sh'
    $header='fixture="$(cd -- "$(dirname -- "$0")" && pwd)/tree"' + "`n"
    $header+=if($case -eq 'unreadable'){'busybox() { if [ "$1" = ls ]; then return 72; fi; command "$@"; }'}else{'busybox() { command "$@"; }'}
    [IO.File]::WriteAllText($scriptFile, $header+"`n"+$command+"`n", (New-Object Text.UTF8Encoding($false)))
    $output=& $BashPath --noprofile --norc $scriptFile 2>&1
    $code=$LASTEXITCODE
    $text=($output -join "`n").Trim()
    $expected=if($case -in @('absent','prefix-only')){'D31_LEGACY_DEPLOYMENT_ABSENT_V1'}elseif($case -eq 'unreadable'){''}else{'D31_MODERN_DEPLOYMENT_PRESENT_V1'}
    if($text -cne $expected -or ($code -eq 0) -ne ($case -ne 'unreadable')){throw "真实shell探测结果不符：$case : $code : $text"}
    $results += [pscustomobject]@{name=$case;passed=$true;exitCode=$code;output=$text}
    Write-Output "通过：$case"
}
[pscustomobject]@{passed=$true;count=$results.Count;cases=$results;realDevice=$false;sourceSha256=(Get-FileHash $source).Hash;productionShellExecuted=$true} |
    ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutputDirectory 'shell-results.json') -Encoding UTF8
$global:LASTEXITCODE=0
