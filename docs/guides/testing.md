# Testing

This project uses three test categories. The default Gradle `test` task must stay safe for CI machines without a desktop OpenGL environment.

## Unit

Pure JVM tests that do not create a GLFW window, initialize GL capabilities, compile real shaders, upload textures, or allocate real framebuffers.

Use this category for:

- data structures and descriptors
- command recording order
- asset/config parsing
- pure mesh data and model parsing
- RenderGraph topology and no-context behavior
- runtime statistics and error classification

Naming/package convention: ordinary `*Test` classes outside `com.kaleblangley.haikalat.integration`, with no special system property.

Default command:

```powershell
.\gradlew.bat test
```

## GL Smoke

Opt-in tests that create a hidden GLFW window and a minimal OpenGL context. These verify that the local graphics stack can execute basic GL work and that runtime GL resources enforce their lifecycle boundaries.

Use this category for:

- shader compile/link checks that need real GL
- framebuffer creation, resize, completeness, and lifecycle checks
- texture upload and sampler behavior
- asset cache behavior that depends on real `Texture2D` or `ShaderProgram`
- minimal pixel readback or clear validation

Naming/package convention: `*SmokeTest` under `com.kaleblangley.haikalat.integration`, guarded by:

```java
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
```

需要访问 package-private 渲染内部状态的真实 GL 回归可以与被测类同包放置，但仍必须使用相同的
`EnabledIfSystemProperty` 守卫，保证默认 CI 不创建窗口或 OpenGL context。

Command:

```powershell
.\gradlew.bat test "-Dhaikalat.glSmoke=true" --rerun-tasks
```

## Integration

Manual or environment-specific checks that exercise full demo or pipeline behavior. These may open visible windows, depend on GPU/driver behavior, or require screenshot/pixel comparison beyond a minimal smoke check.

Use this category for:

- demo startup and resize workflows
- end-to-end scene pipeline behavior
- shadow/postprocess visual validation
- longer-running frame loop checks

Naming/package convention: `*IntegrationTest` under `com.kaleblangley.haikalat.integration`, or manual demo entry points in `src/demo/java`. Integration tests must remain opt-in through a system property or a separate Gradle task if they are automated later.

CI should run only the default unit path unless the environment explicitly provides a desktop GL context.

## v0.17.2 发布与 GL 生命周期门禁

以下入口将发布工程、资源生命周期和性能证据分开，便于失败时定位：

```powershell
.\gradlew.bat assetManifestGuard releaseVerificationContracts
.\gradlew.bat glResourceLifecycleVerification --rerun-tasks
.\gradlew.bat glDebugPolicyGuard --rerun-tasks
.\gradlew.bat runSceneSubmissionStaticGate --rerun-tasks
.\gradlew.bat writePerformanceResult comparePerformanceBaseline --rerun-tasks
```

`glResourceLifecycleVerification` 覆盖 native id reuse、128 次 VAO rebuild、100 次 framebuffer resize 式
rebuild、多 context 隔离和无 context 查询。`glDebugPolicyGuard` 同时读取 `glSmoke` XML 与
`build/reports/gl/*.log`；策略来自 `config/gl-debug-policy.tsv`。任何 `GL_INVALID_*`、未授权 HIGH/MEDIUM、
过期规则、入口或消息文本不匹配、以及超过次数上限都会失败，不能用宽泛 vendor 白名单绕过。

`writePerformanceResult` 只写 `build/reports/performance/current.json`，不会覆盖已审查 baseline；
`comparePerformanceBaseline` 先比较 OS、架构、Java、LWJGL 和完整 GL identity，环境不一致时明确标记为
不可直接比较。PowerShell 下正式候选入口使用
`releaseReadiness "-PreleaseVersion=x.y.z" --rerun-tasks`；它还要求所有候选文件
已经 staged、版本/报告/changelog/capability matrix 一致，并把实际 gate outcome 写入
`build/reports/release/readiness.json` 与 `readiness.md`。它不挂到默认 `check`，也不代替远端 CI 和人工 GL 检查。

The deterministic baseline integration uses a hidden 1280x720 window, fixed camera and frame indices, disabled VSync, and exits after eight frames:

```powershell
.\gradlew.bat runDemoIntegration
```

Deterministic resize and async render-thread integrations:

