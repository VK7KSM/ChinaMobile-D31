param([Parameter(Mandatory=$true)][string]$Apk,[Parameter(Mandatory=$true)][string]$CapturePath,[string]$Serial)
$ErrorActionPreference='Stop'
if([string]::IsNullOrWhiteSpace($Serial) -or $Serial -match '[\s\x00]'){throw '必须通过-Serial显式传入完整目标设备序列号'}
$adb='C:/Dev/android-sdk/platform-tools/adb.exe'
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '捕获目录已存在'}
New-Item -ItemType Directory -Path $capture | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $capture 'host.ps1')
& $adb -P 5042 -s $serial shell 'date; getprop ro.build.fingerprint; id; ps; dumpsys package net.elfradio.d31phone.debug' *> (Join-Path $capture 'before-private.txt')
$old=((& $adb -P 5042 -s $serial shell 'pm path net.elfradio.d31phone.debug') -join '').Trim() -replace '^package:',''
if($old -notmatch '^/data/app/net\.elfradio\.d31phone\.debug-[0-9]+/base\.apk$'){throw '原包路径不符'}
& $adb -P 5042 -s $serial pull $old (Join-Path $capture 'before.apk') *> (Join-Path $capture 'pull-before.log')
if($LASTEXITCODE -ne 0){throw '原包备份失败'}
& $adb -P 5042 -s $serial pull /data/data/net.elfradio.d31phone.debug/shared_prefs/d31_sip_profile.xml (Join-Path $capture 'before-profile-private.xml') *> (Join-Path $capture 'pull-profile.log')
if($LASTEXITCODE -ne 0){throw '原配置备份失败'}
Copy-Item -LiteralPath $Apk -Destination (Join-Path $capture 'candidate.apk')
& C:/Dev/android-sdk/build-tools/34.0.0/apksigner.bat verify --print-certs (Join-Path $capture 'candidate.apk') *> (Join-Path $capture 'candidate-signature.txt')
if($LASTEXITCODE -ne 0){throw '候选签名无效'}
& C:/Dev/android-sdk/build-tools/34.0.0/apksigner.bat verify --print-certs (Join-Path $capture 'before.apk') *> (Join-Path $capture 'before-signature.txt')
$oldCert=Select-String -LiteralPath (Join-Path $capture 'before-signature.txt') -Pattern 'certificate SHA-256 digest:'
$newCert=Select-String -LiteralPath (Join-Path $capture 'candidate-signature.txt') -Pattern 'certificate SHA-256 digest:'
if($oldCert.Line -ne $newCert.Line){throw '覆盖签名不一致'}
& $adb -P 5042 -s $serial install -r (Join-Path $capture 'candidate.apk') *> (Join-Path $capture 'install.log')
if($LASTEXITCODE -ne 0 -or -not (Select-String -LiteralPath (Join-Path $capture 'install.log') -Pattern '^Success')){throw '安装未成功'}
& $adb -P 5042 -s $serial shell 'dumpsys package net.elfradio.d31phone.debug; content call --uri content://net.elfradio.d31phone.debug.sipremote --method status; ps' *> (Join-Path $capture 'after-private.txt')
$files=@(Get-ChildItem -LiteralPath $capture -File)
$files | ForEach-Object { [pscustomobject]@{File=$_.Name;Bytes=$_.Length;SHA256=(Get-FileHash -LiteralPath $_.FullName).Hash} } | Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $capture 'artifacts_sha256.csv')
Write-Output 'QUIK候选安装完成，状态原件已保存'
