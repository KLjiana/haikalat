# Developer Task Plan

本文件把一线开发反馈转成可执行任务。排序以“写 demo、业务功能、测试时最容易卡住”为准，
不按长期架构愿景排序。

## P0: 收敛主渲染路径逃生口

目标：实例化 batch 的 upload/draw 进入正式 `CommandBuffer` API，业务和 demo 不再为主绘制路径写
`cmd.custom()`。

任务：

- 在 `CommandBuffer` 增加实例化 batch 上传和绘制命令，覆盖 `InstancedRenderer`、`MinimalDemo`、
  `AsyncDemo` 当前 custom block 的行为。
- 明确这些命令是否经过 `StateCache`，并把跨线程捕获规则写进方法注释或 `docs/architecture/render-boundaries.md`。
- 让 `InstancedRenderer` 只记录正式命令，不直接捕获裸 GL upload/draw 逻辑。
- 保留 `RenderGraph` GPU timer begin/end 的 `cmd.custom()`，它是 profiling escape hatch，不属于主绘制路径。

验收：

- `rg "cmd\\.custom" src/demo src/main` 只允许命中 `RenderGraph` timer，或命中带有明确豁免说明的调试代码。
- `MinimalDemo`、`AsyncDemo`、`InstancedRenderer` 不再重复实例化 batch custom block。
- 新增或更新测试，验证实例化命令被记录并按预期执行/排序；纯 JVM 测试优先，不强制 GL context。

## P1: 拆开 RenderPipeline 内部阻塞点

目标：降低 `RenderPipeline` 阅读和修改成本。先拆 package-private helper，不急着新增 public interface。

任务：

- 提取 `CameraUniforms`，负责 camera UBO 创建、更新、shader block 绑定和释放。
- 提取 `LightingBinder`，负责 directional/point/spot light uniform 写入和相机位置写入。
- 提取 `TaaHistory`，负责 TAA history framebuffer、valid 标记、resize reset 和释放。
- 提取 `PostProcessPassBuilder`，负责 FXAA/TAA/present pass 创建和 final pass executor 选择。
- 评估是否提取 `ForwardPassBuilder`；如果提取，只负责 shadow + geometry pass 注册，不接管 scene 遍历。

验收：

- `RenderPipeline` 仍是外部入口，但只编排 build/execute/resize/close，不直接承载所有细节。
- 添加 pass 或改 lighting 不需要阅读 TAA history 生命周期代码。
- 现有 `RenderPipelineTest`、`ScenePipelineTest` 继续通过，并补充 helper 级纯 JVM 测试能覆盖的部分。

## P1: 收窄 RenderGraph pass 资源访问

目标：让普通 pass 按“读写逻辑资源”工作，减少直接依赖 FBO、texture id 的扩散。

任务：

- 在 `PassResources` 增加窄 API：`colorAttachment(String textureName)`、`framebufferOfPass(String passName)`、
  `currentTarget()`。
- 将已有 pass 代码优先迁移到窄 API；直接拿 `Framebuffer` 或 attachment id 的用法限制在 postprocess、
  present、shadow 等内部 pass。
- 在 `docs/architecture/render-boundaries.md` 记录 pass 资源访问分层：普通 pass 使用逻辑资源名，内部 OpenGL pass
  才允许访问 backend 类型。

验收：

- 新写 pass 的推荐入口不再是“先拿 FBO 再取 attachment id”。
- 旧 API 如需保留，应在文档中标记为 backend-facing/internal 使用。
- RenderGraph 资源生命周期仍由 graph/manager 管理，pass 不自行创建或释放 render target。

## P2: 明确 Mesh 数据层和 GPU 资源层

目标：让资产加载、纯单元测试、后台模型准备不被 GL context 绑定。

任务：

- 明确推广 `LoadedModel.MeshData` 或引入独立 `MeshData` 作为纯数据层，包含顶点、索引、layout/attributes。
- 为 `Mesh` 增加从 `MeshData` 上传的路径，并把 `Mesh` 文档定义为“GL 上传后的运行时资源”。
- 逐步把 demo 中手写 `Mesh.builder()` 的静态几何迁移到 `MeshData -> Mesh`，至少先覆盖主 demo 的重复 quad/triangle。
- 资产 pipeline 测试只验证 `MeshData`，GL smoke/integration 才验证上传后的 `Mesh`。

