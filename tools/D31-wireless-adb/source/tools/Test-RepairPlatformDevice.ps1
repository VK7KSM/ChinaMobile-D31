param(
 [Parameter(Mandatory=$true)][ValidatePattern('^[0-9.]+:[0-9]+$')][string]$Serial,
 [Parameter(Mandatory=$true)][string]$ApkPath,
 [Parameter(Mandatory=$true)][string]$CheckJar,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedApkSha256,
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_-]{1,60}$')][string]$Attempt,
 [Parameter(Mandatory=$true)][string]$CapturePath
)
$ErrorActionPreference='Stop'
$adb='C:/Dev/android-sdk/platform-tools/adb.exe'
$capture=[IO.Path]::GetFullPath($CapturePath)
if(Test-Path -LiteralPath $capture){throw '捕获目录必须全新'}
if((Get-FileHash -LiteralPath $ApkPath).Hash -ine $ExpectedApkSha256){throw '候选摘要不符'}
New-Item -ItemType Directory -Path $capture | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination "$capture/host.ps1"
$stage="/data/local/tmp/d31-repair-$Attempt"
$fixture="/data/local/d31-repair-fixtures/$Attempt"
function Read-Device([string]$Command){
 $raw=(& $adb -P 5042 -s $Serial shell ($Command+"`nresult=`$?; echo D31_DEVICE_EXIT_`$result") 2>&1 | Out-String).Replace("`r",'').Trim()
 if($LASTEXITCODE -ne 0 -or $raw -notmatch '(?s)^(?:(.*)\n)?D31_DEVICE_EXIT_0$'){throw "设备命令未完成：$raw"}
 return ([string]$Matches[1]).Trim()
}
$before=Read-Device 'date; getprop ro.build.fingerprint; getprop ro.product.model; cat /data/local/d31-remote/runtime/state/health.json; ps'
$before | Out-File "$capture/before-private.txt" -Encoding utf8 -NoClobber
if((Read-Device 'getprop ro.product.device') -ne 'hct6735_66_m0' -or (Read-Device 'getprop ro.product.model') -ne 'hct6737t_66_m0'){throw '不是已确认D31'}
[void](Read-Device "test ! -e '$fixture' && mkdir '$stage' && chmod 0755 '$stage'")
& $adb -P 5042 -s $Serial push $ApkPath "$stage/full.apk" *> "$capture/push-apk.log"
if($LASTEXITCODE -ne 0){throw '暂存APK失败'}
& $adb -P 5042 -s $Serial push $CheckJar "$stage/check.jar" *> "$capture/push-check.log"
if($LASTEXITCODE -ne 0){throw '暂存检查入口失败'}
$apkHash=(Read-Device "/system/bin/busybox sha256sum '$stage/full.apk'").Split(' ')[0]
$jarHash=(Read-Device "/system/bin/busybox sha256sum '$stage/check.jar'").Split(' ')[0]
if($apkHash -cne $ExpectedApkSha256 -or $jarHash -ine (Get-FileHash $CheckJar).Hash){throw '设备暂存摘要不符'}
$command="CLASSPATH='$stage/check.jar`:$stage/full.apk' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.repair.RepairPlatformFixtureMain '$fixture'"
[ordered]@{目标='D31开发板';设备序列号=$Serial;构建=$before;唯一变量='仅独立隔离文件事务';命令=$command;APK摘要=$apkHash;测试入口摘要=$jarHash;回退='未替换现有文件，失败保留隔离目录';时间=[DateTimeOffset]::Now.ToString('o')} | ConvertTo-Json | Out-File "$capture/preregister-private.json" -Encoding utf8 -NoClobber
try {
 & $adb -P 5042 -s $Serial shell ($command+"`nresult=`$?; echo D31_DEVICE_EXIT_`$result") *> "$capture/device-output-private.txt"
 if($LASTEXITCODE -ne 0){throw 'ADB宿主执行未完成'}
} finally {
 & $adb -P 5042 -s $Serial pull $fixture "$capture/device-files" *> "$capture/pull-fixture.log"
 Read-Device 'cat /data/local/d31-remote/runtime/state/health.json; ps; logcat -d -t 200' | Out-File "$capture/after-private.txt" -Encoding utf8 -NoClobber
 $capturedFiles=@(Get-ChildItem $capture -File -Recurse)
 $capturedFiles | ForEach-Object {
  [pscustomobject]@{路径=[IO.Path]::GetRelativePath($capture,$_.FullName);字节=$_.Length;SHA256=(Get-FileHash $_.FullName).Hash}
 } | ConvertTo-Json | Out-File "$capture/artifacts-private.json" -Encoding utf8 -NoClobber
}
$raw=(Get-Content "$capture/device-output-private.txt" -Raw).Replace("`r",'').Trim()
if($raw -notmatch '(?s)^(.*)\nD31_DEVICE_EXIT_0$'){throw '设备隔离测试失败，原始输出及捕获已保留'}
$result=$Matches[1] | ConvertFrom-Json
if($result.state -ne 'PASS'){throw '隔离测试未通过'}
"D31隔离文件平台$($result.cases.Count)项通过；未安装APK或更改系统支持。"
