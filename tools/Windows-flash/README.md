# Windows刷机工具源码

## 1.6.8正式生产来源

公开副本同步1.6.8生产源码、实际生成常量和修正测试输入，绑定固件1.4.5及基础193。最终单EXE为16135680字节，SHA-256为`24B49DEC6E8FBD6A0F72A40A944225ADBA7B03D7BF28FA6452EAA113999A02C3`，与主线两件制品冻结清单及真实构建结果一致。

`source/build.ps1`转调`source/build-v1.6.8.ps1`，`source/build-original-workspace.ps1`保留同一原工作区构建入口。正式参数沿用`-Release`及明确的APK、元数据、固件、批准件和安装映射路径；外部依赖布局仍按原研究工作区解析，不承诺独立检出后零配置构建。

`source/src/BuildConstants.g.cs`来自实际1.6.8构建目录，已绑定1.4.5 ZIP的1725713383字节及`E74EFFC90A36EA9C7149532A7FFA7D556A448462324410BE7AA689DAF583CFC1`，内置基础APK为193。`source/source-files.json`按当前公开源文件实际字节生成；`source/sources-v1.6.8.json`记录本次同步来源与最终制品绑定，`source/sources-v1.6.7.json`仅为历史清单。

最终构建结果记录13项内嵌运行文件、自检、刷机包选择、下载进度解析和PowerShell 5.1中文路径检查通过；补跑记录确认后端56项、system 36项通过。`source/release-v1.6.8.json`仅保留公开制品和验证摘要，不包含原始测试环境或私人日志。公开刷机后端与实际嵌入文本一致，EXE封装时增加UTF-8 BOM。

`source/inputs`保存实际构建消费的固件批准件及安装映射；`source/v168-build`保存原工作区的启动与验收续作入口，供来源追溯，目录中的相对路径仍是原研究布局。续作只修正测试夹具后重跑未完成验证，最终EXE保持同一摘要；这里没有再次构建或执行生产EXE。

本次后端夹具修正位于`source/tests/BackendFakeAdb.cs`：正常初始化和运行完成标记从批准件读取版本；旧初始化和伪造标记的拒绝分支保留。它不是生产EXE或固件行为修改。独立公开副本8个生产C#文件编译通过，假ADB版本分支10项、1.4.5合同门8项通过；没有启动生产界面或连接设备，也不替代主线完整后端及上传续传验收。

新固件离线审核见[1.4.5逐项复核](../../docs/D31-v1.4.5逐项复核.md)，最终下载与摘要见[主README](../../README.md#下载)。

## 1.6.7历史交付说明

此目录是1.6.7成品使用的界面、下载、急救、内嵌资源加载、备份与测试源码。正式工具绑定1.4.4固件，内置基础169作为维护入口；固件部署系统完整170。成品仍只需运行一个EXE，源码不是运行依赖。

下载、长度和SHA-256见[主README](../../README.md#下载)，验收边界见[1.4.4逐项复核](../../docs/D31-v1.4.4逐项复核.md)。本轮修复维护回执错误判定：新版完整组件缺少健康回执时，不再把未知状态误认成旧探针；旧版本兼容分支必须同时核对真实版本与系统部署证据。20项针对性离线测试通过，最终第03次构建于18:27:34完整通过，48项源码构建前后未变；EXE内嵌资源独审已通过。固件来源集合在构建前收紧，并保留打包后精确成员核验。

首次蓝牙引导使用内置基础169（`D31-wireless-adb-v1.34.6-basic.apk`）；固件预置完整170（`D31-elfRemote-v1.34.6-full.apk`），不把完整包嵌入首次引导槽位。两者是保留candidate内部版本名的开发版。基础按钮为“开启或恢复ADB”；Windows按实际端口检测或显式`IP:port`连接，电脑ADB服务器仍为5042。

旧1.11.6探针已有ADB连接时可直接维护，不要求先升级探针。无ADB时先检查8765并恢复连接，探针不可用再走原厂uptool；恢复必须以ADB握手和D31识别为准，不以命令已发送为准。独立8765载荷继续保留1.11.6。刷机与恢复连接是不同操作，Recovery音量加入口及boot不写边界见[主说明](../../README.md#按音量加进入recovery)。

- `source/src`：界面、IP连接、GitHub/Cloudflare下载、8765及uptool客户端，含本次真实生成的版本和哈希常量。
- `source/scripts`：备份流程；实际刷机后端见[安装器源码](../factory-package/source/flash_d31_recovery.ps1)。
- `source/tests`：实际后端模拟、启动后探针、急救协议及布局测试。
- `source/tools`：基础制品合同、资源准备与迁移检查，以及批准固件和系统原生库绑定验证。
- `source/build-original-workspace.ps1`：本次使用的完整构建脚本，保留研究工作区的依赖布局。它不是独立检出后即可运行的构建入口，需配置其引用的固件、ADB、aria2、签名急救ZIP、引导APK及Android工具链；私钥未发布。

使用.NET Framework C#编译器，依赖`System.Windows.Forms`、`System.Drawing`和`System.Web.Extensions`。编译产物嵌入运行资源及其SHA-256，启动释放时逐项核验。本次成品及下载固件的哈希见主README，不要把自行修改后的版本仍称为本项目原版签名制品。

最终离线测试通过：后端56项、启动20项、报告JSON 24项、维护20项、shell 8项、system 36项、native 4项。EXE独审确认13项运行资源／15项总资源、47项安装映射、1.4.4下载常量、基础169、48项来源与Windows PowerShell 5.1后端全部匹配。

`source/build.ps1` 转调 `source/build-v1.6.7.ps1`。正式入口使用 `-Release`，必须显式指定 `BasicApk`、`FullApk`、`MetadataPath`、`FirmwarePackage`、`ApprovedPackagePath` 和 `InstalledFilesPath`；正式1.6.7拒绝1.4.3输入。源码中的旧版本仅保留历史候选兼容与拒绝测试，不是正式工具的下载绑定。

`source/src/BuildConstants.g.cs` 是本次真实生成的常量：1.4.4固件为1725684619字节，SHA-256为 `22427BB1171CA778BFE51F3C9B2E1AD6916EF630DA08FF22B1DA4D41D90C9F58`，两个下载地址均由批准文件注入。`FirmwareManager` 直接使用这些常量；不能仅更改说明或默认文件而替换已编译EXE的固件绑定。

`source/sources-v1.6.7.json` 列出实际构建来源及生成常量的逐文件摘要。`source/.gitattributes` 保留原字节，已核对 `core.autocrlf=true` 不改变这些文件。刷机后端嵌入EXE时只加UTF-8 BOM以兼容Windows PowerShell 5.1，公开源保持构建前字节。
