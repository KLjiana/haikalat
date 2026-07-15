# 核心能力矩阵

本矩阵记录当前实现事实。`完整` 表示已具备运行行为和自动化验证入口；`部分完成` 表示主路径可用但仍缺少端到端证明；`骨架` 表示接口或 pass 已存在，但最终渲染行为尚未闭环。

| 能力 | 状态 | 源码入口 | Demo 入口 | 自动化验证 | 当前限制 |
| --- | --- | --- | --- | --- | --- |
| RenderGraph 排序与资源生命周期 | 完整 | `core/graph/RenderGraph`、`backend/framebuffer/RenderTargetManager` | 三个 Demo | `RenderGraphTest`、`FramebufferDescriptorTest`、`GlContextSmokeTest` | GPU timing 使用正式 `beginGpuTimer/endGpuTimer` query opcode |
| 材质与纹理 | 完整 | `TextureColorSpace`、`Texture2D`、`core/material`、`TextureAssetCache` | `LearnOpenGlDemo`、`MinimalDemo` | `MaterialTest`、`AssetPipelineTest`、`RuntimeResourceGlTest` | 旧纹理 API 默认 linear；颜色纹理需在 manifest/API 显式声明 sRGB，数据纹理保持 linear |
| 方向光、点光、聚光参数 | 完整 | `SceneLight`、`LightingBinder`、Demo forward shader | `LearnOpenGlDemo` | `RenderPipelineTest`、`ScenePipelineTest`、`GlContextSmokeTest` | shader 数组上限为方向光 2、点光 8、聚光 4；shadow light index 与有界方向光数组使用同一选择结果 |
| 方向光阴影 | 完整 | `DirectionalShadowMap`、`ForwardPassBuilder`、`RenderPipeline` | `LearnOpenGlDemo` | `ScenePipelineTest`、`RenderPipelineGlTest.fullLightingAndShadowPipelineChangesFinalPixels`、`runDemoIntegration` | 普通与显式 opt-in 的实例 caster 均使用固定 2048×2048 depth target；GL 测试覆盖开关、移动 caster、改变光方向和最终像素 |
| Instancing | 完整 | `InstancedMeshBatch`、`InstancedRenderer`、`CommandBuffer.drawInstancedBatch/prepareInstancedBatch` | 三个 Demo | `CommandBufferTest`、`InstanceDataLayoutTest`、`RenderPipelineGlTest.instancedShadowOptInChangesPixelsAndBatchRemainsReusableNextFrame` | 实例阴影显式 opt-in；shadow/geometry 复用一次快照与 ring 上传，最后一次 draw 后统一插入 fence |
| None / MSAA / FXAA / TAA | 完整 | `PostProcessPassBuilder`、`subsystems/postprocess` | `LearnOpenGlDemo` | `RenderPipelineTest`、`ScenePipelineTest`、`RenderPipelineGlTest` | LDR 保持旧 pass；HDR 中 MSAA 先 resolve，TAA 在线性空间累积，FXAA 在 tone mapping 后执行 |
| HDR / ACES tone mapping | 完整 | `RenderSettings`、`ToneMappingMode`、`ToneMappingPass`、`RGBA16F` framebuffer | `LearnOpenGlDemo` | `RenderSettingsTest`、`ToneMappingPassTest`、`FramebufferDescriptorTest`、`RenderPipelineGlTest` | 默认保持 LDR；ACES 显式 gamma 并关闭 framebuffer sRGB；暂不包含自动曝光 |
| Bloom | 完整 | `BloomSettings`、`BloomPass`、RenderGraph relative target | `runBloomDemo` | `RenderGraphTest`、`RenderPipelineTest`、`RenderPipelineGlTest`、`runBloomIntegration` | 默认关闭；1～8 层 HDR downsample/upsample，最终半分辨率纹理直接由 ToneMapping 合成 |
| OBJ 模型与场景配置 | 完整 | `ObjModelLoader`、`ModelAssetManager`、`SceneAssetConfig` | `LearnOpenGlDemo` | `AssetPipelineTest`、`DemoResourceContractTest`、`runDemoIntegration` | 主 Demo 从 classpath manifest 加载自有 OBJ，并将多 mesh 映射为共享材质/transform 的 renderer |
| Assimp 模型入口 | 部分完成 | `AssimpModelLoader` | 无 | 无端到端验证 | 仍要求真实文件系统路径，尚未进入打包 Demo 主路径 |
| 异步上传与渲染线程 | 完整 | `UploadSystem`、`LatestFrameMailbox`、`GlRenderThread` | `AsyncDemo` | `UploadSystemTest`、`LatestFrameMailboxTest`、`GlContextSmokeTest`、`runAsyncIntegration` | 两线程 latest-wins；矩阵 UBO 上传成功后才发布对应不可变帧状态 |
| CPU/GPU profiling | 完整 | `FrameDriver`、`GpuTimer`、`FrameProfile` | `LearnOpenGlDemo` 标题 | `ObservabilityTest`、`RenderGraphTest`、`runDemoIntegration` | RenderGraph profiling 不再依赖 `custom()` escape hatch |

## 构建基线

- Java：Gradle Toolchain 固定为 21。
- 默认命令：`compileJava demoClasses test`。
- 版本：`0.9.0-SNAPSHOT`。
- 默认测试：纯 JVM 测试；真实 GL 类通过 `haikalat.glSmoke=true` 显式启用。
- CI：Windows 与 Linux 均执行无窗口编译和纯 JVM 测试。
- 本地真实 GL：`test -Dhaikalat.glSmoke=true --rerun-tasks`，要求桌面环境与 OpenGL 4.6 驱动。
- 完整本地验收：`localGlVerification`，依次运行 GL smoke、基准 Demo、resize Demo 和 AsyncDemo。

矩阵状态随实现阶段更新；未完成端到端验证的能力不得在 README 中描述为完整效果。