```powershell
.\gradlew.bat runDemoResizeIntegration
.\gradlew.bat runMinimalIntegration
.\gradlew.bat runAnimationIntegration
.\gradlew.bat runAsyncIntegration
.\gradlew.bat runEmptyWindowIntegration
.\gradlew.bat runAutoExposureIntegration
.\gradlew.bat runStressIntegration
.\gradlew.bat runPbrIntegration
.\gradlew.bat runPbrResizeIntegration
.\gradlew.bat runPbrCompatibilityIntegration
.\gradlew.bat runPbrFailureIntegration
.\gradlew.bat runPostProcessEffectsIntegration
.\gradlew.bat runLocalShadowsIntegration
.\gradlew.bat localPbrVerification
.\gradlew.bat runGltfIntegration
.\gradlew.bat runGltfSkinningIntegration
.\gradlew.bat runGltfResizeIntegration
.\gradlew.bat runVfxIntegration
.\gradlew.bat runVfxPerformanceBaseline
.\gradlew.bat runShowcaseIntegration
.\gradlew.bat runShowcasePerformanceBaseline
.\gradlew.bat runShowcaseStabilityIntegration
.\gradlew.bat localMilestone4Verification
.\gradlew.bat localGltfVerification
.\gradlew.bat runDiagnosticsIntegration
.\gradlew.bat runDiagnosticsResizeIntegration
.\gradlew.bat runDiagnosticsFailureIntegration
.\gradlew.bat localDiagnosticsVerification
.\gradlew.bat previewGlVerification
.\gradlew.bat runPreviewIntegration
.\gradlew.bat runPreviewBenchmarks
.\gradlew.bat runSceneVisibilityIntegration
.\gradlew.bat runSceneVisibilityResizeIntegration
.\gradlew.bat runSceneVisibilityShadowIntegration
.\gradlew.bat runSceneScalabilityIntegration
.\gradlew.bat localSceneVisibilityVerification
```

Milestone 4 的纯 JVM 路径由 `AdvancedAnimationTest` 覆盖混合、Bone Mask、事件、跨循环根运动与
Two-bone IK；`UiAnimationSystemTest` 覆盖暂停/恢复、进度和诊断计数。`GpuEffectsGlTest` 使用隐藏
OpenGL 4.6 context 验证 Compute/SSBO 粒子、barrier、体积积分、实际像素与关闭后拒绝使用。
`runShowcaseIntegration` 用 640×360 / 24 帧串联完整子系统并回读最终像素；
`runShowcaseStabilityIntegration` 用 320×180 / 3600 帧证明预热后 live GL resource identity 和估算字节不增长。
受控实验的 API 与限制见 `docs/guides/milestone4-api-performance-limitations.md`。

v0.15 diagnostics integration 使用同一个主 Demo 正式管线：第一项在 12 帧 DETAILED 运行后导出
`build/diagnostics/integration.json`；resize 项验证 960×540 managed target 和保持 2048×2048 的固定阴影
target；failure 项发布一个明确的 incomplete/FAILED frame，并验证后续帧恢复和资源 live count 归零。
默认 `test` 仍只运行无桌面的 history、epoch、freeze、确定性 JSON 和 graph description 测试。

v0.17.1 preview 的 JVM 测试覆盖逻辑 key/generation、参数校验、目录、显存估算、请求 revision、
错误环和纯值 JSON。`previewGlVerification` 使用隐藏 OpenGL context 验证确定性色彩 conversion、
MRT MSAA read attachment、DEPTH24_STENCIL8 精确 resolve，以及异步 UI 遇到失效逻辑图片时跳过 draw。
`runPreviewIntegration` 打开 F2、选择 `GeometryPass/sceneColor`、导出 16 帧，并检查 1 draw/最多 1 blit、
graph topology 不变以及 JSON 不含 texture id/像素。

glTF 默认测试保持无窗口：`.gltf/.glb`、external/data/GLB embedded 资源、interleaved
与 normalized attribute、sparse accessor、node graph/transform、normal/tangent 生成、URI root
约束、skin/inverse-bind/animation、STEP/LINEAR/CUBICSPLINE、四影响权重规范化、结构化错误、
百万元素零基底 sparse accessor、sparse indices/values 完整范围、
`radio.gltf` MASK/cutoff 保留和 BLEND 拒绝都在普通 `test` 中执行。
`localGltfVerification` 额外创建隐藏 OpenGL 4.6 context，验证 embedded PNG、同一 image 的
sRGB/linear 双变体、PBR/IBL/HDR/ACES 最终像素、PBR 与方向光 shadow GPU 蒙皮、四阶段上传失败清理、library active-asset
guard、未选 MASK 场景不阻止 OPAQUE shadow instantiate，以及包含 showcase/radio/creeper 共
16 个对象和 animated two-joint fixture 的独立 Demo/resize 链路。可见窗口中按 F1 切换 inspector 交互模式，使用滚轮、
PageUp/PageDown 或 Home/End 检查长资产树，F2 隐藏面板。

