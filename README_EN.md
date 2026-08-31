# China Mobile D31 / Star-net SVP3390 Firmware and Windows Flash Tool

[中文说明](README.md)

## What this project is

This project turns the China Mobile D31, a carrier-customized Star-net SVP3390 video desk phone, into a practical home communications terminal. The firmware keeps the excellent stock phone interface and adds Telegram, Firefox, Thunderbird, Zello, VLC, and Material Files. It also removes obsolete carrier software and reduces the network exposure of the original Android 6 build.

The idea is simple: family members, especially children and older people, should be able to make SIP audio and video calls without needing a smartphone. The D31 already has an 8-inch display, a proper handset, speakerphone, physical keys, and a capable H.264/VP8 SIP client. Once connected to a PBX, it feels much more like a household appliance than a general-purpose tablet.

For a matching portable terminal, see my customized [China Mobile D22 project](https://github.com/VK7KSM/ChinaMobile-D22). I paid RMB 220 for a new-old-stock D31 and RMB 80 for a lightly used D22. They have turned out to be remarkably enjoyable pieces of retired enterprise hardware.

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
- Firefox 142.0, VLC 3.7.1, Zello 5.30.1, Telegram 12.10.1, Thunderbird 22.0, and Material Files 1.7.4 are preinstalled without user accounts.
- D31 Wireless ADB 1.10.6 and D31 Zello Guard 0.2.7 are included with their boot integration.
- Zello stays online in the background. Direct and channel voice messages wake the display and bring Zello forward; after roughly five minutes without interaction, the phone returns to the stock launcher.
- The defunct Video Conference and Voice Conference tiles now open Firefox and Telegram.
- The application page contains Firefox, VLC, Zello, Telegram, Calculator, D31 Wireless ADB, Settings, and Material Files. Pressing `#` eleven times opens the application-page configuration menu.
- The stock analogue clock no longer assumes China Standard Time and now follows the Android time zone.
- The system image includes the IPv4/IPv6 inbound firewall and boot scripts validated on the reference device.
- Live wallpapers, screen-saver assets, MediaTek engineering tools, logging utilities, and factory diagnostics are retained.

### Removed software

- Xuexi Qiangguo and WPS Office.
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
| Address book | Open Material Files | Suppressed so the file manager cannot take over the call |
| Layout | Open Android Recents | Passed back to the SIP interface for video layout control |

## Downloads

### GitHub

- [D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.2-candidate.1/D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip): the complete Windows utility, including the GUI, ADB, per-device rescue creator, signed Recovery rescue launchers, instructions, and bootstrap APKs. The 1.26 GB firmware is downloaded separately.
- [D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.2-candidate.1/D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip): the signed Recovery firmware package. The Windows utility can download it directly from GitHub.

### Cloudflare R2 mirror

- [D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip](https://cdn.elfradio.net/d31/D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip)
- [D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip](https://cdn.elfradio.net/d31/D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip)

### SHA-256

```text
0734AF4EB39E95CE1771BE653998723C4BB7A513C10D605951FE0C184AE76F5A  D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip
E580BF615E57DBCC565FB140667315760815211353ED7303D073F4A92FA3E585  D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip
```

## Preparing a factory D31

The two USB-A sockets are known to operate as host ports. They cannot currently be used as a USB device connection to a PC, so the flash utility communicates with ADB over Ethernet. Do not flash over Wi-Fi: disable Wi-Fi on the D31, connect Ethernet, and use a Windows 10 or newer PC.

1. Test the unmodified phone first. Confirm that it boots normally and that the display, touch panel, camera, Wi-Fi, Ethernet, handset, speakerphone, and SIP client all work.
2. Prepare a 16 GB or larger USB drive or microSD/TF card, formatted as FAT32. It will hold the rescue set made from this particular D31.
3. Enter the factory password `10086` to open Advanced Settings and enable the wired network.
4. Open the dialer. Dial `*#223#*` and press the green call key to open the stock Android launcher. Dial `*#233#*` and press the green call key to open Android Settings directly.
5. If Developer options is hidden, open About device and tap Build number seven times. Enable USB debugging. This enables the Android debugging service; the transport still runs over the network.
6. Enable Unknown sources under Android Security, then pair the D31 with the Windows PC over Bluetooth.
7. Run `fsquirt` on Windows, or use Send a file from the Bluetooth tray menu, and send `首次引导工具/D31-wireless-adb-v1.0.apk` from the extracted utility.
8. Leave the D31 on the stock Android launcher opened with `*#223#*`. Accept the transfer from the notification shade and install the received APK with Android's native package installer.
9. Tap Open after installation, then tap the button that enables wireless ADB on port 5555. The application name says wireless ADB, but the same TCP port is reachable over Ethernet.
10. Find and note the D31's wired IPv4 address in the router or the phone's Advanced Settings.

## Flashing procedure

> This remains a candidate release. The project has only one reference D31, so a complete destructive Recovery flash has not yet been validated on a second unit. Do not skip the per-device rescue procedure during the first deployment.

1. Extract `D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip` to a short path containing only English characters. Keep the directory structure intact.
2. Run `D31-Flash-Tool-v1.3.1.exe`.
3. Choose Select package for a firmware ZIP already on the PC, or choose Download from GitHub.
4. Wait for the complete 1.26 GB package length and embedded SHA-256 check. Device detection remains disabled if validation fails.
5. Enter the D31's wired IPv4 address and select Detect D31. The utility connects to ADB on port 5555.
6. Run the read-only preflight. It validates the package, device, build, partition layout, Recovery, free space, and required tools without writing a partition or rebooting the phone.
7. Select Create per-device rescue set. The utility reads the original `system`, `boot`, and critical identity/calibration partitions from this D31 and verifies the copies on both the phone and PC. The resulting rescue set belongs only to this device.
8. Copy the complete directory named `复制到TF卡或U盘的整个目录` to the FAT32 card or drive. Keep the directory named `禁止公开的本机私有备份` on the PC; never upload it or use it with another D31.
9. While Android still works, insert the rescue media, enter the stock Recovery, and run `D31_RESCUE_TEST.zip`. Continue only if the display reports `D31 rescue media test completed: PASS`. TEST checks the media, device binding, partitions, Recovery, system image, and boot image without writing or erasing anything.
10. Select Reboot system now. Back in Windows, acknowledge that TEST passed and confirm data erasure. The Flash button is enabled only after both confirmations.
11. Start the flash. The backend verifies that the rescue directory still belongs to the connected D31, uploads and checks the firmware, then reboots into Recovery automatically.
12. Once flashing starts, do not remove power, press keys, close the utility, or allow the PC to sleep or shut down. The first boot rebuilds `userdata` and the ART cache, so it may take considerably longer than a normal boot.

### Prove Recovery access before flashing

Physical key handling and Recovery behavior may differ between production batches. There is not yet one boot-key combination verified across every D31. Before the first flash, perform the following checks while Android is still healthy:

1. Open PowerShell in the utility directory and run:

   ```powershell
   .\tools\adb.exe -P 5038 -s <WIRED-D31-IP>:5555 reboot recovery
   ```

2. Confirm that stock Recovery starts and exposes either Apply update from SD card or a working USB OTG media entry.
3. Identify the physical keys used for navigation, confirmation, and return, then run `D31_RESCUE_TEST.zip`.
4. You must also prove that this particular unit can enter Recovery without a working Android system. If you cannot enter Recovery independently, the media is not yet a complete unbrick path and you should not proceed with the flash.

## Recovering from a failed flash

This procedure covers a soft brick: Android no longer boots, repeatedly resets at the logo, or fails before reaching the launcher, while the stock Recovery still works. The Windows utility never writes the `recovery` partition, so a failed system/boot installation should normally leave Recovery intact.

1. Stop retrying the main firmware. Never use a rescue set created by another D31.
2. Connect stable power and insert the FAT32 card or USB drive whose TEST package passed before flashing.
3. Enter stock Recovery using the physical method verified on this exact phone before the flash.
4. For a TF card, choose Apply update from SD card. For a USB drive, choose the USB OTG media entry that was proven during the pre-flash test.
5. Open the per-device rescue directory and select `D31_RESCUE_UPDATE.zip`.
6. UPDATE verifies Recovery, the device binding, partition sizes, and the full system and boot payloads before any partition is written. A mismatch stops the operation safely.
7. Do not remove power after writing begins. The installer restores the original pre-flash `system` and `boot`, then reads both partitions back and verifies their SHA-256 values.
8. When the screen reports `D31 emergency restore completed`, select Reboot system now.
9. If Recovery reports `ERROR 22`, the original system and boot images have already been restored and verified, but automatic `userdata` erasure failed. Return to the main Recovery menu, run Wipe data/factory reset, and reboot.
10. The first recovered boot rebuilds application data and the ART cache. Give it time; do not power-cycle the phone merely because the boot logo remains visible longer than usual.

If stock Recovery itself cannot be entered, card-based recovery cannot run. That is a Recovery, preloader, or hardware-level failure and requires the private backups from this phone, a matching MediaTek low-level firmware set, and BootROM tooling. This Windows package does not currently provide that path. Never write `nvram`, `nvdata`, `proinfo`, or calibration partitions taken from a different D31.

## A final note

The firmware and flashing utility were built in about a week with help from Codex, Antigravity, Cursor, Claude, and other AI-assisted development tools. It works, the family enjoys it, and that is the point.
