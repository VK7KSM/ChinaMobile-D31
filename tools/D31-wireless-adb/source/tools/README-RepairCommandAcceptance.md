# 生产修复拒绝与维护互斥验收工具

## 范围

`Test-RepairCommandDevice.ps1`通过真实`RemoteRepairCommand`和`RemoteWindowsMaintenance`执行验收。生产目标固定为`/data/local/d31-system-support/start.sh`，仅读取和归档，绝不写入或执行。方案故意登记不同的原像SHA-256，只允许推进一次`PENDING`前检，预期终态为`REJECTED`。

工具需要PowerShell 7.2以上。D31 ADB固定使用5042服务器及明确完整序列号；不安装APK、不停止核心、不升级监督、不调用Web。运行前最终完整APK必须已安装并成为活动载荷，且位于既有冻结发布目录。

## 新增文件

- `Test-RepairCommandDevice.ps1`：宿主验收及原始证据归档。
- `repair-fixture/RepairCommandAcceptanceMain.java`：独立测试入口，包名`net.elfradio.d31bootstrap`。调用真实`RepairPlan`计算方案摘要，核对目标原件，并有界持有真实`RemoteMaintenance`锁。
- `Test-RepairCommandTool.ps1`：只载入纯校验函数的离线测试，不载入ADB执行函数或求值设备脚本主体。

独立入口应由主任务使用最终候选的编译类作为编译参考，再以D8生成仅含`RepairCommandAcceptanceMain`及其必要辅助类的测试jar。不要将APK业务类打入测试jar；设备上候选APK和测试jar使用同一个`CLASSPATH`。本工具交付没有生成jar，由主任务集中构建。

## 版本与调用

`ExpectedVersion`是必填参数，允许96及以上任意明确版本，不固定某次构建编号。入口要求冻结APK、实际安装副本和活动载荷的版本均等于该值，三个SHA-256均等于`ExpectedApkSha256`，并确认完整制品标记。以下100仅为主任务当前计划示例；运行时填写真正安装且活动的最终版本：

```powershell
./Test-RepairCommandDevice.ps1 `
    -Serial '<已确认D31完整序列号>' `
    -ApkPath '<最终完整APK本地路径>' `
    -ExpectedApkSha256 '<最终APK小写SHA-256>' `
    -ExpectedVersion 100 `
    -CheckJar '<独立验收DEX jar本地路径>' `
    -CapturePath '<全新本地捕获目录>'
```

脚本只把独立测试jar推到全新`/data/local/tmp/d31-repair-acceptance-<随机号>`目录；不推送或覆盖已冻结APK。另为全新任务创建空`repair-input/<task>/artifacts`目录，排除缺少载荷目录导致的早退；不创建任何替换载荷。

入口命令合同为：

```text
RepairCommandAcceptanceMain snapshot <APK摘要> <明确版本>
RepairCommandAcceptanceMain plan <APK摘要> <明确版本> <reject-随机任务号>
RepairCommandAcceptanceMain hold <APK摘要> <明确版本>
```

`plan`读取真实原像后改变摘要的首个十六进制字符，保证方案原像摘要不等于所读原件；使用实际构建和真实`RepairPlan.sha256()`生成请求。`hold`取得原有维护锁、输出就绪标记，最多持有20秒后释放，不创建新锁路径或持久预留。

## 验收顺序

1. 核对机型、root、API23、最终APK摘要、实际安装与活动版本，生成全新任务；已有修复或Windows维护预留时不开始测试。归档生产原件、内容摘要、inode、属主、组、模式、修改时间、状态变更时间及SELinux标签。
2. 使用新Windows会话预留；同号再次预留成功，不同号预留和错误号释放必须失败。随后重新读取预留归属，证明错误请求未释放或转移预留。
3. Windows预留期间，真实repair提交必须因`Windows刷机交接尚未结束`拒绝；同任务查询失败，且事务目录实际不存在。只释放本测试Windows会话。
4. 正常提交拒绝方案，要求`PENDING`和对应修复预留；此时Windows预留必须被拒绝。
5. 独立进程实际持有同一维护锁期间，repair的`step`必须因`维护正在进行`失败，而`query`仍返回同一`PENDING`和未变化的日志序号。等待独立入口正常释放锁，不杀死生产进程。
6. 重新核对原件未变，只推进一次前检。必须取得`REJECTED`、`attempted=-1`、原生`PRECHECK_FAILED`，且文件观察为`REGULAR`、观察SHA-256等于前置原件而不等于方案原像。只有这一证据组合才在验收结果中标为`precheck_gate=TARGET_MISMATCH`；该字段是有证据的验收分类，不伪称原核心已经提供同名原因字段。
7. 检查未创建备份或暂存目录、修复预留已释放；再次成功执行Windows预留与释放，证明后续维护可用。归档后置原件并与前置摘要比较，全部元数据也须保持。

任何错误都不能把任意`REJECTED`当成目标不匹配通过。离线校验特别覆盖读失败、缺少观察、错误hash、错误阶段、已尝试写入和错误摘要被拒绝。

## 输出与失败收尾

成功后生成`result.json`及`web-query.json`。后者直接兼容`Verify-RemoteRepair.mjs`：

```json
{
  "apk": "/data/local/d31-remote/releases/<实际摘要>/remote.apk",
  "request": {
    "operation": "query",
    "task_id": "reject-<本次任务号>",
    "plan_sha256": "<真实RepairPlan摘要>"
  },
  "expected_phase": "REJECTED"
}
```

本工具不运行Web验证器，不读取Web会话。后续Web仅查询已经验收的同一任务，不能重发修复请求。运行生效始终标为`NOT_CHECKED`。

捕获保存每条精确命令、原始输出、设备退出码、请求、前后原件、任务及预留证据。所有本地输出排他创建；`artifacts-private.json`在冻结文件列表后生成，明确排除清单自身。任务号、路径及现场信息放私有记录，不在工具终端输出完整设备资料。

失败收尾只释放本测试Windows会话。若本次修复仍为`PENDING`且原件仍匹配前置快照，最多执行一次拒绝前检；不会跨入备份、暂存或切换，不删除原事务或手工删除维护记录。异常阶段或原件变化会保留明确失败与收尾结果供主任务处理。

## 本地验证记录

2026-09-11：PowerShell解析通过，`Test-RepairCommandTool.ps1`的13项离线校验通过；独立Java入口按`--release 8`编译通过。证据位于`research/d31/staging/repair-command-tool-compile-20260911-202414-950/`。

首次Java编译曾误用尚无`RemoteWindowsMaintenance`的早期预检类目录，失败记录保留在`repair-command-tool-compile-20260911-202318-177/`；改为包含该类的已构建候选后通过。没有修改运行源码、运行Gradle、构建jar或执行任何设备命令；上述结果不是生产验收已经通过。
