# 刷机包与启动初始化源码

此目录保存1.4.4安装器、首次初始化、启动交接、载荷来源清单及离线验证代码；原始固件、私人日志、用户数据和签名私钥不在源码目录内。

- `source/native/update_binary.c`：Recovery原生安装器，写入前核对构建、分区尺寸和boot基线；不写boot或Recovery。
- `source/startup-handover`：桌面事务交接、回滚、首次权限与功能默认设置、Zello省电白名单及测试。
- `source/templates`：无账号应用页和禁用取号占位包清单。
- `source/sources-v1.4.4.json`：本轮打包载荷的长度及SHA-256；实际离线镜像、APK、OAT与本机局部验收见[逐项复核](../../docs/D31-v1.4.4逐项复核.md)。旧1.4.3记录保留，不代表本轮结果。
- `source/approved-package.json`：Windows前后端共同使用的固件清单。

源码脚本保留原研究工作区的路径和依赖，不包含整套工具链。构建需要匹配D31的原始system、boot和logo、清单所列APK和功能载荷、Python、Android NDK、JDK、AOSP签名工具及证书、OpenSSL和可挂载ext4镜像的Linux环境。应用签名私钥不随项目发布，不能用自行生成的密钥覆盖要求原签名的系统应用。

独立存储与网络支持源码位于[systemSupport](../D31-wireless-adb/source/systemSupport/)。它是单独安装和启动的系统功能组件，源码处于同一个Gradle工程不表示其运行依赖无线ADB管理APK。

本轮系统预置完整elfRemote 170，首次引导基础169供Windows单独内置；完整APK位于`/system/priv-app/D31ElfRemote/D31ElfRemote.apk`。独立8765守护仍使用1.11.6载荷，不随完整应用改号。短信为同包同签名11／`0.5.2-dev-debug`，必须配套其4份DEX对应OAT，不能沿用旧短信0.4.0或0.5.1的预编译物。

预装ABI修补将完整APK内同源的`libjingle_peerconnection_so.so`预置到`/system/priv-app/D31ElfRemote/lib/arm/`。D31的`PackageManagerService.setBundledAppAbi`据此选择32位`armeabi-v7a`；目录0755、文件0644、属主root:root、标签`system_file`，文件纳入安装清单。此修补不改APK或媒体代码，也不以开发板已有`/data`更新包的通过代替首次系统预装验收。构建器同时收紧前置来源集合，保留后置精确成员校验。

干净初始化应包含完整APP权限和启停、SystemSupport通知监听与既有Zello监听、桌面通知及空SIP蜂窝选择配套交接、elfRemote应用页标签，并保留取号组件禁用。出厂种子不复制个人账号、身份密钥、短信、通知、Wi-Fi密钥、用户排序或整个userdata。全部回填与保留项见[功能清单](../../docs/D31-v1.4.4逐项复核.md#功能与对应文件)。

安装器维持写system、logo及清空data的既定范围；boot仅核验，Recovery不写。保留[音量加Recovery入口](../../docs/D31-v1.4.4逐项复核.md#音量加进入recovery)。本版不宣称已完成另台空白机整包实刷或首次仅写system的独立测试。
