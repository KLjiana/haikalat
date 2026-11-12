# 公共 API 分类合同

状态：分类原则持续有效，2026-11-09 文档复核。当前类型清单以本目录 `.allowlist` 与门禁为准；0.27.3 包调整见[迁移清单](../v0.27.3-render3d-package-migration.md)，0.27.4 新增入口见[统一控制合同](../v0.27.4-render3d-feature-control.md)。

v0.20 的域统计和包边界快照见
[v0.20 API 与包边界清单](../v0.20-api-boundary-inventory.md)。

main source set 的每个 public 顶层类型必须恰好出现在一个域清单中。每行格式为
`<stable|advanced|internal> <fully-qualified-class-name>`。

- `stable`：应用推荐依赖的兼容入口；删除需要迁移记录。
- `advanced`：诊断、扩展或低层控制入口；次版本可收紧，但必须更新 changelog。
- `internal`：因跨包实现暂时 public，不构成兼容承诺，只允许本域或 Demo 证明调用。

清单按 `animation`、`backend`、`core`、`runtime`、`render3d`、`postprocess`、`resources`、
`scene`、`ui`、`text`、`vfx`、`windowing` 十三个真实代码域拆分。路线图初稿遗漏了 backend；v0.14 实施时补入，避免
backend public 类型逃出穷尽校验。`animation` 在引擎演进阶段作为无 GL 调用的兄弟
subsystem 独立管理；`resources` 保存宿主无关的资源身份、代次和 CPU 解码协议，两者均由
架构测试禁止引用 backend 或直接调用 OpenGL；`vfx` 同样只保存效果资产、实例模拟和帧快照，
具体 GPU 适配位于 render3d 域；`util` 当前归入 core 域。

Milestone 4 的 `AnimationMixer/BoneMask/RootMotionDelta/TwoBoneIkSolver`、
`GpuParticleExperiment`、`VolumetricLightPass` 与 `UiAnimationDiagnostics` 均分类为 `advanced`。
其中动画和 UI 诊断保持 GL-free；GPU 粒子与体积光是经过真实像素、性能和稳定性验证的受控实验，
不构成完整编辑型 VFX/volume API 的 stable 承诺。详细限制见
`docs/guides/milestone4-api-performance-limitations.md`。

`PublicApiCatalog` 与 `ArchitectureBoundaryTest` 校验未分类、重复、陈旧、错域、stable
第三方签名泄漏和 internal 越域调用。反射检查覆盖导入后的简单类型，不依赖源码中出现完整包名。
稳定基线见 `stable-baseline.allowlist`，迁移记录见 `migrations.md`。

成员级新增 API 如果承担诊断合同，还必须在对应的成员合同文档中记录统计边界。v0.17 新增的
`CommandBuffer` 录制计数见 [command-buffer-diagnostics.md](command-buffer-diagnostics.md)。
v0.23 新增的 optional cascade settings 与 bounded Render3D diagnostics 均分类为
`advanced`，合同见 [M6 CSM](../v0.23-render3d-m6-cascaded-shadows.md) 与
[M7 可见性诊断](../v0.23-render3d-m7-visibility-diagnostics.md)。

v0.28 新增 `VfxRimLight`、`VfxUvFlow`、`VfxDissolve`、`EffectLight`、`VfxLight`、
`VfxLightDiagnostics` 均为 `advanced`；`LightIdAllocator` 与 `VfxLightBridge` 为包内
internal。`EffectSnapshot`/`MeshInstance`/`Render3dDiagnostics` 的 canonical record 组件
扩展及旧构造器兼容见[迁移记录](migrations.md)与[战斗 VFX 指南](../../guides/render3d-combat-vfx.md)。

v0.28.1 S1 新增 `ContactShadowSettings`、`CloudShadowSettings` 等 advanced 首版效果配置；
`ShadowSettings` 依次追加 `contactShadows`、`cloudShadows` 组件，原四/五/六参数构造器保留并
默认关闭。两者都随 `RenderFeatureSettings.directionalShadowMap(...)` 一次发布，不新增 GPU
资源或 Pass。迁移说明见[迁移记录](migrations.md)。

v0.28.1 S2 #19 新增 advanced `PcssSettings`（主方向光 PCSS：世界光尺寸、blocker 搜索半径/采样、
变半径 PCF 半径/采样、归一化深度 bias）。`ShadowSettings` 追加 `pcss` 组件，原四/五/六/七参数
构造器保留并默认 `PcssSettings.disabled()`；设置随
`RenderFeatureSettings.directionalShadowMap(...)` 一次发布，沿用现有方向光级联图集，不新增
manager、factory、纹理或 Pass。禁用时 PBR 继续执行原 hard/PCF 采样序列。迁移说明见
[迁移记录](migrations.md)，技术范围与深度单位见
[PCSS 技术卡](../../planning/v0.28.1-s2-pcss.md)。

v0.28.1 S1 #4 新增 advanced `ScreenDistortion`、`ScreenDistortionSettings`、
`ScreenDistortionSource`、`ScreenDistortionPass`（屏幕空间扭曲数据、设置、逐帧生产合同与
合成 Pass）、`VfxDistortion`（GL-free 世界空间贡献）与 `VfxDistortionProjector`
（render3d 相机投影适配器）。`PostProcessSettings` 追加 `screenDistortion` 组件，默认关闭；
关闭时不创建 Pass、shader 或附件。迁移说明见[迁移记录](migrations.md)，技术范围见
[屏幕空间扭曲技术卡](../../planning/v0.28.1-s1-screen-distortion.md)。

