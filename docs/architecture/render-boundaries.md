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

对于 `ExecutionModel.IMMEDIATE`，graph 会在前一帧命令已执行完成后复用内部 `CommandBuffer`、`PassResources` 和 pass timing 数组；deferred backend 仍必须从 `RenderDevice` 获取独立 command buffer，不能复用尚未消费的数据。

## Pass Resource Access

普通 pass 应按逻辑资源工作，优先使用 `PassResources` 的窄 API：

- `colorAttachment(String textureName)`：按声明的逻辑纹理名取得 color attachment id。
- `depthAttachment(String textureName)`：按声明的逻辑纹理名取得 depth texture id。
- `currentTarget()`：取得当前 pass 的 render target，仅在需要查询当前目标尺寸或调试状态时使用。
- `framebufferOfPass(String passName)`：取得指定 pass 的 framebuffer，仅限 postprocess、present、shadow 等内部 OpenGL pass 使用。

旧入口 `getFramebuffer(...)`、`getFramebuffer()`、`getTextureAttachmentId(...)`、`getTexture(...)` 已收窄为 package-private backend-facing 兼容 API。新写普通 pass 不应先取得 `Framebuffer` 再读取 attachment id；如果只需要采样上游结果，应通过逻辑纹理名调用 `colorAttachment(...)` 或 `depthAttachment(...)`。

Pass 只查询 graph 已声明的资源，不自行创建、resize 或释放 render target。资源生命周期仍由 `RenderGraph` 和 `RenderTargetManager` 统一管理。

Pass 默认使用 graph/window 尺寸；固定分辨率资源通过 `PassBuilder.fixedSize(...)` 声明，窗口比例资源通过互斥的 `relativeSize(...)` 声明。窗口 resize 会按 `max(1, round(windowSize * scale))` 重建相对 target，但固定 pass 保持声明尺寸。方向光 shadow target 使用固定尺寸机制维持 2048x2048，Bloom 使用相对尺寸机制构建 1/2、1/4、1/8 等层级。

## Directional Shadow Boundary

`RenderPipeline` 拥有普通与 instanced depth-only shadow shader，`RenderGraph`/`RenderTargetManager` 拥有 shadow framebuffer 和 depth texture。Shadow pass 先遍历 `MeshRenderer.castShadows=true` 的普通 scene renderer，再在 `InstancedRenderer.castShadows=true` 时提交同一帧实例快照。实例阴影默认关闭，避免旧调用方无意增加一次实例上传和绘制。

10 万实例五轮基准证明双提交稳定增加超过 0.5 ms CPU median 后，生产路径改为正式的 `prepareInstancedBatch -> drawPreparedInstancedBatch -> finishPreparedInstancedBatch` 协议。命令记录时只复制一次稳定快照，执行时只占用并上传一个 ring slot；shadow 与 geometry 分别 draw，最后一个 pass 后才插入 fence。普通单 pass 调用方继续使用 `drawInstancedBatch`，两条路径都不创建 shadow 专用 batch，也不通过 `custom()` 绕过命令系统。

基准场景包含双面平面，因此 shadow pass 显式关闭 face culling，通过可调 slope bias 和 geometry shader 的 3x3 PCF 控制 acne 与锯齿。Shadow texture 使用 nearest filtering、clamp-to-border 和白色边界，超出 light frustum 的采样按不遮挡处理。

## HDR And Tone Mapping Boundary

`RenderSettings.toneMappingMode=NONE` 使用 `SRGB8_ALPHA8` scene、最终后处理和 TAA history target。RenderGraph 根据附件格式在 clear/draw 前提交 framebuffer sRGB；最终 Present 只复制已经编码的字节，因此默认 backbuffer 被驱动标记为 linear 或 sRGB 都不会造成漏编码或重复编码。只有显式选择 `ACES` 才启用 HDR；HDR geometry、MSAA resolve 和 TAA history/accumulation 使用 `RGBA16F`，tone-mapping target 使用普通 `RGBA8`。ACES pass 按 exposure、fitted curve、clamp、gamma 编码的顺序工作，并显式关闭 framebuffer sRGB。

LDR pass 顺序固定为：NONE/MSAA 是 `Geometry -> Present`，FXAA 是 `Geometry -> FXAA sRGB -> Present`，TAA 是 `Geometry -> TAA sRGB -> Present`。HDR pass 顺序固定为：NONE 是 `Geometry -> ToneMapping -> Present`；MSAA 是 `Geometry MSAA -> HdrResolve -> ToneMapping -> Present`；FXAA 是 `Geometry -> ToneMapping -> FXAA`；TAA 是 `Geometry -> TAA HDR -> ToneMapping -> Present`。因此 HDR TAA 始终在线性空间积累，HDR FXAA 处理已经由 ACES 编码的显示空间图像。窗口 resize 重建所有窗口相关 HDR/LDR target 和 TAA history，并使 history 下一帧权重归零；固定 2048×2048 shadow target 不参与窗口 resize。

