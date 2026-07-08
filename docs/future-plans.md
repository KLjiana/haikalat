# Future Plans

本项目已经完成基础 OpenGL/LWJGL 渲染框架雏形，包括 `backend / core / subsystems / runtime` 分层、`CommandBuffer`、`StateCache`、`RenderGraph`、`UploadSystem`、实例化渲染、FXAA/TAA 后处理、GPU 计时、GL debug output、同步与异步 Demo。后续开发重点应从"功能扩展"逐步转向"稳定性、可验证性、性能路径和长期架构边界"。

## Roadmap Overview

| 阶段 | 目标 | 重点 |
|---|---|---|
| 近期 | 稳定当前架构，降低维护成本 | 构建整理、测试补齐、Demo 规范化、RenderGraph 语义收紧 |
| 中期 | 提升渲染能力和性能基础 | 材质系统拆分、UBO、后处理 Graph 化、实例化批处理增强 |
| 长期 | 向小型渲染引擎演进 | 多后端抽象、资源系统、场景系统、资产加载、编辑器/工具链 |

---

## 近期计划

近期目标是让当前代码更稳定、更容易验证，并减少后续开发时的隐性成本。

### 构建与项目结构

- [x] 精简 `build.gradle` 中未使用的 LWJGL 模块，只保留当前实际依赖。
- [x] 将 `lwjglNatives = "natives-windows"` 改为按操作系统自动选择 natives。
- [x] 拆分 Demo 与测试代码，将可运行示例从 `src/test/java` 迁移到独立 source set，例如 `src/demo/java`。
- [x] 规范化 Demo source set，使 IDE 可直接运行 demo `main` 方法。
- [x] 补充 README 中的运行方式、环境要求、JDK 版本、显卡/OpenGL 要求。

### 测试与验证

- [x] 为 `VertexPacking` 添加精度和边界测试。
- [x] 为 `RenderGraph` 添加 Pass 拓扑排序、循环依赖检测、resize 行为测试。
- [x] 为 `UploadSystem` 添加连续上传、重叠上传、last-write-wins、异常聚合测试。
- [x] 为 `TripleBuffer` 添加读写交换和多线程可见性测试。
- [x] 建立基础 CI 流程，至少执行编译和非 OpenGL 上下文依赖的单元测试。

### RenderGraph 与命令系统收敛

- [x] 为 `RenderPipeline` 中的 `PresentPass` 显式添加 `.dependsOn("GeometryPass")`。
- [x] 检查所有 RenderGraph Pass，补齐真实依赖关系，避免依赖注册顺序。
- [x] 梳理 `cmd.custom()` 的使用点，标记哪些是临时逃生口，哪些需要正式命令 API。
- [x] 为 framebuffer blit 增加正式 `CommandBuffer` 方法，减少 `cmd.custom()` 使用。
- [x] 明确 `CommandBuffer` 中可捕获数据的规则，避免捕获跨线程可变对象。

### Demo 稳定性

- [x] 修正异步 Demo 中主线程、渲染线程、上传队列之间的初始化顺序。
- [x] 为窗口 resize 添加统一处理，确保 framebuffer、viewport、projection 同步更新。
- [x] 在窗口最小化或高度为 0 时避免投影矩阵除零。
- [x] 确保空场景时仍然 clear 和 present，避免上一帧画面残留。
- [x] 用 `try/finally` 统一 Demo 资源释放路径。

---

## 中期计划

中期目标是提升渲染系统的表达能力和性能基础，让框架可以承载更复杂的场景。

### 材质与 Uniform 系统

- [x] 将 `Material` 拆分为不可变模板和可变实例状态。
- [x] 明确 `ShaderProgram`、`Texture2D`、`Material` 的资源所有权策略。
- [x] 完善 `UniformValue` 类型系统，减少字符串和运行时类型判断。
- [x] 引入 UBO 支持，用于相机矩阵、光照参数、全局渲染参数。
- [x] 增加 Sampler 抽象，使同一纹理可以使用不同过滤和 wrap 策略。

### 后处理与抗锯齿

- [x] 将 FXAA 作为标准 RenderGraph Pass 接入，而不是作为外部流程。
- [x] 将 TAA accumulation 作为标准 RenderGraph Pass 接入。
- [x] 为后处理链增加开关配置，例如 `NONE / FXAA / TAA / MSAA`。
- [x] 增加后处理输入输出纹理命名约定，降低 Pass 之间的耦合。
- [x] 为 TAA 添加 jitter、history validation、resize 后 history reset。

