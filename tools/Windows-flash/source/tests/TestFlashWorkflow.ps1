param([Parameter(Mandatory=$true)][string]$OutputDirectory,[Parameter(Mandatory=$true)][string]$ConstantsPath)
$ErrorActionPreference='Stop'
if (Test-Path -LiteralPath $OutputDirectory) { throw '测试目录已存在' }
$null=New-Item -ItemType Directory -Path $OutputDirectory
$OutputDirectory=(Resolve-Path -LiteralPath $OutputDirectory).Path
$root=Split-Path -Parent $PSScriptRoot
$sources=@(Get-ChildItem (Join-Path $root 'src') -Filter '*.cs' | Where-Object Name -NE 'BuildConstants.g.cs' | ForEach-Object FullName)
$sources+=@((Resolve-Path -LiteralPath $ConstantsPath).Path,(Join-Path $PSScriptRoot 'FlashWorkflowTests.cs'))
$sources | Set-Content (Join-Path $OutputDirectory '编译输入.txt') -Encoding utf8
$exe=Join-Path $OutputDirectory 'FlashWorkflowTests.exe'
& "$env:SystemRoot/Microsoft.NET/Framework64/v4.0.30319/csc.exe" /nologo /target:exe /main:FlashWorkflowTests /reference:System.Web.Extensions.dll /reference:System.Drawing.dll /reference:System.Windows.Forms.dll "/out:$exe" "/resource:$root/assets/elfradio-logo.png,D31FlashTool.Logo.png" "/resource:$root/assets/elfradio.ico,D31FlashTool.AppIcon.ico" @sources
if ($LASTEXITCODE) { throw '流程测试编译失败' }
& $exe $OutputDirectory (Join-Path $PSScriptRoot 'FlashWorkflowFixture.ps1')
if ($LASTEXITCODE) { throw '一键刷机流程测试失败，详见结果.txt' }
