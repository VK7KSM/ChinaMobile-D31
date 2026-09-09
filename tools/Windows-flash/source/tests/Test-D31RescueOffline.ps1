param(
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$workspaceRoot = Split-Path -Parent $projectRoot
$backend = Join-Path $workspaceRoot "d31\dist\factory-flash-v1.0.4\flash_d31_recovery.ps1"
$toolDirectory = Join-Path $projectRoot "dist-v1.3.5"
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot ("test-output\rescue-offline-" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$report = Join-Path $OutputDirectory "离线故障注入结果.txt"

function Add-Result {
    param([string]$Name, [string]$Result)
    Add-Content -LiteralPath $report -Encoding UTF8 -Value ("{0}`t{1}" -f $Name, $Result)
}

function Expect-Failure {
    param([string]$Name, [scriptblock]$Action, [string]$MessagePattern)
    try {
        & $Action | Out-Null
        throw "预期失败但实际通过：$Name"
    } catch {
        if ($_.Exception.Message -notmatch $MessagePattern) { throw }
        Add-Result $Name ("通过：已拒绝，原因=" + $_.Exception.Message)
    }
}

$tokens = $null
$errors = $null
$tree = [System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path -LiteralPath $backend), [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw "刷机后端PowerShell语法错误" }
$backendText = Get-Content -Raw -LiteralPath $backend
foreach ($marker in @('[switch]$SkipBackup', 'if ($SkipBackup)', '本次刷机不会创建急救恢复包')) {
    if (-not $backendText.Contains($marker)) { throw "刷机后端缺少可选备份标记：$marker" }
}
if ($backendText.Contains('再次备份这台D31自己的身份')) { throw "刷机后端仍包含强制重复备份路径" }
Add-Result "可取消备份后端" "通过：存在SkipBackup路径且没有强制重复备份"
$wantedFunctions = @("Get-GzipRawInfo", "Assert-RescueDirectory")
foreach ($name in $wantedFunctions) {
    $definition = $tree.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $name
    }, $true)
    if ($null -eq $definition) { throw "后端缺少函数：$name" }
    Invoke-Expression $definition.Extent.Text
}

$ExpectedSizes = @{ system = 4096L; boot = 2048L; userdata = 8192L }
$ByName = "/fixture/by-name"
$ExpectedRescueRestoreHash = "783D95431DCE93094347C5CEDA31F367102CEF1786DF4E18A171914C74322BC4"
$ExpectedRescueTestHash = "012FBBA56C97AE9A8A2A7E034EAB5CAB396C8D7E096FFEEB9E551DE6713BFD18"
$remoteRecoveryHash = ("A" * 64)
$remoteProinfoHash = ("B" * 64)
function Get-RemoteSha256 {
    param([string]$Path)
    if ($Path -like "*/recovery") { return $script:remoteRecoveryHash }
    if ($Path -like "*/proinfo") { return $script:remoteProinfoHash }
    throw "夹具收到未知分区：$Path"
}

$card = Join-Path $OutputDirectory "卡刷目录夹具"
New-Item -ItemType Directory -Path $card | Out-Null
$raw = New-Object byte[] $ExpectedSizes.system
for ($index = 0; $index -lt $raw.Length; $index++) { $raw[$index] = [byte]($index % 251) }
$systemPath = Join-Path $card "D31_RESCUE_SYSTEM.img.gz"
$stream = [System.IO.File]::Create($systemPath)
$gzip = New-Object System.IO.Compression.GZipStream($stream, [IO.Compression.CompressionMode]::Compress)
try { $gzip.Write($raw, 0, $raw.Length) } finally { $gzip.Dispose(); $stream.Dispose() }
$bootPath = Join-Path $card "D31_RESCUE_BOOT.img"
$boot = New-Object byte[] $ExpectedSizes.boot
for ($index = 0; $index -lt $boot.Length; $index++) { $boot[$index] = [byte](255 - ($index % 251)) }
[IO.File]::WriteAllBytes($bootPath, $boot)
$restorePath = Join-Path $card "D31_RESCUE_UPDATE.zip"
$testPath = Join-Path $card "D31_RESCUE_TEST.zip"
Copy-Item (Join-Path $toolDirectory "rescue\D31_RESCUE_UPDATE.zip") $restorePath
Copy-Item (Join-Path $toolDirectory "rescue\D31_RESCUE_TEST.zip") $testPath
$manifest = @(
    "format=D31_RESCUE_V1",
    "target_fingerprint=fixture-fingerprint",
    "created_local=2026-08-31T00:00:00+10:00",
    "system_partition_bytes=$($ExpectedSizes.system)",
    "boot_partition_bytes=$($ExpectedSizes.boot)",
    "userdata_partition_bytes=$($ExpectedSizes.userdata)",
    "recovery_sha256=$remoteRecoveryHash",
    "proinfo_sha256=$remoteProinfoHash",
    "system_gzip_bytes=$((Get-Item $systemPath).Length)",
    "system_gzip_sha256=$((Get-FileHash $systemPath -Algorithm SHA256).Hash)",
    "system_raw_sha256=$(([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($raw))).Replace('-', ''))",
    "boot_sha256=$((Get-FileHash $bootPath -Algorithm SHA256).Hash)"
)
[IO.File]::WriteAllLines((Join-Path $card "D31_RESCUE_MANIFEST.txt"), $manifest, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $card "SHA256SUMS.txt"), "夹具", [Text.UTF8Encoding]::new($false))

$resolved = Assert-RescueDirectory $card "fixture-fingerprint"
if ($resolved -ne (Resolve-Path $card).Path) { throw "正常急救目录返回路径异常" }
Add-Result "正常本机急救目录" "通过"

$originalSystem = [IO.File]::ReadAllBytes($systemPath)
$damagedSystem = [byte[]]$originalSystem.Clone()
$damagedSystem[[Math]::Floor($damagedSystem.Length / 2)] = $damagedSystem[[Math]::Floor($damagedSystem.Length / 2)] -bxor 0x01
[IO.File]::WriteAllBytes($systemPath, $damagedSystem)
Expect-Failure "system压缩镜像损坏" { Assert-RescueDirectory $card "fixture-fingerprint" } "system压缩镜像损坏"
[IO.File]::WriteAllBytes($systemPath, $originalSystem)

$remoteProinfoHash = "C" * 64
Expect-Failure "另一台D31绑定" { Assert-RescueDirectory $card "fixture-fingerprint" } "不属于当前连接的D31"
$remoteProinfoHash = "B" * 64

$originalTest = [IO.File]::ReadAllBytes($testPath)
$damagedTest = [byte[]]$originalTest.Clone()
$damagedTest[64] = $damagedTest[64] -bxor 0x01
[IO.File]::WriteAllBytes($testPath, $damagedTest)
Expect-Failure "TEST入口被替换" { Assert-RescueDirectory $card "fixture-fingerprint" } "签名Recovery入口不是本工具批准的版本"
[IO.File]::WriteAllBytes($testPath, $originalTest)

Expect-Failure "选择备份但未提供急救目录" { Assert-RescueDirectory "" "fixture-fingerprint" } "选择备份时必须先用本工具创建本机急救包"
Add-Result "结论" "全部离线故障注入通过；未连接或修改D31"
Get-Content -LiteralPath $report
