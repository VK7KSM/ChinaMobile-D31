param([string]$GeneratedRoot)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$app = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$gradle = Get-Content -LiteralPath (Join-Path $app 'build.gradle') -Raw
$block = [regex]::Match($gradle, '(?s)def localSources = \[(.*?)\]')
if (-not $block.Success) { throw '共享源码必须显式白名单' }
$shared = @([regex]::Matches($block.Groups[1].Value, "'([A-Za-z][A-Za-z0-9]+)'") | ForEach-Object { $_.Groups[1].Value })
$mainRoot = Join-Path $app 'src/main/java/net/elfradio/d31bootstrap'
$all = @(Get-ChildItem -LiteralPath $mainRoot -Filter '*.java')
$basic = @(Get-ChildItem -LiteralPath (Join-Path $app 'src/basic/java') -Filter '*.java' -Recurse)
$selected = @($all | Where-Object { $shared -ccontains $_.BaseName }) + $basic
$names = @($selected | ForEach-Object BaseName)
if ($selected.Count -ne $shared.Count + $basic.Count) { throw '共享源码白名单存在缺项' }
if (@($names | Group-Object | Where-Object Count -gt 1).Count) { throw '基础版出现重复类' }
$forbidden = @($all | Where-Object { $names -cnotcontains $_.BaseName })
foreach ($source in $selected) {
    $text = Get-Content -LiteralPath $source.FullName -Raw
    foreach ($excluded in $forbidden) {
        if ($text -cmatch ('\b' + [regex]::Escape($excluded.BaseName) + '\b')) {
            throw "基础调用闭包引用未纳入类：$($source.Name) -> $($excluded.Name)"
        }
    }
    if ($text -match 'org\.eclipse\.paho|org\.java_websocket|net\.elfradio\.d31system') {
        throw "基础源码引用禁止依赖：$($source.Name)"
    }
}
foreach ($required in @("tasks.register('syncRemoteVariantSources', Sync)",
        "layout.buildDirectory.dir('generated/remote-variants')", 'into remoteSourceRoot',
        "include localSources", "exclude localSources", "include localTests", "exclude localTests",
        "into 'main/java'", "into 'full/java'", "into 'test/java'", "into 'testFull/java'",
        "java.setSrcDirs([remoteSourceRoot.get().dir('main/java').asFile])",
        "java.setSrcDirs(['src/full/java', remoteSourceRoot.get().dir('full/java').asFile])",
        "java.setSrcDirs([remoteSourceRoot.get().dir('test/java').asFile])",
        "java.setSrcDirs(['src/testFull/java', remoteSourceRoot.get().dir('testFull/java').asFile])",
        "name == 'preBuild'", "name.startsWith('lint')", "name.contains('Lint')",
        'dependsOn(syncRemoteVariantSources)', "applicationIdSuffix '.preview'",
        'sourceSets.configureEach', 'kotlin.setSrcDirs([])', 'checkGeneratedSources true',
        "manifest.srcFile 'src/basic/AndroidManifest.xml'", "manifest.srcFile 'src/main/AndroidManifest.xml'",
        "assets.srcDirs = []", "resources.srcDirs = []", "assets.srcDir 'src/main/assets'",
        "resources.srcDir 'src/main/resources'", 'versionCode remoteFullCode - 1',
        'versionCode remoteFullCode', "fullImplementation 'org.eclipse.paho:",
        "fullImplementation 'org.java-websocket:")) {
    if (-not $gradle.Contains($required)) { throw "构建隔离合同缺项：$required" }
}
if (@(Get-ChildItem -LiteralPath (Join-Path $app 'src') -Recurse -File |
        Where-Object { $_.Extension -in @('.kt', '.kts') }).Count) {
    throw '发现Kotlin源码，必须重新审查双制品源码合同'
}
$sourceSets = [regex]::Match($gradle, '(?s)sourceSets\s*\{(.*?)\n\s*signingConfigs\s*\{').Groups[1].Value
if (-not $sourceSets -or $sourceSets -match 'src/main/java|src/test/java|java\.setIncludes|java\.setExcludes') {
    throw 'sourceSet不能再暴露原始整包Java目录或仅靠过滤器隔离lint'
}
if ($gradle -match '(?m)^\s*implementation\s+.*(paho|WebSocket|systemSupport)') { throw '完整依赖泄漏到公共配置' }
[xml]$manifest = Get-Content -LiteralPath (Join-Path $app 'src/basic/AndroidManifest.xml') -Raw
$ns = [Xml.XmlNamespaceManager]::new($manifest.NameTable)
$ns.AddNamespace('a', 'http://schemas.android.com/apk/res/android')
foreach ($component in $manifest.SelectNodes('/manifest/application/activity|/manifest/application/service|/manifest/application/receiver')) {
    $name = $component.GetAttribute('name', 'http://schemas.android.com/apk/res/android').TrimStart('.')
    if ($names -cnotcontains $name) { throw "基础清单引用未编译组件：$name" }
}
if ($manifest.SelectNodes('//action[contains(@a:name,"MEDIA_") or contains(@a:name,"CONNECTIVITY")]', $ns).Count) {
    throw '基础版不能重新接管存储或SIP网络广播'
}
if ($manifest.SelectNodes('/manifest/uses-permission[@a:name="android.permission.DISABLE_KEYGUARD"]', $ns).Count -or
        $gradle -match 'disable\s+[''"]MissingPermission') {
    throw '不能为已排除的业务增加基础权限或压制MissingPermission'
}
$assetFiles = @(Get-ChildItem -LiteralPath (Join-Path $app 'src/basic/assets') -File -Recurse)
if ($assetFiles.Count -ne 1 -or $assetFiles[0].Name -cne 'remote-basic.marker') { throw '基础资源白名单不匹配' }
foreach ($variant in @('basic', 'full')) {
    $markerPath = $assetFiles[0].FullName
    if ($variant -eq 'full') { $markerPath = Join-Path $app 'src/main/assets/remote-full.marker' }
    $expectedBytes = [Text.Encoding]::UTF8.GetBytes("d31-$variant-v1`n")
    $actualBytes = [IO.File]::ReadAllBytes($markerPath)
    if ([BitConverter]::ToString($actualBytes) -cne [BitConverter]::ToString($expectedBytes)) {
        throw "制品标记原始字节不匹配：$variant"
    }
}
$script = Join-Path $app '../tools/Build-RemoteVariants.ps1'
$tokens = $null; $errors = $null
[void][Management.Automation.Language.Parser]::ParseFile([IO.Path]::GetFullPath($script), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw '双制品脚本语法错误' }
$lintScript = Join-Path $PSScriptRoot 'Test-RemoteLintModels.ps1'
[void][Management.Automation.Language.Parser]::ParseFile($lintScript, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw '实际lint模型检查脚本语法错误' }
if ($GeneratedRoot) {
    $testBlock = [regex]::Match($gradle, '(?s)def localTests = \[(.*?)\]')
    if (-not $testBlock.Success) { throw '共享测试必须显式白名单' }
    $sharedTests = @([regex]::Matches($testBlock.Groups[1].Value, "'([A-Za-z][A-Za-z0-9]+)'") | ForEach-Object { $_.Groups[1].Value })
    $expected = @{}
    foreach ($kind in @('main', 'test')) {
        $sourceRoot = Join-Path $app "src/$kind/java"
        $allow = if ($kind -eq 'main') { $shared } else { $sharedTests }
        foreach ($source in Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -File -Recurse) {
            $group = if ($allow -ccontains $source.BaseName) { $kind }
                elseif ($kind -eq 'main') { 'full' } else { 'testFull' }
            $relative = $source.FullName.Substring($sourceRoot.Length).TrimStart('\', '/').Replace('\', '/')
            $expected["$group/java/$relative"] = $source.FullName
        }
    }
    $generated = [IO.Path]::GetFullPath($GeneratedRoot)
    $actual = @(Get-ChildItem -LiteralPath $generated -File -Recurse)
    if ($actual.Count -ne $expected.Count) { throw '生成源码数量与白名单及补集不一致' }
    foreach ($copy in $actual) {
        $relative = $copy.FullName.Substring($generated.Length).TrimStart('\', '/').Replace('\', '/')
        if (-not $expected.ContainsKey($relative)) { throw "生成目录混入未登记文件：$relative" }
        if ((Get-FileHash -LiteralPath $copy.FullName).Hash -cne (Get-FileHash -LiteralPath $expected[$relative]).Hash) {
            throw "生成源码与当前原件不一致：$relative"
        }
    }
    Write-Output "生成目录核对通过：$($actual.Count)个源码/测试副本与原件逐项哈希一致。"
}
Write-Output "静态核查通过：基础$($selected.Count)个类；完整保留全部$($all.Count)个main类；预览身份、清单与依赖隔离合同通过。未运行Gradle或连接设备。"
