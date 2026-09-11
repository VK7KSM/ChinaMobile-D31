# 默认仅离线准备独立迁移目录，不连接设备，不修改批准固件。
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$FullApk,
    [Parameter(Mandatory=$true)][string]$ManifestPath,
    [Parameter(Mandatory=$true)][string]$CheckerJar,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$CheckerSha256,
    [Parameter(Mandatory=$true)][string]$OriginalHook,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$OriginalHookSha256,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$AaptPath,
    [Parameter(Mandatory=$true)][string]$JavaPath,
    [Parameter(Mandatory=$true)][string]$ApkSignerJar,
    [Parameter(Mandatory=$true)][string]$BashPath,
    [string]$ProbeCommonModule
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ProbeCommonModule) {
    $localModule=Join-Path $PSScriptRoot '../../../d31_flash_tool/tools/BasicProbe.Common.psm1'
    $publishedModule=Join-Path $PSScriptRoot '../../../../Windows-flash/source/tools/BasicProbe.Common.psm1'
    if(Test-Path -LiteralPath $localModule -PathType Leaf){$ProbeCommonModule=$localModule}
    elseif(Test-Path -LiteralPath $publishedModule -PathType Leaf){$ProbeCommonModule=$publishedModule}
    else{throw '未找到配套Windows公共模块，请明确指定ProbeCommonModule'}
}
Import-Module $ProbeCommonModule -Force -DisableNameChecking
$contract = Read-ProbeContract $ManifestPath
$full = Test-ProbeApk $FullApk $contract full $AaptPath $JavaPath $ApkSignerJar
Assert-Probe ($full.versionCode -ge 96) 'migration-needs-full96-or-newer'
Assert-Probe ((Get-FileHash -LiteralPath $CheckerJar).Hash -ieq $CheckerSha256) 'checker-hash'
Assert-Probe ((Get-FileHash -LiteralPath $OriginalHook).Hash -ieq $OriginalHookSha256) 'original-hook-hash'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Get-Item -LiteralPath $CheckerJar).FullName)
try {
    $dex = @($zip.Entries | Where-Object FullName -CEQ 'classes.dex')
    Assert-Probe ($dex.Count -eq 1 -and $dex[0].Length -gt 112) 'checker-dex-missing'
    $stream = $dex[0].Open()
    try {
        $magic = New-Object byte[] 8
        Assert-Probe ($stream.Read($magic,0,8) -eq 8 -and [Text.Encoding]::ASCII.GetString($magic) -ceq "dex`n035`0") 'checker-not-api23-dex'
    } finally { $stream.Dispose() }
} finally { $zip.Dispose() }
$utf8 = [Text.UTF8Encoding]::new($false,$true)
$originalBytes = [IO.File]::ReadAllBytes((Get-Item -LiteralPath $OriginalHook).FullName)
$hook = $utf8.GetString($originalBytes)
Assert-Probe ($hook.StartsWith("#!/system/bin/sh`n") -and -not $hook.Contains("`r") -and -not $hook.Contains("`0")) 'hook-needs-original-utf8-lf-shell'
Assert-Probe (-not $hook.Contains('d31-elfremote') -and -not $hook.Contains('D31_ELFREMOTE')) 'hook-already-managed'
# 插入首行之后，避免旧钩子的exit或exec使追加启动段不可达；其余原字节保持不变。
$block = @'
# D31_ELFREMOTE_HOST_BEGIN
if [ -f /system/etc/d31-elfremote.system ] && [ -x /system/bin/d31-elfremote-start ]; then
  /system/bin/busybox start-stop-daemon -S -b -x /system/bin/d31-elfremote-start </dev/null >/dev/null 2>&1
