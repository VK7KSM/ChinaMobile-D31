param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -ne 5){throw '必须使用Windows PowerShell 5.1执行原生传参回归'}
if(Test-Path $OutputDirectory){throw '测试目录已存在，禁止覆盖'}
$null=New-Item -ItemType Directory -Path $OutputDirectory
$OutputDirectory=(Resolve-Path $OutputDirectory).Path
$Adb=Join-Path $OutputDirectory 'OfflineShellBridge.exe'
& "$env:SystemRoot/Microsoft.NET/Framework64/v4.0.30319/csc.exe" /nologo /reference:System.Web.Extensions.dll "/out:$Adb" (Join-Path $PSScriptRoot 'PartitionLayoutNativeBridge.cs')
if($LASTEXITCODE){throw '原生参数接收器编译失败'}
$AdbPort=5042;$Serial='offline-layout';$SessionLog=$null
$env:D31_LAYOUT_DISTRO='docker-desktop'
$env:D31_LAYOUT_FIXTURE=Join-Path $PSScriptRoot 'BackendShellFixture.sh'
$results=@();$passed=$false
function Add-Log { param([string]$Text) }
try {
    foreach($source in @((Join-Path $PSScriptRoot '../../d31/factory_package/flash_d31_recovery.ps1'),(Join-Path $PSScriptRoot '../scripts/create_d31_rescue.ps1'))){
        $tokens=$null;$errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseFile((Resolve-Path $source),[ref]$tokens,[ref]$errors)
        if($errors.Count){throw '生产脚本语法错误'}
        $label=[IO.Path]::GetFileNameWithoutExtension($source)
        foreach($name in @('Invoke-Adb','Invoke-AdbOnce','Get-DeviceValue','Get-CheckedDeviceValue','Add-SessionLog')){
            $node=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
            if($node){Invoke-Expression $node.Extent.Text}
        }
        if($label -eq 'flash_d31_recovery'){
            $tcp=$ast.Find({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$tcpDefault'},$true)
            $support=$ast.Find({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$supportReady'},$true)
            $tcpCommand=$tcp.Right.Find({param($n) $n -is [Management.Automation.Language.StringConstantExpressionAst] -and $n.Value.Contains('TcpAcclerate')},$true).Value
            $supportCommand=$support.Right.Find({param($n) $n -is [Management.Automation.Language.StringConstantExpressionAst] -and $n.Value.Contains('d31-system-actions')},$true).Value
        }
        foreach($case in @('tcp-valid','tcp-enabled','support-ready')){
            $env:D31_LAYOUT_CASE=$case
            $env:D31_LAYOUT_TRANSCRIPT=Join-Path $OutputDirectory ($label+'-'+$case)
            $errorText=$null;$value=$null
            try{
                if($case -eq 'support-ready'){$value=Get-DeviceValue $supportCommand}
                else{$value=Get-CheckedDeviceValue $tcpCommand}
            }catch{$errorText=$_.Exception.Message}
            if($case -eq 'tcp-enabled'){
                if(-not $errorText){throw '实际XML启用TCP仍被误判关闭'}
            }elseif($errorText){throw $errorText}
            elseif($case -eq 'tcp-valid' -and $value -cne "1.4.6`nTCP_DEFAULT_OK"){throw '带引号的实际XML匹配失败'}
            elseif($case -eq 'support-ready' -and $value -cne "net.elfradio.d31system`n/data/local/d31-system-support/guard"){throw '进程grep未逐行返回匹配或执行了guard'}
            $received=[IO.File]::ReadAllText($env:D31_LAYOUT_TRANSCRIPT+'.received.sh')
            $expected=if($case -eq 'support-ready'){$supportCommand}else{$tcpCommand}
            if(-not $received.Contains($expected)){throw '原生参数接收器未逐字收到生产shell命令'}
            $results+=@{source=$label;name=$case;passed=$true;received=$received;output=$value;error=$errorText}
            Write-Host "通过：$label/$case"
        }
    }
    $passed=$true
}finally{
    @{passed=$passed;cases=$results;count=$results.Count;realAdb=$false;realShell=$true;note='真实PS5.1原生参数接收器和隔离BusyBox shell；套用生产命令原文执行。'} |
        ConvertTo-Json -Depth 5 | Out-File (Join-Path $OutputDirectory 'shell-quoting-results.json') -Encoding UTF8
    Remove-Item Env:D31_LAYOUT_DISTRO,Env:D31_LAYOUT_FIXTURE,Env:D31_LAYOUT_CASE,Env:D31_LAYOUT_TRANSCRIPT -ErrorAction SilentlyContinue
}
$global:LASTEXITCODE=0
