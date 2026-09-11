# 2026-09-12 QUIK后台工厂独立加固增量

本目录仅交付自有Worker直接构造加固及6项回归测试，不是完整0.5.1源码，不更新工厂1.4.3或历史0.4.0覆盖层。原公开覆盖层已经包含Worker白名单；本轮去除反射不是对机内旧F-Droid 2238 APK的直接修复。不同包名不能相互覆盖。

## 组成与基线

- `data/src/main/java/com/moez/QKSMS/worker/InjectionWorkerFactory.kt`：生产加固，三个自有任务保留依赖注入，其余类名返回null并委托WorkManager默认工厂。
- `presentation/src/test/java/com/moez/QKSMS/worker/InjectionWorkerFactoryTest.kt`：真实WorkManager包装类型、默认委托、自有任务注入及独立实例6项测试；不执行后台业务。
- `test-config.patch`：仅添加Robolectric 4.10.3与离线运行库配置，不改版本、设备测试入口或生产依赖。
- `Verify-Overlay.ps1`：从本地Git对象还原上游，再按公开清单复制0.4.0覆盖层，最后只应用上述两文件及测试配置补丁。
- `files-sha256.json`：本目录文件的相对路径、字节数及SHA-256；摘要文件本身不纳入自身摘要。
- `LICENSE`：从冻结上游归档保留的GPL许可原文。
- `verification-result.json`：本次0.4.0复现的脱敏结果、测试名称及原始证据摘要；不复制JUnit中的整套环境属性。

上游仓库为QUIK，冻结提交为`555b8822c654b8ee85bb9d3f961eb30f232b1079`。覆盖层输入为发布工作树的`tools/D31-Messages/`，其`source-files.json`逐项验证通过才复制。保留`versionCode=7`、`versionName=0.4.0-dev`，不复制权威0.5.1开发树的未提交修改。代码沿用上游GPL许可证；依赖及公开覆盖层的原生资产沿用各自许可证。

本次公开覆盖层清单共335项，清单文件SHA-256为`759EA75B2A214C9EB950072410707498B73DFE3B719F81C0E71F5415F26758EA`。复现应使用这一清单及对应覆盖层；不同清单不能直接沿用本次结论。依赖沿用WorkManager 2.8.0、JUnit 4.12、Mockito 2.18.3，仅增补上述Robolectric测试依赖。

## 本地复现

需要PowerShell 7、Git、tar、JDK 17、Android SDK 34、已缓存Gradle 8.2及依赖；Robolectric运行库为`9-robolectric-4913185-2-i4`。脚本不下载工具，Gradle与Robolectric均离线执行。输出目录必须全新，且不能位于输入目录内。

```powershell
& ./Verify-Overlay.ps1 `
  -UpstreamRepository <含冻结提交的本地QUIK仓库> `
  -OverlayPackage <公开tools/D31-Messages目录> `
  -OutputDirectory <全新独立暂存目录> `
  -JavaDirectory <JDK17目录> `
  -AndroidSdk <AndroidSDK目录> `
  -GradleExecutable <已缓存gradle-8.2/bin/gradle.bat> `
  -RobolectricDirectory <已缓存9-robolectric-4913185-2-i4目录>
```

只执行`:presentation:testDebugUnitTest --tests dev.octoshrimpy.quik.worker.InjectionWorkerFactoryTest`。输出保留上游归档、覆盖层原清单、应用增量后的源码摘要、Gradle版本、构建日志、JUnit XML及结果。工具不构建APK、不使用签名、不操作设备、不写发布树。不要把包含完整源码和原始构建输出的本地暂存目录整体当作此最小增量发布。

## 验证与边界

2026-09-12独立复现已通过：6项通过、0失败、0错误、0跳过，90个构建任务全部执行，用时4分26秒。生产工厂和测试文件与本轮权威开发源码逐文件SHA-256一致，且与实际编译快照一致。除生产工厂和最小Gradle测试配置外，其余333个公开覆盖层文件保持清单哈希一致；新增测试为快照内唯一测试类。没有额外源码或依赖阻碍，没有迁入0.5.1的通知、账号、版本或设备测试入口变更。

6项覆盖真实`ConstraintTrackingWorker`旧强转异常及默认工厂委托、未知或非自有类委托、Housekeeping/SMS/MMS三个自有任务的依赖注入，以及重试创建独立实例。测试未执行`doWork`。

本地不可变证据目录为`research/d31/staging/quik-overlay040-worker-20260912-034856-738/`，保存`result.json`、`inputs.json`、`build-test.txt`、`gradle-version.txt`、`source-sha256.json`及快照中的`presentation/build/test-results/testDebugUnitTest/TEST-dev.octoshrimpy.quik.worker.InjectionWorkerFactoryTest.xml`。该目录供本地核对，不属于公开最小同步范围。实际工具为JDK 17.0.20、Gradle 8.2及Android SDK 34；既有资源格式、废弃API、非增量注解处理器警告仍保留在日志，不扩展修改。

此前权威0.5.1完整快照32项通过与本次0.4.0的6项通过是两套独立证据。测试采用Robolectric Android 9，不是API23设备执行，不是SIM/SIP新收信、通知或长期后台运行验收。旧F-Droid包已由主任务另行保留数据停用，该设备处置不与源码测试混算。

## 最小同步范围

总负责人仅需同步本目录8个文件，保留生产及测试文件的原相对路径；以`files-sha256.json`为清单。不要将本目录覆盖为完整0.5.1源码，不修改历史0.4.0覆盖层及其`source-files.json`，不复制完整开发工作树、暂存快照或构建缓存。使用时在新建的上游加0.4.0快照上应用本增量即可复现已验证的工厂6项；不能由此宣称可重建完整0.5.1 APK或历史APK的相同字节。