`runPostProcessEffectsIntegration` 和 `PostProcessEffectsGlTest` 以隐藏窗口验证 Color Grading LUT、
距离/高度雾、深度重建、最终像素变化和 resize；雾与 MSAA 的未实现深度 resolve 组合会在分配 GL
资源前明确失败。`runUiIntegration` 同时验证 header fade、spring layout transition 和最终 UI 像素。

Milestone 3 VFX 默认测试覆盖固定种子、容量/寿命、Ribbon 采样、Decal 淘汰、透明稳定排序和
`EffectAsset/EffectInstance` 关闭顺序。`VfxRendererGlTest` 验证三类 primitive 的实际像素与 GL
资源关闭；`runVfxIntegration` 在 16 帧中 resize，`runVfxPerformanceBaseline` 以 512 粒子、240 帧
输出 CPU update、GPU pass 和 uniform payload。当前机器记录约 0.282 ms CPU、0.030 ms GPU、
44,880 B/frame uniform payload，硬件/驱动变化时应重新采样而不是视为跨机器门限。

`LocalShadowMathTest` 验证点光六面、聚光外锥和方向光级联切分/稳定矩阵；`LocalShadowsGlTest`
对同一 PBR 场景执行无阴影/有阴影像素 A/B。`runLocalShadowsIntegration` 在 1280×720 test-quality
场景串联方向光、点光与聚光，当前基线为 point 156 draws、spot 26 draws、GPU pass 约 5.77 ms。

v0.11 UI 的四条有限帧、隐藏窗口验证和聚合入口：

```powershell
.\gradlew.bat runUiIntegration
.\gradlew.bat runUiResizeIntegration
.\gradlew.bat runUiAsyncIntegration
.\gradlew.bat runUiTextIntegration
.\gradlew.bat localUiVerification
```

其中 async 入口由独立 update owner 连续发布三张 snapshot，再由 GL/window 线程消费最新值；
验证会断言 12 个 render frame 中发布 36 张、丢弃 24 张 stale snapshot，并按
render-resource → update/native-resource 顺序等待清理完成。

Run every local check that requires a desktop OpenGL environment:

```powershell
.\gradlew.bat localGlVerification
```

`localGlVerification` also depends on `localUiVerification`, runs MinimalDemo's sRGB LDR target, the deterministic CPU skeletal-animation proof, the full-GPU automatic-exposure transition, the no-draw empty-window check, the 100000-instance triangle/quad/flattened-cube checks, and dedicated indexed Cube plus indexed+compact-SSBO Cube integrations. It is intentionally not attached to the default `check` task, so headless CI remains safe.

The current GL smoke path additionally verifies project shader compilation, a compute dispatch writing through a named SSBO, an indexed procedural Cube drawn from a compact SSBO with no VBO, aligned persistent mapped compact-ring slots and dirty-range propagation, pending-state collapse/custom barriers, depth-mask-controlled depth clear, opaque/transparent ordering across RenderGraph passes, depth-only framebuffer writes and shader sampling, resource use-after-close behavior, linear/sRGB texture sampling, single-encoded mid-gray output across all LDR AA modes and HDR/ACES, RGBA16F values above 1.0, ACES exposure changes, R16F log-luminance plus RG32F sum/weight reduction/history, 9×1 and 1280×1 edge weighting, a 1×1-to-47×33 resize topology regression, fullscreen state ownership, HDR resize, disabled/enabled Bloom pixels across all four AA paths, the 40-case legal UI/pipeline matrix, the complete scene-to-shadow-to-lighting pixel chain, opt-in instanced shadows with one upload reused by two passes, reverse cleanup after a geometry-stage failure, next-frame ring reuse, an async UBO upload that drives instanced final pixels, PBR device-cache recovery after runtime environment preprocessing, shared legacy/PBR model variants, canonical tangent fail-fast, and three injected IBL preprocessing cleanup stages. Unit tests verify primitive-only state packets, boundary separation, relative target sizing, packed-instance quantization, uint8 topology/winding, HDR/Bloom/LDR-present pass order, exposure/Bloom validation, frame-rate-independent adaptation, topology-preserving dynamic light replacement, ACES reference behavior, range-limited inverse-square PBR falloff, sRGB/float framebuffer metadata, and synchronization between generated GLSL and the Java catalog.

