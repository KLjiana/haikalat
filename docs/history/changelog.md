# 变更记录

## v0.20.2（2026-08-06）

- 新增 `haikalat.animation-clip/1` 紧凑外部动画格式：按模型节点名绑定
  translation / rotation / scale / weights 轨道、STEP/LINEAR 关键帧和事件标记；无须
  glTF accessor 或 base64 buffer，缺失节点、重名、重复目标、越界时间和非法四元数会在
  CPU 解码阶段拒绝。原有 `.animation.gltf.json` sidecar 仍要求显式节点绑定。
- `player_wild` Demo 接入 8 个外部动画 clip 和 `steve.png` 纹理，覆盖 stand、move、run、
  idle_sword、attack_light、start、idle_dash、end，共 98 条 TRS channel；Demo 按 0.65x
  播放并在 clip 切换时使用 0.22 秒运行时姿势过渡。
- `GltfSceneInstance` / `AnimationMixer` 支持从当前可见姿势生成直接 cross-fade；过渡期间
  再次切换会从已混合姿势继续，保留 `play(...)` 的立即切换语义，供状态机和外部动画调用。
- 新增独立现代游戏主界面 `ModernUiDemo`：双语战役菜单、玩家档案、任务进度、设置页、
  音量/显示/动态效果/界面缩放控件、页面切换、按钮反馈、呼吸动画、UI VFX 与 reduced-motion
  路径均由 retained UI 组件和正式 runtime 驱动，不依赖旧 `UiDemo`。
- UI 节点新增 style class，`UiConfig` 支持注入 `StyleResolver`；现代主题集中管理 SDF 圆角、
  CJK/Latin/JetBrains Mono 字体和控件状态样式。新增现代主界面 JVM 场景测试、90 帧确定性
  交互脚本和 1280×720 真实 GL framebuffer 像素证明。
- 补充外部动画导入、模型纹理、动画平滑过渡和现代 UI 的使用指南、Demo 职责矩阵与新人文档。

## v0.20.1（2026-07-29）

- 新增宿主无关的 `PresentationTarget`、`ExternalAttachment`、`AttachmentRole` 和
  `PresentationResult`，显式描述 draw/read FBO、color/depth/stencil、尺寸、采样、
  generation 与 `OWNED` / `BORROWED` 生命周期；borrowed 资源不会被删除、重分配或修改采样参数。
- RenderGraph 支持导入和逐帧替换 borrowed color/depth/stencil/presentation target，
  pass 可写入命名或当前 presentation target；`0×0` 目标在录制、上传和提交前安全跳过。
- `RenderPipeline` 新增 `ExternalCamera + PresentationTarget` 显式入口，几何、可见性、
  PBR、阴影、雾、HDR VFX 和最终 LDR/HDR/FXAA 输出使用宿主相机与目标；旧窗口和
  framebuffer `0` 路径保持兼容，pipeline 不 present 或 swap。
- `UiRenderer` 可显式写入宿主 FBO 和尺寸；Bloom、tone mapping、FXAA、HDR VFX 与 UI
  已通过同一非零目标的真实像素验证。
- 新增无窗口、无线程、无主循环的 `HaikalatRuntime.createEmbedded(RenderDevice)`，
  对宿主提供的 RenderDevice 保持 borrowed 语义并强制创建线程调用。
- 新增嵌入渲染 GL state scope，在成功和异常路径恢复宿主 framebuffer、viewport/scissor、
  program/VAO、buffer/texture/sampler/image binding 与 blend/depth/cull/stencil 等状态。
- 新增 `embeddedJvmVerification` 和 `embeddedGlVerification`：验证宿主 raw 非零 FBO、
  color 输出、真实 depth 遮挡、resize/replacement、zero extent、重复 close、
  borrowed deletion sentinel、外部相机以及 UI/VFX/postprocess 同目标；Minecraft/NeoForge
  适配和客户端烟雾测试仍留在 HaikalatHost。

## v0.20.0（2026-07-29）

- 在不扩大 public API allowlist 的前提下收敛 UI 内部职责：`UiSystem`、`UiNode`、
  `UiRenderer` 与 `UiCompositor` 保留 facade，新增 coordinator、dirty/tree/event、
  snapshot publisher、SDF renderer、compositor plan 和 GL resource owner 等
  package-private 组件。
- 静态 UI 现在复用上一张 immutable display-list snapshot；glyph upload 完成、可见
  paint dirty 和 effect 生命周期变化才触发重录。`UiInputRouter` 缓存稳定 hit-test，
  `ListView` 对未变化 viewport 采用 no-op fast path。
- 动画内部新增容量复用的 cursor/run list 与有界优先级 signal queue，统一
  `AnimationController`、`UiAnimationSystem` 和 `UiTimeline` 的重复时间/删除语义，
  旧 Player、graph 和 UI animation 入口保持兼容。
- `RenderGraph`、`RenderTargetManager`、`TaaHistory` 与 UI GL owner 的资源替换采用
  candidate-first：候选完整成功后才激活，失败保留旧 generation；close 继续逆序清理
  并聚合 suppressed exception。
- M7 五轮 UI 容量基准的十个静态测量窗口 snapshot publication 均为零，draw/batch
  未增加；10,000 quad 的剩余约 `255 KiB/frame` 被明确记录为 render/GL submission
  预算。静态 all-visible CPU 为 `2.984 ms`，与 `2.977 ms` 原始基线基本持平，
  不再复现 v0.19.4 的独立 `3.567 ms` 回退。
- M8 修复窗口 resize 误重建 fixed-size shadow target 的生命周期回归，并让 async UI
  integration 使用确定性视觉 revision 驱动 36 次 latest-wins publication，不再依赖计时文本
  四舍五入结果。
- glTF 新增 `haikalat.gltf-animation-library/1` 外部动画库：支持直接加载导出 ZIP，或从
  classpath/资源目录读取解包后的 `animation-library.json`；模型已有动画与多个外部
  `.animation.gltf.json` clip 会按清单合并，并严格校验节点索引、名称绑定、唯一动画名、
  相对 URI、归档重复条目和资源上限。序列化场景会追踪主模型、动画 JSON 与外部 buffer，
  使任一依赖变化都能触发正确的 generation 失效和热重载。
- 新增 `player_wild` 外部动画 Demo 与 `runPlayerWildDemo` /
  `runPlayerWildIntegration`：真实 Blockbench 模型的 1 个 skin、外部 `animation`
  clip 和 11 条 rotation channel 已通过运行时映射、GPU 蒙皮、可见性及循环播放验证。
- 最终 710 项 JVM 与 710 项 GL 测试、UI/scene/resource integration、public API、
  architecture、asset 和 GL debug policy 门禁全部通过；详细性能与 snapshot 验收分别见
  `docs/performance/v0.20-m7-2026-07-28.md` 和
  `docs/releases/v0.20.0-release-report.md`。正式发布门禁在完整 staged candidate 上执行。

## v0.19.4（2026-07-28）

- 完成 0.19.1 UI SDF 基础：`SDF_SHAPE` display-list、独立参数 arena、rounded
  rect/pill/ellipse/ring/arc、border/gradient、radius-aware 控件绘制和真实 GL 像素验证；
  普通 quad/image/glyph 路径保持兼容。
- 完成 0.19.2 现代 UI 动画：`UiVisualTransform` 同时进入 snapshot 顶点与逆变换命中，
  `UiPropertyTrack` 覆盖 color/radius/opacity/transform/scroll/value/effect 属性，
  `UiTimeline` 提供 sequence/group/stagger/repeat/reverse、marker signal、enter/exit、
  reduced motion 与显式 FLIP。
- 完成 0.19.3 显式 layer/compositor topology：稳定 layer id/tree、begin/end snapshot 边界、
  managed sRGB/linear target、双轴 blur target、backdrop source 合同、预算、resize 与 direct
  fallback；advanced snapshot 暂不把 subtree replay 到 offscreen target。
