# 固定启动脚本修复交接

## 范围

`Consume-StartShHandoff.mjs` 消费原 `prepare_start_sh_review.py` 生成、已由操作者审核的 `handoff-private.json`。仅支持既有 `/data/local/d31-system-support/start.sh` 已知模板的ROOT内容差异；没有内容差异时不应生成或执行修复。

默认只在本地校验及预览，不读取管理员会话或访问设备。`baseline/check_start_sh_handoff.py` 复用原六用途证据包、Java规范方案摘要、原像、目标和请求校验。需要已有Python、Java及编译后的 `StartShRepairMain` 类和JSON库，与原单文件准备工具使用相同依赖。

## 使用

先运行本地预览，所有占位项必须替换为已审核的实际值：

```text
node tools/Consume-StartShHandoff.mjs --handoff <本地交接文件> --handoff-sha256 <交接摘要> --device-id <目标编号> --version <活动版本号> --version-name <活动版本名称> --apk-sha256 <活动完整APK摘要> --python <Python路径> --java <Java路径> --classpath <原Java类及JSON库路径>
```

需要执行已审核差异时，在同一命令增加 `--execute --state <本地持久目录> --session <管理员会话文件>`。版本名称例如 `1.34.11-candidate`，版本号例如 `180`，两者不能互换。运行前分别核对Web版本名称、机内活动版本号、APK摘要和启动身份。

顺序为：核对目标及活动核心，创建该事务独立载荷目录，经原上传接口及 `send_file` 送入载荷，再使用原 `root_exec` 调用 `submit`、`run`、`query`。只由设备原修复事务负责目标前像检查、备份、切换和恢复，宿主不直接覆盖系统目标。

中断后使用相同交接和相同 `--state` 目录续作。已入队的请求查询原编号；只有服务器明确证明原号不存在、请求摘要不变且仍在有效期内，才单次补投原编号。非明确缺失、过期或只读查询不会重提修改命令。上传初始化回执若丢失且未取得服务器文件编号，会保留意图并停止，不擅自新建上传。

仅查询原修复事务时增加 `--query-only`，同时保留 `--execute`、原交接及持久目录等参数。这个模式只读取活动核心和原事务，不上传载荷、不调用提交或执行；旧证据过期仍可用于查询，但不能继续新写入。

## 结果

事务结束只返回需继续核验的状态，`closedLoopVerified=false` 始终保留。随后必须保存真实事务计划和完整事件链，用新诊断、新快照及实际文件取回取得后像，再运行原 `start_sh_repair.py verify`。不得使用目标载荷冒充现场后像，也不得把 `SUCCEEDED` 当作业务运行、桌面或整机修复已经通过。

输入错误、失败回执、截断输出或设备绑定不符均停止推进。合成证据只允许离线测试，普通执行入口拒绝。该工具没有新增设备或Web协议，也没有扩大修复文件范围。

## 2026-09-14：后置证据取回

交接消费者现在对正常结束、提交时已结束及只查询续作均保存真实的 `queryRequest`、`queryResult` 归档。后置入口 `Collect-StartShPostEvidence.mjs` 读取这一归档，不重跑修复、不重新提交原事务。

在本地私有配置文件填写下列字段：`handoff`、`handoffSha256` 为原交接路径与摘要；`handoffResult`、`handoffResultSha256` 为真实查询归档路径与摘要；`deviceId`、`version`、`apkSha256` 为已核验目标及活动核心；`python`、`java`、`classpath` 复用原依赖；`state` 使用新的持久取证目录；`session` 为本机管理员会话文件。不要将该配置或设备原始证据提交到公开仓库。

```text
node tools/Collect-StartShPostEvidence.mjs <本地私有配置文件>
node tools/Collect-StartShPostEvidence.mjs <本地私有配置文件> --execute
```

第一条仅本地预检，第二条经原远程任务读取计划、完整事件链、新诊断和现场真实 `start.sh` 后像，前后核对活动核心及启动身份，再调用原验证器。只验证当前固定文件范围，不能将文件校验通过表述为桌面业务或全系统一致性通过。

中断后复用同一配置和 `state` 目录，查询原父任务，不新增修复。文件落盘、摘要与父任务证据回读核验后，调用原 `received` 回执清理服务器暂存。回执失败保留本地原件供续作；`cleanupPending` 表示服务器仍待清理，不能称为已删除。正式执行拒绝合成证据。权威入口95项离线回归通过，真实业务修复后的现场效果仍待确有受支持差异时验收。
