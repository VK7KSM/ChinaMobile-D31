param([Parameter(Mandatory=$true)][string]$OutputDirectory,[switch]$FocusedOnly)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputDirectory){throw '离线测试目录已存在，禁止覆盖'}
$null=New-Item -ItemType Directory -Path $OutputDirectory
$OutputDirectory=(Resolve-Path $OutputDirectory).Path
$powershell="$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe"
$fixture=Join-Path $OutputDirectory '合同夹具'
$null=New-Item -ItemType Directory -Path $fixture
$files=@(
    @{path='/system/priv-app/D31ElfRemote/D31ElfRemote.apk';sha256=('a'*64);mode='0644';uid=0;gid=0},
    @{path='/system/bin/d31-elfremote-start';sha256=('b'*64);mode='0755';uid=0;gid=0},
    @{path='/system/etc/d31-elfremote.system';sha256=('c'*64);mode='0644';uid=0;gid=0},
    @{path='/system/bin/install-recovery.sh';sha256=('d'*64);mode='0750';uid=0;gid=0},
    @{path='/system/priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so';sha256=('e'*64);mode='0644';uid=0;gid=0}
)
$utf8=New-Object Text.UTF8Encoding($true)
[IO.File]::WriteAllText((Join-Path $fixture 'installed-files.json'),(ConvertTo-Json -InputObject $files),$utf8)
$approved=[pscustomobject]@{version='1.4.5';fileName='fixture.zip';bytes=1;sha256=('f'*64);bootSha256=('1'*64);recoverySha256=('2'*64);
    installedFilesSha256='';elfRemote=@{package='net.elfradio.d31bootstrap';systemApk='/system/priv-app/D31ElfRemote/D31ElfRemote.apk';versionCode=194;versionName='1.34.18-candidate';sha256=('a'*64)}}