- 完成 0.19.4 screen-space UI VFX：固定种子 shimmer/ripple/dissolve/spark/confetti/trail/
  scanline/glitch、signal 去重 bridge、detach/cancel、容量诊断、reduced-motion fallback 与
  3,600 帧 soak；VFX 使用 UI SDF/quad path，不进入 scene exposure/Bloom。
- 新增不依赖 `UiDemo` 的独立 `ModernUiDemo`、首帧 UI GL 资源隐藏预热、延迟显示窗口和
  60 帧真实 GL 像素证明；确定性入口每 15 帧重触发过渡并限制 active effect 不超过 3。
  800×520 最终帧为 15 nodes、320 quads、16 draws、3,359 非清屏采样，UI ring 累计等待
  0.55 ms，最大可见帧 13.67 ms。
- 新增统一内建字体目录 `BundledUiFonts`，集中管理 Noto Sans SC、Unifont 与
  JetBrains Mono 2.304 的资源、字体族和确定性 fallback 顺序；ModernUiDemo 使用
  JetBrains Mono 显示 Latin/数字，CJK 自动回退，UiDemo 不再自行读取字体文件。
- 新增严格 UTF-8 `haikalat.scene` v1 parser、显式 `ResourceCatalog`、`AssetByteResolver` 和
  scene/glTF 外部依赖追踪；相对引用、重复字段、父节点环和有界输入会在 CPU 阶段拒绝。
- 新增 `SceneAssetService`/`SceneBuildPlan`/`SceneHandle`/`SceneVersion`：scene、glTF 与图片
  RGBA8 在后台解码，GPU 资源在 GL 线程以 step/time/byte budget 分阶段上传。
- 新增 exact `(AssetId, generation, variant)` glTF GPU cache、lease 共享、失效传播、手动 reload、
  directory watcher、事务式 `RenderPipeline.replaceScene` 和有界结构化诊断。
- 增加 `sceneJvmVerification`、`sceneAssetGlVerification`、`localSceneAssetVerification`，
  以及外部依赖、失败保留旧版本、staged upload 和 RGBA8 上传回归。
- 发布限制：未接入正式 SerializedSceneDemo，`RenderPipeline` 同拓扑替换仍走候选 graph
  重建；UI offscreen subtree replay/cache reuse 仍保留 direct visual fallback。上述限制已在
  正式版验收报告中明确记录，不影响已验证的普通 UI、动画、compositor topology 与 screen-space VFX
  路径。

## v0.18.3（2026-07-27）

- 新增确定性 `AnimationGraph/AnimationController`：typed parameter/trigger、state/transition、
  exit time、queued/interruption、倒放、incoming-only signal，以及 1D 和显式 triangle 2D Blend Tree。
- 新增最多 8 层的 `AnimationLayerStack`，统一 Override/Additive、Bone Mask、fade、自动移除、
  root-motion policy 与 Morph additive reference；pose、root motion 和 Morph 使用同一 motion 权重。
- 新增 Marker/Sync/结构化 `AnimationSignal` 与 Demo 侧 `CharacterEffectBridge`，以单向依赖驱动
  Particle/Decal/MeshVfx；保留 Player/Mixer 兼容 API。
- 新增 Look-at、JointLimit、连续链 FABRIK、双手约束与纯输入 Foot IK；constraint 不查询
  Scene/Physics，PoseBuffer 使用 subtree dirty tracking。
- glTF 新增 POSITION/NORMAL/TANGENT Morph Target、mesh/node default weights 与
  STEP/LINEAR/CUBICSPLINE weights animation；每 primitive 上限 8，独立 delta byte budget。
- asset-owned delta SSBO 与 instance-owned weight SSBO 分离；PBR 和 directional shadow 固定
  Morph-before-skin，支持 conservative bounds、zero-target 路径、失败回滚和关闭归零。
- 新增 Animation/Morph JVM、真实 GL、Showcase bridge 和五轮性能入口；Graph steady-state
  实测 0 B/角色/帧，完整数字见
  `docs/performance/v0.18.3-animation-runtime-and-morph-2026-07-27.md`。

## v0.18.2（2026-07-26）

- 合并经内部验收但未独立发布的 v0.18.1 曲线、VFX over-life、动画 cross-fade/layer fade 和
  Ribbon miter 修复；不创建 v0.18.1 release commit/tag。
- 新增纯值 `VfxMaterial/VfxVisualSet/VfxUvRegion`，以显式 mask、Alpha/Additive、emissive、
  billboard/stretch 和 soft-particle 参数扩展 `EffectAsset` 与不可变 snapshot，同时保留 legacy constructor。
- 新增 renderer-owned VFX texture cache；mask 以 mipmapped `R8` 上传，彩色纹理使用 sRGB storage，
  particle、Ribbon 与平面 Decal 统一使用 textured quad，透明顺序不因材质复用而重排。
- Ribbon stretch UV 使用整条快照路径的反向累计弧长，约定 U 为横截面、V 为轨迹方向，使最新头部
  采样方向纹理亮端；相邻段共享同一个 miter offset，不再用 additive overlap 补缝，消除周期性亮接头。
- 新增 `RenderPipeline.hdrVfx`：自动曝光读取 base HDR，VFX 在带 resolved scene depth 的 `RGBA16F`
  composite 中完成遮挡和 soft fade，Bloom 读取 composite，UI 保持在 tone mapping 后。
- VFX 采用“类型 / 作者 / 素材包 / 资源”单层目录，完整素材与许可记录进入 JAR；
  `vfxAssetGuard` 校验目录命名、打包完整性和代码直接引用。
- VfxDemo 新增 legacy/fire/impact/trail/all、HDR/Bloom/soft-particle 和尺寸参数；Showcase 新增
  NONE/MSAA/FXAA/TAA、fog 与 auto-exposure 验证入口。
- 1080p/4K 的 768 粒子、159 Ribbon、2 Decal 基线分别为 GPU `0.3225 ms` 与 `0.6408 ms`；
  完整数据见 `docs/performance/v0.18.2-textured-hdr-vfx-2026-07-26.md`。

### v0.18.1 内部候选（待合并发布）

- 新增无 GL 依赖的 `core.curve`：`Curve1f/Curves/CubicBezierEasing`、STEP/LINEAR/Hermite
  `FloatTrack`、线性 HDR `ColorGradient`、固定弧长表 `BezierPath3f` 和 `CurveLut`；
  cubic-bezier 先反解 `x(u)=time` 再采样 `y(u)`，Newton 退化时使用固定轮数二分。
- `UiEasing` 保留原 public enum 与像素语义，内部委托共享 preset；glTF 的 STEP、LINEAR 和
  CUBICSPLINE sampler 未改变，也不映射 cubic-bezier。
- `EffectAsset.Builder` 新增 particle size/color/rotation、Ribbon width/color 和 Decal scale/color
  over-life 配置。原 emitter/decal record constructor 未变，未配置曲线时继续执行 0.18.0 线性路径。
- `AnimationMixer` 新增 `transitionBase`、`fadeLayerTo` 与 `fadeOutLayer`；支持中途重定向、incoming-only
  事件语义、同权重根运动混合、零时长立即切换，以及 Bone Mask 同时工作。
- `VfxDemo` 使用两段切线连续的 `BezierPath3f` 近似匀速驱动闭合无限符号 emitter，完整保留一圈
  Ribbon 并支持 curved/linear preset；Showcase 在应用层
  串联 incoming animation event 与 curved VFX，不增加 animation/VFX 反向依赖。
- Ribbon adapter 按相邻段 camera-facing side 计算有上限的共享 miter offset，两段使用完全相同的接头
  边界，封闭大曲率楔形缝隙且不产生 additive 重叠，也不增加 draw call 或修改 `EffectSnapshot` 合同。
- 10,000 粒子 no-GL A/B 在 12 次 warmup、40 次交替采样下测得 linear median `0.5720 ms`、
  curved median `0.5278 ms`；两侧每次 snapshot 分配量同为 `1,241,864` bytes，无新增分配回归。

## v0.18.0-haikalat-opengl46-roadmap（0.18.0，2026-07-25）

