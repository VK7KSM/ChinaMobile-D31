param([Parameter(Mandatory=$true)][string]$SystemFixtureRoot,[Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputDirectory){throw '测试输出已存在，拒绝覆盖'}
$runtime=Join-Path $OutputDirectory 'runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
foreach($name in @('approved-package.json','installed-files.json')){Copy-Item -LiteralPath (Join-Path $SystemFixtureRoot $name) -Destination $runtime}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../../d31/factory_package/flash_d31_recovery.ps1') -Destination $runtime
$approved=Get-Content (Join-Path $runtime 'approved-package.json') -Raw | ConvertFrom-Json
$package=Join-Path $OutputDirectory $approved.fileName
$stream=[IO.File]::Open($package,[IO.FileMode]::CreateNew)
try {$stream.SetLength(16777217);$stream.Position=16777216;$stream.WriteByte(1)} finally {$stream.Dispose()}
$approved.bytes=(Get-Item $package).Length
$approved.sha256=(Get-FileHash $package).Hash
$approved.bootSha256='2'*64
$approved.installedFilesSha256=(Get-FileHash (Join-Path $runtime 'installed-files.json')).Hash
[IO.File]::WriteAllText((Join-Path $runtime 'approved-package.json'),($approved | ConvertTo-Json -Depth 5))
& (Join-Path $PSScriptRoot 'TestBackend.ps1') -RuntimeRoot $runtime -Package $package -OutputDirectory (Join-Path $OutputDirectory 'cases')
if($LASTEXITCODE){throw '模拟完整后端测试失败'}
[pscustomobject]@{passed=$true;syntheticFirmware=$true;realAdb=$false;packageBytes=$approved.bytes;packageSha256=$approved.sha256} |
    ConvertTo-Json | Set-Content (Join-Path $OutputDirectory 'fixture-results.json') -Encoding UTF8
$global:LASTEXITCODE=0
