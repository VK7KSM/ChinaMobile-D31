param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path "$PSScriptRoot/../../../..").Path
$project=(Resolve-Path "$PSScriptRoot/../..").Path
if(Test-Path -LiteralPath $OutputDirectory){throw '输出目录已存在，禁止覆盖'}
$root=(New-Item -ItemType Directory -Path $OutputDirectory).FullName
$classes=(New-Item -ItemType Directory -Path (Join-Path $root 'classes')).FullName
$temporary=(New-Item -ItemType Directory -Path (Join-Path $root 'temp')).FullName
$jdk=Join-Path $workspace '.tools/jdk17/jdk-17.0.20+8/bin'
$android='C:/Dev/android-sdk/platforms/android-34/android.jar'
$json=(Get-ChildItem C:/Users/x/.gradle/caches/modules-2/files-2.1/org.json/json/20240303 -Filter *.jar -Recurse | Select-Object -First 1).FullName
$junit=Join-Path $workspace '.tools/gradle-8.9/lib/junit-4.13.2.jar'
$hamcrest=Join-Path $workspace '.tools/gradle-8.9/lib/hamcrest-core-1.3.jar'
$classpath=@($json,$android,$junit,$hamcrest)-join ';'
$sources=@()
foreach($tree in @('main','test')){foreach($package in @('media','lostmode')){
    $sources+=@(Get-ChildItem -LiteralPath "$project/app/src/$tree/java/net/elfradio/d31bootstrap/$package" -Filter *.java -File | Sort-Object FullName | ForEach-Object FullName)
}}
foreach($source in $sources){
    $text=[IO.File]::ReadAllText($source)
    if($text -match 'CompletableFuture|java\.nio\.file|java\.time\.|ProcessHandle|waitFor\([^)]*TimeUnit|rebootWipeUserData|wipeData\('){throw "发现超范围API或清除调用：$source"}
}
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 -source 8 -target 8 -cp $classpath -d $classes @sources 2>&1 | Out-File (Join-Path $root 'compile.log') -Encoding UTF8
if($LASTEXITCODE){throw '独立Java编译失败，见compile.log'}
$tests=@('media.MediaCaptureTest','media.AlarmTasksTest','media.PhotoReportTest','lostmode.LostModeReadinessTest') | ForEach-Object {'net.elfradio.d31bootstrap.'+$_}
& (Join-Path $jdk 'java.exe') "-Djava.io.tmpdir=$temporary" -cp "$classes;$classpath" org.junit.runner.JUnitCore @tests 2>&1 | Tee-Object -FilePath (Join-Path $root 'tests.log')
if($LASTEXITCODE){throw '独立媒体测试失败，见tests.log'}
$manifest=foreach($source in $sources){[pscustomobject]@{path=[IO.Path]::GetRelativePath($project,$source).Replace('\','/');bytes=(Get-Item $source).Length;sha256=(Get-FileHash $source).Hash}}
$manifest | ConvertTo-Json | Set-Content (Join-Path $root 'source-hashes.json') -Encoding UTF8
[pscustomobject]@{通过=$true;设备操作=$false;共享Gradle=$false;编译API=34;运行目标API=23;说明='API23调用人工审查与独立编译通过；未作真机媒体验收';源码文件数=$sources.Count} | ConvertTo-Json | Set-Content (Join-Path $root 'result.json') -Encoding UTF8
