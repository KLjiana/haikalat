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
