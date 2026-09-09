param(
    [string]$Serial,
    [int]$AdbPort = 5042,
    [string]$PackagePath,
    [string]$RescueDirectory,
    [switch]$SkipBackup,
    [switch]$PreflightOnly,
    [switch]$DevicePreflightOnly,
    [switch]$PackagePreflightOnly
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding
$PackageRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$Adb = Join-Path $PackageRoot "tools\adb.exe"
$Package = $PackagePath
$ApprovedPackage = Get-Content -Raw -LiteralPath (Join-Path $PackageRoot 'approved-package.json') | ConvertFrom-Json
$ExpectedPackageBytes = [int64]$ApprovedPackage.bytes
$ExpectedPackageHash = [string]$ApprovedPackage.sha256
if ($ExpectedPackageBytes -le 0 -or $ExpectedPackageHash -notmatch '^[0-9A-Fa-f]{64}$' -or
    $ApprovedPackage.bootSha256 -notmatch '^[0-9A-Fa-f]{64}$' -or $ApprovedPackage.version -notmatch '^\d+\.\d+\.\d+$') {
    throw '内置固件清单无效，拒绝继续'
}
$ExpectedFingerprint = "alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys"
$ByName = "/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name"
$ExpectedSizes = @{
    system = 1610612736L
    boot = 16777216L
    userdata = 13517717504L
    logo = 8388608L
}
$RequiredBackupPartitions = @("nvram", "nvdata", "protect1", "protect2", "proinfo")
$AdditionalBackupPartitions = @("recovery", "secro", "seccfg", "frp")
$RemotePackage = "/data/local/tmp/D31-factory-v$($ApprovedPackage.version).zip"
$ExpectedRescueRestoreHash = "783D95431DCE93094347C5CEDA31F367102CEF1786DF4E18A171914C74322BC4"
$ExpectedRescueTestHash = "012FBBA56C97AE9A8A2A7E034EAB5CAB396C8D7E096FFEEB9E551DE6713BFD18"
$SessionLog = $null

function Assert-InstalledPayload {
    $files = Get-Content -Raw -LiteralPath (Join-Path $PackageRoot 'installed-files.json') | ConvertFrom-Json
    foreach ($file in $files) {
        if ($file.path -notmatch '^/data/[a-zA-Z0-9/_.-]+$' -or $file.sha256 -notmatch '^[0-9a-fA-F]{64}$') {
            throw '刷后文件核验清单无效'
        }
        if ((Get-RemoteSha256 $file.path) -ne $file.sha256) {
            throw "刷后载荷与固件不一致：$($file.path)"
        }
    }
    Write-Step "刷后$($files.Count)项载荷SHA-256全部匹配，包含应用、独立守护和Recovery激活文件。"
}

function Write-Utf8 {
    param([string]$Path, [string[]]$Lines)
    [System.IO.File]::WriteAllLines($Path, $Lines, [System.Text.UTF8Encoding]::new($false))
}

function Add-SessionLog {
    param([string]$Text)
    if ($SessionLog) {
        [System.IO.File]::AppendAllText(
            $SessionLog,
            (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "  " + $Text + [Environment]::NewLine,
            [System.Text.UTF8Encoding]::new($false))
    }
}

function Write-Step {
    param([string]$Text)
    Write-Host $Text
    Add-SessionLog $Text
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    try {
        # ADB会把成功传输进度写入stderr，不能让Windows PowerShell 5.1把它当成脚本异常。
        $ErrorActionPreference = "Continue"
        $output = & $Adb -P $AdbPort @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    Add-SessionLog ("ADB " + ($Arguments -join " "))
    if ($output) { Add-SessionLog ($output -join [Environment]::NewLine) }
    if ($exitCode -ne 0) {
        throw "ADB命令失败：adb -P $AdbPort $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Invoke-AdbOptional {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = "SilentlyContinue"
        $output = & $Adb -P $AdbPort @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    return [PSCustomObject]@{ ExitCode = $exitCode; Output = @($output) }
}

function Get-DeviceValue {
    param([string]$Command)
    return ((Invoke-Adb -s $Serial shell $Command) -join "`n").Trim()
}

function Convert-AndroidSizeToBytes {
    param([string]$Text)
    $valueText = $Text.Trim()
    if ($valueText -notmatch '^([0-9]+(?:\.[0-9]+)?)([KMGT]?)$') {
        throw "无法解析Android存储空间：$Text"
    }
    $number = [decimal]::Parse(
        $Matches[1],
        [Globalization.NumberStyles]::AllowDecimalPoint,
        [Globalization.CultureInfo]::InvariantCulture)
    [decimal]$multiplier = switch ($Matches[2]) {
        "K" { 1KB }
        "M" { 1MB }
        "G" { 1GB }
        "T" { 1TB }
        default { 1 }
    }
    return [int64][decimal]::Floor($number * $multiplier)
}

function Get-BlockSize {
    param([string]$Name)
    $text = Get-DeviceValue "blockdev --getsize64 $ByName/$Name"
    [int64]$value = 0
    if (-not [int64]::TryParse($text.Trim(), [ref]$value)) {
        throw "无法读取$Name分区尺寸：$text"
    }
    return $value
}

function Get-RemoteSha256 {
    param([string]$Path)
    $line = Get-DeviceValue "busybox sha256sum $Path"
    if ($line -notmatch '^([0-9a-fA-F]{64})\s+') {
        throw "无法解析设备端SHA-256：$line"
    }
    return $Matches[1].ToUpperInvariant()
}

function New-LocalUploadChunk {
    param(
        [string]$SourcePath,
        [int64]$Offset,
        [int]$Count,
        [string]$ChunkPath
    )
    $source = [System.IO.File]::OpenRead($SourcePath)
    $target = [System.IO.File]::Open($ChunkPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try {
        [void]$source.Seek($Offset, [System.IO.SeekOrigin]::Begin)
        $buffer = New-Object byte[] (1MB)
        $remaining = $Count
        while ($remaining -gt 0) {
            $read = $source.Read($buffer, 0, [Math]::Min($buffer.Length, $remaining))
            if ($read -le 0) { throw "读取本机刷机包分块时提前到达文件末尾" }
            $target.Write($buffer, 0, $read)
            $remaining -= $read
        }
    } finally {
        $target.Dispose()
        $source.Dispose()
    }
}

function Send-UploadChunk {
    param(
        [string]$LocalPath,
        [string]$RemotePath,
        [int64]$ExpectedBytes,
        [string]$ExpectedHash,
        [int]$ChunkNumber,
        [int]$ChunkTotal
    )
    $lastError = ""
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $null = Invoke-AdbOptional connect $Serial
        $null = Invoke-AdbOptional -s $Serial shell "rm -f $RemotePath"
        $result = Invoke-AdbOptional -s $Serial push $LocalPath $RemotePath
        if ($result.ExitCode -eq 0) {
            try {
                $remoteBytesText = Get-DeviceValue "stat -c %s $RemotePath"
                [int64]$remoteBytes = 0
                if ([int64]::TryParse($remoteBytesText, [ref]$remoteBytes) -and
                    $remoteBytes -eq $ExpectedBytes -and
                    (Get-RemoteSha256 $RemotePath) -eq $ExpectedHash) {
                    Write-Host "分块$ChunkNumber/$ChunkTotal上传并校验通过。"
                    return
                }
                $lastError = "远端分块长度或SHA-256不匹配"
            } catch {
                $lastError = $_.Exception.Message
            }
        } else {
            $lastError = ($result.Output | Out-String).Trim()
        }
        Write-Host "分块$ChunkNumber/$ChunkTotal第$attempt次上传失败，3秒后自动重连重试：$lastError"
        Start-Sleep -Seconds 3
    }
    throw "分块$ChunkNumber/$ChunkTotal连续5次上传失败；已完成部分保留在D31，下次运行会续传。最后错误：$lastError"
}

function Copy-PackageToDeviceResumable {
    param([string]$SourcePath, [string]$DestinationPath)

    $existingBytesText = Get-DeviceValue "if [ -f $DestinationPath ]; then stat -c %s $DestinationPath; else echo 0; fi"
    [int64]$existingBytes = 0
    if ([int64]::TryParse($existingBytesText, [ref]$existingBytes) -and $existingBytes -eq $ExpectedPackageBytes) {
        if ((Get-RemoteSha256 $DestinationPath) -eq $ExpectedPackageHash) {
            Write-Host "D31内已有完整且SHA-256正确的刷机包，跳过重复上传。"
            return
        }
    }

    $chunkBytes = 16MB
    $remotePartial = "$DestinationPath.partial"
    $remoteChunk = "$DestinationPath.chunk"
    $remoteMarker = "$DestinationPath.upload"
    $marker = "D31_UPLOAD_V2|$ExpectedPackageHash|$chunkBytes"
    $currentMarker = Get-DeviceValue "cat $remoteMarker 2>/dev/null || true"
    if ($currentMarker -ne $marker) {
        Get-DeviceValue "rm -f $DestinationPath $remotePartial $remoteChunk $remoteMarker; printf '%s' '$marker' > $remoteMarker; sync"
    } else {
        Get-DeviceValue "rm -f $DestinationPath $remoteChunk"
    }

    $partialBytesText = Get-DeviceValue "if [ -f $remotePartial ]; then stat -c %s $remotePartial; else echo 0; fi"
    [int64]$offset = 0
    if (-not [int64]::TryParse($partialBytesText, [ref]$offset) -or
        $offset -lt 0 -or $offset -gt $ExpectedPackageBytes -or
        (($offset -ne $ExpectedPackageBytes) -and (($offset % $chunkBytes) -ne 0))) {
        Write-Host "D31内的断点长度无效，将从头重新上传。"
        Get-DeviceValue "rm -f $remotePartial $remoteChunk; : > $remotePartial; sync"
        $offset = 0
    }
    if ($offset -gt 0) {
        Write-Host "发现已校验上传断点：$offset/$ExpectedPackageBytes字节，将继续传输。"
    }

    $temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "Elfradio\D31FlashTool\upload"
    New-Item -ItemType Directory -Force -Path $temporaryDirectory | Out-Null
    $chunkTotal = [int][Math]::Ceiling($ExpectedPackageBytes / [double]$chunkBytes)
    try {
        while ($offset -lt $ExpectedPackageBytes) {
            $chunkNumber = [int]($offset / $chunkBytes) + 1
            $count = [int][Math]::Min($chunkBytes, $ExpectedPackageBytes - $offset)
            $localChunk = Join-Path $temporaryDirectory ("D31-v1.3.0-{0}-{1:D3}.part" -f $PID, $chunkNumber)
            try {
                New-LocalUploadChunk $SourcePath $offset $count $localChunk
                $chunkHash = (Get-FileHash -LiteralPath $localChunk -Algorithm SHA256).Hash
                Send-UploadChunk $localChunk $remoteChunk $count $chunkHash $chunkNumber $chunkTotal
                $newSizeText = Get-DeviceValue "cat $remoteChunk >> $remotePartial && sync && rm -f $remoteChunk && stat -c %s $remotePartial"
                [int64]$newSize = 0
                if (-not [int64]::TryParse($newSizeText, [ref]$newSize) -or $newSize -ne ($offset + $count)) {
                    throw "D31组装分块后的长度不匹配：$newSizeText"
                }
                $offset = $newSize
                $percent = [int][Math]::Floor($offset * 100.0 / $ExpectedPackageBytes)
                Write-Host "固件上传进度：$percent%（$offset/$ExpectedPackageBytes字节）"
            } finally {
                if (Test-Path -LiteralPath $localChunk) { Remove-Item -LiteralPath $localChunk -Force }
            }
        }
    } finally {
        if (Test-Path -LiteralPath $temporaryDirectory) {
            Get-ChildItem -LiteralPath $temporaryDirectory -Filter "D31-v1.3.0-$PID-*.part" -File |
                Remove-Item -Force
        }
    }

    if ((Get-RemoteSha256 $remotePartial) -ne $ExpectedPackageHash) {
        throw "D31内分块组装文件的SHA-256不匹配；未写Recovery命令"
    }
    Get-DeviceValue "mv $remotePartial $DestinationPath; rm -f $remoteChunk $remoteMarker; sync"
}

function Get-GzipRawInfo {
    param([string]$Path)
    $stream = [System.IO.File]::OpenRead($Path)
    $gzip = New-Object System.IO.Compression.GZipStream($stream, [System.IO.Compression.CompressionMode]::Decompress)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $buffer = New-Object byte[] (4 * 1024 * 1024)
    [int64]$total = 0
    try {
        while (($count = $gzip.Read($buffer, 0, $buffer.Length)) -gt 0) {
            [void]$sha.TransformBlock($buffer, 0, $count, $null, 0)
            $total += $count
        }
        [void]$sha.TransformFinalBlock((New-Object byte[] 0), 0, 0)
        return [PSCustomObject]@{
            Bytes = $total
            Sha256 = ([BitConverter]::ToString($sha.Hash)).Replace("-", "")
        }
    } finally {
        $sha.Dispose()
        $gzip.Dispose()
        $stream.Dispose()
    }
}

function Assert-RescueDirectory {
    param([string]$Path, [string]$Fingerprint)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "选择备份时必须先用本工具创建本机急救包"
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $manifestPath = Join-Path $resolved "D31_RESCUE_MANIFEST.txt"
    $systemPath = Join-Path $resolved "D31_RESCUE_SYSTEM.img.gz"
    $bootPath = Join-Path $resolved "D31_RESCUE_BOOT.img"
    $restorePath = Join-Path $resolved "D31_RESCUE_UPDATE.zip"
    $testPath = Join-Path $resolved "D31_RESCUE_TEST.zip"
    foreach ($required in @($manifestPath, $systemPath, $bootPath, $restorePath, $testPath, (Join-Path $resolved "SHA256SUMS.txt"))) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "本机急救包缺少文件：$required" }
    }
    $manifest = ConvertFrom-StringData -StringData (Get-Content -Raw -LiteralPath $manifestPath)
    if ($manifest.format -ne "D31_RESCUE_V1" -or $manifest.target_fingerprint -ne $Fingerprint) {
        throw "本机急救包的格式或目标构建不匹配"
    }
    foreach ($name in @("system_partition_bytes", "boot_partition_bytes", "userdata_partition_bytes", "system_gzip_bytes")) {
        [int64]$value = 0
        if (-not [int64]::TryParse($manifest[$name], [ref]$value)) { throw "本机急救清单字段无效：$name" }
    }
    if ([int64]$manifest.system_partition_bytes -ne [int64]$ExpectedSizes.system -or
        [int64]$manifest.boot_partition_bytes -ne [int64]$ExpectedSizes.boot -or
        [int64]$manifest.userdata_partition_bytes -ne [int64]$ExpectedSizes.userdata) {
        throw "本机急救包的分区尺寸不匹配"
    }
    if ((Get-RemoteSha256 "$ByName/recovery") -ne $manifest.recovery_sha256 -or
        (Get-RemoteSha256 "$ByName/proinfo") -ne $manifest.proinfo_sha256) {
        throw "本机急救包不属于当前连接的D31，或Recovery已经变化"
    }
    if ((Get-Item -LiteralPath $systemPath).Length -ne [int64]$manifest.system_gzip_bytes -or
        (Get-FileHash -LiteralPath $systemPath -Algorithm SHA256).Hash -ne $manifest.system_gzip_sha256) {
        throw "本机急救包的system压缩镜像损坏"
    }
    $rawInfo = Get-GzipRawInfo $systemPath
    if ($rawInfo.Bytes -ne [int64]$ExpectedSizes.system -or $rawInfo.Sha256 -ne $manifest.system_raw_sha256) {
        throw "本机急救包的system完整解压回验失败"
    }
    if ((Get-Item -LiteralPath $bootPath).Length -ne [int64]$ExpectedSizes.boot -or
        (Get-FileHash -LiteralPath $bootPath -Algorithm SHA256).Hash -ne $manifest.boot_sha256) {
        throw "本机急救包的boot镜像损坏"
    }
    if ((Get-FileHash -LiteralPath $restorePath -Algorithm SHA256).Hash -ne $ExpectedRescueRestoreHash -or
        (Get-FileHash -LiteralPath $testPath -Algorithm SHA256).Hash -ne $ExpectedRescueTestHash) {
        throw "本机急救包中的签名Recovery入口不是本工具批准的版本"
    }
    Write-Host "本机急救包独立复核通过：$resolved"
    return $resolved
}

function Wait-ForAndroid {
    param([int]$TimeoutSeconds = 1800)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastProbe = [datetime]::MinValue
    do {
        Start-Sleep -Seconds 10
        if (((Get-Date) - $lastProbe).TotalSeconds -ge 30) {
            $lastProbe = Get-Date
            try {
                $health = Invoke-D31Probe '/health'
                if ($health.service -eq 'd31-root-rescue' -and $health.uid -eq 0 -and -not $health.busy) {
                    $id = [guid]::NewGuid().ToString()
                    $command = 'if [ "$(getprop sys.boot_completed)" = 1 ]; then if [ "$(getprop init.svc.adbd)" != running ] || [ "$(getprop service.adb.tcp.port)" != 5555 ]; then setprop service.adb.tcp.port 5555; stop adbd; start adbd; echo ADB_RESTORED; fi; fi'
                    $result = Invoke-D31Probe '/exec' @{id=$id; command=$command; timeout=5}
                    if ($result.state -eq 'running') { $result = Invoke-D31Probe ("/jobs/" + $id) }
                    if ($result.output -match 'ADB_RESTORED') { Write-Step '已通过独立探针恢复D31机内ADB监听。' }
                }
            } catch { Add-SessionLog '独立探针尚未就绪，继续等待当前D31启动。' }
        }
        Invoke-AdbOptional connect $Serial | Out-Null
        $stateResult = Invoke-AdbOptional -s $Serial get-state
        $state = ($stateResult.Output | Out-String).Trim()
        if ($stateResult.ExitCode -eq 0 -and $state -eq "device") {
            $bootResult = Invoke-AdbOptional -s $Serial shell getprop sys.boot_completed
            $boot = ($bootResult.Output | Out-String).Trim()
            if ($bootResult.ExitCode -eq 0 -and $boot -eq "1") { return }
        }
    } while ((Get-Date) -lt $deadline)
    throw "Recovery刷写触发后，D31未在$TimeoutSeconds秒内恢复TCP ADB。请查看D31屏幕；不要盲目断电或重复刷写。"
}

function Invoke-D31Probe {
    param([string]$Path, $Body = $null)
    $request = [Net.HttpWebRequest]::Create("http://${DeviceIp}:8765$Path")
    $request.Proxy = $null
    $request.Timeout = 5000
    $request.ReadWriteTimeout = 5000
    if ($null -ne $Body) {
        $request.Method = 'POST'
        $request.ContentType = 'application/json; charset=utf-8'
        $bytes = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress))
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream()
        try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
    }
    $response = $request.GetResponse()
    try {
        $reader = New-Object IO.StreamReader($response.GetResponseStream())
        try { return ($reader.ReadToEnd() | ConvertFrom-Json) } finally { $reader.Dispose() }
    } finally { $response.Dispose() }
}

