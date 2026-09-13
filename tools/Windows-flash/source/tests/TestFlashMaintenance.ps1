param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputDirectory) { throw '测试输出已存在，拒绝覆盖' }
$sandbox = Join-Path $OutputDirectory 'runtime'
New-Item -ItemType Directory -Path (Join-Path $sandbox 'tools') -Force | Out-Null
$source = Join-Path $PSScriptRoot '../../d31/factory_package/flash_d31_recovery.ps1'
Copy-Item (Join-Path $PSScriptRoot '../../d31/factory_package/approved-package.json') $sandbox
[IO.File]::WriteAllText((Join-Path $sandbox 'installed-files.json'), '[]')
$csc = "$env:SystemRoot/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
& $csc /nologo /reference:System.Web.Extensions.dll ("/out:" + (Join-Path $sandbox 'tools/adb.exe')) (Join-Path $PSScriptRoot 'BackendFakeAdb.cs')
if ($LASTEXITCODE) { throw '模拟ADB编译失败' }
$tokens=$null; $errors=$null
$ast = [System.Management.Automation.Language.Parser]::ParseInput([IO.File]::ReadAllText($source),[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw '生产后端语法错误' }
foreach ($name in @('Add-SessionLog','Invoke-Adb','Get-DeviceValue','Get-CheckedDeviceValue','Assert-NoActiveRepair','Assert-LegacyFlashDeployment','Enter-FlashMaintenance')) {
    $definition = $ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name}, $true)
    if (-not $definition) { throw "缺少生产函数：$name" }
    Invoke-Expression $definition.Extent.Text
}
$Adb = Join-Path $sandbox 'tools/adb.exe'
$AdbPort = 5042
$Serial = '192.0.2.31:5555'
$SessionLog = $null
$results = @()
try {
    foreach ($case in @(
        @('success',$true,$false), @('legacy95',$true,$false),
        @('full96-success',$true,$true), @('full96-system',$true,$true),
        @('full170-no-health',$false,$false), @('full170-old-health',$false,$false),
        @('full170-pm-no-health',$false,$false), @('legacy-evidence-unreadable',$false,$false),
        @('legacy-pm-unknown',$false,$false), @('legacy-pm-absent',$true,$false),
        @('stock-pm-unavailable',$false,$false), @('stock-pm-inconsistent',$false,$false), @('stock-active-present',$false,$false),
        @('legacy-active-present',$false,$false), @('legacy-version-mismatch',$false,$false),
        @('health-invalid',$false,$false), @('full96-no-protocol',$false,$false),
        @('full96-bad-path',$false,$false), @('full96-reserve-failed',$false,$false),
        @('full96-reserve-unknown',$false,$false)
    )) {
        $env:D31_TEST_CASE = $case[0]
        $env:D31_TEST_TRANSCRIPT = Join-Path $OutputDirectory ($case[0]+'.commands.txt')
        $errorText = $null; $reservation = $null
        try { $reservation = Enter-FlashMaintenance } catch { $errorText = $_.Exception.Message }
        if (($null -eq $errorText) -ne $case[1]) { throw "用例结果不符：$($case[0])：$errorText" }
        if ($case[1] -and ($null -ne $reservation) -ne $case[2]) { throw "预留结果不符：$($case[0])" }
        $commands = Get-Content -LiteralPath $env:D31_TEST_TRANSCRIPT -Raw
        if ($commands -match 'reboot|/cache/recovery/command|\spush\s') { throw '窄测试越过写入门' }
        if ($case[0] -match '^(full170|legacy-)' -and $commands -match 'RemoteWindowsMaintenance') { throw '未知状态触发维护任务' }
        $results += [pscustomobject]@{name=$case[0];passed=$true;rejected=($null -ne $errorText);error=$errorText}
        Write-Output "通过：$($case[0])"
    }
} finally {
    Remove-Item Env:D31_TEST_CASE -ErrorAction SilentlyContinue
    Remove-Item Env:D31_TEST_TRANSCRIPT -ErrorAction SilentlyContinue
}
[pscustomobject]@{passed=$true;count=$results.Count;cases=$results;sourceSha256=(Get-FileHash $source).Hash;realAdb=$false} |
    ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutputDirectory 'maintenance-results.json') -Encoding UTF8
$global:LASTEXITCODE = 0
