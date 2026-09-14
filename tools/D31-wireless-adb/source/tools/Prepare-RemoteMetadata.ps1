param([Parameter(Mandatory=$true)][string]$ApkPath,[Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference='Stop'
if (Test-Path -LiteralPath $OutputPath) { throw '元数据输出已存在' }
$apk=Get-Item -LiteralPath $ApkPath
$badging=& C:/Dev/android-sdk/build-tools/34.0.0/aapt.exe dump badging $apk.FullName
if ($LASTEXITCODE -ne 0) { throw 'APK元数据解析失败' }
$packageLine=$badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
if ($packageLine -notmatch "name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'") { throw 'APK版本字段无效' }
$package=$Matches[1]; $code=[int]$Matches[2]; $version=$Matches[3]
$certOutput=& C:/Dev/android-sdk/build-tools/34.0.0/apksigner.bat verify --print-certs $apk.FullName
if ($LASTEXITCODE -ne 0) { throw 'APK签名验证失败' }
$certLine=$certOutput | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest:' } | Select-Object -First 1
if ($certLine -notmatch 'digest: ([a-fA-F0-9]{64})') { throw 'APK证书摘要缺失' }
$cert=$Matches[1].ToLowerInvariant()
if ($package -ne 'net.elfradio.d31bootstrap' -or $cert -ne '9b31f89fa50b672ecfe02d73a534cc03f6cf893739aec268f9fe0b71e72da72e') { throw '不是原签名D31制品' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($apk.FullName)
$remoteFull=$false
try {
    $marker=$zip.GetEntry('assets/remote-full.marker')
    if ($marker -and $marker.Length -eq 12) {
        $reader=[IO.StreamReader]::new($marker.Open(),[Text.Encoding]::UTF8)
        try { $remoteFull=$reader.ReadToEnd() -ceq "d31-full-v1`n" }
        finally { $reader.Dispose() }
    }
} finally { $zip.Dispose() }
$value=[ordered]@{package=$package;versionCode=$code;versionName=$version;certSha256=$cert;size=$apk.Length;sha256=(Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant();remote_full=$remoteFull}
$bytes=[Text.Encoding]::UTF8.GetBytes(($value | ConvertTo-Json))
$stream=[IO.File]::Open([IO.Path]::GetFullPath($OutputPath),[IO.FileMode]::CreateNew)
try { $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
$value | ConvertTo-Json
