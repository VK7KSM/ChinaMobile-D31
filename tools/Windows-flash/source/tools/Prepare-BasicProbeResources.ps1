[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BasicApk,
    [Parameter(Mandatory=$true)][string]$MetadataPath,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$AaptPath,
    [Parameter(Mandatory=$true)][string]$JavaPath,
    [Parameter(Mandatory=$true)][string]$ApkSignerJar
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'BasicProbe.Common.psm1') -Force
$contract = Read-ProbeContract $MetadataPath
$basic = Test-ProbeApk $BasicApk $contract basic $AaptPath $JavaPath $ApkSignerJar
$root = [IO.Path]::GetFullPath($OutputDirectory)
Assert-Probe (-not (Test-Path -LiteralPath $root)) 'stage-already-exists'
New-Item -ItemType Directory -Path $root -ErrorAction Stop | Out-Null
$relative = Get-ProbeRelativePath $basic
$destination = Join-Path $root $relative
New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -ErrorAction Stop | Out-Null
Copy-ProbeVerified $BasicApk $destination $basic.sha256
Copy-ProbeVerified $MetadataPath (Join-Path $root 'remote-variants.json') ((Get-FileHash -LiteralPath $MetadataPath -Algorithm SHA256).Hash)
# Re-read the staged index to reject a metadata change during preparation.
$stagedContract = Read-ProbeContract (Join-Path $root 'remote-variants.json')
$null = Test-ProbeApk $destination $stagedContract basic $AaptPath $JavaPath $ApkSignerJar
$resourceName = 'D31FlashTool.Runtime.BasicProbe' + $basic.versionCode
$descriptor = 'new RuntimeAssetDescriptor("{0}", "{1}", "{2}")' -f $relative.Replace('\','\\'), $resourceName, $basic.sha256
$manifest = [ordered]@{
    schema = 1; status = 'basic-resource-offline-verified'; artifact = $basic
    metadataFile = 'remote-variants.json'
    runtimeFiles = @([ordered]@{ RelativePath = $relative; ResourceName = $resourceName; Sha256 = $basic.sha256; Bytes = $basic.size })
    runtimeSources = @([ordered]@{ Relative = $relative; Source = $relative; Bom = $false })
    descriptor = $descriptor
    deviceAcceptance = $false
}
# The manifest is the completion marker; partial stages must never be consumed.
Write-ProbeJson (Join-Path $root 'basic-probe-resource.json') $manifest
[pscustomobject]@{ StageDirectory = $root; Manifest = (Join-Path $root 'basic-probe-resource.json'); Sha256 = $basic.sha256 }
