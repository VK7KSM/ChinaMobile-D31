# 刷机包与启动初始化源码

此目录保存1.4.3安装器、首次初始化、启动交接、载荷来源清单及离线验证代码；原始固件、私人日志、用户数据和签名私钥不在源码目录内。

- `source/native/update_binary.c`：Recovery原生安装器，写入前核对构建、分区尺寸和boot基线；不写boot或Recovery。
- `source/startup-handover`：桌面事务交接、回滚、首次权限与功能默认设置、Zello省电白名单及测试。
- `source/templates`：无账号应用页和禁用取号占位包清单。
- `source/sources-v1.4.3.json`：实际打包载荷的长度及SHA-256；最终ZIP和母机对照结果见[逐项复核](../../docs/D31-v1.4.3逐项复核.md)。
- `source/approved-package.json`：Windows前后端共同使用的固件清单。

源码脚本保留原研究工作区的路径和依赖，不包含整套工具链。构建需要匹配D31的原始system、boot和logo、清单所列APK和功能载荷、Python、Android NDK、JDK、AOSP签名工具及证书、OpenSSL和可挂载ext4镜像的Linux环境。应用签名私钥不随项目发布，不能用自行生成的密钥覆盖要求原签名的系统应用。

独立存储与网络支持源码位于[systemSupport](../D31-wireless-adb/source/systemSupport/)。它是单独安装和启动的系统功能组件，源码处于同一个Gradle工程不表示其运行依赖无线ADB管理APK。
