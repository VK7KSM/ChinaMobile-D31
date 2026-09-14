param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$JavaDirectory,
    [Parameter(Mandatory=$true)][string]$AndroidJar,
    [Parameter(Mandatory=$true)][string]$JsonJar,
    [Parameter(Mandatory=$true)][string]$JunitJar,
    [Parameter(Mandatory=$true)][string]$HamcrestJar,
    [Parameter(Mandatory=$true)][string]$FrozenClasses,
    [switch]$IncludeRootBridge
)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputDirectory){throw '输出目录必须全新，保留旧证据'}
$sourceRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$capture=New-Item -ItemType Directory -Path $OutputDirectory
$classes=(New-Item -ItemType Directory -Path (Join-Path $capture.FullName 'classes')).FullName
$temporary=(New-Item -ItemType Directory -Path (Join-Path $capture.FullName 'tmp')).FullName
$main=Join-Path $sourceRoot 'app/src/main/java/net/elfradio/d31bootstrap'
$test=Join-Path $sourceRoot 'app/src/test/java/net/elfradio/d31bootstrap'
$sources=@(Get-ChildItem (Join-Path $main 'management') -Filter 'Network*.java' | Where-Object Name -ne 'NetworkStatus.java')
$sources+=@(Get-ChildItem (Join-Path $test 'management') -Filter 'Network*.java')
$tests=@(Get-ChildItem (Join-Path $test 'management') -Filter 'Network*Test.java' | ForEach-Object {'net.elfradio.d31bootstrap.management.'+$_.BaseName})
if($IncludeRootBridge){
    $sources+=Get-Item (Join-Path $main 'RemoteNetworkAccess.java')
    $rootTest=Join-Path $test 'RemoteNetworkAccessTest.java'
    if(Test-Path -LiteralPath $rootTest){$sources+=Get-Item $rootTest; $tests+='net.elfradio.d31bootstrap.RemoteNetworkAccessTest'}
}
$cp=@($classes,$JsonJar,$JunitJar,$HamcrestJar,$AndroidJar,$FrozenClasses)-join [IO.Path]::PathSeparator
$sources | Get-FileHash -Algorithm SHA256 | Select-Object Path,Hash | Export-Csv (Join-Path $capture.FullName 'source-sha256.csv') -NoTypeInformation
& (Join-Path $JavaDirectory 'bin/javac.exe') --release 8 -encoding UTF-8 -cp $cp -d $classes $sources.FullName 2>&1 | Tee-Object (Join-Path $capture.FullName 'compile.txt')
if($LASTEXITCODE -ne 0){throw '独立编译失败'}
& (Join-Path $JavaDirectory 'bin/java.exe') ('-Djava.io.tmpdir='+$temporary) -cp $cp org.junit.runner.JUnitCore @tests 2>&1 | Tee-Object (Join-Path $capture.FullName 'junit.txt')
if($LASTEXITCODE -ne 0){throw '独立测试失败'}
# 仅编译和运行桌面替身/独立JVM测试；没有Gradle、ADB或设备命令。
