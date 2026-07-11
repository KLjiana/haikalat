# 核心能力矩阵

本矩阵记录当前实现事实。`完整` 表示已具备运行行为和自动化验证入口；`部分完成` 表示主路径可用但仍缺少端到端证明；`骨架` 表示接口或 pass 已存在，但最终渲染行为尚未闭环。

| 能力 | 状态 | 源码入口 | Demo 入口 | 自动化验证 | 当前限制 |
| --- | --- | --- | --- | --- | --- |
| RenderGraph 排序与资源生命周期 | 完整 | `core/graph/RenderGraph`、`backend/framebuffer/RenderTargetManager` | 三个 Demo | `RenderGraphTest`、`FramebufferDescriptorTest`、`GlContextSmokeTest` | GPU timing 仍通过受控 `custom` 命令记录 |
| 材质与纹理 | 完整 | `core/material`、`core/assets/TextureAssetCache` | `LearnOpenGlDemo` | `MaterialTest`、`AssetPipelineTest`、`GlContextSmokeTest` | `Material` 是 OpenGL runtime resource，不是跨后端定义 |
| 方向光、点光、聚光参数 | 完整 | `SceneLight`、`LightingBinder`、Demo forward shader | `LearnOpenGlDemo` | `RenderPipelineTest`、`ScenePipelineTest`、`GlContextSmokeTest` | shader 数组上限为方向光 2、点光 8、聚光 4；shadow light index 与有界方向光数组使用同一选择结果 |
| 方向光阴影 | 完整 | `DirectionalShadowMap`、`ForwardPassBuilder`、`RenderPipeline` | `LearnOpenGlDemo` | `ScenePipelineTest`、`GlContextSmokeTest.fullLightingAndShadowPipelineChangesFinalPixels`、`runDemoIntegration` | GL 测试覆盖光照开关、阴影最终像素、移动不可见 caster 和改变光方向；第一版不把独立 instanced batch 作为 shadow caster |
| Instancing | 完整 | `InstancedMeshBatch`、`InstancedRenderer`、`CommandBuffer.drawInstancedBatch` | 三个 Demo | `CommandBufferTest`、`InstanceDataLayoutTest` | 第一版 shadow pass 不保证实例化 caster |
| None / MSAA / FXAA / TAA | 完整 | `PostProcessPassBuilder`、`subsystems/postprocess` | `LearnOpenGlDemo` | `RenderPipelineTest`、`ScenePipelineTest`、`GlContextSmokeTest` | GL smoke 对每种模式执行一帧并验证非空输出 |
| 模型加载与场景配置 | 部分完成 | `ObjModelLoader`、`AssimpModelLoader`、`SceneAssetConfig` | `LearnOpenGlDemo` | `AssetPipelineTest` | 主 Demo 当前只接通固定 builtin mesh 集合 |
| 异步上传与渲染线程 | 完整 | `UploadSystem`、`GlRenderThread` | `AsyncDemo` | `UploadSystemTest`、`TripleBufferTest` | Demo integration 仍为手动入口 |
| CPU/GPU profiling | 完整 | `FrameDriver`、`GpuTimer`、`FrameProfile` | `LearnOpenGlDemo` 标题 | `ObservabilityTest`、`RenderGraphTest`、`runDemoIntegration` | profiling 命令仍是 RenderGraph 中受控的 escape hatch |

## 构建基线

- Java：Gradle Toolchain 固定为 21。
- 默认命令：`compileJava demoClasses test`。
- 默认测试：81 个测试方法；真实 GL 类通过 `haikalat.glSmoke=true` 显式启用。
- CI：Windows 与 Linux 均执行无窗口编译和纯 JVM 测试。
- 本地真实 GL：`test -Dhaikalat.glSmoke=true --rerun-tasks`，要求桌面环境与 OpenGL 4.6 驱动。

矩阵状态随实现阶段更新；未完成端到端验证的能力不得在 README 中描述为完整效果。
