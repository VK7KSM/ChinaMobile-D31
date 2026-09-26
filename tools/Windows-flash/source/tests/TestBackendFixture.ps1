param([Parameter(Mandatory=$true)][string]$SystemFixtureRoot,[Parameter(Mandatory=$true)][string]$OutputDirectory,[switch]$PrepareOnly)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputDirectory){throw '测试输出已存在，拒绝覆盖'}
$runtime=Join-Path $OutputDirectory 'runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
foreach($name in @('approved-package.json','installed-files.json')){Copy-Item -LiteralPath (Join-Path $SystemFixtureRoot $name) -Destination $runtime}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../../d31/factory_package/flash_d31_recovery.ps1') -Destination $runtime
$approved=Get-Content (Join-Path $runtime 'approved-package.json') -Raw | ConvertFrom-Json
$package=Join-Path $OutputDirectory $approved.fileName
if ($approved.replaceBootRecovery -eq $true) {
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::Open($package,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in @('boot','recovery')) {
            $raw=New-Object byte[] 16777216
            [Text.Encoding]::ASCII.GetBytes('ANDROID!').CopyTo($raw,0)
            $raw[8]=if($name -eq 'boot'){3}else{4}
            $sha=[Security.Cryptography.SHA256]::Create()
            try { $imageHash=([BitConverter]::ToString($sha.ComputeHash($raw))).Replace('-','') } finally { $sha.Dispose() }
            $approved.($name+'Sha256')=$imageHash
            $entry=$zip.CreateEntry("payload/$name.img",[IO.Compression.CompressionLevel]::NoCompression)
            $stream=$entry.Open()
            try { $stream.Write($raw,0,$raw.Length) } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
} else {
    $stream=[IO.File]::Open($package,[IO.FileMode]::CreateNew)
    try {$stream.SetLength(16777217);$stream.Position=16777216;$stream.WriteByte(1)} finally {$stream.Dispose()}
    $approved.bootSha256='2'*64
}
$approved.bytes=(Get-Item $package).Length
$approved.sha256=(Get-FileHash $package).Hash
$approved.installedFilesSha256=(Get-FileHash (Join-Path $runtime 'installed-files.json')).Hash
[IO.File]::WriteAllText((Join-Path $runtime 'approved-package.json'),($approved | ConvertTo-Json -Depth 5))
if (-not $PrepareOnly) {
    & (Join-Path $PSScriptRoot 'TestBackend.ps1') -RuntimeRoot $runtime -Package $package -OutputDirectory (Join-Path $OutputDirectory 'cases')
    if($LASTEXITCODE){throw '模拟完整后端测试失败'}
}
[pscustomobject]@{passed=(-not $PrepareOnly);fixturePrepared=$true;testsExecuted=(-not $PrepareOnly);syntheticFirmware=$true;realAdb=$false;packageBytes=$approved.bytes;packageSha256=$approved.sha256} |
    ConvertTo-Json | Set-Content (Join-Path $OutputDirectory 'fixture-results.json') -Encoding UTF8
$global:LASTEXITCODE=0
