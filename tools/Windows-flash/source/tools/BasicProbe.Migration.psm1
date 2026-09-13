Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'BasicProbe.Common.psm1')

function Test-InventoryArchive($Archive) {
    return ($null -ne $Archive -and (Get-ProbeField $Archive package) -ceq 'net.elfradio.d31bootstrap' -and
        (Get-ProbeField $Archive certSha256) -ceq '9b31f89fa50b672ecfe02d73a534cc03f6cf893739aec268f9fe0b71e72da72e' -and
        (Test-ProbeInteger (Get-ProbeField $Archive versionCode) 1 2100000000) -and
        (Test-ProbeInteger (Get-ProbeField $Archive size) 1 ([long]::MaxValue)) -and
        (Get-ProbeField $Archive sha256) -cmatch '^[a-f0-9]{64}$' -and
        (Get-ProbeField $Archive path) -cmatch '^/[^\r\n]+$')
}

function Get-InventoryArchive($Inventory, [string]$Name) {
    $entry = Get-ProbeField $Inventory $Name
    if ((Get-ProbeField $entry state) -ceq 'OBSERVED') { return Get-ProbeField $entry metadata }
    return $null
}

function Get-InventoryFlavor($Archive, $Marker, $Contract) {
    if (-not (Test-InventoryArchive $Archive)) { return 'UNKNOWN' }
    foreach ($a in $Contract.artifacts) {
        if ($Archive.sha256 -ceq $a.sha256 -and $Archive.versionCode -eq $a.versionCode -and $Archive.size -eq $a.size) { return $a.artifact }
    }
    if ((Get-ProbeField $Marker sha256) -ceq $Archive.sha256 -and (Get-ProbeField $Marker exact) -is [bool] -and $Marker.exact -and
        (Get-ProbeField $Marker kind) -cin @('basic','full')) { return $Marker.kind }
    return 'UNKNOWN'
}