if (([int]$DevicePreflightOnly.IsPresent + [int]$PreflightOnly.IsPresent + [int]$PackagePreflightOnly.IsPresent) -gt 1) {
    throw '只读检查模式不能同时指定'
}
if ($SkipBackup -and -not [string]::IsNullOrWhiteSpace($RescueDirectory)) {
    throw "不能同时指定RescueDirectory和SkipBackup"
}

if (-not $DevicePreflightOnly) {
    if (-not (Test-Path -LiteralPath $Package -PathType Leaf)) { throw "所选签名ZIP不存在：$Package" }
    $Package = (Resolve-Path -LiteralPath $Package).Path
    Write-Host "[1/8] 校验本机签名刷机包。"
    $packageItem = Get-Item -LiteralPath $Package
    if ($packageItem.Length -ne $ExpectedPackageBytes) { throw "固件$($ApprovedPackage.version)长度不匹配：期望$ExpectedPackageBytes，实际$($packageItem.Length)字节；尚未连接设备" }
    $packageHash = (Get-FileHash -LiteralPath $Package -Algorithm SHA256).Hash
    if ($packageHash -ne $ExpectedPackageHash) { throw "签名ZIP的SHA-256不匹配，拒绝继续" }
}
if ($PackagePreflightOnly) {
    Write-Host "PACKAGE PREFLIGHT PASS：固件$($ApprovedPackage.version)，$ExpectedPackageBytes 字节，$ExpectedPackageHash；未连接设备。"
    return
}
if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw "刷机目录缺少ADB：$Adb" }
if ($Serial -notmatch '^\d{1,3}(\.\d{1,3}){3}:5555$' -or $AdbPort -ne 5042) {
    throw 'D31必须指定完整IPv4:5555序列号，并使用电脑ADB端口5042'
}
$DeviceIp = $Serial.Substring(0, $Serial.LastIndexOf(':'))

