param([Parameter(Mandatory=$true)][string]$CapturePath)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$captureRoot = [IO.Path]::GetFullPath($CapturePath)
if (Test-Path -LiteralPath $captureRoot) { throw '制品目录已存在，必须使用全新目录' }
New-Item -ItemType Directory -Path $captureRoot | Out-Null
Push-Location $projectRoot
try {
    & ./gradlew.bat :app:testFullReleaseUnitTest :app:assembleFullRelease :app:lintFullRelease --offline --console=plain *> (Join-Path $captureRoot 'build.log')
    if ($LASTEXITCODE -ne 0) { throw '构建或检查失败，禁止复制旧APK' }
    $apk = Join-Path $projectRoot 'app/build/outputs/apk/full/release/app-full-release.apk'
    $sdk = $env:ANDROID_HOME
    $signer = Join-Path $sdk 'build-tools/34.0.0/apksigner.bat'
    $certificate = & $signer verify --print-certs $apk 2>&1
    if ($LASTEXITCODE -ne 0 -or -not ($certificate -match '9b31f89fa50b672ecfe02d73a534cc03f6cf893739aec268f9fe0b71e72da72e')) { throw '签名校验失败' }
    $certificate | Out-File (Join-Path $captureRoot 'signature.txt') -Encoding utf8
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $dex = $zip.GetEntry('classes.dex')
        $stream = $dex.Open()
        $memory = [IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $symbols = [Text.Encoding]::ASCII.GetString($memory.ToArray()) }
        finally { $stream.Dispose(); $memory.Dispose() }
        foreach ($entry in @('RemoteDaemon','RemoteTlsCheck','RemoteDeployment','RemoteSupervisor','RemoteUpdateEngine')) {
            if (-not $symbols.Contains('Lnet/elfradio/d31bootstrap/'+$entry+';')) { throw "候选APK缺少入口：$entry" }
        }
        if (-not $zip.GetEntry('isrgrootx1.pem')) { throw '候选APK缺少公开根证书' }
        if (-not $zip.GetEntry('update-public.pem')) { throw '候选APK缺少发布公钥' }
    } finally { $zip.Dispose() }
    Copy-Item -LiteralPath $apk -Destination (Join-Path $captureRoot 'remote.apk')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app/build/test-results/testFullReleaseUnitTest') -Destination (Join-Path $captureRoot 'tests') -Recurse
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app/build/reports/lint-results-fullRelease.txt') -Destination (Join-Path $captureRoot 'lint.txt')
    Get-FileHash -LiteralPath (Join-Path $captureRoot 'remote.apk') | Select-Object Hash,Path
} finally { Pop-Location }