fi
# D31_ELFREMOTE_HOST_END
'@
$block = $block.Replace("`r`n","`n") + "`n"
$derived = "#!/system/bin/sh`n" + $block + $hook.Substring("#!/system/bin/sh`n".Length)
$destination = [IO.Path]::GetFullPath($OutputDirectory)
Assert-Probe (-not (Test-Path -LiteralPath $destination)) 'stage-already-exists'
$parent = Split-Path -Parent $destination
Assert-Probe (Test-Path -LiteralPath $parent -PathType Container) 'stage-parent-missing'
# Directory.CreateDirectory会接受并发已建目录，使用原生New-Item拒绝覆盖。
$null = New-Item -ItemType Directory -Path $destination -ErrorAction Stop
$sources = [ordered]@{
    'remote.apk'=$FullApk; 'remote-variants.json'=$ManifestPath; 'migration-check.jar'=$CheckerJar
    'original-hook.sh'=$OriginalHook
    'install-remote-system.sh'=(Join-Path $PSScriptRoot '../install-remote-system.sh')
    'replace-remote-system.sh'=(Join-Path $PSScriptRoot '../replace-remote-system.sh')
    'remote-system-transaction.sh'=(Join-Path $PSScriptRoot 'remote-system-transaction.sh')
    'start.sh'=(Join-Path $PSScriptRoot '../start-remote-system.sh')
    'Invoke-SystemMigration.ps1'=(Join-Path $PSScriptRoot 'Invoke-SystemMigration.ps1')
}
foreach ($name in $sources.Keys) {
    if ($name.EndsWith('.sh') -and $name -ne 'original-hook.sh') {
        # 只规范化仓库脚本的打包副本；真实钩子原像不转换字节。
        $sourceText=$utf8.GetString([IO.File]::ReadAllBytes((Get-Item -LiteralPath $sources[$name]).FullName))
        [IO.File]::WriteAllBytes((Join-Path $destination $name),$utf8.GetBytes($sourceText.Replace("`r`n","`n")))
    } else { Copy-ProbeVerified $sources[$name] (Join-Path $destination $name) (Get-FileHash -LiteralPath $sources[$name]).Hash }
}
[IO.File]::WriteAllBytes((Join-Path $destination 'install-recovery.sh'),$utf8.GetBytes($derived))
[IO.File]::WriteAllBytes((Join-Path $destination 'marker'),[Text.Encoding]::ASCII.GetBytes("1`n"))
# 再验复制结果，防准备期间输入改变；只执行语法检查，不执行钩子内容。
$null = Test-ProbeApk (Join-Path $destination 'remote.apk') (Read-ProbeContract (Join-Path $destination 'remote-variants.json')) full $AaptPath $JavaPath $ApkSignerJar
Assert-Probe ((Get-FileHash -LiteralPath (Join-Path $destination 'remote.apk')).Hash -ieq $full.sha256) 'copied-full-changed'
Assert-Probe ((Get-FileHash -LiteralPath (Join-Path $destination 'original-hook.sh')).Hash -ieq $OriginalHookSha256) 'copied-hook-changed'
Assert-Probe ((Get-FileHash -LiteralPath (Join-Path $destination 'migration-check.jar')).Hash -ieq $CheckerSha256) 'copied-checker-changed'
foreach ($name in @('install-remote-system.sh','replace-remote-system.sh','remote-system-transaction.sh','start.sh','install-recovery.sh','original-hook.sh')) {
    $text = $utf8.GetString([IO.File]::ReadAllBytes((Join-Path $destination $name)))
    Assert-Probe (-not $text.Contains("`r") -and -not $text.StartsWith([string][char]0xfeff,[StringComparison]::Ordinal)) 'shell-must-be-lf-without-bom'
    $null = Invoke-ProbeTool $BashPath @('-n',(Join-Path $destination $name)) 'shell-syntax'
}
$files = @('remote.apk','remote-variants.json','migration-check.jar','original-hook.sh','install-remote-system.sh',
    'replace-remote-system.sh','remote-system-transaction.sh','start.sh','install-recovery.sh','marker','Invoke-SystemMigration.ps1') | ForEach-Object {
    $file = Get-Item -LiteralPath (Join-Path $destination $_)
    [ordered]@{ name=$_; bytes=$file.Length; sha256=(Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant() }
}
$index = [ordered]@{
    schema=1; operation='system_migration_stage'; offlinePrepared=$true; migrationVerified=$false
    remoteStage=('/data/local/d31-remote/deploy-host-' + [guid]::NewGuid().ToString('N'))
    fullSha256=$full.sha256; fullVersionCode=$full.versionCode; checkerSha256=$CheckerSha256
    originalHookSha256=$OriginalHookSha256; files=@($files)
}
Write-ProbeJson (Join-Path $destination 'stage.json') $index
[pscustomobject]@{ StageDirectory=$destination; StageManifestSha256=(Get-FileHash -LiteralPath (Join-Path $destination 'stage.json')).Hash.ToLowerInvariant(); OfflinePrepared=$true; MigrationVerified=$false } | ConvertTo-Json
