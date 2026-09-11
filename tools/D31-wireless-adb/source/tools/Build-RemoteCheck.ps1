param([Parameter(Mandatory=$true)][string]$SourcePath,[Parameter(Mandatory=$true)][string]$OutputDirectory,[string]$ExtraClasspath='',[string]$CompiledClasses='')
$ErrorActionPreference='Stop'
$source=Get-Item -LiteralPath $SourcePath
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw '诊断目录已存在，不能覆盖原件'}
$project=Split-Path $PSScriptRoot -Parent
$classes=if ($CompiledClasses) { [IO.Path]::GetFullPath($CompiledClasses) } else { Join-Path $project 'app/build/intermediates/javac/fullRelease/compileFullReleaseJavaWithJavac/classes' }
if (-not (Test-Path -LiteralPath $classes -PathType Container)) { throw '本轮候选编译目录不存在，禁止使用历史类文件代替' }
$android='C:/Dev/android-sdk/platforms/android-34/android.jar'
$build=New-Item -ItemType Directory -Path (Join-Path $output 'classes')
Copy-Item -LiteralPath $source.FullName -Destination (Join-Path $output $source.Name)
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -source 8 -target 8 -cp "$android;$classes;$ExtraClasspath" -d $build.FullName $source.FullName *> (Join-Path $output 'javac.log')
if($LASTEXITCODE -ne 0){throw '诊断源码编译失败'}
$inputs=@(Get-ChildItem -LiteralPath $build.FullName -Recurse -Filter '*.class' | ForEach-Object FullName)
& C:/Dev/android-sdk/build-tools/34.0.0/d8.bat --min-api 23 --lib $android --classpath $classes --output (Join-Path $output 'check.jar') @inputs *> (Join-Path $output 'd8.log')
if($LASTEXITCODE -ne 0){throw '诊断DEX生成失败'}
Get-FileHash -LiteralPath (Join-Path $output 'check.jar') -Algorithm SHA256
