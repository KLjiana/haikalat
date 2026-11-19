# Testing

2026-11-09 文档复核，适用当前 0.27.4 工作树。本文只列当前入口与选择方法；具体验收规则见[有侧重验收规范](focused-acceptance-policy.md)。0.27.5 已落地 `check` 去重、默认 GL 叶子与测试合同索引；自动按 diff 选测仍是[计划](../planning/v0.27.5-agent-and-test-governance.md)，不是已实现功能。

## 日常选择

以下入口按目标选择，不是需要依次运行的清单。普通功能验收默认不强制 benchmark，限时脚本默认 900 秒（2026-11-17 起；Focused 范围已增长到 1400+ 用例，冷候选实测约 450 秒）、零自动重试。纯文档修改检查链接和事实一致性即可。

| 目标 | 当前入口 | 范围 |
|---|---|---|
| 一个 JVM 行为 | `gradlew.bat test --tests "*RenderFeatureControlTest"` | 替换为本次实际受影响的类或方法；不创建 GL context |
| 一个真实 GL 行为 | `gradlew.bat glSmoke --tests "*RenderFeatureControlGlTest"` | 需要桌面会话和 OpenGL 4.6；保持过滤器，检查实际执行数和日志 |
| 全部 GL 守卫类（无 JVM） | `gradlew.bat glLeafVerification` | 从测试源码派生 GL 类，排除 soak/benchmark；真实 GL，单 fork；不是 `glSmoke` 的别名 |
| 限时 GL 叶子 | `.\verify.ps1 -Mode Gl -TimeoutSeconds 300` | 包装 `glLeafVerification`；写带候选身份的限时报告 |
| Render3D 聚焦 JVM 与门禁 | `verify.ps1 -Mode Jvm` | 固定范围，非自动 diff 选测 |
| Render3D 聚焦 JVM/GL 与门禁 | `verify.ps1` | 固定合同与有限 GL 用例；不包含完整性能、视觉或长测 |
| 全部无桌面检查 | `gradlew.bat check` | 比定向验收更广；已去掉重复的过滤 JUnit guard 执行，guard 仍可单独调用 |
| 预览任务依赖 | `gradlew.bat <实际任务名> --dry-run` | 只检查任务图，不产生功能通过证据 |

Windows 使用 `.\gradlew.bat`、`.\verify.ps1`。其他平台使用 `./gradlew` 的实际任务；当前限时脚本面向 Windows，不宣称已验证跨平台进程树终止。

默认使用增量构建。不要惯例性叠加 `clean`、`--rerun-tasks` 或在聚焦验收后追加所有 `local*Verification`。需要新执行证据时才选择性重跑；Gradle UP-TO-DATE 与可复用验收证据不是同一件事。

<a id="当前工作树的快速逻辑验收2026-10-08"></a>

## Render3D 聚焦入口

```powershell
.\verify.ps1
# 或只验证 JVM 与门禁
.\verify.ps1 -Mode Jvm
```

对应 Gradle 入口为 `acceptance` / `render3dLogicVerification` 和 `logicVerification`。实际 case 清单唯一来源为 [focused-verification.gradle](../../gradle/focused-verification.gradle)，不要在指南再维护一份类数清单。

0.27.4 最近记录执行 116 个 JVM、50 个 GL 用例，包含统一控制、发布后退役一致性和失败请求不重试；这只是该候选的实测数量，新增覆盖后以结构化报告为准。证据见[0.27.4 验收](../releases/v0.27.4-review.md)。

聚焦任务显式启用 GL/readback 和体积诊断，并核对必需类/方法的执行事件。不能用 `--tests` 缩窄聚焦合同；临时探查用普通 `test` 或带过滤的 `glSmoke`，不冒充完整聚焦验收。

## UI Compositor 定向入口

```powershell
.\verify.ps1 -Mode UiConsumers -TimeoutSeconds 300
# 独立选择缓存长测，不与上一条惯例性叠加
.\verify.ps1 -Mode UiSoak -TimeoutSeconds 300
```

局部改动可选择 `UiJvm`、`Ui` 或 `UiDemos`。`UiConsumers` 包括核心 JVM/GL、四个 Demo、VLS/Modern 消费者与相关门禁；不能据此签收人工输入体验或正式性能。详细能力见 [UI Compositor 指南](ui-compositor.md)。

## 编写与组织测试

按行为合同组织测试，禁止把“新增生产类”作为必须新增测试类的理由。优先扩展已有领域测试；简单默认值、getter、无分支委托无需独立用例，除非它们承载公开兼容或真实缺陷回归。

纯 JVM 测试不创建 GLFW window、GL capabilities、真实 shader/texture/framebuffer。真实 GL 用例使用明确的任务和守卫：

```java
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
```

GL 类可以在 integration 包，也可以因包内状态访问需求与生产类型同包；名称中不强制只有 `SmokeTest`，现有 `*GlTest` 同样适用。默认 `test` 不开启 GL；GL 被守卫跳过不能作为 GPU 验收通过。

需要像素证据的任务必须确认 readback 实际开启并有断言，不能只检查窗口成功创建。保留独立数值参考、候选事务、历史失效、resize、关闭、宿主借用和已修复故障的必要覆盖；不要复制生产 helper 来计算唯一期望值。

