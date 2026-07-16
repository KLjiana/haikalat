# 项目目标

本项目已经从 LearnOpenGL 教程练习演进为一个学习导向、工程化约束明确的
Java/LWJGL/OpenGL 4.6 实时渲染框架。当前目标是用可运行 demo、单元测试和少量
清晰文档，沉淀一套可学习、可验证、可逐步扩展的 OpenGL 渲染架构。

当前阶段的重点不是无边界扩张功能面，而是稳定已有 runtime、RenderGraph、资源生命周期、
材质、场景渲染、后处理、UI subsystem 和 demo 证明路径。调试器与编辑工具可以作为这些
公共能力的集成场景逐步发展。

## 已有能力

- 分层结构：`backend` 负责 OpenGL 资源与错误边界，`core` 负责渲染数据协议，
  `subsystems` 提供窗口、3D scene pipeline、postprocess，`runtime` 提供帧驱动、
  统计、GPU timing 和 debug overlay 数据。
- OpenGL 后端：shader、buffer、texture、sampler、framebuffer、VAO、uniform block、
  GPU fence、state cache、GL debug/error 分类。
- 核心渲染协议：`CommandBuffer`、`RenderDevice`、`RenderGraph`、mesh/vertex layout、
  instancing、latest-frame mailbox、upload system、material/material instance、typed uniform value。
- 3D 管线：forward pipeline、scene/camera/object/light 模型、带输入校验和数量上限的基础光照、
  固定分辨率 directional shadow map、depth-only caster pass、bias/3x3 PCF，以及 FXAA/TAA/MSAA/none 后处理选择。
- 资产系统：classpath resource locator、shader asset、texture cache、`.properties` 场景配置，
  以及由主 Demo 证明的 OBJ 加载链路；Assimp 加载器仍是要求文件系统路径的实验入口。
- UI subsystem：Yoga/Flexbox 布局、保留式节点树、主题与样式、widgets、事件传播、滚动与
  虚拟列表、FreeType/HarfBuzz 文本、glyph atlas、字体注册与切换，以及 Windows IME 组合输入。
- Demo 证明：`LearnOpenGlDemo` 作为综合场景、阴影、后处理证明；`MinimalDemo` 验证最小窗口、
  命令提交和 instancing；`AsyncDemo` 验证异步更新、上传和 render thread 协作；`UiDemo`
  验证布局、输入、文本、弹窗、滚动、虚拟化和 UI 渲染链路。
- 现代材质闭环：opaque metallic-roughness、OBJ tangent、HDR cubemap、GPU irradiance/GGX
  prefilter/BRDF LUT、Cook–Torrance 直接光、IBL、环境背景，以及与阴影、ACES、Bloom、自动曝光和 UI 的协同。
- 测试覆盖：render graph、pipeline pass、asset config、vertex packing、instance layout、
  upload system、latest-frame mailbox、material、framebuffer descriptor、observability，以及可选真实
  GL context smoke test。

## 目标方向

- 优先稳定 OpenGL 后端、RenderGraph、资源生命周期和 Scene Pipeline。
- 每个长期保留的抽象必须能被 demo、测试或明确生命周期职责证明；核心抽象最好同时具备
  demo、测试和文档。
- 用少量高质量 demo 证明能力，避免每个新特性都变成长期维护入口。
- 将 UI 稳定公共 API 与内部渲染/native 协议分开管理；编辑和调试工具优先复用公共 API，
  并用真实需求检验其便利性与完整性。
- 在 OpenGL 后端稳定前，不急于引入 Vulkan/多后端，也不扩展成完整游戏引擎。
- instanced batch upload/draw 已经从 `cmd.custom()` 逃生口收敛到正式 `CommandBuffer.drawInstancedBatch(...)`，
  主渲染路径不再依赖业务侧自定义 GL block。
- 继续补强资源生命周期错误验证，覆盖 framebuffer、texture、shader、asset cache 等真实 GL 资源边界。

## 非目标

- 暂不做完整游戏引擎：物理、音频、脚本、动画状态机不是当前重点。
- 暂不引入复杂 ECS，除非现有 `SceneObject` 模型明确撑不住。
- 暂不直接重写 Vulkan 或第二后端；第二后端只能在 OpenGL 边界稳定后用于验证 `RenderDevice`。
- 暂不一次性建设与框架紧耦合的单体生产级编辑器；允许基于 UI subsystem 逐步增加调试面板、
  资源检查器和编辑工作流。
- 暂不把 v0.12 最小 PBR 扩张为完整材质平台；glTF、transmission、clearcoat、local probe、
  clustered lighting 与 shader graph 必须由后续真实内容需求和性能数据驱动。

## 命名

`learnopengl` 项目名保留历史语义，表示它仍然是学习 OpenGL 和渲染架构的实验场。
源码包名和文档中的 `haikalat` 则表示当前代码已经具备框架身份，后续架构决策应以
稳定框架原型为准。
