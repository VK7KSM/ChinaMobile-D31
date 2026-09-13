$ErrorActionPreference = 'Stop'
$root = 'C:\Dev\H13_D22'
$java = Join-Path $root '.tools/jdk17/jdk-17.0.20+8/bin'
$out = Join-Path $PSScriptRoot ('handover-build-' + (Get-Date -Format HHmmss))
if (Test-Path -LiteralPath $out) { throw '不覆盖旧编译产物' }
New-Item -ItemType Directory -Path "$out/classes", "$out/dex" | Out-Null
$sources = Get-ChildItem -LiteralPath "$PSScriptRoot/handover" -Filter '*.java' | ForEach-Object FullName
& "$java/javac.exe" --release 8 -encoding UTF-8 -d "$out/classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'handover编译失败' }
& "$java/jar.exe" cf "$out/classes.jar" -C "$out/classes" .
if ($LASTEXITCODE -ne 0) { throw 'class归档失败' }
& "$java/java.exe" -cp C:/Dev/android-sdk/build-tools/34.0.0/lib/d8.jar com.android.tools.r8.D8 --release --min-api 23 --lib C:/Dev/android-sdk/platforms/android-34/android.jar --output "$out/dex" "$out/classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'D8编译失败' }
& "$java/jar.exe" cf "$out/handover.jar" -C "$out/dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw 'DEX归档失败' }
Get-FileHash -LiteralPath "$out/handover.jar" -Algorithm SHA256
[ordered]@{ path = "$out/handover.jar"; sha256 = (Get-FileHash "$out/handover.jar").Hash; bytes = (Get-Item "$out/handover.jar").Length } | ConvertTo-Json | Set-Content -LiteralPath "$PSScriptRoot/handover-candidate.json" -Encoding utf8NoBOM
