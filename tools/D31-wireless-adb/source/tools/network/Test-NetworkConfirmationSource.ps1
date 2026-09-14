param(
    [string]$JavaDirectory = 'C:/Users/x/.jdks/jdk-17.0.20.1+1',
    [string]$AndroidJar = 'C:/Dev/android-sdk/platforms/android-34/android.jar',
    [string]$FrozenClasses,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$FrozenClasses) { $FrozenClasses = Join-Path $root 'app/build/intermediates/javac/fullRelease/compileFullReleaseJavaWithJavac/classes' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot ('source-offline-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
if (Test-Path -LiteralPath $OutputDirectory) { throw '输出目录必须全新' }
if (!(Test-Path -LiteralPath $FrozenClasses -PathType Container)) { throw '请指定本轮既有编译类目录；本脚本不运行Gradle' }
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$json = (Get-ChildItem (Join-Path $cache 'org.json/json') -Filter 'json-20240303.jar' -Recurse | Select-Object -First 1).FullName
$junit = (Get-ChildItem (Join-Path $cache 'junit/junit') -Filter 'junit-4.13.2.jar' -Recurse | Select-Object -First 1).FullName
$hamcrest = (Get-ChildItem (Join-Path $cache 'org.hamcrest/hamcrest-core') -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
foreach ($file in @((Join-Path $JavaDirectory 'bin/javac.exe'), $AndroidJar, $json, $junit, $hamcrest)) {
    if (!$file -or !(Test-Path -LiteralPath $file -PathType Leaf)) { throw '离线编译依赖缺失' }
}
$capture = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
$classes = (New-Item -ItemType Directory -Path (Join-Path $capture 'classes')).FullName
$main = Join-Path $root 'app/src/main/java/net/elfradio/d31bootstrap'
$test = Join-Path $root 'app/src/test/java/net/elfradio/d31bootstrap'
# RemoteProtocol仅链接冻结的BASE/localJobId，避免牵入其他任务正在编辑的生产接线。
$sources = @('RemoteNetworkConfirmationSource.java', 'RemoteNetworkConfirmationJson.java', 'RemoteHttp.java', 'RemoteTls.java',
    'management/NetworkConfirmationDispatch.java', 'management/NetworkRecoveryDispatch.java', 'management/NetworkChangeTransaction.java') |
    ForEach-Object { Get-Item -LiteralPath (Join-Path $main $_) }
$sources += Get-Item -LiteralPath (Join-Path $test 'RemoteNetworkConfirmationSourceTest.java')
$sources += Get-Item -LiteralPath (Join-Path $test 'RemoteHttpTest.java')
$snapshot = (New-Item -ItemType Directory -Path (Join-Path $capture 'source')).FullName
$frozenSources = @($sources | ForEach-Object {
    $destination = Join-Path $snapshot $_.Name
    Copy-Item -LiteralPath $_.FullName -Destination $destination
    Get-Item -LiteralPath $destination
})
$frozenSources | Get-FileHash -Algorithm SHA256 | Select-Object Path, Hash | Export-Csv (Join-Path $capture 'source-sha256.csv') -NoTypeInformation
@($json, $junit, $hamcrest, $AndroidJar, (Join-Path $FrozenClasses 'net/elfradio/d31bootstrap/RemoteProtocol.class'),
    (Join-Path $FrozenClasses 'net/elfradio/d31bootstrap/management/NetworkAndroidPlatform.class'),
    (Join-Path $FrozenClasses 'net/elfradio/d31bootstrap/management/NetworkAndroidPlatform$Maintenance.class')) |
    Get-FileHash -Algorithm SHA256 | Select-Object Path, Hash | Export-Csv (Join-Path $capture 'dependency-sha256.csv') -NoTypeInformation
$cp = @($classes, $json, $junit, $hamcrest, $AndroidJar, $FrozenClasses) -join [IO.Path]::PathSeparator
& (Join-Path $JavaDirectory 'bin/javac.exe') --release 8 -encoding UTF-8 -cp $cp -d $classes $frozenSources.FullName 2>&1 | Tee-Object (Join-Path $capture 'compile.txt')
if ($LASTEXITCODE -ne 0) { throw '独立编译失败' }
Copy-Item -LiteralPath (Join-Path $root 'app/src/main/resources/isrgrootx1.pem') -Destination $classes
& (Join-Path $JavaDirectory 'bin/java.exe') -cp $cp org.junit.runner.JUnitCore net.elfradio.d31bootstrap.RemoteNetworkConfirmationSourceTest net.elfradio.d31bootstrap.RemoteHttpTest 2>&1 | Tee-Object (Join-Path $capture 'junit.txt')
if ($LASTEXITCODE -ne 0) { throw '离线测试失败' }
Write-Output ('离线测试证据：' + $capture)