v0.28.1 S2 #5 新增 advanced `VertexDisplacementSettings`（有界局部正弦位移模型值）与
`VertexDisplacementDrawBinding`（每对象 `SceneDrawBinding`/`TemporalDrawBinding` 适配器，
可包装既有 skin/morph binding）。两者默认关闭；关闭时不创建 GPU 资源，`SceneDrawBinding`
新增的 `recordVertexDisplacement`/`boundsPadding` 默认方法保持旧实现源码兼容。技术范围、
顺序、bounds/previous-frame 和 shader 指纹见
[顶点位移技术卡](../../planning/v0.28.1-s2-vertex-displacement.md)。

v0.28.1 S2 #27 新增 advanced `render3d.vfx.GpuParticleSettings`（有界容量、世界空间 spawn
体积、寿命/重力/初速度/billboard 半宽/加色颜色的配置值）与 advanced
`render3d.vfx.GpuParticleSystem`（拥有 particle/counter/active-index SSBO、reset/update/draw
program 与 VAO 的 GPU 粒子 indirect owner；compute 原子累加 GPU 计数并压缩活跃索引，draw
消费同一 counter 的 `DrawArraysIndirectCommand`，CPU 不读回）。`render3d.vfx.GpuParticleEmission`
是包内 package-private 的发射游标，不进入 public API 清单，也不是 `internal` 分类（`internal`
专指因跨包实现而暂时 public 的类型）。技术范围、限制、shader 指纹与 GL 证据见
[GPU 粒子 indirect 技术卡](../../planning/v0.28.1-s2-particle-indirect.md)。

v0.28.1 S3 #12 新增 advanced `DepthOfFieldSettings`（焦点距离、全画幅等效焦距 mm、光圈 f-number、最大 CoC
半径）与 `postprocess.depthoffield.DepthOfFieldPass`（HDR 16-tap 深度驱动 gather）。
`PostProcessSettings` 追加 `depthOfField` 组件，默认关闭；关闭或 `maxCocPixels=0` 时不创建
Pass、shader 或附件。开启要求 HDR tone mapping 与共享单采样深度，并在 TAA 之后、曝光/Bloom/
tone mapping 之前运行；透明/VFX 与 UI 合成位置见
[景深技术卡](../../planning/v0.28.1-s3-depth-of-field.md)。迁移说明见[迁移记录](migrations.md)。

