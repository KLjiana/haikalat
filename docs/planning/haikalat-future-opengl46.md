# Haikalat 引擎演进报告

**执行摘要：** Haikalat 引擎目标提供一套与具体宿主无关的通用表现引擎能力，包括**动画系统**、**VFX 特效系统**、**基于物理的渲染与后处理管线**、**保留模式 UI**、**资源管理**等。核心原则是模块化、高可配置、与宿主平台完全解耦，并以 **OpenGL 4.6 Core Profile** 作为唯一开发与运行基线。Haikalat 不承担 OpenGL 3.x 兼容层，也不为缺少 DSA、SSBO、Compute Shader 等现代能力的环境提供后端降级。下列建议供开发决策参考：

- **分层模块开发**：优先实现基础框架（P0），逐步添加高级功能（P1/P2/P3），确保每个阶段可用性和可验证性。
- **接口与边界清晰**：所有核心功能在 Haikalat 内定义，不依赖任何具体宿主的类型。对外只暴露经过 Demo、测试和实际引擎需求证明的通用数据与生命周期接口。
- **OpenGL 4.6 单后端基线**：DSA、SSBO、Compute Shader、Image Load/Store、Persistent Mapping、Multi-Draw Indirect、Shader Draw Parameters 和 KHR_debug 均可作为正式架构能力使用。启动时必须完成 capability 验证；不满足硬要求时明确终止初始化，而不是进入功能残缺的兼容模式。
- **测试驱动开发**：为各模块添加单元/集成测试，包括动画准确性、VFX 效果、渲染结果验证和性能基准。每个里程碑产出都应包含示例场景和验证用例。
- **三年分阶段路线**：制定清晰的里程碑和验收标准。第一年完成 P0–P1（基础动画、渲染管线、简易后处理、UI 框架），第二年推进 P2（阴影、实时光照、IK 等），第三年完善 P3（高级特效、GPU-driven 与 OpenGL 4.6 深化）。
- **当前范围**：先完成并稳定 Haikalat 本体。HaikalatHost、Minecraft 接入、模组 API、游戏事件与网络同步均不属于当前阶段的实现或验收范围。


## OpenGL 4.6 技术基线

Haikalat 当前是 **Java 21+ / LWJGL 3 / OpenGL 4.6 Core Profile 的单后端实时渲染框架**。未来功能设计可以直接依赖下列核心能力：

- Direct State Access：资源创建、存储、更新和绑定不再维护传统 bind-to-edit 后端；
- Buffer Storage 与 Persistent Mapping：实例、UI、粒子、骨骼和命令数据采用多帧 ring/page arena；
- SSBO、UBO 与 Shader Draw Parameters：统一组织 frame、material、draw、joint 和 effect 数据；
- Compute Shader 与 Image Load/Store：用于 IBL 预处理、自动曝光、GPU 粒子、可见性、扫描压缩和体积效果；
- Multi-Draw Indirect：作为批量提交和 GPU-driven 演进基础；
- Sync Object、Timer Query 与 Pipeline Statistics：用于资源复用、性能分析和诊断；
- KHR_debug：所有 Pass、Shader 与 GPU 资源必须具有可追踪名称和调试组。

OpenGL 4.6 是架构前提，而不是可选快速路径。可选扩展（例如 `ARB_bindless_texture`）仍需单独检测，但不应迫使核心后端退回旧式 OpenGL。Haikalat 暂不承担 macOS、OpenGL 3.x 或多图形 API 的运行兼容目标。


## 目标能力清单（按优先级 P0–P3）

