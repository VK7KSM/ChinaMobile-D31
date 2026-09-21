# macOS 刷机工具源码

给 macOS 用的 D31 刷机命令行工具。只做两件事：把签名刷机包取到本机，然后交给刷机后端。

## 与 Windows 版的关系

**刷机逻辑完全共用**。两边跑的都是 [`tools/factory-package/source/flash_d31_recovery.ps1`](../factory-package/source/flash_d31_recovery.ps1)，没有第二份实现，不会各自漂移。macOS 版只是换了一个入口：Windows 是图形界面，macOS 是命令行。

为让同一份后端能在两个系统上跑，2026-09-22 对它做了三处可移植性修订，逻辑一行未改：

| 位置 | 原来 | 现在 |
| --- | --- | --- |
| adb 路径 | 写死 `tools\adb.exe` | 新增 `-AdbPath` 参数；不传时按平台取 `adb.exe` 或 `adb` |
| 上传暂存目录 | `Elfradio\D31FlashTool\upload` | 改用 `Join-Path` 逐级拼接 |
| 日志目录 | `logs\D31_recovery_flash_…` | 同上 |

平台判据是 `$IsWindows`。Windows PowerShell 5.1 里这个变量不存在，脚本把「未定义」按 Windows 处理，所以 Windows 图形版的行为一字未变。

## 组成

| 文件 | 作用 |
| --- | --- |
| `source/d31-flash.sh` | 引导层。确保 `pwsh` 与 `adb` 可用，然后把活交给下面那个脚本 |
| `source/d31-flash.ps1` | 主流程。读清单、下载并校验刷机包、调用刷机后端 |
| `source/build-mac.ps1` | 打包成可分发目录与 zip，打包时从 factory-package 取当前后端 |
| `source/使用说明.md` | 给使用者看的说明 |

入口必须是 shell 而不是 PowerShell：还没装 PowerShell 的时候没法跑 PowerShell。

## 运行时按需补齐

首次运行会补两个官方免安装包，之后跳过；系统里已有 `pwsh` 或 `adb` 就直接用现成的。

| 运行时 | 来源 | 体积 | 校验 |
| --- | --- | --- | --- |
| PowerShell 7 | 微软 GitHub 发布页 | 约 68 MB | 比对官方 `hashes.sha256` |
| adb | 谷歌 platform-tools | 约 16 MB | 官方 HTTPS 源，解压后校验可执行 |

两者解压到工具目录下的 `runtime/`，不需要 brew、不需要 sudo、不写任何系统目录。用 curl 下载不带隔离标记，Gatekeeper 不介入。整个工具目录删掉即可彻底清除。adb 官方包是通用二进制，在 Apple Silicon 上原生运行。

## 不做的事

图形界面、原厂 uptool 救砖通道、刷机前备份。备份省掉的风险有限，因为安装器本来就不写 `nvram`、`nvdata`、`protect1/2`、`proinfo` 这些身份与校准分区。需要这些功能请用 Windows 图形版。

## 一条硬前提

**D31 必须插网线。** 刷机会清空 `userdata`，Wi-Fi 密码随之消失；只有有线才能在重启后自动回到网上，让工具完成最后的校验。后端保留了「所连地址必须属于设备 eth0」这道检查。

Mac 自己用什么上网无所谓，Wi-Fi 即可，只要和 D31 在同一个局域网。Mac 不需要网口，也不需要转接头。
