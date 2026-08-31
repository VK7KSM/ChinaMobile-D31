# 中国移动 D31 / SVP3390 研究与恢复工具

[English documentation](#english-documentation)

本项目整理中国移动定制D31座机（产品型号`SVP3390`）的设备资料、无账号完整Recovery刷机候选包和Windows图形刷机工具。项目目标是在保留原厂专业电话功能的前提下，为同一硬件和固件修订提供可审计、可校验的系统恢复与批量部署路径。

## 当前状态

- 固件版本：`v1.0.2-candidate.1`
- Windows工具：`v1.2.1`
- 发布性质：预发布候选版
- 已完成：源文件哈希、ZIP结构与CRC、Recovery整文件签名、载荷、安装器、`boot.img`和`system.img.gz`离线回验；设备侧安装器只读`--self-test`；Windows工具离线构建和正常/错误刷机包校验。
- 尚未完成：项目目前只有一台D31母机，未在第二台同构建设备上执行真实Recovery刷写。因此不能把本候选版视为已经完成量产验收的固件。

不要在唯一设备、构建不一致的设备或没有独立备份与救援路径的设备上试刷。

## 下载

所有公开候选制品位于[GitHub Release v1.0.2-candidate.1](https://github.com/VK7KSM/ChinaMobile-D31/releases/tag/v1.0.2-candidate.1)。

普通用户应下载：

- [`D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip`](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.2-candidate.1/D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip)：推荐的完整Windows工具包，包含EXE、ADB、后端脚本、说明和首次引导APK，不包含1.26GB固件。
- [`D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip`](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.2-candidate.1/D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip)：独立签名Recovery刷机包。也可在工具中点击“从GitHub下载”。

Cloudflare R2镜像（浏览器直接下载）：

- [`D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip`](https://cdn.elfradio.net/d31/D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip)：完整Windows工具包R2镜像。
- [`D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip`](https://cdn.elfradio.net/d31/D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip)：完整Recovery刷机包R2镜像，支持断点续传。

图形工具中的“从GitHub下载”按钮仍使用GitHub Release；需要使用R2镜像时，请通过上面的链接手动下载后，在工具中点击“选择刷机包”。无论使用哪个来源，均须核对下方SHA-256。

仅更新现有完整工具包时可下载：

- [`D31-Flash-Tool-v1.2.1.exe`](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.2-candidate.1/D31-Flash-Tool-v1.2.1.exe)：单独EXE，必须放回完整工具包根目录，不能脱离脚本和`tools`目录独立工作。

旧资产`D31刷机工具.exe`、v1.1和v1.2工具已被v1.2.1取代，不应继续使用。刷机工具C#源码目前暂未公开；仓库提供文档、校验值和厂商资料。

## SHA-256

```text
E580BF615E57DBCC565FB140667315760815211353ED7303D073F4A92FA3E585  D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip
C93B17FDD1F41E8CEE481050159A4219A37A99870A5F631DC22B0F57E426299C  D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip
BE568CC13B55F4EAF1EEAE12BA80CC2AAC9AC32CE0C2C834B3BCDA15F78AA5D6  D31-Flash-Tool-v1.2.1.exe
```

完整文件长度和说明书哈希见[`SHA256SUMS.txt`](SHA256SUMS.txt)。

## 精确适用范围

工具只接受以下Android构建指纹：

```text
alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys
```

工具还会核对目标机为root ADB、目标地址属于有线网卡`eth0`，并由后端检查`system`、`boot`和`userdata`分区尺寸。外观相同但构建指纹或分区尺寸不同的设备必须停止，不得绕过检查。

## 全新原厂机首次启用ADB

D31的两个USB-A口目前只证明为主机口，本流程不依赖USB数据线。已经验证的入口为“拨号暗码、原生设置/桌面、蓝牙传输APK、有线TCP ADB”。

1. 在原厂拨号键盘输入`*#233#*`并按绿色拨出键，直接打开Android系统设置。
2. 如果没有“开发者选项”，进入“关于设备”，连续点击“版本号”7次，然后打开“USB调试”。这里打开的是Android调试总开关；实际ADB数据仍走网络。
3. 在“安全”中打开“未知来源”，再打开蓝牙并与Windows电脑配对。
4. 在原厂拨号键盘输入`*#223#*`并按绿色拨出键，进入Android原生`Launcher3`桌面。
5. Windows运行`fsquirt`，把完整工具包中`首次引导工具/D31-wireless-adb-v1.0.apk`通过蓝牙发送给D31。
6. 在D31原生桌面接受文件，并通过蓝牙传输完成通知调用Android原生包安装器。不要使用原厂阉割文件管理器打开APK。
7. 安装完成后立即点击“打开”，再点击“开启无线 ADB（端口 5555）”。程序名称虽然是“无线ADB”，同一个`5555`端口也可以通过有线网卡访问。
8. 从路由器确认D31有线IPv4。后续刷机工具只接受该`eth0`地址，拒绝Wi-Fi地址。

如果另一固件修订无法通过上述暗码或最小ADB程序启动5555，可以用工具包中的`D31-setup-probe.apk`只读检查设置入口；不要直接绕过机型检查刷写。

## Windows图形工具标准流程

1. 解压`D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip`，保持目录结构不变。
2. 双击`D31-Flash-Tool-v1.2.1.exe`。
3. 点击“选择刷机包”选择已下载的官方ZIP，或点击“从GitHub下载”。工具不会在启动时自动搜索固件或自动联网。
4. 等待1.26GB文件的固定长度和内置SHA-256全部通过。校验失败时设备检测保持锁定。
5. 填写D31有线IPv4并点击“检测D31”。工具会连接`IP:5555`并拒绝Wi-Fi地址、错误构建和非root ADB。
6. 点击“只读检查”。该步骤检查包、设备、分区、Recovery入口、空间和依赖，不写分区、不重启。
7. 只有只读检查完整通过后，清空数据确认框和“开始刷机”才会启用。
8. 首次真实刷写只能在同构建备用D31上进行。勾选确认框，点击“开始刷机”，阅读二次确认后再继续。
9. 工具依次执行本机校验、设备检查、分区检查、目标机独立备份、上传ZIP、设备端校验、触发Recovery和首次启动验证。
10. Recovery触发后禁止断电、关闭窗口或让电脑休眠。首次启动会重建`userdata`并进行ART优化，可能明显变慢。

## 数据和设备身份边界

刷机会覆盖`system`和`boot`并清空整个`userdata`。SIP、Wi-Fi、通信录、邮箱、Telegram、Zello和其他账号配置都不会从母机复制，也不会被刷机包预设。

工具会在触发Recovery前备份目标机自己的`nvram`、`nvdata`、`protect1`、`protect2`、`proinfo`、`recovery`、`secro`、`seccfg`和`frp`，并拉回电脑逐项校验。此类身份、校准和密钥材料只能恢复到原机，绝不能上传到公共存储或复制到另一台D31。刷机包本身不写这些分区。

## 文档

- [D31刷机工具使用说明](docs/D31刷机工具使用说明.md)
- [D31完整刷机包技术说明](docs/D31完整刷机包技术说明.md)
- [SVP3390产品资料](manuals/SVP3390_产品资料.pdf)
- [SVP3390用户指南](manuals/SVP3390_用户指南.pdf)
- [说明书索引与版权说明](manuals/README.md)

## 许可证与第三方材料

仓库中的原创项目文档适用根目录`LICENSE`。厂商固件、APK、产品资料和用户指南的权利归各自权利人所有；它们不因存放于本仓库而自动适用MIT许可证。使用者应自行确认所在地区和设备的授权、保修及合规要求。

---

## English Documentation

This repository documents the China Mobile D31 desk phone, product model `SVP3390`, and distributes an account-free Recovery image candidate plus a Windows flashing utility. The goal is to retain the vendor phone experience while providing an auditable and integrity-checked recovery and deployment path for the exact supported hardware and firmware revision.

### Project Status

- Firmware: `v1.0.2-candidate.1`
- Windows tool: `v1.2.1`
- Release type: pre-release candidate
- Completed validation: source hashes, ZIP structure and CRC, whole-file Recovery signature, payload and installer inspection, `boot.img` and `system.img.gz` round-trip checks, the device installer's read-only `--self-test`, Windows build checks, and valid/invalid package verification.
- Not yet completed: the project currently has only one D31 reference unit. No destructive Recovery flash has been performed on a second matching unit. This candidate is not production-qualified firmware.

Do not flash a sole device, a device with a different build, or a device without an independent backup and recovery path.

### Downloads

All public candidate artifacts are on [GitHub Release v1.0.2-candidate.1](https://github.com/VK7KSM/ChinaMobile-D31/releases/tag/v1.0.2-candidate.1).

Normal users should download:

- [`D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip`](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.2-candidate.1/D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip): the recommended complete Windows bundle. It includes the GUI, ADB, backend scripts, documentation and bootstrap APKs, but not the 1.26 GB firmware.
- [`D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip`](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.2-candidate.1/D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip): the separate signed Recovery package. The GUI can also download it from GitHub.

Cloudflare R2镜像（浏览器直接下载）：

- [`D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip`](https://cdn.elfradio.net/d31/D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip)：完整Windows工具包R2镜像。
- [`D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip`](https://cdn.elfradio.net/d31/D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip)：完整Recovery刷机包R2镜像，支持断点续传。

图形工具中的“从GitHub下载”按钮仍使用GitHub Release；需要使用R2镜像时，请通过上面的链接手动下载后，在工具中点击“选择刷机包”。无论使用哪个来源，均须核对下方SHA-256。

For updating an existing complete tool directory only:

- [`D31-Flash-Tool-v1.2.1.exe`](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.2-candidate.1/D31-Flash-Tool-v1.2.1.exe): standalone replacement executable. It must remain beside the scripts and `tools` directory from the complete bundle.

The old `D31刷机工具.exe`, v1.1 and v1.2 tools are superseded by v1.2.1. The C# source of the flashing utility is not currently published.

### Integrity Values

```text
E580BF615E57DBCC565FB140667315760815211353ED7303D073F4A92FA3E585  D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip
C93B17FDD1F41E8CEE481050159A4219A37A99870A5F631DC22B0F57E426299C  D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip
BE568CC13B55F4EAF1EEAE12BA80CC2AAC9AC32CE0C2C834B3BCDA15F78AA5D6  D31-Flash-Tool-v1.2.1.exe
```

See [`SHA256SUMS.txt`](SHA256SUMS.txt) for byte lengths and manual hashes.

### Exact Supported Build

The tool accepts only this Android build fingerprint:

```text
alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys
```

It also requires root ADB, verifies that the selected address belongs to wired `eth0`, and checks the `system`, `boot` and `userdata` partition sizes. Stop if an otherwise similar unit has a different fingerprint or partition layout. Do not bypass these checks.

### Bootstrap a Factory-Locked Unit

The two USB-A ports are currently known only as host ports. This procedure does not require a USB data connection. The verified path is dial code, stock Android Settings/Launcher, Bluetooth APK transfer, then wired TCP ADB.

1. Enter `*#233#*` in the vendor dialer and press the green call key to open Android Settings.
2. If Developer options is hidden, open About device and tap Build number seven times. Enable USB debugging. This enables Android debugging globally; the actual ADB transport remains TCP.
3. Enable Unknown sources under Security, enable Bluetooth, and pair the D31 with the Windows PC.
4. Enter `*#223#*` in the vendor dialer and press the green call key to open the stock `Launcher3` desktop.
5. Run `fsquirt` on Windows and send `首次引导工具/D31-wireless-adb-v1.0.apk` from the complete tool bundle.
6. Accept the transfer while the D31 is on the stock desktop. Open the completed Bluetooth transfer with Android's native package installer. Do not use the restricted vendor file manager.
7. Tap Open after installation, then tap the button that starts TCP ADB on port `5555`. Despite the app's Chinese name referring to wireless ADB, the same port is reachable over Ethernet.
8. Find the D31 wired IPv4 address in the router. The flashing tool accepts only this `eth0` address and rejects Wi-Fi addresses.

`D31-setup-probe.apk` is a fallback for inspecting Settings when the codes or the minimal ADB starter differ on another firmware revision. It is not permission to bypass build validation.

### Standard Windows GUI Workflow

1. Extract `D31_SVP3390_Windows_Flash_Tool_v1.2.1.zip` without changing its directory structure.
2. Run `D31-Flash-Tool-v1.2.1.exe`.
3. Choose Select package for an existing official ZIP, or Download from GitHub. The tool does not scan the PC or access the network automatically at startup.
4. Wait for the complete 1.26 GB fixed-length and embedded SHA-256 verification. Device detection remains disabled if verification fails.
5. Enter the wired D31 IPv4 address and click Detect D31. The tool connects to `IP:5555` and rejects Wi-Fi addresses, an unsupported build, or non-root ADB.
6. Run the read-only preflight. It checks the package, device, partitions, Recovery entry, free space and dependencies without writing partitions or rebooting.
7. The data-erasure acknowledgement and Flash button are enabled only after the full preflight passes.
8. Perform the first real flash only on a spare unit with the exact same build. Review the second warning before proceeding.
9. The tool performs local verification, device and partition checks, per-device identity backups, package transfer, on-device verification, Recovery activation and first-boot validation.
10. After Recovery is triggered, do not remove power, close the tool or let the PC sleep. The first boot may be slow while `userdata` and ART caches are rebuilt.

### Data and Device Identity Boundary

The image replaces `system` and `boot` and erases all of `userdata`. SIP, Wi-Fi, contacts, email, Telegram, Zello and other account settings are neither copied from the reference unit nor preconfigured in the package.

Before Recovery, the tool backs up the target unit's own `nvram`, `nvdata`, `protect1`, `protect2`, `proinfo`, `recovery`, `secro`, `seccfg` and `frp`, then verifies each copy on the PC. These identity, calibration and key materials may only be restored to the original unit. Never upload them to public storage or copy them to another D31. The firmware package does not write those partitions.

### Documentation and License

See the Chinese [GUI guide](docs/D31刷机工具使用说明.md), [technical package notes](docs/D31完整刷机包技术说明.md), [product brief](manuals/SVP3390_产品资料.pdf), and [user guide](manuals/SVP3390_用户指南.pdf).

Original project documentation is covered by the root `LICENSE`. Vendor firmware, APKs and manuals remain the property of their respective rights holders and are not automatically relicensed under MIT merely by being archived here. Users are responsible for authorization, warranty and regulatory compliance.