$versions=if($FocusedOnly){@('1.4.6')}else{@('1.4.5','1.4.6')}
foreach($version in $versions) {
    $approved.version=$version
    if($version -eq '1.4.6'){$approved | Add-Member replaceBootRecovery $true}
    [IO.File]::WriteAllText((Join-Path $fixture 'approved-package.json'),($approved|ConvertTo-Json -Depth 5),$utf8)
    if($FocusedOnly){
        & $powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'TestBackendFixture.ps1') -SystemFixtureRoot $fixture -OutputDirectory (Join-Path $OutputDirectory $version) -PrepareOnly
    } else {
        & $powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'TestBackendFixture.ps1') -SystemFixtureRoot $fixture -OutputDirectory (Join-Path $OutputDirectory $version)
    }
    if($LASTEXITCODE){throw "旧规则/完整新合同回归失败：$version"}
}
$runtime=Join-Path $OutputDirectory '1.4.6/cases/中文 模拟设备'
if($FocusedOnly){
    $runtime=Join-Path $OutputDirectory '1.4.6/runtime'
    $null=New-Item -ItemType Directory -Path (Join-Path $runtime 'tools')
    & "$env:SystemRoot/Microsoft.NET/Framework64/v4.0.30319/csc.exe" /nologo /reference:System.Web.Extensions.dll ("/out:"+(Join-Path $runtime 'tools/adb.exe')) (Join-Path $PSScriptRoot 'BackendFakeAdb.cs')
    if($LASTEXITCODE){throw '离线ADB编译失败'}
}
$backend=Join-Path $runtime 'flash_d31_recovery.ps1'
$package=Join-Path $OutputDirectory '1.4.6/fixture.zip'
$approvedPath=Join-Path $runtime 'approved-package.json'
$goodApproval=[IO.File]::ReadAllText($approvedPath)
# 仅缩短真实单次ADB函数的超时默认值；其余生产控制流不替换。
$text=[IO.File]::ReadAllText($backend)
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
if($errors.Count){throw '生产副本语法错误'}
if($FocusedOnly){
    $probe=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-D31Probe'},$true)
    $text=$text.Remove($probe.Extent.StartOffset,$probe.Extent.EndOffset-$probe.Extent.StartOffset).Insert($probe.Extent.StartOffset,"function Invoke-D31Probe { throw 'OFFLINE_HTTP_DISABLED' }")
    $text=$text.Insert($ast.ParamBlock.Extent.EndOffset,"`r`nfunction Start-Sleep { param([int]`$Seconds) }`r`n")
}
$text=$text.Insert($ast.ParamBlock.Extent.EndOffset,"`r`n`$PSDefaultParameterValues['Invoke-AdbOnce:TimeoutSeconds']=3`r`n")
[IO.File]::WriteAllText($backend,$text,$utf8)
$results=New-Object 'System.Collections.Generic.List[object]'
function Run-MigrationCase([string]$Name,[bool]$Pass,[bool]$Write,[bool]$Reboot,[string[]]$Extra=@(),[string]$ErrorPattern='') {
    $env:D31_TEST_CASE=$Name
    $env:D31_TEST_TRANSCRIPT=Join-Path $OutputDirectory ($Name+'.commands.txt')
    try {
        $ErrorActionPreference='Continue'
        $output=& $powershell -NoProfile -ExecutionPolicy Bypass -File $backend -Serial '192.0.2.31:5654' -PackagePath $package -SkipBackup @Extra 2>&1
        $code=$LASTEXITCODE
    } finally { $ErrorActionPreference='Stop' }
    $output | Out-File (Join-Path $OutputDirectory ($Name+'.log')) -Encoding UTF8
    $commands=if(Test-Path $env:D31_TEST_TRANSCRIPT){Get-Content $env:D31_TEST_TRANSCRIPT -Raw}else{''}
    $writes=[regex]::Matches($commands,"busybox dd if='[^']+' of='/dev/block/[^']+/recovery'")
    if(($code -eq 0) -ne $Pass -or ($writes.Count -eq 1) -ne $Write -or $writes.Count -gt 1 -or
        ($commands.Contains('reboot recovery')) -ne $Reboot){throw "迁移结果/写入次数/重启门不符：$Name，退出码$code"}
    if($ErrorPattern -and ($output -join "`n") -notmatch $ErrorPattern){throw "失败未落在预期门：$Name"}
    if($commands -match '(?m)^(?!-P 5042 ).+' -or $commands -match '(?m)^-P 5042 -s (?!192\.0\.2\.31:5654 )' -or
        $commands -match "of='/dev/block/[^']+/boot'"){throw 'ADB端口、完整Serial或boot写入边界不符'}
    if($Extra -contains '-DevicePreflightOnly' -and $commands -match 'd31-migration-|RemoteWindowsMaintenance|\b(push|pull|dd|reboot)\b|mkdir|/cache/recovery/command') {throw '只读检查发生副作用'}
    if($Write) {
        $positions=@($commands.IndexOf('busybox sha256sum /data/local/tmp/D31-factory-v1.4.6.zip'),
            $commands.IndexOf('RemoteWindowsMaintenance reserve '),$commands.IndexOf(' pull '),
            $commands.LastIndexOf(' pull '),$commands.IndexOf(' push '),$writes[0].Index)
        for($i=0;$i -lt $positions.Count;$i++){
            if($positions[$i] -lt 0 -or ($i -gt 0 -and $positions[$i] -lt $positions[$i-1])){throw "迁移顺序不符：$Name"}
        }
        if(($output -join "`n") -notmatch '(?m)^D31_PARTITION_WRITE_BEGIN_V1 recovery\s*$'){throw '写入缺少UI保护标记'}
        $log=Get-ChildItem (Join-Path $runtime 'logs') -Directory | Sort-Object CreationTimeUtc | Select-Object -Last 1
        $state=Get-Content (Join-Path $log.FullName 'migration-state.json') -Raw | ConvertFrom-Json
        foreach($partitionName in @('boot','recovery')){
            $original=Join-Path $log.FullName ("original-$partitionName.img")
            if((Get-FileHash $original).Hash -ne $state.backups.$partitionName.sha256){throw '失败后原像未保留'}
        }
        $session=Get-Content (Join-Path $log.FullName '刷机记录.txt') -Raw
        if($session.IndexOf('D31_PARTITION_WRITE_BEGIN_V1 recovery') -gt $session.IndexOf(" of='/dev/block/")){throw 'UI保护标记晚于分区写入'}
        if(-not $Pass -and -not $state.failure){throw '失败状态未持久化'}
        if($Reboot -and ($commands.IndexOf('D31_OLD_RECOVERY_') -lt $writes[0].Index -or
            $commands.IndexOf('rm -f /cache/recovery/command') -lt $writes[0].Index)){throw '旧Recovery状态清理顺序错误'}
    }
    if([regex]::Matches($commands,'\breboot recovery').Count -gt 1){throw '重启请求发生了自动重发'}
    $results.Add([pscustomobject]@{name=$Name;passed=$true;exitCode=$code;writes=$writes.Count;reboot=$Reboot})
    Write-Host "通过：$Name"
}
try {
    Run-MigrationCase 'migration-preflight' $true $false $false @('-DevicePreflightOnly')
    Run-MigrationCase 'migration-invalid-hash' $false $false $false @('-DevicePreflightOnly') '无法解析设备端SHA-256'
    Run-MigrationCase 'migration-maintenance-denied' $false $false $false
    Run-MigrationCase 'migration-backup-missing' $false $false $false @() '原像备份缺失'
    Run-MigrationCase 'migration-backup-corrupt' $false $false $false @() '原像备份缺失'
    Run-MigrationCase 'migration-backup-disappeared' $false $false $false @() '最小备份缺失'
    Run-MigrationCase 'migration-upload-corrupt' $false $false $false @() '上传Recovery'
    Run-MigrationCase 'migration-readback-wrong' $false $true $false @() '回读哈希不符'
    Run-MigrationCase 'migration-write-failed' $false $true $false
    Run-MigrationCase 'migration-write-marker-missing' $false $true $false @() '退出未确认'
    Run-MigrationCase 'migration-write-marker-duplicate' $false $true $false @() '退出未确认'
    Run-MigrationCase 'migration-timeout' $false $true $false @() '超时'
    Run-MigrationCase 'migration-success' $true $true $true
    Run-MigrationCase 'migration-reboot-disconnect' $true $true $true @() '重启请求已尝试一次但ADB结果不确定'
    Run-MigrationCase 'full96-reboot-failed' $false $true $true @() '首次启动后的boot/Recovery不符合批准目标镜像'
    Run-MigrationCase 'migration-post-payload' $false $true $true @() '刷后载荷与固件不一致'
    Run-MigrationCase 'migration-post-init' $false $true $true @() '首次初始化的权限'
    Run-MigrationCase 'migration-post-log' $false $true $true @() 'Recovery日志中缺少安装器完成标记'
    foreach($case in @('old-version','string-flag','missing-recovery-hash','target-hash-mismatch')){
        $a=$goodApproval|ConvertFrom-Json
        switch($case){
            'old-version'{$a.version='1.4.5'}
            'string-flag'{$a.replaceBootRecovery='true'}
            'missing-recovery-hash'{$a.recoverySha256=''}
            'target-hash-mismatch'{$a.recoverySha256='0'*64}
        }
        [IO.File]::WriteAllText($approvedPath,($a|ConvertTo-Json -Depth 5),$utf8)
        Run-MigrationCase ('migration-'+$case) $false $false $false
    }
    $originalPackage=$package
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    foreach($case in @('image-magic','image-size','image-duplicate','same-recovery')){
        $package=Join-Path $OutputDirectory ($case+'.zip')
        $zip=[IO.Compression.ZipFile]::Open($package,[IO.Compression.ZipArchiveMode]::Create)
        $a=$goodApproval|ConvertFrom-Json
        try {
            $raw=New-Object byte[] $(if($case -eq 'image-size'){2048}else{16777216})
            [Text.Encoding]::ASCII.GetBytes($(if($case -eq 'image-magic'){'BROKEN!!'}else{'ANDROID!'})).CopyTo($raw,0)
            $raw[8]=2
            $sha=[Security.Cryptography.SHA256]::Create()
            try {$a.recoverySha256=([BitConverter]::ToString($sha.ComputeHash($raw))).Replace('-','')}finally{$sha.Dispose()}
            $count=if($case -eq 'image-duplicate'){2}else{1}
            for($i=0;$i -lt $count;$i++){
                $entry=$zip.CreateEntry('payload/recovery.img',[IO.Compression.CompressionLevel]::Optimal)
                $stream=$entry.Open()
                try{$stream.Write($raw,0,$raw.Length)}finally{$stream.Dispose()}
            }
        } finally {$zip.Dispose()}
        $a.bytes=(Get-Item $package).Length;$a.sha256=(Get-FileHash $package).Hash
        [IO.File]::WriteAllText($approvedPath,($a|ConvertTo-Json -Depth 5),$utf8)
        $pass=$case -eq 'same-recovery'
        Run-MigrationCase ('migration-'+$case) $pass $false $pass
    }
    $package=$originalPackage
} finally {
    [IO.File]::WriteAllText($approvedPath,$goodApproval,$utf8)
    Remove-Item Env:D31_TEST_CASE,Env:D31_TEST_TRANSCRIPT -ErrorAction SilentlyContinue
    [pscustomobject]@{cases=@($results.ToArray());count=$results.Count;realAdb=$false;networkOperations=0;
        note='执行真实生产PowerShell控制流；唯一ADB为本地编译的fakeADB，HTTP已隔离；超时用例仅缩短等待。'} |
        ConvertTo-Json -Depth 6 | Out-File (Join-Path $OutputDirectory 'migration-results.json') -Encoding UTF8
}
$global:LASTEXITCODE=0