Write-Host $(if ($DevicePreflightOnly) { "[1/3] 连接D31并核对root、构建和网络。" } else { "[2/8] 连接D31并核对root、构建和有线网卡。" })
Invoke-Adb connect $Serial | Out-Null
$state = ((Invoke-Adb -s $Serial get-state) -join "`n").Trim()
if ($state -ne "device") { throw "D31 ADB状态不是device：$state" }
$identity = Get-DeviceValue "id"
if ($identity -notmatch 'uid=0\(root\)') { throw "D31 ADB shell不是root：$identity" }
$fingerprint = Get-DeviceValue "getprop ro.build.fingerprint"
if ($fingerprint -ne $ExpectedFingerprint) { throw "构建指纹不匹配：$fingerprint" }
$ethernet = Get-DeviceValue "ip -4 addr show dev eth0"
if ($ethernet -notmatch "(?m)\binet\s+$([regex]::Escape($DeviceIp))/") {
    if ($DevicePreflightOnly) {
        Write-Host "提示：当前目标地址$DeviceIp不属于D31的eth0；可以完成只读检查，但开始刷机前必须改用有线IP重新连接。"
    } else {
        throw "目标地址$DeviceIp不属于D31的eth0；本工具只允许使用有线网络ADB刷机"
    }
}
$busybox = Get-DeviceValue "busybox 2>/dev/null | head -n 1"
if ($busybox -notmatch 'BusyBox') { throw "目标D31缺少已验证的busybox工具，无法执行设备端哈希校验" }

