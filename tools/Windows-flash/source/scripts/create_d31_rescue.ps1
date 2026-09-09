param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [int]$AdbPort = 5042,
    [Parameter(Mandatory = $true)][string]$OutputBase
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding
$PackageRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$Adb = Join-Path $PackageRoot "tools\adb.exe"
$RescueAssets = Join-Path $PackageRoot "rescue"
$RestoreLauncher = Join-Path $RescueAssets "D31_RESCUE_UPDATE.zip"
$TestLauncher = Join-Path $RescueAssets "D31_RESCUE_TEST.zip"
$ExpectedFingerprint = "alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys"
$ExpectedRecoveryHash = "173CB00459E4CDFC2B4BF04D7BED4A130947795F8ACB3B557BBEF2C218B2E7D5"
$ExpectedRestoreLauncherHash = "783D95431DCE93094347C5CEDA31F367102CEF1786DF4E18A171914C74322BC4"
$ExpectedTestLauncherHash = "012FBBA56C97AE9A8A2A7E034EAB5CAB396C8D7E096FFEEB9E551DE6713BFD18"
$ByName = "/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name"
$ExpectedSizes = @{
    system = 1610612736L
    boot = 16777216L
    userdata = 13517717504L
}
$PrivatePartitions = @("nvram", "nvdata", "protect1", "protect2", "proinfo", "recovery", "secro", "seccfg", "frp")
$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$RemoteRoot = "/data/local/tmp/d31-rescue-$Timestamp"
$IncompleteRoot = Join-Path $OutputBase ("D31_本机急救_$Timestamp-未完成")
$FinalRoot = Join-Path $OutputBase ("D31_本机急救_$Timestamp")
$CardRoot = Join-Path $IncompleteRoot "需要抢救时复制到TF卡或U盘"
$PrivateRoot = Join-Path $IncompleteRoot "禁止公开的本机私有备份"
$SessionLog = Join-Path $IncompleteRoot "创建记录.txt"

function Write-Utf8 {
    param([string]$Path, [string[]]$Lines)
    [System.IO.File]::WriteAllLines($Path, $Lines, [System.Text.UTF8Encoding]::new($false))
}

