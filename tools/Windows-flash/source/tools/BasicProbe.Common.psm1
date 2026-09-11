Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:Package = 'net.elfradio.d31bootstrap'
$script:Certificate = '9b31f89fa50b672ecfe02d73a534cc03f6cf893739aec268f9fe0b71e72da72e'

function Get-ProbeField($Value, [string]$Name) {
    if ($null -eq $Value) { return $null }
    $property = $Value.PSObject.Properties[$Name]
    if ($null -ne $property) { return $property.Value }
    return $null
}

function Assert-Probe([bool]$Condition, [string]$Reason) {
    if (-not $Condition) { throw "BasicProbe:$Reason" }
}

function Test-ProbeInteger($Value, [long]$Minimum, [long]$Maximum) {
    return (($Value -is [int] -or $Value -is [long]) -and $Value -ge $Minimum -and $Value -le $Maximum)
}

function Read-ProbeContract([string]$MetadataPath) {
    $index = Get-Content -LiteralPath $MetadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Probe ((Get-ProbeField $index schema) -eq 1) 'metadata-schema'
    Assert-Probe ((Get-ProbeField $index status) -ceq 'candidate-offline-verified') 'metadata-status'
    $artifacts = @(Get-ProbeField $index artifacts)
    Assert-Probe ($artifacts.Count -eq 2) 'metadata-artifact-count'
    foreach ($kind in @('basic', 'full')) {
        $found = @($artifacts | Where-Object { (Get-ProbeField $_ artifact) -ceq $kind })
        Assert-Probe ($found.Count -eq 1) 'metadata-artifact-kind'
        $a = $found[0]
        Assert-Probe ($a.package -ceq $script:Package) 'metadata-package'
        Assert-Probe ($a.remote_full -is [bool] -and $a.remote_full -eq ($kind -eq 'full')) 'metadata-flavor'
        Assert-Probe ($a.certSha256 -ceq $script:Certificate -and $a.certificateSha256 -ceq $script:Certificate) 'metadata-certificate'
        Assert-Probe ((Test-ProbeInteger $a.versionCode 91 2100000000)) 'metadata-version'
        Assert-Probe ($a.versionName -cmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$' -and $a.versionName -notmatch 'not-for-install|preview') 'metadata-version-name'
        Assert-Probe ($a.sha256 -cmatch '^[a-f0-9]{64}$') 'metadata-hash'
        Assert-Probe ((Test-ProbeInteger $a.size 1 ([long]::MaxValue)) -and $a.size -eq $a.bytes) 'metadata-size'
        Assert-Probe ($a.minSdk -eq 21 -and $a.targetSdk -eq 27 -and $a.target -ceq 'D31') 'metadata-platform'
        Assert-Probe ($a.debuggable -is [bool] -and -not $a.debuggable) 'metadata-debuggable'
    }
    $basic = @($artifacts | Where-Object artifact -CEQ 'basic')[0]
    $full = @($artifacts | Where-Object artifact -CEQ 'full')[0]
    Assert-Probe ($basic.versionCode -eq $index.basicVersionCode -and $full.versionCode -eq $index.fullVersionCode -and $full.versionCode -eq ($basic.versionCode + 1)) 'metadata-version-pair'
    Assert-Probe ($basic.versionName -ceq ($full.versionName + '-basic')) 'metadata-name-pair'
    $previous = Get-ProbeField $index previousVerifiedFullVersionCode
    if ($null -ne $previous) {
        Assert-Probe ((Test-ProbeInteger $previous 1 2099999999) -and $basic.versionCode -gt $previous) 'metadata-previous-version'
    }
    Assert-Probe (@($basic.dependencies).Count -eq 1 -and $basic.dependencies[0] -ceq 'org.nanohttpd:nanohttpd:2.3.1') 'metadata-basic-dependencies'
    return $index
}

function Assert-ProbeMarkers([string]$ApkPath, [ValidateSet('basic','full')][string]$Kind) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $entries = @($zip.Entries | Where-Object { $_.FullName -imatch '^assets/remote-(basic|full)\.marker$' })
        Assert-Probe ($entries.Count -eq 1 -and $entries[0].FullName -ceq "assets/remote-$Kind.marker") 'apk-marker-kind'
        $expected = [Text.Encoding]::ASCII.GetBytes("d31-$Kind-v1`n")
        Assert-Probe ($entries[0].Length -eq $expected.Length) 'apk-marker-length'
        $stream = $entries[0].Open()
        try {
            foreach ($byte in $expected) { Assert-Probe ($stream.ReadByte() -eq $byte) 'apk-marker-content' }
            Assert-Probe ($stream.ReadByte() -eq -1) 'apk-marker-tail'
        } finally { $stream.Dispose() }
    } finally { $zip.Dispose() }
}