Write-Host $(if ($DevicePreflightOnly) { "[2/3] 核对目标分区尺寸、Recovery入口和存储空间。" } else { "[3/8] 核对目标分区尺寸、Recovery入口和存储空间。" })
foreach ($name in $ExpectedSizes.Keys) {
    $actual = Get-BlockSize $name
    if ($actual -ne [int64]$ExpectedSizes[$name]) { throw "$name分区尺寸不匹配：$actual" }
}
if ((Get-RemoteSha256 "$ByName/boot") -ne $ApprovedPackage.bootSha256) {
    throw '当前boot与固件批准基线不一致；本包不写boot，已在上传前拒绝'
}
foreach ($name in @($RequiredBackupPartitions + $AdditionalBackupPartitions)) {
    $exists = Get-DeviceValue "if [ -e $ByName/$name ]; then echo YES; else echo NO; fi"
    if ($exists -ne "YES") { throw "目标机缺少分区：$name" }
}
$cacheMount = Get-DeviceValue "mount | grep ' /cache '"
if ($cacheMount -notmatch '/cache') { throw "目标机/cache未挂载，不能安全写入Recovery命令" }
$availableText = Get-DeviceValue "df /data | busybox tail -n 1 | busybox awk '{print `$4}'"
$availableBytes = Convert-AndroidSizeToBytes $availableText
if ($availableBytes -lt ($ExpectedPackageBytes + 536870912L)) {
    throw "D31的/data空间不足；上传刷机包后必须至少保留512MiB余量"
}

