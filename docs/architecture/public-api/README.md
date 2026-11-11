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
