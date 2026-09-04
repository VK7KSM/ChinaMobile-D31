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
- Firefox 142.0, VLC 3.7.1, Zello 5.30.1, Telegram 12.10.1, Thunderbird 22.0, Messages 0.3.0, and the D31 File Manager 1.7.4-d31.2 are preinstalled without user accounts.
- D31 Wireless ADB 1.10.19 and D31 Zello Guard 0.2.7 are included with their boot integration. USB drives and TF cards are detected dynamically, shown in a confirmation prompt, and listed as separate storage locations in the file manager.
- Zello stays online in the background. Direct and channel voice messages wake the display and bring Zello forward; after roughly five minutes without interaction, the phone returns to the stock launcher.
- The defunct Video Conference and Voice Conference tiles now open Firefox and Telegram. The boot patch also stops conference processes started before the patched package is mounted, preventing those tiles from falling back to the original conference screens.
- The orange tile on the stock home screen now opens the dual-channel Messages application. The application page contains Firefox, VLC, Zello, Telegram, Calculator, D31 Wireless ADB, Settings, and File Manager. Pressing `#` eleven times opens the application-page configuration menu.
- The stock analogue clock no longer assumes China Standard Time and now follows the Android time zone.
- The stock SIP client no longer forces TLS 1.0. It can negotiate TLS 1.2 with current SIP servers, and stale TLS sessions are cleared after a network change so SIP registration can recover. The fix has been verified with a successful TLS registration and a real incoming call.
- The package includes the dynamic IPv4/IPv6 inbound firewall, port 5555 ADB recovery at boot, the port 8765 status probe, and their boot integration as validated on the reference device.
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

### GitHub

- [D31_SVP3390_Windows_Flash_Tool_v1.4.8.zip](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.2.0-candidate.1/D31_SVP3390_Windows_Flash_Tool_v1.4.8.zip): the complete Windows utility, including the GUI, ADB, optional PC-based system backup, signed Recovery rescue launchers, instructions, bootstrap APKs, and the aria2 1.37.0 download engine. The 1.32 GB firmware is downloaded separately. It supports explicit ADB connection and disconnection, device checks before downloading firmware, multiple D31 phones selected by IP, Chinese Windows paths, accelerated GitHub and Cloudflare downloads, live throughput and ETA, resume support, and automatic single-connection fallback. The utility uses the dedicated D31 ADB server port 5042 and does not interfere with Pixel 3 on 5041 or H13 on 5038.
- [D31_SVP3390_Factory_Flash_v1.2.0_testkey.zip](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.2.0-candidate.1/D31_SVP3390_Factory_Flash_v1.2.0_testkey.zip): the signed Recovery firmware package. Version 1.2.0 retains the complete, physically cleaned system image and adds dynamic USB/TF storage handling, the customized file manager, dual-channel messaging, and the Messages home-screen tile.

### Cloudflare R2 mirror