Bloom 默认关闭；启用后从上述 MSAA resolve/TAA accumulation 之后的线性 HDR 纹理提取高亮，逐级降采样和上采样，并把最终半分辨率纹理直接传给 ToneMapping。Bloom pass 只声明 RenderGraph attachment，不拥有 framebuffer。ACES shader 负责最终 gamma，ToneMapping 显式提交 `framebufferSrgb=false`，避免重复编码。

自动曝光默认关闭且只允许与 HDR/ACES 组合。启用后，`AutoExposurePass` 从 resolve/TAA 后、Bloom 前的同一个线性 HDR producer 分支：全分辨率 `R16F` pass 写对数亮度，固定 14 级 RenderGraph `RG32F` target 分别累计总和与像素权重，adaptation 读取 `sum / max(weight, 1)` 后写入后处理子系统持有的双 1×1 history。前 13 级使用 `1/2`～`1/8192` 相对尺寸，末级固定 1×1，因此 graph 以小窗口构建后再放大也不会丢失归约 pass。resize 只重建 graph 管理的 target，history 不重建，并且只在整张 graph 成功执行后交换；失败帧继续保留上一张有效 history。ToneMapping 直接采样本帧 exposure texture，生产路径不得通过 CPU readback 获取曝光值。

自动曝光 adaptation 使用 `1 - exp(-speed * deltaSeconds)`，主 Demo 从 `FrameClock` 传入 delta，pipeline 再限制异常大的暂停间隔。`writeToExternalTarget()` 仅用于 executor 通过正式 framebuffer 命令写跨帧持久目标；它不允许隐式 clear，也不改变 RenderGraph 的依赖排序、timer query 或状态边界。

`Scene.setLight()` 是拓扑保持型动态更新接口：replacement 的 `LightType` 和 `castShadows` 必须与原灯光一致。颜色、强度、方向、位置、范围和锥角可逐帧更新；改变类型或阴影投射拓扑必须重新 build pipeline。

## Package Dependency Guard

依赖方向固定为 `subsystems/runtime -> core -> backend`。`backend` 不得依赖 `core`、`runtime` 或 `subsystems`，`core` 不得依赖 `runtime` 或 `subsystems`。OpenGL 格式、buffer upload target、vertex attribute/layout 和 GPU query timer 属于 backend；RenderGraph profile 数据属于 core。`ArchitectureBoundaryTest` 对这些规则做零允许列表检查，新增反向依赖必须先修正设计，而不是扩大白名单。

## OpenGL State Ownership

`GlRenderDevice` 持有跨 command buffer、跨 pass 和跨帧复用的 `StateCache`。正常命令提交不会全量失效缓存；只有绕过缓存的代码才允许做最小范围失效，例如 instanced batch 直接绑定 VAO 后调用 `invalidateVertexArray()`。Buffer 创建、分配、上传和映射使用 OpenGL DSA，不再污染缓存外的全局 buffer binding。外部裸 OpenGL 调用如果修改了受缓存管理的状态，必须显式调用 `invalidateState()`。

缓存覆盖 program、VAO、texture unit/2D texture/sampler、read/draw framebuffer、viewport、blend/depth/cull、framebuffer sRGB、clear color、indexed uniform/storage-buffer range 和 image unit。binding 数量在首次使用时读取真实 OpenGL capability，不再写死 32。普通 buffer 操作使用 DSA，因此不缓存 array/element buffer binding。`StateCache.Statistics` 记录实际应用与被跳过的状态变化，供 profiling 和回归测试使用。

`CommandBuffer` 使用可复用的 opcode/int/long/object structure-of-arrays，不再为每条命令分配捕获 `Consumer`。viewport、clear color、blend function、blend/depth/cull 开关只更新录制期 `PendingPipelineState`；遇到 draw、clear、blit、dispatch、memory barrier、GPU query、custom 或 command-buffer 结束时，编码一个只含最终 dirty values 的 primitive state packet。执行时 packet 仍通过长期存活的 `StateCache`，因此跨 command buffer、pass 和帧的 GL 去重继续有效。

状态折叠不得跨 observable boundary：depth mask 在 depth clear 前提交；每个 draw 保留独立材质状态；RenderGraph pass 的正式 timer-query begin/end 构成 pass 屏障；`custom()` 前提交全部 pending state，执行后全量失效 `StateCache`。资源绑定和 DSA uniform 可以位于状态 setter 与边界之间，因为它们不观察 raster pipeline state，但其彼此顺序不改变。

`CommandBuffer` 不允许对任意 draw 全局排序，因为 framebuffer、clear、uniform、透明 draw 和 pass 依赖具有顺序语义。draw 排序仍由 scene 层缓存完成：opaque/additive 按 shader/material/mesh 分组，alpha 保留提交顺序并最后绘制，shadow 按 mesh 分组。

多 pass 实例命令由同一 `CommandExecutor` 执行周期维护身份栈。每个成功的 `prepareInstancedBatch` 入栈，显式 `finishPreparedInstancedBatch` 移除并完成；命令流异常或遗漏 finish 时，执行器在 `finally` 中逆序结束剩余批次，插入 fence、释放矩阵快照并失效 VAO cache。清理失败不会覆盖主异常，而是作为 suppressed exception 保留。

