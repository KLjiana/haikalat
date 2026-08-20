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

v0.22.1 文本专项的 JVM 合同集中在 `MarkdownParserTest`、`TextSystemTest`、`UiSystemTest`
与 `ArchitectureBoundaryTest`。它们覆盖 Markdown 子集/容量、字体注册失败回滚、重复或延迟 close、
默认/显式字体失效范围，以及 GL-free/适配层边界。

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

灯光缓存的聚焦真实 GL 回归可单独执行：

```powershell
.\gradlew.bat glSmoke --tests "*SceneVisibilityGlTest.lightAndCameraChangesInvalidateTheRequiredQueues" --rerun-tasks
```

它会在 pipeline build 后替换方向光，验证 shadow queue/matrix 更新且 RenderGraph identity 与
scene graph rebuild count 不变，并由 GL debug 门禁检查错误与未授权消息。

v0.23.2 GTAO 的无桌面合同和真实 GL smoke 分开执行：

```powershell
.\gradlew.bat test --tests "*GtaoSettingsTest" --tests "*GtaoPassesTest" --tests "*PipelineTopologyTest" \
    --tests "*FramebufferDescriptorTest" --tests "*RenderGraphTest" --rerun-tasks
.\gradlew.bat glSmoke --tests "*GtaoShaderGlTest" --rerun-tasks
.\gradlew.bat runRender3dGtaoIntegration --rerun-tasks
.\gradlew.bat runRender3dGtaoBenchmarks "-PgtaoBenchmarkMode=comparison" "-PgtaoBenchmarkRounds=5" "-PgtaoBenchmarkWarmup=30" "-PgtaoBenchmarkFrames=60" --rerun-tasks
# 单独跑一侧时可使用 enabled 或 disabled；comparison 默认同时跑两侧。
.\gradlew.bat runRender3dGtaoBenchmarks "-PgtaoBenchmarkMode=enabled" "-PgtaoBenchmarkRounds=5" "-PgtaoBenchmarkWarmup=30" "-PgtaoBenchmarkFrames=60" --rerun-tasks
```

`localGtaoVerification` 的 1 轮/2 帧 smoke 只验证任务 wiring，benchmark 会自动跳过
这类样本的性能断言。正式性能结论使用预热后的稳定帧（默认 3 轮/60 帧）：直接检查
CPU p50/p95 增量、GTAO 全链 GPU p50、disabled 的 v0.23.1 历史基线回退，以及线程
分配的后半段增长趋势；整轮 wall p50/p95 只作冷启动诊断，不参与正式 gate。

人工视觉复核使用独立的 GTAO 接触场景，不依赖生产场景的构图：

```powershell
.\gradlew.bat runRender3dGtaoVisualDemo --args="--capture=build/reports/gtao-contact.png" --rerun-tasks
.\gradlew.bat runRender3dGtaoVisualDemo --args="--hidden --frames=3 --size=640x400 --preview --capture=build/reports/gtao-contact-preview.png" --rerun-tasks
.\gradlew.bat runRender3dGtaoVisualDemo --args="--hidden --frames=3 --size=640x400 --no-gtao --capture=build/reports/gtao-contact-baseline.png" --rerun-tasks
```

截图左侧 bay 使用 GTAO，右侧 bay 是相同几何、灯光和相机下的材质级 opt-out 参考；两侧的
地面接触、墙角、球体交叠和悬挑底面应有明显的明暗差异。`--preview` 会把左侧输出为
AO 灰度图，适合确认遮蔽分布；`--no-gtao` 用于成本和像素基线对照。性能复核建议使用
`--hidden --no-vsync`，并通过 `--gtao-quality`、`--gtao-radius`、`--gtao-strength` 和
`--gtao-thickness` 固定参数后再比较。

交互窗口默认保持成对全景，避免启动时鼠标位置改变验收构图；需要移动到左侧近距离检查时显式加
`--free-camera`，或用 `--view=left` / `--view=close` 复现固定视角。相机移动期间 GTAO history
会在较大视图变化时丢弃，避免旧遮蔽跨表面拖影；近处的屏幕空间采样半径也有上限，避免采样半径
投影到整个屏幕后出现突变或阴影消失。Demo 默认使用较保守的 `radius=0.90`、`strength=1.25`、
`thickness=0.22`，需要放大效果时再显式调高参数。

GTAO 是屏幕空间遮蔽：遮挡体完全离开当前 depth prepass 后，不保证继续产生阴影；需要跨视锥体
或物体背面的持久投影阴影时，应使用灯光 shadow map/contact-shadow 路径，而不是继续提高 GTAO 强度。

