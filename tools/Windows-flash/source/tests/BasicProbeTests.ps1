[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BasicApk,
    [Parameter(Mandatory=$true)][string]$FullApk,
    [Parameter(Mandatory=$true)][string]$MetadataPath,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$AaptPath,
    [Parameter(Mandatory=$true)][string]$JavaPath,
    [Parameter(Mandatory=$true)][string]$ApkSignerJar
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$tools = Join-Path $PSScriptRoot '../tools'
Import-Module (Join-Path $tools 'BasicProbe.Common.psm1') -Force
Import-Module (Join-Path $tools 'BasicProbe.Migration.psm1') -Force
Assert-Probe (-not (Test-Path -LiteralPath $OutputDirectory)) 'test-output-already-exists'
$root = (New-Item -ItemType Directory -Path $OutputDirectory -ErrorAction Stop).FullName
$results = [Collections.Generic.List[object]]::new()
function Check([string]$Name, [scriptblock]$Body) {
    try { & $Body; $results.Add([pscustomobject]@{name=$Name;passed=$true}); Write-Host "PASS $Name" }
    catch { $results.Add([pscustomobject]@{name=$Name;passed=$false;reason=$_.Exception.Message}); Write-Host "FAIL $Name : $($_.Exception.Message)" }
}
function Expect-Reject([scriptblock]$Body, [string]$Reason) {
    $caught = $null
    try { & $Body | Out-Null } catch { $caught = $_.Exception.Message }
    Assert-Probe ($null -ne $caught -and $caught.Contains($Reason)) "expected-rejection-$Reason"
}
function Clone($Value) { return ($Value | ConvertTo-Json -Depth 25 | ConvertFrom-Json) }
function Save-Json([string]$Name, $Value) {
    $path = Join-Path $root $Name
    Write-ProbeJson $path $Value
    return $path
}
$toolArgs = @{ AaptPath=$AaptPath; JavaPath=$JavaPath; ApkSignerJar=$ApkSignerJar }
$contract = Read-ProbeContract $MetadataPath
$basic = @($contract.artifacts | Where-Object artifact -CEQ basic)[0]
$full = @($contract.artifacts | Where-Object artifact -CEQ full)[0]
Check 'real-basic-identity' { $null = Test-ProbeApk $BasicApk $contract basic @toolArgs }
Check 'real-full-identity' { $null = Test-ProbeApk $FullApk $contract full @toolArgs }
Check 'full-rejected-for-embedding' { Expect-Reject { Test-ProbeApk $FullApk $contract basic @toolArgs } 'apk-size-hash' }
$badging = "package: name='$($basic.package)' versionCode='$($basic.versionCode)' versionName='$($basic.versionName)'`nsdkVersion:'21'`ntargetSdkVersion:'27'"
$signature = "Signer #1 certificate SHA-256 digest: $($basic.certSha256)`nVerified using v1 scheme (JAR signing): true"
Check 'identity-parser-valid' { Assert-ProbeIdentity $basic $badging $signature }
Check 'wrong-package-rejected' { Expect-Reject { Assert-ProbeIdentity $basic ($badging.Replace($basic.package,'net.other.app')) $signature } 'apk-package' }
Check 'preview-package-rejected' { Expect-Reject { Assert-ProbeIdentity $basic ($badging.Replace($basic.package,($basic.package+'.preview'))) $signature } 'apk-package' }
Check 'wrong-version-rejected' { Expect-Reject { Assert-ProbeIdentity $basic ($badging.Replace("versionCode='$($basic.versionCode)'","versionCode='1'")) $signature } 'apk-version' }
Check 'wrong-version-name-rejected' { Expect-Reject { Assert-ProbeIdentity $basic ($badging.Replace($basic.versionName,'other-basic')) $signature } 'apk-version' }
Check 'debuggable-rejected' { Expect-Reject { Assert-ProbeIdentity $basic ($badging+"`napplication-debuggable") $signature } 'apk-sdk-debuggable' }
Check 'wrong-sdk-rejected' { Expect-Reject { Assert-ProbeIdentity $basic ($badging.Replace("sdkVersion:'21'","sdkVersion:'24'")) $signature } 'apk-sdk-debuggable' }
Check 'wrong-certificate-rejected' { Expect-Reject { Assert-ProbeIdentity $basic $badging ($signature.Replace($basic.certSha256,('a'*64))) } 'apk-certificate' }
Check 'two-signers-rejected' { Expect-Reject { Assert-ProbeIdentity $basic $badging ($signature+"`nSigner #2 certificate SHA-256 digest: $($basic.certSha256)") } 'apk-certificate' }
Check 'v2-only-rejected-on-android6' { Expect-Reject { Assert-ProbeIdentity $basic $badging ($signature.Replace(': true',': false')) } 'apk-android6-signature' }
Check 'metadata-boolean-string-rejected' {
    $c = Clone $contract; $c.artifacts[0].remote_full = 'false'
    $path = Save-Json 'boolean.json' $c
    Expect-Reject { Read-ProbeContract $path } 'metadata-flavor'
}
Check 'metadata-version-pair-rejected' {
    $c = Clone $contract; $c.fullVersionCode++
    $path = Save-Json 'pair.json' $c
    Expect-Reject { Read-ProbeContract $path } 'metadata-version-pair'
}
Check 'metadata-wrong-original-certificate-rejected' {
    $c=Clone $contract;$c.artifacts[0].certSha256='a'*64
    $path=Save-Json 'wrong-cert.json' $c
    Expect-Reject { Read-ProbeContract $path } 'metadata-certificate'
}
Check 'metadata-basic-extra-dependency-rejected' {
    $c=Clone $contract;$c.artifacts[0].dependencies += 'cloud-client'
    $path=Save-Json 'dependency.json' $c
    Expect-Reject { Read-ProbeContract $path } 'metadata-basic-dependencies'
}
Check 'full-hash-disguised-as-basic-still-rejected' {
    $c=Clone $contract;$c.artifacts[0].sha256=$full.sha256;$c.artifacts[0].bytes=$full.bytes;$c.artifacts[0].size=$full.size
    Expect-Reject { Test-ProbeApk $FullApk $c basic @toolArgs } 'apk-marker-kind'
}
Check 'metadata-path-injection-rejected' {
    $c = Clone $contract; $c.artifacts[0].versionName = '../outside'
    $path = Save-Json 'injection.json' $c
    Expect-Reject { Read-ProbeContract $path } 'metadata-version-name'
}
Check 'next-contract-not-hardcoded' {
    $c = Clone $contract
    $c.basicVersionCode += 2; $c.fullVersionCode += 2
    $c.artifacts[0].versionCode += 2; $c.artifacts[1].versionCode += 2
    $c.artifacts[0].versionName = '2.0.0-basic'; $c.artifacts[1].versionName = '2.0.0'
    $path = Save-Json 'next-contract.json' $c
    $next = Read-ProbeContract $path
    Assert-Probe ((Get-ProbeRelativePath $next.artifacts[0]).EndsWith("D31-basic-v2.0.0-basic-$($c.basicVersionCode).apk")) 'dynamic-filename'
}
Check 'new-tools-no-old-version-or-device-execution' {
    foreach ($file in Get-ChildItem -LiteralPath $tools -Filter '*BasicProbe*' -File) {
        $source = Get-Content -LiteralPath $file.FullName -Raw
        Assert-Probe ($source -notmatch '1\.11\.6|adb\.exe|pm install|stop adbd|kill-server|Invoke-WebRequest|Start-Sleep') 'offline-tool-boundary'
    }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Marker-Zip([string]$Name, [string[]]$Names, [string[]]$Contents) {
    $path = Join-Path $root $Name
    $zip = [IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Create)
    try {
        for ($i=0; $i -lt $Names.Count; $i++) {
            $stream = $zip.CreateEntry($Names[$i]).Open()
            try { $bytes = [Text.Encoding]::UTF8.GetBytes($Contents[$i]); $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
    return $path
}
Check 'exact-basic-marker' { Assert-ProbeMarkers (Marker-Zip 'basic.zip' @('assets/remote-basic.marker') @("d31-basic-v1`n")) basic }
Check 'full-marker-rejected' { Expect-Reject { Assert-ProbeMarkers (Marker-Zip 'full.zip' @('assets/remote-full.marker') @("d31-full-v1`n")) basic } 'apk-marker-kind' }
Check 'dual-markers-rejected' { Expect-Reject { Assert-ProbeMarkers (Marker-Zip 'dual.zip' @('assets/remote-basic.marker','assets/remote-full.marker') @("d31-basic-v1`n","d31-full-v1`n")) basic } 'apk-marker-kind' }
Check 'duplicate-marker-rejected' { Expect-Reject { Assert-ProbeMarkers (Marker-Zip 'duplicate.zip' @('assets/remote-basic.marker','assets/remote-basic.marker') @("d31-basic-v1`n","d31-basic-v1`n")) basic } 'apk-marker-kind' }
Check 'missing-marker-rejected' { Expect-Reject { Assert-ProbeMarkers (Marker-Zip 'missing.zip' @('other') @('other')) basic } 'apk-marker-kind' }
Check 'marker-bom-rejected' { Expect-Reject { Assert-ProbeMarkers (Marker-Zip 'bom.zip' @('assets/remote-basic.marker') @(([char]0xfeff+"d31-basic-v1`n"))) basic } 'apk-marker-length' }
Check 'marker-crlf-rejected' { Expect-Reject { Assert-ProbeMarkers (Marker-Zip 'crlf.zip' @('assets/remote-basic.marker') @("d31-basic-v1`r`n")) basic } 'apk-marker-length' }
Check 'marker-content-rejected' { Expect-Reject { Assert-ProbeMarkers (Marker-Zip 'content.zip' @('assets/remote-basic.marker') @("d31-basic-v2`n")) basic } 'apk-marker-content' }
Check 'marker-case-rejected' { Expect-Reject { Assert-ProbeMarkers (Marker-Zip 'case.zip' @('assets/Remote-basic.marker') @("d31-basic-v1`n")) basic } 'apk-marker-kind' }
Check 'forged-metadata-does-not-allow-unsigned-apk' {
    $apk = Join-Path $root 'basic.zip'; $c = Clone $contract
    $c.artifacts[0].size = (Get-Item -LiteralPath $apk).Length; $c.artifacts[0].bytes = $c.artifacts[0].size
    $c.artifacts[0].sha256 = (Get-FileHash -LiteralPath $apk).Hash.ToLowerInvariant()
    Expect-Reject { Test-ProbeApk $apk $c basic @toolArgs } 'aapt-failed'
}
$stage = Join-Path $root 'stage'
Check 'prepare-real-basic-resource' { $null = & (Join-Path $tools 'Prepare-BasicProbeResources.ps1') -BasicApk $BasicApk -MetadataPath $MetadataPath -OutputDirectory $stage @toolArgs }
Check 'stage-no-overwrite' { Expect-Reject { & (Join-Path $tools 'Prepare-BasicProbeResources.ps1') -BasicApk $BasicApk -MetadataPath $MetadataPath -OutputDirectory $stage @toolArgs } 'stage-already-exists' }
$exports = (New-Item -ItemType Directory -Path (Join-Path $root 'exports')).FullName
Check 'export-identical-to-candidate' {
    $export = & (Join-Path $tools 'Export-BasicProbeResource.ps1') -StageDirectory $stage -OutputDirectory $exports @toolArgs
    Assert-Probe ((Get-FileHash -LiteralPath $export.Path).Hash -ieq $basic.sha256 -and (Get-Item -LiteralPath $export.Path).Length -eq $basic.size) 'export-mismatch'
}
Check 'export-no-overwrite' { Expect-Reject { & (Join-Path $tools 'Export-BasicProbeResource.ps1') -StageDirectory $stage -OutputDirectory $exports @toolArgs } 'already exists' }
Check 'real-runtime-extraction-and-repair' {
    $manifest = Get-Content -LiteralPath (Join-Path $stage 'basic-probe-resource.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $constants = 'namespace D31FlashTool { internal static class BuildConstants { internal const string ToolVersion = "basic-probe-offline-test"; internal static readonly RuntimeAssetDescriptor[] RuntimeFiles = new RuntimeAssetDescriptor[] { ' + $manifest.descriptor + ' }; } }'
    $generated = Join-Path $root 'BasicProbeBuildConstants.g.cs'
    [IO.File]::WriteAllText($generated,$constants,[Text.UTF8Encoding]::new($false))
    $dll = Join-Path $root 'BasicProbeRuntimeTest.dll'
    $resource = Join-Path $stage $manifest.runtimeFiles[0].RelativePath
    $csc = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    $output = & $csc /nologo /target:library "/out:$dll" "/resource:$resource,$($manifest.runtimeFiles[0].ResourceName)" (Join-Path $tools '../src/RuntimeAssets.cs') (Join-Path $PSScriptRoot 'BasicProbeRuntimeHarness.cs') $generated 2>&1
    Assert-Probe ($LASTEXITCODE -eq 0) ('runtime-compile-' + ($output -join ' '))
    Add-Type -Path $dll
    $extract = Join-Path $root 'runtime-extracted'
    [D31FlashTool.BasicProbeRuntimeHarness]::Extract($extract)
    $file = Join-Path $extract $manifest.runtimeFiles[0].RelativePath
    Assert-Probe ((Get-FileHash -LiteralPath $file).Hash -ieq $basic.sha256) 'runtime-extract-hash'
    $stamp = (Get-Item -LiteralPath $file).LastWriteTimeUtc
    [D31FlashTool.BasicProbeRuntimeHarness]::Extract($extract)
    Assert-Probe ((Get-Item -LiteralPath $file).LastWriteTimeUtc -eq $stamp) 'runtime-unnecessary-overwrite'
    [IO.File]::WriteAllText($file,'test-corruption')
    [D31FlashTool.BasicProbeRuntimeHarness]::Extract($extract)
    Assert-Probe ((Get-FileHash -LiteralPath $file).Hash -ieq $basic.sha256) 'runtime-repair-hash'
}
Check 'stage-descriptor-injection-rejected' {
    $m=Get-Content -LiteralPath (Join-Path $stage 'basic-probe-resource.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $m.descriptor='untrusted-code'
    Expect-Reject { Assert-ProbeResourceManifest $m $basic } 'stage-build-contract'
}
Check 'stage-source-path-escape-rejected' {
    $m=Get-Content -LiteralPath (Join-Path $stage 'basic-probe-resource.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $m.runtimeSources[0].Source='../outside.apk'
    Expect-Reject { Assert-ProbeResourceManifest $m $basic } 'stage-build-contract'
}
Check 'stage-corrupted-apk-export-rejected' {
    $corrupt=Join-Path $root 'corrupted-stage'
    Copy-Item -LiteralPath $stage -Destination $corrupt -Recurse
    [IO.File]::WriteAllText((Join-Path $corrupt (Get-ProbeRelativePath $basic)),'test-corruption')
    Expect-Reject { & (Join-Path $tools 'Export-BasicProbeResource.ps1') -StageDirectory $corrupt -OutputDirectory $exports @toolArgs } 'apk-size-hash'
}

# Synthetic inventory is never used as real device evidence.
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$inventoryHash = 'f'*64
function Archive($Artifact, [string]$Path) {
    return [ordered]@{package=$Artifact.package;certSha256=$Artifact.certSha256;versionCode=$Artifact.versionCode;versionName=$Artifact.versionName;sha256=$Artifact.sha256;size=$Artifact.size;path=$Path}
}
$installed = Archive $basic '/data/app/net.elfradio.d31bootstrap-1/base.apk'
$hookPath = Join-Path $root 'hook-before.bin'
[IO.File]::WriteAllBytes($hookPath,[Text.Encoding]::ASCII.GetBytes("original-hook-fixture`n"))
$hookHash = (Get-FileHash -LiteralPath $hookPath).Hash.ToLowerInvariant()
$hookSize = (Get-Item -LiteralPath $hookPath).Length
Copy-ProbeVerified $BasicApk (Join-Path $root 'basic-before.apk') $basic.sha256
Copy-ProbeVerified $FullApk (Join-Path $root 'full-before.apk') $full.sha256
$inventory = Clone ([ordered]@{
    schemaVersion=1; operation='runtime_inventory'; capturedAtMs=$now
    installed=@{state='OBSERVED';metadata=$installed};systemArchive=@{state='READ_FAILED'};active=@{state='READ_FAILED'}
    installation=@{state='OBSERVED';metadata=@{system=$false;updatedSystem=$false;privileged=$false;uid=10001;grantedPermissions=@()}}
    health=@{state='READ_FAILED'};assessment=@{loadedCode='NOT_CHECKED';coreHealth='NOT_CONFIRMED'}
})
$evidence = Clone ([ordered]@{
    schemaVersion=1;inventorySha256=$inventoryHash
    device=@{sdk=23;device='hct6735_66_m0';model='hct6737t_66_m0';rootUid=0}
    systemFiles=@{marker='ABSENT';directory='ABSENT';startScript='ABSENT'}
    markers=@{};activeProcess=@{state='ABSENT'};activePointer='ABSENT'
    cloudBusy=$false;manualPending=$false;supervisorReady=$false
    permissions=@{checked=$true;candidateSha256=$full.sha256;systemFiles=@()};rollbackReviewed=$true
    hook=@{path='/system/bin/install-recovery.sh';sha256=$hookHash;size=$hookSize}
    backups=@(@{sourcePath=$installed.path;sha256=$basic.sha256;bytes=$basic.size;file='basic-before.apk'},
        @{sourcePath='/system/bin/install-recovery.sh';sha256=$hookHash;bytes=$hookSize;file='hook-before.bin'})
})
function Plan($i=$inventory, $e=$evidence, $c=$contract) { Get-BasicProbeMigrationPlan $i $e $c $inventoryHash $root -NowMs $now }
Check 'ordinary-basic-classification' { $p=Plan; Assert-Probe ($p.prerequisitesSatisfied -and $p.basicMayBeInstalled -and $p.systemState -ceq 'ORDINARY' -and $p.route -ceq 'FIRST_SYSTEM_DEPLOYMENT_REVIEW') ($p.blockers -join ',') }
Check 'missing-installation-not-fresh-device' { $i=Clone $inventory; $i.installed.state='READ_FAILED'; $p=Plan $i; Assert-Probe (-not $p.prerequisitesSatisfied -and -not $p.basicMayBeInstalled) 'read-failure-not-absence' }
Check 'old-value-envelope-rejected' { $i=Clone $inventory; $i.installation= [pscustomobject]@{state='OBSERVED';value=$i.installation.metadata}; Assert-Probe (-not (Plan $i).prerequisitesSatisfied) 'metadata-envelope-required' }
Check 'missing-marker-evidence-blocks' { $e=Clone $evidence; $e.systemFiles.marker='UNKNOWN'; Assert-Probe (-not (Plan $inventory $e).prerequisitesSatisfied) 'unknown-system' }
Check 'stale-inventory-blocks' { $i=Clone $inventory; $i.capturedAtMs=$now-1800001; Assert-Probe ((Plan $i).blockers -contains 'inventory-stale-or-time-unknown') 'stale' }
Check 'future-inventory-blocks' { $i=Clone $inventory; $i.capturedAtMs=$now+1; Assert-Probe (-not (Plan $i).prerequisitesSatisfied) 'future' }
Check 'inventory-binding-blocks' { $e=Clone $evidence; $e.inventorySha256='a'*64; Assert-Probe ((Plan $inventory $e).blockers -contains 'evidence-not-bound-to-inventory') 'binding' }
Check 'backup-mismatch-blocks' { $e=Clone $evidence; $e.backups[0].file='hook-before.bin'; Assert-Probe ((Plan $inventory $e).blockers -contains 'archive-preimage-missing-or-mismatch') 'preimage' }
Check 'backup-path-escape-blocks' { $e=Clone $evidence; $e.backups[0].file='../outside.apk'; Assert-Probe (-not (Plan $inventory $e).prerequisitesSatisfied) 'backup-path' }
Check 'hook-backup-required' { $e=Clone $evidence; $e.backups=@($e.backups[0]); Assert-Probe ((Plan $inventory $e).blockers -contains 'hook-preimage-missing-or-mismatch') 'hook' }
Check 'permission-review-required' { $e=Clone $evidence; $e.permissions.checked=$false; Assert-Probe ((Plan $inventory $e).blockers -contains 'candidate-permissions-review-missing') 'permissions' }
Check 'permission-review-bound-to-candidate' { $e=Clone $evidence; $e.permissions.candidateSha256='a'*64; Assert-Probe (-not (Plan $inventory $e).prerequisitesSatisfied) 'permission-candidate' }
Check 'pending-cloud-update-blocks' { $e=Clone $evidence; $e.cloudBusy=$true; Assert-Probe ((Plan $inventory $e).blockers -contains 'cloudBusy-or-unknown') 'cloud' }
Check 'pending-manual-handoff-blocks' { $e=Clone $evidence; $e.manualPending=$true; Assert-Probe ((Plan $inventory $e).blockers -contains 'manualPending-or-unknown') 'manual' }
Check 'unknown-receipt-cannot-authorize' { $e=Clone $evidence; $e.manualPending=$null; Assert-Probe (-not (Plan $inventory $e).prerequisitesSatisfied) 'unknown' }
Check 'rollback-review-required' { $e=Clone $evidence; $e.rollbackReviewed=$false; Assert-Probe (-not (Plan $inventory $e).prerequisitesSatisfied) 'rollback' }
Check 'root-required-for-migration' { $e=Clone $evidence; $e.device.rootUid=2000; Assert-Probe (-not (Plan $inventory $e).prerequisitesSatisfied) 'root' }

$managed = Clone $inventory
$systemPath='/system/priv-app/D31ElfRemote/D31ElfRemote.apk'
$activePath="/data/local/d31-remote/releases/$($full.sha256)/remote.apk"
$managed.installed.metadata = Clone (Archive $full $installed.path)
$managed.systemArchive = [pscustomobject]@{state='OBSERVED';metadata=(Clone (Archive $full $systemPath))}
$managed.active = [pscustomobject]@{state='OBSERVED';metadata=(Clone (Archive $full $activePath))}
$managed.installation.metadata.system=$true; $managed.installation.metadata.updatedSystem=$true; $managed.installation.metadata.privileged=$true
$managedEvidence = Clone $evidence
$managedEvidence.systemFiles.marker='PRESENT';$managedEvidence.systemFiles.directory='PRESENT';$managedEvidence.systemFiles.startScript='PRESENT'
$managedEvidence.activePointer='PRESENT';$managedEvidence.supervisorReady=$true
$managedEvidence.activeProcess=[pscustomobject]@{state='OBSERVED';path=$activePath;sha256=$full.sha256;uid=0;loadedArchiveVerified=$true}
$managedEvidence.backups = @($evidence.backups[1])
foreach($path in @($installed.path,$systemPath,$activePath)) { $managedEvidence.backups += [pscustomobject]@{sourcePath=$path;sha256=$full.sha256;bytes=$full.size;file='full-before.apk'} }
foreach($pair in @(@('/system/priv-app/D31ElfRemote','0755'),@($systemPath,'0644'),@('/system/etc/d31-elfremote.system','0644'),@('/system/bin/d31-elfremote-start','0755'))) {
    $managedEvidence.permissions.systemFiles += [pscustomobject]@{path=$pair[0];mode=$pair[1];uid=0;gid=0;selinux='u:object_r:system_file:s0'}
}
function Add-ActiveRecord($i,$e,[string]$Name) {
    $file=Save-Json $Name $i.active.metadata
    $hash=(Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant();$size=(Get-Item -LiteralPath $file).Length
    $record=[pscustomobject]@{path='/data/local/d31-remote/runtime/active.json';sha256=$hash;size=$size}
    $e | Add-Member -NotePropertyName activeRecord -NotePropertyValue $record -Force
    $e.backups += [pscustomobject]@{sourcePath=$record.path;sha256=$hash;bytes=$size;file=$Name}
}
Add-ActiveRecord $managed $managedEvidence 'managed-active-before.json'
Check 'managed-full-never-basic-takeover' { $p=Plan $managed $managedEvidence; Assert-Probe ($p.prerequisitesSatisfied -and -not $p.basicMayBeInstalled -and -not $p.basicMayTakeOverManagedCore -and $p.installationState -ceq 'DATA_SYSTEM_UPDATE') ($p.blockers -join ',') }
Check 'active-record-original-required' { $e=Clone $managedEvidence;$e.activeRecord=$null;Assert-Probe ((Plan $managed $e).blockers -contains 'active-record-preimage-missing-or-mismatch') 'active-record' }
Check 'active-record-must-match-inventory' { $i=Clone $managed;$i.active.metadata.versionCode--;Assert-Probe ((Plan $i $managedEvidence).blockers -contains 'active-record-preimage-missing-or-mismatch') 'active-record-version' }
Check 'loaded-not-equated-to-health-acceptance' { $p=Plan $managed $managedEvidence; Assert-Probe (-not $p.deviceAccepted -and -not $p.adbHandshakeVerified -and $p.healthEvidence -ceq 'SELF_REPORT_ONLY') 'acceptance-boundary' }
Check 'selfreport-alone-cannot-prove-loaded' {
    $i=Clone $managed; $e=Clone $managedEvidence; $i.assessment.coreHealth='FRESH_SELF_REPORTED_MATCH'; $e.activeProcess=[pscustomobject]@{state='UNKNOWN'}
    $p=Plan $i $e; Assert-Probe ($p.blockers -contains 'loaded-core-not-independently-verified' -and $p.loadedCore -ceq 'NOT_CONFIRMED') 'selfreport'
}
Check 'process-hash-mismatch-blocks' { $e=Clone $managedEvidence;$e.activeProcess.sha256='a'*64;Assert-Probe (-not (Plan $managed $e).prerequisitesSatisfied) 'loaded-hash' }
Check 'marker-alone-not-system-full' { $i=Clone $managed;$i.systemArchive.state='READ_FAILED';Assert-Probe ((Plan $i $managedEvidence).blockers -contains 'managed-system-incomplete') 'marker-only' }
Check 'hidden-pm-version-not-system-archive' { $i=Clone $managed;$i.systemArchive.state='READ_FAILED';$i.installation.metadata | Add-Member hiddenSystemVersionCode 75;Assert-Probe (-not (Plan $i $managedEvidence).prerequisitesSatisfied) 'hidden-version' }
Check 'system-permissions-require-readback' { $e=Clone $managedEvidence;$e.permissions.systemFiles[1].mode='0777';Assert-Probe ((Plan $managed $e).blockers -contains 'system-permissions-unverified') 'system-mode' }
Check 'flags-do-not-substitute-for-path' { $i=Clone $managed;$i.installed.metadata.path=$systemPath;Assert-Probe ((Plan $i $managedEvidence).blockers -contains 'installation-flags-path-conflict') 'flags' }
Check 'same-version-different-full-rejected' { $i=Clone $managed;$i.installed.metadata.sha256='b'*64;Assert-Probe ((Plan $i $managedEvidence).blockers -contains 'candidate-downgrade-or-equal-version-conflict') 'equal-conflict' }
Check 'newer-active-full-rejects-downgrade' { $i=Clone $managed;$i.active.metadata.versionCode++;Assert-Probe ((Plan $i $managedEvidence).blockers -contains 'candidate-downgrade-or-equal-version-conflict') 'downgrade' }

# Historical archives use distinct local fixture bytes, never counterfeit real APK acceptance.
$oldBytes=[Text.Encoding]::ASCII.GetBytes('historical-full-fixture')
$oldPath=Join-Path $root 'historical-before.bin';[IO.File]::WriteAllBytes($oldPath,$oldBytes)
$oldHash=(Get-FileHash -LiteralPath $oldPath).Hash.ToLowerInvariant()
$historical = Clone $managed
$historicalEvidence=Clone $managedEvidence
foreach($name in @('installed','systemArchive','active')) {
    $a=$historical.$name.metadata;$a.versionCode=90;$a.versionName='historical';$a.sha256=$oldHash;$a.size=$oldBytes.Length
    if($name -eq 'active'){$a.path="/data/local/d31-remote/releases/$oldHash/remote.apk"}
    $historicalEvidence.markers | Add-Member $name ([pscustomobject]@{kind='full';sha256=$oldHash;exact=$true})
}
$historicalEvidence.activeProcess.path=$historical.active.metadata.path;$historicalEvidence.activeProcess.sha256=$oldHash
$historicalEvidence.backups=@($evidence.backups[1])
foreach($name in @('installed','systemArchive','active')){$a=$historical.$name.metadata;$historicalEvidence.backups += [pscustomobject]@{sourcePath=$a.path;sha256=$a.sha256;bytes=$a.size;file='historical-before.bin'}}
Add-ActiveRecord $historical $historicalEvidence 'historical-active-before.json'
Check 'installed90-system90-active90-to-full-candidate' { $p=Plan $historical $historicalEvidence;Assert-Probe ($p.prerequisitesSatisfied -and $p.route -ceq 'MANUAL_FULL_HANDOFF_REVIEW' -and -not $p.basicMayBeInstalled) ($p.blockers -join ',') }
Check 'old-supervisor-baseline-classified-separately' { $i=Clone $historical;$i.systemArchive.metadata.versionCode=75;$p=Plan $i $historicalEvidence;Assert-Probe ($p.prerequisitesSatisfied -and $p.route -ceq 'MANUAL_BOOTSTRAP_BASELINE_REVIEW') ($p.blockers -join ',') }
Check 'compatible-system-baseline-may-differ' { $i=Clone $historical;$i.systemArchive.metadata.versionCode=85;$p=Plan $i $historicalEvidence;Assert-Probe ($p.prerequisitesSatisfied -and $p.route -ceq 'MANUAL_FULL_HANDOFF_REVIEW') ($p.blockers -join ',') }
Check 'inventory-alone-classifies-system-update-without-authorizing' {
    $i=Clone $historical;$i.systemArchive.metadata.versionCode=85
    $p=Plan $i ([pscustomobject]@{})
    Assert-Probe ($p.classification -ceq 'MANAGED_SYSTEM_UPDATE_OBSERVED' -and -not $p.prerequisitesSatisfied -and
        $p.loadedCore -ceq 'NOT_CONFIRMED' -and $p.notes -contains 'compatible-baseline-need-not-equal-installed-version') 'layering-only'
}
Check 'installed-new-full-active-old-is-pending-not-success' {
    $i=Clone $historical;$e=Clone $historicalEvidence;$i.installed.metadata=Clone $managed.installed.metadata
    $e.backups[1]=Clone $managedEvidence.backups[1]
    $p=Plan $i $e;Assert-Probe ($p.prerequisitesSatisfied -and $p.notes -contains 'installed-active-different-not-loaded-proof' -and -not $p.deviceAccepted) ($p.blockers -join ',')
}
Check 'cli-consumes-real-inventory-contract-offline' {
    $ip=Save-Json 'inventory-fixture.json' $inventory
    $e=Clone $evidence;$e.inventorySha256=(Get-FileHash -LiteralPath $ip).Hash.ToLowerInvariant()
    $ep=Save-Json 'evidence-fixture.json' $e
    $p=& (Join-Path $tools 'Get-BasicProbeMigrationPlan.ps1') -InventoryPath $ip -EvidencePath $ep -BasicApk $BasicApk -FullApk $FullApk -MetadataPath $MetadataPath @toolArgs | ConvertFrom-Json
    Assert-Probe ($p.prerequisitesSatisfied -and -not $p.deviceAccepted) 'cli-plan'
}
Write-ProbeJson (Join-Path $root 'BasicProbeTestResults.json') $results.ToArray()
$failed=@($results | Where-Object { -not $_.passed })
Write-Host "TOTAL $($results.Count) PASSED $($results.Count-$failed.Count) FAILED $($failed.Count)"
if($failed.Count -gt 0){throw 'BasicProbe:test-failures'}