v0.28.1 S3 #21 新增 advanced `MotionBlurSettings`（曝光比例与最大半径）与
`postprocess.motionblur.MotionBlurPass`（HDR 有界屏幕空间 velocity gather）。
`PostProcessSettings` 追加 `motionBlur` 组件，默认关闭；关闭、`shutterFraction=0` 或
`maxRadiusPixels=0` 时不创建 Pass、shader 或附件。开启要求 HDR tone mapping 与共享单采样
velocity/validity，并在 TAA、景深之后，曝光/Bloom/tone mapping 之前运行；颜色、velocity、validity
统一按当前 raster 坐标采样，velocity 值本身已去 jitter；不拥有或采样历史，相机切镜和无效 velocity
由 validity=0 精确还原输入。无 velocity 的透明/VFX 使用其身后不透明表面的 velocity；若身后没有
不透明表面（天空等），validity=0 使该像素保持清晰。输入/输出、钳制与单帧 gather 限制见
[运动模糊技术卡](../../planning/v0.28.1-s3-motion-blur.md)。迁移说明见[迁移记录](migrations.md)。
v0.28.1 S3 #14 新增 advanced `render3d.water.WaterSurfaceSettings`（有限平面 bounds、
`level`、双列波 wavelength/speed/amplitude/direction、吸收色与密度、岸边渐隐、IBL 回退色，
全部构造期校验）与 internal `render3d.water.WaterSurfacePass`（单位矩形水面绘制、深度
吸收/岸边过渡与 Fresnel 反射，不作为兼容入口）。`RenderFeatureSettings` 追加
`waterSurface` 组件，默认 `WaterSurfaceSettings.disabled()`；`RenderPipeline` 新增
`waterSurface(...)`/`applyWaterSurface(...)`。开启要求 HDR，并与体积雾明确互斥。合同、成本与
限制见[有限水面技术卡](../../planning/v0.28.1-s3-water-surface.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S3 #13 新增 advanced GL-free `core.material.RefractionSettings`（IOR、厚度、粗糙
透射、透射/反射权重与默认关闭路径）、`Material.refraction(...)` 模板组件、advanced
`pbr.PbrMaterials.createGlass(...)` 材质工厂入口，以及 internal `pbr.RefractionPass`（快照
复制与玻璃绘制 recorder）。启用时管线在 geometry/volumetric transparent 之后复制单采样
scene colour 快照，再以共享颜色+深度的折射 pass 绘制玻璃；玻璃只采样快照，不采样正在写的
附件。默认 disabled 时不创建 Pass、shader 或附件；多层/粗糙透射/离屏回退与反馈禁止见
[折射技术卡](../../planning/v0.28.1-s3-refraction.md)。迁移说明见[迁移记录](migrations.md)。
v0.28.1 S2 #22 在 advanced `AntiAliasingMode` 中新增 `SMAA` 枚举值（单值选择，因此与
NONE/MSAA/FXAA/TAA 互斥），并新增 advanced
`postprocess.smaa.SmaaPass`（SMAA 1x 三 Pass、程序内生成的 area/search 查找纹理与
`docs/licenses/smaa.md` 记录的 MIT provenance）。SMAA 只接受 HDR tone mapping 之后的
display-referred RGBA8，LDR 组合在 `RenderSettings`、`PostProcessPipeline`、
`PipelineFeaturePolicy` 与 pass-plan 查询处明确拒绝；关闭/`NONE` 不创建任何 SMAA 资源或
Pass，保持精确基线颜色链。技术范围与指纹见
[SMAA 技术卡](../../planning/v0.28.1-s2-smaa.md)，迁移说明见[迁移记录](migrations.md)。

v0.28.1 S2 #16 新增 advanced GL-free `core.material.ClearcoatSettings(enabled, roughness,
normalStrength)`（固定 `F0 = 0.04`、独立清漆粗糙度与切空间法线强度，构造期校验），
`Material`/`Material.Builder` 新增 `clearcoat(ClearcoatSettings)` 与 `clearcoat()`，默认
`disabled()` 且为精确 no-op。新增 advanced
`pbr.PbrMaterials.createWithBindings(..., ClearcoatSettings, PbrTextureBinding)` 与便捷入口
`createWithClearcoat(...)`，清漆法线贴图固定 unit 5
（`PbrMaterials.CLEARCOAT_NORMAL_UNIT`，必须为线性贴图），未提供时借用共享扁平法线
fallback。清漆是材质值而不是 `RenderFeatureSettings` 开关：`directionalShadows`/IBL 等既有
帧级开关继续生效（仅对首个 PBR 材质模板；多材质帧级 gate 的已知限制见
[清漆技术卡](../../planning/v0.28.1-s2-clearcoat.md) 第 8 项），关闭清漆不创建 Pass、纹理或附件；不承诺也不加载
`KHR_materials_clearcoat`（默认严格策略拒绝该 payload，lenient 策略仅警告并忽略）。
PBR/IBL 能量分割、法线空间、零值还原与 GL 参考见
[清漆技术卡](../../planning/v0.28.1-s2-clearcoat.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S2 #17 新增 advanced GL-free `core.material.AnisotropicSettings(strength, rotation)`
（strength `[0,1]`、rotation 有限弧度，0 强度为精确 isotropic 路径），`Material`/`Material.Builder` 新增
`anisotropic(...)`/`anisotropic()`，默认 `disabled()`；新增 advanced
`pbr.PbrMaterials.createWithAnisotropy(...)` 与带末位 `AnisotropicSettings` 的 `createWithBindings` 重载，
旧工厂默认 disabled。各向异性是材质值而非帧级开关：只拉伸 base specular（Burley GGX/Smith），清漆保持
各向同性，IBL 为有界拉伸近似而非精确预过滤；完全缺失 TANGENT 仍由既有装配期拒绝，退化/镜像帧在 shader
内回退，非有限 rotation 显式 fail-closed。合同、限制与 GL 参考见
[各向异性技术卡](../../planning/v0.28.1-s2-anisotropic.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S4 #34 新增 advanced GL-free `postprocess.TemporalDenoiseSettings`（启用、历史混合、邻域 clamp
半径、深度/法线拒绝阈值、reactive/亮度突变阈值、最大重投影距离，构造期校验；默认 `disabled()`），
`PostProcessSettings` 追加 `temporalDenoise` 组件并保持旧构造器兼容；`PostProcessPipeline` 在 SSR 之后插入
`postprocess.denoise.TemporalDenoiseInputs`（借用颜色/depth/normal/velocity/previous-depth/矩/稳定 VP 的输入合同）与 `TemporalDenoisePass`（prev-VP/velocity 重投影、邻域 clamp、disocclusion/亮度突变 reset、双 slot 历史、
resize candidate 事务）。首版只绑定 SSR 信号，不同信号不共享历史；无逐通道协方差/逐像素方差估计，
历史约 48 B/px。合同、限制与 GL 参考见[时域降噪技术卡](../../planning/v0.28.1-s4-temporal-denoise.md)，
迁移说明见[迁移记录](migrations.md)。
v0.28.1 S4 #32/#33 新增 advanced GL-free `core.material.SubsurfaceScatteringSettings`（散射色/强度、解析 profile、世界单位平均自由程、厚度来源与常量厚度、屏幕空间半径上限与最大距离，构造期校验；默认 `disabled()`）与 `Material` 的 `subsurfaceScattering` 组件；advanced `postprocess.sss.SubsurfaceScatteringPass` 是 `GlResource`（16-tap Poisson 屏幕空间扩散、深度/法线边界拒绝、世界到像素半径、64 px 上限、高光保护、边缘淡出、disabled 精确 no-op）。共享 surface 新增 `SUBSURFACE` mask 通道（由 `SceneSurfacePass` 写入、MSAA resolve 保留）；`PostProcessSettings` 追加 `subsurfaceScattering` 组件并保持旧构造器兼容。合同、限制与 GL 证据见[次表面散射技术卡](../../planning/v0.28.1-s4-sss.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S4 #42 新增 advanced GL-free `postprocess.vrs.VariableRateShadingSettings`（启用、tile 尺寸、粗/细 shading rate、fallback 策略，构造期校验；默认 `disabled()`）与 `postprocess.vrs.VariableRateShadingPass`（真实 `GL_NV_shading_rate_image` 路径：R8UI immutable rate image、per-viewport enable、palette 逐项查询、TexSubImage barrier、host state 恢复；FBO-only；EXT_fragment_shading_rate 仅检测未实现）。`PostProcessSettings` 追加 `variableRateShading` 组件；disabled 时零资源/零命令。RenderPipeline/feature-control 接线与 demo 仍为后续项。合同、限制与 GL 证据见[VRS 技术卡](../../planning/v0.28.1-s4-vrs.md)。
v0.28.1 S5 #45 新增 advanced GL-free `render3d.vct.VctSettings`（有界体素网格、分辨率 32/64/128、内存预算、cone 数/步数、diffuse/specular aperture、dirty 预算，构造期校验；默认 `disabled()`）与 internal `VctPass`（compute 体素化 + 完整 mip 链 + diffuse/specular cone trace + 深度重建 additive 合成；候选优先重建、事务失败保留旧代、幂等 close）。RenderPipeline 接线与逐像素材质输入仍为后续项。合同、限制与 GL 证据见[体素锥追踪技术卡](../../planning/v0.28.1-s5-vct.md)。
v0.28.1 S4 #40 新增 advanced GL-free `render3d.volumetric.VolumetricCloudSettings`（云层 base/top、密度/消光、覆盖场复用 #10 `CloudShadowSettings`、renderScale、步数/光步、深度/曝光/历史拒绝阈值，构造期校验；默认 `disabled()`）与 internal `VolumetricCloudPass`（低分辨率 bounded march、深度感知重建、双缓冲历史、候选优先 resize、`S + T*scene` 单次合成）。RenderPipeline 接线与 demo 仍为后续项。合同、限制与 GL 证据见[体积云技术卡](../../planning/v0.28.1-s4-clouds.md)。
v0.28.1 S5 #43 新增 advanced GL-free `render3d.volumetric.SmokeSimulationSettings`（网格/步长/CFL、注入源、耗散、浮力、边界，构造期校验；默认 `disabled()`）与 `SmokeRenderSettings`（体积渲染步数/步长、消光/散射、光方向/颜色，构造期校验）以及 internal `SmokeSimulation`/`SmokeVolumePass`（双 3D 纹理 ping-pong、确定性 fixed-step、候选优先重建、深度截断单次散射、预乘合成；disabled 精确 no-op）。RenderPipeline 接线与 demo 仍为后续项。合同、限制与 GL 证据见[体积烟雾技术卡](../../planning/v0.28.1-s5-smoke.md)。
v0.28.1 S5 #39 新增 advanced GL-free `postprocess.SsgiSettings`（采样/步数预算、强度、最大离屏比例、厚度/边缘阈值，构造期校验；默认 `disabled()`）与 `postprocess.ssgi.SsgiPass`（`GlResource`：有界余弦半球屏幕空间间接漫反射、天空样本贡献 0、仅离屏 miss 使用环境回退、一次 albedo/PI 反弹；disabled 精确 no-op）。共享 surface 新增 RGBA8 `ALBEDO` 通道（标量 base-colour factor，MSAA resolve 保留）；`PostProcessSettings` 追加 `ssgi` 组件。S4 #41 新增 advanced `postprocess.AdaptiveSamplingSettings`（tile 大小、最小/最大步数、误差阈值、屏幕步长/深度跳变尺度）与 `SsrPass(SsrSettings, AdaptiveSamplingSettings)` 的自适应 march；默认 disabled 时使用原 `ssr.frag` 逐字节基线。合同、限制与 GL 证据见[SSGI 技术卡](../../planning/v0.28.1-s5-ssgi.md)与[自适应采样技术卡](../../planning/v0.28.1-s4-adaptive-sampling.md)。
v0.28.1 S4 #46 新增 advanced GL-free `render3d.hair.HairStrandSettings`（程序化发束数量/段数/宽度像素上下界、LOD 距离与像素阈值、Kajiya-Kay 漫反射/双高光参数、CUTOUT/SORTED_ALPHA、方向光阴影参数，构造期校验；默认 `disabled()`）与 internal `render3d.hair.HairStrandPass`（`GlResource`：真实 polyline 发束 ribbon、屏幕空间宽度/LOD、每根 draw、既有方向光 shadow depth 接收与 `recordShadow` 投射、candidate-first resize、幂等 close）。不写共享 surface 通道，不参与 TAA/SSR/SSS/DOF；RenderPipeline 接线由 Integrator 完成。合同、限制与 GL 证据见[发束技术卡](../../planning/v0.28.1-s4-hair.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S4 #38 新增 advanced GL-free `render3d.volumetric.VolumetricParticleSettings`（椭球包围盒、密度/吸收/散射、最大步数/步长、最大屏幕覆盖率、相位各向异性，构造期校验；默认 `disabled()`）与 `render3d.volumetric.VolumetricParticlePass`（`GlResource`：解析闭式光深、深度截断、单次散射、预乘合成；candidate resize、幂等 close、disabled 精确 no-op）。该 pass 只消费调用方已合成的 HDR 颜色与同帧不透明深度，不重复积分已有体积雾；不包含 #43 烟雾模拟。合同、限制与 GL 证据见[体积粒子技术卡](../../planning/v0.28.1-s4-volumetric-particles.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S4 #37 新增 advanced GL-free `render3d.transparency.StochasticTransparencySettings`（每帧样本数、jitter、覆盖率映射、时域历史/拒绝、邻域 clamp、容量，构造期校验；默认 `disabled()`）、advanced `StochasticTransparencyInputs`（stable view/projection 及逆矩阵、借用单采样场景深度）与 internal `render3d.transparency.StochasticTransparencyPass`（`GlResource`：私有样本深度、RGBA32F 帧和、双 slot 时域历史、预乘合成；disabled 精确 no-op，不写共享深度）。`OitSurface` 集合表示复用 #28。合同、限制与 GL 证据见[随机透明技术卡](../../planning/v0.28.1-s4-stochastic-transparency.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S4 #38/#37 生产接线（`codex/render-pipeline-wiring`）：`RenderFeatureSettings` 追加
`volumetricParticles` 与 `stochasticTransparency`（第 18/19 个 canonical 组件，builder/defaults/
`toBuilder()` 保持源码兼容，直接调用 canonical 构造器的调用方需更新）；`RenderFeature` 追加
`VOLUMETRIC_PARTICLES`/`STOCHASTIC_TRANSPARENCY` 两个活动项；`RenderPipeline` 新增
`volumetricParticles(...)`/`applyVolumetricParticles(...)`、
`stochasticTransparency(...)`/`applyStochasticTransparency(...)` 与
`stochasticTransparencySurfaces(...)`/`stochasticTransparencySurfaces()`。粒子只在体积雾合成之后、
透明 replay/HDR VFX 之前消费同一 HDR 颜色与单采样不透明深度；随机透明在最终场景颜色上原位合成，
位于雾/景深/Bloom/TAA 之前，图节点不声明深度附件。两者默认关闭时拓扑与基线逐字节一致；MSAA
（粒子/随机透明）与 TAA（随机透明）报告 `UNAVAILABLE` 且不改变图。`TaaInvalidationReason` 末尾追加
`VOLUMETRIC_PARTICLE_SETTINGS`/`STOCHASTIC_TRANSPARENCY_SETTINGS`（既有常量与序号不变）。本接线没有
新增 public 顶层类型，`render3d.allowlist` 无需新增条目；迁移说明见[迁移记录](migrations.md)。
v0.28.1 S3 #28/#30/#36 生产接线（`codex/render-wiring-b1`）：`RenderFeatureSettings` 追加
`weightedOit`、`planarReflections`、`caustics`（第 23/24/25 个 canonical 组件，默认各自 `disabled()`；
旧 19 参数构造器保留，源码兼容）；`RenderFeature` 末尾追加 `WEIGHTED_OIT`、`PLANAR_REFLECTIONS`、
`CAUSTICS`；`RenderFeatureReason` 追加 `NO_TRANSPARENT_SET`、`NO_WATER`、`NO_PLANE`。`RenderPipeline`
新增 `weightedOit(...)`/`weightedOit()`/`applyWeightedOit(...)`/
`weightedOitSurfaces(...)`/`weightedOitSurfaces()`/`weightedOitSetDeclared()`、
`planarReflections(...)`/`planarReflections()`/`applyPlanarReflections(...)` 与
`caustics(...)`/`caustics()`/`applyCaustics(...)`。`WeightedOitSettings.defaults()`、
`PlanarReflectionSettings.hasPlane()`、`CausticsSettings.MINIMUM_LIGHT_DOWNWARD_Y` 为新增辅助入口。
`PipelineTopology` 追加 `weightedOit`/`planarReflections`/`caustics` 三个 internal 组件，保留旧
33 参数构造器和旧 `capture(...)` 重载；`PipelineGeneration` 拥有/借用/事务式 resize/退役三个 Pass。
顺序为 `scene colour producer → PlanarReflection → Caustics → WaterSurface → Refraction → OIT →
Stochastic → PostProcess`；OIT 与随机透明是同一透明集合的互斥策略（重叠集合 fail-closed，可证明不相交
时允许共存），OIT 要求 HDR + 单采样非 TAA + 已声明集合，平面反射要求 HDR + 合法单平面，焦散要求 HDR + 水面 +
向下方向光且首版不接 MSAA。关闭时拓扑与基线逐字节一致。焦散采用“水面之前”的安全顺序而非
“水面/折射之后”，理由与剩余下游缺口见三张技术卡。没有新增 public 顶层类型，`render3d.allowlist`
无需新增条目；迁移说明见[迁移记录](migrations.md)。

v0.28.1 S5 #44/#45 与 S4 #46 生产接线（`codex/render-wiring-b3`）没有新增 public 顶层类型，也没有删除
stable 类型或修改稳定签名。advanced `RenderFeatureSettings` canonical record 由 25 个组件扩展为 28 个
（末尾追加 `ddgi`/`vct`/`hairStrands`，默认 `disabled()`）；builder、`defaults()`、`toBuilder()` 与访问器
保持源码兼容，直接调用 canonical 构造器的调用方需要更新。`RenderFeature` 末尾追加 `DDGI`、
`VOXEL_CONE_TRACING`、`HAIR_STRANDS`；`PipelineTopology` 追加 internal `ddgi`/`voxelConeTracing`/
`hairStrands` 组件并保留旧参数列表的 `capture(...)` 重载。`RenderPipeline` 新增
`ddgi(...)`/`ddgi()`/`applyDdgi(...)`、`vct(...)`/`vct()`/`applyVct(...)`、
`hairStrands(...)`/`hairStrands()`/`applyHairStrands(...)`，以及 VCT 的
`vctSceneInput(...)`/`vctDirtyBoxes(...)`/`vctProbes(...)`/`vctSurface(...)` 暂存入口与
`hairStrandModel(...)` 放置入口。DDGI/VCT/hair 默认关闭时不创建 pass、target、program 或 draw，
拓扑 key 与基线逐字节一致；MSAA/TAA 下的 DDGI/VCT 报告 `UNAVAILABLE`，无方向光时报告
`NO_ELIGIBLE_LIGHT`；DDGI 与 VCT 互斥、与 SSGI 的间接漫反射去重均在请求/装配期 fail-closed。
迁移说明见[迁移记录](migrations.md)。
v0.28.1 S4 #35 新增 advanced GL-free `postprocess.TemporalUpscalingSettings`（启用、输入/输出
`renderScale`、history blend、bounded sharpness、reactive/曝光阈值、最大重投影误差、LOW/MEDIUM/HIGH
quality，构造期校验；`disabled()` 是精确 no-op）、advanced `postprocess.TemporalUpscalingSource`
（借用低分辨率颜色/depth/velocity/previous-depth/reactive/UI 源）与 advanced
`postprocess.upscale.TemporalUpscalingInputs`；advanced `postprocess.upscale.TemporalUpscalingPass`
是 `GlResource`（稳定输出栅格重投影、邻域 clamp、曝光/reactive/UI 拒绝、双 slot 历史、candidate
resize、bounded unsharp），`TemporalUpscalingHistory` 为包内实现、不进入 public API。`PostProcessSettings`
追加 `temporalUpscaling` 组件（第 12 个 canonical 组件），原有构造器全部保留并默认 `disabled()`。
`PostProcessPipeline` 在 HDR 链头部插入 upscale pass；external-target producer 因而拒绝 Bloom/自动
曝光/全分辨率 surface consumer/late reactive producer，并与 TAA/MSAA 互斥（首版限制，装配期 fail-closed）。
合同、限制与 GL 证据见[时域放大技术卡](../../planning/v0.28.1-s4-temporal-upscaling.md)，
迁移说明见[迁移记录](migrations.md)。
v0.28.1 S3 #15 新增 advanced GL-free `render3d.vfx.GpuParticleCollisionSettings`（启用、TEXEL_CENTRE/
CONSERVATIVE_MIN_2X2 采样、世界厚度、restitution、friction、搜索步数/半径、opaque-only，构造期校验；
默认 `disabled()`），`GpuParticleSystem` 扩展为可选碰撞 compute program 与 8 参数 `record(...)`，消费
同帧/同 view/同 viewport 的单采样不透明深度；离屏/无深度保持自由飞行，disabled 逐字节保留 #27 轨迹。
限制：仅屏幕空间当前可见深度、有界厚度平面近似、无连续碰撞/复杂形状、无 GPU 读回；与 #26/#28 的组合
仍需接线。合同与 GL 参考见[粒子碰撞技术卡](../../planning/v0.28.1-s3-particle-collision.md)，
迁移说明见[迁移记录](migrations.md)。
v0.28.1 S2 #18 新增 advanced GL-free `render3d.pbr.ReflectionProbeSettings`（探针位置、影响盒/球、
blend distance、质量档、强度/旋转）、`ReflectionProbeSet`（有限数量静态 HDR 预计算、加载失败回滚、
close 释放）与 `ReflectionProbeSelector`（影响区选择、权重/混合、全局 IBL 回退）；PBR shader 增加
局部探针 cubemap/局部校正/权重采样，`PbrMaterials.bindReflectionProbes(instance, set, receiverPoint)`
为每个 receiver 绑定探针。首版最多 2 个静态探针、只影响 specular（diffuse/BRDF LUT/clearcoat IBL 仍全局），
逐 draw 在代表点选择；无实时捕获。关闭/无探针时仍写死专用 sampler unit 22/23，避免与 `uBaseColorMap`
的 unit 0 别名。合同、限制与 GL 参考见
[局部反射探针技术卡](../../planning/v0.28.1-s2-reflection-probes.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S3 #26 新增 advanced GL-free `render3d.vfx.GpuParticleSortingSettings`（启用、VIEW_DEPTH/
DISTANCE 键、方向、最大排序数、相同键 STABLE/UNSTABLE，构造期校验；默认 `disabled()`）与 GL-free
`GpuParticleSortPlan`（2 的幂 padding、bitonic 序列、dispatch 组）。`GpuParticleSystem` 扩展为
reset→update→sort→draw：排序启用时用预乘 alpha-over 绘制排序后的 active set，disabled 时逐字节保留
#27 的加色/原命令路径。排序只覆盖同一 owner 的活跃集合，不解决跨 owner/透明网格交错；超过
`maximumSortedParticles` 为部分排序。合同与 GL 参考见
[粒子排序技术卡](../../planning/v0.28.1-s3-particle-sorting.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S2 #27 新增 advanced GL-free `render3d.vfx.GpuParticleSettings`（capacity、粒子尺寸、寿命、
重力/速度范围等，构造期校验）与 package-private `GpuParticleEmission`（显式发射窗口/描述，不属于
公共 API），以及 advanced
`render3d.vfx.GpuParticleSystem`（reset/update compute + 原子活跃索引 + counter SSBO 兼作
`DrawArraysIndirectCommand` + `glDrawArraysIndirect`；CPU 不读计数、不 map，空集合由 GPU 语义零图元）。
命令层新增 typed `CommandBuffer.drawArraysIndirect(...)` 与 `StateCache.bindDrawIndirectBuffer`。
`RenderPipeline` 生产接线仍待 Integrator；#26 排序/#28 WBOIT/#15 碰撞都应在同一 active-set owner 上扩展。
合同、限制与 GL 参考见[GPU 粒子间接渲染技术卡](../../planning/v0.28.1-s2-particle-indirect.md)，
迁移说明见[迁移记录](migrations.md)。
v0.28.1 S3 #11 新增 advanced GL-free `render3d.water.WaterRefractionSettings`（IOR/折射强度、最大屏幕
偏移、厚度缩放、前景拒绝 bias、离屏回退，构造期校验；默认 `disabled()` 时水面 shader 与 #14 基线逐字节一致），
`WaterSurfacePass` 新增带折射设置的重载/构造函数与 `recordBackgroundCopy(...)`，由
`WaterSurfacePass.REFRACTION_BACKGROUND_PASS` 提供受控背景颜色拷贝。屏幕空间近似：无真实水下体积/色散/
多层水体/焦散，前景拒绝仅用共享不透明深度；与 #13 玻璃折射互斥、与 SSR/体积雾的水面限制不变。
合同、限制与 GL 参考见[水面折射技术卡](../../planning/v0.28.1-s3-water-refraction.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S3 #36 新增 advanced GL-free `render3d.caustics.CausticsSettings`（强度/颜色、世界尺度、
动画速度、水面矩形/水位、接收面高度带、最大投射距离、深度偏置/衰减、可选图样纹理，构造期校验）与
internal `render3d.caustics.CausticsPass`（自有 RGBA16F 目标、程序化图样 + 共享深度拒绝、resize/close
事务式生命周期，不作为兼容入口）。首版是**艺术近似**，不是光子映射/物理焦散；不写共享深度、不产生
反馈，`RenderPipeline` 生产接线仍待 Integrator。合同、限制与 GL 参考见
[焦散技术卡](../../planning/v0.28.1-s3-caustics.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S3 #31 新增 advanced GL-free `core.material.ParallaxOcclusionSettings`（heightScale、min/max steps、
角度/距离 fade、最大距离、height channel/flip，构造期校验；默认 `disabled()` 为精确 no-op），`Material`/
`Material.Builder` 新增 `parallaxOcclusion(...)`/`parallaxOcclusion()`；新增 advanced
`pbr.PbrMaterials.createWithParallax(...)` 与末位 `ParallaxOcclusionSettings` 的 `createWithBindings` 重载，
`PbrTextureRole.HEIGHT`（LINEAR）绑定到 `PbrMaterials.HEIGHT_UNIT = 6`。POM 只位移材质 UV 采样，不改变
几何深度、shadow map、velocity 或轮廓，不扩张 silhouette，也不参与 clearcoat 法线；缺省用 1×1 黑色高度
fallback。合同、限制与 GL 参考见[POM 技术卡](../../planning/v0.28.1-s3-pom.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S3 #28 新增 advanced GL-free `render3d.transparency.WeightedOitSettings`（深度权重尺度/上限、
逐层 alphaThreshold discard、最大表面数）与 advanced `OitSurface`（调用方提供的最小透明表面描述），以及 internal
`render3d.transparency.WeightedOitPass`（RGBA16F accum：RGB=Σ(c·α·w)、A=revealage；R16F：Σ(α·w)；
加权累积与 resolve；`record`/`resolve` 可跨 command buffer 但必须先累积后 resolve；surface/resolve 两端有界保护，
任何合法表面列表不会写出 Inf/NaN）。共享命令层新增 typed
`blendFuncSeparate`（`PipelineStateSink`/`StateCache`/`CommandBuffer`/`PendingPipelineState`）。首版是
近似加权平均，不精确排序、不写/采样共享深度、不承诺 MSAA/任意透明交错；与 #26 按集合互斥切换。
`RenderPipeline` 生产接线仍待 Integrator。合同、误差界与 GL 参考见
[加权 OIT 技术卡](../../planning/v0.28.1-s3-oit.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S3 #30 新增 advanced GL-free `render3d.planar.PlanarReflectionSettings`（单平面点/法线、
分辨率缩放与上限、更新间隔帧数、近平面偏移，构造期校验）与 advanced GL-free
`render3d.planar.PlanarReflectionMath`（镜像相机、`V*R`、Lengyel 斜近平面裁剪），以及 internal
`render3d.planar.PlanarReflectionPass`（自有 RGBA16F+DEPTH24 反射 FBO、`SceneRecorder` 回调、
更新节流、绕序/目标/viewport 恢复，不作为兼容入口）。首版最多 1 个平面且非递归；Pass 尚未接入
`RenderPipeline` 生产帧路径，水面/SSR 下游采样仍是明确的接线缺口。合同、限制与 GL 参考见
[平面反射技术卡](../../planning/v0.28.1-s3-planar-reflections.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S2 #29 新增 advanced GL-free `render3d.environment.AtmosphereSettings`（米制行星半径/大气厚度、
Rayleigh RGB 与 Mie 散射/吸收系数、标高、太阳方向与辐照度、曝光、太阳圆盘、LUT 尺寸与采样数，构造期校验）
与 internal `render3d.environment.AtmosphereSkyPass`（transmittance/sky-view LUT、全屏 HDR 背景绘制与
事务式 LUT 重建，不作为兼容入口）。`RenderPipeline` 新增 `atmosphere(AtmosphereSettings)`/`applyAtmosphere(...)`，
`null` 表示关闭；大气天空与 `outdoorEnvironment` 的 stylized 天空互斥，开启时替换背景且不叠加 IBL 背景。
合同、限制与验证见[大气散射技术卡](../../planning/v0.28.1-s2-atmosphere.md)，迁移说明见[迁移记录](migrations.md)。
v0.28.1 S3 #20 新增 advanced `SsrSettings`（最大世界距离、march 步数、命中厚度、粗糙度 cutoff、
置信度下限、强度与屏幕边缘渐隐）、`SsrEnvironmentSource`（借用 PBR 环境/IBL 回退合同）与
`postprocess.ssr.SsrPass`（HDR 有界屏幕空间反射）。`PostProcessSettings` 追加 `ssr` 组件，
canonical record 由 9 个组件扩展为 10 个，原 2/3/4/6/7/8/9 参数构造器保留并默认
`SsrSettings.disabled()`。共享表面新增 `SceneBufferChannel.ROUGHNESS`（R8，可选分配；首版为
PBR 标量粗糙度）。开启要求 HDR tone mapping、PBR 材质与借用环境；关闭时不创建 program、Pass
或附件，颜色链顺序保持精确基线。技术范围、坐标/深度约定、MSAA 解析和回退策略见
[SSR 技术卡](../../planning/v0.28.1-s3-ssr.md)，迁移说明见[迁移记录](migrations.md)。

v0.28.1 S1 #6 新增 advanced `render3d.vfx.ProjectedDecal`、`ProjectedDecalSettings` 与
`VfxRenderer.recordProjectedDecals`；包内 `ProjectedDecalPlan` 保持 internal。默认关闭，关闭时
不创建 shader/网格、不录制命令；投射贴花借用现有 VFX 路径的调用方深度与 viewport，未进入
`RenderFeatureSettings`（本轮未修改 `RenderPipeline`/forward 装配）。
v0.28.1 S4 #40 / S5 #43 生产接线（`codex/render-wiring-b2`）：advanced `RenderFeatureSettings`
canonical record 由 19 个组件扩展为 22 个（末尾新增 `volumetricClouds`、`smokeSimulation`、
`smokeRender`，默认全部 `disabled()`）；builder、`defaults()`、`toBuilder()` 与访问器保持源码
兼容，直接调用 canonical 构造器的调用方需更新。`RenderFeature` 末尾追加 `VOLUMETRIC_CLOUDS`、
`VOLUMETRIC_SMOKE`。`RenderPipeline` 新增 `volumetricClouds(...)`/`volumetricClouds()`/
`applyVolumetricClouds(...)`、`smokeSimulation(...)`/`smokeSimulation()`/
`applySmokeSimulation(...)`、`smokeRender(...)`/`smokeRender()`/`applySmokeRender(...)`。internal
`PipelineTopology` 追加 `volumetricClouds`/`volumetricSmoke` 两个组件，并提供保留旧 16 参数
列表的 `capture(...)` 重载；`volumetricCloudCompositionActive(...)`/`volumetricSmokeCompositionActive(...)`
报告软不可用条件。云层/烟雾都在最终场景颜色链末端、后处理之前各合成一次；图节点只声明
RGBA16F 颜色附件，不写共享不透明深度。两者要求 HDR + 单采样栅格；MSAA/TAA 保持
`UNAVAILABLE`，不改变图。云层在 #10 云影启用时要求 coverage field 与
`DirectionalShadowMap.settings().cloudShadows()` 一致。internal `VolumetricCloudPass.COLOR_TEXTURE`
与 `SmokeVolumePass.COLOR_TEXTURE` 是图颜色附件逻辑名。没有新增 public 顶层类型，
`render3d.allowlist` 无需新增条目；迁移说明见[迁移记录](migrations.md)，接线合同见
[体积云技术卡](../../planning/v0.28.1-s4-clouds.md)与[体积烟雾技术卡](../../planning/v0.28.1-s5-smoke.md)。
