# 中国移动云视讯 D31（星网锐捷 SVP3390）刷机包和 Windows 刷机工具

[English documentation](README_EN.md)

## 项目介绍

这是一套给中国移动云视讯 D31 座机使用的无广告海外版固件和 Windows 刷机工具。固件预装 Telegram、Firefox、Thunderbird、Zello、VLC 和质感文件，并对原机的 Android 6 系统做了安全收尾，减少旧组件和无用联网服务带来的暴露面。

开发这套系统，是为了把退役的高级商用电话改成适合家庭使用的通信终端。在不给老人、小孩使用智能手机的情况下，也能让他们方便地使用 SIP 电话、视频通话、Zello、Telegram 和电子邮件。D31 本身就是一台带8英寸屏幕、手柄、免提和实体键的专业电话，配置SIP线路后，比拿着手机找微信视频更直接。

移动端可以配合我定制的[中国移动和对讲 D22](https://github.com/VK7KSM/ChinaMobile-D22)使用。我从闲鱼买到的全新D31只花了220元，九成新的D22只花了80元，全家人玩得不亦乐乎。

## 产品信息

- 产品全称：中国移动云视讯 D31 / 星网锐捷 SVP3390 视频话机
- 厂商：[福建星网锐捷通讯股份有限公司](https://www.smart-china.com/)
- 官方产品页：[SVP3390统一通信视频话机](https://www.smart-china.com/productinfo/1453527.html)
- 硬件型号：`SVP3390`
- Android内部型号：`hct6737t_66_m0`
- Android系统：`6.0`，API 23
- 安全补丁：`2017-07-05`
- 硬件平台：MediaTek MT6737T
- 内存：约2GB，真机`MemTotal`为1,959,528KB
- 内部存储：约16GB，本构建`userdata`分区为13,517,717,504字节
- 扩展存储：TF卡，厂家资料标称最大256GB
- 屏幕：8英寸电容多点触摸屏，1280 x 800，213 dpi
- 网络：两个10/100/1000Mbps自适应以太网口、PoE、2.4GHz/5GHz Wi-Fi、蓝牙4.0+EDR
- 接口：两个USB 2.0 Host、HDMI、3.5mm耳机口和RJ9手柄接口
- 电话能力：最多4个SIP账号，原厂客户端支持H.264、VP8及最高720p@30fps视频
- 构建标识：`full_hct6737t_66_m0-userdebug 6.0 MRA58K 1583081804 test-keys`
- 构建指纹：`alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys`
- Android产品标识：`ro.product.name=full_hct6737t_66_m0`，`ro.product.device=hct6735_66_m0`

## 官方资料

- [《SVP3390产品资料》](manuals/SVP3390_产品资料.pdf)：4页产品彩页，包含接口、编解码器、网络、屏幕和物理参数。
- [《SVP3390用户指南》](manuals/SVP3390_用户指南.pdf)：96页完整用户手册，包含电话、通信录、视频、按键、Web配置和维护操作。
- [资料来源和文件校验](manuals/README.md)

## 固件内容

- 保留原厂Nexui专业电话桌面、SIP音视频客户端、拨号、来电、通话、通信录、黑名单、录音、图库、中文拼音输入法、计算器、Android设置和系统WebView。
- 保留最多4个SIP账号、H.264/VP8视频通话、手柄、免提、HDMI、双网口、Wi-Fi、蓝牙和USB Host能力。
- 预装Firefox 142.0、VLC 3.7.1、Zello 5.30.1、Telegram 12.10.1、Thunderbird 22.0和质感文件1.7.4。
- 预装D31无线ADB 1.10.6和D31 Zello守护0.2.7，并带有开机绑定脚本。
- Zello保持后台在线，收到私聊或频道语音时唤醒屏幕并立即切到前台；无操作约5分钟后返回原厂桌面。
- 原厂桌面的“视频会议”和“语音会议”入口分别替换为Firefox和Telegram。
- 原厂桌面应用页整理为Firefox、VLC、Zello、Telegram、计算器、D31无线ADB、设置和质感文件。在D31实体按键上连续按11次`#`键，可以打开应用页配置菜单。
- 修复原厂模拟时钟写死东八区的问题，使模拟时钟和数字时间都跟随Android系统时区。
- `system`镜像包含已在母机运行验收的IPv4/IPv6入站防火墙和相关开机脚本。
- 保留动态壁纸、屏保素材、MTK工程工具、日志工具和工厂测试工具。

### 移除的原厂预装和无用组件

- 学习强国和WPS Office。
- 存在严重安全风险的原厂旧浏览器、旧i-jetty服务、HTML Viewer和旧音乐播放器。
- 蓝牙MIDI、AOSP快速搜索框、旧Exchange邮件服务和百度定位预装服务。
- AOSP/MTK日历、日历数据库提供程序和日历导入器。
- TR-069、TR-069代理、EMU运营商消息、废弃会议登录守护和OMACP配置程序。

## 实体按键

| 实体键 | 待机或普通应用状态 | 原厂SIP音视频通话状态 |
| --- | --- | --- |
| 信封 | 启动Thunderbird | 吞掉按键，避免邮件抢占通话 |
| 三人 | 启动Telegram | 吞掉按键，避免Telegram抢占通话 |
| 三节点 | 启动Firefox | 交回原厂SIP界面，保留屏幕分享 |
| 翻书 | 启动质感文件 | 吞掉按键，避免文件管理器抢占通话 |
| 布局切换 | 打开Android最近任务 | 交回原厂SIP界面，保留视频布局切换 |

## 下载

### GitHub

- [D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.2-candidate.1/D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip)：完整Windows工具包，包含图形程序、ADB、急救包生成器、Recovery急救入口、说明和首次引导APK，不包含1.26GB固件。
- [D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.2-candidate.1/D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip)：1.26GB独立签名Recovery刷机包，也可以直接在刷机工具中点击“从GitHub下载”。

### Cloudflare R2镜像

- [D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip](https://cdn.elfradio.net/d31/D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip)
- [D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip](https://cdn.elfradio.net/d31/D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip)

### SHA-256

```text
0734AF4EB39E95CE1771BE653998723C4BB7A513C10D605951FE0C184AE76F5A  D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip
E580BF615E57DBCC565FB140667315760815211353ED7303D073F4A92FA3E585  D31_SVP3390_Factory_Flash_v1.0.2_testkey.zip
```

## 刷机前提：启用ADB

D31的两个USB-A口目前只证明是主机口，不能当作USB设备接口连接电脑，所以本工具使用网络ADB。Wi-Fi链路容易在刷机中途断开，正式刷机必须关闭D31的Wi-Fi、插好网线，并使用Windows 10或更高版本的电脑。

1. 先完整测试原厂系统。确认开机、屏幕、触摸、摄像头、Wi-Fi、有线网络、手柄、免提和SIP电话都没有硬件故障。
2. 准备一个16GB或更大的U盘或TF卡，用于保存这台D31自己的急救恢复包。介质格式必须是FAT32。
3. 输入默认密码`10086`进入原厂高级设置，配置网络并打开有线连接。
4. 摘下听筒或点击屏幕上的“拨号”，输入`*#223#*`并按绿色拨出键，可进入Android原生桌面；输入`*#233#*`并按绿色拨出键，可直接打开Android系统设置。
5. 如果没有“开发者选项”，进入“关于设备”，连续点击“版本号”7次，然后打开“USB调试”。这里打开的是Android调试总开关，实际ADB数据仍走网络。
6. 在Android“安全”设置中打开“未知来源”，再打开蓝牙并与Windows电脑配对。
7. 在Windows中运行`fsquirt`，或者右键点击任务栏蓝牙图标选择“发送文件”，把工具包中`首次引导工具/D31-wireless-adb-v1.0.apk`发送给D31。
8. 让D31停留在通过`*#223#*`打开的Android原生桌面，从顶部通知栏接受蓝牙文件。传输完成后点击收到的APK，用Android原生安装器安装。
9. 安装完成后立即点击“打开”，再点击“开启无线ADB（端口5555）”。应用名称虽然叫“无线ADB”，同一个端口也可以通过有线网络访问。
10. 从路由器后台或D31高级设置中查到D31的有线IPv4地址并记下来。

## 正式刷机流程

> 当前项目只有一台D31母机，尚未在第二台设备上完成真实Recovery刷写。第一次刷机必须先把本机急救包和Recovery入口测试好，不能跳过。

1. 把`D31_SVP3390_Windows_Flash_Tool_v1.3.1.zip`解压到路径较短的英文目录，保持目录结构不变。
2. 双击`D31-Flash-Tool-v1.3.1.exe`。
3. 点击“选择刷机包”选择已经下载的官方ZIP，或点击“从GitHub下载”。
4. 等待1.26GB固件的固定长度和内置SHA-256全部通过。校验失败时，设备检测会保持锁定。
5. 填写D31的有线IPv4地址，点击“检测D31”。工具会连接该设备的ADB端口5555。
6. 点击“只读检查”。工具会核对固件、设备、构建、分区、Recovery、可用空间和依赖，不写分区，也不重启。
7. 点击“创建本机急救包”。工具会从当前D31读取刷机前的原始`system`、`boot`及关键身份和校准分区，并在设备端和电脑端分别核对SHA-256。这个急救包只能用于创建它的这一台D31。
8. 把生成目录中名为`复制到TF卡或U盘的整个目录`的目录及其全部文件原样复制到FAT32介质。名为`禁止公开的本机私有备份`的目录只能保存在电脑上，不能上传网盘，也不能用于另一台D31。
9. 在D31仍能正常启动时，插入介质并进入原厂Recovery，选择`D31_RESCUE_TEST.zip`。只有屏幕显示`D31 rescue media test completed: PASS`，才说明介质、Recovery、分区绑定、system和boot载荷都可以用于该机。TEST不会写分区，也不会清除数据。
10. 在Recovery中选择`Reboot system now`返回Android。勾选刷机工具中的“已运行TEST看到PASS”和清空数据确认框，“开始刷机”按钮才会启用。
11. 点击“开始刷机”并确认。后端会再次核对急救包与当前D31的绑定，然后上传、校验固件并自动重启到Recovery安装。
12. 刷机开始后，不要给D31断电，不要乱按实体键，不要关闭刷机窗口，也不能让电脑休眠或关机。首次启动会重建`userdata`并执行ART优化，明显变慢属于正常现象。

### 刷机前必须验证Recovery入口

不同生产批次的实体键和Recovery可能有差异，目前没有一套已经在所有D31上验证的通用开机组合键。第一次刷机前必须在这台D31仍能正常启动时完成以下测试：

1. 在工具目录打开PowerShell，执行：

   ```powershell
   .\tools\adb.exe -P 5038 -s <D31有线IP>:5555 reboot recovery
   ```

2. 确认D31可以进入原厂Recovery，并找到`Apply update from SD card`或USB OTG介质入口。
3. 确认哪些实体键可以上下移动、确认和返回，再运行`D31_RESCUE_TEST.zip`。
4. 还必须确认在Android无法启动的情况下，能够通过该机实际可用的实体键方式再次进入Recovery。没有确认这一点，就不具备刷砖后的独立恢复能力，不要开始正式刷机。

## 刷机失败变砖抢救教程

本教程适用于刷机后Android无法启动、反复停留在开机画面或进入桌面前重启，但原厂Recovery仍然可以进入的情况。本工具不写`recovery`分区，因此正常的system/boot刷写失败不应破坏Recovery。

1. 不要反复重刷，也不要使用另一台D31的急救包。
2. 给D31接上稳定电源，插入第一次刷机前已经运行TEST并显示PASS的FAT32 TF卡或U盘。
3. 使用刷机前已经亲自验证过的实体键方式进入原厂Recovery。
4. TF卡选择`Apply update from SD card`；U盘选择Recovery中实际显示的USB OTG介质入口。
5. 进入刷机前复制的本机急救目录，选择`D31_RESCUE_UPDATE.zip`。
6. UPDATE会先检查当前Recovery、设备绑定、分区尺寸以及system和boot的长度与SHA-256。任何一项不匹配都会在写分区前停止。
7. 开始恢复后绝对不能断电。程序会写回这台D31刷机前的原始`system`和`boot`，并在写入后完整回读校验。
8. 屏幕显示`D31 emergency restore completed`后，选择`Reboot system now`。
9. 如果显示`ERROR 22`，表示原始system和boot已经恢复并通过校验，但自动清除`userdata`失败。返回Recovery主菜单，手动执行`Wipe data/factory reset`，然后重启。
10. 恢复后的第一次开机会重建应用数据和ART缓存，等待时间会比普通启动长，不要因为暂时停留在开机画面就拔电。

如果连原厂Recovery也无法进入，TF卡/U盘卡刷就无法执行。这属于Recovery、预引导程序或硬件层面的故障，需要使用该机的私有分区备份、对应批次的MTK底层刷机文件和BootROM工具处理；当前Windows工具不包含这条底层恢复路线。不要把其他D31的`nvram`、`nvdata`、`proinfo`或校准分区写入故障机。

## 最后声明

本刷机包和刷机工具主要由Codex、Antigravity、Cursor、Claude等AI工具协助编写，一个星期搞完的，能用就好，开心就好。
