param(
    [ValidateSet('Static', 'Build', 'Verify')][string]$Mode = 'Static',
    [string]$CapturePath,
    [ValidateRange(91, 2099999999)][int]$BaseVersionCode = 91,
    [ValidateRange(90, 2099999998)][int]$PreviousVerifiedFullVersionCode = 90,
    [ValidatePattern('^[0-9A-Za-z][0-9A-Za-z._-]{0,79}$')][string]$VersionName = '1.19.0-candidate',
    [string]$SdkPath = 'C:/Dev/android-sdk',
    [string]$BuildToolsVersion = '34.0.0'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PreviousVerifiedFullVersionCode -ge $BaseVersionCode) { throw '候选版本必须高于明确的已验证完整版' }
$project = Split-Path $PSScriptRoot -Parent

# 默认只读静态核查；只有父任务显式指定Build才启动Gradle。
& (Join-Path $project 'app/src/basic/tests/Test-RemoteVariants.ps1')
if ($Mode -eq 'Static') { return }
if (-not $CapturePath) { throw '构建或验包必须指定独立CapturePath' }
$capture = [IO.Path]::GetFullPath($CapturePath)
$build = Join-Path $capture 'build'
$index = Join-Path $capture 'remote-variants.json'
if (Test-Path -LiteralPath $index) { throw '已有验包索引，禁止覆盖' }

if ($Mode -eq 'Build') {
    if (Test-Path -LiteralPath $capture) { throw '制品目录必须全新，禁止覆盖旧制品' }
    # 不查找密钥，不输出值；签名环境由父任务统一注入。
    foreach ($name in @('D31_SIGNING_KEYSTORE', 'D31_SIGNING_STORE_PASSWORD',
                       'D31_SIGNING_KEY_ALIAS', 'D31_SIGNING_KEY_PASSWORD')) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { throw '正式签名环境未完整注入' }
    }
    New-Item -ItemType Directory -Path $capture | Out-Null
    Push-Location $project
    try {
        $arguments = @(':app:testBasicReleaseUnitTest', ':app:testFullReleaseUnitTest',
            ':app:assembleBasicRelease', ':app:assembleFullRelease',
            ':app:lintBasicRelease', ':app:lintFullRelease', ':app:writeRemoteVariantDependencies',
            "-PremoteBaseVersionCode=$BaseVersionCode", "-PremoteVersionName=$VersionName",
            "-PremoteBuildDirectory=$build", '--offline', '--console=plain')
        & ./gradlew.bat @arguments *> (Join-Path $capture 'build.log')
        if ($LASTEXITCODE -ne 0) { throw '构建或检查失败，不复制任何旧APK' }
    } finally { Pop-Location }
}