- 新增无 OpenGL 依赖的 `subsystems.animation`，包含骨架层次、绑定姿态、可复用 `PoseBuffer`、不可变 `Pose`、STEP/LINEAR/CUBICSPLINE TRS 关键帧采样和 LOOP/ONCE 播放游标。
- 新增 `AnimationDemo` 与 `runAnimationIntegration`，使用固定步长和末端关节位移断言证明 CPU 动画求值可通过现有命令流绘制。
- glTF 2.0 链路新增 skin、inverse bind matrix、animation sampler/channel、四影响 `JOINTS_0/WEIGHTS_0` 规范化、每实例 `JointPalette` 与 SSBO GPU 蒙皮；PBR forward 和方向光 shadow pass 共享同一动画姿态，并由确定性 two-joint fixture、真实 GL 像素与资产生命周期测试验证。
- 后处理新增无 GL 依赖的 tiled 2D `ColorGradingLut`、颜色分级设置与距离/高度雾设置；GL adapter 在 HDR、Bloom/曝光之后、tone mapping 之前组合雾，并在 ACES 与 gamma 之间应用 LUT。
- UI 新增无 GL 依赖的 Tween/Transition subsystem，支持 visual/layout 通道、linear/cubic/spring easing、delay、取消、替换和节点关闭清理；`UiDemo` 与 GL 测试覆盖 fade、spring layout、snapshot 像素及 resize。
- 新增无 OpenGL 依赖的 `subsystems.vfx`：`EffectAsset/EffectInstance` 管理资产实例生命周期，固定种子粒子、Ribbon 控制点和 Decal 使用有界 CPU 模拟并发布按相机距离稳定排序的不可变快照；`VfxRenderer` 是独立 render3d GL adapter。
- 新增 `VfxDemo`、`runVfxIntegration` 与 `runVfxPerformanceBaseline`；128 粒子 resize 集成覆盖三类 primitive，512 粒子基线记录 CPU update、RenderGraph GPU pass、draw 和 uniform payload。
- 新增 3×2 六面点光 depth atlas 与聚光透视 depth map，PBR/legacy shader 使用各自 PCF 可见度；新增 2～4 级 practical split、world-texel 稳定的 `DirectionalCascadePlan`，并由矩阵、PBR 像素 A/B 与综合 Demo 验证。
- 高级动画新增 `AnimationMixer`、`BoneMask`、`PoseBlender`、有序 `AnimationEvent`、跨循环 `RootMotionDelta` 和 `TwoBoneIkSolver`；纯 JVM 测试覆盖非拓扑骨架、别名目标、事件边界、循环位移/旋转、不可达目标和部分 IK 权重。
- 新增 `GpuParticleExperiment`：单 SSBO、64 线程 compute workgroup、显式 shader-storage barrier 和几何阶段 billboard 批次，正式路径没有 CPU map/readback；新增固定 4～128 步的 `VolumetricLightPass` 受控实验。
- UI 动画句柄新增暂停、恢复与归一化进度，`UiAnimationDiagnostics` 提供 started/completed/cancelled/replaced、通道分布、暂停数和峰值并发的确定性快照。
- 新增 `HaikalatShowcaseDemo` 及 `runShowcaseIntegration`、`runShowcasePerformanceBaseline`、`runShowcaseStabilityIntegration`：同帧串联高级动画、PBR/阴影、Bloom/LUT/雾、CPU/GPU VFX、体积光与 UI。3600 帧运行验证预热后 live GL resource identity/估算显存不变且退出后归零。
- 新增 Milestone 4 API、性能与限制说明；所有本阶段 public 类型归入现有 animation/render3d/postprocess/ui advanced 清单，范围明确排除 HaikalatHost 与宿主集成。
- 将 NVIDIA preview benchmark 的精确 shader-specialization 日志策略上限调整为五轮四模式矩阵的理论上限 120；消息来源、ID、文本和任务范围仍严格匹配。
- 公共 API 目录新增 animation 域，并用架构测试禁止 animation subsystem 引用 backend 或直接调用 OpenGL。
- 新增 `GlCapabilityContract`，将 OpenGL 4.6 Core、DSA、SSBO、Compute、Image Load/Store、Buffer Storage、MDI、Shader Draw Parameters 和 debug output 固定为生产硬要求，并单独报告 bindless texture 可选能力。
- 新增无 OpenGL 依赖的 `subsystems.resources`，包含规范化 `AssetId`、有界 `ResourceSource`、逐资源 generation 票据和虚拟线程/调用方 executor 异步 CPU 解码。
- `GlRenderDevice` 在真实命令提交边界强制 capability contract，`GlRenderThread` 在初始化钩子前提前验证；新增纯 JVM 缺失项测试和真实隐藏窗口 4.6 Core 验证。
- 项目许可证明确为 `AGPL-3.0-only`，根目录加入 GNU AGPLv3 完整许可证文本。
- `jar` 任务现在同时生成二进制 JAR 与 `-sources.jar`，两个制品均携带 `META-INF/LICENSE` 和许可证 manifest 元数据。
- 统一 Java API 文档为中文说明，并固定 Javadoc 输入、页面和输出编码为 UTF-8。

## 已完成路线图

已完成的里程碑集中记录在这里，规划文档只保留后续工作和仍需验证的内容。

### v0.1-stable-demo

- 精简 LWJGL 依赖，并按操作系统选择原生库。
- 将 Demo 源码拆分到 `src/demo/java`，保留可直接从 IDE 启动的入口。
- 在 README 中补充运行环境和启动方式。
- 修复窗口 resize、零高度投影、空场景 clear/present、异步初始化和资源清理问题。

### v0.2-tested-core

- 为 `VertexPacking`、`RenderGraph`、`UploadSystem` 和 `TripleBuffer` 增加纯 JVM 测试。
- 增加 Windows/Linux CI 编译和无 OpenGL 上下文单元测试。
- 收紧 RenderGraph pass 依赖与命令捕获规则。
- 增加正式 framebuffer blit 命令，减少 `custom()` 使用。

### v0.3-materials

- 分离材质模板和材质实例状态。
- 增加类型化 `UniformValue`、UBO、采样器配置和明确的资源所有权规则。

### v0.4-postprocess-graph

- 将 FXAA 和 TAA 接入 RenderGraph pass。
- 增加后处理模式选择、纹理命名约定、TAA jitter/history 验证和 resize history reset。

### v0.5-instancing-framebuffer

- 扩展实例批处理，支持按 mesh 分组提交、同步、persistent mapping 评估、统计和可替换实例布局。
- 增加 MRT、深度纹理、framebuffer descriptor、render target 生命周期和 resize 重建流程。

### v0.6-engine-surface

- 建立最小 backend 边界，引入 `RenderDevice` 和资源 barrier 概念。
- 增加场景数据、forward pipeline、基础光照、deferred pipeline 评估和方向光阴影。
- 增加模型加载、纹理缓存、shader 热重载、资源定位和可配置 Demo 场景。
- 增加 pass 级 GPU 计时、CPU/GPU frame profile、调试叠加数据、GL object label 和渲染错误分类。

### v0.7-stable-assets

- 完成稳定资产闭环：校验 manifest 引用，打包自有 OBJ 基准模型，并通过 `ModelAssetManager`、`ObjModelLoader` 映射到场景 renderer。
- 定义 `.properties` 作为资产 manifest 和简单 Demo 场景绑定格式，统一 builtin mesh 名称并清理重复配置。
- 引入纯数据 `MeshData`、`MaterialDef`，将 `Mesh.from(MeshData)` 和 `Material` 明确为 OpenGL 资源边界。
- 将 Minimal、Async Demo 的窗口、render target、mesh、frame driver、上传和清理迁移到引擎 API，移除默认包旧入口及私有重复 shader。
- 用不可变 latest-frame snapshot 替代裸引用 `TripleBuffer`；AsyncDemo 只在矩阵 UBO 上传成功后发布匹配的帧元数据。
- 增加 `FrameClock`、`PeriodicTimer` 和统一的 `RenderStatistics`，分离 present FPS、CPU submit 与 GPU 时间，避免输入事件改变动画速度。
- 固定 `subsystems/runtime -> core -> backend` 依赖方向，清除 backend 对 core 以及 core 对 runtime 的反向依赖。
- 拆分 `RenderPipeline` 内部职责，收紧 `PassResources`、`RenderDevice` 和 RenderGraph 的公开边界。
- 增加 resize、异步线程失败/超时/清理、资源生命周期、架构依赖和隐藏窗口像素回读测试。