验收：

- 不需要 GL context 也能测试模型解析、builtin mesh 数据和 scene asset 绑定。
- `Mesh` 的 close/lifecycle 仍清楚归属运行时资源。
- demo 里新几何优先写成纯数据，再在 GL 初始化阶段上传。

## P2: 决定 Material 当前边界  

目标：消除“Material 是长期抽象还是 GL runtime material”的歧义。

决策：当前阶段接受 `Material` 是 GL runtime material，不把它描述成跨后端长期抽象。配置层先引入定义对象，
不要让配置直接持有 GL 对象。

任务：

- 在文档中明确 `Material` 持有 `ShaderProgram`、`Texture2D`、`Sampler`，属于 OpenGL runtime resource。
- 如需配置驱动材质，新增 `MaterialDef` 或等价配置对象，用 shader/texture 引用描述材质，不负责 GL 生命周期。
- 将 `ResourceOwnership` 的使用场景写清楚：demo 便捷构造可使用 runtime material，资产系统应集中管理资源关闭。

验收：

- 一线调用方能明确：配置/资产阶段处理 `MaterialDef`，渲染阶段处理 `Material`。
- 热重载或替换 shader/texture 的任务不再要求调用方理解所有 ownership 细节。

## P2: 统一 demo 入口

目标：新同学只看到一套推荐 demo 风格。

任务：

- 保留 `com.kaleblangley.haikalat.demo.LearnOpenGlDemo` 作为主 demo。
- 保留 `MinimalDemo` 作为底层 smoke demo。
- 保留 `AsyncDemo` 作为线程和上传 demo。
- 处理旧顶层 `src/demo/java/Main.java`、`Light.java`、`Camera.java`、`Shader.java`：直接删除，其文件中依赖的着色器文件也一并删除。

验收：

- README 只推荐新体系 demo。
- 旧 demo 不再和新 demo 处在同一个默认包入口层级。
- 改 shader/texture/window 时不会出现两套等价推荐写法。

## P3: 明确资产配置下一步

目标：避免 `.properties` 停在“像 scene，实际只是资源索引”的半成品状态。

决策：下一阶段先把配置定位为 asset manifest + 简单 scene manifest，不引入完整编辑器格式。

任务：

- 保留 shader/texture/model 作为 asset manifest。
- 允许配置描述简单 object/light/material binding；动画 updater 和复杂 builtin mesh 仍由 Java demo 代码负责。
- 为 builtin mesh 建立小而固定的名字集合，例如 `triangle`、`quad`，避免配置里塞任意几何数组。
- 在 `SceneAssetConfig` 文档中写清哪些字段是稳定 manifest，哪些只是 demo scene 便利字段。

验收：

- `LearnOpenGlDemo` 的资源索引、简单 object/light/material 绑定逐步从 Java 硬编码迁移到配置。
- 配置不承担复杂动画、脚本或编辑器职责。

## P3: 建立测试分类约定

目标：让开发者知道什么测试能进普通 CI，什么测试需要 GL 环境。

任务：

- 在 README 或新测试文档中定义三类测试：
  `unit`：纯 JVM，无 GL context；`glSmoke`：隐藏窗口 + 最小 GL 验证；
  `integration`：demo/pipeline 级别，可手动或特定 CI 环境运行。
- 将 shader 编译、framebuffer 生命周期、texture/shader/asset cache 的真实 GL 错误验证归入 `glSmoke` 或
  `integration`。
- 普通 `test` 默认只跑纯 JVM 和 opt-out 安全测试；真实 GL 测试继续由系统属性显式开启。

验收：

- 新测试文件从命名、包或注解上能看出所属层级。
- CI 默认路径不因为缺少桌面 GL 环境失败。
- 资源生命周期错误有明确测试入口，不再混进普通单测。

## Recommended Execution Order

1. P0：先消灭实例化主路径 `cmd.custom()`，这是最直接的一线阻塞。
2. P1：拆 `RenderPipeline` 和收窄 `PassResources`，降低后续 pass/状态修改成本。
3. P2：统一 Mesh/Material/Demo 边界，减少资产和新人入口混乱。
4. P3：推进配置和测试分层，让后续功能有稳定落点。
