# D31原厂uptool：完整动作码、使用方法与安全说明

## 这是什么

`/system/bin/uptool`是D31原厂固件内的以太网批量管理程序，不是后来安装的8765探针，也不是ADB。它接收原始以太网帧，按目标MAC通信，协议类型为`EtherType 0x9974`，不是TCP或UDP端口。

本页依据本项目提取的uptool 1.0.9：文件333076字节，SHA-256为`20036AEE577D3D604360FB5A5786B68D100661024B8515CD3B4DFAA9F8354F89`。版本来自程序字符串；动作数值来自DWARF枚举，并结合反汇编及既有真机记录复核。不同批次固件可能不同，不能只看设备外壳相同就假定协议完全一致。

2026年9月6日，已经在卡开机、桌面和ADB不可用的本项目D31上，通过此接口恢复root ADB。这个结果不代表所有动作都经过测试，也不是内核或引导器损坏时的离线救砖保证。

## 全部18个协议动作

以下列出该样本中识别出的全部`AC_`动作码，包括请求、响应及错误报告。响应码不是另一条应由电脑主动发送的指令。“静态”表示枚举或处理路径已识别，不表示该动作已在真机完成。

| 动作码 | 原厂标识 | 方向与作用 | 核验状态 |
| --- | --- | --- | --- |
| `0x0101` | `AC_GET_DEVICE_INFO` | 电脑请求设备信息 | 已实测单播查询 |
| `0x0102` | `AC_SEND_DEVICE_INFO` | 设备返回设备信息 | 已收到长度及校验正确的回复 |
| `0x0103` | `AC_GET_ALL_NORMAL_CMD_STATES` | 请求普通命令的状态集合 | 静态识别，未真机验收 |
| `0x0104` | `AC_SEND_ALL_OPERATION_STATES` | 返回操作状态集合 | 静态识别，未真机验收 |
| `0x0105` | `AC_GET_RESTORE_STATE` | 请求恢复操作状态 | 静态识别，未真机验收 |
| `0x0106` | `AC_SEND_RESTORE_STATE` | 返回恢复操作状态 | 静态识别，未真机验收 |
| `0x0201` | `AC_TRANS_SOFTWARE_START` | 开始软件传输，解析升级信息 | 静态识别；涉及升级流程，未执行 |
| `0x0202` | `AC_TRANS_SOFTWARE_ING` | 软件传输中的数据处理 | 静态识别；可能写升级文件，未执行 |
| `0x0203` | `AC_TRANS_SOFTWARE_ED` | 软件传输结束及后续校验处理 | 静态识别；可能推进升级，未执行 |
| `0x0204` | `AC_RECEIVE_DATA_END` | 数据接收结束通知 | 静态识别，未真机验收 |
| `0x0301` | `AC_DOWN_CMD` | 下发普通命令，处理链可调用系统shell | 已实测固定的临时启动ADB命令；未穷举shell命令 |
| `0x0302` | `AC_DOWN_CMD_NEED_REBOOT_REQ` | 需要重启的命令请求 | 静态识别；可能写入并重启，未执行 |
| `0x0303` | `AC_DOWN_CMD_NEED_REBOOT_RSP` | 上一类命令的响应 | 静态识别，未真机验收 |
| `0x0304` | `AC_SET_DEVICE_STATE` | 设置设备状态，经适配回调处理 | 静态识别；不是只读查询，未执行 |
| `0x0401` | `AC_RESTORE_REQ` | 请求恢复操作 | 静态识别；可能清除配置或数据，未执行 |
| `0x0402` | `AC_RESTORE_RSP` | 恢复请求的响应 | 静态识别，未真机验收 |
| `0x0403` | `AC_REBOOT_DEVICE` | 重启设备 | 静态识别；未通过此动作实测重启 |
| `0xF101` | `AC_REPORT_ERROR` | 错误报告 | 静态识别，未真机验收 |

这些是协议动作，不是可以直接输入的`uptool query`、`uptool reboot`等命令行子命令。本项目没有证明原厂程序提供这些CLI语法，不能照动作名称编造用法。

