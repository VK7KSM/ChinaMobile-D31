param(
    [Parameter(Mandatory=$true,ParameterSetName='Candidate')][ValidatePattern('^rc[1-9][0-9]{0,3}$')][string]$CandidateId,
    [Parameter(Mandatory=$true,ParameterSetName='Release')][switch]$Release,
    [Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$BasicApk,
    [Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$FullApk,
    [Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$MetadataPath,
    [string]$FirmwarePackage,
    [string]$ApprovedPackagePath,
    [string]$InstalledFilesPath,
    [string]$AaptPath,
    [string]$ApkSignerJar,
    [string]$JavaPath,
    [string]$BashPath
)
$ErrorActionPreference = "Stop"

$global:LASTEXITCODE = 0
& (Join-Path $PSScriptRoot "build-v1.6.8.ps1") @PSBoundParameters
exit $LASTEXITCODE
