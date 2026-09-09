param(
    [string]$SourceDirectory = "C:\Dev\H13_D22\research\d31\analysis\2026-09-09-factory-v1.4.3",
    [string]$OutputDirectory = "C:\Dev\H13_D22\research\d31\dist\factory-flash-v1.4.3"
)

$ErrorActionPreference = "Stop"
$root = "C:\Dev\H13_D22"
$ndk = Join-Path $root ".tools\android-sdk\ndk\26.3.11579264\toolchains\llvm\prebuilt\windows-x86_64\bin"
$clang = Join-Path $ndk "aarch64-linux-android23-clang.cmd"
$readelf = Join-Path $ndk "llvm-readelf.exe"
$strip = Join-Path $ndk "llvm-strip.exe"
$strings = Join-Path $ndk "llvm-strings.exe"
$java = Join-Path $root ".tools\jdk17\jdk-17.0.20+8\bin\java.exe"
$signApk = Join-Path $root "research\h13_root\aosp\signapk.jar"
$certificate = Join-Path $root "research\h13_root\aosp\testkey.x509.pem"
$privateKey = Join-Path $root "research\h13_root\aosp\testkey.pk8"
$conscrypt = Join-Path $root "research\d31\factory_package\tools\conscrypt-openjdk-uber-2.5.2.jar"
$conscryptSha256 = "EAF537D98E033D0F0451CD1B8CC74E02D7B55EC882DA63C88060D806BA89C348"
$source = Join-Path $root "research\d31\factory_package\native\update_binary.c"
$tlsSource = Join-Path $root "research\d31\factory_package\native\tls_align.S"
$pythonBuilder = Join-Path $root "research\d31\factory_package\build_factory_package.py"
$pythonVerifier = Join-Path $root "research\d31\factory_package\verify_factory_package.py"
$systemVerifier = Join-Path $root "research\d31\factory_package\verify_d31_system_image.sh"
$configTemplate = Join-Path $root "research\d31\factory_package\templates\config-tab"
$aapt = "C:\Dev\android-sdk\build-tools\34.0.0\aapt.exe"
$apksigner = "C:\Dev\android-sdk\build-tools\34.0.0\apksigner.bat"
$openssl = "C:\Program Files\Git\usr\bin\openssl.exe"

foreach ($required in @($clang, $readelf, $strip, $strings, $java, $signApk, $certificate, $privateKey, $conscrypt, $source, $tlsSource, $pythonBuilder, $pythonVerifier, $systemVerifier, $configTemplate, $aapt, $apksigner, $openssl, $SourceDirectory)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "缺少制作依赖：$required"
    }
}
if ((Get-FileHash -LiteralPath $conscrypt -Algorithm SHA256).Hash -ne $conscryptSha256) {
    throw "Conscrypt依赖哈希不匹配：$conscrypt"
}

$installerSource = Get-Content -LiteralPath $source -Raw
$payloadModeContracts = @(
    @{ Entry = 'payload/system-patches/apply-home-patch.sh'; Destination = '/data/local/d31-patches/apply-home-patch.sh'; Mode = '0750' },
    @{ Entry = 'payload/system-patches/config-tab'; Destination = '/data/local/d31-patches/config-tab'; Mode = '0644' },
    @{ Entry = 'payload/system-patches/getnumber-cellular-labels-v1-unsigned.apk'; Destination = '/data/local/d31-patches/getnumber-cellular-labels-v1-unsigned.apk'; Mode = '0644' },
    @{ Entry = 'payload/system-patches/libvsip-tls12-dns-transport-v2.so'; Destination = '/data/local/d31-patches/libvsip-tls12-dns-transport-v2.so'; Mode = '0644' },
    @{ Entry = 'payload/system-patches/nexui-v8-ethernet-gate.apk'; Destination = '/data/local/d31-patches/nexui-v8-ethernet-gate.apk'; Mode = '0644' },
    @{ Entry = 'payload/system-patches/imscc-firefox-telegram-messages-wrapper-v3.apk'; Destination = '/data/local/d31-patches/imscc-firefox-telegram-messages-wrapper-v3.apk'; Mode = '0644' }
)
foreach ($contract in $payloadModeContracts) {
    $expected = '{"' + $contract.Entry + '", "' + $contract.Destination + '", ' + $contract.Mode + ', 0, 0}'
    if (-not $installerSource.Contains($expected)) {
        throw "Recovery安装器载荷权限合同不匹配：$($contract.Entry) 应为 $($contract.Mode) root:root"
    }
}

