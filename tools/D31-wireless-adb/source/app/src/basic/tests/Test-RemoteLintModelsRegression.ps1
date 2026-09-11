param([Parameter(Mandatory=$true)][string]$BuildRoot)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$app = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$checker = Join-Path $PSScriptRoot 'Test-RemoteLintModels.ps1'
$fullSources = @(Get-ChildItem -LiteralPath (Join-Path $app 'src/main/java') -Filter '*.java' -File -Recurse).Count
$required = @(
    '实际lint模型核对通过：basicRelease/main，16个源码文件，生成源码检查已开启。',
    "实际lint模型核对通过：fullRelease/main，${fullSources}个源码文件，生成源码检查已开启。"
)
# 读取同一批真实模型；同时覆盖普通Windows路径和斜杠/尾分隔符输入，不改原件。
$paths = @([IO.Path]::GetFullPath($BuildRoot), ([IO.Path]::GetFullPath($BuildRoot).Replace('\', '/').TrimEnd('/') + '/'))
foreach ($path in $paths) {
    $output = @(& $checker -BuildRoot $path)
    foreach ($line in $required) {
        if ($output -cnotcontains $line) { throw "实际模型回归缺少预期结果：$line" }
    }
    if (@($output | Where-Object { $_ -like '实际lint模型核对通过：*' }).Count -ne 4) {
        throw '必须同时验证基础及完整的主代码和测试模型'
    }
    $output | Where-Object { $_ -like '实际lint模型核对通过：*' }
}
'真实模型路径列表回归通过；两种路径写法均核对四份模型，未运行Gradle或APK验包。'
