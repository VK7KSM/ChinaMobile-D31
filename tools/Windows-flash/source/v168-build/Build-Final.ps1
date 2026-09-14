param(
    [Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$FirmwarePackage,
    [Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$ApprovedPackagePath,
    [Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$InstalledFilesPath
)
$ErrorActionPreference='Stop'
$tool=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$variants=[IO.Path]::GetFullPath((Join-Path $tool '../d31/staging/b25-variants193-194-20260914'))
& (Join-Path $tool 'build-v1.6.8.ps1') -Release `
    -BasicApk (Join-Path $variants 'build/outputs/apk/basic/release/app-basic-release.apk') `
    -FullApk (Join-Path $variants 'build/outputs/apk/full/release/app-full-release.apk') `
    -MetadataPath (Join-Path $variants 'remote-variants.json') `
    -FirmwarePackage $FirmwarePackage -ApprovedPackagePath $ApprovedPackagePath -InstalledFilesPath $InstalledFilesPath `
    -JavaPath 'C:/Users/x/.jdks/jdk-17.0.20.1+1/bin/java.exe'
if($LASTEXITCODE -ne 0){throw '最终构建或内置验收失败，不可发布'}
