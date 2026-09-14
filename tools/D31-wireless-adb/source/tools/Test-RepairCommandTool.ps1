#requires -Version 7.2
param([string]$ScriptPath=(Join-Path $PSScriptRoot 'Test-RepairCommandDevice.ps1'))
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$errors=$null;$tokens=$null
$ast=[Management.Automation.Language.Parser]::ParseFile([IO.Path]::GetFullPath($ScriptPath),[ref]$tokens,[ref]$errors)
if($errors.Count -ne 0){throw ($errors | Out-String)}
# 仅载入纯校验函数；不求值脚本主体、ADB函数或设备命令。
foreach($name in @('Quote-Sh','Assert-Original','Assert-Receipt','Assert-Rejection')){
    $function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
    if($null -eq $function){throw "缺少校验函数：$name"}
    . ([scriptblock]::Create($function.Extent.Text))
}
$checks=0
function Rejects([scriptblock]$Action){
    $caught=$false
    try {& $Action} catch {$caught=$true}
    if(!$caught){throw '错误回执被误接受'}
    $script:checks++
}
$quote=[char]39
if((Quote-Sh "a'b") -cne ($quote+'a'+$quote+'\'+$quote+$quote+'b'+$quote)){throw 'shell单引号转义错误'}
$checks++
$task='reject-'+('a'*32)
$baseline=[pscustomobject]@{target=[pscustomobject]@{path='/data/local/d31-system-support/start.sh';sha256=('1'*64);bytes=20;device=1;inode=2;uid=0;gid=0;mode=33216;modified=1;changed=1;selinux='u:object_r:system_data_file:s0'}}
$plan=[pscustomobject]@{submit=[pscustomobject]@{plan_sha256=('3'*64);plan=[pscustomobject]@{changes=@([pscustomobject]@{original_sha256=('2'*64)})}}}
function Receipt([string]$Kind='REGULAR',[string]$Hash=('1'*64),[string]$Reason='PRECHECK_FAILED'){
    return [pscustomobject]@{task_id=$task;plan_sha256=('3'*64);runtime_effect='NOT_CHECKED';state=[pscustomobject]@{phase='REJECTED';reason=$Reason;attempted=-1;observations=@([pscustomobject]@{path='system-support/start.sh';kind=$Kind;sha256=$Hash})}}
}
Assert-Rejection (Receipt);$checks++
Rejects {Assert-Rejection (Receipt 'READ_FAILED')}
Rejects {Assert-Rejection (Receipt 'REGULAR' ('2'*64))}
Rejects {Assert-Rejection (Receipt 'REGULAR' ('4'*64))}
Rejects {Assert-Rejection (Receipt 'REGULAR' ('1'*64) 'STAGE_FAILED')}
Rejects {$r=Receipt;$r.state.observations=@();Assert-Rejection $r}
Rejects {$r=Receipt;$r.state.attempted=0;Assert-Rejection $r}
Rejects {$r=Receipt;$r.state.phase='BACKUP';Assert-Rejection $r}
Rejects {$r=Receipt;$r.plan_sha256='0'*64;Assert-Rejection $r}
Assert-Original $baseline;$checks++
Rejects {$changed=$baseline | ConvertTo-Json -Depth 10 | ConvertFrom-Json;$changed.target.inode=3;Assert-Original $changed}
Rejects {$changed=$baseline | ConvertTo-Json -Depth 10 | ConvertFrom-Json;$changed.target.sha256='2'*64;Assert-Original $changed}
Write-Output "宿主工具${checks}项离线校验通过；未加载ADB执行函数，未接触设备。"
