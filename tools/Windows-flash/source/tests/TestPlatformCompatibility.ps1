param([Parameter(Mandatory=$true)][string]$OutputDirectory, [string]$ConstantsPath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (Test-Path -LiteralPath $OutputDirectory) { throw '测试目录已存在，禁止覆盖' }
$null = New-Item -ItemType Directory -Path $OutputDirectory
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$root = Split-Path -Parent $PSScriptRoot
$backup = Join-Path $root 'scripts/create_d31_rescue.ps1'
if ($ConstantsPath) {
    $constants = (Resolve-Path -LiteralPath $ConstantsPath -ErrorAction Stop).Path
} else {
    $constants = Join-Path $root 'obj-v1.6.11/BuildConstants.g.cs'
    if (-not (Test-Path -LiteralPath $constants)) { $constants = Join-Path $root 'obj-v1.6.10/BuildConstants.g.cs' }
    if (-not (Test-Path -LiteralPath $constants)) { $constants = Join-Path $root 'obj-v1.6.9/BuildConstants.g.cs' }
    if (-not (Test-Path -LiteralPath $constants)) { $constants = Join-Path $root 'src/BuildConstants.g.cs' }
}
$testSource = Join-Path $PSScriptRoot 'PlatformCompatibilityTests.cs'
$reportPath = Join-Path $OutputDirectory 'platform-compatibility-results.json'
$results = New-Object 'System.Collections.Generic.List[object]'
$sourceRecords = @()
$failure = $null
$csharp = $null
try {
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src') -Filter '*.cs' -File |
        Where-Object Name -NE 'BuildConstants.g.cs' | ForEach-Object FullName)
    $sources += @($constants, $testSource)
    $sourceRecords = @(@($sources) + @($backup, $PSCommandPath) | ForEach-Object {
        [pscustomobject]@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash}
    })
    $compiler = Join-Path $env:SystemRoot 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    $exe = Join-Path $OutputDirectory 'PlatformCompatibilityTests.exe'
    $arguments = @('/nologo','/target:exe','/main:PlatformCompatibilityTests',
        '/reference:System.dll','/reference:System.Core.dll','/reference:System.Web.Extensions.dll',
        '/reference:System.Drawing.dll','/reference:System.Windows.Forms.dll',("/out:$exe"))
    $compilerOutput = & $compiler @arguments @sources 2>&1
    $compileExit = $LASTEXITCODE
    $compilerOutput | Out-File -LiteralPath (Join-Path $OutputDirectory '编译记录.txt') -Encoding UTF8
    if ($compileExit -ne 0) { throw '实际生产源码编译失败，详见编译记录' }
    $csharpPath = Join-Path $OutputDirectory 'csharp-results.json'
    & $exe $csharpPath
    $testExit = $LASTEXITCODE
    $csharp = Get-Content -Raw -LiteralPath $csharpPath -Encoding UTF8 | ConvertFrom-Json
    if ($testExit -ne 0 -or -not $csharp.passed) { throw '实际C#平台方法测试失败' }

    $tokens = $null; $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($backup,[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw '备份脚本语法错误' }
    $tries = @($ast.EndBlock.Statements | Where-Object { $_ -is [System.Management.Automation.Language.TryStatementAst] })
    if ($tries.Count -ne 1) { throw '无法唯一识别备份主try，拒绝执行' }
    $statements = @($tries[0].Body.Statements)
    $boundary = @($statements | Where-Object {
        $_ -is [System.Management.Automation.Language.ForEachStatementAst] -and
        $_.Condition.Extent.Text -eq '$ExpectedSizes.Keys'
    })
    if ($boundary.Count -ne 1) { throw '无法唯一识别分区检查边界，拒绝执行' }
    $selected = @($statements | Where-Object { $_.Extent.StartOffset -lt $boundary[0].Extent.StartOffset })
    $text = ($selected | ForEach-Object { $_.Extent.Text }) -join "`r`n"
    $segment = [scriptblock]::Create($text)
    # 仅允许已模拟的入口及纯输出命令；源脚本增加其他命令时必须重新审查。
    $commands = @($segment.Ast.FindAll({param($node) $node -is [System.Management.Automation.Language.CommandAst]},$true))
    foreach ($command in $commands) {
        if ($command.GetCommandName() -cnotin @('Write-Host','Invoke-Adb','Out-Null','Get-DeviceValue') -or
            $command.Redirections.Count -ne 0) { throw '只读片段包含未批准命令或重定向' }
    }
    $methods = @($segment.Ast.FindAll({param($node) $node -is [System.Management.Automation.Language.InvokeMemberExpressionAst]},$true))
    foreach ($method in $methods) {
        if ($method.Member.Extent.Text -cnotin @('Trim','Substring','LastIndexOf','Escape')) {
            throw '只读片段包含未批准的方法调用'
        }
    }
    $constant = @($ast.EndBlock.Statements | Where-Object {
        $_ -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $_.Left.Extent.Text -eq '$ExpectedFingerprint'
    })
    if ($constant.Count -ne 1) { throw '无法唯一读取参考指纹' }
    $literal = @($constant[0].Right.FindAll({param($node)
        $node -is [System.Management.Automation.Language.StringConstantExpressionAst]
    },$true))
    if ($literal.Count -ne 1 -or $constant[0].Right.Extent.Text -cne $literal[0].Extent.Text) {
        throw '参考指纹不是单个字符串常量'
    }
    $referenceFingerprint = $literal[0].Value
    $bindings = @($ast.FindAll({param($node)
        $node -is [System.Management.Automation.Language.ExpandableStringExpressionAst] -and
        $node.Value -eq 'target_fingerprint=$fingerprint'
    },$true))
    if ($bindings.Count -ne 1) { throw '备份清单未唯一绑定实际fingerprint变量' }
    $bindingExpression = [scriptblock]::Create($bindings[0].Extent.Text)
    $text | Out-File -LiteralPath (Join-Path $OutputDirectory '备份只读准入片段.txt') -Encoding UTF8
    $cases = @(
        @{name='原厂指纹';model='hct6737t_66_m0';device='hct6735_66_m0';fingerprint=$referenceFingerprint;reject=$false},
        @{name='第三方指纹旧设备名';model='hct6737t_66_m0';device='hct6735_66_m0';fingerprint='offline/vendor/build:6.0/custom';reject=$false},
        @{name='第三方指纹同型号设备名';model='hct6737t_66_m0';device='hct6737t_66_m0';fingerprint='offline/vendor/another:6.0/custom';reject=$false},
        @{name='错误型号';model='other';device='hct6735_66_m0';reject=$true},
        @{name='错误设备名';model='hct6737t_66_m0';device='other';reject=$true},
        @{name='空型号';model='';device='hct6735_66_m0';reject=$true},
        @{name='空设备名';model='hct6737t_66_m0';device='';reject=$true},
        @{name='缺失型号';model=$null;device='hct6735_66_m0';reject=$true},
        @{name='缺失设备名';model='hct6737t_66_m0';device=$null;reject=$true},
        @{name='型号大小写变化';model='HCT6737T_66_M0';device='hct6735_66_m0';reject=$true},
        @{name='设备名大小写变化';model='hct6737t_66_m0';device='HCT6735_66_M0';reject=$true}
    )
    foreach ($case in $cases) {
        if (-not $case.ContainsKey('fingerprint')) { $case.fingerprint = 'offline/vendor/build:6.0/custom' }
        $result = & {
            $Serial = '192.0.2.31:5555'
            $ExpectedFingerprint = $referenceFingerprint
            $fingerprint = $null
            $calls = New-Object 'System.Collections.Generic.List[string]'
            $messages = New-Object 'System.Collections.Generic.List[string]'
            function Write-Host { $messages.Add(($args -join ' ')) }
            function Invoke-Adb {
                $command = $args -join ' '
                $calls.Add("模拟ADB：$command")
                if ($command -ceq "connect $Serial") { return '模拟连接' }
                if ($command -ceq "-s $Serial get-state") { return 'device' }
                throw "禁止的模拟ADB请求：$command"
            }
            function Get-DeviceValue([string]$Command) {
                $calls.Add("模拟只读：$Command")
                switch -CaseSensitive ($Command) {
                    'id' { return 'uid=0(root) gid=0(root)' }
                    'getprop ro.build.fingerprint' { return $case.fingerprint }
                    'getprop ro.product.model' { return $case.model }
                    'getprop ro.product.device' { return $case.device }
                    'ip -4 addr show dev eth0' { return 'inet 192.0.2.31/24' }
                    default { throw "禁止的设备请求：$Command" }
                }
            }
            $errorText = $null; $binding = $null
            try {
                . $segment
                $binding = & $bindingExpression
            } catch { $errorText = $_.Exception.Message }
            $rejected = $null -ne $errorText
            $ok = $rejected -eq $case.reject
            if ($case.reject) { $ok = $ok -and $errorText -like '*产品平台不受支持*' }
            else {
                $ok = $ok -and $fingerprint -ceq $case.fingerprint -and
                    $binding -ceq ('target_fingerprint=' + $case.fingerprint)
                $warned = @($messages | Where-Object { $_ -like '提示：*' }).Count -gt 0
                $ok = $ok -and ($warned -eq ($case.fingerprint -cne $referenceFingerprint))
            }
            [pscustomobject]@{name=$case.name;passed=[bool]$ok;rejected=$rejected;error=$errorText;
                manifestBinding=$binding;messages=@($messages.ToArray());commands=@($calls.ToArray())}
        }
        $results.Add($result)
    }
    if (@($results | Where-Object { -not $_.passed }).Count) { throw '备份只读准入测试失败' }
} catch { $failure = $_.Exception.Message }
finally {
    [pscustomobject]@{passed=($null -eq $failure);error=$failure;csharp=$csharp;
        backupCases=@($results.ToArray());sources=$sourceRecords;deviceOperations=0;networkOperations=0;
        note='只编译并调用平台纯方法；备份入口由AST截取，ADB及属性读取全部模拟；未执行分区检查、备份写入或真实设备命令。'} |
        ConvertTo-Json -Depth 8 | Out-File -LiteralPath $reportPath -Encoding UTF8
}
if ($failure) { throw "离线测试失败：$failure；报告：$reportPath" }
Write-Output "通过：C#平台方法$($csharp.checks)项，备份准入$($results.Count)项；报告：$reportPath"