function Test-LocalPreimage($Source, $Backups, [string]$EvidenceDirectory) {
    $found = @($Backups | Where-Object { (Get-ProbeField $_ sourcePath) -ceq $Source.path })
    if ($found.Count -ne 1) { return $false }
    $backup = $found[0]
    if ((Get-ProbeField $backup sha256) -cne $Source.sha256 -or (Get-ProbeField $backup bytes) -ne $Source.size) { return $false }
    try {
        $root = [IO.Path]::GetFullPath($EvidenceDirectory).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
        $path = [IO.Path]::GetFullPath((Join-Path $root $backup.file))
        if (-not $path.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { return $false }
        return ((Get-Item -LiteralPath $path).Length -eq $Source.size -and (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ieq $Source.sha256)
    } catch { return $false }
}

function Get-BasicProbeMigrationPlan {
    param($Inventory, $Evidence, $Contract, [string]$InventorySha256, [string]$EvidenceDirectory,
        [long]$NowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(), [int]$MaxAgeMinutes = 30)
    $blocked = [Collections.Generic.List[string]]::new()
    $notes = [Collections.Generic.List[string]]::new()
    if ((Get-ProbeField $Inventory schemaVersion) -ne 1 -or (Get-ProbeField $Inventory operation) -cne 'runtime_inventory') { $blocked.Add('inventory-contract') }
    if ((Get-ProbeField $Evidence schemaVersion) -ne 1 -or (Get-ProbeField $Evidence inventorySha256) -cne $InventorySha256) { $blocked.Add('evidence-not-bound-to-inventory') }
    $time = Get-ProbeField $Inventory capturedAtMs
    if (-not (Test-ProbeInteger $time 0 ([long]::MaxValue)) -or $time -gt $NowMs -or $NowMs - $time -gt $MaxAgeMinutes * 60000L) { $blocked.Add('inventory-stale-or-time-unknown') }
    $device = Get-ProbeField $Evidence device
    if ((Get-ProbeField $device sdk) -ne 23 -or (Get-ProbeField $device device) -cne 'hct6735_66_m0' -or
        (Get-ProbeField $device model) -cne 'hct6737t_66_m0' -or (Get-ProbeField $device rootUid) -ne 0) { $blocked.Add('target-or-root-unverified') }
    $basic = @($Contract.artifacts | Where-Object artifact -CEQ basic)[0]
    $full = @($Contract.artifacts | Where-Object artifact -CEQ full)[0]
    $installed = Get-InventoryArchive $Inventory installed
    $baseline = Get-InventoryArchive $Inventory systemArchive
    $active = Get-InventoryArchive $Inventory active
    $markers = Get-ProbeField $Evidence markers
    $installedKind = Get-InventoryFlavor $installed (Get-ProbeField $markers installed) $Contract
    $baselineKind = Get-InventoryFlavor $baseline (Get-ProbeField $markers systemArchive) $Contract
    $activeKind = Get-InventoryFlavor $active (Get-ProbeField $markers active) $Contract
    if ($installedKind -ceq 'UNKNOWN') { $blocked.Add('installed-identity-or-marker-unknown') }
    $systemFiles = Get-ProbeField $Evidence systemFiles
    $marker = Get-ProbeField $systemFiles marker
    $systemState = 'UNKNOWN'
    if ($marker -ceq 'PRESENT') {
        if ($baselineKind -ceq 'full' -and $baseline.path -ceq '/system/priv-app/D31ElfRemote/D31ElfRemote.apk' -and
            (Get-ProbeField $systemFiles directory) -ceq 'PRESENT' -and (Get-ProbeField $systemFiles startScript) -ceq 'PRESENT') { $systemState = 'MANAGED_FULL' }
        else { $blocked.Add('managed-system-incomplete') }
    } elseif ($marker -ceq 'ABSENT' -and (Get-ProbeField $systemFiles directory) -ceq 'ABSENT' -and (Get-ProbeField $systemFiles startScript) -ceq 'ABSENT' -and $null -eq $baseline) {
        $systemState = 'ORDINARY'
    } else { $blocked.Add('system-presence-unknown-or-inconsistent') }
    $installation = Get-ProbeField $Inventory installation
    $facts = $null
    if ((Get-ProbeField $installation state) -ceq 'OBSERVED') {
        $facts = Get-ProbeField $installation metadata
    }
    foreach ($flag in @('system','updatedSystem','privileged')) {
        if ((Get-ProbeField $facts $flag) -isnot [bool]) { $blocked.Add("installation-$flag-unknown") }
    }
    if (-not (Test-ProbeInteger (Get-ProbeField $facts uid) 0 2147483647) -or $null -eq $facts -or
        $null -eq $facts.PSObject.Properties['grantedPermissions'] -or $facts.grantedPermissions -isnot [array]) { $blocked.Add('installation-permissions-unknown') }
    $dataUpdate = 'UNKNOWN'
    if ($null -ne $installed -and $null -ne $facts) {
        if ($installed.path -cmatch '^/data/app/[^\r\n]+\.apk$') {
            $dataUpdate = 'DATA_INSTALLED'
            if ((Get-ProbeField $facts updatedSystem) -eq $true) { $dataUpdate = 'DATA_SYSTEM_UPDATE' }
        } elseif ($installed.path -ceq '/system/priv-app/D31ElfRemote/D31ElfRemote.apk') { $dataUpdate = 'SYSTEM_INSTALLED' }
        if ($dataUpdate -ceq 'UNKNOWN' -or ((Get-ProbeField $facts updatedSystem) -eq $true -and
            ((Get-ProbeField $facts system) -ne $true -or $dataUpdate -cne 'DATA_SYSTEM_UPDATE'))) { $blocked.Add('installation-flags-path-conflict') }
        if ($systemState -ceq 'ORDINARY' -and ((Get-ProbeField $facts system) -ne $false -or (Get-ProbeField $facts updatedSystem) -ne $false)) { $blocked.Add('ordinary-system-flags-conflict') }
        if ($systemState -ceq 'MANAGED_FULL' -and (Get-ProbeField $facts system) -ne $true) { $blocked.Add('managed-system-flags-conflict') }
    }
    $classification = $systemState
    # Classification describes observed layering, not permission to write or proof of loaded code.
    if ($dataUpdate -ceq 'DATA_SYSTEM_UPDATE' -and (Test-InventoryArchive $installed) -and
        (Test-InventoryArchive $baseline) -and $baseline.path -ceq '/system/priv-app/D31ElfRemote/D31ElfRemote.apk' -and
        (Get-ProbeField $facts system) -eq $true) { $classification = 'MANAGED_SYSTEM_UPDATE_OBSERVED' }
    $process = Get-ProbeField $Evidence activeProcess
    $coreState = 'NOT_CONFIRMED'
    if ($systemState -ceq 'ORDINARY') {
        if ((Get-ProbeField $process state) -ceq 'ABSENT' -and $null -eq $active -and (Get-ProbeField $Evidence activePointer) -ceq 'ABSENT') { $coreState = 'ABSENT' }
        else { $blocked.Add('ordinary-active-state-conflict') }
    } else {
        if ($activeKind -cne 'full') { $blocked.Add('active-archive-unverified') }
        if ($activeKind -ceq 'full' -and (Get-ProbeField $process state) -ceq 'OBSERVED' -and
            (Get-ProbeField $process path) -ceq $active.path -and (Get-ProbeField $process sha256) -ceq $active.sha256 -and
            (Get-ProbeField $process uid) -eq 0 -and (Get-ProbeField $process loadedArchiveVerified) -is [bool] -and $process.loadedArchiveVerified) {
            $coreState = 'LOADED_ARCHIVE_OBSERVED'
        } else { $blocked.Add('loaded-core-not-independently-verified') }
        if ($null -ne $active -and $active.path -cne '/system/priv-app/D31ElfRemote/D31ElfRemote.apk' -and
            $active.path -cne ("/data/local/d31-remote/releases/" + $active.sha256 + '/remote.apk')) { $blocked.Add('active-path-invalid') }
        if ((Get-ProbeField $Evidence supervisorReady) -isnot [bool] -or -not $Evidence.supervisorReady) { $blocked.Add('supervisor-not-ready') }
    }
    foreach ($name in @('cloudBusy','manualPending')) {
        if ((Get-ProbeField $Evidence $name) -isnot [bool] -or $Evidence.$name) { $blocked.Add("$name-or-unknown") }
    }
    $permissions = Get-ProbeField $Evidence permissions
    if ((Get-ProbeField $permissions checked) -isnot [bool] -or -not $permissions.checked -or
        (Get-ProbeField $permissions candidateSha256) -cne $full.sha256) { $blocked.Add('candidate-permissions-review-missing') }
    if ($systemState -ceq 'MANAGED_FULL') {
        $checks = @(Get-ProbeField $permissions systemFiles)
        $required = @(
            @('/system/priv-app/D31ElfRemote','0755'),
            @('/system/priv-app/D31ElfRemote/D31ElfRemote.apk','0644'),
            @('/system/etc/d31-elfremote.system','0644'),
            @('/system/bin/d31-elfremote-start','0755')
        )
        foreach ($r in $required) {
            $matched = @($checks | Where-Object { (Get-ProbeField $_ path) -ceq $r[0] -and (Get-ProbeField $_ mode) -ceq $r[1] -and
                (Get-ProbeField $_ uid) -eq 0 -and (Get-ProbeField $_ gid) -eq 0 -and (Get-ProbeField $_ selinux) -ceq 'u:object_r:system_file:s0' })
            if ($matched.Count -ne 1) { $blocked.Add('system-permissions-unverified'); break }
        }
    }
    $backups = @(Get-ProbeField $Evidence backups)
    foreach ($archive in @($installed, $baseline, $active)) {
        if ($null -ne $archive -and -not (Test-LocalPreimage $archive $backups $EvidenceDirectory)) { $blocked.Add('archive-preimage-missing-or-mismatch') }
    }
    if ($systemState -ceq 'MANAGED_FULL') {
        $record = Get-ProbeField $Evidence activeRecord
        $recordValid = $false
        if ((Get-ProbeField $record path) -ceq '/data/local/d31-remote/runtime/active.json' -and
            (Test-LocalPreimage $record $backups $EvidenceDirectory)) {
            try {
                $entry = @($backups | Where-Object { $_.sourcePath -ceq $record.path })[0]
                if ($record.size -le 64000) {
                    $saved = Get-Content -LiteralPath (Join-Path $EvidenceDirectory $entry.file) -Raw -Encoding UTF8 | ConvertFrom-Json
                    $recordValid = (Test-InventoryArchive $saved) -and $null -ne $active -and $saved.sha256 -ceq $active.sha256 -and
                        $saved.path -ceq $active.path -and $saved.versionCode -eq $active.versionCode -and $saved.size -eq $active.size
                }
            } catch { $recordValid = $false }
        }
        if (-not $recordValid) { $blocked.Add('active-record-preimage-missing-or-mismatch') }
    }
    $hook = Get-ProbeField $Evidence hook
    if ((Get-ProbeField $hook path) -cne '/system/bin/install-recovery.sh' -or
        (Get-ProbeField $hook sha256) -cnotmatch '^[a-f0-9]{64}$' -or -not (Test-LocalPreimage $hook $backups $EvidenceDirectory)) { $blocked.Add('hook-preimage-missing-or-mismatch') }
    if ((Get-ProbeField $Evidence rollbackReviewed) -isnot [bool] -or -not $Evidence.rollbackReviewed) { $blocked.Add('rollback-not-reviewed') }
    $route = 'BLOCKED'
    if ($systemState -ceq 'ORDINARY' -and $installedKind -ceq 'basic') { $route = 'FIRST_SYSTEM_DEPLOYMENT_REVIEW' }
    elseif ($systemState -ceq 'MANAGED_FULL') {
        if ($baseline.versionCode -lt 85) { $route = 'MANUAL_BOOTSTRAP_BASELINE_REVIEW'; $notes.Add('baseline-below-manual-supervisor-minimum-85') }
        else { $route = 'MANUAL_FULL_HANDOFF_REVIEW'; $notes.Add('compatible-baseline-need-not-equal-installed-version') }
    } else { $blocked.Add('migration-route-unknown') }
    $already = $null -ne $installed -and $null -ne $active -and $installed.sha256 -ceq $full.sha256 -and $active.sha256 -ceq $full.sha256
    if ($already) { $route = 'CANDIDATE_ALREADY_SELECTED_REVIEW_HEALTH' }
    foreach ($archive in @($installed,$baseline,$active)) {
        if ($null -ne $archive -and ($archive.versionCode -gt $full.versionCode -or
            ($archive.versionCode -eq $full.versionCode -and $archive.sha256 -cne $full.sha256))) { $blocked.Add('candidate-downgrade-or-equal-version-conflict') }
    }
    if (-not $already -and $null -ne $active -and $active.versionCode -ge $full.versionCode) { $blocked.Add('manual-handoff-requires-newer-full') }
    if ($null -ne $installed -and $null -ne $active -and $installed.sha256 -cne $active.sha256) { $notes.Add('installed-active-different-not-loaded-proof') }
    if ((Test-InventoryArchive $baseline) -and $baseline.versionCode -ge 85 -and $null -ne $installed -and
        $baseline.versionCode -ne $installed.versionCode) { $notes.Add('compatible-baseline-need-not-equal-installed-version') }
    $allowBasic = $systemState -ceq 'ORDINARY' -and $installedKind -ceq 'basic' -and $coreState -ceq 'ABSENT' -and
        ($installed.versionCode -lt $basic.versionCode -or ($installed.versionCode -eq $basic.versionCode -and $installed.sha256 -ceq $basic.sha256))
    # Missing evidence is a closed gate, even for reinstalling a basic candidate.
    return [pscustomobject]@{
        schemaVersion = 1; operation = 'basic_probe_migration_plan'; mode = 'OFFLINE_REVIEW_ONLY'
        classification = $classification; systemState = $systemState; installedFlavor = $installedKind; installationState = $dataUpdate
        baselineFlavor = $baselineKind; activeFlavor = $activeKind; loadedCore = $coreState
        route = $route; prerequisitesSatisfied = ($blocked.Count -eq 0)
        basicMayBeInstalled = ($allowBasic -and $blocked.Count -eq 0)
        basicMayTakeOverManagedCore = $false; deviceAccepted = $false; adbHandshakeVerified = $false
        healthEvidence = 'SELF_REPORT_ONLY'; basicVersionCode = $basic.versionCode; fullVersionCode = $full.versionCode
        blockers = @($blocked | Select-Object -Unique); notes = $notes.ToArray()
    }
}

Export-ModuleMember -Function Get-BasicProbeMigrationPlan
