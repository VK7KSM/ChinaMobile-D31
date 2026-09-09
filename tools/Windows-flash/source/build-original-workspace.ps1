param(
    [string]$BuildSuffix = '',
    [string]$FirmwareDirectory = 'factory-flash-v1.4.2'
)
$ErrorActionPreference = "Stop"

$version = "1.6.5"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceDirectory = Join-Path $projectRoot "src"
$outputDirectory = Join-Path $projectRoot "dist-v$version$BuildSuffix"
$objectDirectory = Join-Path $projectRoot "obj-v$version$BuildSuffix"
$runtimeStage = Join-Path $objectDirectory "runtime"
$reportDirectory = Join-Path $objectDirectory "test-reports"
$releaseDirectory = Join-Path $projectRoot "..\d31\release"
if ($BuildSuffix) { $releaseDirectory = Join-Path $releaseDirectory "v$version$BuildSuffix" }
$candidate = Join-Path $objectDirectory "D31-Flash-Tool-v$version.exe"
$output = Join-Path $outputDirectory "D31-Flash-Tool-v$version.exe"
$releaseOutput = Join-Path $releaseDirectory "D31-Flash-Tool-v$version.exe"
$selfTestReport = Join-Path $reportDirectory "D31刷机工具离线自检结果.txt"
$packageReport = Join-Path $reportDirectory "D31刷机包选择流程测试.txt"
$downloadParserReport = Join-Path $reportDirectory "高速下载进度解析测试.txt"
$compatibilityReport = Join-Path $reportDirectory "Windows PowerShell 5.1中文路径兼容测试.txt"
$generated = Join-Path $objectDirectory "BuildConstants.g.cs"
$packageRoot = (Resolve-Path (Join-Path $projectRoot "..\d31\dist\$FirmwareDirectory")).Path
$package = Join-Path $packageRoot "D31_SVP3390_Factory_Flash_v1.4.2_testkey.zip"
$backendRoot = (Resolve-Path (Join-Path $projectRoot "..\d31\factory_package")).Path
$legacyAssetsRoot = (Resolve-Path (Join-Path $projectRoot "..\d31\dist\factory-flash-v1.0.4")).Path
$rescueBuild = (Resolve-Path (Join-Path $projectRoot "..\d31\dist\rescue-launcher-v1-attempt4")).Path
$aria2Root = (Resolve-Path (Join-Path $projectRoot "vendor\aria2-1.37.0-win-32bit-build1\aria2-1.37.0-win-32bit-build1")).Path
$brandLogo = (Resolve-Path (Join-Path $projectRoot "assets\elfradio-logo.png")).Path
$brandIcon = (Resolve-Path (Join-Path $projectRoot "assets\elfradio.ico")).Path
$localRecoveryApk = (Resolve-Path (Join-Path $projectRoot "..\d31_adb_bootstrap\dist\installable-v1.11.6-final\D31-wireless-adb-v1.11.6-signed.apk")).Path
$signingJava = Join-Path $projectRoot '..\..\.tools\jdk17\jdk-17.0.20+8\bin\java.exe'
$signatureResult = & $signingJava -jar C:\Dev\android-sdk\build-tools\34.0.0\lib\apksigner.jar verify --verbose --print-certs --min-sdk-version 23 $localRecoveryApk 2>&1
if ($LASTEXITCODE -ne 0 -or ($signatureResult -join "`n") -notmatch 'Verified using v1 scheme \(JAR signing\): true' -or ($signatureResult -join "`n") -notmatch '9b31f89fa50b672ecfe02d73a534cc03f6cf893739aec268f9fe0b71e72da72e') {
    throw '首次引导APK未通过安卓6签名及旧版证书兼容检查'
}
$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"

if (-not (Test-Path -LiteralPath $csc)) { throw "未找到.NET Framework C#编译器" }
if (-not (Test-Path -LiteralPath $package)) { throw "未找到D31签名刷机包" }
if (Test-Path -LiteralPath $output) { throw "v$version最终EXE已存在，拒绝覆盖：$output" }
if (Test-Path -LiteralPath $releaseOutput) { throw "v$version发布EXE已存在，拒绝覆盖：$releaseOutput" }

