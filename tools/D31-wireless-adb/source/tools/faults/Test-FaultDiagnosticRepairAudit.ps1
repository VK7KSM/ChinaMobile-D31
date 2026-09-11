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
$output = Join-Path $OutputRoot ('fault-diagnostic-repair-audit-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
if (Test-Path -LiteralPath $output) { throw '证据目录已存在，拒绝覆盖' }
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
$sourceFiles = @()
$testNames = @()
foreach ($kind in @('main', 'test')) {
    foreach ($package in @('faults', 'diagnostics', 'repair')) {
        $relative = "app/src/$kind/java/net/elfradio/d31bootstrap/$package"
        $destination = Join-Path $output "source/$relative"
        New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $sourceRoot $relative) -Destination $destination -Recurse
        $sourceFiles += @(Get-ChildItem -LiteralPath $destination -Recurse -Filter '*.java')
        if ($kind -eq 'test') {
            foreach ($file in @(Get-ChildItem -LiteralPath $destination -Recurse -Filter '*Test.java')) {
                $tail = [IO.Path]::GetRelativePath($destination, $file.FullName).Replace('\', '.').Replace('/', '.')
                $testNames += 'net.elfradio.d31bootstrap.' + $package + '.' + $tail.Substring(0, $tail.Length - 5)
            }
        }
    }
}
$runner = Join-Path $output 'source/FaultTestRunner.java'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'FaultTestRunner.java') -Destination $runner
$sourceFiles += Get-Item -LiteralPath $runner
$sourceFiles | ForEach-Object {
    [pscustomobject]@{ path=[IO.Path]::GetRelativePath($output, $_.FullName); bytes=$_.Length;
        sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'source-manifest.json') -Encoding utf8
$classPath = @($json, $junit, $hamcrest, $AndroidJar) -join [IO.Path]::PathSeparator
$arguments = @('--release', '8', '-encoding', 'UTF-8', '-cp', $classPath, '-d', $classes.FullName) + @($sourceFiles.FullName)
& (Join-Path $Jdk 'bin/javac.exe') @arguments *>&1 | Tee-Object -FilePath (Join-Path $output 'compile.log')
if ($LASTEXITCODE -ne 0) { throw "独立编译失败，证据：$output" }
& (Join-Path $Jdk 'bin/java.exe') "-Djava.io.tmpdir=$($temporary.FullName)" '-cp' ($classes.FullName + [IO.Path]::PathSeparator + $classPath) `
    'net.elfradio.d31bootstrap.faults.FaultTestRunner' @testNames *>&1 | Tee-Object -FilePath (Join-Path $output 'junit.log')
$testExit = $LASTEXITCODE
Write-Output "独立证据：$output"
if ($testExit -ne 0) { throw '离线回归失败，保留全部夹具和日志' }
