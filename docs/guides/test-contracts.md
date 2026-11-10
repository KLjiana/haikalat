# 测试合同索引（0.27.5）

记录日期：2026-11-09（2026-11-10 重建并补入证据绑定规则）。本文是当前测试组合的合同级索引，
不是全部 JUnit 方法清单；精确 focused case 清单的唯一来源仍是
[focused-verification.gradle](../../gradle/focused-verification.gradle)。执行命令和失败语义见
[测试指南](testing.md)与[有侧重验收规范](focused-acceptance-policy.md)。

## 入口

| 入口 | 范围 | 成本／前提 | 用途 |
|---|---|---|---|
| `test --tests "*Class"` | 单个 JVM 行为 | 无 GL；增量 | 局部逻辑、策略、解析、状态机 |
| `glLeafVerification` | 仅 GL 守卫类；从测试源码派生，排除 `*Soak*`／`*Benchmark*` | 真实 OpenGL 4.6；约 314 tests／61 suites；本机约 67–90 秒 | GL 回归的默认叶子，不执行 JVM 套件 |
| `glSmoke --tests "*Class"` | 指定 GL 类（可含 JVM 类） | 真实 GL | 临时探查或单类复现 |
| `glSmoke`（无过滤） | 完整测试集，含 JVM 与 GL | 真实 GL；约 1150 cases | 兼容旧命令；**不是最小 GL 入口** |
| `verify.ps1` | focused JVM＋GL＋门禁 | 300 秒总体限时；GL | Render3D 日常验收（默认 Focused） |
| `verify.ps1 -Mode Jvm` | focused JVM＋门禁 | 300 秒；无 GL | 只验证逻辑与合同 |
| `verify.ps1 -Mode Gl` | 包装 `glLeafVerification` | 300 秒；真实 GL | 限时 GL 验收＋证据归档 |
| `verify.ps1 -Mode UiConsumers` | UI JVM/GL、四例回放、VLS/Modern、门禁 | 300 秒；GL | UI 消费者验收 |
| `check` | 完整 `test`＋动画 Demo 集成＋非 JUnit 门禁 | 无 GL；约 45–70 秒 | 无桌面完整检查 |
| `verify.ps1 -Mode Extended` | `releaseReadiness` 历史发布门 | 需 `-PextendedVerification=true`；独立性能／Git 要求 | 发布准备，不是日常入口 |

`glLeafVerification` 是无过滤 `glSmoke` 的明确 GL 叶子；旧入口保留兼容，不静默改变范围。
soak、benchmark、质量矩阵与多硬件仍走各自扩展任务，不进入默认路径。

## 合同分组

