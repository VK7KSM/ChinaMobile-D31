[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$InventoryPath,
    [string]$EvidencePath,
    [Parameter(Mandatory=$true)][string]$BasicApk,
    [Parameter(Mandatory=$true)][string]$FullApk,
    [Parameter(Mandatory=$true)][string]$MetadataPath,
    [Parameter(Mandatory=$true)][string]$AaptPath,
    [Parameter(Mandatory=$true)][string]$JavaPath,
    [Parameter(Mandatory=$true)][string]$ApkSignerJar,
    [ValidateRange(1,1440)][int]$MaxAgeMinutes = 30
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'BasicProbe.Common.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'BasicProbe.Migration.psm1') -Force
$contract = Read-ProbeContract $MetadataPath
$null = Test-ProbeApk $BasicApk $contract basic $AaptPath $JavaPath $ApkSignerJar
$null = Test-ProbeApk $FullApk $contract full $AaptPath $JavaPath $ApkSignerJar
$hash = (Get-FileHash -LiteralPath $InventoryPath -Algorithm SHA256).Hash.ToLowerInvariant()
$inventory = Get-Content -LiteralPath $InventoryPath -Raw -Encoding UTF8 | ConvertFrom-Json
$evidence = [pscustomobject]@{}
$evidenceDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($InventoryPath))
if ($EvidencePath) {
    $evidence = Get-Content -LiteralPath $EvidencePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $evidenceDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($EvidencePath))
}
Assert-Probe ((Get-FileHash -LiteralPath $InventoryPath -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $hash) 'inventory-changed-during-read'
Get-BasicProbeMigrationPlan -Inventory $inventory -Evidence $evidence -Contract $contract -InventorySha256 $hash `
    -EvidenceDirectory $evidenceDirectory -MaxAgeMinutes $MaxAgeMinutes | ConvertTo-Json -Depth 10