### v0.8-instanced-shadow-hdr

- 为实例 renderer 增加显式 `castShadows`，普通 caster 与实例 caster 共享方向光 shadow pass，并分别统计数量。
- 修复多方向光阴影关联：上传明确且有界的 shadow light index，最终 shader 不再默认把阴影套到第 0 盏灯。
- 增加完整的最终像素回归，覆盖有光/无光、受光区/阴影区、移动 caster 和改变方向光后的阴影更新。
- 建立兼容优先的 HDR 配置：默认维持 LDR，显式 `ToneMappingMode.ACES` 使用 exposure、ACES fitted curve、clamp 和 gamma 编码。
- HDR geometry、MSAA resolve、TAA history/accumulation 使用 `RGBA16F`，tone-mapping 输出使用 `RGBA8`；TAA 在线性空间累积，FXAA 在显示空间执行。
- 完成 NONE、MSAA、FXAA、TAA 四条 LDR/HDR pass 数据流、resize 重建和真实 GL 像素验收。
- 将 fullscreen pass 的 blend、depth test、depth mask、cull 和 framebuffer sRGB 状态改为 pass 显式所有，避免继承上一 pass 或上一帧状态。
- 将 `CommandBuffer` 改为紧凑 typed opcode stream，并引入 pending pipeline state；只在 draw、clear、blit、dispatch、query、pass 和 `custom()` 等可观察边界提交最终差异。
- 保留 `StateCache` 的跨命令、跨帧去重；`custom()` 成为 flush 后完整失效屏障，透明排序和 RenderGraph pass 顺序语义保持不变。
- 增加正式 indexed-instanced draw：Cube 使用 8 个 bit 解码角点和 36 个 `GL_UNSIGNED_BYTE` 索引，Quad 使用 4 个逻辑顶点和 6 个索引，均不需要 Cube/Quad VBO。
- 增加 16 字节 `std430 uvec4` compact instance SSBO、对齐的 persistent-mapped ring、slot fence 和 dirty-range 更新，保留 Matrix4f dynamic 模式作 A/B 对照。
- 扩展 procedural shader generator，从 topology 生成专用 GLSL、Java catalog、索引数量与类型，生成结果只进入 `build/generated`。
- StressDemo 增加 `gpu`、`indexed`、`indexed-ssbo`、`dynamic` 路径以及 Triangle/Quad/Cube 旋转控制；输出 present FPS、CPU/GPU 平均值与中位数、draw call、状态跳过率和 VS invocation。
- 增加 EmptyWindowDemo 与 Nsight 启动脚本，作为同分辨率、clear、present、VSync 和 debug 条件下的无绘制基线。

### v0.9-color-bloom-closeout（0.9.0，2026-07-15）

#### 色彩空间与 LDR/HDR 输出

- 引入明确的 `TextureColorSpace.LINEAR/SRGB` 契约；颜色纹理使用 `GL_SRGB8` 或 `GL_SRGB8_ALPHA8`，法线、roughness、metallic、遮罩和查找表继续使用 linear 格式。
- texture cache key 现在包含资源路径、flip 和 color space，避免同一资源按不同语义加载时错误复用。
- LDR geometry、最终后处理和 TAA history target 统一使用 `SRGB8_ALPHA8`，由 RenderGraph 根据 attachment 格式负责 framebuffer sRGB 状态。
- NONE、MSAA、FXAA、TAA 的 LDR present 只复制已经编码的字节；无论默认 backbuffer 被驱动报告为 linear 还是 sRGB，都不会漏编码或重复编码。
- HDR/ACES 继续在线性 `RGBA16F` 中渲染和累积，并由 tone-mapping shader 显式 gamma；对应 pass 显式关闭 framebuffer sRGB，避免双重转换。
- MinimalDemo 增加确定性的 sRGB LDR 集成验证，覆盖最终 present 像素，而不仅是纹理格式或非空画面。

#### Bloom 与 RenderGraph 资源

- 增加不可变 `BloomSettings`，支持 threshold、soft-knee、intensity 和 1～8 个 level；默认关闭，不改变旧调用方的 pass 计划和画面。
- RenderGraph 支持相对窗口尺寸 target，并在 resize、奇数尺寸和最小化恢复后按至少 1×1 正确重建。
- Bloom 在线性 HDR scene 上执行高亮提取、逐级 downsample 和 upsample，最终半分辨率结果直接交给 tone mapping 合成，不额外创建全分辨率 HDR combine target。
- 四种 HDR/AA 路径均完成真实 GL 最终像素回归；disabled 路径保持原 ACES 输出，enabled 路径验证亮区扩散且暗部不被错误抬升。
- 增加 pass 级 Bloom GPU 计时和正式五轮性能报告，数据记录在 `docs/performance/post-v0.8-bloom-2026-07-15.md`。

#### 实例批次、同步与异常收尾

- 10 万实例阴影基准达到优化触发条件后，引入 typed single-upload/multi-pass 命令；shadow 与 geometry 复用同一帧 snapshot、同一个 persistent-ring slot 和一次上传。
- GPU fence 只在批次最后一次 draw 后插入；下一帧 ring slot 复用前仍执行完整同步检查。
- `CommandExecutor` 显式跟踪所有已 prepare 但未 finish 的批次；任一 geometry/pass 命令失败时，按逆序完成剩余批次的 fence、snapshot 和 ring 清理。
- 清理阶段的附加异常作为 suppressed exception 保留，原始渲染异常不会被覆盖。
- 增加真实 GL 回归：在 prepared multi-pass 的 geometry 阶段主动失败后，验证 fence 已插入、快照已释放，并且下一帧能够复用 ring 正常绘制。

#### 命令与状态提交

- pending pipeline state 覆盖 blend、depth test、depth mask、cull、viewport、clear color 和 framebuffer sRGB，只在可观察边界 flush 最终状态差异。
- depth clear 前强制提交 `depthMask=true`，避免前序透明或后处理状态阻止深度清除。
- GPU timer query 使用正式 opcode；`custom()` 保持 flush、执行和完整 cache invalidation 的屏障语义。
- 增加状态折叠、draw/pass 顺序、透明物体顺序、depth-mask clear 和真实 GL 状态回归，确认优化不跨越语义边界。

#### 压力测试与运行工具

- 压力入口默认提升到 100 万实例，同时保留 10 万实例的两帧正确性集成，避免把启动检查误当成正式性能结果。
- 统一空窗口、Triangle、Quad、展开 Cube、Indexed Cube、Indexed+SSBO Cube 和 Dynamic Cube 的窗口、分辨率、clear、present、VSync 与 debug 条件。
- 正式基准采用 100 帧预热、1000 帧采样和多轮测试，记录 present FPS、CPU submit、GPU 时间、draw call、状态跳过率及可用时的 VS invocation。
- 详细性能数据记录在 `docs/performance/stress-indexed-ssbo-2026-07-14.md`、`stress-matrix-copy-fix-2026-07-15.md` 和 `stress-one-million-2026-07-15.md`。

#### 发布验证

- Gradle 稳定版本更新为 `0.9.0`，Java Toolchain 保持 21。
- 完整执行 `compileJava demoClasses test localGlVerification --rerun-tasks`，21 个任务全部执行并通过。
- 验收覆盖纯 JVM 测试、真实 GL smoke、LDR/HDR 四种 AA、Bloom、阴影最终像素、resize、Minimal、LearnOpenGL、Async、空窗口以及压力测试入口。
- 验收未发现 GL error、线程悬挂、漏 fence、prepared batch 泄漏、ring 次帧复用失败或 resize 资源错误。
- `git diff --check` 通过；当前能力与限制同步记录在 `docs/planning/capability-matrix.md`。

