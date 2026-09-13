param([Parameter(Mandatory=$true)][string]$BuildRoot)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$app = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$build = [IO.Path]::GetFullPath($BuildRoot)
$generated = Join-Path $build 'generated/remote-variants'
& (Join-Path $PSScriptRoot 'Test-RemoteVariants.ps1') -GeneratedRoot $generated

function Resolve-ModelPath([string]$Path, [string]$ModuleRoot) {
    if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
    return [IO.Path]::GetFullPath((Join-Path $ModuleRoot $Path))
}

foreach ($variant in @('basic', 'full')) {
    $name = "${variant}Release"
    $title = $variant.Substring(0, 1).ToUpperInvariant() + $variant.Substring(1)
    foreach ($kind in @('main', 'test')) {
        $folder = if ($kind -eq 'main') {
            Join-Path $build "intermediates/lint_report_lint_model/$name/generate${title}ReleaseLintReportModel"
        } else {
            Join-Path $build "intermediates/unit_test_lint_model/$name/generate${title}ReleaseUnitTestLintModel"
        }
        [xml]$module = Get-Content -LiteralPath (Join-Path $folder 'module.xml') -Raw
        [xml]$model = Get-Content -LiteralPath (Join-Path $folder "$name.xml") -Raw
        $root = Resolve-ModelPath $module.DocumentElement.GetAttribute('dir') $app
        if ($root -ine $app -or $model.DocumentElement.GetAttribute('name') -cne $name) {
            throw 'lint模型项目或variant不匹配'
        }
        $options = $module.SelectSingleNode('/lint-module/lintOptions')
        if (-not $options -or $options.GetAttribute('checkGeneratedSources') -cne 'true') {
            throw "lint模型未开启生成源码实际检查：$name/$kind"
        }
        $expected = @{}
        # PowerShell会把单元素管道解包为字符串；显式列表避免+=误变成路径拼接。
        $expectedRoots = [Collections.Generic.List[string]]::new()
        if ($kind -eq 'main') { $expectedRoots.Add((Join-Path $generated 'main/java')) }
        else { $expectedRoots.Add((Join-Path $generated 'test/java')) }
        if ($variant -eq 'full') {
            if ($kind -eq 'main') {
                $expectedRoots.Add((Join-Path $generated 'full/java'))
                $expectedRoots.Add((Join-Path $app 'src/full/java'))
            } else {
                $expectedRoots.Add((Join-Path $generated 'testFull/java'))
                $expectedRoots.Add((Join-Path $app 'src/testFull/java'))
            }
        } else {
            if ($kind -eq 'main') { $expectedRoots.Add((Join-Path $app 'src/basic/java')) }
            else {
                $expectedRoots.Add((Join-Path $app 'src/basic/tests/java'))
                $expectedRoots.Add((Join-Path $app 'src/testBasic/java'))
            }
        }
        foreach ($expectedRoot in $expectedRoots) {
            $directory = Resolve-ModelPath $expectedRoot $app
            if (Test-Path -LiteralPath $directory -PathType Container) {
                foreach ($file in Get-ChildItem -LiteralPath $directory -Filter '*.java' -Recurse -File) {
                    $expected[(Resolve-ModelPath $file.FullName $app)] = $true
                }
            }
        }
        $actual = @{}
        $nodes = $model.SelectNodes('/variant/sourceProviders/sourceProvider|/variant/testSourceProviders/sourceProvider')
        foreach ($provider in $nodes) {
            foreach ($directory in $provider.GetAttribute('javaDirectories').Split(';')) {
                if (-not $directory) { continue }
                $resolved = Resolve-ModelPath $directory $root
                foreach ($forbidden in @('src/main/java', 'src/test/java', 'src/main/kotlin', 'src/test/kotlin')) {
                    if ($resolved -ieq (Resolve-ModelPath $forbidden $app)) {
                        throw "lint仍通过默认源目录扫描原件：$name/$kind/$forbidden"
                    }
                }
                if (Test-Path -LiteralPath $resolved -PathType Container) {
                    foreach ($file in Get-ChildItem -LiteralPath $resolved -File -Recurse) {
                        if ($file.Extension -in @('.kt', '.kts')) { throw '模型中出现未审查Kotlin源码' }
                        if ($file.Extension -ne '.java') { continue }
                        $sourcePath = Resolve-ModelPath $file.FullName $root
                        if (-not $expected.ContainsKey($sourcePath)) { throw "lint混入范围外源码：$name/$kind/$($file.Name)" }
                        $actual[$sourcePath] = $true
                    }
                }
            }
        }
        if ($actual.Count -ne $expected.Count) { throw "lint漏读源码：$name/$kind，实际$($actual.Count)，应为$($expected.Count)" }
        Write-Output "实际lint模型核对通过：$name/$kind，$($actual.Count)个源码文件，生成源码检查已开启。"
    }
}