| Case ID／组 | 合同 | 层级 | 主叶子入口 | 成本 | 扩展触发 | 状态 |
|---|---|---|---|---|---|---|
| `FEATURE-JVM` | 统一功能策略、HDR／PBR／雾／VFX 依赖、revision 状态 | JVM | `RenderFeatureControlTest` | 秒级 | 新增模块开关或策略规则 | KEEP |
| `FEATURE-GL` | pause／resume、批量提交、退役失败一致、失败终结、postprocess／shadow／direct／VFX／preview | GL | `RenderFeatureControlGlTest` | 秒级 | 控制入口、owner 或事务变更 | KEEP |
| `COMBAT-VFX` | 轮廓／UV Flow／溶解组合、效果灯身份与门控、作者 schema、资源准备 | JVM＋GL＋Showcase | `VfxMaterialTest`、`EffectInstanceTest`、`VfxRendererMathTest`、`VfxLightBridgeTest`、`CombatVfxProfileCodecTest`、`VfxRendererGlTest`、`VfxTextureCacheGlTest`、`VfxLightingGlTest`、`runShowcaseIntegration` | 秒级至分钟 | VFX 材质、灯桥接、Showcase 配置或 shader 变化 | KEEP（0.28 新增） |
| `FOCUSED-JVM` | Render3D 领域数学、拓扑、设置、报告合同 | JVM | `verify.ps1 -Mode Jvm`／`focusedRender3dJvmTest` | 约 116 tests | 领域合同或 guard 变化 | KEEP |
| `FOCUSED-GL` | Render3D 代表像素、资源与生命周期 | GL | `verify.ps1`／`focusedRender3dGlTest` | 约 50 tests | 渲染路径、shader、共享表面变化 | KEEP |
| `GTAO` | 解析参考、时域相位、共享 depth、history 失效 | JVM＋GL | `GtaoModularityGlTest`、`GtaoShaderGlTest`、`GtaoHistoryResizeGlTest` | 秒级 | GTAO 设置、历史、表面需求变化 | KEEP |
| `VOLUME` | 体积雾网格、时域、合成、owner 回收 | JVM＋GL | `VolumetricModularityGlTest`、`VolumetricTemporalGlTest`、`VolumetricCompositeGlTest` | 秒级至分钟 | 体积雾实现、shader、共享表面变化 | KEEP |
| `SHADOW` | 方向光／点光／聚光调度、图集、缓存、像素 | JVM＋GL | `LocalShadowsGlTest`、`VolumetricShadowGlTest`、`ShadowLightSchedulerTest` | 秒级 | 阴影容量、调度、采样变化 | KEEP |
| `SURFACE-TAA` | SceneSurface 需求并集、TAA 历史、resize | JVM＋GL | `SceneSurfaceResolveGlTest`、`TaaHistoryGlTest`、`TemporalTransactionGlTest` | 秒级 | 表面通道、TAA 或 resize 变化 | KEEP |
| `POSTPROCESS` | Bloom／曝光／ACES／FXAA／雾／调色顺序 | JVM＋GL | `PostProcessEffectsGlTest`、`AutoExposureGlTest`、`RenderPipelineGlTest` | 秒级 | 颜色链、全屏 pass 变化 | KEEP |
| `UI` | layout、input、text、compositor、cache、attachment 生命周期 | JVM＋GL | `verify.ps1 -Mode UiConsumers`；叶子见 UI 指南 | 约 67 tests／场景 | UI 公共 API、合成、输入变化 | KEEP |
| `HOST` | 外部 target、外部相机、GL 状态恢复、UI＋3D 同目标 | JVM＋GL | `EmbeddedPresentationGlTest`、`embeddedGlVerification` | 秒级 | `PresentationTarget`／宿主合同变化 | KEEP |
| `ASSETS` | glTF、场景 schema、资源身份、动画／skin／morph | JVM＋GL＋Demo | `localSceneAssetVerification`、glTF 专项入口 | 分钟级 | 资源格式、加载、GPU 上传变化 | KEEP |
| `API-ARCH` | 公共 API allowlist、依赖方向、GL 逃逸、迁移记录 | JVM | `publicApiGuard`／`architectureGuard`（单独） | 6／18 tests | 新 public 类型、包边界、GL 调用变化 | KEEP（`check` 中由 `test` 覆盖） |
| `BUILD` | release 脚本约束、资产 policy、focused 报告语义 | 构建合同 | `releaseVerificationContracts`、`focusedVerificationContracts` | 秒级 | 构建脚本、报告、过滤变化 | KEEP |
| `LEGACY-GLSMOKE` | 旧完整套件入口 | 全部 | `glSmoke`（无过滤） | 约 1150 cases | 仅兼容或诊断 | KEEP-LEGACY（默认 GL 改用 `glLeafVerification`） |
| `EXTENDED` | soak、正式性能、视觉质量、多硬件 | 扩展 | 各自 `-PextendedVerification=true` 任务 | 分钟至小时 | 明确性能／封版申请 | KEEP-EXTENDED |

`KEEP` 表示合同仍需独立验证；`KEEP-LEGACY`／`KEEP-EXTENDED` 表示入口保留但不应进入日常默认路径。
断言级删除必须等替代覆盖实际执行并记录。

## 证据必须绑定候选内容

提交号和未提交条目数无法区分同一文件的不同修改，因此限时脚本记录**内容指纹**：

- `workingTreeSha256`：对 `git ls-files --cached --others --exclude-standard` 的路径（去重排序）
  逐个写入「相对路径 + 0x00 + 文件内容 SHA-256 + 0x00」后取总 SHA-256；
- `workingTreeFileCount`：参与指纹的文件数；
- `workingTreeSha256After`／`workingTreeFileCountAfter`：运行结束时重新计算；
- `candidateStable`：前后一致为 `true`；不一致说明验收期间候选被修改，直接判 FAIL。

同一提交、同一「修改文件数」但内容不同时，指纹必须不同（已实测：323 条目／2159 文件不变，
指纹由 `76c481d8…` 变为 `6e091832…`）。验收期间修改任一文件同样会被识别：
实测 `candidateStable=False`、`status=FAIL`，`error` 记录前后指纹。
该检查是开始／结束两次快照的比较；若改动在结束快照前被还原，指纹回到原值，不会报错。