- **P0（基础功能）**
  - **动画系统**：通用蒙皮骨骼动画引擎。支持 glTF 皮肤和动画加载（`skins`、关节层次、逆绑定矩阵、animation sampler/channel）、基于关键帧的 `AnimationClip` 播放、骨骼层次（`Skeleton`/`Pose`）和 GPU 蒙皮。第一版明确支持 `JOINTS_0/WEIGHTS_0` 的 4 骨骼影响，后续可扩展第二组关节权重。CPU 负责姿态求值、混合和 IK，关节矩阵通过 SSBO 上传；默认在 Vertex Shader 中蒙皮，需要跨多个 Pass 复用变形结果时再增加 Compute Skinning。数据结构包括 `Skeleton`、`Skin`、`AnimationClip`、`AnimationPlayer`、`PoseBuffer`、`JointPalette`。**成本：** 高（约 4–6 人·月），*风险：* 动画语义、混合、根运动和 IK 的复杂度较高。
  - **核心渲染架构**：命令缓冲和状态管理。包括强类型 `CommandBuffer/CommandExecutor`、`RenderGraph` 渲染图系统、`StateCache` 状态缓存（避免冗余 GL 调用）。支持多帧资源环、GPU 计时、调试组、错误隔离和诊断快照。**关键要点：** 统一采用 OpenGL 4.6 DSA 路径，管线状态、Framebuffer、纹理、缓冲区与同步对象均由单一后端管理；不维护 `glGen* + glBind*` 兼容实现。数据结构包括 `CommandStream`、`RenderGraph`、`PassResources` 和显式 frame-slot/fence 协议。**成本：** 中等（约 3–4 人·月），*风险：* 需保证跨帧同步、资源所有权和嵌入式运行时的状态边界正确。
  - **资源系统与 OpenGL 4.6 后端**：定义 `ResourceSource`、`AssetId`、资源 generation 和异步 CPU 解码协议，供 glTF、纹理、着色器、VFX、UI 主题等模块使用。GPU 资源统一采用 DSA 创建与更新（如 `glCreateTextures`、`glCreateBuffers`、`glNamedBufferStorage`），上传系统优先使用 persistent mapped storage 与 fence 保护的多帧槽。**关键要点：** 资源抽象与具体宿主完全解耦；后端不为旧版 OpenGL 保留重复实现。**成本：** 中等（约 2 人·月），*风险：* 资源依赖、重载 generation、异步上传所有权和 glTF 路径解析较复杂。
  - **UI 系统基础**：已有 Flexbox 布局、文本引擎、基本控件。补充缺失输入事件桥接与基本动画。*关键要点：* 支持 `Yoga` 布局、`UiDisplayList` 渲染、GPU 顶点批处理。接口如 `UiSystem`、`UiNode`、`UiEvent` 等。依赖极少，仅需 OpenGL 绘制，主要在 CPU 更新线程完成布局和文本。**成本：** 中等（约 2–3 人·月），*风险：* 布局稳定性（光标、文本裁剪）、不同屏幕尺寸兼容。

- **P1（进阶功能）**
  - **后处理与环境渲染**：HDR 管线：**Bloom**、**色调映射（ACES）**、**自动曝光**、**色彩分级（LUT）**、**景深**、**高度雾/距离雾**、**屏幕空间光柱**、**SSAO** 等。*关键要点：* 利用 `RenderGraph` 添加后处理 Pass。Bloom 软阈值与渐进合成；色调映射使用 ACES 曲线；雾计算依赖深度缓冲。引用可参考 Bloom 算法和色彩处理论文。依赖：RenderGraph、HDR 纹理、Compute/Fragment Pass 与深度重建。不同画质档仅调整采样数、分辨率和 Pass 组合，不切换到旧版 OpenGL 实现。**成本：** 中等（3 人·月），*风险：* 视觉调优复杂、参数选择多。
  - **IBL 和 PBR 环境**：支持辐射度和环境立方体贴图预计算，包括**Diffuse Irradiance**、**GGX 预滤波**、**BRDF 积分 LUT**等。集成天空盒、环境光。在材质层面，支持 PBR 参数（粗糙度、金属度、法线等）和基于 Cook-Torrance 的着色（使用 Trowbridge-Reitz D 分布、Smith *V* 几何、Schlick Fresnel 近似）。数据结构：`PbrMaterial`、`PbrEnvironment`、`EnvironmentPreprocessor`。依赖：HDR Cubemap、mip 链、Compute Shader 或离线预计算资源。低画质档可降低环境贴图分辨率与采样数，但仍保持同一 PBR 数据模型和 OpenGL 4.6 后端。**成本：** 较高（4–5 人·月），*风险：* 生成流程复杂、内存成本高。
  - **基础 VFX 系统**：通用特效框架。**粒子系统**（点精灵/网格粒子）、**尾迹（Ribbon）**、**地面贴花（Decal）**、**镜头特效（Shake/Flash）**、**动态光源**。定义 `EffectAsset/EffectInstance`，驱动器可通过动画事件激发。数据结构：`ParticleEmitter`、`RibbonEmitter`、`DecalRenderer` 等。依赖：透明排序、实例缓冲和特效材质。第一版可采用 CPU 模拟 + persistent mapped instance buffer，后续再将高数量粒子迁移到 Compute Shader；这是功能演进而非旧硬件回退。**成本：** 中等（约 3 人·月），*风险：* 参数组合爆炸、编辑工具支持缺失。
  - **UI 动画与转场**：补充渐变动画、布局过渡。支持基于时间轴和插值的 Tween/Spring；控件状态过渡；共享元素动画等。接口示例：`node.animate().opacity(0,1, dur, ease)`。依赖：仅 CPU计算、后端可利用顶点缓冲平移/缩放。**成本：** 较低（1–2 人·月），*风险：* 动画组合逻辑复杂度中等。

