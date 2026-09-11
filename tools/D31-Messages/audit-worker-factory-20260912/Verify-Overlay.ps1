#requires -Version 7.0
param(
    [Parameter(Mandatory=$true)][string]$UpstreamRepository,
    [Parameter(Mandatory=$true)][string]$OverlayPackage,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$JavaDirectory,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$GradleExecutable,
    [Parameter(Mandatory=$true)][string]$RobolectricDirectory
)
$ErrorActionPreference='Stop'
$baseline='555b8822c654b8ee85bb9d3f961eb30f232b1079'
$repository=(Resolve-Path -LiteralPath $UpstreamRepository).Path
$overlayPackagePath=(Resolve-Path -LiteralPath $OverlayPackage).Path
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw '输出目录必须全新'}
foreach($protected in @($repository,$overlayPackagePath,$PSScriptRoot)){
    if($output.StartsWith($protected+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){
        throw '输出不得位于源码、覆盖层或交付目录内'
    }
}
foreach($required in @((Join-Path $JavaDirectory 'bin/java.exe'),(Join-Path $AndroidSdk 'platforms/android-34/android.jar'),$GradleExecutable,$RobolectricDirectory)){
    if(-not(Test-Path -LiteralPath $required)){throw '缺少本地工具或依赖缓存；不自动下载'}
}
$manifestPath=Join-Path $overlayPackagePath 'source-files.json'
$entries=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$overlay=Join-Path $overlayPackagePath 'overlay'
foreach($entry in $entries){
    $relative=[string]$entry.'路径'
    if([IO.Path]::IsPathRooted($relative) -or $relative -match '(^|[/\\])\.\.([/\\]|$)'){
        throw '覆盖层清单路径越界'
    }
    $file=Get-Item -LiteralPath (Join-Path $overlay $relative)
    if($file.Length -ne $entry.'字节数' -or (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne $entry.SHA256){
        throw ('公开覆盖层与清单不一致：'+$relative)
    }
}
& git -C $repository cat-file -e ($baseline+'^{commit}')
if($LASTEXITCODE -ne 0){throw '缺少冻结上游Git对象；不下载'}
[void](New-Item -ItemType Directory -Path $output)
$snapshot=Join-Path $output 'source'
[void](New-Item -ItemType Directory -Path $snapshot)
$archive=Join-Path $output 'upstream.tar'
& git -C $repository archive --format=tar ('--output='+$archive) $baseline
if($LASTEXITCODE -ne 0){throw '导出上游对象失败'}
& tar -xf $archive -C $snapshot
if($LASTEXITCODE -ne 0){throw '解包上游对象失败'}
foreach($entry in $entries){
    $target=Join-Path $snapshot $entry.'路径'
    [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target))
    Copy-Item -LiteralPath (Join-Path $overlay $entry.'路径') -Destination $target
}
Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $output 'overlay-source-files.json')
$deltaPaths=@(
    'data/src/main/java/com/moez/QKSMS/worker/InjectionWorkerFactory.kt',
    'presentation/src/test/java/com/moez/QKSMS/worker/InjectionWorkerFactoryTest.kt'
)
foreach($relative in $deltaPaths){
    $target=Join-Path $snapshot $relative
    [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target))
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $relative) -Destination $target
}
# 禁止从当前开发工作树复制任何其它文件；补丁只包含测试配置。
Push-Location $snapshot
try {
    & git apply --check -- (Join-Path $PSScriptRoot 'test-config.patch')
    if($LASTEXITCODE -ne 0){throw '最小测试配置补丁不匹配0.4.0'}
    & git apply -- (Join-Path $PSScriptRoot 'test-config.patch')
    if($LASTEXITCODE -ne 0){throw '应用测试配置补丁失败'}
} finally { Pop-Location }
$sourceHashes=Get-ChildItem -LiteralPath $snapshot -File -Recurse | ForEach-Object {
    [ordered]@{path=[IO.Path]::GetRelativePath($snapshot,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
}
$sourceHashes | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'source-sha256.json') -Encoding utf8
[ordered]@{baseline=$baseline;overlayManifestSha256=(Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash;overlayFiles=@($entries).Count;deltaFiles=$deltaPaths;configurationPatch='test-config.patch';apkBuilt=$false;deviceUsed=$false} |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'inputs.json') -Encoding utf8
$oldJava=$env:JAVA_HOME; $oldAndroid=$env:ANDROID_HOME; $oldSdk=$env:ANDROID_SDK_ROOT
try {
    $env:JAVA_HOME=$JavaDirectory; $env:ANDROID_HOME=$AndroidSdk; $env:ANDROID_SDK_ROOT=$AndroidSdk
    Push-Location $snapshot
    try {
        $versionOutput=& $GradleExecutable --offline --version 2>&1
        $versionOutput | Set-Content -LiteralPath (Join-Path $output 'gradle-version.txt') -Encoding utf8
        if($LASTEXITCODE -ne 0 -or ($versionOutput -join "`n") -notmatch '(?m)^Gradle 8\.2\s*$'){
            throw '要求本地已缓存Gradle 8.2'
        }
        & $GradleExecutable --offline --no-daemon --max-workers=2 '-Dorg.gradle.caching=false' `
            '-Pkotlin.compiler.execution.strategy=in-process' ('-ProbolectricDependencyDir='+$RobolectricDirectory) `
            :presentation:testDebugUnitTest --tests 'dev.octoshrimpy.quik.worker.InjectionWorkerFactoryTest' `
            2>&1 | Tee-Object -FilePath (Join-Path $output 'build-test.txt')
        $buildExit=$LASTEXITCODE
    } finally { Pop-Location }
} finally { $env:JAVA_HOME=$oldJava; $env:ANDROID_HOME=$oldAndroid; $env:ANDROID_SDK_ROOT=$oldSdk }
$report=Join-Path $snapshot 'presentation/build/test-results/testDebugUnitTest/TEST-dev.octoshrimpy.quik.worker.InjectionWorkerFactoryTest.xml'
$suite=if(Test-Path -LiteralPath $report){([xml](Get-Content -LiteralPath $report -Raw)).testsuite}else{$null}
$passed=$buildExit -eq 0 -and $null -ne $suite -and [int]$suite.tests -eq 6 -and [int]$suite.failures -eq 0 -and [int]$suite.errors -eq 0 -and [int]$suite.skipped -eq 0
[ordered]@{baseline=$baseline;overlayVersion='0.4.0-dev';buildExit=$buildExit;tests=if($suite){[int]$suite.tests}else{0};failures=if($suite){[int]$suite.failures}else{0};errors=if($suite){[int]$suite.errors}else{0};skipped=if($suite){[int]$suite.skipped}else{0};passed=$passed;sharedBuildUsed=$false;apkBuilt=$false;deviceUsed=$false} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
if(-not $passed){throw '0.4.0工厂6项未通过，保留日志并停止，不迁入其它开发差异'}