`AC_DOWN_CMD`不是只允许18种固定操作的白名单：它下发的内容能进入系统shell，实际能力受运行身份、系统工具和权限约束。因此“列出全部动作码”不等于能够列完所有可能的shell命令组合。

### 已复核的参数与处理边界

- 通用报文：以太网目的MAC、源MAC、协议号之后是4字节校验；动作体头含2字节动作、4字节会话、8字节`sad`字段及2字节载荷长度，数值字段按已验证脚本使用网络字节序。不能把未知字段擅自解释为密码。
- 校验：取动作体MD5摘要的前4字节。不是加密，不是数字签名，也不是身份认证。最低长度不足60字节时补齐以太网帧。
- `0x0101`：现有脚本发送无载荷单播查询。实测`0x0102`载荷1448字节，但响应长度不是唯一成功条件，还需核对来源、动作与校验。
- `0x0301`：已验证载荷为命令编号、2字节命令长度和以零结束的命令。结构声明中的大缓冲不能当作安全输入上限：下游还按分号拆分到200字节槽。本项目固定命令少于200字节且不含分号，不扩展为任意长命令、批量命令或模糊测试。
- `0x0302`：由`ug_get_need_reboot_cmd`和`ug_handle_need_reboot_normal_cmd`处理。字段及重启时序未完成真机合同核验，不提供猜测的发送参数。
- `0x0304`：`ug_set_device_state`先解析状态，再调用适配回调。不能因名字带“状态”就把它当只读测试。
- `0x0403`：调试信息包含`reboot_info_t.delay_sec`，但仅凭内存结构不能证明完整线上序列化合同；没有把这一字段包装为可直接运行的重启工具。
- 升级、恢复相关路径还会调用适配层或厂商应用服务。uptool能接收报文，不代表这些依赖在卡启动现场都能运行，更不代表一定进入Recovery。

静态定位索引：`ug_open_socket`约`0x8cf0`、`ug_resource_init`约`0xc744`、`ug_get_normal_cmd`约`0xb328`、`ug_execute_normal_command`约`0x5aac`、`ug_send_all_normal_cmd_states`约`0xb500`、`ug_send_restore_state`约`0xe91c`、`ug_set_device_state`约`0xeb98`、`ug_reboot_device`约`0xed88`。地址只对应上述文件哈希。

## Windows实际使用

只管理自己拥有或获准维修的设备。电脑和D31应接同一有线二层网络；不同IP子网不必然阻止二层查询，但后续ADB必须有可用IP连接。普通路由VPN不是这类原始以太网通道。原厂服务没有启动、有线链路失效或引导器/内核损坏时，本方法不可用。