## Runtime Timing

`FrameClock` 提供模拟/输入使用的限幅 delta 和累计时间；它不计算渲染 FPS。`FrameDriver` 在 `beginFrame -> endFrame` 之间统计上传和 CPU 命令提交耗时，并通过 `present(swapAction)` 在 swap 成功返回后记录呈现帧。`RenderStatistics` 使用一秒采样窗口输出 present FPS，同时通过线程安全 `Snapshot` 暴露 CPU submit、GPU profile 和呈现计数。`PeriodicTimer` 用于限制标题/overlay 等低频工作，禁止再用“每 N 帧”充当墙钟定时器。

`RenderGraph` profiling 使用正式 `beginGpuTimer/endGpuTimer` query opcode，生产代码不再调用 `CommandBuffer.custom()`。该逃生口仅用于诊断或迁移，必须按“前 flush、后 invalidate”的完整屏障处理。

## Shader Program And Bindings

`ShaderProgram` 负责 OpenGL stage 编译/链接、uniform 与 block 反射缓存，以及 program 级 DSA 更新。builder 支持 vertex、tessellation control/evaluation、geometry、fragment 和 compute；compute program 不可混入 graphics stage。`ShaderAsset` 仅描述 `ShaderStage -> AssetRef`，`HotReloadableShader` 负责在 GL 线程构建新 program 后原子替换旧 program。

普通材质仍使用 graphics program；compute 工作通过 `CommandBuffer.bindStorageBuffer/bindImage/dispatchCompute/memoryBarrier` 明确记录。SSBO block 和 UBO block 的 program binding 由 `ShaderProgram` 完成，实际 buffer range/image unit 由 `StateCache` 去重。直接调用 `ShaderProgram.set*` 使用 DSA，不要求先 `use()`。

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

- `shader.*`：命名 shader 资源；properties 格式支持 `vertex`、`tessControl`、`tessEvaluation`、`geometry`、`fragment` 和 `compute` stage。
- `texture.*`：命名 texture 资源和加载选项。
- `model.*`：命名外部模型资源。
- `material.*`：命名材质定义，引用 shader/texture/sampler 名称。

demo scene 便利字段：

- `object.*`：引用一个 model/material，提供基础 position/rotation/scale/castShadows。
- `light.*`：描述基础 directional/point light 参数。

配置不承载动画 updater、脚本、任意 procedural geometry、复杂 builtin mesh 参数或编辑器数据。Demo 中需要动画时，由 Java 代码按 object 名称提供 `ModelUpdater`；配置只提供基础 transform。Builtin mesh 只允许固定小集合，例如 `builtin:triangle`、`builtin:quad`、`builtin:texturedQuad`，避免把任意几何数组塞进配置。

## CommandBuffer Instanced Batch

`CommandBuffer.drawInstancedBatch(...)` 是单 pass 实例化批处理的正式主路径命令。它在记录命令时复制传入的 `Matrix4f` transform 列表，执行时在渲染线程按 `beginFrame -> submitAll -> flush` 顺序调用 `InstancedMeshBatch`。

需要在多个 pass 使用同一份实例数据时，调用方按顺序记录 `prepareInstancedBatch(...)`、一个或多个 `drawPreparedInstancedBatch(...)`，最后记录 `finishPreparedInstancedBatch(...)`。该协议不跨 draw 或 RenderGraph pass 改变 raster state 语义，只复用已上传 vertex-input range；每个 draw 仍保留自己的 shader、uniform、framebuffer 和 pending pipeline state 边界。

该命令的 batch upload/draw 由 `InstancedMeshBatch` 自己完成；结束后只失效 VAO/element-buffer 缓存，不再触发全状态失效。调用方不得通过 `cmd.custom()` 捕获实例化 batch 的裸 upload/draw 逻辑；如果 transform 来自生产线程，必须在记录命令前或记录时形成稳定快照。

## Demo Proof

当前保留一个综合 demo 作为能力证明：

- `LearnOpenGlDemo`：读取标准 `.properties` 场景配置，运行 scene pipeline，默认启用 ACES HDR、FXAA 和实例阴影，并通过配置中的 shadow-casting directional light 创建 shadow pass。

专项 demo 保留为调试入口：

- `MinimalDemo`：验证最小窗口、命令提交和实例化批处理。
- `AsyncDemo`：验证异步更新、上传队列和 render thread 交互；详细线程所有权、发布和关闭顺序见 [AsyncDemo 渲染线程契约](async-render-thread.md)。

## GL Smoke Verification

默认单元测试不要求真实 OpenGL context。需要验证本机图形环境时运行：

```powershell
.\gradlew.bat test "-Dhaikalat.glSmoke=true" --rerun-tasks
```

该 smoke test 会创建隐藏 GLFW 窗口，初始化 GL capabilities，执行 clear，并用 `glReadPixels` 验证回读像素。它还覆盖 framebuffer、shader、texture 和 texture cache 的最小生命周期边界，确保关闭后的资源拒绝继续使用。