### v0.10-gpu-auto-exposure（0.10.0-rc.1，2026-07-15）

- 新增 `ExposureMode.MANUAL/AUTO` 和不可变 `AutoExposureSettings`；手动 exposure 继续作为默认值和自动 history 初始值。
- backend 增加 `R16F` 单通道与 `RG32F` 双通道浮点 render target，descriptor 分别使用正确的 `GL_RED`、`GL_RG` 和 `GL_FLOAT` 元数据。
- 新增全 GPU `AutoExposurePass`：按 Rec.709 提取对数亮度，逐级归约到 1×1，再以帧率无关指数公式适应目标曝光。
- reduction target 使用 RG32F 显式携带 `logLuminance sum` 与像素 weight；第一级把 R16F texel 视为 `(value, 1)`，后续级直接累加 RG，彻底移除从尺寸反推权重的逻辑。
- 自动曝光固定 14 级拓扑：前 13 级为 `1/2`～`1/8192` 相对尺寸，末级固定 1×1；resize 只重建 graph target，两个 exposure history 保留，正式支持最大 16384 像素边长。
- 两个持久 1×1 `R16F` history 逐帧 ping-pong；只有整张 RenderGraph 成功执行后才交换，失败帧保留上一张有效 history。
- Tone Mapping 在 AUTO 模式直接采样本帧 exposure texture，生产路径没有 `glReadPixels`、`glGetTexImage`、同步 map 或 `CommandBuffer.custom()`。
- 自动曝光与 Bloom 从同一个 resolve/TAA 后的线性 HDR source 分支，测光不受 Bloom 扩散反向影响；Tone Mapping 同时依赖两条分支。
- `RenderPipeline.execute(RenderDevice, deltaSeconds)` 接入 `FrameClock`，同时限制调试暂停产生的异常大 delta；旧重载保持确定性 1/60 秒。
- Main Demo 增加 `--auto-exposure`、确定性明暗切换集成和模式标题；不为了 UI 显示逐帧回读曝光值。
- RenderGraph 增加窄用途 external-target pass，用正式 framebuffer 命令支持跨帧 history，同时保留 pass 排序和 GPU timer profiling。
- `Scene.setLight()` 只接受保持 `LightType` 与 `castShadows` 的动态更新；结构变化报告 index/字段并要求重新 build pipeline。
- 增加 R16F/RG32F、9×1 完整归约、1280×1 边缘对称、1×1 构建后 resize 到 47×33、灯光结构边界、失败帧 history、四种 AA、Bloom 开关和重复关闭的纯 JVM/真实 GL 回归。
- 1080p/4K 五轮正式基准显示 AUTO GPU median 增量分别为 `0.215 ms` 和 `0.435 ms`；详细数据见 `docs/performance/v0.10-auto-exposure-2026-07-15.md`。
- `clean compileJava demoClasses test localGlVerification --rerun-tasks` 的 23 个任务全部通过，版本进入 `0.10.0-rc.1`。

### v0.11-retained-ui（0.11.0，2026-07-17）

#### Tree、layout、控件与输入

- 新增每窗口 `UiSystem/UiDocument/UiNode` retained tree，定义稳定 ID、parent/child invariant、
  dirty propagation、关闭后拒绝使用和单 update-thread 所有权。
- 通过 LWJGL Yoga 实现 row/column、grow/shrink、min/max、percent、padding/margin、gap 和
  intrinsic text measure；Yoga symbol 被架构测试限制在 `subsystems.ui.layout`。
- 增加 typed theme/style、capture-target-bubble event、pointer capture、焦点与 focus scope，
  并提供 Panel、Label、Image、Button、Toggle、Slider、ScrollView、virtualized ListView、
  TextField、Popup 和 Menu。
- GLFW callback 汇总为严格递增的不可变 `WindowInputSnapshot`，明确 logical window、framebuffer、
  content scale、按键边沿、鼠标、滚轮、Unicode commit 和 composition；失焦会合成 release。
- TextField 完成 selection、grapheme deletion、clipboard、undo 和 composition/preedit；Windows
  `Win32TextInputAdapter` 使用 JNA 可链式 WndProc hook，并保持 GLFW char 为唯一 commit 来源。

#### 文本与 GPU 渲染

- 接入 FreeType 2.13.2、HarfBuzz 8.2.0 和 Sans2.004 Noto Sans SC variable TTF；字体来源、
  SHA-256 与 OFL-1.1 完整归档，Demo 与自动测试不依赖开发机字体。
- 新增 Latin/CJK fallback shaping cache、word/CJK/grapheme wrap、cluster-safe ellipsis、DPI ppem、
  glyph rasterization 与有 generation/in-flight guard 的多页 CPU atlas。
- GPU atlas 使用 R8 texture page 和 typed texture-region upload；整批命令成功后才发布 placement，
  失败批次保留并重试，没有 CPU readback 或生产 `CommandBuffer.custom()`。
- UI display list 只合并相邻兼容 primitive，保持透明 paint order；renderer 使用 premultiplied
  alpha、framebuffer sRGB、typed scissor、uint16 EBO、VAO 高水位池和三槽 persistent-mapped ring。
- `GpuFenceTarget.executionFailed` 与命令执行器补齐未到达 fence 的失败通知，确保 draw/upload
  命令中途失败后 ring 和 atlas completion 不停留在 active 状态。

#### RenderGraph、线程与 Demo

- `RenderPipeline.finalPassName()` 成为 UI 与 3D/postprocess 的唯一 composition 锚点；UI 始终在
  最终 backbuffer pass 后绘制，不进入 Bloom、自动曝光、TAA 或 FXAA。
- 新增真实 GL pipeline 矩阵，执行 40 个合法运行项，覆盖 LDR/HDR、四种 AA、Bloom 开关、
  manual/auto exposure 和 UI 开关；每个 UI 组合均产生正式 draw 且无 GL error。
- RenderGraph 可查询 backbuffer pass 并冻结拓扑；每个 pass 基线显式关闭 scissor，避免 UI
  状态泄漏到下一 pass/帧。`StateCache` 增加 scissor 跨命令/跨帧去重。
- snapshot exchange 支持同步双槽和异步三槽 latest-wins。正式异步集成在 12 个 render frame
  发布 36 张 snapshot、丢弃 24 张旧 snapshot，并按 render GL resource → update/native resource
  顺序完成双阶段关闭。
- 新增 `UiDemo`、LearnOpenGlDemo overlay、Nsight 启动脚本以及 deterministic、resize、async、
  text 四条 integration；`localUiVerification` 聚合真实 GL smoke，`localGlVerification` 纳入该任务。
- 修复 ScrollView 内容被 viewport 的 flex shrink 压扁、ListView 只移动 cell 外框却遗漏 Label
  子树，以及 Popup 默认占据左上角的问题；虚拟 cell 现在通过 materialized host 偏移保持完整
  子树坐标，Popup 按 owner 下边缘锚定，UiDemo 启动时不再强制打开菜单。
- 修复组合控件事件只在最深命中节点执行默认行为的问题：默认行为现在沿 bubble 路径执行，
  焦点与 pointer capture 归属于实际请求节点，因此 Button 内文字可点击，ScrollView 和 ListView
  内子节点可响应滚轮，内层消费滚轮后不会再驱动外层滚动。
- 修复全屏 `UiOverlayRoot` 在没有弹窗时仍拦截普通树 hit-test 的问题；overlay 根节点自身不再
  命中，但其 Popup/Menu 子节点仍保持最高命中优先级。UiDemo 标题增加窗口、光标、hit 和 focus
  诊断，便于区分 GLFW 输入、坐标命中和控件焦点。

#### 验证、性能与当前限制

