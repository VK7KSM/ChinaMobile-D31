param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$JsonJar,
    [Parameter(Mandatory=$true)][string]$JunitJar,
    [Parameter(Mandatory=$true)][string]$HamcrestJar,
    [Parameter(Mandatory=$true)][string]$PythonExe,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw '测试输出目录已存在，禁止覆盖' }
$null = New-Item -ItemType Directory -Path $output
$classes = Join-Path $output 'classes'
$null = New-Item -ItemType Directory -Path $classes
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$source = Join-Path $project 'app/src/main/java/net/elfradio/d31bootstrap/diagnostics'
$tests = Join-Path $project 'app/src/test/java/net/elfradio/d31bootstrap/diagnostics'
$inputs = @('DiagnosticContract','DiagnosticManifest','DiagnosticRules','DiagnosticComparator','DiagnosticCoverageComparison') | ForEach-Object { Join-Path $source ($_.ToString() + '.java') }
$inputs += Join-Path $tests 'DiagnosticCoverageComparisonTest.java'
$inputs += Join-Path $tests 'DiagnosticComparatorTest.java'
$cp = "$JsonJar;$JunitJar;$HamcrestJar"
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -source 8 -target 8 -cp $cp -d $classes $inputs 2>&1 | Tee-Object -FilePath "$output/javac.txt"
if ($LASTEXITCODE -ne 0) { throw '独立编译失败' }
& "$JavaHome/bin/java.exe" -Xmx512m -cp "$classes;$cp" org.junit.runner.JUnitCore net.elfradio.d31bootstrap.diagnostics.DiagnosticCoverageComparisonTest net.elfradio.d31bootstrap.diagnostics.DiagnosticComparatorTest 2>&1 | Tee-Object -FilePath "$output/junit.txt"
if ($LASTEXITCODE -ne 0) { throw '诊断测试失败' }
& $PythonExe -B "$PSScriptRoot/test_compare_baseline.py" -v 2>&1 | Tee-Object -FilePath "$output/python.txt"
if ($LASTEXITCODE -ne 0) { throw '宿主测试失败' }
$inputs += @("$PSScriptRoot/compare_baseline.py", "$PSScriptRoot/test_compare_baseline.py", "$PSScriptRoot/CoverageMain.java", $PSCommandPath)
$hashes = @($inputs | ForEach-Object { [ordered]@{ name = [IO.Path]::GetFileName($_); sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash } })
[IO.File]::WriteAllText("$output/inputs-sha256.json", ($hashes | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText("$output/result.json", '{"passed":true,"deviceAccess":false,"sharedGradle":false}', [Text.UTF8Encoding]::new($false))