GL 等模式的 JUnit XML 会被复制到本次运行目录的 `junit/`，并在 `result.json.evidence` 记录：

| 字段 | 含义 |
|---|---|
| `archive` | 归档目录绝对路径（证据随运行保留） |
| `files` | 归档 XML 数 |
| `tests`／`executed`／`skipped`／`failures` | 实际 case 统计 |
| `sha256` | 归档文件的路径与内容哈希汇总 |
| `reused` | 归档 XML 是否早于本次运行开始（UP-TO-DATE 复用） |

`result.json.evidenceFreshness` 汇总为 `FRESH`／`REUSED_TASK_OUTPUT`／`MISSING`。
`-Mode Gl` 要求 GL 证据必须是本次新执行：该模式固定追加 `--rerun-tasks`，若检测到复用则判
FAIL 并写入 `error`（负例已实测）。

## 空选择与全跳过不能算通过

- Gradle 侧：`gradle/test-evidence.gradle` 提供 `junitSummary` 与 `requireExecutedEvidence`。
  `glLeafVerification` 在 `doLast` 校验：无 XML、`tests==0`、`executed==0`、或 `skipped>0`
  都抛错，任务失败。
  负例实测：强制关闭 GL 守卫后 314 个 case 全跳过，任务失败并报告
  `glLeafVerification: all 314 cases were skipped`。
- 包装脚本侧：`verify.ps1` 归档证据后要求 `executed>0`；`-Mode Gl` 还要求 `skipped==0`，
  否则判 FAIL 并写入 `failure`。
- `releaseVerificationContracts` 直接断言这两个门禁：空目录与全跳过 fixture 必须被拒绝，
  健康 fixture 通过。

## 构建脚本职责

无桌面验证入口（`publicApiGuard`、`architectureGuard`、`sceneJvmVerification`、
`dependencyLockGuard`、`embeddedJvmVerification`、`embeddedGlVerification`）已从根
`build.gradle` 迁到 `gradle/verification-tasks.gradle`，根文件只保留插件、工具链、依赖、
source set、标准产物与脚本入口（324→181 行）。任务数与 `check` 图不变；`localGlVerification`
仍包含 `embeddedGlVerification`。

## 构建脚本重复配置

四行 JUnit 管道（`dependsOn testClasses`、`testClassesDirs`、`classpath`、
`useJUnitPlatform()`）原先在 `demo-tasks`、`scene-tasks`、`ui-tasks`、`diagnostics-tasks`
的 28 个 Test 任务中重复。现由 `gradle/verification-tasks.gradle` 顶部一份
`tasks.withType(Test).configureEach` 提供默认值，任务只保留 filters、系统属性、超时和
显式 classpath 覆盖；累计删除 104 行重复声明，任务数 296 与 `check` 图 28 均未改变。

## GL 日志策略

`config/gl-debug-policy.tsv` 的解析与审计（`parseGlPolicy`、`auditGlText`、`auditGlFiles`）
位于 `gradle/gl-policy.gradle`，与任务装配分离；`release-verification.gradle` 通过 `ext`
引用，`releaseVerificationContracts` 断言其行为，GL Test 任务在 `doLast` 中用同一策略审计日志。

## 首批断言级审计结论

盘点口径（2026-11-09 的 `check` XML）：252 个测试类、1150 个 case、其中 328 个是未开启
GL 时的预期 skip；单 case 类 50 个、双 case 类 50 个。按合同覆盖、层次和诊断价值逐组判断：

