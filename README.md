# 中国移动 D31 / SVP3390 研究与恢复工具

[English documentation](#english-documentation)

本项目整理中国移动定制D31座机（产品型号`SVP3390`）的设备资料、无账号完整Recovery刷机候选包和Windows图形刷机工具。项目目标是在保留原厂专业电话功能的前提下，为同一硬件和固件修订提供可审计、可校验的系统恢复与批量部署路径。

## 设备信息与官方资料

- 产品全称：星网锐捷统一通信3390视频话机，中国移动定制型号`D31`
- 厂商：福建星网锐捷通讯股份有限公司
- 厂商官网：[https://www.smart-china.com/](https://www.smart-china.com/)
- 官方产品页：[SVP3390统一通信视频话机](https://www.smart-china.com/productinfo/1453527.html)
- 硬件型号：`SVP3390`；Android内部型号：`hct6737t_66_m0`
- Android：`6.0`，API 23
- 安全补丁：`2017-07-05`
- 平台：MediaTek `MT6737T`
- 内存：约2GB；真机`MemTotal`为1,959,528KB
- 内部存储：约16GB；本构建`userdata`分区为13,517,717,504字节
- 扩展存储：TF卡，厂家资料标称最大256GB
- 屏幕：8英寸电容多点触摸屏，`1280 x 800`，Android密度`213 dpi`
- 网络：两个10/100/1000Mbps自适应以太网口、PoE、2.4GHz/5GHz Wi-Fi、蓝牙4.0+EDR
- 接口：两个USB 2.0 Host、HDMI、3.5mm耳机口和RJ9手柄接口
- 电话能力：最多4个SIP账号；原厂客户端支持H.264、VP8及最高`720p@30fps`视频
- 构建显示标识：`full_hct6737t_66_m0-userdebug 6.0 MRA58K 1583081804 test-keys`
- 构建指纹：`alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys`
- Android产品标识：`ro.product.name=full_hct6737t_66_m0`，`ro.product.device=hct6735_66_m0`

官方资料：

- [《SVP3390产品资料》](manuals/SVP3390_产品资料.pdf)：4页产品彩页，包含接口、编解码器、网络、屏幕和物理参数。
- [《SVP3390用户指南》](manuals/SVP3390_用户指南.pdf)：96页完整用户手册，包含电话、通信录、视频、按键、Web配置和维护操作。
- [说明书来源、哈希与版权说明](manuals/README.md)

厂家彩页对外置摄像头像素分别出现“200万”和“500万”两种写法，存在内部矛盾。本项目不把其中任一数值作为刷机准入条件；已一致确认的能力是外置可旋转摄像头、H.264/VP8和最高`720p@30fps`视频。

## 固件内容

当前完整Recovery刷机候选包包含完整`system`和`boot`，刷入时清空`userdata`，不会复制母机账号或应用配置。主要内容如下：

- 保留原厂Nexui专业电话桌面、SIP音视频客户端、拨号、来电、通话、通信录、黑名单、录音、图库、中文拼音输入法、计算器、Android设置和系统WebView；
- 保留原厂最多4个SIP账号、音频与H.264/VP8视频通话、手柄、免提、HDMI、双网口、Wi-Fi、蓝牙和USB Host能力；
- 预装Firefox 142.0、VLC 3.7.1、Zello 5.30.1、Telegram 12.10.1、Thunderbird 22.0和质感文件1.7.4；
- 预装`D31无线ADB` 1.10.6和`D31 Zello守护` 0.2.7，并带有开机绑定脚本；
- Zello保持后台在线，收到私聊或频道语音时唤醒屏幕并立即切到前台；无操作约5分钟后返回原厂桌面；
- 原厂桌面“视频会议”和“语音会议”失效入口分别替换为`FireFox`和`Telegram`，同时替换图标、文字与点击目标；
- 原厂桌面应用页整理为Firefox、VLC、Zello、Telegram、计算器、D31无线ADB、设置和质感文件；
- 修复原厂模拟时钟写死东八区的问题，使模拟时钟和数字时间都跟随Android系统时区；
- 加入`tca8418`设备专用键位和状态感知快捷键；普通状态启动目标应用，原厂SIP视频通话时保留分享与布局按键；
- 基础`system`镜像包含母机已通过运行态验收的IPv4/IPv6入站防火墙和相关开机脚本；首次备用机实刷后仍必须按验收清单复核规则、SIP和管理端口；
- 保留动态壁纸、屏保素材、MTK工程工具、日志工具和工厂测试工具，不因普通用户暂时不用而删除诊断能力。

实体功能键映射如下：

| 实体键 | 待机或普通应用状态 | 原厂SIP音视频通话状态 |
| --- | --- | --- |
| 信封 | 启动Thunderbird | 吞掉按键，避免邮件抢占通话 |
| 三人 | 启动Telegram | 吞掉按键，避免Telegram抢占通话 |
| 三节点 | 启动Firefox | 交回原厂SIP界面，保留屏幕分享 |
| 翻书 | 启动质感文件 | 吞掉按键，避免文件管理器抢占通话 |
| 布局切换 | 打开Android最近任务 | 交回原厂SIP界面，保留视频布局切换 |

Android 6在完全息屏时会把第一次非电源按键只用于唤醒，通常需要第二次按键才执行快捷动作。这是系统输入策略边界，不是键位映射丢失。

刷机包只预装APK和无账号系统功能文件，不携带Zello、Telegram、Thunderbird、SIP或Wi-Fi配置。新机首次启动后仍需逐台登录账号，并确认Zello守护所需的辅助功能和通知读取授权；这些授权与首次真实Recovery刷写后的完整功能都属于备用机验收项目。

固件已从`system`精简以下18项无用或已有替代的软件路径：

- 学习强国和WPS Office；
- 蓝牙MIDI、AOSP快速搜索框、旧Exchange邮件服务和百度定位预装服务；
- AOSP/MTK日历、日历数据库提供程序和日历导入器；
- 原厂旧浏览器、旧i-jetty服务、HTML Viewer和旧音乐播放器；
- TR-069、TR-069代理、EMU运营商消息、已废弃会议登录守护和OMACP配置程序。

上述精简项已在母机上经历可逆停用、重启和普通SIP音视频回归；刷机包中的删除对象以安装器实际18项清单为准。系统WebView、原厂一对一SIP电话、通信录、中文输入法、录音和诊断工具均未随这些组件删除。

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
