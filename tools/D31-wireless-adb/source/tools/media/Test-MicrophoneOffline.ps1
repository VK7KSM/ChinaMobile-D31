param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path "$PSScriptRoot/../../../..").Path
$project=(Resolve-Path "$PSScriptRoot/../..").Path
if(Test-Path -LiteralPath $OutputDirectory){throw '输出目录已存在，禁止覆盖'}
$root=(New-Item -ItemType Directory -Path $OutputDirectory).FullName
$classes=(New-Item -ItemType Directory -Path (Join-Path $root 'classes')).FullName
$temporary=(New-Item -ItemType Directory -Path (Join-Path $root 'temp')).FullName
$cache='C:/Users/x/.gradle/caches/modules-2/files-2.1'
function CachedFile([string]$relative,[string]$pattern){
    $entry=Get-ChildItem -LiteralPath (Join-Path $cache $relative) -Filter $pattern -Recurse -File | Select-Object -First 1
    if(!$entry){throw "本地依赖缺失：$relative"};return $entry.FullName
}
$aar=CachedFile 'io.github.webrtc-sdk/android/125.6422.07' '*.aar'
$rtc=Join-Path $root 'webrtc-classes.jar'
$native=Join-Path $root 'libjingle_peerconnection_so.so'
$zip=[IO.Compression.ZipFile]::OpenRead($aar)
try{
    [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('classes.jar'),$rtc)
    [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('jni/armeabi-v7a/libjingle_peerconnection_so.so'),$native)
    [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('AndroidManifest.xml'),(Join-Path $root 'webrtc-manifest.xml'))
    $zip.Entries | Where-Object {$_.FullName -match '^jni/|^classes.jar$'} | Select-Object FullName,Length | ConvertTo-Json | Set-Content (Join-Path $root 'aar-entries.json') -Encoding UTF8
}finally{$zip.Dispose()}
$jdk=Join-Path $workspace '.tools/jdk17/jdk-17.0.20+8/bin'
$android='C:/Dev/android-sdk/platforms/android-34/android.jar'
$json=CachedFile 'org.json/json/20240303' '*.jar'
$ws=CachedFile 'org.java-websocket/Java-WebSocket/1.5.7' '*.jar'
$slf=CachedFile 'org.slf4j/slf4j-api' '*.jar'
$junit=Join-Path $workspace '.tools/gradle-8.9/lib/junit-4.13.2.jar'
$hamcrest=Join-Path $workspace '.tools/gradle-8.9/lib/hamcrest-core-1.3.jar'
$classpath=@($rtc,$ws,$slf,$json,$android,$junit,$hamcrest)-join ';'
$sources=@()
foreach($tree in @('main','test')){foreach($package in @('media','lostmode')){
    $sources+=@(Get-ChildItem -LiteralPath "$project/app/src/$tree/java/net/elfradio/d31bootstrap/$package" -Filter *.java -File | Where-Object {$tree -eq 'main' -or $_.Name -notlike 'AndroidAudioOccupancy*'} | Sort-Object FullName | ForEach-Object FullName)
}}
foreach($source in $sources){
    $content=[IO.File]::ReadAllText($source)
    if($content -match 'CompletableFuture|java\.time\.|ProcessHandle|checkSelfPermission\(|getActiveRecordingConfigurations\(|setSpeakerphoneOn\(|setStreamVolume\(|wipeData\(' -or
       ($source -match '[\\/]src[\\/]main[\\/]' -and $content -match 'java\.nio\.file')){throw "发现高版本API或超范围动作：$source"}
}
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 -source 8 -target 8 -cp $classpath -d $classes @sources 2>&1 | Out-File (Join-Path $root 'compile.log') -Encoding UTF8
if($LASTEXITCODE){throw '独立编译失败，见compile.log'}
$tests=@('media.MediaCaptureTest','media.AlarmTasksTest','media.PhotoReportTest','lostmode.LostModeReadinessTest',
    'media.RtcOfferTest','media.RtcAwaitTest','media.MicrophoneSessionTest','media.ApkMediaLibraryTest',
    'media.AppMediaContractTest','media.AppMediaControllerTest','media.AppMediaBackendTest') | ForEach-Object {'net.elfradio.d31bootstrap.'+$_}
