# 产品组件与权限只读核验

完整客户端186起，原 `RemoteDiagnosticCommand` 的 `{"operation":"runtime"}` 报告增加 `productConfiguration`。当前固定38项，只在明确请求时查询，不增加开机任务或定时报告工作。

电脑端在私有配置中填写 `deviceId`、`deviceName`、`version`、`versionName`、`apkSha256`、`state` 和 `session`。前三项身份及版本必须来自已确认的目标，版本号和版本名称不能互换；`state` 是本次专用取证目录，`session` 指向本机已有管理员会话文件。不要公开这些私有文件。

```text
node tools/Collect-ProductConfiguration.mjs <私有配置文件>
node tools/Collect-ProductConfiguration.mjs <私有配置文件> --execute
```

第一条仅本地预览。第二条沿既有远程任务读取身份、生成运行态诊断、取回文件并复核身份，校验报告摘要后才发送接收回执清理服务器暂存。中断时继续使用原配置及状态目录，查询原任务，避免重新采集。`cleanupPending` 表示服务器尚待清理，不代表本地原件丢失。

对照正式初始化参考的离线入口为：

```text
python -B tools/baseline/compare_product_configuration.py build-expected --workspace <开发目录> --output <新的预期目录>
python -B tools/baseline/compare_product_configuration.py compare --report <取回的product-runtime-private.json> --expected <预期目录/expected.json> --output <新的比较目录>
```

随源码提供的 `tools/baseline/product-1.4.4.expected.json` 是已绑定正式源生成的有限参考，可直接作为 `--expected` 输入。需要重新生成时，使用上述 `build-expected` 命令及既有正式固件消费者和模板，逐项核对固定来源摘要。缺来源保留未知，不从开发板反向猜出厂默认。比较结果应结合原取证目录的身份与回执使用，独立离线比较不能证明报告来自某台设备。默认短信、用户授权和省电例外的差异不自动视为损坏；包默认覆盖值0也不是禁用。

运行权限授予不等于应用操作权限或实际业务通过；通知监听授权不等于服务运行；电池豁免是当前合并查询，不是初始化时用户白名单的历史证明。结果不清除原九项配置覆盖缺口、不证明全系统一致，也不生成或执行修复。