准备[Python 3.8或更新版](https://www.python.org/downloads/windows/)、[官方Npcap](https://npcap.com/#download)、[Android平台工具](https://developer.android.com/tools/releases/platform-tools)。下载[项目ZIP](https://github.com/VK7KSM/ChinaMobile-D31/archive/refs/heads/main.zip)，解压后在`tools/recovery`目录打开管理员PowerShell。Npcap安装可能短暂影响电脑网络。

### 1. 选择网卡和目标

```powershell
Get-Service npcap
Get-NetAdapter -Physical | Format-Table ifIndex, Name, Status, MacAddress
$nicIndex = [int](Read-Host '请输入连接D31的有线网卡ifIndex')
$nic = Get-NetAdapter -InterfaceIndex $nicIndex
if ($nic.Status -ne 'Up') { throw '网卡未连接，停止' }
$adapter = '\Device\NPF_' + ([guid]$nic.InterfaceGuid).ToString('B')
$sourceMac = $nic.MacAddress
$targetMac = Read-Host '请输入已核实的D31有线MAC'
$ip = Read-Host '请输入同一D31的有线IPv4'
$adb = (Resolve-Path -LiteralPath (Read-Host '请输入adb.exe完整路径，不加引号')).Path
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
```

结合设备标签和已知租约核实目标。多台D31时，不要仅凭名称选择，也不要把电脑MAC填成目标。邻居记录可能过期。不要选WARP、虚拟网卡或未验证的Wi-Fi适配器。

### 2. 查询设备

```powershell
python .\query_uptool.py --adapter $adapter --source $sourceMac --target $targetMac --output ".\query-$stamp"
```

允许继续的依据是收到目标的`action=0x0102`、`checksum_valid=true`及`device_info_response=true`。仅`request_sent=true`不算目标可达。脚本只发送一帧单播、等待8秒，目录必须是新目录；不扫描、不广播、不自动重试。

### 3. 临时恢复ADB

查询成功之后执行：

```powershell
python .\restore_adbd_uptool.py --adapter $adapter --source $sourceMac --target $targetMac --output ".\restore-$stamp"
& $adb -P 5042 connect "${ip}:5555"
& $adb -P 5042 -s "${ip}:5555" shell id
& $adb -P 5042 -s "${ip}:5555" shell getprop sys.boot_completed
```

脚本下发的固定内容是：

```sh
test "$(id -u)" = 0 && setprop service.adb.tcp.port 5555 && setprop ctl.start adbd
```

只在root身份下设置临时端口、启动adbd，不设置持久属性，不改变ADB认证、防火墙、应用或分区，不清数据，不重启。此次实测返回root shell；其他固件权限不能据此保证。

这个恢复动作没有直接响应，`responses=[]`不一定失败，须以ADB握手和shell结果判断。若同一D31存在旧offline连接，可执行下面两条，禁止杀掉所有ADB进程：

```powershell
& $adb -P 5042 disconnect "${ip}:5555"
& $adb -P 5042 connect "${ip}:5555"
```

`5042`是电脑ADB服务端口，`5555`是设备端口。不要停止其他设备使用的`5038`、`5041`。若adbd已经运行在其他端口，`ctl.start`未必重新监听；认证失败或启动守护随后关闭adbd也需要另行排查。

### 会话去重限制

现有查询脚本固定会话号1，恢复脚本固定会话号2。原厂服务可能忽略同一生命周期内重复会话；重新创建输出目录不会改变会话号。不要不断重复发送，也不要为重试而贸然重启设备。本轮只写文档，没有把旧脚本偷偷改成新会话管理器；后续工具需专门实现并测试这个问题。

### 其他动作怎样使用

上表其余动作全部保留，但当前公开脚本只实现`0x0101`和固定用途的`0x0301`。没有`--action`参数，不能套用不存在的命令行开关。继续实现时须先确认完整载荷、会话状态和响应条件，先做离线报文测试；查询类也要检查是否触发历史挂起操作。升级、恢复、设置和重启类必须单独确认作用范围及回滚，再决定是否上机，不能拿唯一设备试清数据。

## 其他已逆向的原厂入口

这些不是uptool动作，不能通过改动作码直接调用。这里只列本项目已经识别的入口，不声称列尽整个Android系统的所有命令。

| 入口 | 已识别能力 | 使用边界 |
| --- | --- | --- |
| `snSudoClient`、保留原件时的`snSudoClient.real` | 厂商本机root命令代理；当前管理APK首次部署使用 | 本机执行，受套接字权限影响；不是网络端口 |
| `sn` | 读取外置存储中的`opensesame`内容、设置USB序列号节点并切换`cmode` | 静态识别，不是通用命令注入；未实测外部USB设备模式 |
| `netdiag` | `socket_local_server`本机诊断入口 | 不等于远程TCP服务，没有公开远程调用教程 |
| `vsacp_server` | 抽象本机socket `wifishare_socket`；部分函数向其他设备传输文件 | 不是已验证的本机Recovery救援入口 |
| `dropbear.sh` | 脚本指定SSH端口6102 | 文件存在不代表SSH已运行，不提供未知凭据 |
| `com.starnet.systemservice`升级服务 | 外置存储查找更新包，经确认及厂商接口调用升级 | 依赖Android服务，不是上电自动卡刷 |

## 可能被怎样滥用

**风险不是“可能有人看到型号”这么轻。** 在本次样本和实测状态中，不提供管理密码或令牌也能让原厂服务执行固定root命令。恶意方若能向目标送达同类原始帧，可能利用命令执行能力读取配置和凭据、改变SIP设置、启动远程服务、破坏文件或令设备重启，继而把设备当作网络攻击跳板。后半部分是基于root权限的风险推断，未在真机实施破坏性验证。

- 主要威胁来自同二层网络中的不可信设备、访客或被入侵的电脑/物联网设备。与有线LAN桥接的Wi-Fi终端是否能送达该协议取决于AP及桥接实现，不能只因为攻击者使用Wi-Fi就认为安全。
- 普通互联网IP流量不会自动变成这种二层报文；但内部设备失陷、二层VPN或不恰当桥接可能让攻击者进入可达范围。“有路由器”不等于局域网内无人能调用。
- 目标MAC不是秘密，也不是认证；MAC白名单可能被仿冒。MD5字段只是可计算的报文校验，不能防止恶意方重新生成合法格式的报文。
- 公共恢复脚本只允许固定命令，是对使用者的误操作保护，不限制原厂服务本身。恶意方不必使用我们的脚本。
- 关闭5555、8765，或者添加普通`iptables`/`ip6tables`端口规则，不能据此认定`AF_PACKET`二层接口已封堵。端口扫描查不到uptool也是正常现象。

## 如何降低风险而不堵死救援

| 场景 | 建议 | 必须验证的边界 |
| --- | --- | --- |
| 当前开发和维修 | 保留uptool；使用只接D31与可信电脑的维修网络，或有真正二层隔离的维护网段 | 不要把访客、共享电脑和不可信设备放进同一二层范围；只更换IP段不是二层隔离 |
| 需要日常保留救援 | 可在支持的交换机或独立桥接设备上，限制到D31的`EtherType 0x9974`，仅维护通路放行 | 必须确认设备真的支持该层过滤，并测试非授权通路查询失败、授权通路仍可恢复；源MAC规则不等于密码认证 |
| 不需要常驻uptool | 先验证独立替代救援，再查明全部启动和守护来源，设计按需启停 | 单次杀进程可能被拉起；直接删程序可能丢掉最后的救援入口，不建议未验证就做 |
| 计划在D31本机过滤 | 先确认内核、驱动与实际收包路径，再对真实查询做阻断和回滚验证 | 不保证套用一条`ebtables`或普通IP规则就能挡住本机原始socket，未测试不得标为已防护 |
| 怀疑已经被滥用 | 先从共享网络隔离，接可信维修网络留证；核查新增服务、启动文件及账号配置 | root级入侵时单纯改SIP密码不足；恢复可信系统后再更换可能泄露的账号凭据 |

不能一边让原厂免认证root接口对所有同网段设备开放，一边承诺任何同网段设备都无法滥用。开发期选择保留接口，是在可恢复性与暴露面之间作出的明确取舍，不是已经修补了认证问题。

本轮仅发布说明，**没有关闭uptool、没有增加认证、没有限定管理电脑、没有修改网络或防火墙**。上述防护是可选方案，不代表已在你的D31或网络部署。

## 与8765和Recovery的关系

- uptool是原厂原生程序，走`0x9974`二层通道；仍要求内核、有线驱动和该进程运行，不需要互联网。
- [8765探针](../tools/D31-wireless-adb/README.md)是本项目新增独立root服务，走HTTP；已验证早于桌面运行及无ADB时恢复命令。它也有独立的开发期免密码风险，关闭uptool不会自动关闭8765。
- 两者都不能保证在内核、引导器或存储损坏时工作。Recovery实体入口、TF卡/U盘卡刷是另一个研究项目，目前仅记录计划，不把本页当作已验证卡刷教程。

历史故障现场与取证步骤见[以太网救援记录](D31以太网救援.md)。原始响应可能含设备或账号信息，只留在自己的电脑；公开前必须脱敏。此文没有公开本机MAC、IP、网卡标识、密钥或原始设备日志。
