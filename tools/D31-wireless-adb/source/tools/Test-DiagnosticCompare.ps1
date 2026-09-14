param(
    [Parameter(Mandatory=$true)][string]$CompiledClasses,
    [Parameter(Mandatory=$true)][string]$JsonJar,
    [Parameter(Mandatory=$true)][string]$FixtureRecordPath,
    [Parameter(Mandatory=$true)][string]$CapturePath
)
$ErrorActionPreference='Stop'
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '诊断验收目录必须全新'}
New-Item -ItemType Directory -Path "$capture/classes" | Out-Null
$source=Join-Path $PSScriptRoot 'DiagnosticCompareMain.java'
Copy-Item -LiteralPath $source -Destination "$capture/DiagnosticCompareMain.java"
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -source 8 -target 8 -cp "$CompiledClasses;$JsonJar" -d "$capture/classes" $source *> "$capture/compile.log"
if($LASTEXITCODE -ne 0){throw '离线比较入口编译失败'}
$text=Get-Content -LiteralPath $FixtureRecordPath -Raw -Encoding UTF8
$blocks=[regex]::Matches($text,'(?s)```json\r?\n(.*?)\r?\n```')
if($blocks.Count -ne 2){throw '合成JSON样例段数不符'}
$board=$blocks[0].Groups[1].Value | ConvertFrom-Json
$rules=$blocks[1].Groups[1].Value | ConvertFrom-Json
$firmware=$board | ConvertTo-Json -Depth 20 | ConvertFrom-Json
$firmware.role='FIRMWARE'; $firmware.snapshotId='fixture-FIRMWARE'
$target=$board | ConvertTo-Json -Depth 20 | ConvertFrom-Json
$target.role='TARGET'; $target.snapshotId='fixture-TARGET'
$incomplete=$target | ConvertTo-Json -Depth 20 | ConvertFrom-Json
$incomplete.entries[0].fields.PSObject.Properties.Remove('sha256')
$invalid=$target | ConvertTo-Json -Depth 20 | ConvertFrom-Json
$invalid.schemaVersion=999
$fixtures=@{ 'board'=$board; 'firmware'=$firmware; 'rules'=$rules; 'target'=$target; 'incomplete'=$incomplete; 'invalid'=$invalid }
foreach($name in $fixtures.Keys){$fixtures[$name] | ConvertTo-Json -Depth 20 | Out-File -LiteralPath "$capture/$name.json" -Encoding UTF8 -NoClobber}
$classpath="$capture/classes;$CompiledClasses;$JsonJar"
foreach($name in @('target','incomplete','invalid')){
    & "$env:JAVA_HOME/bin/java.exe" -cp $classpath net.elfradio.d31bootstrap.DiagnosticCompareMain "$capture/board.json" "$capture/firmware.json" "$capture/$name.json" "$capture/rules.json" "$capture/report-$name.json" 10000 *> "$capture/run-$name.log"
    $expected=if($name -eq 'invalid'){2}else{0}
    if($LASTEXITCODE -ne $expected){throw "离线比较退出状态不符：$name"}
}
$match=Get-Content "$capture/report-target.json" -Raw | ConvertFrom-Json
$missing=Get-Content "$capture/report-incomplete.json" -Raw | ConvertFrom-Json
$rejected=Get-Content "$capture/report-invalid.json" -Raw | ConvertFrom-Json
if($match.offlineComparison -ne 'MATCH_WITHIN_SCOPE' -or $match.counts.same -ne 1){throw '完整合成样例未按范围匹配'}
if($missing.offlineComparison -ne 'INSUFFICIENT_EVIDENCE' -or $missing.counts.unchecked -ne 1 -or $missing.counts.same -ne 0){throw '缺证据被误判匹配'}
if($rejected.offlineComparison -ne 'NOT_COMPARED'){throw '非法合同未生成明确拒绝报告'}
if($match.systemConsistency -ne 'NOT_ASSESSED' -or $missing.systemConsistency -ne 'NOT_ASSESSED'){throw '离线结果越界为整机通过'}
$before=(Get-FileHash -LiteralPath "$capture/report-target.json").Hash
& "$env:JAVA_HOME/bin/java.exe" -cp $classpath net.elfradio.d31bootstrap.DiagnosticCompareMain "$capture/board.json" "$capture/firmware.json" "$capture/target.json" "$capture/rules.json" "$capture/report-target.json" 10000 *> "$capture/existing-report.log"
if($LASTEXITCODE -eq 0 -or (Get-FileHash -LiteralPath "$capture/report-target.json").Hash -ne $before){throw '已有报告被覆盖'}
[ordered]@{通过=$true;检查=@('范围内匹配','缺证据不一致','未知合同拒绝','原报告不覆盖');设备采集='未执行'} | ConvertTo-Json -Depth 4 | Out-File -LiteralPath "$capture/result.json" -Encoding UTF8 -NoClobber
Write-Output '离线比较CLI四项验收通过，原始报告已保留'
