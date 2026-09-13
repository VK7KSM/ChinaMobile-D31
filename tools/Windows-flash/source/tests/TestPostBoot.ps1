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
    $script:adbCommands+=@($text)
    if($text -match 'get-state'){
        if($script:mode -eq 'no-handshake'){return @{ExitCode=1;Output='offline'}}
        return @{ExitCode=0;Output='device'}
    }
    if($text -match 'sys.boot_completed'){return @{ExitCode=0;Output=$(if($script:mode -eq 'not-booted'){'0'}else{'1'})}}
    if($text -match '^connect '){return @{ExitCode=0;Output='connected'}}
    throw "未覆盖的ADB命令：$text"
}
foreach($DeviceAdbPort in @(5555,5654,1,65535)) {
    $Serial='192.0.2.31:' + $DeviceAdbPort
    foreach($case in @('normal','busy','unavailable','no-handshake','not-booted')) {
        $script:mode=$case; $script:commands=@(); $script:adbCommands=@(); $script:now=[datetime]'2026-09-09T00:00:00'
        $failure=$null
        try { Wait-ForAndroid -TimeoutSeconds 40 } catch { $failure=$_ }
        if($case -in @('no-handshake','not-booted')) {
            if(-not $failure -or $failure.Exception.Message -notmatch '^Recovery刷写触发后'){throw '未完成ADB握手和启动核验仍宣称恢复成功'}
        } elseif($failure) { throw $failure }
        if($case -in @('normal','no-handshake','not-booted')) {
            if(-not $commands.Count) {throw '未执行恢复探针模拟'}
            foreach($command in $commands) {
                if($command -notmatch 'sys.boot_completed' -or $command -notmatch ('service.adb.tcp.port ' + $DeviceAdbPort + ';')) {throw '恢复命令未使用当前已验证端口'}
                if($command -notmatch 'stop adbd\s*(?:;|&&)\s*sleep 1\s*(?:;|&&)\s*start adbd'){throw 'Android6停止等待时序丢失'}
                if($command -match 'reboot|dd |mount|rm |iptables|persist\.|sys.usb|__PORT__'){throw '恢复越出D31 adbd范围'}
            }
        } elseif($commands.Count){throw '探针忙碌或不可达时错误提交命令'}
        foreach($command in $adbCommands) { if(-not $command.Contains($Serial)){throw 'ADB握手未定向当前完整序列号'} }
        Write-Output "通过：端口${DeviceAdbPort}，启动后探针${case}分支"
    }
}
