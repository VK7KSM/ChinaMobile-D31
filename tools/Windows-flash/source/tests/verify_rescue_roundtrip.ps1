param([string]$HostAddress, [int]$InterfaceIndex, [string]$TargetMac, [string]$OutputPath)
$ErrorActionPreference = 'Stop'
Start-Transcript -Path $OutputPath -NoClobber | Out-Null
$assembly = [Reflection.Assembly]::LoadFrom((Join-Path $PSScriptRoot '..\dist-v1.6.0\D31-Flash-Tool-v1.6.0.exe'))
$flags = [Reflection.BindingFlags]'Static,NonPublic'
$client = $assembly.GetType('D31FlashTool.RescueClient')
$vendor = $assembly.GetType('D31FlashTool.UptoolClient')
$device = $assembly.GetType('D31FlashTool.DeviceDetector')
[string]$root = Join-Path $env:LOCALAPPDATA 'Elfradio\D31FlashTool\1.6.0'
function Command([string]$Command) { $client.GetMethod('Execute',$flags).Invoke($null,@($HostAddress,$Command)) }
function Connect { Start-Sleep -Milliseconds 3500; $device.GetMethod('PrepareDedicatedServer',$flags).Invoke($null,@($root)); [string]$serial = $device.GetMethod('Connect',$flags).Invoke($null,@($root,$HostAddress)); $info = $device.GetMethod('Inspect',$flags).Invoke($null,@($root,$serial,$HostAddress)); if (!$info.IsRoot -or !$info.TargetAddressIsEthernet) { throw 'root或有线目标检查失败' }; 'ADB握手、构建、有线地址及root检查通过' }
$restore = $client.GetField('StartAdb',$flags).GetRawConstantValue()
$items = $vendor.GetMethod('Adapters',$flags).Invoke($null,@())
$selected = @($items | Where-Object { $_.GetType().GetField('Nic',[Reflection.BindingFlags]'Instance,NonPublic').GetValue($_).GetIPProperties().GetIPv4Properties().Index -eq $InterfaceIndex })
if ($selected.Count -ne 1) { throw '有线网卡不唯一' }
try {
    Command 'id; getprop ro.build.fingerprint; getprop sys.boot_completed; getprop init.svc.adbd; cat /sys/class/net/eth0/address'
    Connect
    '阶段一：8765恢复闭环'
    Command 'setprop ctl.stop adbd'
    Start-Sleep -Seconds 1
    $state = Command 'getprop init.svc.adbd' | ConvertFrom-Json
    if ($state.output.Trim() -ne 'stopped') { throw '未确认adbd停止，不计为恢复通过' }
    Command $restore
    Connect
    '阶段二：uptool恢复闭环'
    $vendor.GetMethod('Run',$flags).Invoke($null,@($selected[0].PSObject.BaseObject,$TargetMac,$false))
    Command 'setprop ctl.stop adbd'
    Start-Sleep -Seconds 1
    $state = Command 'getprop init.svc.adbd' | ConvertFrom-Json
    if ($state.output.Trim() -ne 'stopped') { throw '未确认adbd停止，不计为恢复通过' }
    $vendor.GetMethod('Run',$flags).Invoke($null,@($selected[0].PSObject.BaseObject,$TargetMac,$true))
    Connect
    '两条恢复闭环均通过'
} finally {
    Command $restore
    Connect
    Command 'getprop sys.boot_completed; getprop init.svc.adbd; uptime'
    Stop-Transcript | Out-Null
}