### 实例化与批处理

- [x] 增强 `InstancedMeshBatch`，支持多 Mesh 或按 Mesh 分组提交。
- [x] 为动态 instance data 增加 fence 或更明确的 GPU 同步策略。
- [x] 评估恢复 persistent mapping 路径，减少频繁 buffer update 成本。
- [x] 增加 batch 统计信息，例如 instance 数量、draw call 数量、buffer update 次数。
- [x] 设计可替换的实例数据布局，支持 mat4、packed transform、颜色、自定义属性。

### Framebuffer 与资源管理

- [x] 完善 MRT 支持，允许一个 Pass 输出多个 color attachment。
- [x] 增加 depth texture 支持，为 shadow map、SSAO、depth prepass 做准备。
- [x] 增加 framebuffer descriptor，统一描述尺寸、格式、采样数和 attachment。
- [x] 建立简单的 render target 生命周期管理，减少手动创建和销毁。
- [x] 为 resize 引入统一资源重建流程。

---

## 长期计划

长期目标是把项目从 OpenGL 学习框架推进到可扩展的小型渲染引擎基础。

### 渲染后端抽象

- [x] 重新审视 `RenderDevice` 接口，区分立即式 OpenGL 实现和未来显式 API 后端需求。
- [x] 将资源创建从 `RenderGraph` 中进一步下沉到 backend/device 层。
- [x] 为 buffer、texture、framebuffer、pipeline state 引入 descriptor。
- [x] 减少 core 层直接依赖 OpenGL 常量，为 Vulkan/Metal/Direct3D 后端预留空间。
- [x] 定义 resource barrier / layout transition 的抽象，即使 OpenGL 后端中暂时为空实现。

### 场景与渲染管线

- [x] 引入基础 Scene 数据结构，统一管理 camera、mesh renderer、material、transform。
- [x] 增加 forward rendering 的标准 pipeline。
- [x] 评估 deferred rendering pipeline，包括 GBuffer、lighting pass、postprocess pass。
- [x] 增加基础光照模型，例如 directional light、point light、spot light。
- [x] 增加 shadow mapping 支持，优先实现 directional light shadow map。

### 资产与工具链

- [x] 接入 Assimp 或自定义加载流程，支持 glTF/OBJ 等模型格式。
- [x] 增加 texture asset cache，避免重复加载相同图片。
- [x] 增加 shader hot reload，用于快速迭代 GLSL。
- [x] 增加统一 asset path 和 resource locator。
- [x] 为 Demo 添加可配置场景文件，减少硬编码 mesh 和 material。

### 可观测性与调试

- [x] 完善 GPU timer，将每个 RenderGraph Pass 的 GPU 时间纳入统计。
- [x] 增加 CPU/GPU frame profile 输出。
- [x] 增加 debug overlay，显示 FPS、draw call、instance count、GPU time、active AA mode。
- [x] 增加 GL object label，配合 debug callback 输出更可读的错误信息。
- [x] 建立渲染错误分类，区分资源错误、shader 错误、framebuffer 错误和状态错误。

---

## Non-Goals

以下事项暂不作为优先目标，避免项目过早扩大范围。

- [ ] 暂不追求完整游戏引擎功能，例如物理、音频、脚本、动画状态机。
- [ ] 暂不引入复杂 ECS，除非当前 SceneObject 模型无法支撑后续需求。
- [ ] 暂不直接重写 Vulkan 后端，先把 `RenderDevice` 和资源描述抽象打稳。
- [ ] 暂不做大型编辑器，优先保证运行时框架、Demo 和调试信息稳定。
- [ ] 暂不引入复杂 PBR 管线，先完成基础光照、阴影、材质和后处理链。

---

## Suggested Milestones

| Milestone | 验收标准 |
|---|---|
| `v0.1-stable-demo` | Demo 可从 IDE 直接运行 main，resize 稳定，资源释放完整 |
| `v0.2-tested-core` | `RenderGraph`、`UploadSystem`、`TripleBuffer`、`VertexPacking` 有单元测试 |
| `v0.3-materials` | 材质模板/实例拆分，UniformValue 类型化，基础 UBO 可用 |
| `v0.4-postprocess-graph` | FXAA/TAA 全部作为 RenderGraph Pass 执行 |
| `v0.5-scene-pipeline` | 引入 Scene、标准 forward pipeline、基础光照和统计 overlay |
