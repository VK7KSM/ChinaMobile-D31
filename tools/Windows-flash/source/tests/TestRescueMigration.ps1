param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
if(Test-Path $OutputDirectory){throw '测试目录已存在，禁止覆盖'}
$null=New-Item -ItemType Directory -Path $OutputDirectory
$OutputDirectory=(Resolve-Path $OutputDirectory).Path
$runtime=Join-Path $OutputDirectory 'runtime'
$null=New-Item -ItemType Directory -Path (Join-Path $runtime 'tools'),(Join-Path $runtime 'rescue')
$csc="$env:SystemRoot/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
$Adb=Join-Path $runtime 'tools/adb.exe'
& $csc /nologo ("/out:$Adb") (Join-Path $PSScriptRoot 'RescueMigrationFakeAdb.cs')
if($LASTEXITCODE){throw '备份fakeADB编译失败'}
foreach($name in @('UPDATE','TEST')){[IO.File]::WriteAllText((Join-Path $runtime "rescue/D31_RESCUE_$name.zip"),"离线签名入口占位$name")}
$ExpectedRescueRestoreHash=(Get-FileHash (Join-Path $runtime 'rescue/D31_RESCUE_UPDATE.zip')).Hash
$ExpectedRescueTestHash=(Get-FileHash (Join-Path $runtime 'rescue/D31_RESCUE_TEST.zip')).Hash
$source=Join-Path $PSScriptRoot '../scripts/create_d31_rescue.ps1'
$text=[IO.File]::ReadAllText((Resolve-Path $source))
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
if($errors.Count){throw '生产备份脚本语法错误'}
$main=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.IfStatementAst]})[0]
# 仅缩小测试分区及换用离线签名入口摘要，生产函数和主流程保持原样。
$override="`r`n`$ExpectedSizes=@{system=4096L;boot=2048L;recovery=2048L;userdata=8192L;logo=512L}`r`n"+
    "`$ExpectedRestoreLauncherHash='$ExpectedRescueRestoreHash'`r`n`$ExpectedTestLauncherHash='$ExpectedRescueTestHash'`r`n"
$text=$text.Insert($main.Extent.StartOffset,$override)
$backup=Join-Path $runtime 'create_d31_rescue.ps1'
[IO.File]::WriteAllText($backup,$text,(New-Object Text.UTF8Encoding($true)))
$backend=Join-Path $PSScriptRoot '../../d31/factory_package/flash_d31_recovery.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile((Resolve-Path $backend),[ref]$tokens,[ref]$errors)
foreach($name in @('Get-GzipRawInfo','Assert-RescueDirectory','Get-RemoteSha256','Get-DeviceValue','Invoke-Adb','Add-SessionLog')){
    $definition=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
    Invoke-Expression $definition.Extent.Text
}
$AdbPort=5042;$Serial='192.0.2.31:5654';$SessionLog=$null
$ExpectedSizes=@{system=4096L;boot=2048L;recovery=2048L;userdata=8192L;logo=512L}
$ByName='/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name'
$ExpectedRecoveryHash='173CB00459E4CDFC2B4BF04D7BED4A130947795F8ACB3B557BBEF2C218B2E7D5'
$results=@()
try {
    foreach($case in @('third-party','missing-boot','corrupt-boot','missing-receipt')){
        $env:D31_RESCUE_CASE=$case
        $env:D31_RESCUE_TRANSCRIPT=Join-Path $OutputDirectory ($case+'.commands.txt')
        $destination=Join-Path $OutputDirectory $case
        try {
            $ErrorActionPreference='Continue'
            $output=& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backup -Serial $Serial -OutputBase $destination 2>&1
            $code=$LASTEXITCODE
        }finally{$ErrorActionPreference='Stop'}
        $output | Out-File (Join-Path $OutputDirectory ($case+'.log')) -Encoding UTF8
        if(($code -eq 0) -ne ($case -eq 'third-party')){throw "生产备份主流程结果不符：$case"}
        $commands=Get-Content $env:D31_RESCUE_TRANSCRIPT -Raw
        if($commands -match '\b(reboot|push)\b|of=/dev/block/' -or $commands -match '(?m)^(?!-P 5042 ).+'){throw '备份测试越过设备写入边界'}
        if($case -eq 'third-party'){
            $manifest=Get-ChildItem $destination -Recurse -Filter D31_RESCUE_MANIFEST.txt | Select-Object -First 1
            $values=ConvertFrom-StringData (Get-Content $manifest.FullName -Raw)
            if($values.target_fingerprint -cne 'offline/third-party/build:6.0/test' -or $values.recovery_entry_status -cne 'raw-only-unverified'){throw '实际指纹或Recovery能力合同不符'}
            $null=Assert-RescueDirectory $manifest.DirectoryName $values.target_fingerprint
            $rejected=$false
            try{$null=Assert-RescueDirectory $manifest.DirectoryName 'forged-fingerprint'}catch{$rejected=$true}
            if(-not $rejected){throw '错误指纹通过备份校验'}
            $values.recovery_entry_status='stock-baseline'
            [IO.File]::WriteAllLines($manifest.FullName,@($values.Keys | ForEach-Object {$_+'='+$values[$_]}))
            $rejected=$false
            try{$null=Assert-RescueDirectory $manifest.DirectoryName 'offline/third-party/build:6.0/test'}catch{$rejected=$true}
            if(-not $rejected){throw '第三方Recovery伪造原厂能力通过'}
        } elseif($commands -match 'rm -rf /data/local/tmp/d31-rescue-'){throw '失败后删除了设备原像现场'}
        $results+=@{name=$case;passed=$true;exitCode=$code}
        Write-Host "通过：$case"
    }
}finally{
    Remove-Item Env:D31_RESCUE_CASE,Env:D31_RESCUE_TRANSCRIPT -ErrorAction SilentlyContinue
    @{cases=$results;realAdb=$false;networkOperations=0;note='真实备份主流程及后端Assert-RescueDirectory双向合同；所有ADB均为离线模拟。'} |
        ConvertTo-Json -Depth 5 | Out-File (Join-Path $OutputDirectory 'rescue-migration-results.json') -Encoding UTF8
}
$global:LASTEXITCODE=0
