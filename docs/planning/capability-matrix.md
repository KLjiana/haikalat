# 核心能力矩阵

本矩阵记录当前实现事实。`完整` 表示已具备运行行为和自动化验证入口；`部分完成` 表示主路径可用但仍缺少端到端证明；`骨架` 表示接口或 pass 已存在，但最终渲染行为尚未闭环。

| 能力 | 状态 | 源码入口 | Demo 入口 | 自动化验证 | 当前限制 |
| --- | --- | --- | --- | --- | --- |
| RenderGraph 排序与资源生命周期 | 完整 | `core/graph/RenderGraph`、`backend/framebuffer/RenderTargetManager` | 三个 Demo | `RenderGraphTest`、`FramebufferDescriptorTest`、`GlContextSmokeTest` | GPU timing 使用正式 `beginGpuTimer/endGpuTimer` query opcode |
| 材质与纹理 | 完整 | `TextureColorSpace`、`Texture2D`、`core/material`、`TextureAssetCache` | `LearnOpenGlDemo`、`MinimalDemo` | `MaterialTest`、`AssetPipelineTest`、`RuntimeResourceGlTest` | 旧纹理 API 默认 linear；颜色纹理需在 manifest/API 显式声明 sRGB，数据纹理保持 linear |
| Metallic-roughness PBR | 完整 | `MaterialModel`、`PbrMaterialProperties`、`TangentGenerator`、`PbrMaterials`、PBR forward shader | `PbrDemo`、`LearnOpenGlDemo` PBR proof | `AssetPipelineTest`、`TangentGeneratorTest`、`PbrBrdfMathTest`、`PbrEnvironmentGlTest`、`runPbrCompatibilityIntegration` | framework material 保持 opaque，glTF scene-asset 可设置 MASK cutoff；单一固定 shader/unit contract；不承诺 PBR instancing、alpha blending/transmission 或高级材质扩展 |
| glTF 2.0 静态资产 | 完整 | `core/assets/gltf`、`subsystems/render3d/gltf`、`SceneAssetConfig.gltfScenes` | 独立 `GltfDemo` + retained inspector、主 Demo showcase | `GltfAssetLoaderTest`、`GltfRuntimeGlTest`、`runGltfIntegration`、`runGltfResizeIntegration`、`localGltfVerification`、`runGltfBenchmarks` | opaque/MASK `.gltf/.glb`、external/data/GLB embedded 资源、完整 accessor 主型、hierarchy、共享 mesh、PBR 像素、四阶段上传失败清理、五轮性能矩阵与人工验收均已闭环；MASK shadow caster 与 BLEND 保持明确拒绝语义 |
| HDR cubemap 与 GPU IBL | 完整 | `TextureCube`、typed cube commands、`EnvironmentPreprocessor`、`PbrEnvironment` | `PbrDemo` environment/background | `PbrEnvironmentGlTest`、`runPbrIntegration`、`runPbrResizeIntegration`、`runPbrFailureIntegration` | 单一全局 environment；启动期 compute 预计算；生产路径无 readback/glFinish，resize 不重建 environment |
| 方向光、点光、聚光参数 | 完整 | `SceneLight`、`LightingBinder`、Demo forward shader | `LearnOpenGlDemo` | `RenderPipelineTest`、`ScenePipelineTest`、`GlContextSmokeTest` | shader 数组上限为方向光 2、点光 8、聚光 4；shadow light index 与有界方向光数组使用同一选择结果 |
| 方向光阴影 | 完整 | `DirectionalShadowMap`、`ForwardPassBuilder`、`RenderPipeline` | `LearnOpenGlDemo` | `ScenePipelineTest`、`RenderPipelineGlTest.fullLightingAndShadowPipelineChangesFinalPixels`、`runDemoIntegration` | 普通与显式 opt-in 的实例 caster 均使用固定 2048×2048 depth target；GL 测试覆盖开关、移动 caster、改变光方向和最终像素 |
| Instancing | 完整 | `InstancedMeshBatch`、`InstancedRenderer`、`CommandBuffer.drawInstancedBatch/prepareInstancedBatch` | 三个 Demo | `CommandBufferTest`、`InstanceDataLayoutTest`、`InstanceUploadGlTest.preparedMultiPassBatchIsFencedWhenGeometryStageFailsAndNextFrameReusesRing`、`RenderPipelineGlTest` | 实例阴影显式 opt-in；shadow/geometry 复用一次快照与 ring 上传，最后一次 draw 后统一插入 fence；命令失败时执行器逆序清理所有未完成批次 |
| None / MSAA / FXAA / TAA | 完整 | `PostProcessPassBuilder`、`subsystems/postprocess` | `LearnOpenGlDemo`、`MinimalDemo` | `RenderPipelineTest`、`ScenePipelineTest`、`RenderPipelineGlTest`、`runMinimalIntegration` | LDR 使用 `SRGB8_ALPHA8` scene/final/history target 并复制编码结果到 backbuffer；HDR 中 MSAA 先 resolve，TAA 在线性空间累积，FXAA 在 tone mapping 后执行 |
| HDR / ACES tone mapping | 完整 | `RenderSettings`、`ToneMappingMode`、`ToneMappingPass`、`RGBA16F` framebuffer | `LearnOpenGlDemo` | `RenderSettingsTest`、`ToneMappingPassTest`、`FramebufferDescriptorTest`、`RenderPipelineGlTest` | 默认保持 LDR；ACES 显式 gamma 并关闭 framebuffer sRGB |
| 全 GPU 自动曝光 | 完整 | `ExposureMode`、`AutoExposureSettings`、`AutoExposurePass`、`R16F/RG32F` framebuffer | `runAutoExposureDemo`、`runAutoExposureIntegration` | `AutoExposurePassTest`、`AutoExposureGlTest`、`RenderPipelineGlTest` | 默认 MANUAL；AUTO 只用于 HDR；固定 14 级 sum/weight reduction，最大正式边长 16384；全程留在 GPU，不向标题回读曝光数值 |
| Bloom | 完整 | `BloomSettings`、`BloomPass`、RenderGraph relative target | `runBloomDemo` | `RenderGraphTest`、`RenderPipelineTest`、`RenderPipelineGlTest`、`runBloomIntegration` | 默认关闭；1～8 层 HDR downsample/upsample，最终半分辨率纹理直接由 ToneMapping 合成 |
| Retained-mode 游戏 UI | 完整 | `subsystems/ui/UiSystem`、`UiDocument`、Yoga layout、widget/event/style、`UiRenderer` | `UiDemo`、`LearnOpenGlDemo` overlay | `UiSystemTest`、widget/layout/event tests、`UiRendererGlTest`、`UiDemoGlTest`、四项 UI integration | 每窗口一棵单线程 mutable tree；同步双槽或异步三槽 immutable snapshot；editor docking、CSS parser 和 OS accessibility bridge 不在 v0.11 |
| Latin/CJK 文本与 glyph atlas | 完整 | `FontManager`、`TextShaper`、`TextLayouter`、`GlyphAtlas`、`UiGlyphAtlasGpu` | `UiDemo`、`runUiTextIntegration` | native shaping/font/atlas tests、`UiGlyphAtlasGpuGlTest`、`UiDemoGlTest` | 内建 Sans2.004 Noto Sans SC VF；正式承诺 Latin/CJK，暂不承诺完整 BiDi、彩色 emoji 或 variable-axis UI |
| UI 输入、焦点、clipboard 与 Windows IME | 完整 | `WindowInputSnapshot`、`UiInputRouter`、`TextField`、`TextInputAdapter`、`Win32TextInputAdapter` | `UiDemo`、`runUiInteractiveSoak` | input/focus/TextField tests、可注入 IME integration、`runUiSyntheticImeSoak`、`runUiNativeSoak`、2026-07-17 Microsoft Pinyin 人工矩阵 | committed char 与 composition 分离；非 Windows 平台降级为 committed-char；Emoji 输入可提交，但内建字体不保证对应字形且不支持彩色 Emoji |
| OBJ 模型与场景配置 | 完整 | `ObjModelLoader`、`ModelAssetManager`、`SceneAssetConfig` | `LearnOpenGlDemo` | `AssetPipelineTest`、`DemoResourceContractTest`、`runDemoIntegration` | 主 Demo 从 classpath manifest 加载自有 OBJ，并将多 mesh 映射为共享材质/transform 的 renderer |
| 异步上传与渲染线程 | 完整 | `UploadSystem`、`LatestFrameMailbox`、`GlRenderThread` | `AsyncDemo` | `UploadSystemTest`、`LatestFrameMailboxTest`、`GlContextSmokeTest`、`runAsyncIntegration` | 两线程 latest-wins；矩阵 UBO 上传成功后才发布对应不可变帧状态 |
| CPU/GPU profiling | 完整 | `FrameDriver`、`GpuTimer`、`FrameProfile` | `LearnOpenGlDemo` 标题 | `ObservabilityTest`、`RenderGraphTest`、`runDemoIntegration` | RenderGraph profiling 不再依赖 `custom()` escape hatch |
| 调试与可观察性闭环 | 完整 | `runtime/diagnostics`、`RenderGraph.description`、`GlDebug` message/resource snapshot | `LearnOpenGlDemo` F2 五页面板 | `FrameDiagnosticsTest`、`RenderGraphDescriptionTest`、`localDiagnosticsVerification` | 有界 240-frame/256-message；显存是估值；不预览纹理、不编辑 graph、不控制外部 debugger |

