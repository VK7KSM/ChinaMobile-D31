param([Parameter(Mandatory=$true)][string]$Backend)
$ErrorActionPreference='Stop'
$tokens=$null; $errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($Backend,[ref]$tokens,[ref]$errors)
if($errors.Count){throw '后端语法错误'}
$definition=$ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Wait-ForAndroid'},$true)
Invoke-Expression $definition.Extent.Text
function Get-Date { $script:now }
function Start-Sleep { param([int]$Seconds) $script:now=$script:now.AddSeconds($Seconds) }
function Write-Step { param($Text) }
function Add-SessionLog { param($Text) }
function Invoke-D31Probe {
    param($Path,$Body)
    if($script:mode -eq 'unavailable'){throw '模拟探针不可达'}
    if($Path -eq '/health'){return @{service='d31-root-rescue';uid=0;busy=($script:mode -eq 'busy')}}
    if($Path -eq '/exec'){
        $script:commands+=@($Body.command)
        return @{state='running'}
    }
    if($Path.StartsWith('/jobs/')){return @{state='completed';output='ADB_RESTORED'}}
    throw '未覆盖的探针请求'
}
function Invoke-AdbOptional {
    $text=$args -join ' '
    if($text -match 'get-state'){return @{ExitCode=0;Output='device'}}
    if($text -match 'sys.boot_completed'){return @{ExitCode=0;Output='1'}}
    if($text -match '^connect '){return @{ExitCode=0;Output='connected'}}
    throw "未覆盖的ADB命令：$text"
}
$Serial='192.0.2.31:5555'
foreach($case in @('normal','busy','unavailable')) {
    $script:mode=$case; $script:commands=@(); $script:now=[datetime]'2026-09-09T00:00:00'
    Wait-ForAndroid -TimeoutSeconds 40
    if($case -eq 'normal') {
        if($commands.Count -ne 1 -or $commands[0] -notmatch 'sys.boot_completed' -or $commands[0] -notmatch 'service.adb.tcp.port 5555') {throw '恢复命令不符合合同'}
        if($commands[0] -match 'reboot|dd |mount|rm |iptables'){throw '恢复越出D31 adbd范围'}
    } elseif($commands.Count){throw '探针忙碌或不可达时错误提交命令'}
    Write-Output "通过：启动后探针$case分支"
}
