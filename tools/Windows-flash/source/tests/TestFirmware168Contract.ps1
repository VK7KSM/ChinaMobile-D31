param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputDirectory){throw '测试目录已存在，禁止覆盖'}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
Import-Module (Join-Path $PSScriptRoot '../tools/FirmwareContract.psm1') -Force
# 仅验证合同门，不生成固件或工具制品。
$manifest=Join-Path $OutputDirectory 'synthetic-installed-files.json'
[IO.File]::WriteAllText($manifest,'[]')
$manifestHash=(Get-FileHash -LiteralPath $manifest).Hash
$results=@()
foreach($case in @('approved145','old144','wrong-github','wrong-cdn','wrong-manifest','wrong-boot-hash','legacy144')) {
    $version=if($case -in @('old144','legacy144')){'1.4.4'}else{'1.4.5'}
    $name="D31_SVP3390_Factory_Flash_v${version}_testkey.zip"
    $approval=[ordered]@{version=$version;fileName=$name;bytes=256;sha256=('a'*64);bootSha256=('b'*64);
        installedFilesSha256=$manifestHash;githubUrl="https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v$version/$name";
        cloudflareUrl="https://cdn.elfradio.net/d31/$name";
        elfRemote=@{package='net.elfradio.d31bootstrap';systemApk='/system/priv-app/D31ElfRemote/D31ElfRemote.apk';versionCode=194;versionName='1.34.18-candidate';sha256=('c'*64)}}
    switch($case){
        'wrong-github' {$approval.githubUrl=$approval.githubUrl.Replace('/v1.4.5/','/v1.4.4/')}
        'wrong-cdn' {$approval.cloudflareUrl=$approval.cloudflareUrl.Replace('v1.4.5','v1.4.4')}
        'wrong-manifest' {$approval.installedFilesSha256='0'*64}
        'wrong-boot-hash' {$approval.bootSha256=''}
    }
    $file=Join-Path $OutputDirectory ("synthetic-$case.json")
    [IO.File]::WriteAllText($file,($approval | ConvertTo-Json -Depth 5))
    $failure=$null
    try {
        if($case -eq 'legacy144'){$null=Read-ApprovedFirmwareContract $file $manifest -Release}
        else {$null=Read-ApprovedFirmwareContract $file $manifest -Release -ExpectedReleaseVersion '1.4.5'}
    } catch {$failure=$_.Exception.Message}
    if(($null -eq $failure) -ne ($case -in @('approved145','legacy144'))){throw "合同门结果不符：$case"}
    $results += [pscustomobject]@{name=$case;passed=$true;rejected=($null -ne $failure)}
}
$command=Get-Command (Join-Path $PSScriptRoot '../build-v1.6.8.ps1')
foreach($set in $command.ParameterSets){
    foreach($name in @('FirmwarePackage','ApprovedPackagePath','InstalledFilesPath')){
        if(-not ($set.Parameters | Where-Object Name -eq $name).IsMandatory){throw "最终原件参数非强制：$name"}
    }
}
$results += [pscustomobject]@{name='required-final-originals';passed=$true;rejected=$false}
[pscustomobject]@{passed=$true;checks=$results.Count;cases=$results;syntheticContractOnly=$true;formalExeBuilt=$false;deviceOperations=0;networkOperations=0} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'contract-results.json') -Encoding UTF8
Write-Output "通过：$($results.Count)项1.4.5合同及最终原件准入检查"