- 默认测试为 349 项，其中无桌面运行通过 289 项并按条件跳过 60 项真实 GL；`glSmoke`
  通过 348 项，仅跳过专用于非 Windows 的 1 项平台测试。`localUiVerification --rerun-tasks`
  的 13 个任务和完整 `localGlVerification --rerun-tasks` 的 25 个任务全部通过。
- 修正高 DPI 隐藏窗口回归：pipeline 像素断言按实际 framebuffer extent 读取中心，不再把
  32×32 logical window 错当为 32×32 framebuffer。
- 修正 Windows monitor content scale 与实际 framebuffer 比例不同导致的 scissor 偏移；GL 裁剪
  现在由 framebuffer/window 尺寸比换算。Demo properties 与 Gradle resource processing 固定 UTF-8，
  文本垂直裁剪增加 1 个逻辑像素的 hinting 保护带，避免字形上下覆盖率边缘被截断。
- 新增 `runUiBenchmarks`，完成 1080p/4K 五轮容量记录。10,000 quads 保持 1 draw，warm atlas
  hit 为 100%，layout/shape/upload 归零；详细数据见 `docs/performance/v0.11-ui-2026-07-16.md`。
- 性能记录同时暴露两个后续优化点：10,000 quads 仍约分配 1.57 MiB/frame，virtual list 的
  相邻 Label 尚未跨节点合并 glyph batch。它们是优化项，不通过跳过 fence 或改变 paint order 掩盖。
- Gradle 版本进入 `0.11.0`。Microsoft Pinyin 的候选翻页、DPI、多显示器、Alt+Tab、composition
  commit/cancel 和 committed character 去重矩阵已于 2026-07-17 人工通过；Emoji 显示列为字体覆盖限制。

## v0.12.0（2026-07-18）— Minimal PBR / IBL

### 核心渲染能力

- 建立 opaque metallic-roughness 材质闭环：六类 factor、base color、normal、metallic-roughness、occlusion、emissive 五类固定纹理，以及 manifest 引用、颜色空间、数值范围和 opaque 合同校验。
- 为顶点布局加入 position、UV、normal、tangent 等显式语义；OBJ 可按 PBR 模式生成 Gram–Schmidt 正交化 tangent vec4，退化 UV 使用 finite 确定性回退，legacy 输出保持不变。
- backend 增加 HDR float decode、`TextureCube`、`RG16F`、typed cubemap sampling/image、layered image binding、mip 生成和区分 texture target 的状态缓存；PBR 生产路径不使用 `CommandBuffer.custom()`。
- GPU compute 完成 equirectangular→cubemap、diffuse irradiance、逐 mip GGX prefilter 和 split-sum BRDF LUT；资源具有明确所有权、正确 barrier，resize 不重建 environment。
- 新增 Cook–Torrance GGX forward 路径，支持 directional/point/spot light、方向光阴影、tangent-space normal、AO、emissive、diffuse/specular IBL 和同步旋转的 HDR environment background；point/spot 使用带 range 窗口的 inverse-square falloff。
- PBR 线性输出完整接入 RGBA16F、自动曝光、Bloom、ACES、None/MSAA/FXAA/TAA 与最终 UI overlay；legacy/PBR 可以在同一 scene 和同一 model reference 下共存。

### Demo、调试与性能入口

- 新增 `PbrDemo`：5×5 metallic/roughness 球阵、五纹理外部 OBJ、legacy 对照、方向光阴影、两个点光、HDR environment 和 retained-mode 参数面板；主 `LearnOpenGlDemo` 同时包含 manifest 驱动的 PBR 对象。
- 增加 `runPbrIntegration`、resize/compatibility/failure integration、`localPbrVerification`、`runPbrBenchmarks` 和 Nsight PBR 启动入口。
- 归档 RTX 3050 Laptop GPU 五轮代表基准：1080p 中位 352.8 FPS、GPU median 0.797 ms；4K 中位 192.1 FPS、GPU median 1.594 ms。

### 稳定性与架构加固

- Environment 预计算强制通过调用方 `RenderDevice` 提交；临时 shader、sampler、texture 删除后失效活动状态缓存，避免运行时加载 environment 后错误跳过下一帧绑定。
- Demo mesh 按 `model reference + MaterialModel` 缓存独立 GPU variant；`RenderPipeline.build()` 对每个 PBR renderer 校验 canonical position、UV、normal、tangent location/type/size，并对不支持的布局 fail-fast。
- Environment 故障注入覆盖 cubemap 创建后、prefilter mip 前和 BRDF LUT 完成后；部分资源逆序且仅关闭一次，cleanup 异常作为 suppressed exception 保留，Demo 不再吞掉关闭异常。
- 固定 PBR public API allowlist，保持 backend 不依赖 core/PBR subsystem、environment 由调用方拥有、pipeline 只借用的依赖与所有权边界。
- 修复 retained UI `Slider` 缺少视觉图元的问题；现在绘制底轨、激活段和带边框 thumb，位置限制在扣除 thumb 宽度后的有效行程内。

### 验证

- 默认 `compileJava demoClasses test`、`localPbrVerification`、完整 `localGlVerification` 与 `git diff --check` 通过。
- 真实 GL 回归覆盖 HDR decode、cubemap face/mip、BRDF LUT、PBR 最终像素、运行时 environment 状态恢复、共享 legacy/PBR model variant、无 tangent fail-fast、resize identity 和三阶段 failure cleanup。
- 纯 JVM 测试覆盖 tangent handedness/退化输入、PBR manifest、BRDF finite 端点、range inverse-square 衰减、资源合同和 Slider 图元定位。

### v0.13-gltf-static-asset-pipeline（0.13.0，2026-07-18）

#### 静态资产解码与运行时

- 新增纯 JVM `GltfAssetLoader`，以 Jackson streaming parser 读取 `.gltf`/`.glb`，支持 GLB BIN、
  data URI、受根目录约束的相对资源、scene name/index 选择、node TRS/matrix、shared mesh 和
  结构化阶段错误。
- accessor 解码支持 float/normalized integer、interleaved stride、uint8/16/32 index 和 sparse
  overlay；缺失 normal/tangent 自动生成，输出 position/UV/normal/tangent/可选 vertex color
  canonical layout。
- 新增 `GltfRuntimeLibrary` 与 `GltfSceneAsset`，在当前 context 上去重上传 mesh、image
  color-space variant、sampler 和 PBR material；active-asset guard、重复关闭、use-after-close、
  sampler/texture/material/mesh 四阶段失败清理与最终 PBR 像素均有真实 GL 测试。
- `SceneAssetConfig` 增加独立 `gltf.*` scene instance；主 Demo 同时运行 legacy、framework PBR
  与 glTF embedded PBR，并新增独立 `GltfDemo`、`runGltfIntegration`、resize 验证和
  `localGltfVerification`。

#### PBR、状态与 Demo 闭环

- PBR contract 增加可选 COLOR_0、double-sided normal 和负 determinant tangent handedness；
  新增 typed `frontFace` pending state 和 `Material.cullMode`，保留跨 draw 状态语义与
  `StateCache` 去重。
- 运行期 glTF mesh 上传会保存并恢复现有 VAO/array-buffer 状态，EBO 通过 DSA 一次性附着到
  新 VAO；增加预热 `StateCache` 后加载资产并继续 indexed draw 的真实 GL 回归，避免动态加载
  污染后续帧。
- `GltfDemo` 拥有独立窗口、runtime、PBR environment、scene、pipeline 和 retained inspector，
  只绘制 `showcase.gltf` 的 2 个对象与 `radio.gltf` 的 8 个对象，不再委托 `LearnOpenGlDemo`。
- glTF PBR 增加颜色 pass 的 `alphaMode=MASK`/`alphaCutoff`；`radio.gltf` 直接按原始 embedded
  PNG 镂空渲染，不再生成 OPAQUE 副本。MASK shadow caster 明确要求 `castShadows=false`，
  BLEND 继续 fail-fast。

#### 发布加固与性能

- 无基础 `bufferView` 的 sparse accessor 复用单个只读零页，不再逐分量分配 `ByteBuffer`；
  indices/values 在读取前以 long exact arithmetic 验证负 offset、绝对 alignment、截断、越界
  与溢出，并保留精确 accessor location。
