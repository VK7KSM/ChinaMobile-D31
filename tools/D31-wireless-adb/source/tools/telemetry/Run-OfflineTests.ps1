param(
    [string]$JavaHome,
    [string]$AndroidJar = 'C:/Dev/android-sdk/platforms/android-34/android.jar'
)
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$JavaHome) {
    $JavaHome = (Resolve-Path (Join-Path $sourceRoot '../d31_phone_sms/toolchain/temurin17/jdk-17.0.20.1+1')).Path
}
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Find-TestJar([string]$relative) {
    $matches = @(Get-ChildItem (Join-Path $cache $relative) -Recurse -Filter '*.jar')
    if ($matches.Count -ne 1) { throw "测试依赖数量不符：$relative" }
    return $matches[0].FullName
}
$jsonJar = Find-TestJar 'org.json/json/20240303'
$junitJar = Find-TestJar 'junit/junit/4.13.2'
$hamcrestJar = Find-TestJar 'org.hamcrest/hamcrest-core/1.3'
$runRoot = Join-Path $PSScriptRoot (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
New-Item -ItemType Directory -Path $runRoot -ErrorAction Stop | Out-Null
$classes = Join-Path $runRoot 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
$main = Join-Path $sourceRoot 'app/src/main/java/net/elfradio/d31bootstrap/telemetry'
$tests = Join-Path $sourceRoot 'app/src/test/java/net/elfradio/d31bootstrap/telemetry'
$sources = @((Get-ChildItem $main -Filter '*.java').FullName) + @((Get-ChildItem $tests -Filter '*.java').FullName)
$testClasses = @(Get-ChildItem $tests -Filter '*Test.java' | Sort-Object Name | ForEach-Object {
    'net.elfradio.d31bootstrap.telemetry.' + $_.BaseName
})
if ($testClasses.Count -eq 0) { throw '未找到telemetry测试入口' }
$compileClasspath = "$jsonJar;$AndroidJar;$junitJar;$hamcrestJar"
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -source 8 -target 8 -classpath $compileClasspath -d $classes $sources 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw "独立编译失败，记录：$runRoot" }
& (Join-Path $JavaHome 'bin/java.exe') -classpath "$classes;$jsonJar;$junitJar;$hamcrestJar" org.junit.runner.JUnitCore `
    @testClasses 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'junit.log')
if ($LASTEXITCODE -ne 0) { throw "独立测试失败，记录：$runRoot" }
$sources | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 } |
    Select-Object @{Name='文件';Expression={$_.Path.Substring($sourceRoot.Length + 1)}}, Hash |
    Export-Csv -LiteralPath (Join-Path $runRoot 'source-sha256.csv') -NoTypeInformation -Encoding UTF8
Write-Output "独立测试完成：$runRoot"