& (Join-Path $project 'app/src/basic/tests/Test-RemoteVariants.ps1') -GeneratedRoot (Join-Path $build 'generated/remote-variants')
& (Join-Path $project 'app/src/basic/tests/Test-RemoteLintModels.ps1') -BuildRoot $build
$aapt = Join-Path $SdkPath "build-tools/$BuildToolsVersion/aapt.exe"
$signer = Join-Path $SdkPath "build-tools/$BuildToolsVersion/apksigner.bat"
$certificateSha256 = '9b31f89fa50b672ecfe02d73a534cc03f6cf893739aec268f9fe0b71e72da72e'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$records = @()
foreach ($variant in @('basic', 'full')) {
    $apk = Join-Path $build "outputs/apk/$variant/release/app-$variant-release.apk"
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "缺少本轮APK：$variant" }
    $badging = @(& $aapt dump badging $apk 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "APK身份无法读取：$variant" }
    $packageLine = @($badging | Where-Object { $_ -match '^package:' })
    if ($packageLine.Count -ne 1 -or $packageLine[0] -notmatch "name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'") {
        throw "APK身份格式不完整：$variant"
    }
    $packageName = $Matches[1]
    $code = [int]$Matches[2]
    $name = $Matches[3]
    $expectedCode = $BaseVersionCode
    $expectedName = "$VersionName-basic"
    if ($variant -eq 'full') { $expectedCode++; $expectedName = $VersionName }
    if ($packageName -cne 'net.elfradio.d31bootstrap' -or $code -ne $expectedCode -or $name -cne $expectedName) {
        throw "包名或版本与本批合同不一致：$variant"
    }
    if (-not ($badging -match "^sdkVersion:'21'$")) { throw '最低API与D31兼容合同不一致' }
    if (-not ($badging -match "^targetSdkVersion:'27'$")) { throw '目标API与D31兼容合同不一致' }
    if ($badging -match '^application-debuggable') { throw '可调试APK不能作为正式身份候选' }
    $cert = @(& $signer verify --print-certs $apk 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "APK签名验证失败：$variant" }
    $digests = @($cert | Where-Object { $_ -match '^Signer #[0-9]+ certificate SHA-256 digest:' })
    if ($digests.Count -ne 1 -or $digests[0].Trim() -notmatch (':\s*' + $certificateSha256 + '$')) {
        throw "不是已登记的唯一正式证书：$variant"
    }
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $expectedMarker = "assets/remote-$variant.marker"
        $other = if ($variant -eq 'basic') { 'full' } else { 'basic' }
        $marker = $zip.GetEntry($expectedMarker)
        if (-not $marker -or $zip.GetEntry("assets/remote-$other.marker")) { throw '制品标记缺失或混装' }
        $markerText = "d31-$variant-v1`n"
        if ($marker.Length -ne [Text.Encoding]::UTF8.GetByteCount($markerText)) { throw '制品标记字节长度不符' }
        $reader = [IO.StreamReader]::new($marker.Open())
        try { if ($reader.ReadToEnd() -cne $markerText) { throw '制品标记内容不符' } }
        finally { $reader.Dispose() }
        $symbols = [Text.StringBuilder]::new()
        foreach ($entry in @($zip.Entries | Where-Object { $_.FullName -match '^classes[0-9]*\.dex$' })) {
            $stream = $entry.Open()
            $memory = [IO.MemoryStream]::new()
            try { $stream.CopyTo($memory); [void]$symbols.Append([Text.Encoding]::ASCII.GetString($memory.ToArray())) }
            finally { $stream.Dispose(); $memory.Dispose() }
        }
        $dex = $symbols.ToString()
        foreach ($entry in @('MainActivity', 'ProbeService', 'BootReceiver', 'AdbControl',
                            'RootTransport', 'RescueDaemon', 'RescueInstaller', 'RescueHttpServer', 'RescueJobs')) {
            if (-not $dex.Contains("Lnet/elfradio/d31bootstrap/$entry;")) { throw "缺少本地入口：$entry" }
        }
        if ($variant -eq 'basic') {
            if ($dex -match 'Lorg/eclipse/paho/|Lorg/java_websocket/|Lnet/elfradio/d31system/') { throw '基础APK携带禁止依赖' }
            foreach ($entry in @('RemoteDaemon', 'RemoteSupervisor', 'RemotePush', 'RemoteHttp',
                                'RemoteManualBootstrap', 'RemoteManualReceiver', 'RemoteUpdateEngine',
                                'RemoteSip', 'SipNetworkMonitor', 'UsbStorageControl', 'DeferredAppStartup',
                                'VendorNetworkReceiver', 'UsbBrowseActivity', 'UsbInsertPromptActivity')) {
                if ($dex.Contains("Lnet/elfradio/d31bootstrap/$entry;")) { throw "基础APK混入完整业务：$entry" }
            }
            if (-not $dex.Contains('Lnet/elfradio/d31bootstrap/BasicFileTool;')) { throw '基础APK缺少文件工具' }
            if ($zip.GetEntry('isrgrootx1.pem') -or $zip.GetEntry('update-public.pem')) { throw '基础APK混入云资源' }
        } else {
            foreach ($entry in @('RemoteDaemon', 'RemoteSupervisor', 'RemotePush', 'RemoteManualReceiver',
                                'RemoteManualBootstrap', 'RemoteUpdateEngine', 'RemoteUpdatePlatform')) {
                if (-not $dex.Contains("Lnet/elfradio/d31bootstrap/$entry;")) { throw "完整APK缺少原功能入口：$entry" }
            }
            if (-not $zip.GetEntry('isrgrootx1.pem') -or -not $zip.GetEntry('update-public.pem')) { throw '完整APK缺少原公开证书资源' }
        }
    } finally { $zip.Dispose() }
    $dependencies = Join-Path $build "remote-variants/$variant-dependencies.txt"
    $coordinates = @(Get-Content -LiteralPath $dependencies | Where-Object { $_.Trim() })
    if ($variant -eq 'basic') {
        if ($coordinates.Count -ne 1 -or $coordinates[0] -cne 'org.nanohttpd:nanohttpd:2.3.1') {
            throw '基础版运行依赖不再是唯一NanoHTTPD，必须重新审核'
        }
    } else {
        foreach ($required in @('org.nanohttpd:nanohttpd:2.3.1',
                'org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5',
                'org.java-websocket:Java-WebSocket:1.5.7')) {
            if ($coordinates -cnotcontains $required) { throw "完整版缺少依赖：$required" }
        }
    }
    $records += [ordered]@{
        artifact = $variant; remote_full = ($variant -eq 'full')
        package = $packageName; versionCode = $code; versionName = $name
        path = "build/outputs/apk/$variant/release/app-$variant-release.apk"
        bytes = (Get-Item -LiteralPath $apk).Length
        size = (Get-Item -LiteralPath $apk).Length
        sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        certSha256 = $certificateSha256
        certificateSha256 = $certificateSha256; minSdk = 21; targetSdk = 27
        debuggable = $false; target = 'D31'
        dependencies = $coordinates
    }
}
# 两包全部通过后才写索引；新目录保存候选，不代表批准正式发布。
[ordered]@{
    schema = 1; status = 'candidate-offline-verified'; createdUtc = [DateTime]::UtcNow.ToString('o')
    basicVersionCode = $BaseVersionCode; fullVersionCode = $BaseVersionCode + 1
    previousVerifiedFullVersionCode = $PreviousVerifiedFullVersionCode; artifacts = $records
    deviceAcceptance = '未执行；系统副本优先级、核心交接及功能须独立验收'
} | ConvertTo-Json -Depth 6 | Out-File -LiteralPath $index -Encoding UTF8 -NoClobber
Write-Output "双制品离线身份、签名、依赖和版本检查通过；索引：$index"
