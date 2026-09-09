# Windows刷机工具源码

此目录是1.6.4成品使用的界面、下载、急救、内嵌资源加载、备份与测试源码。成品仍只需运行一个EXE，源码不是运行依赖。

- `source/src`：界面、IP连接、GitHub/Cloudflare下载、8765及uptool客户端，含本次真实生成的版本和哈希常量。
- `source/scripts`：备份流程；实际刷机后端见[安装器源码](../factory-package/source/flash_d31_recovery.ps1)。
- `source/tests`：实际后端模拟、启动后探针、急救协议及布局测试。
- `source/build-original-workspace.ps1`：本次使用的完整构建脚本，保留研究工作区的依赖布局。它不是独立检出后即可运行的构建入口，需配置其引用的固件、ADB、aria2、签名急救ZIP、引导APK及Android工具链；私钥未发布。

使用.NET Framework C#编译器，依赖`System.Windows.Forms`、`System.Drawing`和`System.Web.Extensions`。编译产物嵌入运行资源及其SHA-256，启动释放时逐项核验。本次成品及下载固件的哈希见主README，不要把自行修改后的版本仍称为本项目原版签名制品。
