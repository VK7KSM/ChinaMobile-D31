# China Mobile D31 / Star-net SVP3390 Firmware and Windows Flash Tool

[中文说明](README.md)

## What this project is

This is an ad-free international firmware package for the China Mobile D31. It comes with Telegram, Firefox, the Thunderbird email client, and Zello. The package also hardens the phone's Android 6 system against the malware, viruses, and remote attacks that can exploit an old Android release.

I developed this custom system to turn a retired high-end business phone into a communications terminal for the home. It gives children and older family members a simple way to communicate without handing them a smartphone. A desk phone is a communications device in its purest form, and the D31 is especially well suited to the job: it has a high-quality 8-inch display, native IP telephony and video calling, and becomes a proper enterprise-grade internal phone once a SIP line is configured. It is much simpler and more convenient than arranging every video call through WeChat. For a mobile companion, I also customized another piece of electronic waste, the [China Mobile D22 public-network radio](https://github.com/VK7KSM/ChinaMobile-D22), for children and older family members to carry when they go out.

I found this brand-new D31 on Xianyu for only RMB 220, and a lightly used D22 for just RMB 80. The whole family has had a great time with them.

![China Mobile D31 / Star-net SVP3390 hardware](images/d31-device.jpg)

## Hardware

- Product: China Mobile Cloud Video D31 / Star-net SVP3390 video phone
- Manufacturer: [Fujian Star-net Communication Co., Ltd.](https://www.smart-china.com/)
- Product page: [SVP3390 Unified Communications Video Phone](https://www.smart-china.com/productinfo/1453527.html)
- Hardware model: `SVP3390`
- Android platform name: `hct6737t_66_m0`
- Operating system: Android 6.0, API 23
- Security patch level: `2017-07-05`
- SoC: MediaTek MT6737T
- Memory: approximately 2 GB; the reference unit reports `MemTotal: 1,959,528 kB`
- Internal storage: approximately 16 GB; this build has a 13,517,717,504-byte `userdata` partition
- Expansion: microSD/TF card, up to 256 GB according to the manufacturer
- Display: 8-inch capacitive multi-touch panel, 1280 x 800 at 213 dpi
- Networking: two 10/100/1000 Ethernet ports, PoE, dual-band Wi-Fi, and Bluetooth 4.0+EDR
- Ports: two USB 2.0 host ports, HDMI, 3.5 mm audio, and an RJ9 handset connector
- Telephony: up to four SIP accounts, H.264 and VP8 video, up to 720p at 30 fps
- Build ID: `full_hct6737t_66_m0-userdebug 6.0 MRA58K 1583081804 test-keys`
- Build fingerprint: `alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys`
- Android product properties: `ro.product.name=full_hct6737t_66_m0`, `ro.product.device=hct6735_66_m0`

## Manuals

- [SVP3390 Product Brief](manuals/SVP3390_产品资料.pdf): a four-page overview of the hardware, interfaces, codecs, networking, and physical specifications.
- [SVP3390 User Guide](manuals/SVP3390_用户指南.pdf): the complete 96-page manual covering calls, contacts, video, physical keys, web configuration, and maintenance.
- [Source and checksum information](manuals/README.md)

## What is included

- The stock Nexui phone launcher, SIP audio/video client, dialer, incoming-call screen, contacts, blacklist, call recording, gallery, Chinese keyboard, calculator, Android Settings, and System WebView remain available.
- The original four-account SIP implementation, H.264/VP8 video, handset, speakerphone, HDMI, Ethernet, Wi-Fi, Bluetooth, and USB host support are preserved.
- 预装Firefox 142.0、VLC 3.7.1、Zello 5.30.1、Telegram 12.10.1、Thunderbird 22.0、短信0.5.2开发版和文件管理器1.7.4-d31.2，不含用户账号。短信保持原包名及签名，包含通知、未读、四线路SIP配置和WorkerFactory修复。
- [完整elfRemote 194、系统支持1.1.0、独立8765守护1.11.6与全部回填项](docs/D31-v1.4.5逐项复核.md)。基础193供首次引导和Windows维护；普通`network_write`仍关闭。
- Zello stays online in the background. Direct and channel voice messages wake the display and bring Zello forward; after roughly five minutes without interaction, the phone returns to the stock launcher.
- The defunct Video Conference and Voice Conference tiles now open Firefox and Telegram. The boot patch also stops conference processes started before the patched package is mounted, preventing those tiles from falling back to the original conference screens.
- 原厂桌面橙色入口打开双通道短信；应用页为Firefox、VLC、Zello、Telegram、计算器、elfRemote、设置和文件管理器。连续按11次`#`键打开应用页配置。新增通知红点，无SIP账号且SIM就绪时可选蜂窝线路，不抢占已有SIP线路。
- The stock analogue clock no longer assumes China Standard Time and now follows the Android time zone.
- The stock SIP client no longer forces TLS 1.0. It can negotiate TLS 1.2 with current SIP servers, and stale TLS sessions are cleared after a network change so SIP registration can recover. The fix has been verified with a successful TLS registration and a real incoming call.
- [启动交接、自动短信取号禁用及蜂窝VoLTE验收边界](docs/D31-v1.4.4逐项复核.md#功能与对应文件)
- 开发版保留防火墙功能但默认关闭。独立8765守护保留1.11.6；ADB端口按实际监听检测或显式`IP:port`连接，旧探针恢复5555仍兼容，不能保证原厂adbd始终在线。[详细说明](docs/D31-v1.4.4逐项复核.md)
- Live wallpapers, screen-saver assets, MediaTek engineering tools, logging utilities, and factory diagnostics are retained.

### System interface

| Nexui home screen | Application screen |
| --- | --- |
| ![D31 Nexui home screen](images/d31-home.png) | ![D31 application screen](images/d31-apps.png) |

### Removed software

- **The worst junk of the lot:** Xuexi Qiangguo and WPS Office.
- The obsolete stock browser, i-jetty service, HTML Viewer, and music player.
- Bluetooth MIDI, AOSP Quick Search Box, the old Exchange service, and the preinstalled Baidu location service.
- The AOSP/MediaTek calendar application, calendar provider, and calendar importer.
- TR-069, its proxy service, the EMU carrier messaging component, abandoned conference-login services, and OMACP.

## Physical key assignments

| Key | Standby or normal application | Stock SIP audio/video call |
| --- | --- | --- |
| Envelope | Open Thunderbird | Suppressed so email cannot take over the call |
| Three people | Open Telegram | Suppressed so Telegram cannot take over the call |
| Three nodes | Open Firefox | Passed back to the SIP interface for screen sharing |
| Address book | Open File Manager | Suppressed so the file manager cannot take over the call |
| Layout | Open Android Recents | Passed back to the SIP interface for video layout control |

## Downloads

Windows工具1.6.11配套1.4.6完整固件，覆盖boot、Recovery、system及logo并清空userdata，不再要求旧boot或Recovery摘要一致。身份和校准数据保留。[完整刷机说明](docs/D31-v1.4.6完整刷机说明.md)。

### GitHub

- [D31-Flash-Tool-v1.6.11.exe](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/tool-v1.6.11/D31-Flash-Tool-v1.6.11.exe)：16162304字节。
- [D31_SVP3390_Factory_Flash_v1.4.6_testkey.zip](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.4.6/D31_SVP3390_Factory_Flash_v1.4.6_testkey.zip)：1725719086字节。

### Cloudflare R2 mirror

- [D31-Flash-Tool-v1.6.11.exe](https://cdn.elfradio.net/d31/D31-Flash-Tool-v1.6.11.exe)
- [D31_SVP3390_Factory_Flash_v1.4.6_testkey.zip](https://cdn.elfradio.net/d31/D31_SVP3390_Factory_Flash_v1.4.6_testkey.zip)

源码：[Windows刷机工具](tools/Windows-flash/README.md)、[固件安装器与启动初始化](tools/factory-package/README.md)、[无线ADB与独立系统支持](tools/D31-wireless-adb/README.md)、[短信客户端](tools/D31-Messages/README.md)。

### SHA-256

```text
AF119B060139BAC3E4BDF4DA352BF63ECCBD6335C800569E67E96C378C4BC8CC  D31-Flash-Tool-v1.6.11.exe
1BF50A5D00CE1571340EA3C1ADEAECA0067D502E7AD8E4E7A1C4DB4C7B7CD92B  D31_SVP3390_Factory_Flash_v1.4.6_testkey.zip
```

## Preparing a factory D31

The two USB-A sockets are known to operate as host ports. They cannot currently be used as a USB device connection to a PC, so the flash utility communicates with ADB over Ethernet. Do not flash over Wi-Fi: disable Wi-Fi on the D31, connect Ethernet, and use a Windows 10 or newer PC.

1. Test the unmodified phone first. Confirm that it boots normally and that the display, touch panel, camera, Wi-Fi, Ethernet, handset, speakerphone, and SIP client all work.
2. Enter the factory password `10086` to open Advanced Settings and enable the wired network.
3. Open the dialer. Dial `*#223#*` and press the green call key to open the stock Android launcher. Dial `*#233#*` and press the green call key to open Android Settings directly.
4. If Developer options is hidden, open About device and tap Build number seven times. Enable USB debugging. This enables the Android debugging service; the transport still runs over the network.
5. Enable Unknown sources under Android Security, then pair the D31 with the Windows PC over Bluetooth.
6. 点击Windows工具的“首次引导/急救APK”取得基础193，在Windows运行`fsquirt`发送到D31。[使用说明与源码](tools/D31-wireless-adb/README.md)。
7. Leave the D31 on the stock Android launcher opened with `*#223#*`. Accept the transfer from the notification shade and install the received APK with Android's native package installer.
8. 安装后点击“打开”，再点击“开启或恢复ADB”，按实际显示地址和端口连接。有线网络同样可访问此ADB端口，不能假定固定5555。
9. Find and note the D31's wired IPv4 address in the router or the phone's Advanced Settings.

## Flashing procedure

[1.6.11工具配套1.4.6固件：写入范围、顺序和验收边界](docs/D31-v1.4.6完整刷机说明.md)。本次系统及应用载荷与1.4.5保持相同，第三方系统实刷及首次启动仍待验收。

![D31 Windows Flash and Backup Tool](images/d31-flash-tool-v1.6.0.png)

1. 下载`D31-Flash-Tool-v1.6.11.exe`，不需要解压，运行依赖和基础193已内置。
2. 运行`D31-Flash-Tool-v1.6.11.exe`。
3. 填写目标D31的IP并点击“连接ADB”，由工具检测端口；也接受显式`IP:port`。IP不会自动发现。连接后识别设备，“检测D31”刷新状态，“断开ADB”用于切换设备。
4. “只读检查”是可选的独立检查入口，不是开始刷机的前置点击步骤。它不需要刷机包，不修改或重启D31；Wi-Fi可以用于连接和只读检查，正式刷机使用有线地址。
5. Select Choose firmware package to use a ZIP already downloaded to the PC, or select GitHub accelerated download or Cloudflare accelerated download. Public GitHub Release downloads do not require an account or token. GitHub uses up to eight connections and Cloudflare uses up to sixteen; these are upper limits rather than mandatory connection counts. If the host rejects or limits segmented transfers, the utility preserves the partial download and automatically continues in single-connection compatibility mode.
6. 等待固件固定长度及内置SHA-256验证通过；连接设备、只读检查、选择固件可以任意先后。
7. 已识别D31有线`eth0`地址并校验好刷机包后，就可以勾选清空数据确认框；勾选后“开始刷机”可用，无需先手动检查。
8. “刷机前自动备份原系统到电脑硬盘”默认勾选，可以取消。该选择只决定是否备份完整系统，不影响开始按钮；boot和Recovery两个小备份仍会自动保存到电脑。备份保存到电脑的`D31备份`目录，正常刷机不需要U盘或TF卡。
9. 工具将自动保存boot/Recovery原像，在必要时先写入配套Recovery并回读，再完成整机刷写。点击“开始刷机”并确认目标后，自动执行设备检查、可选备份、传输校验、Recovery刷机和刷后验收。检查或备份失败立即停止；单独“只读检查”不会触发刷机。分区写入开始后不要断电、按实体键、关闭窗口或让电脑休眠。刷后一直停在原厂桌面、U盘打不开或反复无响应均不属于正常结果，请保留日志。

## Recovering from a failed flash

If the D31 is stuck at the China Mobile logo or “Starting apps,” keeps restarting its launcher, or will not accept ADB connections, use the Windows tool to restore management access first. Do not repeatedly disconnect power or jump straight to a factory reset. **Restoring ADB does not repair the system by itself**; it lets you collect diagnostics and address the fault.

### Windows工具1.6.11恢复ADB

1. Leave the D31 powered on and connect it and the PC to the same wired LAN. Internet access and a downloaded firmware package are not required.
2. 运行`D31-Flash-Tool-v1.6.11.exe`，填写故障D31的有线IPv4，点击“设备急救”；ADB未连接也能打开。
3. Check the IP at the top of the rescue window. If you have several D31 phones, make sure you have selected the right one.

![D31 Device rescue button and window](images/d31-rescue-workflow-v1.6.0.png)

#### Try the port 8765 command probe first

1. In the upper section, select **检查探针** (Check probe).
2. If it returns the probe version and a status including `uid=0`, select **恢复ADB** (Restore ADB) in that same section and confirm.
3. 工具按探针实际回执恢复adbd并检测端口，通过电脑专用5042服务器连接；兼容旧1.11.6恢复入口的5555。以真实ADB握手及D31识别通过为成功标准。
4. Use **导出诊断** (Export diagnostics) to save read-only status information to the PC. Close the rescue window, select Connect ADB in the main window, and continue with detection and the read-only check.

此通道要求故障前已有可用8765探针。旧APK部署需提前启用“开机救援（8765）”；新固件保留独立1.11.6载荷。旧探针ADB可连时可直接维护，不必更换APK；首次蓝牙引导则使用基础193。

固件1.4.4保留独立守护及开机入口，使用`/data/local/d31-rescue/enabled`标记；仅卸载管理APK不会停止这个固件内置服务。[独立标记与历史说明](tools/D31-wireless-adb/README.md#2026-09-09开发版组成更新)

#### If the probe is unavailable, use the factory uptool service

1. Install [official Npcap](https://npcap.com/#download) on the PC. The rescue window’s **Npcap官网** button opens its website; select **刷新网卡** (Refresh adapters) after installation. **Python and a separate ADB installation are not needed; ADB is bundled with the tool.** If Npcap was restricted to administrators, run the tool as administrator.
2. In the lower section, select the PC’s Ethernet adapter connected to the D31 and enter the affected phone’s **wired MAC address**. Do not use the PC’s MAC or the D31’s Wi-Fi MAC. Check the phone’s network settings, device label, or a trusted network-neighbor record.
3. Select **查询设备** (Query device). After receiving a valid response from the target, select **恢复ADB** (Restore ADB) in the lower section and confirm.
4. The tool queries that MAC again, sends the fixed ADB recovery command, and attempts an actual connection to the IP entered above. Again, **the ADB handshake and D31 identification must pass**; “sent” alone is not a success result.
5. Close the rescue window and connect from the main window to investigate the fault. If recovery fails, keep the log and check the adapter, IP, and MAC instead of repeatedly reflashing.

uptool requires the PC and D31 to share the same wired Layer 2 network. An ordinary Internet connection, routed connection, or VPN is not a substitute. The D31’s kernel, Ethernet driver, and factory service must still be running, but its launcher and the port 8765 probe need not be available.

**Restore ADB does not flash partitions, erase user data, reboot the phone, or start a firmware installation.** The PC uses only the D31’s dedicated ADB server on port 5042 and leaves other devices’ servers alone. Diagnose first: Start flashing is a separate operation that erases data.

Third-party tools or scripts can also call uptool. Manual use is an alternative covered in the [command reference](docs/D31-uptool指令与安全说明.md); the graphical workflow above is the recommended route and does not require a command line.

### Recovery

[1.4.4保留音量加激活Recovery；音量减移动，免提确认；操作步骤和适用条件](docs/D31-v1.4.4逐项复核.md#音量加进入recovery)

### Limits and security

- [1.4.6完整固件、1.6.11工具：覆盖boot、Recovery、system及logo，清空data和旧系统缓存，保留设备身份和校准](docs/D31-v1.4.6完整刷机说明.md)。第三方系统实刷及首次启动仍待验收。
- Neither rescue channel guarantees recovery when the bootloader or kernel cannot start. See the [Windows rescue guide](docs/D31-Windows工具急救.md) for the test scope.
- The development probe and the tested factory uptool service allow root commands without a password. Use a trusted maintenance network and do not expose ports 5555 or 8765 to the Internet. uptool uses raw Ethernet frames, so ordinary TCP/UDP filtering does not establish that it is blocked. See the [security guidance](docs/D31-uptool指令与安全说明.md).

## A final note

The firmware and flashing utility were built in about a week with help from Codex, Antigravity, Cursor, Claude, and other AI-assisted development tools. It works, the family enjoys it, and that is the point.