function Add-Log {
    param([string]$Text)
    [System.IO.File]::AppendAllText(
        $SessionLog,
        (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "  " + $Text + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false))
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $Adb -P $AdbPort @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    Add-Log ("ADB " + ($Arguments -join " "))
    if ($output) { Add-Log ($output -join [Environment]::NewLine) }
    if ($exitCode -ne 0) {
        throw "ADB命令失败：adb -P $AdbPort $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return $output
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

function Get-RemoteSha256 {
    param([string]$Path)
    $line = Get-DeviceValue "busybox sha256sum $Path"
    if ($line -notmatch '^([0-9a-fA-F]{64})\s+') { throw "无法解析设备端SHA-256：$line" }
    return $Matches[1].ToUpperInvariant()
}

function Get-BlockSize {
    param([string]$Name)
    $text = Get-DeviceValue "blockdev --getsize64 $ByName/$Name"
    [int64]$value = 0
    if (-not [int64]::TryParse($text, [ref]$value)) { throw "无法读取$Name分区尺寸：$text" }
    return $value
}

function Get-RemoteFileSize {
    param([string]$Path)
    $text = Get-DeviceValue "stat -c %s $Path"
    [int64]$value = 0
    if (-not [int64]::TryParse($text, [ref]$value)) { throw "无法读取远端文件长度：$Path" }
    return $value
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

function Pull-VerifiedFile {
    param([string]$RemotePath, [string]$LocalPath, [int64]$ExpectedBytes, [string]$ExpectedHash)
    Invoke-Adb -s $Serial pull $RemotePath $LocalPath | Write-Host
    $item = Get-Item -LiteralPath $LocalPath
    $hash = (Get-FileHash -LiteralPath $LocalPath -Algorithm SHA256).Hash
    if ($item.Length -ne $ExpectedBytes -or $hash -ne $ExpectedHash) {
        throw "拉回文件的长度或SHA-256不一致：$LocalPath"
    }
}

if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw "工具包缺少ADB：$Adb" }
if (-not (Test-Path -LiteralPath $RestoreLauncher -PathType Leaf) -or
    -not (Test-Path -LiteralPath $TestLauncher -PathType Leaf)) {
    throw "工具包缺少已签名的D31急救入口"
}
if ((Get-FileHash -LiteralPath $RestoreLauncher -Algorithm SHA256).Hash -ne $ExpectedRestoreLauncherHash -or
    (Get-FileHash -LiteralPath $TestLauncher -Algorithm SHA256).Hash -ne $ExpectedTestLauncherHash) {
    throw "工具包中的D31急救入口损坏或版本不匹配"
}
if ($Serial -notmatch '^\d{1,3}(\.\d{1,3}){3}:5555$') { throw "目标ADB地址格式不正确：$Serial" }
if ((Test-Path -LiteralPath $IncompleteRoot) -or (Test-Path -LiteralPath $FinalRoot)) {
    throw "本次急救输出目录已经存在，拒绝覆盖"
}
New-Item -ItemType Directory -Force -Path $OutputBase, $CardRoot, $PrivateRoot | Out-Null
$outputDrive = New-Object System.IO.DriveInfo([System.IO.Path]::GetPathRoot((Resolve-Path -LiteralPath $OutputBase).Path))
if ($outputDrive.AvailableFreeSpace -lt 4GB) {
    throw "急救包输出磁盘可用空间不足4GiB，拒绝开始备份"
}
Write-Utf8 $SessionLog @("D31本机急救包创建记录", "开始时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")

try {
    Write-Host "[1/9] 连接并核对目标D31、root和有线网卡。"
    Invoke-Adb connect $Serial | Out-Null
    if (((Invoke-Adb -s $Serial get-state) -join "`n").Trim() -ne "device") { throw "D31 ADB状态不是device" }
    if ((Get-DeviceValue "id") -notmatch 'uid=0\(root\)') { throw "D31 ADB shell不是root" }
    $fingerprint = Get-DeviceValue "getprop ro.build.fingerprint"
    if ($fingerprint -ne $ExpectedFingerprint) { throw "构建指纹不受当前刷机包支持：$fingerprint" }
    $deviceIp = $Serial.Substring(0, $Serial.LastIndexOf(':'))
    if ((Get-DeviceValue "ip -4 addr show dev eth0") -notmatch "(?m)\binet\s+$([regex]::Escape($deviceIp))/") {
        throw "目标地址不属于eth0；创建急救包和刷机都只允许有线TCP ADB"
    }

    Write-Host "[2/9] 核对分区布局和原厂Recovery信任基线。"
    foreach ($name in $ExpectedSizes.Keys) {
        if ((Get-BlockSize $name) -ne [int64]$ExpectedSizes[$name]) { throw "$name 分区尺寸不匹配" }
    }
    foreach ($name in $PrivatePartitions) {
        if ((Get-DeviceValue "if [ -e $ByName/$name ]; then echo YES; else echo NO; fi") -ne "YES") {
            throw "目标机缺少分区：$name"
        }
    }
    $recoveryHash = Get-RemoteSha256 "$ByName/recovery"
    if ($recoveryHash -ne $ExpectedRecoveryHash) {
        throw "目标机Recovery与已验证版本不同，无法保证接受急救入口签名；已拒绝刷机。Recovery SHA-256=$recoveryHash"
    }
    $proinfoHash = Get-RemoteSha256 "$ByName/proinfo"

    Write-Host "[3/9] 检查设备临时空间并读取原始system哈希。"
    $availableText = Get-DeviceValue "df /data | busybox tail -n 1 | busybox awk '{print `$4}'"
    $availableBytes = Convert-AndroidSizeToBytes $availableText
    if ($availableBytes -lt 2GB) {
        throw "D31的/data可用空间不足2GiB，无法安全生成原始system压缩镜像"
    }
    Get-DeviceValue "rm -rf $RemoteRoot; mkdir -p $RemoteRoot" | Out-Null
    $systemRawHash = Get-RemoteSha256 "$ByName/system"

    Write-Host "[4/9] 在目标机只读压缩原始system分区。此步骤可能耗时较长。"
    $remoteSystem = "$RemoteRoot/$([IO.Path]::GetFileName('D31_RESCUE_SYSTEM.img.gz'))"
    Get-DeviceValue "busybox gzip -1 -c $ByName/system > $remoteSystem; sync" | Out-Null
    $systemGzipBytes = Get-RemoteFileSize $remoteSystem
    $systemGzipHash = Get-RemoteSha256 $remoteSystem
    $localSystem = Join-Path $CardRoot "D31_RESCUE_SYSTEM.img.gz"
    Pull-VerifiedFile $remoteSystem $localSystem $systemGzipBytes $systemGzipHash
    Get-DeviceValue "rm -f $remoteSystem" | Out-Null
    $rawInfo = Get-GzipRawInfo $localSystem
    if ($rawInfo.Bytes -ne [int64]$ExpectedSizes.system -or $rawInfo.Sha256 -ne $systemRawHash) {
        throw "电脑端完整解压回验的system长度或SHA-256不一致"
    }

    Write-Host "[5/9] 备份并双端校验原始boot分区。"
    $remoteBoot = "$RemoteRoot/D31_RESCUE_BOOT.img"
    Get-DeviceValue "busybox dd if=$ByName/boot of=$remoteBoot bs=4M; sync" | Out-Null
    $bootBytes = Get-RemoteFileSize $remoteBoot
    $bootHash = Get-RemoteSha256 $remoteBoot
    if ($bootBytes -ne [int64]$ExpectedSizes.boot) { throw "boot备份长度不匹配" }
    $localBoot = Join-Path $CardRoot "D31_RESCUE_BOOT.img"
    Pull-VerifiedFile $remoteBoot $localBoot $bootBytes $bootHash
    Get-DeviceValue "rm -f $remoteBoot" | Out-Null

    Write-Host "[6/9] 独立保存本机身份、校准和Recovery分区。"
    $privateManifest = New-Object System.Collections.Generic.List[string]
    $privateManifest.Add("分区`t字节数`tSHA-256")
    foreach ($name in $PrivatePartitions) {
        $remote = "$RemoteRoot/$name.img"
        $local = Join-Path $PrivateRoot "$name.img"
        Get-DeviceValue "busybox dd if=$ByName/$name of=$remote bs=4M; sync" | Out-Null
        $bytes = Get-RemoteFileSize $remote
        $hash = Get-RemoteSha256 $remote
        Pull-VerifiedFile $remote $local $bytes $hash
        Get-DeviceValue "rm -f $remote" | Out-Null
        $privateManifest.Add("$name`t$bytes`t$hash")
        Write-Utf8 (Join-Path $PrivateRoot "私有备份清单.tsv") $privateManifest
    }

    Write-Host "[7/9] 生成与本机Recovery和proinfo绑定的急救清单。"
    $manifest = @(
        "format=D31_RESCUE_V1",
        "target_fingerprint=$fingerprint",
        "created_local=$(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK')",
        "system_partition_bytes=$($ExpectedSizes.system)",
        "boot_partition_bytes=$($ExpectedSizes.boot)",
        "userdata_partition_bytes=$($ExpectedSizes.userdata)",
        "recovery_sha256=$recoveryHash",
        "proinfo_sha256=$proinfoHash",
        "system_gzip_bytes=$systemGzipBytes",
        "system_gzip_sha256=$systemGzipHash",
        "system_raw_sha256=$systemRawHash",
        "boot_sha256=$bootHash"
    )
    Write-Utf8 (Join-Path $CardRoot "D31_RESCUE_MANIFEST.txt") $manifest
    Copy-Item -LiteralPath $RestoreLauncher -Destination (Join-Path $CardRoot "D31_RESCUE_UPDATE.zip")
    Copy-Item -LiteralPath $TestLauncher -Destination (Join-Path $CardRoot "D31_RESCUE_TEST.zip")

    Write-Host "[8/9] 写入中文急救说明和完整SHA-256清单。"
    $instructions = @(
        "D31本机急救恢复说明",
        "",
        "这套文件只适用于创建它的这一台D31，禁止上传、分享或用于另一台设备。",
        "它保存的是第一次刷机前该机自己的system和boot，不包含userdata、账号、密码、通信录或SIP配置。",
        "整套备份已经保存在电脑硬盘。正常刷机不需要插入U盘或TF卡。",
        "D31_RESCUE_TEST.zip只做完整读取和绑定校验，不写任何分区；它是可选测试，不是刷机前提。",
        "D31_RESCUE_UPDATE.zip会恢复原始system和boot，并清空userdata。",
        "",
        "刷成无法启动后的恢复：",
        "1. 将TF卡或U盘格式化为FAT32。不要使用NTFS。",
        "2. 把本目录连同全部文件原样复制到介质，文件必须保持在同一目录。",
        "3. 保持D31稳定供电，插入介质并进入原厂Recovery。",
        "4. TF卡选择Apply update from SD card；U盘选择Recovery显示的USB OTG介质入口。",
        "5. 选择D31_RESCUE_UPDATE.zip。Recovery会先核对设备、分区尺寸、system和boot全部哈希；任一不匹配都会在写入前停止。",
        "6. 开始写入后严禁断电。出现D31 emergency restore completed后选择Reboot system now。",
        "7. 首次启动会重建userdata和ART缓存，可能明显慢于普通开机。",
        "8. 若显示ERROR 22，system和boot已经恢复，但userdata清除失败；请在Recovery中手动执行Wipe data/factory reset后重启。",
        "",
        "禁止把名为禁止公开的本机私有备份的目录复制到公共存储。它包含本机身份和校准分区，只能用于原机工程恢复。"
    )
    Write-Utf8 (Join-Path $CardRoot "急救恢复说明.txt") $instructions
    $hashLines = Get-ChildItem -LiteralPath $CardRoot -File |
        Where-Object Name -ne "SHA256SUMS.txt" |
        Sort-Object Name |
        ForEach-Object { "{0}  {1}" -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash, $_.Name }
    Write-Utf8 (Join-Path $CardRoot "SHA256SUMS.txt") $hashLines

    Write-Host "[9/9] 重新核对急救目录并原子完成命名。"
    foreach ($line in $hashLines) {
        if ($line -notmatch '^([0-9A-F]{64})  (.+)$') { throw "急救SHA-256清单格式异常" }
        $candidate = Join-Path $CardRoot $Matches[2]
        if ((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash -ne $Matches[1]) {
            throw "急救文件最终SHA-256复核失败：$candidate"
        }
    }
    Write-Utf8 (Join-Path $IncompleteRoot "本机急救包状态.txt") @(
        "状态=创建完成，已保存到电脑硬盘",
        "急救目录=需要抢救时复制到TF卡或U盘",
        "私有备份目录=禁止公开的本机私有备份",
        "创建时间=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    )
    Get-DeviceValue "rm -rf $RemoteRoot" | Out-Null
    Move-Item -LiteralPath $IncompleteRoot -Destination $FinalRoot
    $FinalCardRoot = Join-Path $FinalRoot "需要抢救时复制到TF卡或U盘"
    Write-Host "D31本机急救包创建通过：$FinalCardRoot"
} catch {
    Add-Log ("失败：" + $_.Exception.Message)
    throw
} finally {
    try { Get-DeviceValue "rm -rf $RemoteRoot" | Out-Null } catch { }
}
