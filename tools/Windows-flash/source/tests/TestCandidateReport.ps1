[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Builder,
    [Parameter(Mandatory=$true)][string]$BaselineBuilder,
    [Parameter(Mandatory=$true)][string]$ObjectDirectory,
    [Parameter(Mandatory=$true)][string]$MetadataPath,
    [Parameter(Mandatory=$true)][string]$Executable,
    [Parameter(Mandatory=$true)][string]$FirmwarePackage,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$CommonModule=(Join-Path $PSScriptRoot '../tools/BasicProbe.Common.psm1'),
    [ValidateRange(5,60)][int]$TimeoutSeconds=30,
    [switch]$Worker
)
$ErrorActionPreference='Stop'
if($Worker){
    # Start-Process继承PowerShell 7模块路径；仅在隔离子进程使用Windows PowerShell自己的系统模块。
    $env:PSModulePath="$PSHOME\Modules"
}
trap {
    if($Worker){[Console]::Error.WriteLine($_.Exception.Message);[Console]::Error.WriteLine($_.ScriptStackTrace)}
    break
}
foreach($name in @('Builder','BaselineBuilder','ObjectDirectory','MetadataPath','Executable','FirmwarePackage','CommonModule')){
    Set-Variable -Name $name -Value (Resolve-Path -LiteralPath (Get-Variable -Name $name -ValueOnly)).Path
}
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
if(!$Worker){
    if(Test-Path -LiteralPath $OutputDirectory){throw 'REPORT_TEST_OUTPUT_EXISTS'}
    New-Item -ItemType Directory -Path $OutputDirectory -ErrorAction Stop | Out-Null
    $sources=Join-Path $OutputDirectory 'sources'
    New-Item -ItemType Directory -Path $sources | Out-Null
    Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $sources 'TestCandidateReport.ps1')
    Copy-Item -LiteralPath $Builder -Destination (Join-Path $sources 'builder-after.ps1')
    Copy-Item -LiteralPath $BaselineBuilder -Destination (Join-Path $sources 'builder-before.ps1')
    Copy-Item -LiteralPath $CommonModule -Destination (Join-Path $sources 'BasicProbe.Common.psm1')
    $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+(Join-Path $sources 'TestCandidateReport.ps1')+'"'),'-Worker')
    $parameters=[ordered]@{
        Builder=(Join-Path $sources 'builder-after.ps1'); BaselineBuilder=(Join-Path $sources 'builder-before.ps1')
        CommonModule=(Join-Path $sources 'BasicProbe.Common.psm1'); ObjectDirectory=$ObjectDirectory
        MetadataPath=$MetadataPath; Executable=$Executable; FirmwarePackage=$FirmwarePackage; OutputDirectory=$OutputDirectory
    }
    foreach($entry in $parameters.GetEnumerator()){$arguments+=@('-'+$entry.Key,('"'+$entry.Value+'"'))}
    $process=Start-Process -FilePath "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" -ArgumentList $arguments `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $OutputDirectory 'stdout.log') `
        -RedirectStandardError (Join-Path $OutputDirectory 'stderr.log')
    try{
        if(!$process.WaitForExit($TimeoutSeconds*1000)){
            if(!$process.HasExited){$process.Kill();$process.WaitForExit()}
            throw 'REPORT_TEST_TIMEOUT'
        }
        $process.Refresh()
        if($process.ExitCode -ne 0){throw ('REPORT_TEST_FAILED: '+[IO.File]::ReadAllText((Join-Path $OutputDirectory 'stderr.log')))}
    }finally{$process.Dispose()}
    $summary=[IO.File]::ReadAllText((Join-Path $OutputDirectory 'test-result.json')) | ConvertFrom-Json
    if($summary.status -ne 'passed'){throw 'REPORT_TEST_RESULT_MISSING'}
    $summary
    return
}
if($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1){throw 'REPORT_TEST_REQUIRES_PS51'}
$clock=[Diagnostics.Stopwatch]::StartNew()
$checks=New-Object 'Collections.Generic.List[string]'
function Check([string]$Name,[bool]$Pass){if(!$Pass){throw "REPORT_ASSERT_$Name"};$checks.Add($Name)}
function Read-Ast([string]$Path){
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseInput([IO.File]::ReadAllText($Path),[ref]$tokens,[ref]$errors)
    if($errors.Count){throw 'REPORT_BUILDER_PARSE_FAILED'}
    return $ast
}
function Report-Assignment($Ast){
    $found=@($Ast.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] `
        -and $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and $node.Left.VariablePath.UserPath -ceq 'result'},$true))
    if($found.Count -ne 1){throw 'REPORT_ASSIGNMENT_NOT_UNIQUE'}
    return $found[0]
}
$before=Report-Assignment (Read-Ast $BaselineBuilder)
$afterAst=Read-Ast $Builder
$after=Report-Assignment $afterAst
$writerCalls=@($afterAst.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -ceq 'Write-ProbeJson'},$true))
if($writerCalls.Count -ne 1){throw 'REPORT_WRITER_NOT_UNIQUE'}
$reportFields=@()
$table=$before.Find({param($node) $node -is [Management.Automation.Language.HashtableAst]},$true)
foreach($pair in $table.KeyValuePairs){if($pair.Item2.Extent.Text -match 'Get-Content -Tail 1 -LiteralPath'){$reportFields+=([string]$pair.Item1.SafeGetValue())}}
Check 'four-legacy-report-fields' ($reportFields.Count -eq 4)
$objectSource=$ObjectDirectory
$reports=Join-Path $objectSource 'test-reports'
$runtime=Join-Path $objectSource 'runtime'
$output=$Executable
$metadata=[IO.File]::ReadAllText($MetadataPath) | ConvertFrom-Json
$basic=@($metadata.artifacts | Where-Object artifact -CEQ basic)[0]
$full=@($metadata.artifacts | Where-Object artifact -CEQ full)[0]
$distFiles=@(Get-ChildItem -LiteralPath (Split-Path -Parent $output) -File)
$runtimeSources=@(Get-ChildItem -LiteralPath $runtime -Recurse -File)
$packageItem=Get-Item -LiteralPath $FirmwarePackage
$packageHash=(Get-FileHash -LiteralPath $FirmwarePackage).Hash
$approved=[IO.File]::ReadAllText((Join-Path $runtime 'approved-package.json')) | ConvertFrom-Json
Check 'frozen-firmware-identity' ($packageItem.Length -eq $approved.bytes -and $packageHash -ieq $approved.sha256)
$rescueRestoreHash=(Get-FileHash -LiteralPath (Join-Path $runtime 'rescue/D31_RESCUE_UPDATE.zip')).Hash
$rescueTestHash=(Get-FileHash -LiteralPath (Join-Path $runtime 'rescue/D31_RESCUE_TEST.zip')).Hash
$aria2Hash=(Get-FileHash -LiteralPath (Join-Path $runtime 'tools/aria2c.exe')).Hash
# 从实际构建器提取报告路径赋值，不执行构建步骤。
$reportDirectory=$reports
foreach($variable in @('selfTestReport','packageReport','downloadParserReport','compatibilityReport')){
    $assignment=$afterAst.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] `
        -and $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and $node.Left.VariablePath.UserPath -ceq $variable},$true)
    if($null -eq $assignment){throw 'REPORT_PATH_ASSIGNMENT_MISSING'}
    . ([scriptblock]::Create($assignment.Extent.Text))
}
$oldResult=& ([scriptblock]::Create($before.Extent.Text+"`n"+'$result'))
$legacyEvidence=New-Object 'Collections.Generic.List[object]'
foreach($field in $reportFields){
    $oldValue=$oldResult.PSObject.Properties[$field].Value
    Check ('legacy-provider-rejected-'+$legacyEvidence.Count) ($null -ne $oldValue.PSObject.Properties['PSProvider'])
    # 仅用浅层序列化证明旧字段格式错误，不重跑旧提供程序对象的深层展开。
    $shallow=([pscustomobject]@{text=$oldValue} | ConvertTo-Json -Depth 1) | ConvertFrom-Json
    Check ('legacy-json-not-string-'+$legacyEvidence.Count) (!($shallow.text -is [string]))
    $legacyEvidence.Add([ordered]@{field=$field;properties=@($oldValue.PSObject.Properties.Name);shallow=$shallow})
}
$objectDirectory=Join-Path $OutputDirectory 'derived'
New-Item -ItemType Directory -Path $objectDirectory | Out-Null
Import-Module $CommonModule -Force
$actual=& ([scriptblock]::Create($after.Extent.Text+"`n"+$writerCalls[0].Extent.Text+"`n"+'$result'))
$jsonPath=Join-Path $objectDirectory 'candidate-result.json'
$json=[IO.File]::ReadAllText($jsonPath) | ConvertFrom-Json
foreach($field in $reportFields){
    $actualValue=$actual.PSObject.Properties[$field].Value
    Check ('new-plain-string-'+$field) ($actualValue -is [string] -and $null -eq $actualValue.PSObject.Properties['PSProvider'] -and $null -eq $actualValue.PSObject.Properties['PSDrive'] -and $null -eq $actualValue.PSObject.Properties['PSPath'])
    $encoded=$json.PSObject.Properties[$field].Value
    Check ('new-json-string-'+$field) ($encoded -is [string])
    Check ('content-preserved-'+$field) ([string]::Equals($encoded,$oldResult.PSObject.Properties[$field].Value.ToString(),[StringComparison]::Ordinal))
}
Check 'no-provider-properties-in-json' (!([IO.File]::ReadAllText($jsonPath) -match '"PS(?:Provider|Drive|Path)"'))
Check 'bounded-report-size' ((Get-Item -LiteralPath $jsonPath).Length -lt 32768)
$clock.Stop()
$summary=[ordered]@{status='passed';powershell=$PSVersionTable.PSVersion.ToString();checks=$checks.Count;names=$checks.ToArray();elapsedMs=$clock.ElapsedMilliseconds;
    legacyRejectedFields=4;legacyDeepSerializationExecuted=$false;actualBuilderAssignmentExecuted=$true;actualWriterExecuted=$true;
    report=$jsonPath;executableSha256=(Get-FileHash -LiteralPath $Executable).Hash.ToLowerInvariant();
    builderSha256=(Get-FileHash -LiteralPath $Builder).Hash.ToLowerInvariant();recompiled=$false;backendRetested=$false;deviceOperations=0}
Write-ProbeJson (Join-Path $OutputDirectory 'legacy-shape-evidence.json') @($legacyEvidence.ToArray())
Write-ProbeJson (Join-Path $OutputDirectory 'test-result.json') $summary
'REPORT_TEST_PASS'
