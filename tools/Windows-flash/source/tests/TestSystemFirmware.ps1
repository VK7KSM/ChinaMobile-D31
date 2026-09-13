param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
if (Test-Path -LiteralPath $OutputDirectory) { throw '测试输出已存在，拒绝覆盖' }
$PackageRoot=Join-Path $OutputDirectory 'runtime'
New-Item -ItemType Directory -Path (Join-Path $PackageRoot 'tools') -Force | Out-Null
$source=Join-Path $PSScriptRoot '../../d31/factory_package/flash_d31_recovery.ps1'
$tokens=$null; $errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseInput([IO.File]::ReadAllText($source),[ref]$tokens,[ref]$errors)
if($errors.Count){throw '后端语法错误'}
foreach($name in @('Read-InstalledPayloadManifest','Assert-InstalledPayload','Assert-FactoryInitialization','Assert-SystemElfRemote',
    'Assert-ElfRemoteProcess','Add-SessionLog','Write-Step','Invoke-Adb','Get-DeviceValue','Get-CheckedDeviceValue','Get-RemoteSha256')) {
    $definition=$ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
    if(-not $definition){throw "缺少生产函数：$name"}
    Invoke-Expression $definition.Extent.Text
}
$csc="$env:SystemRoot/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
$Adb=Join-Path $PackageRoot 'tools/adb.exe'
& $csc /nologo /reference:System.Web.Extensions.dll "/out:$Adb" (Join-Path $PSScriptRoot 'BackendFakeAdb.cs')
if($LASTEXITCODE){throw '模拟ADB编译失败'}
$AdbPort=5042; $Serial='192.0.2.31:5555'; $SessionLog=$null
$manifestPath=Join-Path $PackageRoot 'installed-files.json'
$approvalPath=Join-Path $PackageRoot 'approved-package.json'
function New-Fixture {
    $script:files=@(
        @{path='/system/priv-app/D31ElfRemote/D31ElfRemote.apk';sha256=('a'*64);mode='0644';uid=0;gid=0},
        @{path='/system/bin/d31-elfremote-start';sha256=('b'*64);mode='0755';uid=0;gid=0},
        @{path='/system/etc/d31-elfremote.system';sha256=('c'*64);mode='0644';uid=0;gid=0},
        @{path='/system/bin/install-recovery.sh';sha256=('d'*64);mode='0750';uid=0;gid=0},
        @{path='/data/local/d31-startup-handover/handover.jar';sha256=('e'*64)},
        @{path='/system/priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so';sha256=('1'*64);mode='0644';uid=0;gid=0}
    )
    $script:ApprovedPackage=[pscustomobject]@{version='1.4.4';fileName='D31_SVP3390_Factory_Flash_v1.4.4_testkey.zip';bytes=256;sha256=('f'*64);bootSha256=('0'*64);
        installedFilesSha256='';githubUrl='https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.4.4/D31_SVP3390_Factory_Flash_v1.4.4_testkey.zip';
        cloudflareUrl='https://cdn.elfradio.net/d31/D31_SVP3390_Factory_Flash_v1.4.4_testkey.zip';
        elfRemote=[pscustomobject]@{package='net.elfradio.d31bootstrap';systemApk='/system/priv-app/D31ElfRemote/D31ElfRemote.apk';versionCode=170;versionName='1.34.6-candidate';sha256=('a'*64)}}
}
function Write-Fixture {
    [IO.File]::WriteAllText($manifestPath, (ConvertTo-Json -InputObject @($script:files) -Depth 5))
    $script:ApprovedPackage.installedFilesSha256=(Get-FileHash $manifestPath).Hash
    [IO.File]::WriteAllText($approvalPath, ($script:ApprovedPackage | ConvertTo-Json -Depth 5))
}
$results=@()
$expectedErrors=@{
    'system-file-mode'='系统文件权限';'system-file-context'='SELinux标签';'system-pm-data'='唯一批准系统路径';'system-pm-version'='PM版本';
    'system-active-data'='活动版本';'system-missing-health'='首启运行态';'system-health-uid'='心跳版本';'system-stale-health'='心跳版本';
    'system-health-version'='心跳版本';'system-not-ready'='核心本机健康';'system-supervisor-pid'='当前唯一实例';'system-process-uid'='实际进程不是root';
    'system-bad-map'='实际ART映射';'system-runtime-mode'='运行目录权限';'system-boot-changed'='发生切换';'system-old-init'='当前批准固件版本';
    'system-forged-init'='首次初始化的权限';'system-init-exit'='设备退出未确认'
}
try {
    New-Fixture; Write-Fixture
    foreach($case in @('system-success','system-file-mode','system-file-context','system-pm-data','system-pm-version','system-active-data',
        'system-missing-health','system-health-uid','system-stale-health','system-health-version','system-not-ready','system-supervisor-pid',
        'system-process-uid','system-bad-map','system-runtime-mode','system-boot-changed','system-old-init','system-forged-init','system-init-exit')) {
        $env:D31_TEST_CASE=$case
        $env:D31_TEST_TRANSCRIPT=Join-Path $OutputDirectory ($case+'.commands.txt')
        $failure=$null
        try { Assert-InstalledPayload; Assert-FactoryInitialization; Assert-SystemElfRemote } catch { $failure=$_.Exception.Message }
        if (($null -eq $failure) -ne ($case -eq 'system-success')) { throw "用例结果不符：$case : $failure" }
        if ($failure -and $failure -notmatch $expectedErrors[$case]) { throw "失败未落在目标门：$case : $failure" }
        $commands=Get-Content -Raw $env:D31_TEST_TRANSCRIPT
        if($commands -match 'reboot|\spush\s|\bpm (grant|disable|enable)|--init|\bchmod\b'){throw '刷后验证越过只读范围'}
        $results += [pscustomobject]@{name=$case;passed=$true;error=$failure}
        Write-Output "通过：$case"
    }
    foreach($case in @('missing-system-apk','missing-start','missing-marker','missing-recovery-hook','missing-native','duplicate-path','traversal-path','missing-mode','non-root',
        'transient-init-marker','apk-hash-mismatch','manifest-hash-mismatch','missing-elfremote-contract')) {
        New-Fixture
        switch($case) {
            'missing-system-apk' {$script:files=@($script:files | Where-Object path -ne '/system/priv-app/D31ElfRemote/D31ElfRemote.apk')}
            'missing-start' {$script:files=@($script:files | Where-Object path -ne '/system/bin/d31-elfremote-start')}
            'missing-marker' {$script:files=@($script:files | Where-Object path -ne '/system/etc/d31-elfremote.system')}
            'missing-recovery-hook' {$script:files=@($script:files | Where-Object path -ne '/system/bin/install-recovery.sh')}
            'missing-native' {$script:files=@($script:files | Where-Object path -ne '/system/priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so')}
            'duplicate-path' {$script:files+=@($script:files[0])}
            'traversal-path' {$script:files[4].path='/data/../system/file'}
            'missing-mode' {$script:files[0].Remove('mode')}
            'non-root' {$script:files[0].uid=2000}
            'transient-init-marker' {$script:files[4].path='/data/local/factory-init-required'}
            'apk-hash-mismatch' {$script:files[0].sha256='1'*64}
            'missing-elfremote-contract' {$script:ApprovedPackage.elfRemote=$null}
        }
        Write-Fixture
        if($case -eq 'manifest-hash-mismatch') {$script:ApprovedPackage.installedFilesSha256='0'*64}
        $failure=$null
        try {$null=Read-InstalledPayloadManifest} catch {$failure=$_.Exception.Message}
        if(-not $failure){throw "无效清单未拒绝：$case"}
        $results += [pscustomobject]@{name=$case;passed=$true;error=$failure}
        Write-Output "通过：$case"
    }
    Import-Module (Join-Path $PSScriptRoot '../tools/FirmwareContract.psm1') -Force
    foreach($case in @('approved-release','old-firmware-release','unapproved-url','unbound-manifest')) {
        New-Fixture; Write-Fixture
        if($case -eq 'old-firmware-release') {$script:ApprovedPackage.version='1.4.3';$script:ApprovedPackage.fileName='D31_SVP3390_Factory_Flash_v1.4.3_testkey.zip'}
        if($case -eq 'unapproved-url') {$script:ApprovedPackage.githubUrl=''}
        if($case -eq 'unbound-manifest') {$script:ApprovedPackage.installedFilesSha256='0'*64}
        [IO.File]::WriteAllText($approvalPath,($script:ApprovedPackage | ConvertTo-Json -Depth 5))
        $failure=$null
        try {$null=Read-ApprovedFirmwareContract $approvalPath $manifestPath -Release} catch {$failure=$_.Exception.Message}
        if(($null -eq $failure) -ne ($case -eq 'approved-release')){throw "批准门结果不符：$case"}
        $results += [pscustomobject]@{name=$case;passed=$true;error=$failure}
        Write-Output "通过：$case"
    }
} finally {
    Remove-Item Env:D31_TEST_CASE -ErrorAction SilentlyContinue
    Remove-Item Env:D31_TEST_TRANSCRIPT -ErrorAction SilentlyContinue
}
[pscustomobject]@{passed=$true;count=$results.Count;cases=$results;sourceSha256=(Get-FileHash $source).Hash;realAdb=$false;syntheticFirmware=$true} |
    ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutputDirectory 'system-firmware-results.json') -Encoding UTF8
$global:LASTEXITCODE=0
