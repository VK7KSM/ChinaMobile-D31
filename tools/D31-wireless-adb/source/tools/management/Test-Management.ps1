# 独立编译管理包及测试；所有生成物写入新证据目录，不运行Gradle或设备命令。
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Jdk,
    [Parameter(Mandatory = $true)][string]$AndroidJar,
    [Parameter(Mandatory = $true)][string]$JsonJar,
    [Parameter(Mandatory = $true)][string]$JunitJar,
    [Parameter(Mandatory = $true)][string]$HamcrestJar,
    [Parameter(Mandatory = $true)][string]$WebSource,
    [Parameter(Mandatory = $true)][string]$EvidenceParent
)
$ErrorActionPreference = 'Stop'
foreach ($file in @((Join-Path $Jdk 'bin/javac.exe'), (Join-Path $Jdk 'bin/java.exe'), $AndroidJar, $JsonJar, $JunitJar, $HamcrestJar, $WebSource)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "输入文件不存在：$file" }
}
$node = (Get-Command node -ErrorAction Stop).Source
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$evidence = Join-Path ([IO.Path]::GetFullPath($EvidenceParent)) "management-$stamp"
if (Test-Path -LiteralPath $evidence) { throw '证据目录已存在，拒绝覆盖' }
$classes = New-Item -ItemType Directory -Path (Join-Path $evidence 'classes')
$sources = @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'app/src/main/java/net/elfradio/d31bootstrap/management') -Filter '*.java')
$sources += @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'app/src/test/java/net/elfradio/d31bootstrap/management') -Filter '*.java')
$classpath = "$JsonJar;$AndroidJar;$JunitJar;$HamcrestJar"
$sources | Get-FileHash -Algorithm SHA256 | Select-Object Path, Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'source-hashes.json') -Encoding UTF8
function Invoke-Recorded([string]$Executable, [string[]]$Arguments, [string]$Log) {
    $prior = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Executable @Arguments 2>&1 | Tee-Object -FilePath (Join-Path $evidence $Log) | Out-Host
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prior }
    if ($code -ne 0) { throw "命令失败，退出码$code；原始日志：$evidence/$Log" }
}
Invoke-Recorded (Join-Path $Jdk 'bin/javac.exe') (@('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', $classpath, '-d', $classes.FullName) + @($sources.FullName)) 'javac.log'
Invoke-Recorded (Join-Path $Jdk 'bin/java.exe') @("-Dmanagement.evidence=$evidence", '-cp', "$($classes.FullName);$classpath", 'org.junit.runner.JUnitCore', 'net.elfradio.d31bootstrap.management.SystemManagementTest') 'junit.log'
Invoke-Recorded $node @('--check', (Join-Path $PSScriptRoot 'Test-WebContract.mjs')) 'node-check.log'
Invoke-Recorded $node @((Join-Path $PSScriptRoot 'Test-WebContract.mjs'), $WebSource, (Join-Path $evidence 'java-success.json')) 'web-contract.log'
Write-Output "独立检查通过；证据目录：$evidence"