`GtaoShaderGlTest` 会编译 estimate/temporal/denoise/upsample、opaque/masked depth 和两套内置 PBR
shader，并在隐藏 OpenGL context 中构建启用 GTAO 的 PBR pipeline，检查 depth prepass、ceil
half-resolution target、R8 attachment、独立 history、相机位移后的 history 重建、最终像素 A/B、
NONE/MSAA/FXAA/TAA 组合和失败后下一帧恢复。另有
`RenderPipelineGlTest.gtaoDepthAndInstancedShadowReuseOnePreparedBatchAcrossPasses` 覆盖 GTAO depth、
instanced shadow/cascade 与 geometry 的一次上传复用；`GltfRuntimeGlTest` 的 skin/morph forward 与
shadow 用例也启用 GTAO depth prepass。发布候选仍需在同一最终提交上重跑 1080p/4K 性能结果，
透明/emissive/UI 隔离继续由既有队列和后处理用例负责，并不扩大 GTAO 的支持合同。
benchmark 默认输出每轮以及跨轮聚合的机器可读 stable CPU/GPU/GTAO 子链 p50/p95、
`allocationKiBPerFrame` 与 `allocationTrendKiBPerFrame`，并标出
`v0.23.1@4cc6d44` 对照来源。两侧必须使用相同 warmup、帧数和分辨率；wall 数据仍会输出，
但只用于识别窗口/context/shader 冷启动抖动。benchmark 阶段会关闭可选 RenderGraph debug
group，并将 GTAO GPU query 聚合为一条完整链路计时；普通交互/RenderDoc 运行仍保留逐 pass
计时。若 4K disabled 与历史 GPU 基线出现明显偏离，必须先在相同渲染设置、电源/时钟状态下
重建 v0.23.1 对照，不得放宽回退门槛。

The v0.19 serialized-scene gate keeps the CPU and focused GL checks explicit:

```powershell
.\gradlew.bat localSceneAssetVerification --rerun-tasks
```

It covers the strict `haikalat.scene` parser, catalog and generation
coalescing, staged glTF upload/lease sharing, and prepare/activate/commit
scene replacement. It is intentionally not part of the default `check`
because the GL half needs a desktop context.

## Integration

Manual or environment-specific checks that exercise full demo or pipeline behavior. These may open visible windows, depend on GPU/driver behavior, or require screenshot/pixel comparison beyond a minimal smoke check.

Use this category for:

- demo startup and resize workflows
- end-to-end scene pipeline behavior
- shadow/postprocess visual validation
- longer-running frame loop checks

Naming/package convention: `*IntegrationTest` under `com.kaleblangley.haikalat.integration`, or manual demo entry points in `src/demo/java`. Integration tests must remain opt-in through a system property or a separate Gradle task if they are automated later.

CI should run only the default unit path unless the environment explicitly provides a desktop GL context.

## Text performance baseline

```powershell
.\gradlew.bat runTextBenchmarks
```

默认口径为三轮、30 帧 warmup、120 帧测量和 100 次 Markdown parse sample，覆盖 1080p/4K、
1,000/10,000 glyph、字体切换、atlas cold/stable、普通文字、gradient、outline、drop shadow、
outer glow、inner glow 与组合 runs。报告包含 parse/CPU/GPU p50/p95、allocation、shape/layout
cache、glyph upload request/bytes/pages、draw/batch/state break 与 GL 消息。稳定帧若再次 shaping、
layout 或 upload 会直接失败；GL 消息由 `config/gl-debug-policy.tsv` 的精确规则审计。

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

混合架构 CPU 上必须先确认性能任务没有在较慢核心之间迁移。若 Windows
`Kernel-Processor-Power` 事件 37 只指向 E-core，且相同 Git 候选的 CPU median
随轮次大幅漂移，可用 `start /affinity` 将一次性 `--no-daemon` Gradle 进程限定到
已确认的 P-core mask；不要修改 baseline、回退阈值或性能结果。affinity mask 是机器相关值，
必须按本机逻辑处理器拓扑确定并在 release report 中记录，不能复制其他机器的 mask。

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
.\gradlew.bat runRender3dV023Demo
.\gradlew.bat runRender3dV023Integration
.\gradlew.bat runRender3dShadowBudgetDemo
.\gradlew.bat runRender3dShadowBudgetIntegration
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
`radio.gltf` MASK/cutoff 保留、BLEND decode/default/double-sided/异常输入都在普通 `test` 中执行。
`localGltfVerification` 额外创建隐藏 OpenGL 4.6 context，验证 embedded PNG、同一 image 的
sRGB/linear 双变体、PBR/IBL/HDR/ACES 最终像素、PBR 与方向光 shadow GPU 蒙皮、四阶段上传失败清理、library active-asset
guard、未选 MASK 场景不阻止 OPAQUE shadow instantiate，以及包含 showcase/radio/creeper 共
16 个对象和 animated two-joint fixture 的独立 Demo/resize 链路。可见窗口中按 F1 切换 inspector 交互模式，使用滚轮、
PageUp/PageDown 或 Home/End 检查长资产树，F2 隐藏面板。
共享动画专项还覆盖 `GltfAnimationSetTest` 与 `GltfAnimationLibraryTest` 的 GLB 源、多模型
绑定、严格 Rig 拒绝、clip 选择和关键帧引用复用；它们由
`runGltfSharedAnimationIntegration` 和 `localGltfVerification` 自动执行。