function Assert-ProbeIdentity($Artifact, [string]$Badging, [string]$Signature) {
    $package = [regex]::Match($Badging, "(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'")
    Assert-Probe ($package.Success -and $package.Groups[1].Value -ceq $script:Package) 'apk-package'
    Assert-Probe ([long]$package.Groups[2].Value -eq $Artifact.versionCode -and $package.Groups[3].Value -ceq $Artifact.versionName) 'apk-version'
    Assert-Probe ($Badging -cmatch "(?m)^sdkVersion:'21'\r?$" -and $Badging -cmatch "(?m)^targetSdkVersion:'27'\r?$" -and $Badging -notmatch '(?m)^application-debuggable') 'apk-sdk-debuggable'
    $signers = [regex]::Matches($Signature, '(?m)^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\r?$')
    Assert-Probe ($signers.Count -eq 1 -and $signers[0].Groups[1].Value.ToLowerInvariant() -ceq $script:Certificate) 'apk-certificate'
    Assert-Probe ($Signature -cmatch '(?m)^Verified using v1 scheme \(JAR signing\): true\r?$') 'apk-android6-signature'
}

function Invoke-ProbeTool([string]$Executable, [string[]]$Arguments, [string]$Failure) {
    # Windows PowerShell 5.1 wraps native stderr as ErrorRecord; normalize by exit code.
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    try { $output = & $Executable @Arguments 2>&1; $code = $LASTEXITCODE }
    catch { throw "BasicProbe:$Failure" }
    Assert-Probe ($null -ne $code -and $code -eq 0) $Failure
    return ($output -join "`n")
}

function Test-ProbeApk {
    param([string]$ApkPath, $Contract, [ValidateSet('basic','full')][string]$Kind,
        [string]$AaptPath, [string]$JavaPath, [string]$ApkSignerJar)
    $a = @($Contract.artifacts | Where-Object artifact -CEQ $Kind)[0]
    $apk = (Get-Item -LiteralPath $ApkPath).FullName
    Assert-Probe ((Get-Item -LiteralPath $apk).Length -eq $a.size -and (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $a.sha256) 'apk-size-hash'
    Assert-ProbeMarkers $apk $Kind
    $badging = Invoke-ProbeTool $AaptPath @('dump','badging',$apk) 'aapt-failed'
    $signature = Invoke-ProbeTool $JavaPath @('-jar',$ApkSignerJar,'verify','--verbose','--print-certs','--min-sdk-version','23',$apk) 'apksigner-failed'
    Assert-ProbeIdentity $a $badging $signature
    Assert-Probe ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $a.sha256) 'apk-changed-during-verification'
    return $a
}

function Get-ProbeRelativePath($Artifact) {
    $directory = -join ([char[]]@(0x9996,0x6b21,0x5f15,0x5bfc,0x5de5,0x5177))
    return "$directory\D31-basic-v$($Artifact.versionName)-$($Artifact.versionCode).apk"
}

function Assert-ProbeResourceManifest($Manifest, $Basic) {
    $relative = Get-ProbeRelativePath $Basic
    $resource = 'D31FlashTool.Runtime.BasicProbe' + $Basic.versionCode
    $descriptor = 'new RuntimeAssetDescriptor("{0}", "{1}", "{2}")' -f $relative.Replace('\','\\'), $resource, $Basic.sha256
    Assert-Probe ($Manifest.schema -eq 1 -and $Manifest.status -ceq 'basic-resource-offline-verified' -and $Manifest.metadataFile -ceq 'remote-variants.json') 'stage-manifest'
    Assert-Probe (@($Manifest.runtimeFiles).Count -eq 1 -and $Manifest.runtimeFiles[0].RelativePath -ceq $relative -and
        $Manifest.runtimeFiles[0].ResourceName -ceq $resource -and $Manifest.runtimeFiles[0].Sha256 -ceq $Basic.sha256 -and
        $Manifest.runtimeFiles[0].Bytes -eq $Basic.size) 'stage-resource-contract'
    Assert-Probe (@($Manifest.runtimeSources).Count -eq 1 -and $Manifest.runtimeSources[0].Relative -ceq $relative -and
        $Manifest.runtimeSources[0].Source -ceq $relative -and $Manifest.runtimeSources[0].Bom -is [bool] -and
        -not $Manifest.runtimeSources[0].Bom -and $Manifest.descriptor -ceq $descriptor) 'stage-build-contract'
    foreach ($field in @('artifact','remote_full','package','certSha256','certificateSha256','sha256','size','bytes','versionCode','versionName','debuggable','minSdk','targetSdk')) {
        Assert-Probe ((Get-ProbeField $Manifest.artifact $field) -ceq (Get-ProbeField $Basic $field)) 'stage-artifact-contract'
    }
    Assert-Probe ($Manifest.deviceAcceptance -is [bool] -and -not $Manifest.deviceAcceptance) 'stage-acceptance-contract'
}

function Copy-ProbeVerified([string]$Source, [string]$Destination, [string]$Sha256) {
    # CreateNew prevents overwriting either evidence or a concurrent export.
    $inputStream = [IO.File]::OpenRead($Source)
    try {
        $outputStream = [IO.File]::Open($Destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
    } finally { $inputStream.Dispose() }
    Assert-Probe ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -ieq $Sha256) 'copy-hash'
}

function Write-ProbeJson([string]$Path, $Value) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $writer = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($false))
    try { $writer.Write(($Value | ConvertTo-Json -Depth 20)) } finally { $writer.Dispose() }
}

Export-ModuleMember -Function *-Probe*, Get-ProbeField
