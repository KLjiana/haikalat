# Abstraction Audit

本次盘点依据 `docs/architecture/abstraction-density.md` 执行，重点检查只有一个实现的 `public interface`，以及只有少量调用点的 `descriptor / manager / factory`。

## Public Interface Inventory

| 抽象 | 实现/使用情况 | 结论 |
|---|---|---|
| `GlResource` | 多个 OpenGL 资源实现，统一释放协议 | 保留 |
| `RenderWindow` | 当前只有 `GlfwWindow` 实现，但隔离 demo/runtime 对 GLFW 的依赖 | 保留，属于窗口后端边界 |
| `RenderDevice` | 当前只有 `GlRenderDevice` 实现，接口已收缩到 backend kind、execution model、command submit、barrier transition | 保留，但继续限制增长 |
| `ModelAssetLoader` | `ObjModelLoader` 与 `AssimpModelLoader` 两个实现 | 保留 |
| `BufferUploadTarget` | 生产实现为 `GlBuffer`，测试使用 fake target 验证 `UploadSystem` | 保留，属于测试隔离 |
| `UniformValue` | sealed value hierarchy，多个 uniform 类型实现 | 保留 |
| `UploadSystem.UploadRequest` / `PassExecutor` / `InstanceDef` / `ModelUpdater` | 函数式回调，不作为长期架构接口扩展 | 保留，保持局部化 |

## Descriptor / Manager / Factory Inventory

| 类型 | 使用证据 | 结论 |
|---|---|---|
| `FramebufferDescriptor` | `Framebuffer`、`RenderGraph`、shadow map、测试共同使用 | 保留 |
| `RenderTargetManager` | 管理 RenderGraph render target 创建、resize、释放生命周期 | 保留 |
| `Sampler.Descriptor` | `Sampler` 创建策略使用，表达过滤和 wrap 配置 | 保留 |
| `InstanceDataLayout` | instancing demo、batch、测试共同验证 | 保留 |
| `ModelAssetManager` | 按扩展名路由模型加载器，测试覆盖路由行为 | 保留 |
| `TextureAssetCache` | 负责纹理加载去重和生命周期 | 保留 |
| `FrameProfile` / `PassProfile` | RenderGraph profiling 与 overlay 使用 | 保留 |
| `RenderResourceFactory` / `GlRenderResourceFactory` | 只有一个 OpenGL 实现，资源创建没有真实策略差异 | 降级移除 |
| `BufferDescriptor` / `TextureDescriptor` / `PipelineStateDescriptor` / `CullMode` | 主要只有构造测试和未来愿景，没有生产行为支撑 | 降级移除 |
| `UploadQueue` | deprecated wrapper，零调用点，行为完全转发到 `UploadSystem` | 降级移除 |
| `AntiAliasPipeline` | 后处理已经 graph 化并进入 `RenderPipeline`，该类零调用点 | 降级移除 |
| `DefaultAssetManagers` | 零调用点 factory，只有一条固定构造路径 | 降级移除 |
| `DeferredPipelinePlan` | 只表达长期评估结果，没有运行时行为 | 降级移除 |

## CommandBuffer Custom Escapes

`InstancedRenderer`、`MinimalDemo`、`AsyncDemo` 的 instanced batch upload/draw 已经收敛到正式 `CommandBuffer.drawInstancedBatch(...)`。

当前主源码和 demo 中只保留 `RenderGraph` GPU timer begin/end 的 `cmd.custom()`，这是 profiling escape hatch。测试中的 `cmd.custom()` 仅用于验证命令排序。

## Downgrade Summary

本次降级删除了当前没有实际行为支撑的资源 factory、通用 descriptor、旧上传 wrapper、旧后处理管线和长期评估 plan，避免 roadmap 概念继续留在运行时代码里。`RenderGraph` 直接使用 `RenderTargetManager`，`RenderTargetManager` 直接通过 `Framebuffer.fromDescriptor` 创建资源。

保留的抽象都有至少一种当前证据：demo 使用、单元测试、生命周期管理职责，或明确的后端/测试隔离边界。
