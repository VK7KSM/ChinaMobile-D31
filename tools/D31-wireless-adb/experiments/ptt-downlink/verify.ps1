$ErrorActionPreference='Stop'
$workspace='C:/Dev/H13_D22'
$stage=$PSScriptRoot
$output=Join-Path $stage ('checks/'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path "$output/classes" -Force | Out-Null
$java="$workspace/.tools/jdk17/jdk-17.0.20+8/bin"
$frozen="$workspace/research/d31/staging/b12-variants133-134-final-20260912-2052/build/intermediates/javac/fullRelease/compileFullReleaseJavaWithJavac/classes"
$deps=@("$workspace/research/d31/staging/b13-mic-offline/check-20260912-233224-316/classes",$frozen,'C:/Dev/android-sdk/platforms/android-34/android.jar','C:/Users/x/.gradle/caches/8.9/transforms/a8d53bae574369a356b2fb74f6946a18/transformed/android-125.6422.07-runtime.jar')
$deps+=@(Get-ChildItem "$workspace/.gradle-d31/caches/modules-2/files-2.1" -Recurse -Filter '*.jar' | Where-Object Name -Match '^(json-2024|junit-4|hamcrest-core)' | Select-Object -ExpandProperty FullName)
$sources=@(Get-ChildItem "$stage/src","$stage/tests" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
$sources+="$workspace/research/d31_adb_bootstrap/app/src/main/java/net/elfradio/d31bootstrap/media/RtcAwait.java"
$sources | ForEach-Object {Get-FileHash -Algorithm SHA256 $_} | Export-Csv "$output/inputs-sha256.csv" -NoTypeInformation
& "$java/javac.exe" --release 8 -encoding UTF-8 -cp ($deps -join ';') -d "$output/classes" @sources 2>&1 | Tee-Object "$output/javac.txt"
if($LASTEXITCODE -ne 0){throw '独立编译失败'}
$runtime=@("$output/classes")+@($deps | Where-Object {$_ -notlike '*android-34/android.jar'})+@('C:/Dev/android-sdk/platforms/android-34/android.jar')
& "$java/java.exe" "-Dd31.workspace=$workspace" -cp ($runtime -join ';') org.junit.runner.JUnitCore net.elfradio.d31bootstrap.media.DownlinkTrackBindingTest net.elfradio.d31bootstrap.media.DownlinkRouteLeaseTest net.elfradio.d31bootstrap.media.PttProtocolTest net.elfradio.d31bootstrap.media.D31OutputEvidenceTest net.elfradio.d31bootstrap.media.PttOutputGuardTest net.elfradio.d31bootstrap.media.PttSessionControllerTest 2>&1 | Tee-Object "$output/junit.txt"
if($LASTEXITCODE -ne 0){throw '独立回归失败'}
Write-Output $output