Windows 上同一开关还会启用 `Win32TextInputAdapterSmokeTest`：它只验证隐藏 GLFW 窗口的
WndProc hook 安装、消息链和恢复，不会自动打开真实输入法。Microsoft Pinyin 的候选、DPI、
焦点和多显示器矩阵见 [Windows IME 适配与验收](windows-ime.md)，发布前必须手工记录。

正式自动曝光性能复现：

```powershell
.\gradlew.bat runAutoExposureBenchmarks
```

该任务在 1080p 与 4K 下分别测试 MANUAL/AUTO，每项执行 5 轮，每轮预热 100 帧并统计 1000 帧。

正式 UI 容量性能复现：

```powershell
.\gradlew.bat runUiBenchmarks
```

该任务在 1080p/4K 下分别运行 100 nodes、1,000 nodes、10,000 logical virtual list、
10,000 quads 和 2,000 visible CJK/Latin glyphs。每项执行 5 轮，每轮记录 cold frame、
预热 30 帧并统计 120 帧；当前基准见
[`docs/performance/v0.11-ui-2026-07-16.md`](../performance/v0.11-ui-2026-07-16.md)。

正式 PBR 1080p/4K 五轮性能矩阵：

```powershell
.\gradlew.bat runPbrBenchmarks
```

每个场景预热 100 帧并统计 1000 帧，覆盖 NONE/FXAA/MSAA/TAA 及 manual/auto exposure、
Bloom off/on 的代表组合。当前代表性 FXAA+AUTO+Bloom 五轮记录见
[`docs/performance/v0.12-pbr-2026-07-17.md`](../performance/v0.12-pbr-2026-07-17.md)。

v0.13 glTF 五轮 decode/upload 与 1080p/4K 稳态矩阵：

```powershell
.\gradlew.bat runGltfBenchmarks
```

decode/upload 直接使用原始 MASK `radio.gltf`，不生成或改写 OPAQUE 副本；GPU upload 单独测量
sampler、sRGB image、material 与 mesh 创建，稳态部分使用主 Demo 的 glTF/OBJ/legacy
共存场景，每轮预热 100 帧并正式统计 1000 帧。
当前本机结果见 [`docs/performance/v0.13-gltf-2026-07-18.md`](../performance/v0.13-gltf-2026-07-18.md)。

v0.16 普通 renderer 可见性门禁保持默认 `test` 无桌面：bounds、world AABB、六平面 frustum、
TAA stable projection、primitive stable queue 和 10,000 index sort 都是纯 JVM 测试。
`localSceneVisibilityVerification` 额外创建隐藏 OpenGL 4.6 context，验证 camera/shadow 独立 queue、
enabled/disabled 像素一致、resize aspect、固定 2048×2048 shadow target、updater 单次执行、
失败帧恢复和 10,000 renderer 的 90% draw reduction。

正式 scene scalability 五轮矩阵：

```powershell
.\gradlew.bat runSceneScalabilityBenchmarks
```

默认在 1920×1080 与 3840×2160 分别执行 100/1,000/10,000 的 enabled/disabled 配对，
并补充 10,000 all-visible/all-hidden；每轮预热 100 帧、统计 1000 帧。输出 present FPS、CPU/GPU
平均和中位数、SceneFrame 各阶段时间、进程级 allocation、draw 和 state-cache skip ratio。
当前本机结果见 [`docs/performance/v0.16-scene-visibility-2026-07-19.md`](../performance/v0.16-scene-visibility-2026-07-19.md)。

v0.17 scene submission 门禁：

```powershell
.\gradlew.bat localSceneSubmissionVerification --rerun-tasks
.\gradlew.bat runSceneSubmissionBenchmarks
```

前者串行执行 revision/matrix arena JVM 测试、真实 GL queue/TAA/pixel parity、共享资源 glTF 1k、
resize、camera-motion、10k allocation 和 static/all-hidden 0.15 ms 门禁。后者对程序化与真实 glTF 的
100/1k/10k compat/optimized 路径各运行五轮，每轮预热 100 帧并采样 1,000 帧。结果见
[`docs/performance/v0.17-scene-submission-2026-07-19.md`](../performance/v0.17-scene-submission-2026-07-19.md)。