故障注入默认关闭且只能由测试显式设置。真实 GL 提交失败恢复用例使用一次性
`haikalat.test.failSubmissionAfterPass=<passName>`：`RenderGraph` 在全部 pass 回调录制完成、
`device.execute` 之前抛出，属性触发后立即清除，因此 GPU 不会看到该帧的任何命令，生产路径
从不设置。测试必须在 `finally` 恢复原值，并断言失败帧确实没有执行录制命令。

## 失败与证据

- 限时脚本报告：`build/reports/bounded-verification/<runId>/result.json`，以及该次 stdout/stderr。
- Render3D 合同报告：`build/reports/logic-verification/<runId>/report.json` 与归档 JUnit XML。
- UI 报告：`build/reports/ui-compositor-v0260/<runId>/`，包含候选、实际用例、日志和场景证据。
- 普通定向测试 XML：`build/test-results/<task>/`；后续执行可能覆盖，引用前按运行身份归档。

FAIL、TIMEOUT、INVALID、空选择、意外 skip、NOT_RUN 和输入变化均不能计为 PASS。失败后先定位产品、测试或环境问题；只有确认可恢复的启动/环境故障才允许一次定向复跑，同故障再次出现停止盲目重跑。修复后运行受影响用例及直接消费者。

`verify.ps1` 使用独立单次 Gradle 进程树实施总体时限；直接 Gradle 只有任务级 timeout。仅终止本次启动的进程树，不按 Java 进程名称清理其他任务。JVM 无法启动的环境问题记为 `INVALID`（exit 2），不与测试 FAIL 混同。正确性、性能与发布就绪分开报告，聚焦报告的 `releaseReady=false` 不等于聚焦测试失败。

`result.json` 绑定候选**内容**而不是仅提交号：

| 字段 | 含义 |
|---|---|
| `buildVersion`／`gitHead`／`workingTreeEntries` | 版本、提交与未提交条目数 |
| `workingTreeSha256`／`workingTreeFileCount` | 运行开始时的内容指纹；同一提交下改一个字节即变化 |
| `workingTreeSha256After`／`workingTreeFileCountAfter` | 运行结束时的内容指纹 |
| `candidateStable` | 前后指纹一致为 `true`；验收期间候选被修改则为 `false` 并判 FAIL |
| `evidence` | 本次归档的 JUnit XML：`archive`、`files`、`tests`、`executed`、`skipped`、`failures`、`sha256`、`reused` |
| `evidenceFreshness` | `FRESH`／`REUSED_TASK_OUTPUT`／`MISSING` |

归档目录为 `build/reports/bounded-verification/<runId>/junit/`。`executed==0`（全跳过或空运行）一律判 FAIL；`-Mode Gl` 还要求 `skipped==0` 且证据必须是本次新执行（该模式固定加 `--rerun-tasks`，UP-TO-DATE 的旧 XML 会判 `FAIL`）。**运行前后内容指纹不一致时同样判 FAIL**，防止验收期间修改候选却仍报 PASS；这是开始／结束两次快照的比较，若改动在结束前被还原则不会报错，验收期间不要改候选文件。Gradle 侧同样有门禁：`glLeafVerification` 在 `doLast` 校验 JUnit XML，全跳过会直接让任务失败。

Windows 上 `@TempDir` 偶发 `Failed to delete temp directory` 属于外部句柄占用系统临时目录的环境故障，不是断言失败。共享 Test 管道已把 fork 出的测试 JVM 的 `java.io.tmpdir` 指向 `build/tmp/junit`，让这类噪声留在 `build/` 内；断言与失败语义不变。

## 扩展验证与历史任务

长测、正式性能、全视觉矩阵和完整发布按明确需求单独安排。扩展入口需要 `-PextendedVerification=true`；它只是允许执行，不表示结果被豁免。

```powershell
# 仅在明确选择完整发布验证时运行；它有独立元数据、Git 与性能要求
.\verify.ps1 -Mode Extended -TimeoutSeconds 1800
```

`releaseReadiness`、`localGlVerification`、`localReleaseVerification` 等是历史宽范围聚合入口，不能由名称推断成本。无过滤的 `glSmoke` 仍执行完整测试集（含 JVM）；默认 GL 叶子改用 `glLeafVerification`，它只跑 GL 守卫类并排除 soak/benchmark。合同分组见[测试合同索引](test-contracts.md)。

旧 `runOutdoorEnvironment*` / `localOutdoorVerification` 已退役。当前天空和体积雾用法见[室外迁移说明](render3d-outdoor-environment.md)和[体积雾指南](render3d-volumetric-fog.md)，旧算法的复现前提见[历史参考](outdoor-reference.md)。

历史多轮 1080p/4K、IME、完整 AA 或全部 subsystem 的命令清单已从本指南删除。原候选结果仍保存在 [performance](../performance/) 和 [releases](../releases/)；它们不是每个新版本的执行义务，也不能改写成新候选性能达标。

## 专项资料

- [材质、阴影与 GTAO](render3d-materials-and-shadows.md)、[体积雾](render3d-volumetric-fog.md)、[嵌入宿主](embedded-rendering.md)。
- [测试合同索引](test-contracts.md)、[Demo 职责与入口](demo-responsibilities.md)、[诊断](diagnostics.md)、[Windows IME](windows-ime.md)。
- [证据复用与范围升级](focused-acceptance-policy.md)、[资源所有权](../architecture/resource-ownership.md)、[公共 API](../architecture/public-api/README.md)。

资产以 [assets.tsv](../licenses/assets.tsv) 的实际字节与来源为准；Windows 的指纹文本遵守 `.gitattributes`。不能用更新 hash 或参考图掩盖无意换行/资源变化。