$homePatchSource = Get-Content -LiteralPath (Join-Path $SourceDirectory 'system_payload\apply-home-patch.sh') -Raw
$handoverEntry = 'exec /system/bin/sh /data/local/d31-startup-handover/start.sh'
if (-not $homePatchSource.Contains($handoverEntry)) {
    throw "缺少已验证的事务式桌面启动入口"
}
if (Test-Path -LiteralPath $OutputDirectory) {
    throw "输出目录已经存在，为避免覆盖旧制品而停止：$OutputDirectory"
}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$nativeBinary = Join-Path $OutputDirectory "update-binary"

& $clang -static -Oz -fno-ident '-Wl,--build-id=none' -Wall -Wextra -Werror -o $nativeBinary $source $tlsSource -lz
if ($LASTEXITCODE -ne 0) {
    throw "D31原生update-binary编译失败：$LASTEXITCODE"
}
& $strip $nativeBinary
if ($LASTEXITCODE -ne 0) {
    throw "D31原生update-binary剥离调试信息失败：$LASTEXITCODE"
}
$readelfOutput = & $readelf --file-header --program-headers --dynamic $nativeBinary
$readelfOutput |
    Out-File -LiteralPath (Join-Path $OutputDirectory "update-binary-readelf.txt") -Encoding utf8
if ($LASTEXITCODE -ne 0) {
    throw "update-binary结构检查失败：$LASTEXITCODE"
}
if (($readelfOutput -join "`n") -notmatch '(?m)^\s*TLS\s+.*\s0x40\s*$') {
    throw "update-binary的ARM64 TLS段不是0x40对齐"
}
if (($readelfOutput -join "`n") -match '\(NEEDED\)') {
    throw "update-binary出现动态库依赖，不适用于D31 Recovery"
}
$binaryStrings = & $strings $nativeBinary
if (($binaryStrings -join "`n") -notmatch 'D31 update-binary self-test: PASS') {
    throw "update-binary缺少只读自检入口"
}

& C:\Python314\python.exe $pythonBuilder --source $SourceDirectory --binary $nativeBinary --output $OutputDirectory
if ($LASTEXITCODE -ne 0) {
    throw "刷机包载荷构建失败：$LASTEXITCODE"
}

$unsigned = Join-Path $OutputDirectory "D31_SVP3390_Factory_Flash_v1.4.3_unsigned.zip"
$signed = Join-Path $OutputDirectory "D31_SVP3390_Factory_Flash_v1.4.3_testkey.zip"
& $java -cp "$conscrypt;$signApk" com.android.signapk.SignApk -w $certificate $privateKey $unsigned $signed
if ($LASTEXITCODE -ne 0) {
    throw "刷机包签名失败：$LASTEXITCODE"
}

$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
& C:\Python314\python.exe $pythonVerifier --package $signed --output (Join-Path $OutputDirectory "package_verification.json") --certificate $certificate --openssl $openssl --binary $nativeBinary --updater-script (Join-Path $root "research\d31\factory_package\native\updater-script") --system-verifier $systemVerifier --config-template $configTemplate --aapt $aapt --apksigner $apksigner
if ($LASTEXITCODE -ne 0) {
    throw "签名刷机包离线校验失败：$LASTEXITCODE"
}

Get-FileHash -Algorithm SHA256 $nativeBinary, $unsigned, $signed |
    Format-Table -AutoSize |
    Out-String -Width 4096 |
    Out-File -LiteralPath (Join-Path $OutputDirectory "final_sha256.txt") -Encoding utf8
Get-Content -LiteralPath (Join-Path $OutputDirectory "final_sha256.txt")
