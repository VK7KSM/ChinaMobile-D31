#requires -Version 7.2
param(
    [string]$Jdk = 'C:/Users/x/.jdks/jdk-17.0.20.1+1',
    [string]$AndroidJar = 'C:/Dev/android-sdk/platforms/android-34/android.jar',
    [string]$DependencyCache = 'C:/Users/x/.gradle/caches/modules-2/files-2.1',
    [string]$OutputRoot = 'C:/Dev/H13_D22/research/d31/staging'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = Join-Path $OutputRoot ('faults-offline-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
if (Test-Path -LiteralPath $output) { throw '测试输出已存在，拒绝覆盖' }
New-Item -ItemType Directory -Path $output | Out-Null
$classes = New-Item -ItemType Directory -Path (Join-Path $output 'classes')
$temporary = New-Item -ItemType Directory -Path (Join-Path $output 'temporary')
function Dependency([string]$Path, [string]$Name) {
    $items = @(Get-ChildItem -LiteralPath (Join-Path $DependencyCache $Path) -Recurse -File -Filter $Name)
    if ($items.Count -ne 1) { throw "测试依赖数量不符：$Name" }
    return $items[0].FullName
}
$junit = Dependency 'junit/junit/4.13.2' 'junit-4.13.2.jar'
$hamcrest = Dependency 'org.hamcrest/hamcrest-core/1.3' 'hamcrest-core-1.3.jar'
$json = Dependency 'org.json/json/20240303' 'json-20240303.jar'
$sources = @(
    Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'app/src/main/java/net/elfradio/d31bootstrap/diagnostics') -Recurse -Filter '*.java'
    Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'app/src/main/java/net/elfradio/d31bootstrap/faults') -Recurse -Filter '*.java'
    Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'app/src/test/java/net/elfradio/d31bootstrap/faults') -Recurse -Filter '*.java'
)
$classPath = @($json, $junit, $hamcrest, $AndroidJar) -join [IO.Path]::PathSeparator
$arguments = @('--release', '8', '-encoding', 'UTF-8', '-cp', $classPath, '-d', $classes.FullName) + @($sources.FullName)
& (Join-Path $Jdk 'bin/javac.exe') @arguments *>&1 | Tee-Object -FilePath (Join-Path $output 'compile.log')
if ($LASTEXITCODE -ne 0) { throw "独立编译失败，原件保留：$output" }
& (Join-Path $Jdk 'bin/java.exe') "-Djava.io.tmpdir=$($temporary.FullName)" '-cp' ($classes.FullName + [IO.Path]::PathSeparator + $classPath) `
    'org.junit.runner.JUnitCore' 'net.elfradio.d31bootstrap.faults.FaultMonitorTest' `
    'net.elfradio.d31bootstrap.faults.AndroidFaultSourcesTest' *>&1 | Tee-Object -FilePath (Join-Path $output 'junit.log')
$testExit = $LASTEXITCODE
$sources | ForEach-Object {
    [pscustomobject]@{ path=$_.FullName; bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'source-manifest.json') -Encoding utf8
Write-Output "离线证据：$output"
if ($testExit -ne 0) { throw '故障取证离线测试失败，未删除夹具与失败日志' }