- **P2（高级功能）**
  - **阴影映射**：方向光、点光和聚光灯阴影。支持级联阴影贴图和深度偏移。数据结构：`DirectionalShadowMap` 等。依赖：深度纹理、纹理数组/Cubemap、矩阵计算和 RenderGraph Pass。画质档通过阴影分辨率、级联数量与更新频率控制成本。**成本：** 中等（3 人·月），*风险：* 锯齿和采样性能。
  - **高级动画**：骨骼层次混合、动作同步和根运动（Root Motion）支持。增量骨骼融合（Layered/Bone Mask）、**两骨IK**。数据：`AnimationMixer`、`AnimationGraph`、`BoneMask`。依赖：精确计时和插值。无 IK 时可只用预制作动画。**成本：** 高（3–4 人·月），*风险：* 同步与权重调优复杂。
  - **实时光照**：可选的屏幕空间漫反射/镜面光衰减，加速全局光近似（屏幕空间光线追踪）。动态范围光源（火焰、爆炸等）对场景局部照亮（可基于深度重建计算效果）。依赖：深度重构、法线/材质信息与 Compute/Fragment 光照 Pass。低画质档通过光源数量、半分辨率和采样预算控制成本。**成本：** 较高（3 人·月），*风险：* 性能瓶颈明显。

- **P3（前沿拓展）**
  - **OpenGL 4.6 高级 GPU 管线**：体积雾与光束、GPU 粒子、GPU culling、prefix-sum/scan/compact、Multi-Draw Indirect、indirect draw count、bindless texture（扩展能力）、异步 readback 和更完整的 GPU-driven scene。Compute Shader、SSBO、Image Load/Store 与 persistent storage 可直接作为实现基础，不设计 GL3.x 降级路径。**成本：** 视目标而定（5+ 人·月），*风险：* 同步、显存管理、调试复杂度与收益验证要求很高。
  - **单后端深化而非多后端扩张**：当前长期路线继续专注 OpenGL 4.6，不规划 Vulkan、Metal 或移动端后端。`RenderDevice` 等抽象保留用于架构边界、测试和命令协议，而不是承诺多后端实现。只有在出现独立且明确的产品需求时，才重新评估多后端。

## 模块化仓库结构与 API 边界

建议采用模块化仓库：

```
Haikalat/                          # 核心库，无任何具体宿主依赖
├─ backend/      # OpenGL 4.6 Core 单后端封装（DSA/SSBO/Compute/同步等）
├─ core/         # 渲染协议层（CommandBuffer, RenderGraph, RenderDevice 抽象）
├─ render3d/     # 渲染系统（前向管线、阴影、IBL 等）
├─ gltf/         # glTF 2.0 加载与解码
├─ animation/    # 通用骨骼动画运行时（Skeleton, AnimationPlayer 等）
├─ vfx/          # 通用特效系统（粒子、Ribbon、EffectAsset 等）
├─ ui/           # UI 系统（布局、控件、渲染、文本）
├─ ui-animation/ # UI 动画支持（Tween, Transition 等）
└─ runtime/      # 帧驱动、设置、统计与运行时诊断
```

**接口边界：** Haikalat 只定义引擎自身需要的通用资源、帧输入和外部资源协议，例如 `AssetId` 与 `ResourceSource`。生产代码不得引用任何具体宿主的 SDK 或资源类型，也不为尚未实施的适配层预先扩张公共 API。新增边界必须先由独立 Demo、测试和资源生命周期职责证明。

## 所需测试与验证

