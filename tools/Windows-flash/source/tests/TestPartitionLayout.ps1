param(
    [string]$OutputDirectory = (Join-Path $env:TEMP ('D31-PartitionLayout-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))),
    [string]$WslDistribution = 'docker-desktop',
    [switch]$ReproduceOnly,
    [switch]$ModernCompatibility,
    [ValidateSet('Legacy','Standard','Windows')][string]$NativeArgumentMode = 'Windows'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $ModernCompatibility -and ($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1)) {
    throw '本回归必须通过 Windows PowerShell 5.1 执行'
}
$nativeMode = Get-Variable PSNativeCommandArgumentPassing -ErrorAction SilentlyContinue
if ($ModernCompatibility -and ($null -eq $nativeMode -or $PSVersionTable.PSVersion.Major -lt 7)) {
    throw '兼容性补测需要支持原生参数模式的新版本 PowerShell'
}
if ($ModernCompatibility) {
    $PSNativeCommandArgumentPassing = $NativeArgumentMode
    $nativeMode = Get-Variable PSNativeCommandArgumentPassing
}
$isLegacy = $null -eq $nativeMode -or $nativeMode.Value -eq 'Legacy'
if (Test-Path -LiteralPath $OutputDirectory) { throw '测试目录已存在，禁止覆盖' }
$null = New-Item -ItemType Directory -Path $OutputDirectory
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$root = Split-Path -Parent $PSScriptRoot
$sources = @((Join-Path $root '../d31/factory_package/flash_d31_recovery.ps1'),
    (Join-Path $root 'scripts/create_d31_rescue.ps1'))
$Adb = Join-Path $OutputDirectory 'PartitionLayoutNativeBridge.exe'
$AdbPort = 5042
$Serial = 'offline-layout'
$ByName = '/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name'
$ExpectedSizes = @{ boot=16777216L; recovery=16777216L; logo=8388608L; system=1610612736L; userdata=13517717504L }
$names = @('boot','recovery','logo','system','userdata','nvram','nvdata','protect1','protect2','proinfo','secro','seccfg','frp')
$results = @()
$completed = $false
$utf8 = New-Object Text.UTF8Encoding($false)
$env:D31_LAYOUT_DISTRO = $WslDistribution
$env:D31_LAYOUT_FIXTURE = Join-Path $PSScriptRoot 'PartitionLayoutFixture.sh'
function Add-SessionLog { param([string]$Text) }
function Add-Log { param([string]$Text) }
function Invoke-UnescapedLegacyAdb([string]$Command) {
    $arguments=@('-s',$Serial,'shell',$Command)
    $output=& $Adb -P $AdbPort @arguments 2>&1
    if($LASTEXITCODE){throw ($output -join "`n")}
    return ($output -join "`n").Trim()
}
function Read-Ast([string]$Path) {
    $tokens=$null; $errors=$null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw "脚本语法错误：$Path；$errors" }
    return $ast
}
function Find-Function($Ast, [string]$Name) {
    $node = $Ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $Name},$true)
    if (-not $node) { throw "找不到真实函数：$Name" }
    return $node.Extent.Text
}
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
try {
    & "$env:SystemRoot/Microsoft.NET/Framework64/v4.0.30319/csc.exe" /nologo /reference:System.Web.Extensions.dll "/out:$Adb" (Join-Path $PSScriptRoot 'PartitionLayoutNativeBridge.cs')
    if ($LASTEXITCODE) { throw '离线原生参数桥编译失败' }
    foreach ($source in $sources) {
        $label = [IO.Path]::GetFileNameWithoutExtension($source)
        $ast = Read-Ast $source
        # 仅加载这些真实函数，绝不点加载整个刷机脚本或运行其顶层语句。
        . ([scriptblock]::Create((Find-Function $ast 'Invoke-Adb')))
        . ([scriptblock]::Create((Find-Function $ast 'Get-DeviceValue')))
        . ([scriptblock]::Create((Find-Function $ast 'Assert-PartitionLayout')))
        $assignment = $ast.Find({param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$command' -and $n.Right.Extent.Text.Contains('p=$(readlink -f')},$true)
        $literal = $assignment.Right.Find({param($n) $n -is [System.Management.Automation.Language.StringConstantExpressionAst]},$true)
        $commandTemplate = $literal.Value
        [IO.File]::WriteAllText((Join-Path $OutputDirectory ($label + '.source.ps1')), [IO.File]::ReadAllText($source), $utf8)
        $env:D31_LAYOUT_CASE = 'valid'
        $env:D31_LAYOUT_TRANSCRIPT = Join-Path $OutputDirectory ($label + '-legacy')
        $legacy = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'PartitionLayoutLegacyCommand.sh')).Trim()
        # 此冻结字符串来自 1.6.9 原函数；复现模式还核对与当前源码完全相同。
        if ($ReproduceOnly) {
            Assert-True ($commandTemplate -ceq $legacy) '冻结原命令与源码不一致'
        }
        $legacy = $legacy.Replace('__BYNAME__',$ByName).Replace('__NAME__','recovery')
        $legacyError = ''
        try { $legacyValue = Invoke-UnescapedLegacyAdb $legacy } catch { $legacyError = $_.Exception.Message }
        $received = [IO.File]::ReadAllText($env:D31_LAYOUT_TRANSCRIPT + '.received.sh')
        if ($isLegacy) {
            Assert-True ($received -ceq $legacy.Replace('"','')) '未复现旧式原生传参剥离双引号'
            Assert-True ($legacyError -match 'syntax error') '旧命令未触发预期 shell 语法失败'
        } else {
            Assert-True ($received -ceq $legacy) '新式原生传参改变了命令'
            Assert-True (-not $legacyError -and $legacyValue -ceq '/dev/block/mmcblk0p2|34816|32768') '新式传参对照失败'
        }
        [IO.File]::WriteAllText($env:D31_LAYOUT_TRANSCRIPT + '.error.txt', $legacyError, $utf8)
        $results += [pscustomobject]@{source=$label;case='legacy';passed=$true;error=$legacyError}
        Write-Output "通过：$label 原命令传参对照（旧式模式=$isLegacy）"
        # 单独修正 printf 后仍执行原 tr 片段，证明换行没有被删掉，不能只修复管道符。
        if ($isLegacy) {
            $env:D31_LAYOUT_TRANSCRIPT = Join-Path $OutputDirectory ($label + '-legacy-tr')
            $printfOnly = $legacy.Replace('printf "%s|"', "printf '%s|'").Replace('printf "|"', "printf '|'")
            $trValue = Invoke-UnescapedLegacyAdb $printfOnly
            Assert-True ($trValue -ceq "/dev/block/mmcblk0p2|34816`n|32768") '未复现 tr 丢失引用后保留换行'
            $results += [pscustomobject]@{source=$label;case='legacy-tr';passed=$true;error=$trValue}
            Write-Output "通过：$label 原 tr 片段未删除换行已复现"
        }
        if ($ReproduceOnly) { continue }
        $cases = @(
            @{name='valid';error=''},
            @{name='missing-link';error='分区映射无法确认'},
            @{name='regular-file';error='分区映射无法确认'},
            @{name='wrong-disk';error='分区映射无法确认'},
            @{name='duplicate';error='分区映射重复或重叠'},
            @{name='overlap';error='分区映射重复或重叠'},
            @{name='zero-start';error='分区范围无效'},
            @{name='negative-start';error='分区映射无法确认'},
            @{name='huge-start';error='分区范围无效'},
            @{name='overflow-start';error='分区范围无效'},
            @{name='zero-size';error='分区范围无效'},
            @{name='huge-size';error='分区范围无效'},
            @{name='overflow-size';error='分区范围无效'},
            @{name='wrong-size';error='分区映射容量不匹配'},
            @{name='invalid-size';error='分区映射无法确认'},
            @{name='extra-line';error='分区映射无法确认'},
            @{name='empty-size';error='分区映射无法确认'},
            @{name='missing-start';error='ADB命令失败|分区映射无法确认'},
            @{name='missing-size';error='ADB命令失败|分区映射无法确认'}
        )
        foreach ($case in $cases) {
            $env:D31_LAYOUT_CASE = $case.name
            $env:D31_LAYOUT_TRANSCRIPT = Join-Path $OutputDirectory ($label + '-' + $case.name)
            $message = ''
            try { Assert-PartitionLayout $(if ($case.name -eq 'valid') { $names } else { @('boot','recovery') }) }
            catch { $message = $_.Exception.Message }
            if ($case.error) {
                Assert-True ($message -match $case.error) "拒绝条件不符：$label/$($case.name)：$message"
                if ($message -notmatch 'ADB命令失败') {
                    Assert-True ($message -match '原始返回值=') "缺少原始返回诊断：$message"
                    if ($case.name -eq 'extra-line') { Assert-True ($message.Contains('\nextra')) '诊断未保留额外行' }
                    if ($case.name -eq 'regular-file') { Assert-True ($message.Contains('原始返回值=""')) '空返回诊断不明确' }
                }
            } else { Assert-True (-not $message) "有效布局被误拒绝：$label：$message" }
            foreach ($receivedFile in (Get-ChildItem -LiteralPath $OutputDirectory -Filter ($label + '-' + $case.name + '*.received.sh'))) {
                $receivedCommand = [IO.File]::ReadAllText($receivedFile.FullName)
                $partition = [regex]::Match($receivedCommand, 'by-name/([a-z0-9]+)').Groups[1].Value
                $expectedCommand = $commandTemplate.Replace('__BYNAME__',$ByName).Replace('__NAME__',$partition)
                Assert-True ($receivedCommand -ceq $expectedCommand) "原生参数未逐字保留实际 shell 字符串：$($receivedFile.Name)"
            }
            [IO.File]::WriteAllText($env:D31_LAYOUT_TRANSCRIPT + '.result.txt', $message, $utf8)
            $results += [pscustomobject]@{source=$label;case=$case.name;passed=$true;error=$message}
            Write-Output "通过：$label/$($case.name)"
        }
    }
    $completed = $true
} finally {
    $report = [pscustomobject]@{passed=$completed;powershell=$PSVersionTable.PSVersion.ToString();legacyNativeArguments=$isLegacy;distribution=$WslDistribution;realAdb=$false;
        cases=$results;count=$results.Count;sources=@($sources | ForEach-Object { @{path=$_;sha256=(Get-FileHash -LiteralPath $_).Hash} })}
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'results.json') -Encoding UTF8
    Get-ChildItem -LiteralPath $OutputDirectory -File | ForEach-Object {
        "{0}`t{1}`t{2}" -f $_.Name,$_.Length,(Get-FileHash -LiteralPath $_.FullName).Hash
    } | Set-Content -LiteralPath (Join-Path $OutputDirectory 'artifacts_sha256.tsv') -Encoding UTF8
    foreach ($variable in @('D31_LAYOUT_DISTRO','D31_LAYOUT_FIXTURE','D31_LAYOUT_CASE','D31_LAYOUT_TRANSCRIPT')) {
        Remove-Item -LiteralPath "Env:$variable" -ErrorAction SilentlyContinue
    }
    Write-Output "测试证据：$OutputDirectory"
}