New-Item -ItemType Directory -Force -Path $objectDirectory, $runtimeStage, $reportDirectory | Out-Null
$approvedPath = Join-Path $backendRoot 'approved-package.json'
$approved = Get-Content -Raw -LiteralPath $approvedPath | ConvertFrom-Json
$packageItem = Get-Item -LiteralPath $package
$packageHash = (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash
if ($packageItem.Length -ne $approved.bytes -or $packageHash -ne $approved.sha256 -or $packageItem.Name -ne $approved.fileName) {
    throw '发布固件与批准清单不一致'
}
$installer = Get-Content -Raw -LiteralPath (Join-Path $backendRoot 'native\update_binary.c')
$sourcesManifest = Get-Content -Raw -LiteralPath (Join-Path $backendRoot 'sources-v1.4.2.json') | ConvertFrom-Json
$installedFiles = @()
foreach ($match in [regex]::Matches($installer, '\{"payload/(apps|system-patches|runtime)/([^"\r\n]+)", "([^"\r\n]+)"')) {
    $folder = @{apps='apks'; 'system-patches'='system_payload'; runtime='runtime'}[$match.Groups[1].Value]
    $name = $folder + '/' + $match.Groups[2].Value
    if ($folder -eq 'runtime' -or $name -match '/factory-init-required$') { continue }
    $entry = $sourcesManifest.$name
    if (-not $entry) { throw "缺少安装文件哈希：$name" }
    $installedFiles += [pscustomobject]@{path=$match.Groups[3].Value; sha256=$entry[1]}
}
if ($installedFiles.Count -ne 34) { throw '刷后核验清单项目数与安装器不一致' }
$installedManifest = Join-Path $objectDirectory 'installed-files.json'
$installedFiles | ConvertTo-Json | Set-Content -LiteralPath $installedManifest -Encoding UTF8

$runtimeSources = @(
    [PSCustomObject]@{ Relative = 'approved-package.json'; Source = $approvedPath; Bom = $false },
    [PSCustomObject]@{ Relative = 'installed-files.json'; Source = $installedManifest; Bom = $false },
    [PSCustomObject]@{ Relative = "flash_d31_recovery.ps1"; Source = (Join-Path $backendRoot "flash_d31_recovery.ps1"); Bom = $true },
    [PSCustomObject]@{ Relative = "create_d31_rescue.ps1"; Source = (Join-Path $projectRoot "scripts\create_d31_rescue.ps1"); Bom = $true },
    [PSCustomObject]@{ Relative = "tools\adb.exe"; Source = (Join-Path $legacyAssetsRoot "tools\adb.exe"); Bom = $false },
    [PSCustomObject]@{ Relative = "tools\AdbWinApi.dll"; Source = (Join-Path $legacyAssetsRoot "tools\AdbWinApi.dll"); Bom = $false },
    [PSCustomObject]@{ Relative = "tools\AdbWinUsbApi.dll"; Source = (Join-Path $legacyAssetsRoot "tools\AdbWinUsbApi.dll"); Bom = $false },
    [PSCustomObject]@{ Relative = "tools\aria2c.exe"; Source = (Join-Path $aria2Root "aria2c.exe"); Bom = $false },
    [PSCustomObject]@{ Relative = "tools\aria2-COPYING.txt"; Source = (Join-Path $aria2Root "COPYING"); Bom = $false },
    [PSCustomObject]@{ Relative = "首次引导工具\D31-setup-probe.apk"; Source = (Join-Path $legacyAssetsRoot "首次引导工具\D31-setup-probe.apk"); Bom = $false },
    [PSCustomObject]@{ Relative = "首次引导工具\D31-wireless-adb-v1.11.6.apk"; Source = $localRecoveryApk; Bom = $false },
    [PSCustomObject]@{ Relative = "rescue\D31_RESCUE_UPDATE.zip"; Source = (Join-Path $rescueBuild "D31_RESCUE_UPDATE.zip"); Bom = $false },
    [PSCustomObject]@{ Relative = "rescue\D31_RESCUE_TEST.zip"; Source = (Join-Path $rescueBuild "D31_RESCUE_TEST.zip"); Bom = $false }
)

$utf8Bom = New-Object System.Text.UTF8Encoding($true)
$resourceArguments = New-Object System.Collections.Generic.List[string]
$descriptorLines = New-Object System.Collections.Generic.List[string]
for ($index = 0; $index -lt $runtimeSources.Count; $index++) {
    $asset = $runtimeSources[$index]
    if (-not (Test-Path -LiteralPath $asset.Source)) { throw "缺少单文件运行资源：$($asset.Source)" }
    $staged = Join-Path $runtimeStage $asset.Relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $staged) | Out-Null
    if ($asset.Bom) {
        [System.IO.File]::WriteAllText(
            $staged,
            [System.IO.File]::ReadAllText($asset.Source, [System.Text.Encoding]::UTF8),
            $utf8Bom)
    } else {
        Copy-Item -LiteralPath $asset.Source -Destination $staged -Force
    }
    $resourceName = "D31FlashTool.Runtime.Asset" + $index.ToString("00")
    $resourceArguments.Add("/resource:$staged,$resourceName")
    $hash = (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash
    $relativeForCSharp = $asset.Relative.Replace("\", "\\").Replace('"', '\"')
    $descriptorLines.Add("            new RuntimeAssetDescriptor(`"$relativeForCSharp`", `"$resourceName`", `"$hash`")")
}

$rescueRestoreHash = (Get-FileHash -LiteralPath (Join-Path $rescueBuild "D31_RESCUE_UPDATE.zip") -Algorithm SHA256).Hash
$rescueTestHash = (Get-FileHash -LiteralPath (Join-Path $rescueBuild "D31_RESCUE_TEST.zip") -Algorithm SHA256).Hash
$aria2Hash = (Get-FileHash -LiteralPath (Join-Path $aria2Root "aria2c.exe") -Algorithm SHA256).Hash
$descriptors = $descriptorLines -join ",`r`n"
$generatedSource = @"
namespace D31FlashTool
{
    internal static class BuildConstants
    {
        internal const string ToolVersion = "$version";
        internal const string OfficialPackageSha256 = "$($approved.sha256)";
        internal const long OfficialPackageBytes = $($approved.bytes)L;
        internal const string RescueRestoreSha256 = "$rescueRestoreHash";
        internal const string RescueTestSha256 = "$rescueTestHash";
        internal const string Aria2Sha256 = "$aria2Hash";
        internal static readonly RuntimeAssetDescriptor[] RuntimeFiles = new RuntimeAssetDescriptor[]
        {
$descriptors
        };
    }
}
"@
[System.IO.File]::WriteAllText($generated, $generatedSource, [System.Text.UTF8Encoding]::new($false))

$sources = @(Get-ChildItem -LiteralPath $sourceDirectory -Filter "*.cs" -File | ForEach-Object { $_.FullName })
$sources += $generated
$compilerArguments = @(
    "/nologo",
    "/target:winexe",
    "/optimize+",
    "/platform:anycpu",
    "/reference:System.dll",
    "/reference:System.Core.dll",
    "/reference:System.Web.Extensions.dll",
    "/reference:System.Drawing.dll",
    "/reference:System.Windows.Forms.dll",
    "/win32icon:$brandIcon",
    "/resource:$brandLogo,D31FlashTool.Logo.png",
    "/resource:$brandIcon,D31FlashTool.AppIcon.ico",
    "/out:$candidate"
)
$compilerArguments += $resourceArguments
& $csc @compilerArguments @sources
if ($LASTEXITCODE -ne 0) { throw "D31单文件刷机工具编译失败" }

$protocolTest = Join-Path $objectDirectory 'RescueTests.exe'
& $csc /nologo /reference:System.Web.Extensions.dll "/out:$protocolTest" (Join-Path $sourceDirectory 'RescueClient.cs') (Join-Path $projectRoot 'tests\RescueTests.cs')
if ($LASTEXITCODE -ne 0) { throw '急救协议测试编译失败' }
& $protocolTest (Join-Path $reportDirectory '急救协议测试.txt')
if ($LASTEXITCODE -ne 0) { throw '急救协议测试失败' }
$layoutTest = Join-Path $objectDirectory 'RescueLayoutTests.exe'
& $csc /nologo /reference:System.Web.Extensions.dll /reference:System.Drawing.dll /reference:System.Windows.Forms.dll /main:RescueLayoutTests "/out:$layoutTest" @sources (Join-Path $projectRoot 'tests\RescueLayoutTests.cs')
if ($LASTEXITCODE -ne 0) { throw '急救布局测试编译失败' }
& $layoutTest $runtimeStage (Join-Path $reportDirectory '急救布局测试.txt')
if ($LASTEXITCODE -ne 0) { throw '急救布局测试失败' }

$process = Start-Process -FilePath $candidate -ArgumentList @("--self-test", $selfTestReport, $package) -Wait -PassThru
if ($process.ExitCode -ne 0) {
    throw ("D31刷机工具离线自检失败：" + [Environment]::NewLine + (Get-Content -Raw $selfTestReport))
}
$verify = Start-Process -FilePath $candidate -ArgumentList @("--verify-package", $package, $packageReport) -Wait -PassThru
if ($verify.ExitCode -ne 0) {
    throw ("D31刷机包选择流程测试失败：" + [Environment]::NewLine + (Get-Content -Raw $packageReport))
}
$downloadParser = Start-Process -FilePath $candidate -ArgumentList @("--test-download-progress", $downloadParserReport) -Wait -PassThru
if ($downloadParser.ExitCode -ne 0) {
    throw ("高速下载进度解析测试失败：" + [Environment]::NewLine + (Get-Content -Raw $downloadParserReport))
}

# Windows PowerShell 5.1按系统代码页读取无BOM脚本，因此在中文路径中验证EXE释放出的最终脚本。
$runtimeCache = Join-Path $env:LOCALAPPDATA "Elfradio\D31FlashTool\$version"
$compatibilityDirectory = Join-Path $objectDirectory "中文路径兼容测试"
New-Item -ItemType Directory -Force -Path $compatibilityDirectory | Out-Null
$compatibilityLines = New-Object System.Collections.Generic.List[string]
foreach ($scriptName in @("flash_d31_recovery.ps1", "create_d31_rescue.ps1")) {
    $sourceScript = Join-Path $runtimeCache $scriptName
    $testScript = Join-Path $compatibilityDirectory $scriptName
    Copy-Item -LiteralPath $sourceScript -Destination $testScript -Force
    $bytes = [System.IO.File]::ReadAllBytes($testScript)
    if ($bytes.Length -lt 3 -or $bytes[0] -ne 0xEF -or $bytes[1] -ne 0xBB -or $bytes[2] -ne 0xBF) {
        throw "PowerShell脚本缺少UTF-8 BOM：$scriptName"
    }
    $escaped = $testScript.Replace("'", "''")
    $parseCommand = @"
`$tokens = `$null
`$errors = `$null
`$ast = [System.Management.Automation.Language.Parser]::ParseFile('$escaped', [ref]`$tokens, [ref]`$errors)
if (`$errors.Count -ne 0) { `$errors | ForEach-Object { Write-Error `$_.Message }; exit 1 }
`$definition = `$ast.Find({ param(`$node) `$node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and `$node.Name -eq 'Convert-AndroidSizeToBytes' }, `$true)
if (`$null -eq `$definition) { Write-Error '缺少Android空间单位解析函数'; exit 1 }
Invoke-Expression `$definition.Extent.Text
`$cases = @(
    @('1', 1L),
    @('1K', 1024L),
    @('1.5M', 1572864L),
    @('9.6G', 10307921510L),
    @('1T', 1099511627776L)
)
foreach (`$case in `$cases) {
    `$actual = Convert-AndroidSizeToBytes `$case[0]
    if (`$actual -ne `$case[1]) { Write-Error "空间单位解析错误：`$(`$case[0]) -> `$actual"; exit 1 }
}
try { Convert-AndroidSizeToBytes '9.6GB' | Out-Null; Write-Error '错误单位未被拒绝'; exit 1 } catch { }
Write-Output 'POWERSHELL 5.1 PARSE AND SIZE TEST PASS'
"@
    $parseOutput = & "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -Command $parseCommand 2>&1
    if ($LASTEXITCODE -ne 0 -or ($parseOutput -join "`n") -notmatch "SIZE TEST PASS") {
        throw "Windows PowerShell 5.1中文路径解析失败：$scriptName`n$($parseOutput -join [Environment]::NewLine)"
    }
    $compatibilityLines.Add("通过：$scriptName；EXE释放内容SHA-256匹配；UTF-8 BOM；中文路径；PowerShell 5.1解析及空间单位测试通过。")
}
[System.IO.File]::WriteAllLines($compatibilityReport, $compatibilityLines, [System.Text.UTF8Encoding]::new($true))

& (Join-Path $projectRoot 'tests\TestBackend.ps1') -RuntimeRoot $runtimeCache -Package $package -OutputDirectory (Join-Path $reportDirectory ('实际后端-' + (Get-Date -Format yyyyMMdd-HHmmss)))
if ($LASTEXITCODE -ne 0) { throw '实际后端流程测试失败' }
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -File (Join-Path $projectRoot 'tests\TestPostBoot.ps1') -Backend (Join-Path $runtimeCache 'flash_d31_recovery.ps1')
if ($LASTEXITCODE -ne 0) { throw '启动后探针分支测试失败' }

New-Item -ItemType Directory -Force -Path $outputDirectory, $releaseDirectory | Out-Null
Copy-Item -LiteralPath $candidate -Destination $output
Copy-Item -LiteralPath $candidate -Destination $releaseOutput

$distFiles = @(Get-ChildItem -LiteralPath $outputDirectory -File)
if ($distFiles.Count -ne 1 -or $distFiles[0].Name -ne "D31-Flash-Tool-v$version.exe") {
    throw "单文件发布门失败：dist目录并非只含一个EXE"
}

[PSCustomObject]@{
    工具 = $output
    工具字节数 = (Get-Item -LiteralPath $output).Length
    工具SHA256 = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash
    发布目录文件数 = $distFiles.Count
    内置运行文件数 = $runtimeSources.Count
    内置刷机包字节数 = $packageItem.Length
    内置刷机包SHA256 = $packageHash
    急救恢复入口SHA256 = $rescueRestoreHash
    急救测试入口SHA256 = $rescueTestHash
    aria2版本 = "1.37.0 win-32bit"
    aria2SHA256 = $aria2Hash
    自检 = (Get-Content -Tail 1 -LiteralPath $selfTestReport)
    选择流程 = (Get-Content -Tail 1 -LiteralPath $packageReport)
    高速下载进度解析 = (Get-Content -Tail 1 -LiteralPath $downloadParserReport)
    PowerShell5兼容 = (Get-Content -Tail 1 -LiteralPath $compatibilityReport)
}
