$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:/Users/x/.jdks/jdk-17.0.20.1+1'
$out = Join-Path $PSScriptRoot ('build-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory $out | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" --release 8 -d $out "$PSScriptRoot/StartupHandover.java" "$PSScriptRoot/HandoverPolicy.java" "$PSScriptRoot/HandoverRuntime.java" "$PSScriptRoot/FactoryInit.java"
if ($LASTEXITCODE) { throw '编译失败' }
& "$env:JAVA_HOME/bin/javac.exe" --release 8 -cp $out -d $out "$PSScriptRoot/HandoverPolicyTest.java"
if ($LASTEXITCODE) { throw '测试编译失败' }
& "$env:JAVA_HOME/bin/java.exe" -cp $out HandoverPolicyTest | Tee-Object -FilePath "$out/policy-tests.txt"
if ($LASTEXITCODE) { throw '状态机测试失败' }
& "$env:JAVA_HOME/bin/javac.exe" --release 8 -cp $out -d $out "$PSScriptRoot/FactoryDefaultsTest.java"
if ($LASTEXITCODE) { throw '首次设置测试编译失败' }
& "$env:JAVA_HOME/bin/java.exe" -cp $out FactoryDefaultsTest | Tee-Object -FilePath "$out/defaults-tests.txt"
if ($LASTEXITCODE) { throw '首次设置测试失败' }
$classes = @(Get-ChildItem $out -Filter '*.class' | Where-Object { $_.Name -notlike 'HandoverPolicyTest*' -and $_.Name -notlike 'FactoryDefaultsTest*' } | ForEach-Object FullName)
& C:/Dev/android-sdk/build-tools/34.0.0/d8.bat --min-api 23 --lib C:/Dev/android-sdk/platforms/android-34/android.jar --output "$out/handover.jar" @classes
if ($LASTEXITCODE) { throw 'DEX转换失败' }
Copy-Item "$PSScriptRoot/StartupHandover.java" "$out/StartupHandover.java"
Copy-Item "$PSScriptRoot/HandoverPolicy.java" "$out/HandoverPolicy.java"
Copy-Item "$PSScriptRoot/HandoverRuntime.java" "$out/HandoverRuntime.java"
Copy-Item "$PSScriptRoot/start.sh" "$out/start.sh"
Copy-Item "$PSScriptRoot/FactoryInit.java" "$out/FactoryInit.java"
Get-FileHash "$out/handover.jar" | Format-List | Out-File "$out/hash.txt"
Write-Output $out
