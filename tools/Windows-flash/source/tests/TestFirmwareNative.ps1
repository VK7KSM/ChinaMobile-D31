param([Parameter(Mandatory=$true)][string]$FullApk,[Parameter(Mandatory=$true)][string]$InstalledFilesPath,[Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputDirectory){throw '测试输出已存在，拒绝覆盖'}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
Import-Module (Join-Path $PSScriptRoot '../tools/FirmwareContract.psm1') -Force
$results=@()
foreach($case in @('actual-native','missing-native','duplicate-native','wrong-native-hash')) {
    $files=@(Get-Content -LiteralPath $InstalledFilesPath -Raw -Encoding UTF8 | ConvertFrom-Json | ForEach-Object { $_ })
    $path='/system/priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so'
    $item=@($files | Where-Object path -CEQ $path)[0]
    if($case -eq 'missing-native'){$files=@($files | Where-Object path -CNE $path)}
    if($case -eq 'duplicate-native'){$files+=@($item)}
    if($case -eq 'wrong-native-hash'){$item.sha256='0'*64}
    $fixture=Join-Path $OutputDirectory ($case+'.json')
    [IO.File]::WriteAllText($fixture,(ConvertTo-Json -InputObject $files -Depth 5))
    $failure=$null;$native=$null
    try {$native=Assert-FirmwareNativePayload $FullApk $fixture} catch {$failure=$_.Exception.Message}
    if(($null -eq $failure) -ne ($case -eq 'actual-native')){throw "原生库测试结果不符：$case : $failure"}
    $results+=[pscustomobject]@{name=$case;passed=$true;error=$failure;native=$native}
    Write-Output "通过：$case"
}
[pscustomobject]@{passed=$true;count=$results.Count;cases=$results;fullApkSha256=(Get-FileHash $FullApk).Hash;installedManifestSha256=(Get-FileHash $InstalledFilesPath).Hash;deviceOperations=0} |
    ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutputDirectory 'native-results.json') -Encoding UTF8
$global:LASTEXITCODE=0
