param([string]$HostAddress)
$ErrorActionPreference = 'Stop'
$assembly = [Reflection.Assembly]::LoadFrom((Join-Path $PSScriptRoot '..\dist-v1.6.0\D31-Flash-Tool-v1.6.0.exe'))
$client = $assembly.GetType('D31FlashTool.RescueClient')
$flags = [Reflection.BindingFlags]'Static,NonPublic'
$client.GetMethod('Health', $flags).Invoke($null, @($HostAddress))
$client.GetMethod('Execute', $flags).Invoke($null, @($HostAddress, 'id; uptime; getprop sys.boot_completed; getprop init.svc.adbd'))
