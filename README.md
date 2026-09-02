# 中国移动云视讯 D31（星网锐捷 SVP3390）刷机包和 Windows 刷机工具

[English documentation](README_EN.md)

## 项目介绍

本刷机包是预装Telegram、Firefox浏览器、Thunderbird邮件客户端、Zello对讲的无广告海外版刷机包，并将本机Android 6系统进行多项安全加固，避免老版本Android的各种漏洞导致感染木马、病毒和被远程攻击。

之所以为D31开发这款定制系统，主要是将这台退役的高级商用电话改装成家庭使用的通信终端。在不给老人、小孩使用智能手机的情况下，为他们提供方便使用的通信工具。电话座机是最纯粹的通信工具，尤其是带8英寸高质量屏幕的D31，能够原生支持IP电话和视频通信，配置上SIP线路后，就能成为企业级的内网电话系统，比使用微信视频方便简单多了。对于移动端设备，我定制了同样是电子垃圾的[中国移动和对讲D22公网对讲机系统](https://github.com/VK7KSM/ChinaMobile-D22)，作为小孩、老人外出携带的通信终端。

我从闲鱼上淘的这台全新D31座机才220元人民币！九成新D22才80元人民币！全家人玩得不亦乐乎。

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
- 预装D31无线ADB 1.10.9和D31 Zello守护0.2.7，并带有开机绑定脚本。
- Zello保持后台在线，收到私聊或频道语音时唤醒屏幕并立即切到前台；无操作约5分钟后返回原厂桌面。
- 原厂桌面的“视频会议”和“语音会议”入口分别替换为Firefox和Telegram；开机补丁会清理挂载前启动的旧会议进程，避免点击后重新进入原厂会议界面。
- 原厂桌面应用页整理为Firefox、VLC、Zello、Telegram、计算器、D31无线ADB、设置和质感文件。在D31实体按键上连续按11次`#`键，可以打开应用页配置菜单。
- 修复原厂模拟时钟写死东八区的问题，使模拟时钟和数字时间都跟随Android系统时区。
- 修复原厂SIP客户端固定使用TLS 1.0的问题，使其可以与现代SIP服务器协商TLS 1.2；网络变化后会清理失效TLS连接并恢复SIP注册，已经完成分机TLS注册和真实来电响铃验收。
- `system`镜像包含已在母机运行验收的IPv4/IPv6入站防火墙和相关开机脚本。
- 保留动态壁纸、屏保素材、MTK工程工具、日志工具和工厂测试工具。

### 移除的原厂预装和无用组件

- **垃圾中的垃圾：**学习强国和WPS Office。
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

- [D31_SVP3390_Windows_Flash_Tool_v1.3.6.zip](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.4-candidate.1/D31_SVP3390_Windows_Flash_Tool_v1.3.6.zip)：完整Windows工具包，包含图形程序、ADB、可选备份功能、Recovery急救入口、说明和首次引导APK，不包含1.26GB固件。v1.3.6修复Windows PowerShell 5.1读取中文脚本时的解析错误，并支持中文用户名和中文目录。
- [D31_SVP3390_Factory_Flash_v1.0.4_testkey.zip](https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.0.4-candidate.1/D31_SVP3390_Factory_Flash_v1.0.4_testkey.zip)：1.26GB独立签名Recovery刷机包，也可以直接在刷机工具中点击“从GitHub下载”。

### Cloudflare R2镜像

- [D31_SVP3390_Windows_Flash_Tool_v1.3.6.zip](https://cdn.elfradio.net/d31/D31_SVP3390_Windows_Flash_Tool_v1.3.6.zip)
- [D31_SVP3390_Factory_Flash_v1.0.4_testkey.zip](https://cdn.elfradio.net/d31/D31_SVP3390_Factory_Flash_v1.0.4_testkey.zip)

### SHA-256

```text
C5919AF3EA7741E67D6E4E2F06170C9ED77498CFC170B5807ECBCA98E133BA88  D31_SVP3390_Windows_Flash_Tool_v1.3.6.zip
3BDFB0B9D1A8D18872B4F0C6DD78FAA7408B4866DDC94C48C34A927A75CD2606  D31_SVP3390_Factory_Flash_v1.0.4_testkey.zip
```

## 刷机前提：启用ADB

D31的两个USB-A口目前只证明是主机口，不能当作USB设备接口连接电脑，所以本工具使用网络ADB。Wi-Fi链路容易在刷机中途断开，正式刷机必须关闭D31的Wi-Fi、插好网线，并使用Windows 10或更高版本的电脑。

1. 先完整测试原厂系统。确认开机、屏幕、触摸、摄像头、Wi-Fi、有线网络、手柄、免提和SIP电话都没有硬件故障。
2. 输入默认密码`10086`进入原厂高级设置，配置网络并打开有线连接。
3. 摘下听筒或点击屏幕上的“拨号”，输入`*#223#*`并按绿色拨出键，可进入Android原生桌面；输入`*#233#*`并按绿色拨出键，可直接打开Android系统设置。
4. 如果没有“开发者选项”，进入“关于设备”，连续点击“版本号”7次，然后打开“USB调试”。这里打开的是Android调试总开关，实际ADB数据仍走网络。
5. 在Android“安全”设置中打开“未知来源”，再打开蓝牙并与Windows电脑配对。
6. 在Windows中运行`fsquirt`，或者右键点击任务栏蓝牙图标选择“发送文件”，把工具包中`首次引导工具/D31-wireless-adb-v1.0.apk`发送给D31。
7. 让D31停留在通过`*#223#*`打开的Android原生桌面，从顶部通知栏接受蓝牙文件。传输完成后点击收到的APK，用Android原生安装器安装。
8. 安装完成后立即点击“打开”，再点击“开启无线ADB（端口5555）”。应用名称虽然叫“无线ADB”，同一个端口也可以通过有线网络访问。
9. 从路由器后台或D31高级设置中查到D31的有线IPv4地址并记下来。

## 正式刷机流程

1. 解压`D31_SVP3390_Windows_Flash_Tool_v1.3.6.zip`，保持目录结构不变。该版本支持中文用户名和中文目录。
2. 双击`D31-Flash-Tool-v1.3.6.exe`。
3. 点击“选择刷机包”选择已经下载的官方ZIP，或点击“从GitHub下载”。
4. 等待1.26GB固件的固定长度和内置SHA-256全部通过。校验失败时，设备检测会保持锁定。
5. 填写D31的有线IP地址并点击“检测D31”。工具会连接D31的ADB端口5555。
6. 点击“只读检查”。该步骤检查刷机包、设备、分区、Recovery入口、空间和依赖，确保可以刷机。
7. “刷机前自动备份原系统到电脑硬盘”默认勾选，但可以取消。勾选时，工具会先把当前D31的原系统保存到电脑的`D31备份`目录，然后自动继续刷机；取消时，工具提示风险后允许直接刷机。正常刷机不需要插U盘或TF卡。
8. 勾选清空数据确认框，点击“开始刷机”。D31会自动重启。此时绝对不要将D31断电、乱按实体键或关闭刷机窗口，在刷机完成前不要碰D31，也绝对不能让电脑休眠或关机。刷机结束后，D31首次启动时会重建`userdata`并进行ART优化，可能明显变慢，这是正常现象。

## 刷机失败变砖抢救教程

本教程适用于刷机后Android无法启动、反复停留在开机画面或进入桌面前重启，但原厂Recovery仍然可以进入的情况。本工具不写`recovery`分区，因此正常的system/boot刷写失败不应破坏Recovery。

1. 先确认刷机时是否保留了默认备份选项。如果当时取消了备份，电脑中没有本机急救包，不能使用本教程。不要使用另一台D31的急救包。
2. 在电脑的`D31备份`中找到这台D31的急救包，把其中名为`需要抢救时复制到TF卡或U盘`的目录及全部文件原样复制到FAT32格式的TF卡或U盘。
3. 给D31接上稳定电源并插入TF卡或U盘，然后进入原厂Recovery。
4. TF卡选择`Apply update from SD card`；U盘选择Recovery中显示的USB OTG介质入口。
5. 进入本机急救目录，选择`D31_RESCUE_UPDATE.zip`。
6. 急救程序会先检查设备、分区尺寸以及`system`和`boot`的长度与SHA-256。任何一项不匹配都会停止，不会写入分区。
7. 开始恢复后绝对不能断电。程序会写回这台D31刷机前的原始`system`和`boot`，并在写入后完整回读校验。
8. 屏幕显示`D31 emergency restore completed`后，选择`Reboot system now`。
9. 如果显示`ERROR 22`，表示原始`system`和`boot`已经恢复并通过校验，但自动清除`userdata`失败。返回Recovery主菜单，手动执行`Wipe data/factory reset`，然后重启。
10. 恢复后的第一次开机会重建应用数据和ART缓存，等待时间会比普通启动长，不要因为暂时停留在开机画面就拔电。

如果连原厂Recovery也无法进入，TF卡/U盘卡刷就无法执行。这属于Recovery、预引导程序或硬件层面的故障，需要使用该机的私有分区备份、对应批次的MTK底层刷机文件和BootROM工具处理；当前Windows工具不包含这条底层恢复路线。不要把其他D31的`nvram`、`nvdata`、`proinfo`或校准分区写入故障机。

## 最后声明

本刷机包和刷机工具主要由Codex、Antigravity、Cursor、Claude等AI工具协助编写，一个星期搞完的，能用就好，开心就好。