- MASK shadow 限制只检查选中场景可达节点实际实例化的 primitive；未选场景、不可达节点或
  未使用 mesh 中的 MASK material 不再阻止当前 OPAQUE 场景投影。
- `runGltfBenchmarks` 五轮结果：CPU decode 中位 3.604 ms、GPU upload 中位 1.242 ms；混合场景
  1080p/4K GPU median 分别为 0.623/1.352 ms。完整数据归档于
  `docs/performance/v0.13-gltf-2026-07-18.md`。
- 默认无桌面测试、`localGltfVerification`、完整 `localGlVerification` 与 `git diff --check`
  均通过，v0.13 静态 glTF 资产主路径完成。

### v0.14-consolidation-and-contract-hardening（0.14.0，2026-07-19）

#### API、依赖与文档收口

- 移除未被 Demo 或端到端验证证明的 `AssimpModelLoader` 实验 API 及其 LWJGL native 依赖；
  正式模型导入路径收敛为 OBJ 和静态 glTF 2.0。
- 建立覆盖 main source set 的 backend/core/runtime/render3d/postprocess/ui/windowing 七域公共 API
  清单；stable、advanced、internal 分类具有未分类、重复、陈旧、错域、第三方签名泄漏和越域门禁。
- 增加 dependency locking、发布资源八字段 manifest 与 SHA-256 校验，并把 API、架构、依赖和
  资产守卫纳入默认 `check`。
- 归档 v0.8–v0.14 实施计划，新增资源所有权合同、公共 API 迁移说明、发布验收报告和性能复测报告。

#### 内部职责与生命周期

- 将 glTF document、URI、buffer、accessor、material、node 与 canonical mesh decode 拆为包内阶段，
  保持 `GltfAssetLoader` facade、错误 phase/location、sparse 和 selected-scene 语义。
- 将 `.properties` 与行式 scene manifest 的解析、scalar/vector 转换和引用校验分离；非法布尔值
  不再静默退化为 false，错误携带精确 key 或行号。
- 复用包内 `CloseStack` 统一 glTF runtime 构造回滚和逆序关闭，保留 active lease、幂等 close、
  主异常与 suppressed cleanup 异常合同。
- `RenderGraph` 将依赖校验和稳定拓扑排序委托给不可变 compiled plan；`CommandBuffer` 保留既有
  primitive SoA stream、typed executor 与 pending pipeline state，避免为了拆分类名制造薄包装。

#### 验证与性能

- 建立串行 `localReleaseVerification`，覆盖 headless check、GL smoke、baseline/async、PBR、glTF、
  UI、synthetic IME 和 Windows native soak；正式发布前 27 个任务全部重跑通过。
- glTF decode/upload 中位相对 v0.13 为 `+1.4%/-6.6%`，未触发 5% 回退门槛。
- 优化静态 UI 样式脏标记、children 快照、命中测试和绘制边界临时对象；10,000 quads 三轮稳定为
  254.5 KiB/frame，2,000 glyphs 中位低于 5 KiB/frame。
- 百万 Cube 路径继续保持单 draw、100% state skip；indexed/compact SSBO 为约 900 万次 VS invocation，
  expanded 路径为 3600 万次。

### v0.15-debugging-and-observability（0.15.0，2026-07-19）

#### 帧诊断与 GPU 查询

- GPU pass query 增加 submission/result identity、`PENDING/AVAILABLE/SKIPPED/FAILED`、sample age 和
  完整总和语义；未知或未完成值不再伪装成零，8-slot ring 保持非阻塞。
- `FrameDriver` 提供 OFF/BASIC/DETAILED、有界 240 帧 history、epoch、freeze/clear、scene/UI/upload
  摘要和 incomplete failure frame；主 Demo 默认使用 BASIC。
- RenderGraph 发布来自 compiled plan 和 framebuffer descriptor 的不可变拓扑、target、attachment 与
  pass 描述，并通过 typed command 生成 `RenderGraph/<pass>` OpenGL debug group。
- 命令异常会结束并丢弃仍 active 的 timer query，再逆序恢复 prepared batch 和 debug group；真实 pass
  callback 与 query 后 command failure 均保留当前帧序号，下一帧可继续渲染。

#### GL 消息、资源与生命周期

- backend 增加每 context 独立的 256 项结构化 message ring，相邻消息折叠，溢出优先淘汰
  notification/low，并保留 dropped、repeat、phase 和 driver identity。
- 主要 GL wrapper 接入单调 resource sequence、label、创建帧和 storage 字节估值；native id 复用不再
  污染身份，正常/失败/重复关闭均不会留下 tracked resource。
- 资源跟踪改为 context-scoped 引用计数 lease；同 context 的 BASIC/OFF driver 不再关闭另一个
  DETAILED session。native callback 内部异常被截断，不能穿过原生边界。
- `FrameDiagnostics.clear()` 同时清除暂存 scene/UI 摘要；关闭后 read/history/freeze/clear 行为统一。
  runtime 公共 frozen capture 使用自身不可变 DTO，不暴露 backend/LWJGL 类型。

#### retained 诊断面板与导出

- 主 Demo 在原 `UiSystem/UiOverlayPass` 中加入 F2 五页诊断面板：Overview、Passes、Graph、Resources、
  Messages；面板可见时保持 UI 输入，F1 不再错误切回 CAMERA。
- 资源和消息页面与 Overview 共用同一 `FrameDiagnostics.read()` 发布边界；明细行支持单击、Ctrl/Shift
  多选、Ctrl+A 和 Ctrl+C，不直接查询 backend live registry。
- schema v1 JSON 在写文件前验证有限数值、计数和 frame/pass/graph/resource/message 一致性；写出
  引擎版本、构建修订、OpenGL vendor/renderer/version，默认省略 native GL id。
- Demo 自动导出限制在 `build/diagnostics/`，拒绝目录穿越和符号链接；临时文件完整关闭后再原子替换，
  失败不留下半文件。

#### Demo、资产与验收

- `GltfDemo` 增加用户提供的 `creeper.gltf`，独立场景绘制 16 个对象，并把资源纳入来源、许可和
  SHA-256 清单；inspector 支持 F1 UI/相机切换、F2 隐藏、滚轮和键盘翻页。
- 新增 deterministic、resize、真实 callback/command failure diagnostics integrations，以及消息优先
  淘汰、native id 复用、多 context 隔离、资源失败清理和关闭后访问测试。
- `localGlVerification --rerun-tasks` 的 40 个任务和 `localReleaseVerification --rerun-tasks` 的
  31 个任务全部通过；五轮 OFF/BASIC/DETAILED 性能与限制记录于 v0.15 性能报告。

### v0.16-scene-scalability-and-visibility（0.16.0，2026-07-19）

#### Bounds、SceneFrame 与可见性

- 新增不可变 `Bounds3f`，按 POSITION semantic/stride/offset 为 interleaved `MeshData` 计算有限
  local AABB；缺失或不支持的布局保守使用 unbounded，`Mesh`/Builder 传播或显式覆盖该值。
- 新增 allocation-free world AABB 中心/extent 变换、六平面三态 frustum 和 Camera projection
  共享事实；TAA 使用稳定非抖动视锥，方向光 shadow volume 独立分类。
- `SceneFrameBuilder` 每 renderer 每帧只执行一次 updater，复用 matrix/world-bounds/index arena，
  分别构建 forward/shadow queue；失败回滚 active counts，下一帧可重试。
- `RenderPipeline` 的 geometry/shadow pass 消费同一快照；mirrored front-face 和 draw matrix 不再
  重复求值，scene membership revision 变化时使缓存失效。

#### Render Queue 与诊断

- OPAQUE/ADDITIVE 按 shader/material/mesh 稳定分组，ALPHA 保持用户 insertion order；shadow 按
  mesh 分组。`RenderSettings.sceneVisibility(false)` 保留同一条 frame/queue 路径供 A/B。
