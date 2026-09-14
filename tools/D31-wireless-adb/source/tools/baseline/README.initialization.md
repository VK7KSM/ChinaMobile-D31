# 初始化完成条件旁注

复用同目录 `compare_baseline.py` 和原 `DiagnosticManifest/DiagnosticCoverageComparison`。接收一至五份原采集 manifest 及一份原固件 manifest；不接受原始shell文本，不改原九项目录、客户端或协议。

```powershell
python -B tools/baseline/interpret_initialization.py --observation $原采集清单 --firmware $原固件清单 --java-home $Java目录 --json-jar $Json依赖 --output $全新私有目录
```

可重复传入 `--observation`，序号对应输出的 `inputIndex`。输出目录由调用者明确指定，可位于工程外，父目录须已存在；已有目录、文件均拒绝覆盖。输出包含私有规范化输入，应按原证据包权限保管。

原宿主完成原件、回执与身份绑定核验后调用。`coverage-N-private.json/md` 是原比较器报告；`completion.json` 是另存的事实旁注，不能作为 manifest 或修复输入。`provenance.json` 保留原输入摘要、包装指针和实际源码摘要。

只解释五叶：三个初始化标记及 `start.sh/handover.jar`。两项正式1.4.4消费者摘要都匹配才能投影标记门条件；旧2026-09-11 JAR仅识别来源，保持 `MISMATCH/UNKNOWN`。未知不判缺失，分批叶数据不拼成同时状态，安装前期望不生成运行期修复，始终 `repairPlanGenerated=false`。单份完整条件解释也不代表初始化成功、当前权限或实际执行。

```powershell
python -B tools/baseline/test_interpret_initialization.py --java-home $Java目录 --json-jar $Json依赖 --output $全新测试目录
```

测试输出须选工具目录外的新目录，以验证工程相对依赖定位与外置私有输出；测试只用合成清单，不访问设备。