& (Join-Path $jdk 'java.exe') "-Djava.io.tmpdir=$temporary" "-Dd31.test.webrtcArmLibrary=$native" -cp "$classes;$classpath" org.junit.runner.JUnitCore @tests 2>&1 | Tee-Object -FilePath (Join-Path $root 'tests.log')
if($LASTEXITCODE){throw '媒体测试失败，见tests.log'}
$ndk='C:/Dev/android-sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64'
$readelf=Join-Path $ndk 'bin/llvm-readelf.exe'
& $readelf -h -d -n --hex-dump=.note.android.ident $native | Set-Content (Join-Path $root 'native-elf.txt') -Encoding UTF8
if($LASTEXITCODE){throw '原生库静态解析失败'}
$dynamic=& $readelf --dyn-syms --wide $native
$dynamic | Set-Content (Join-Path $root 'native-symbols.txt') -Encoding UTF8
$required=@($dynamic | ForEach-Object {if($_ -match 'GLOBAL\s+DEFAULT\s+UND\s+([^\s@]+)'){$Matches[1]}} | Sort-Object -Unique)
$exports=@{}
$dependencies=@('libEGL.so','libdl.so','libm.so','liblog.so','libc.so')
foreach($dependency in $dependencies){
    $stub=Join-Path $ndk "sysroot/usr/lib/arm-linux-androideabi/23/$dependency"
    if(!(Test-Path $stub)){throw "API23链接桩缺失：$dependency"}
    $symbols=& $readelf --dyn-syms --wide $stub
    if($LASTEXITCODE){throw "API23链接桩解析失败：$dependency"}
    foreach($line in $symbols){if($line -match '(GLOBAL|WEAK)\s+DEFAULT\s+\d+\s+([^\s@]+)'){$exports[$Matches[2]]=$true}}
}
$missing=@($required | Where-Object {!$exports.ContainsKey($_)})
[pscustomobject]@{依赖=$dependencies;强导入符号数=$required.Count;API23桩缺失符号=$missing;说明='仅静态链接符号核验，未在D31加载JNI'} | ConvertTo-Json | Set-Content (Join-Path $root 'native-api23.json') -Encoding UTF8
if($missing.Count){throw '存在API23链接桩缺失符号，见native-api23.json'}
& (Join-Path $jdk 'javap.exe') -classpath $rtc -c -p org.webrtc.audio.WebRtcAudioRecord | Set-Content (Join-Path $root 'audio-record-bytecode.txt') -Encoding UTF8
& (Join-Path $jdk 'javap.exe') -classpath $ws -c -p org.java_websocket.client.WebSocketClient | Set-Content (Join-Path $root 'websocket-bytecode.txt') -Encoding UTF8
$manifest=foreach($source in $sources){[pscustomobject]@{path=[IO.Path]::GetRelativePath($project,$source).Replace('\','/');bytes=(Get-Item $source).Length;sha256=(Get-FileHash $source).Hash}}
$manifest | ConvertTo-Json | Set-Content (Join-Path $root 'source-hashes.json') -Encoding UTF8
@($aar,$rtc,$native,$ws,$slf) | ForEach-Object {[pscustomobject]@{文件名=[IO.Path]::GetFileName($_);字节=(Get-Item $_).Length;sha256=(Get-FileHash $_).Hash}} | ConvertTo-Json | Set-Content (Join-Path $root 'dependency-hashes.json') -Encoding UTF8
[pscustomobject]@{通过=$true;源码文件数=$sources.Count;设备操作=$false;网络请求=$false;JNI实际加载=$false;共享Gradle=$false;编译桩API=34;目标运行API=23;说明='真实WebRTC依赖独立编译、宿主测试及原生链接静态核验；非设备媒体通过'} | ConvertTo-Json | Set-Content (Join-Path $root 'result.json') -Encoding UTF8
