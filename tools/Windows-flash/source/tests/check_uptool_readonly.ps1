param([int]$InterfaceIndex, [string]$TargetMac)
$ErrorActionPreference = 'Stop'
$assembly = [Reflection.Assembly]::LoadFrom((Join-Path $PSScriptRoot '..\dist-v1.6.0\D31-Flash-Tool-v1.6.0.exe'))
$client = $assembly.GetType('D31FlashTool.UptoolClient')
$flags = [Reflection.BindingFlags]'Static,NonPublic'
$items = $client.GetMethod('Adapters', $flags).Invoke($null, @())
$selected = @($items | Where-Object {
    $nic = $_.GetType().GetField('Nic', [Reflection.BindingFlags]'Instance,NonPublic').GetValue($_)
    $nic.GetIPProperties().GetIPv4Properties().Index -eq $InterfaceIndex
})
if ($selected.Count -ne 1) { throw '未找到唯一匹配的有线网卡' }
$client.GetMethod('Run', $flags).Invoke($null, @($selected[0].PSObject.BaseObject, $TargetMac, $false))
