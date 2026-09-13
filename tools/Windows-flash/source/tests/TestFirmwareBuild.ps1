param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputDirectory){throw '测试输出已存在，拒绝覆盖'}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$root=Split-Path $PSScriptRoot -Parent
$tokens=$null;$errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'build-v1.6.7.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw '构建器语法错误'}
$versionAssignment=$ast.Find({param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$version'},$true)
foreach($case in @(@($true,'','1.6.7'),@($false,'rc10','1.6.7-rc10'))) {
    $Release=$case[0];$CandidateId=$case[1]
    Invoke-Expression $versionAssignment.Extent.Text
    if($version -cne $case[2]){throw '正式或候选版本生成不符'}
}
foreach($entry in @('build.ps1','build-v1.6.7.ps1')) {
    $command=Get-Command (Join-Path $root $entry)
    if(@($command.ParameterSets | Where-Object Name -eq 'Release').Count -ne 1 -or
        @($command.ParameterSets | Where-Object Name -eq 'Candidate').Count -ne 1){throw '正式和候选参数集未分离'}
}
$assignment=$ast.Find({param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$generatedSource'},$true)
if(-not $assignment){throw '缺少真实生成常量源码'}
$version='1.6.7-offline-check'
$approved=[pscustomobject]@{fileName='D31_SVP3390_Factory_Flash_v1.4.4_testkey.zip';sha256=('a'*64);bytes=256}
$firmwareContract=[pscustomobject]@{GitHubUrl=('https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.4.4/'+$approved.fileName);CloudflareUrl=('https://cdn.elfradio.net/d31/'+$approved.fileName)}
$rescueRestoreHash='b'*64;$rescueTestHash='c'*64;$aria2Hash='d'*64;$descriptors=''
Invoke-Expression $assignment.Extent.Text
$constants=Join-Path $OutputDirectory 'BuildConstants.g.cs'
[IO.File]::WriteAllText($constants,$generatedSource)
$sources=@(Get-ChildItem (Join-Path $root 'src') -Filter '*.cs' | Where-Object Name -ne 'BuildConstants.g.cs' | ForEach-Object FullName)
$library=Join-Path $OutputDirectory 'WindowsOffline.dll'
$csc="$env:SystemRoot/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
& $csc /nologo /target:library /reference:System.dll /reference:System.Core.dll /reference:System.Web.Extensions.dll /reference:System.Drawing.dll /reference:System.Windows.Forms.dll "/out:$library" @sources $constants
if($LASTEXITCODE){throw 'Windows源码库编译失败'}
$assembly=[Reflection.Assembly]::LoadFile([IO.Path]::GetFullPath($library))
$manager=$assembly.GetType('D31FlashTool.FirmwareManager',$true)
$flags=[Reflection.BindingFlags]'Static,NonPublic'
foreach($pair in @(@('PackageName',$approved.fileName),@('GitHubDownloadUrl',$firmwareContract.GitHubUrl),@('CloudflareDownloadUrl',$firmwareContract.CloudflareUrl))) {
    if($manager.GetField($pair[0],$flags).GetRawConstantValue() -cne $pair[1]){throw '实际Windows下载绑定未消费批准常量'}
}
& $csc /nologo /target:library /reference:System.Windows.Forms.dll /reference:System.Drawing.dll ("/out:"+(Join-Path $OutputDirectory 'BasicProbeExeTests.dll')) (Join-Path $PSScriptRoot 'BasicProbeExeTests.cs')
if($LASTEXITCODE){throw '正式EXE验收源码编译失败'}
[pscustomobject]@{passed=$true;checks=9;allWindowsSourcesCompiled=$true;actualBuilderConstants=$true;formalExeBuilt=$false;syntheticFirmware=$true;deviceOperations=0;networkOperations=0;librarySha256=(Get-FileHash $library).Hash} |
    ConvertTo-Json | Set-Content (Join-Path $OutputDirectory 'build-contract-results.json') -Encoding UTF8
$global:LASTEXITCODE=0
Write-Output '通过：Windows全部源码、实际常量、下载绑定及正式/候选参数门'