- [D31_SVP3390_Windows_Flash_Tool_v1.4.8.zip](https://cdn.elfradio.net/d31/D31_SVP3390_Windows_Flash_Tool_v1.4.8.zip)
- [D31_SVP3390_Factory_Flash_v1.2.0_testkey.zip](https://cdn.elfradio.net/d31/D31_SVP3390_Factory_Flash_v1.2.0_testkey.zip)

### SHA-256

```text
91285ADF2C4701251B31DBCF20AB390885D29428CC5C2D7C6DE8B80C63B02D99  D31_SVP3390_Windows_Flash_Tool_v1.4.8.zip
BB50F3BE5B4D9156915D01EC50270F4C5A6ECA80CB0B782C4764B84AA5FABD22  D31_SVP3390_Factory_Flash_v1.2.0_testkey.zip
```

## Preparing a factory D31

The two USB-A sockets are known to operate as host ports. They cannot currently be used as a USB device connection to a PC, so the flash utility communicates with ADB over Ethernet. Do not flash over Wi-Fi: disable Wi-Fi on the D31, connect Ethernet, and use a Windows 10 or newer PC.

1. Test the unmodified phone first. Confirm that it boots normally and that the display, touch panel, camera, Wi-Fi, Ethernet, handset, speakerphone, and SIP client all work.
2. Enter the factory password `10086` to open Advanced Settings and enable the wired network.
3. Open the dialer. Dial `*#223#*` and press the green call key to open the stock Android launcher. Dial `*#233#*` and press the green call key to open Android Settings directly.
4. If Developer options is hidden, open About device and tap Build number seven times. Enable USB debugging. This enables the Android debugging service; the transport still runs over the network.
5. Enable Unknown sources under Android Security, then pair the D31 with the Windows PC over Bluetooth.
6. Run `fsquirt` on Windows, or use Send a file from the Bluetooth tray menu, and send `首次引导工具/D31-wireless-adb-v1.0.apk` from the extracted utility.
7. Leave the D31 on the stock Android launcher opened with `*#223#*`. Accept the transfer from the notification shade and install the received APK with Android's native package installer.
8. Tap Open after installation, then tap the button that enables wireless ADB on port 5555. The application name says wireless ADB, but the same TCP port is reachable over Ethernet.
9. Find and note the D31's wired IPv4 address in the router or the phone's Advanced Settings.

## Flashing procedure

![D31 Windows Flash and Backup Tool](images/d31-flash-tool-v1.4.7.png)

1. Extract `D31_SVP3390_Windows_Flash_Tool_v1.4.8.zip` and keep the directory structure intact. This release supports Windows user names and extraction paths containing Chinese characters.
2. Run `D31-Flash-Tool-v1.4.8.exe`.
3. Enter the D31's IP address and select Connect ADB. The address is not discovered automatically: when several D31 phones are present, enter the address of the unit you want to manage. A successful connection identifies the phone immediately. Detect D31 refreshes its status, while Disconnect ADB releases the current phone before switching to another one.
4. Select Read-only check. The firmware package does not need to be downloaded first. This step checks the device, root access, build, network, partition layout, Recovery entry point, available space, and dependencies without modifying or restarting the D31. A Wi-Fi address can be used for connection and read-only checks, but not for flashing.
5. Select Choose firmware package to use a ZIP already downloaded to the PC, or select GitHub accelerated download or Cloudflare accelerated download. Public GitHub Release downloads do not require an account or token. GitHub uses up to eight connections and Cloudflare uses up to sixteen; these are upper limits rather than mandatory connection counts. If the host rejects or limits segmented transfers, the utility preserves the partial download and automatically continues in single-connection compatibility mode.
6. Wait for the 1.32 GB firmware package to pass both the fixed-length check and the built-in SHA-256 check. Device connection, the read-only check, and package selection may be completed in any order.
7. Before flashing, connect Ethernet and make sure the utility is connected to the address assigned to the D31's wired `eth0` interface. The data-wipe confirmation and Start flashing control are enabled only after the device check, package verification, and wired-address check have all passed.
8. Back up the original system to this PC before flashing is selected by default, but it is optional. If left selected, the utility saves this D31's original system under `D31备份` on the PC and then continues automatically. If cleared, the utility warns that no rescue package will be available and allows the flash to continue. No USB drive or TF card is needed for a normal flash.
9. Tick the data-wipe confirmation and select Start flashing. The D31 restarts automatically. Do not disconnect its power, press its physical keys, close the flashing utility, or allow the PC to sleep or shut down before the process finishes. On the first boot, Android rebuilds `userdata` and performs ART optimization, so the phone may run much more slowly than usual. This is normal.

## Recovering from a failed flash

This procedure covers a soft brick: Android no longer boots, repeatedly resets at the logo, or fails before reaching the launcher, while the stock Recovery still works. The Windows utility never writes the `recovery` partition, so a failed system/boot installation should normally leave Recovery intact.

1. First confirm that the backup option was left enabled for the failed flash. If it was cleared, no rescue package exists and this procedure cannot be used. Never use a rescue package from another D31.
2. Under `D31备份` on the PC, find the rescue package for this D31. Copy the complete directory named `需要抢救时复制到TF卡或U盘`, including all of its files, to a FAT32-formatted TF card or USB drive.
3. Connect the D31 to stable power, insert the TF card or USB drive, and enter the stock Recovery.
4. For a TF card, select Apply update from SD card. For a USB drive, select the USB OTG media entry shown by Recovery.
5. Open the rescue directory for this D31 and select `D31_RESCUE_UPDATE.zip`.
6. The rescue installer checks the device, partition sizes, and the lengths and SHA-256 values of `system` and `boot` before writing anything. If any check fails, it stops without writing a partition.
7. Do not remove power after recovery begins. The installer writes back the original pre-flash `system` and `boot` from this D31, then reads both partitions back and verifies them in full.
8. When the screen reports `D31 emergency restore completed`, select Reboot system now.
9. If Recovery reports `ERROR 22`, the original `system` and `boot` images have already been restored and verified, but automatic `userdata` erasure failed. Return to the Recovery main menu, run Wipe data/factory reset, and reboot.
10. The first recovered boot rebuilds application data and the ART cache. Give it time; do not power-cycle the phone merely because the boot logo remains visible longer than usual.

If stock Recovery itself cannot be entered, card-based recovery cannot run. That is a Recovery, preloader, or hardware-level failure and requires the private backups from this phone, a matching MediaTek low-level firmware set, and BootROM tooling. This Windows package does not currently provide that path. Never write `nvram`, `nvdata`, `proinfo`, or calibration partitions taken from a different D31.

## A final note

The firmware and flashing utility were built in about a week with help from Codex, Antigravity, Cursor, Claude, and other AI-assisted development tools. It works, the family enjoys it, and that is the point.
