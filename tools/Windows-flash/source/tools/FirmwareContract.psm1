function Read-ApprovedFirmwareContract {
    param([string]$ApprovedPackagePath, [string]$InstalledFilesPath, [switch]$Release,
        [ValidateSet('1.4.4','1.4.5')][string]$ExpectedReleaseVersion='1.4.4')
    $approved = Get-Content -Raw -LiteralPath $ApprovedPackagePath -Encoding UTF8 | ConvertFrom-Json
    if ($approved.version -notmatch '^\d+\.\d+\.\d+$' -or $approved.fileName -cne ("D31_SVP3390_Factory_Flash_v"+$approved.version+"_testkey.zip") -or
        [string]$approved.bytes -notmatch '^[1-9][0-9]{0,11}$' -or $approved.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or
        $approved.bootSha256 -notmatch '^[a-fA-F0-9]{64}$') { throw '固件批准合同无效' }
    if ($Release -and $approved.version -cne $ExpectedReleaseVersion) { throw "正式构建只接受已批准$ExpectedReleaseVersion，不能使用其它固件代替" }
    $github = 'https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v'+$approved.version+'/'+$approved.fileName
    $cdn = 'https://cdn.elfradio.net/d31/'+$approved.fileName
    if ([version]$approved.version -ge [version]'1.4.4') {
        if ($approved.githubUrl -cne $github -or $approved.cloudflareUrl -cne $cdn) { throw '新固件下载地址尚未批准或不匹配' }
        if (-not $InstalledFilesPath -or $approved.installedFilesSha256 -notmatch '^[a-fA-F0-9]{64}$' -or
            (Get-FileHash -LiteralPath $InstalledFilesPath).Hash -ne $approved.installedFilesSha256) { throw '安装清单未绑定批准摘要' }
        $remote = $approved.elfRemote
        if (-not $remote -or $remote.package -cne 'net.elfradio.d31bootstrap' -or
            $remote.systemApk -cne '/system/priv-app/D31ElfRemote/D31ElfRemote.apk' -or
            [string]$remote.versionCode -notmatch '^[1-9][0-9]{0,8}$' -or [int]$remote.versionCode -lt 96 -or
            $remote.versionName -notmatch '^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?$' -or $remote.sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw '完整系统APK合同无效' }
    } elseif ($approved.version -cne '1.4.3') { throw '未支持的旧固件合同' }
    return [pscustomobject]@{Approval=$approved;GitHubUrl=$github;CloudflareUrl=$cdn}
}
function Assert-FirmwareNativePayload {
    param([string]$FullApk, [string]$InstalledFilesPath)
    $installed = Get-Content -LiteralPath $InstalledFilesPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $path='/system/priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so'
    $items=@($installed | Where-Object path -CEQ $path)
    if ($items.Count -ne 1 -or $items[0].mode -cne '0644' -or [string]$items[0].uid -ne '0' -or [string]$items[0].gid -ne '0') { throw '系统原生库遗漏或属性不符' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($FullApk)
    try {
        $entries=@($zip.Entries | Where-Object FullName -CEQ 'lib/armeabi-v7a/libjingle_peerconnection_so.so')
        if($entries.Count -ne 1){throw '完整APK的原生库条目不唯一或缺失'}
        $stream=$entries[0].Open();$sha=[Security.Cryptography.SHA256]::Create()
        try {$hash=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant()} finally {$stream.Dispose();$sha.Dispose()}
        if($hash -ine $items[0].sha256){throw '系统原生库与冻结完整APK字节不一致'}
        return [pscustomobject]@{path=$path;bytes=$entries[0].Length;sha256=$hash}
    } finally {$zip.Dispose()}
}
Export-ModuleMember -Function Read-ApprovedFirmwareContract,Assert-FirmwareNativePayload
