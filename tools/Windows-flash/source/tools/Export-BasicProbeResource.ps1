[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$StageDirectory,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$AaptPath,
    [Parameter(Mandatory=$true)][string]$JavaPath,
    [Parameter(Mandatory=$true)][string]$ApkSignerJar
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'BasicProbe.Common.psm1') -Force
$manifest = Get-Content -LiteralPath (Join-Path $StageDirectory 'basic-probe-resource.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$contract = Read-ProbeContract (Join-Path $StageDirectory 'remote-variants.json')
$basic = @($contract.artifacts | Where-Object artifact -CEQ basic)[0]
$relative = Get-ProbeRelativePath $basic
Assert-ProbeResourceManifest $manifest $basic
$source = Join-Path $StageDirectory $relative
$null = Test-ProbeApk $source $contract basic $AaptPath $JavaPath $ApkSignerJar
Assert-Probe (Test-Path -LiteralPath $OutputDirectory -PathType Container) 'export-directory-missing'
$destination = Join-Path $OutputDirectory ([IO.Path]::GetFileName($relative))
Copy-ProbeVerified $source $destination $basic.sha256
[pscustomobject]@{ Path = $destination; Sha256 = $basic.sha256; Bytes = $basic.size }
