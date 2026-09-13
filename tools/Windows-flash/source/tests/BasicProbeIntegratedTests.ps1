[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Executable,
    [Parameter(Mandatory=$true)][string]$MetadataPath,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../tools/BasicProbe.Common.psm1') -Force
$contract = Read-ProbeContract $MetadataPath
$basic = @($contract.artifacts | Where-Object artifact -CEQ basic)[0]
Assert-Probe (-not (Test-Path -LiteralPath $OutputDirectory)) 'integrated-output-exists'
$root = (New-Item -ItemType Directory -Path $OutputDirectory -ErrorAction Stop).FullName
$exe = (Resolve-Path -LiteralPath $Executable).Path
$runner = Join-Path $root 'BasicProbeExeTests.exe'
$csc = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
& $csc /nologo /reference:System.Windows.Forms.dll /reference:System.Drawing.dll "/out:$runner" (Join-Path $PSScriptRoot 'BasicProbeExeTests.cs')
if ($LASTEXITCODE -ne 0) { throw 'BasicProbe:integrated-test-compile' }
& $runner $exe $root $basic.sha256 ([string]$basic.versionCode) ([IO.Path]::GetFileName((Get-ProbeRelativePath $basic)))
if ($LASTEXITCODE -ne 0) { throw 'BasicProbe:integrated-test-failed' }
$target = Join-Path $root ([IO.Path]::GetFileName((Get-ProbeRelativePath $basic)))
$report = Join-Path $root 'exe-export.txt'
$process = Start-Process -FilePath $exe -ArgumentList @('--export-basic-probe',('"'+$target+'"'),('"'+$report+'"')) -WindowStyle Hidden -Wait -PassThru
Assert-Probe ($process.ExitCode -eq 0 -and (Get-FileHash -LiteralPath $target).Hash -ieq $basic.sha256) 'exe-export-hash'
$collision = Join-Path $root 'collision.apk'
[IO.File]::WriteAllText($collision,'independent-existing-file')
$originalHash = (Get-FileHash -LiteralPath $collision).Hash
$report = Join-Path $root 'exe-export-rejected.txt'
$process = Start-Process -FilePath $exe -ArgumentList @('--export-basic-probe',('"'+$collision+'"'),('"'+$report+'"')) -WindowStyle Hidden -Wait -PassThru
Assert-Probe ($process.ExitCode -ne 0 -and (Get-FileHash -LiteralPath $collision).Hash -eq $originalHash) 'exe-export-collision'
Write-ProbeJson (Join-Path $root 'result.json') ([ordered]@{ status='passed'; executableSha256=(Get-FileHash -LiteralPath $exe).Hash; basicVersionCode=$basic.versionCode; basicSha256=$basic.sha256; cliChecks=2; deviceOperations=0 })
Write-Host 'BasicProbe EXE integration PASS; CLI export and collision checks PASS'
