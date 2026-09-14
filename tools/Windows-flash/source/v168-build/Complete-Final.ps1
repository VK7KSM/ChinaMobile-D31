$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$version='1.6.8'
$objectDirectory=Join-Path $projectRoot 'obj-v1.6.8'
$outputDirectory=Join-Path $projectRoot 'dist-v1.6.8'
$candidate=Join-Path $objectDirectory 'D31-Flash-Tool-v1.6.8.exe'
$output=Join-Path $outputDirectory 'D31-Flash-Tool-v1.6.8.exe'
$reportDirectory=Join-Path $objectDirectory 'test-reports'
$runtime=Join-Path $objectDirectory 'runtime'
$backend=Get-Content -Raw (Join-Path $reportDirectory 'backend-version-contract-fixed/backend-results.json') | ConvertFrom-Json
$system=Get-Content -Raw (Join-Path $reportDirectory 'system-firmware-fixture-fixed/system-firmware-results.json') | ConvertFrom-Json
$completion=Get-Content -Raw (Join-Path $PSScriptRoot 'remaining-tests-complete.json') | ConvertFrom-Json
if(-not $backend.passed -or -not $system.passed -or -not $completion.passed){throw '最后测试未全部通过'}
if((Get-FileHash $candidate).Hash -cne '24B49DEC6E8FBD6A0F72A40A944225ADBA7B03D7BF28FA6452EAA113999A02C3'){throw '已编译受验EXE发生变化'}
if(Test-Path -LiteralPath $outputDirectory){throw '正式输出已存在，拒绝覆盖'}
$variants=[IO.Path]::GetFullPath((Join-Path $projectRoot '../d31/staging/b25-variants193-194-20260914'))
$MetadataPath=Join-Path $variants 'remote-variants.json'
$metadata=Get-Content -Raw $MetadataPath | ConvertFrom-Json
$basic=@($metadata.artifacts | Where-Object artifact -CEQ basic)[0]
$full=@($metadata.artifacts | Where-Object artifact -CEQ full)[0]
$approvedPath=Join-Path $runtime 'approved-package.json'
$installedManifest=Join-Path $runtime 'installed-files.json'
Import-Module (Join-Path $projectRoot 'tools/FirmwareContract.psm1') -Force
Import-Module (Join-Path $projectRoot 'tools/BasicProbe.Common.psm1') -Force
$firmwareContract=Read-ApprovedFirmwareContract $approvedPath $installedManifest -Release -ExpectedReleaseVersion '1.4.5'
$approved=$firmwareContract.Approval
$package=Join-Path $projectRoot '../d31/dist/factory-flash-v1.4.5/D31_SVP3390_Factory_Flash_v1.4.5_testkey.zip'
$packageItem=Get-Item -LiteralPath $package
$packageHash=(Get-FileHash -LiteralPath $package).Hash
if($packageItem.Length -ne 1725713383 -or $packageHash -cne 'E74EFFC90A36EA9C7149532A7FFA7D556A448462324410BE7AA689DAF583CFC1'){throw '固件原件变化'}
if((Get-FileHash $installedManifest).Hash -cne '566DF4C608147834D93B1531362DBF1EC6C254BE667C19687653851FC490E19D'){throw '安装清单原件变化'}
$nativePayload=Assert-FirmwareNativePayload (Join-Path $variants $full.path) $installedManifest
$runtimeSources=@(Get-ChildItem -LiteralPath $runtime -Recurse -File)
$rescueRestoreHash=(Get-FileHash (Join-Path $runtime 'rescue/D31_RESCUE_UPDATE.zip')).Hash
$rescueTestHash=(Get-FileHash (Join-Path $runtime 'rescue/D31_RESCUE_TEST.zip')).Hash
$aria2Hash=(Get-FileHash (Join-Path $runtime 'tools/aria2c.exe')).Hash
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $projectRoot 'build-v1.6.8.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw '原构建器解析失败'}
foreach($variable in @('selfTestReport','packageReport','downloadParserReport','compatibilityReport')){
    $assignment=$ast.Find({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -ceq ('$'+$variable)},$true)
    if(-not $assignment){throw '原报告路径赋值缺失'}
    Invoke-Expression $assignment.Extent.Text
}
# 仅恢复原构建器的单EXE复制、目录门和报告生成；此前所有产品测试已有原件。
$text=[IO.File]::ReadAllText((Join-Path $projectRoot 'build-v1.6.8.ps1'))
$start=$text.IndexOf('New-Item -ItemType Directory -Path $outputDirectory | Out-Null',[StringComparison]::Ordinal)
if($start -lt 0){throw '未找到原发布收尾入口'}
Invoke-Expression $text.Substring($start)
