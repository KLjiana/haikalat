# Render Boundary Contracts

本文件记录当前稳定化阶段保留的最小边界，避免继续扩张薄抽象。

## RenderDevice

`RenderDevice` 只表示命令提交边界：

- `backendKind()` 标识当前后端。
- `executionModel()` 标识立即式或未来显式提交模型。
- `createCommandBuffer()` 创建当前后端可执行的命令缓冲。
- `execute(CommandBuffer)` 提交并执行命令。
- `transition(ResourceBarrier...)` 保留资源状态转换协议，OpenGL 后端当前为空实现。

`RenderDevice` 不暴露 `StateCache`、framebuffer invalidation、资源创建 factory 或具体 GL 对象。需要这些能力时应使用具体 OpenGL 类型，或等第二个后端实验证明边界后再提升到接口。

## RenderGraph

`RenderGraph` 只负责三类职责：

- Pass：注册 pass、声明依赖、拓扑排序、执行 pass executor。
- Resource：根据 pass 声明创建、查询、resize、释放 render target。
- Profile：记录 pass 级 CPU/GPU 时间，并输出 `FrameProfile`。

`RenderGraph` 不负责场景遍历、材质绑定、光照模型、shader variant、asset loading 或 demo 输入逻辑。

## Pass Resource Access

普通 pass 应按逻辑资源工作，优先使用 `PassResources` 的窄 API：

- `colorAttachment(String textureName)`：按声明的逻辑纹理名取得 color attachment id。
- `currentTarget()`：取得当前 pass 的 render target，仅在需要查询当前目标尺寸或调试状态时使用。
- `framebufferOfPass(String passName)`：取得指定 pass 的 framebuffer，仅限 postprocess、present、shadow 等内部 OpenGL pass 使用。

保留的旧入口 `getFramebuffer(...)`、`getFramebuffer()`、`getTextureAttachmentId(...)`、`getTexture(...)` 属于 backend-facing/internal 兼容 API。新写普通 pass 不应先取得 `Framebuffer` 再读取 attachment id；如果只需要采样上游颜色结果，应通过逻辑纹理名调用 `colorAttachment(...)`。

Pass 只查询 graph 已声明的资源，不自行创建、resize 或释放 render target。资源生命周期仍由 `RenderGraph` 和 `RenderTargetManager` 统一管理。

## Mesh Data And Runtime Mesh

`MeshData` 是纯 JVM 数据层，用于模型解析、内置几何、后台准备和普通单元测试。它只包含顶点数组、索引数组、`VertexLayout` 和 primitive mode，不创建 VAO/VBO/EBO，也不要求 OpenGL context。

`Mesh` 是 `MeshData` 上传到 OpenGL 后的运行时资源。`Mesh.from(MeshData)` 是数据层到 GPU 资源层的上传入口之一，调用时必须已经有当前 GL context；返回的 `Mesh` 拥有 VAO、vertex buffer、可选 index buffer，并由运行时调用方负责 `close()`。

资产 pipeline 测试应优先验证 `MeshData` 的顶点、索引和 layout。只有 GL smoke 或 integration 测试才验证上传后的 `Mesh`、buffer 生命周期和真实绘制行为。

## Material Definition And Runtime Material

`MaterialDef` 是配置/资产层对象，只保存 shader 名称、texture 名称、sampler 名称、blend/depth 等引用和值。它不持有 `ShaderProgram`、`Texture2D` 或 `Sampler`，因此可以在没有 OpenGL context 的测试、资产解析和热重载准备阶段使用。

`Material` 是当前 OpenGL runtime material。它直接持有 `ShaderProgram`、`Texture2D` 和可选 `Sampler`，负责绑定 shader、纹理、sampler、默认 uniform 和渲染状态。它不是跨后端长期抽象；如果后续出现第二后端，应先重新评估 runtime material 边界，而不是把当前类直接提升为通用接口。

`ResourceOwnership.BORROWED` 用于资产系统、缓存或 demo setup 统一管理 shader/texture/sampler 生命周期的场景，是默认选择。`ResourceOwnership.OWNED` 只用于便捷构造的 runtime material，表示关闭 material 时一并关闭它独占引用的 GL 资源。配置层不使用 `ResourceOwnership`，因为配置层不拥有 GL 对象。

## Asset Manifest And Demo Scene

当前 `.properties` 配置定位为 asset manifest + 小型 demo scene manifest，不是完整场景格式或编辑器格式。

稳定 asset manifest 字段：

- `shader.*`：命名 shader 资源。
- `texture.*`：命名 texture 资源和加载选项。
- `model.*`：命名外部模型资源。
- `material.*`：命名材质定义，引用 shader/texture/sampler 名称。

demo scene 便利字段：

- `object.*`：引用一个 model/material，提供基础 position/rotation/scale/castShadows。
- `light.*`：描述基础 directional/point light 参数。

配置不承载动画 updater、脚本、任意 procedural geometry、复杂 builtin mesh 参数或编辑器数据。Demo 中需要动画时，由 Java 代码按 object 名称提供 `ModelUpdater`；配置只提供基础 transform。Builtin mesh 只允许固定小集合，例如 `builtin:triangle`、`builtin:quad`、`builtin:texturedQuad`，避免把任意几何数组塞进配置。

## CommandBuffer Instanced Batch

`CommandBuffer.drawInstancedBatch(...)` 是实例化批处理的正式主路径命令。它在记录命令时复制传入的 `Matrix4f` transform 列表，执行时在渲染线程按 `beginFrame -> submitAll -> flush` 顺序调用 `InstancedMeshBatch`。

该命令的 batch upload/draw 由 `InstancedMeshBatch` 自己完成，当前不会经过 `StateCache` 去重。调用方不得通过 `cmd.custom()` 捕获实例化 batch 的裸 upload/draw 逻辑；如果 transform 来自生产线程，必须在记录命令前或记录时形成稳定快照。

## Demo Proof

当前保留一个综合 demo 作为能力证明：

- `LearnOpenGlDemo`：读取标准 `.properties` 场景配置，运行 scene pipeline，默认启用 FXAA postprocess，并通过配置中的 shadow-casting directional light 创建 shadow pass。

专项 demo 保留为调试入口：

- `MinimalDemo`：验证最小窗口、命令提交和实例化批处理。
- `AsyncDemo`：验证异步更新、上传队列和 render thread 交互。

## GL Smoke Verification

默认单元测试不要求真实 OpenGL context。需要验证本机图形环境时运行：

```powershell
.\gradlew.bat test "-Dhaikalat.glSmoke=true" --rerun-tasks
```

该 smoke test 会创建隐藏 GLFW 窗口，初始化 GL capabilities，执行 clear，并用 `glReadPixels` 验证回读像素。它还覆盖 framebuffer、shader、texture 和 texture cache 的最小生命周期边界，确保关闭后的资源拒绝继续使用。
