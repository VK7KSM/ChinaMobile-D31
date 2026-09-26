param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
if (Test-Path -LiteralPath $OutputDirectory) { throw '测试目录已存在，禁止覆盖' }
$null=New-Item -ItemType Directory -Path $OutputDirectory
Import-Module (Join-Path $PSScriptRoot '../tools/FirmwareContract.psm1') -Force
# 使用独立合成合同，不读取真实固件、不运行后端。
$manifest=Join-Path $OutputDirectory 'synthetic-installed-files.json'
[IO.File]::WriteAllText($manifest,'[]')
$manifestHash=(Get-FileHash -LiteralPath $manifest).Hash
$cases=@(
    @{name='批准146';version='1.4.6';flag=$true;pass=$true},
    @{name='146缺标记';version='1.4.6';omit=$true},
    @{name='146关闭标记';version='1.4.6';flag=$false},
    @{name='146字符串标记';version='1.4.6';flag='true'},
    @{name='146整数标记';version='1.4.6';flag=1},
    @{name='146空标记';version='1.4.6';flag=$null},
    @{name='146缺Recovery摘要';version='1.4.6';flag=$true;badRecovery=$true},
    @{name='146缺boot摘要';version='1.4.6';flag=$true;badBoot=$true},
    @{name='146错清单';version='1.4.6';flag=$true;badManifest=$true},
    @{name='146错下载地址';version='1.4.6';flag=$true;badUrl=$true},
    @{name='新版本不能继承覆盖授权';version='1.4.7';flag=$true},
    @{name='旧145不能用于1611';version='1.4.5';omit=$true;release146=$true}
)
foreach ($version in @('1.4.3','1.4.4','1.4.5')) {
    $cases+=@{name="旧${version}无标记";version=$version;omit=$true;pass=$true}
    $cases+=@{name="旧${version}关闭标记";version=$version;flag=$false;pass=$true}
    $cases+=@{name="旧${version}不能覆盖";version=$version;flag=$true}
}
$results=@()
foreach ($case in $cases) {
    $version=$case.version
    $name="D31_SVP3390_Factory_Flash_v${version}_testkey.zip"
    $approval=[ordered]@{version=$version;fileName=$name;bytes=256;sha256=('a'*64);bootSha256=('b'*64);
        recoverySha256=('d'*64);installedFilesSha256=$manifestHash;
        githubUrl="https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v$version/$name";
        cloudflareUrl="https://cdn.elfradio.net/d31/$name";
        elfRemote=@{package='net.elfradio.d31bootstrap';systemApk='/system/priv-app/D31ElfRemote/D31ElfRemote.apk';versionCode=194;versionName='1.34.18-candidate';sha256=('c'*64)}}
    if (-not $case.omit) { $approval.replaceBootRecovery=$case.flag }
    if ($case.badRecovery) { $approval.Remove('recoverySha256') }
    if ($case.badBoot) { $approval.bootSha256='' }
    if ($case.badManifest) { $approval.installedFilesSha256='0'*64 }
    if ($case.badUrl) { $approval.githubUrl=$approval.githubUrl.Replace('/v1.4.6/','/v1.4.5/') }
    $file=Join-Path $OutputDirectory ("synthetic-"+$results.Count+'.json')
    [IO.File]::WriteAllText($file,($approval | ConvertTo-Json -Depth 5))
    $failure=$null; $contract=$null
    try {
        if ($version -eq '1.4.6' -or $case.release146) {
            $contract=Read-ApprovedFirmwareContract $file $manifest -Release -ExpectedReleaseVersion '1.4.6'
        } else { $contract=Read-ApprovedFirmwareContract $file $manifest }
    } catch { $failure=$_.Exception.Message }
    if (($null -eq $failure) -ne [bool]$case.pass) { throw "合同门结果不符：$($case.name)；$failure" }
    if ($case.pass -and $contract.ReplaceBootRecovery -ne ($version -eq '1.4.6')) { throw '覆盖行为不符' }
    $results += [pscustomobject]@{name=$case.name;passed=$true;rejected=($null -ne $failure);error=$failure}
}
$build=Join-Path $PSScriptRoot '../build-v1.6.11.ps1'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($build,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw '1611构建脚本语法错误' }
$command=Get-Command $build
foreach ($set in $command.ParameterSets) {
    foreach ($name in @('FirmwarePackage','ApprovedPackagePath','InstalledFilesPath','BasicApk','FullApk','MetadataPath')) {
        if (-not ($set.Parameters | Where-Object Name -eq $name).IsMandatory) { throw "构建原件参数非强制：$name" }
    }
}
$text=$ast.Extent.Text
foreach ($required in @("-ExpectedReleaseVersion '1.4.6'", "`$approved.version -cne '1.4.6' -or -not `$firmwareContract.ReplaceBootRecovery",
    "'1.6.11'", '`$basic.versionCode -ne 193', '`$basic.size -ne 135645', '`$full.versionCode -ne 194',
    "'platform-compatibility') -ConstantsPath `$generated", "'flash-workflow') -ConstantsPath `$generated")) {
    $literal=$required.Replace('`$', '$')
    if (-not $text.Contains($literal)) { throw "构建绑定缺失：$literal" }
}
$results += [pscustomobject]@{name='1611绑定146及既有APK并传入本次生成常量';passed=$true;rejected=$false}
# 只求值构建脚本的常量模板，使用明确的合成合同；不运行构建或打包流程。
$assignments=@($ast.FindAll({param($node)
    $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$generatedSource'
},$true))
if ($assignments.Count -ne 1) { throw '生成常量模板不唯一' }
$templates=@($assignments[0].Right.FindAll({param($node)
    $node -is [Management.Automation.Language.ExpandableStringExpressionAst]
},$false))
if ($templates.Count -ne 1) { throw '生成常量不是单个模板' }
$firmwareContract=Read-ApprovedFirmwareContract (Join-Path $OutputDirectory 'synthetic-0.json') $manifest -Release -ExpectedReleaseVersion '1.4.6'
$approved=$firmwareContract.Approval
$version='1.6.11-rc1'
$rescueRestoreHash='e'*64; $rescueTestHash='f'*64; $aria2Hash='0'*64; $descriptors=''
$generatedSource=$ExecutionContext.InvokeCommand.ExpandString($templates[0].Value)
if (-not $generatedSource.Contains('ToolVersion = "1.6.11-rc1"') -or
    -not $generatedSource.Contains('OfficialPackageName = "D31_SVP3390_Factory_Flash_v1.4.6_testkey.zip"') -or
    -not $generatedSource.Contains($firmwareContract.GitHubUrl) -or
    -not $generatedSource.Contains($firmwareContract.CloudflareUrl)) { throw '常量模板未绑定1611和146' }
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'SyntheticBuildConstants.g.cs'),$generatedSource,[Text.UTF8Encoding]::new($false))
$results += [pscustomobject]@{name='实际生成模板合成146常量';passed=$true;rejected=$false}
[pscustomobject]@{passed=$true;checks=$results.Count;cases=$results;syntheticContractOnly=$true;formalExeBuilt=$false;deviceOperations=0;networkOperations=0} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'contract-results.json') -Encoding UTF8
Write-Output "通过：$($results.Count)项146覆盖合同及1611构建准入检查"