## 构建基线

- Java：Gradle Toolchain 固定为 21。
- 默认命令：`compileJava demoClasses test`。
- 正式稳定基线：`0.15.0`。
- 默认测试：纯 JVM 测试；真实 GL 类通过 `haikalat.glSmoke=true` 显式启用。
- CI：Windows 与 Linux 均执行无窗口编译和纯 JVM 测试。
- 本地真实 GL：`test -Dhaikalat.glSmoke=true --rerun-tasks`，要求桌面环境与 OpenGL 4.6 驱动。
- UI 本地验收：`localUiVerification`，执行 GL smoke、deterministic/resize/async/text 四项 integration、100 轮 synthetic IME soak 与至少 50 轮 Windows native hook soak。
- PBR 本地验收：`localPbrVerification`，执行 PBR JVM/真实 GL、deterministic、resize、legacy compatibility 与 failure cleanup。
- glTF 本地验收：`localGltfVerification`，执行 parser/accessor/node/material JVM 测试、真实 GL upload/lifecycle，以及 deterministic/resize 集成。
- diagnostics 本地验收：`localDiagnosticsVerification`，执行 history/export JVM 测试以及 deterministic、resize、failure/recovery 三项真实 GL capture。
- 完整本地验收：`localGlVerification`，包含 `localUiVerification`，并继续运行基准/resize/Bloom/自动曝光/Minimal/Async/空窗口 Demo 和压力入口。

矩阵状态随实现阶段更新；未完成端到端验证的能力不得在 README 中描述为完整效果。
