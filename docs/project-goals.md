# Project Goals

本项目已经从 LearnOpenGL 教程练习演进为一个学习导向、工程化约束明确的
Java/LWJGL/OpenGL 3.3 实时渲染框架。当前目标是用可运行 demo、单元测试和少量
清晰文档，沉淀一套可学习、可验证、可逐步扩展的 OpenGL 渲染架构。

当前阶段的重点不是继续扩张功能面，而是稳定已有 runtime、RenderGraph、资源生命周期、
材质、场景渲染、后处理和 demo 证明路径。

## Existing Capabilities

- 分层结构：`backend` 负责 OpenGL 资源与错误边界，`core` 负责渲染数据协议，
  `subsystems` 提供窗口、3D scene pipeline、postprocess，`runtime` 提供帧驱动、
  统计、GPU timing 和 debug overlay 数据。
- OpenGL 后端：shader、buffer、texture、sampler、framebuffer、VAO、uniform block、
  GPU fence、state cache、GL debug/error 分类。
- 核心渲染协议：`CommandBuffer`、`RenderDevice`、`RenderGraph`、mesh/vertex layout、
  instancing、triple buffer、upload system、material/material instance、typed uniform value。
- 3D 管线：forward pipeline、scene/camera/object/light 模型、方向光/点光/聚光基础参数、
  directional shadow map pass、FXAA/TAA/MSAA/none 后处理选择。
- 资产系统：classpath resource locator、shader asset、texture cache、`.properties` 场景配置、
  OBJ/Assimp 模型加载入口。
- Demo 证明：`LearnOpenGlDemo` 作为综合场景、阴影、后处理证明；`MinimalDemo` 验证最小窗口、
  命令提交和 instancing；`AsyncDemo` 验证异步更新、上传和 render thread 协作。
- 测试覆盖：render graph、pipeline pass、asset config、vertex packing、instance layout、
  upload system、triple buffer、material、framebuffer descriptor、observability，以及可选真实
  GL context smoke test。

## Target Direction

- 优先稳定 OpenGL 后端、RenderGraph、资源生命周期和 Scene Pipeline。
- 每个长期保留的抽象必须能被 demo、测试或明确生命周期职责证明；核心抽象最好同时具备
  demo、测试和文档。
- 用少量高质量 demo 证明能力，避免每个新特性都变成长期维护入口。
- 在 OpenGL 后端稳定前，不急于引入 Vulkan/多后端、不扩展成完整游戏引擎、不做大型编辑器。
- instanced batch upload/draw 已经从 `cmd.custom()` 逃生口收敛到正式 `CommandBuffer.drawInstancedBatch(...)`，
  主渲染路径不再依赖业务侧自定义 GL block。
- 继续补强资源生命周期错误验证，覆盖 framebuffer、texture、shader、asset cache 等真实 GL 资源边界。

## Non-Goals

- 暂不做完整游戏引擎：物理、音频、脚本、动画状态机不是当前重点。
- 暂不引入复杂 ECS，除非现有 `SceneObject` 模型明确撑不住。
- 暂不直接重写 Vulkan 或第二后端；第二后端只能在 OpenGL 边界稳定后用于验证 `RenderDevice`。
- 暂不做大型编辑器，运行时框架、demo 和调试信息优先。
- 暂不引入复杂 PBR 管线，先把基础光照、阴影、材质和后处理链打稳。

## Naming

`learnopengl` 项目名保留历史语义，表示它仍然是学习 OpenGL 和渲染架构的实验场。
源码包名和文档中的 `haikalat` 则表示当前代码已经具备框架身份，后续架构决策应以
稳定框架原型为准。
