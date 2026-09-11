# D31短信0.4.0定制源码

此目录保存固件1.4.3中短信应用的定制源码覆盖层。应用版本为`0.4.0-dev-debug`，版本代码为`7`，包名为`net.elfradio.d31phone.debug`。

## 功能

- SIM与SIP短信使用同一个会话列表：SIM浅粉色，SIP浅蓝色。
- 蓝色加号创建SIM短信，粉色加号创建SIP短信；共用QUIK的`ComposeActivity`与编辑布局，内部按通道发送。
- SIP账号设置位于设置中的网络通信入口；修正发送传输、端口和代理绑定，按消息标识处理结果。
- 保留数据库升级兼容及后台任务工厂修复。此目录不包含任何实际账号、短信数据库或私钥。

## 还原与构建

1. 获取上游[QUIK源码](https://github.com/quik-sms/quik)，切换到提交`555b8822c654b8ee85bb9d3f961eb30f232b1079`。
2. 将本目录`overlay`中的文件按原相对路径覆盖到上游工作树。`source-files.json`登记每个文件的长度及SHA-256。
3. 配置JDK 17和Android SDK。使用上游Gradle包装器运行`:presentation:assembleDebug :presentation:testDebugUnitTest`；首次构建需取得Gradle依赖，已有完整缓存时可使用`--offline`。

`overlay`包括匹配的PJSUA2 Java绑定及Arm64原生库，以保留本次应用使用的接口。原生库复用既有网关制品，本次没有修改，完整原生工具链未包含在此目录。对应上游为[PJSIP/PJSUA2](https://github.com/pjsip/pjproject)、[OpenH264](https://github.com/cisco/openh264)及Android NDK的LLVM运行库。

QUIK及其定制代码沿用[GPL许可证](LICENSE)，第三方依赖保留各自许可证，不因本仓库其他目录采用不同许可证而改变。构建会使用本机调试签名；自行构建的APK不保证能覆盖已安装的项目签名版本，不应为安装而随意清除已有短信数据。

## 制品核对

本次打包APK的SHA-256：`9B1CED633F782964B1F696329CFD5C8CF6F24CCDA1A61925B7C2ACFB4D9D559F`。

固件同时预置与该APK的4个DEX及安装路径对应的Arm64缓存；不是沿用旧版APK的缓存。用户已完成短信应用测试，发布过程再次核对母机APK与最终ZIP载荷相同。

## 2026-09-12审核增量

本轮后台任务工厂的直接构造加固及6项可复现测试见[独立增量说明](audit-worker-factory-20260912/README.md)。它基于本目录冻结的0.4.0覆盖层单独应用，不修改这里的历史制品、覆盖层或清单，也不表示已发布完整0.5.1源码或新版短信APK。