- diagnostics schema v1 增加可选 `scene.visibility`，Overview/JSON 发布候选、裁剪、draw category、
  状态 key 变化和 queue 分阶段时间，并校验所有计数等式。
- 新增独立 `SceneScalabilityDemo`、100/1k/10k deterministic layout、all-visible/all-hidden、
  resize/shadow/10k 集成、JVM/GL 聚合门禁与 1080p/4K 五轮交替顺序成对基准。

#### 性能与验收

- 10k large 的普通 draw 由 10,000 降到 1,000；1080p 五轮 CPU median 从 4.853 ms 降到
  0.922 ms，GPU median 从 1.008 ms 降到 0.130 ms，成对节省 3.907/0.874 ms。
- 100 all-visible 的成对 CPU 开销在 1080p/4K 均低于 5%；all-hidden 只保留 unbounded probe，
  稳态整帧分配为 2.2 KiB/frame。
- 完整 JVM、真实 GL、public API、架构和本地发布门禁通过；发布人完成人工视觉验收并批准
  工程版本进入 `0.16.0`。

### v0.17-real-scene-cpu-submission-consolidation（0.17.0，2026-07-19）

- 新增 Transform revision 与防御性复制的 `SceneObject.fixed(...)`，静态 model/world-bounds 在稳定帧
  复用；任意 `ModelUpdater` 仍严格每 renderer 每帧调用一次。
- forward/shadow queue 使用独立完整 matrix key；支持稳定复用和 camera/light/resize/mutation 精确失效，
  TAA jitter 不再制造无意义 rebuild。
- mat4 command snapshot 改用可复用 primitive arena，保持录制后不可变语义；geometry/shadow pass
  对相邻 shader/material/mesh binding 去重。
- `Scene.add(SceneObject)` 改为每对象独立 `MaterialInstance`，修复共享 Material 时 override 串扰；
  同材质且无 override 的实例仍保持安全 binding 折叠。
- `CommandBuffer.recordedMatrixSnapshotCount()` 与 `recordedObjectPayloadCount()` 正式列为 advanced
  diagnostics API，统计边界和 reset 行为形成文档及测试合同。
- 新增项目自有多 primitive/material glTF 压测资产、`GltfSceneScalabilityDemo`、100/1k/10k 布局、
  allocation hard gate 与五轮 compat/optimized 基准。
- diagnostics schema/UI 增加 cache、queue 和 command encoding 指标；真实 GL 覆盖 matrix 像素 parity、
  TAA 32 帧、独立 queue 失效、resize 和失败恢复。
- 五轮 10k/10% glTF CPU median 从 1.106 ms 降到 0.626 ms，allocation 从 86.2 降到
  7.3 KiB/frame；当前证据决定 v0.18 暂不进入 GPU-driven。
- 完整 JVM、真实 GL 与本地发布门禁通过；发布人批准工程版本进入 `0.17.0`。

### v0.17.1-rendergraph-resource-preview（0.17.1，2026-07-21）

- F2 Graph 页可选择 RenderGraph attachment，并通过 GPU-only conversion 在同一 UiOverlayPass 内预览
  LDR/sRGB/HDR/R/RG/depth；支持通道、EV/range、false-color、depth、cubemap face/mip 和节流参数。
- 新增逻辑 key/catalog、pipeline/request generation 与纯值 `PreviewSummary`；FrozenDiagnostics/JSON
  不含 source native id、owner 或像素，并通过 request revision 拒绝并发切换产生的迟到结果。
- 新增 typed color/depth blit，color resolve 显式选择 MRT read attachment；DEPTH24_STENCIL8 使用同格式
  depth-stencil texture，1080p HDR MSAA 复用管线已有 `HdrResolvePass`，避免重复 16 MiB 临时目标。
- UI 逻辑图片在 render-record 时重新解析，旧异步 snapshot 遇到已释放 preview output 会跳过 batch；
  render3d 不依赖 UI subsystem，架构方向保持不变。
- 新增 `GraphPreviewGlTest`、`runPreviewIntegration`、`runPreviewBenchmarks` 与 8 MiB/1 draw/1 blit、
  topology 不变、无生产 readback、JSON 无 native/pixel payload 门禁。
- fixed-only scene 首帧缓存完成后省略 10k immutable model/bounds 重复扫描；membership 变化仍完整失效，
  static gate 从连续失败的 `0.178～0.225 ms` 降至 `0.003～0.004 ms`，原 `0.15 ms` 门槛保持不变。
- 完整 JVM、真实 GL、本地发布、asset manifest 和工作树一致性门禁通过；工程版本进入 `0.17.1`。

### v0.17.2-release-lifecycle-static-submission-hardening（0.17.2，2026-07-22）

- 将资产清单、Git candidate、版本元数据、GL debug policy、结构化性能结果和 readiness 证据集中到
  独立 Gradle 发布脚本；机器摘要会校验发布报告中的成功声明，未知或缺失证据默认失败。
- readiness claim 校验同时登记直接门禁和被报告的 benchmark/contract 叶任务，不再把已执行成功的
  `releaseVerificationContracts` 或静态五轮基准误报为无证据；环境摘要采用 JavaExec 实际使用的 JDK。
- OpenGL wrapper 删除资源时推进当前 context 的 deletion epoch；`GlRenderDevice` 在下一提交边界失效
  状态缓存，防止驱动复用 VAO、program、texture、sampler、buffer 或 framebuffer 数值 ID 后错误跳过绑定。
- epoch 按弱引用的 LWJGL capabilities identity 隔离并使用全局唯一 token；无 context 路径不初始化 GLFW，
  同一 device 在多个 context 间切换也会正确失效缓存。
- JUnit 与 JavaExec integration 共用精确到 vendor、renderer、severity、source、type、id、入口、文本、
  次数和到期日的 GL 日志策略；未知 HIGH/MEDIUM 与任何 `GL_INVALID_*` 均阻止发布。
- GL 汇总门禁现在显式依赖全部受审计的 Test/JavaExec 生产者，并把对应 XML/日志声明为输入；性能 JSON
  从 benchmark 自报配置读取尺寸、warmup、samples 与 rounds，不再复制常量。
- 性能环境或场景参数不一致会写入 `incomparableReasons` 并使发布失败；performance/readiness 共用同一份
  staged candidate Git 身份，且 readiness 会拒绝候选身份不一致的证据。
- diagnostics OFF 不再构造不会发布的摘要，visibility statistics 只在命令录制结束后构造一次；shader
  matrix uniform 复用 program-owned direct scratch，移除逐 draw 的临时 FloatBuffer wrapper；uniform
  location 增加逐 program 的两路 identity 热缓存，消除静态全可见路径反复查询 `uModel` 时的字符串哈希。
- pending pipeline state 会记住同一 command buffer 内已经提交的值，连续 draw 的相同 front-face、depth、
  blend 等状态不再重复编码；`custom()` 会清除这份知识并强制后续状态重发。矩阵 arena 的常用容量路径
  合并为单次边界检查，同时保留扩容失败前不发布 opcode 的原子性。
- command recorder 同样折叠连续相同 program binding，单对象 draw 以一次原子命令写入；RenderPipeline
  只在 winding 确实变化时写 front-face，并避免不会覆盖引擎 binding 的材质重复提交逐帧光照状态。
- `MaterialInstance` 缓存不可变 override 快照；内容未变化时 diagnostics、排序与 binding 检查不再重复
  复制映射，同时旧快照继续保持非 live 的公共 API 语义。
- 真实 GL 覆盖 128 次 VAO 重建、100 次 framebuffer resize 式重建、精确 ID reuse、多 context 与无
  context；五轮静态基准、JFR allocation、lifecycle 耗时和 GL 消息计数均进入 schema-v1 证据。
- 修复高频连续 benchmark 下 glyph atlas 首次 page 初始化 fence 尚未完成时，下一批合法 region upload
  被误判为并发初始化的问题；只有已插入 fence 的 submission 可被后续上传依赖，未执行命令仍保持拒绝。
