param(
    [Parameter(Mandatory=$true)][ValidatePattern('^rc[1-9][0-9]{0,3}$')][string]$CandidateId,
    [Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$BasicApk,
    [Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$FullApk,
    [Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$MetadataPath,
    [string]$FirmwarePackage,
    [string]$AaptPath,
    [string]$ApkSignerJar,
    [string]$JavaPath,
    [string]$BashPath
)
$ErrorActionPreference = "Stop"

$global:LASTEXITCODE = 0
& (Join-Path $PSScriptRoot "build-v1.6.7.ps1") @PSBoundParameters
exit $LASTEXITCODE