| 候选 | 判定 | 理由 |
|---|---|---|
| `RenderPipelineTest` 22／`RenderPipelineGlTest` 23 | KEEP（分层） | 前者纯 JVM 拓扑、策略与生命周期；后者真实 GL 像素、readback 与资源对账，不可互换 |
| `ScenePipelineTest` 14／`SceneVisibilityGlTest` 10 | KEEP（分层） | JVM 可见性与队列排序 vs 真实相机/阴影/resize 像素回归 |
| `VolumetricSettingsTest` 3／`VolumetricGridTest` 6／`VolumetricAllocationPlanTest` 1 | KEEP | 前者覆盖多配置类的拒绝、NaN/Inf 与防御性拷贝；网格数学与分配预算各自独立，不能由 shader 编译替代 |
| `DirectionalCascadeSettingsTest` → `PipelineTopologyTest` | MERGE（已完成） | 级联图集布局是阴影拓扑的一部分，且拓扑测试本就构造 `DirectionalCascadeSettings` |
| `DepthResolveDescriptorTest` → `SceneBufferRequirementsTest` | MERGE（已完成） | MSAA＋雾的深度 resolve 描述就是该 surface 布局的输入校验 |
| `FrameTimingAccumulatorTest`＋`PassBenchmarkAccumulatorTest` → `TimingAccumulatorTest` | MERGE（已完成） | 同属运行时计时累加合同：有界样本、稳定 pass 名、缺失 GPU 样本 |
| `RenderQueueClassTest` → `RenderQueueSorterTest` | MERGE（已完成） | 分类与阴影投射资格和排序同属 render queue 合同 |
| `CameraVisibilityRevisionTest` → `TransformRevisionTest` | MERGE（已完成） | 两类都承诺“只有影响渲染的输入变化才推进 revision” |
| `UniformBlockTest` 等其余单 case 类 | KEEP | 逐类抽查为单一行为边界（UBO 对齐数学、纹理存储拒绝等），未发现仅复制实现逻辑的断言 |
| `ArchitectureBoundaryTest.render3dKeepsSceneAndShadowStoragePrivate` | REWRITE（已完成） | 源码文本匹配改为反射类型边界，去掉对注释/命名/import 的偶然耦合 |

结论：冗余主要在构建执行层（重复 JUnit 管道、`check` 重复子集、源码形状断言）；断言语义层
通过 5 组同域 `MERGE` 把 6 个单 case 类并入更强的领域测试，全部断言保留，`check` 仍为
1150 个 case、0 失败，测试类 252→247。不设删除配额，也不为凑数量合并行为不同的合同。

## 不稳定用例与平台噪声

- `SceneAssetServiceTest.reloadWhileHandleIsClosingCompletesCleanly` 曾间歇失败：
  `closeManaged` 在 `CompletableFuture.cancel(true)` 收到 `CancellationException` 并抛给调用方。
  修复为关闭路径捕获取消阶段的并发异常并继续清理；handle 已移出 `managedScenes`，不能发布新计划。
- Windows 上 `@TempDir` 偶发 `Failed to delete temp directory`：外部句柄短暂占用系统临时目录，
  用例本身只用 `Files.*` 且无未关闭流。JUnit 5.10 无关闭清理的配置项，因此不改断言、不升级依赖，
  而是把测试 JVM 的 `java.io.tmpdir` 指向 `build/tmp/junit`，把噪声限制在 `build/`。
  该故障仍按环境问题记录，不算 PASS 依据。

## 失败、超时与空选择

- `verify.ps1` 使用单次独立 Gradle 进程树；超时后只终止本次进程树，写 `TIMEOUT` 报告。
  已修复超时清理与后续 `taskkill` 竞态：进程已退出时不再让清理异常覆盖报告。
- 本机实测 `verify.ps1 -Mode Jvm -TimeoutSeconds 1` → `status=TIMEOUT`、`exitCode=124`、
  `elapsedSeconds≈1.4–1.5`；正常 `-TimeoutSeconds 300` → `PASS`。
- JVM 启动环境错误（如非法 `JAVA_TOOL_OPTIONS`）记为 `INVALID`／exit 2，而不是测试 `FAIL`；
  该次运行仍写出带候选身份的报告。
- `check` 的 `test` XML 中约 328 个 skip 是未开启 GL 时的 `@EnabledIfSystemProperty` 预期排除；
  `glLeafVerification` 的 314 个 GL cases 为 0 skip。预期分层排除与必需用例的意外 skip
  分开报告，不混为“全绿”。
- 直接 `test --tests` 空匹配由 Gradle 报错；不得把空选择记 PASS。
- 默认零自动重试；只有确认可恢复的启动／环境故障允许一次定向复跑。

## 维护规则

1. 新增 GL 类仍使用 `haikalat.glSmoke` 守卫；`glLeafVerification` 自动纳入，不需要手工补任务。
2. 新增 soak/benchmark 类必须在名称中体现，避免进入默认 GL 叶子。
3. 新增或迁移 case 后核对 focused 清单、`check`、报告核对和本文链接。
4. 删除或合并断言前记录 `MERGE`／`RETIRE` 的合同、替代证据和实际执行结果。
5. 报告实际执行 case 与候选身份；`UP-TO-DATE`、历史复用和新执行分开说明。
