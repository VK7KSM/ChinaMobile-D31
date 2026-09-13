# D31短信定制源码

## 2026-09-13：固件1.4.4配套0.5.2

当前 `overlay` 已更新为版本代码11、`0.5.2-dev-debug`，包名仍为 `net.elfradio.d31phone.debug`；上游基线仍为 `555b8822c654b8ee85bb9d3f961eb30f232b1079`。包含当前自定义源码、资源、匹配原生库、正式测试及WorkerFactory修正。

本次来源、还原命令、32项专项测试范围、签名和原生库复现限制见[源码来源说明](源码来源-0.5.2.md)，文件哈希见[source-files.json](source-files.json)。[version-11.patch](version-11.patch)保留两行版本变更供审核；覆盖层已经包含该变更，还原后不要重复应用补丁。

以下是0.4.0历史记录，不作为当前APK版本或当前验收结果。

### 1.4.4当前制品核对

固件内短信APK为版本代码`11`、`0.5.2-dev-debug`，69777875字节，SHA-256：`49300FC626B5493F490013414CBCD0B9FB8DCA31729B2E36DA1328906D75F6EF`。保持实际包名`net.elfradio.d31phone.debug`及旧签名，不能将旧code10／0.5.1制品当作包含WorkerFactory修复的新包。

后台任务工厂直接构造三个自有Worker，其余类型返回`null`交给既有机制处理。本次32项专项测试通过；主线已安装短信11，核对匹配安装路径的4份DEX与OAT，SIP注册及实际界面正常，观察窗口无FATAL或ANR。这是本机局部验收，不代表另台空白机整包实刷通过。固件最终摘要和验证边界见[1.4.4逐项复核](../../docs/D31-v1.4.4逐项复核.md)。

## 0.4.0历史记录

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

## 0.4.0历史制品核对

旧1.4.3打包APK的SHA-256：`9B1CED633F782964B1F696329CFD5C8CF6F24CCDA1A61925B7C2ACFB4D9D559F`；仅保留作历史对照，不用于1.4.4校验。

旧1.4.3记录包含与当时APK的4个DEX及安装路径对应的Arm64缓存和本机应用测试。1.4.4使用短信11对应的新缓存，不沿用本节旧制品。