`runPostProcessEffectsIntegration` 和 `PostProcessEffectsGlTest` 以隐藏窗口验证 Color Grading LUT、
距离/高度雾、深度重建、最终像素变化和 resize；`runRender3dV023Integration` 额外验证 Fog 从
4x MSAA 显式 resolved depth 取样、resize 后 target 代次替换，以及固定 CSM atlas 不随窗口重建。
`runUiIntegration` 同时验证 header fade、spring layout transition 和最终 UI 像素。

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
.\gradlew.bat runModernUiIntegration
.\gradlew.bat localUiVerification
```

其中 async 入口由独立 update owner 连续发布三张 snapshot，再由 GL/window 线程消费最新值；
验证会断言 12 个 render frame 中发布 36 张、丢弃 24 张 stale snapshot，并按
render-resource → update/native-resource 顺序等待清理完成。

`runModernUiIntegration` 在不启动 `UiDemo` 的独立小型场景中额外验证 SDF/property
transform、显式 managed layer/blur topology、backdrop source unavailable direct fallback、
animation signal bridge、shimmer/ripple/confetti、800×520 framebuffer 与最终像素。纯 JVM
`UiEffectRuntimeTest` 执行 3,600 帧容量/清理 soak。

该入口还会每 15 帧重触发一次 Play transition，验证上一组 timeline、ripple、shimmer 和
confetti 被统一取消，连续重播不会累积超过 3 个 active effect。

Run every local check that requires a desktop OpenGL environment:

```powershell
.\gradlew.bat localGlVerification
```

`localGlVerification` also depends on `localUiVerification`, runs MinimalDemo's sRGB LDR target, the deterministic CPU skeletal-animation proof, the full-GPU automatic-exposure transition, the no-draw empty-window check, the 100000-instance triangle/quad/flattened-cube checks, and dedicated indexed Cube plus indexed+compact-SSBO Cube integrations. It is intentionally not attached to the default `check` task, so headless CI remains safe.

v0.23 的窗口化纵向证明可单独运行：

```powershell
.\gradlew.bat runRender3dV023Demo
.\gradlew.bat runRender3dV023Integration --rerun-tasks
```

交互入口使用 4096x4096 固定方向光 atlas；4 级 CSM 时每个 tile 为 2048x2048。隐藏入口固定
12 帧并在中途 resize，硬断言四类 queue、MASK caster、depth resolve、cascade 数、缓存与
diagnostics。该任务创建 GLFW/OpenGL 窗口，只属于本地桌面验证，不接入无桌面默认 `check`。

v0.23.1 多局部光与缓存专项：

```powershell
.\gradlew.bat test --tests "*ShadowLightSchedulerTest" --tests "*ScenePipelineTest"
.\gradlew.bat glSmoke --tests "*LocalShadowsGlTest" --rerun-tasks
.\gradlew.bat runRender3dShadowBudgetIntegration --rerun-tasks
.\gradlew.bat runRender3dShadowBudgetDemo
```

纯 JVM 路径锁定 stable light ID、优先级/相机评分、迟滞、拒绝原因、2 point + 4 spot atlas 布局、
HARD/PCF kernel 与 1,392-byte std140 offset。真实 GL 路径反射 UBO binding/大小，验证 legacy 像素、
三档 filter 的稳定像素和 tile guard、balanced 2+4 独立深度、静态完全复用、point 仅 6 face、spot
仅 1 tile、CSM 子 texel 相机移动/同宽高比 resize 复用，以及 caster 变化精确失效。

隐藏 integration 固定 18 帧：首帧 20 tile，frame 4 移动 point 得到 6 redraw，frame 7 移动 spot
得到 1 redraw，frame 10 移动相机得到 4 cascade redraw，frame 12 更新 skin/morph 得到 20 redraw，
frame 14 从 640×360 resize 到 800×450 且 fixed atlas generation/内容不变，最终为 0 redraw / 20 reuse。
场景还包含真实 glTF MASK caster 和两个低优先级预算拒绝项。该任务已纳入 `localGlVerification`，不
挂入默认无桌面 `check`。

正式 1080p/4K profile 入口每轮 60 帧 warmup、120 帧采样；发布记录至少执行五轮：

```powershell
.\gradlew.bat runRender3dShadowLegacyPerformance1080p
.\gradlew.bat runRender3dShadowLegacyPerformance4k
.\gradlew.bat runRender3dShadowBudgetPerformance1080p
.\gradlew.bat runRender3dShadowBudgetPerformance4k
```

输出 CPU median/P95、GPU median、shadow GPU median、tile redraw/reuse 和估算 depth MiB。legacy
回退必须与 v0.23.0 的相同 1+1 参数配对比较，不能用 balanced 2+4 直接得出兼容性能结论。GPU
频率或后台负载导致不同轮次明显漂移时，应把原始数据和限制写入报告，不能调整阈值或挑选单轮。

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