if ($DevicePreflightOnly) {
    Write-Host "[3/3] 设备只读检查通过：root、目标构建、分区尺寸、Recovery入口和存储空间全部匹配。"
    return
}

if ($PreflightOnly) {
    Write-Host "只读准入检查通过：签名ZIP、root、有线地址、目标构建、分区尺寸、Recovery入口和存储空间全部匹配。"
    return
}

if ($SkipBackup) {
    Write-Host "[4/8] 用户选择跳过原系统备份；本次刷机不会创建急救恢复包。"
} else {
    Write-Host "[4/8] 独立复核电脑硬盘上的本机急救包及其设备绑定。"
    $RescueDirectory = Assert-RescueDirectory $RescueDirectory $fingerprint
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$logRoot = Join-Path $PackageRoot "logs\D31_recovery_flash_$timestamp"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$SessionLog = Join-Path $logRoot "刷机记录.txt"
Write-Utf8 $SessionLog @("D31 Recovery刷机记录", "目标：$Serial", "开始时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
Write-Utf8 (Join-Path $logRoot "设备属性.txt") @((Get-DeviceValue "getprop") -split "`n")
Write-Utf8 (Join-Path $logRoot "分区映射.txt") @((Get-DeviceValue "ls -l $ByName") -split "`n")

Write-Step "[5/8] 分块上传签名ZIP（$ExpectedPackageBytes 字节）；支持断线重试和下次续传。"
Copy-PackageToDeviceResumable $Package $RemotePackage

Write-Step "[6/8] 校验D31内的ZIP长度和SHA-256。"
$remotePackageBytesText = Get-DeviceValue "stat -c %s $RemotePackage"
[int64]$remotePackageBytes = 0
if (-not [int64]::TryParse($remotePackageBytesText, [ref]$remotePackageBytes) -or
    $remotePackageBytes -ne $ExpectedPackageBytes) {
    throw "D31内的ZIP长度不匹配：$remotePackageBytesText"
}
$remotePackageHash = Get-RemoteSha256 $RemotePackage
if ($remotePackageHash -ne $ExpectedPackageHash) { throw "D31内的ZIP SHA-256不匹配" }

Write-Step "[7/8] 写入Recovery安装命令并重启；从此步骤开始会清空userdata。"
$recoveryCommand = "mkdir -p /cache/recovery; printf '%s\n' '--update_package=$RemotePackage' '--locale=zh_CN' > /cache/recovery/command; chmod 0600 /cache/recovery/command; cat /cache/recovery/command; sync"
$commandResult = Get-DeviceValue $recoveryCommand
if ($commandResult -notmatch [regex]::Escape("--update_package=$RemotePackage")) {
    throw "Recovery命令回读不一致，尚未重启"
}
Invoke-Adb -s $Serial reboot recovery | Out-Null

Write-Step "[8/8] 等待Recovery安装和D31首次启动，最长30分钟。"
Wait-ForAndroid
$postFingerprint = Get-DeviceValue "getprop ro.build.fingerprint"
if ($postFingerprint -ne $ExpectedFingerprint) { throw "首次启动后的构建指纹异常：$postFingerprint" }
Assert-InstalledPayload
foreach ($packageName in @("org.mozilla.firefox", "org.videolan.vlc", "com.loudtalks", "org.telegram.messenger.web", "net.thunderbird.android", "me.zhanghai.android.files", "net.elfradio.d31bootstrap", "net.elfradio.d31zelloguard", "net.elfradio.d31phone.debug", "net.elfradio.d31system")) {
    $verify = Get-DeviceValue "pm path $packageName"
    if ($verify -notmatch '(?m)^package:/data/app/.+/base\.apk\s*$') {
        throw "首次启动后没有检测到预装应用：$packageName"
    }
}
$forbiddenSystem = Get-DeviceValue "for p in /system/vendor/3rd-app/android.apk /system/vendor/3rd-app/moffice.apk /system/vendor/3rd-app/tr069.apk /system/vendor/3rd-app/tr069proxy.apk /system/vendor/3rd-app/emu.apk /system/vendor/3rd-app/daemon.apk /system/app/MtkBrowser /system/app/HTMLViewer /system/app/Omacp; do [ -e `$p ] && echo `$p; done"
if (-not [string]::IsNullOrWhiteSpace($forbiddenSystem)) {
    throw "首次启动后发现本应物理删除的系统组件：$forbiddenSystem"
}
$patchState = Get-DeviceValue 'b=$(cat /proc/sys/kernel/random/boot_id); p=/data/local/d31-startup-handover/runs/$b/result.txt; i=0; while [ $i -lt 120 ] && ! grep -q "HANDOVER_EXIT=" "$p" 2>/dev/null; do sleep 1; i=$((i+1)); done; cat "$p" 2>/dev/null'
if ($patchState -notmatch '(?m)^.*HANDOVER_COMPLETE\s*$' -or $patchState -notmatch '(?m)^HANDOVER_EXIT=0\s*$') {
    throw "首次启动后桌面、TLS和会议入口补丁没有进入就绪状态：$patchState"
}
$initialization = Get-DeviceValue 'test ! -e /data/local/d31-startup-handover/factory-init-required && cat /data/local/d31-startup-handover/factory-init-complete && CLASSPATH=/data/local/d31-startup-handover/handover.jar app_process /system/bin FactoryInit --verify'
if ($initialization -notmatch 'FACTORY_STATE_VERIFIED') { throw "首次初始化的权限、组件或短信阻断未通过回读：$initialization" }
$tcpDefault = Get-DeviceValue 'cat /data/local/d31-startup-handover/factory-runtime-complete && grep -q ''name="TcpAcclerate" value="false"\|value="false" name="TcpAcclerate"'' /data/data/com.starnet.nexui/shared_prefs/starNetBaseConfigFile.xml && echo TCP_DEFAULT_OK'
if ($tcpDefault -notmatch 'TCP_DEFAULT_OK') { throw '首次启动的TCP加速关闭设置没有通过回读。' }
$supportReady = Get-DeviceValue 'test -S /dev/socket/d31-system-actions && ps | grep -E "net.elfradio.d31system|/data/local/d31-system-support/guard"'
if ($supportReady -notmatch 'net\.elfradio\.d31system' -or $supportReady -notmatch '/data/local/d31-system-support/guard') {
    throw '独立存储服务或本机命令通道未就绪，不能判定刷机通过。'
}
$appPage = Get-DeviceValue "cat /data/starnet/launcher/config/config-tab"
$appPageDocument = $appPage | ConvertFrom-Json
$fileManagerItems = @($appPageDocument.tabs | ForEach-Object { $_.items } | Where-Object { $_.info.app.packageName -eq 'me.zhanghai.android.files' })
if ($fileManagerItems.Count -ne 1 -or $fileManagerItems[0].info.app.name -ne '文件管理器') {
    throw '实际桌面应用页的文件管理器名称不正确，不能判定刷机通过。'
}
foreach ($marker in @('org.mozilla.firefox', 'org.videolan.vlc', 'com.loudtalks', 'org.telegram.messenger.web', 'com.android.calculator2', 'net.elfradio.d31bootstrap', 'com.android.settings', 'me.zhanghai.android.files')) {
    if ($appPage -notmatch [regex]::Escape($marker)) {
        throw "首次启动后的Nexui应用页缺少入口：$marker"
    }
}
$recoveryLog = Get-DeviceValue "cat /cache/recovery/last_log 2>/dev/null"
Write-Utf8 (Join-Path $logRoot "Recovery-last_log.txt") @($recoveryLog -split "`n")
if ($recoveryLog -notmatch '刷机完成：所有软件均为未配置状态') {
    throw "D31已经重新启动，但Recovery日志中缺少安装器完成标记；请保留现场检查"
}

Write-Step "完整Recovery刷机和首次启动验证全部完成。"
if ($SkipBackup) {
    Write-Step "本次刷机由用户选择不创建原系统备份。"
} else {
    Write-Step "原系统备份保存在电脑硬盘：$RescueDirectory"
}
Write-Step "刷机记录：$logRoot"