- **单元测试**：验证动画插值结果、关节层次正确性、VFX 生命周期、渲染通道输出等。可以使用库 (如 JUnit) 对 Math/Transform 等逻辑进行回归测试。
- **集成测试**：创建测试场景（glTF 模型、粒子系统、UI）并在 CI 环境中渲染生成图像，验证无异常、颜色/深度符合预期。可对比参考渲染（如电影风格）进行图像差异测试。
- **性能基准**：使用 `GpuTimer`、管线统计、KHR_debug 标记和固定场景回放，对帧率、CPU 提交、GPU Pass、命令数、状态变更、上传字节和显存占用进行统计。重点验证 OpenGL 4.6 热路径是否真正受益于 persistent mapping、SSBO、Compute 与 MDI，而不是验证旧版本降级。
- **OpenGL 4.6 基线测试**：在 Windows/NVIDIA、Windows/AMD、Linux/NVIDIA 与 Linux/Mesa 等可提供 OpenGL 4.6 Core 的环境验证 capability、驱动差异、同步语义和 Shader 编译行为。低于 4.6 的环境应通过明确的启动报告被拒绝，而不是静默禁用功能。
- **稳定性验证**：长期运行测试应用场景，确保无内存泄漏（所有 `GlResource` 均正确关闭）、线程安全（上传系统等正确同步）。

## 第一年路线图（Haikalat）

现有 `CommandBuffer`、`RenderGraph`、UI、PBR/IBL、HDR/ACES、Bloom、自动曝光、方向光阴影和静态 glTF 链路作为已完成基线继续稳定，不在下表中从零重做。

当前执行状态：

- [x] Milestone 1A：完成无 GL 依赖的 `Skeleton`、`Pose`、`PoseBuffer`、`AnimationClip` 与 `AnimationPlayer`，以及纯 JVM 测试和隐藏窗口动画证明。
- [x] Milestone 1B：补齐 OpenGL 4.6 capability contract 与通用资源 generation/异步 CPU 解码基础，并由纯 JVM 与隐藏窗口测试验证。
- [x] Milestone 2：完成 glTF skin/animation、CUBICSPLINE 与四影响 GPU 蒙皮，并补齐 Color Grading LUT、距离/高度雾和 UI Tween/Transition。
- [x] Milestone 3：完成通用 VFX 基础、点光/聚光阴影与方向光级联方案，并记录确定性性能基线。
- [x] Milestone 4：实现高级动画、GPU 特效实验、综合 Demo 与长期稳定性验证。

Milestone 4 验收证据：`AdvancedAnimationTest` 覆盖混合、Bone Mask、动作事件、循环根运动与
Two-bone IK；`GpuEffectsGlTest` 验证 Compute/SSBO 粒子和有界体积光的真实像素与关闭语义；
`runShowcaseIntegration` 在同一帧图串联动画、PBR、后处理、CPU/GPU VFX 与 UI；
`runShowcaseStabilityIntegration` 连续运行 3600 帧，并要求预热后的 GL 资源序列集合与估算显存不变、
退出后资源归零。API、性能与限制见 `docs/guides/milestone4-api-performance-limitations.md`。

| 阶段 | 时间 | Haikalat 目标 | 验收标准及产物 |
| --- | --- | --- | --- |
| **Milestone 1** | 1–3 个月 | 完成 `Skeleton`、`Pose`、`AnimationClip`、`AnimationPlayer` 与确定性关键帧求值；补齐 OpenGL 4.6 capability contract 和资源 generation 基础。 | 纯 JVM 动画插值、层次与失败路径测试；独立 Demo 播放可控骨骼动画；默认无窗口测试保持通过。 |
| **Milestone 2** | 4–6 个月 | 扩展 glTF skin/animation 解码和 4 影响 GPU 蒙皮；增加 Color Grading LUT、高度/距离雾以及 UI Tween/Transition。 | 动画 glTF 在普通、阴影和 PBR Pass 中结果一致；后处理与 UI 动画具备确定性图像/集成验证和 resize 回归。 |
| **Milestone 3** | 7–9 个月 | 建立 `EffectAsset/EffectInstance`、粒子、Ribbon 与 Decal 基础；增加点光/聚光阴影和方向光级联方案。 | VFX 生命周期、透明排序和资源关闭测试；固定场景验证粒子、尾迹、贴花与多类阴影；记录 CPU/GPU/上传性能基线。 |
| **Milestone 4** | 10–12 个月 | 完成动画混合、Bone Mask、根运动、动作事件和 Two-bone IK；增加 GPU 粒子与体积效果的受控实验；完善 UI 动画和诊断。 | 独立综合 Demo 串联动画、VFX、PBR、后处理与 UI；长时间资源稳定性验证通过；形成 API、性能和限制说明。 |

**注：**每个里程碑都必须产出相应测试场景、验证任务和文档。验收只覆盖 Haikalat 本体的功能正确性、资源生命周期、性能和公共 API 边界；宿主适配在 Haikalat 稳定后另立计划。
