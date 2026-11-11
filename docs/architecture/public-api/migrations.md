# 公共 API 迁移记录

本文件只记录从稳定 API 基线删除或破坏性替换的类型。删除
`stable-baseline.allowlist` 中的类型时，必须在这里说明替代入口和迁移方法。

v0.14 没有删除 stable 类型。`AssimpModelLoader` 属于未完成的实验入口，不在稳定基线中；
使用者应将源资产离线转换为 glTF，并改用 `GltfAssetLoader` 与 `GltfRuntimeLibrary`。

v0.15 没有删除 stable 类型。advanced `PassProfile` 保留原三参数构造器；新增 GPU 状态、样本帧身份、
年龄和 skipped 计数。旧调用方可继续读取显式传入的 `gpuNanos`，新诊断调用方应先检查
`gpuStatus()==AVAILABLE`。advanced `FrameProfile` 保留双参数构造器，并新增 frame sequence 与
`gpuTotalComplete()`。

v0.16 没有删除 stable 类型。advanced `MeshData` 保持原 record component 和构造签名，新增
`localBounds()`；advanced `Mesh`/`Mesh.Builder` 新增不可变 `Bounds3f` 读取与显式设置入口。
旧 builder 无法从 POSITION semantic 推导范围时会保守返回 unbounded，不会错误裁剪。

v0.17 没有删除 stable 类型或修改 `SceneObject` record component。advanced `SceneObject` 新增
`fixed(Mesh, Material, Matrix4fc[, boolean])`，用于明确表达可缓存的防御性复制固定矩阵；现有构造器
与任意 `ModelUpdater` 保持动态、每帧一次语义。`Transform` 的 revision 与 queue/cache 类型不公开。

v0.17.2 没有删除 stable 类型。advanced backend `GlDebug` 新增 `contextStateEpoch()`，只用于
`GlRenderDevice` 在提交边界同步 native 资源删除代次；普通调用方不应以该值实现资源所有权或跨 context
同步。无 current context 时返回固定标记，正式资源生命周期仍由各 `GlResource.close()` 管理。

v0.18.0 没有删除 stable 类型。新增 animation、resources、VFX、局部阴影、后处理和 UI 动画接口均已在
域 allowlist 中明确分类；高级动画、GPU 粒子、体积光和 UI 动画诊断保持 `advanced`，不扩大 stable
兼容承诺。`SceneObject` 新增可选 `SceneDrawBinding`，原构造器和固定/动态模型更新语义保持兼容。

v0.24.2（候选）没有删除 stable 类型。`GltfRuntimeLibrary` 保留 `create()`，但移除了
`createShadowBudget()`：唯一 `pbr-forward.frag` 通过 buffer header 决定灯数，不再存在
legacy/budget 双 shader 路径。`Scene` 新增 `removeLight(int)`。`Render3dDiagnostics` 在
canonical record 中新增 `clustered` 组件，`SelectedShadowLight`/`RejectedShadowLight` 的
`shaderIndex` 更名为 `frameLightIndex`（advanced API）；旧构造入口保留。advanced
`ClusteredLightingDiagnostics` 的组件在实现期扩展了 `gpuCountersAvailable` 与
`gpuSampleFrameSequence`：GPU 计数改为双缓冲 persistent-coherent 映射 + fence 延迟读回，
未完成时计数字段为 -1。新增 advanced 类型 `ClusteredLightingSettings`、
`ClusterDebugMode`。自定义 lit shader 必须改用 binding 0 light table、binding 2/3 cluster list、
binding 6 `ClusterParametersBlock` 和 binding 5 `ShadowSamplingBlock`，旧的
`uDirectionalLights/uPointLights/uSpotLights` 数组合同不再接受。

v0.26 没有删除 stable 类型或修改构建版本。UI layer/options/backdrop/screen rect/blend 与
Compositor 诊断记录所属类型提升为 advanced，具体清单见 `ui.allowlist`；renderer/snapshot/ring
等实现仍为 internal。`UiSystem.prepareAttachment` 和 `AttachmentCandidate.commit/close` 提供
候选 graph 协议，调用方拥有 graph，并须在 update owner/current GL context 的帧边界操作。
`RenderGraph.isExecuting()` 覆盖记录、提交和完成回调，是查询而非并发锁。

原先接受但静默无效的图层 Glow/shadow/color transform/tint、ROUNDED mask、非预乘 blend 和
非零 zOrder 现在明确报 `UNSUPPORTED_EFFECT`。同层内容与背景模糊也明确拒绝；普通主题 SDF/文字
特效不受此枚举限制。背景缺失保留普通内容与组透明度；旧 topology-only compositor 注册重载不再
能冒充可执行合成器。已有应用改用 `UiSystem.attachTo`，迁移示例见 [指南](../../guides/ui-compositor.md)。

`GlDebug.contextStateEpoch()` 现在也反映绕过设备缓存的 framebuffer 构造/绑定恢复，
`contextStateChanged()` 供底层资源代码通知 native 状态变化；两者都不是资源所有权或跨 context 同步工具。

v0.27.3 没有删除 stable 类型。后处理按效果拆包：advanced `BloomPass`、`AutoExposurePass`、
`ToneMappingPass`、`ColorGradingLut`、`FogPass`、`FxaaPostProcessor`、`TemporalAccumulationPass`
分别移到 `postprocess.bloom/exposure/tonemapping/color/fog/fxaa/temporal`，类名、构造器、
公开方法和行为保持不变，调用方只需更新 import。advanced `render3d.ScreenQuad` 改为
`postprocess.ScreenQuad`；`render3d.TaaResolveInputs` 与 `render3d.TaaSettings` 改为
`postprocess.temporal.*`，record component 与校验语义不变。包内 `PostProcessPassBuilder`
由 `postprocess.PostProcessPipeline` 取代（advanced 低层装配入口），并新增 advanced
`SurfaceInputs`、`LateSurfaceProducer`、`TemporalFramePose`、`TaaInvalidationReason`：
Render3D 装配方通过适配器提供真实 producer、帧租约和失败校验，post-process 不再导入
Render3D 类型。稳定类型及其签名未变。

v0.27.3 继续将 advanced 阴影/光照实现类型迁入职责包：`DirectionalShadowMap`、
`PointShadowAtlas`、`SpotShadowAtlas`、`SpotShadowMap`、`ShadowCullingStatistics`、
`DirectionalCascadePlan` 移到 `render3d.shadow`；`ClusteredLightingDiagnostics` 移到
`render3d.lighting`。类名、公开构造器和方法保持原语义，调用方只需更新 import。
其余 shadow／lighting 实现类型由包内可见提升为 public + `internal`，只在 render3d
子包之间装配使用，不构成兼容承诺；包内数据布局由所属模块继续拥有。
`PipelineGeneration` 的 owner 字段为私有，装配方通过类型化入口访问所需对象。
`ExternalCamera` 的可变内部位置限于包内，外部仍通过 `position()` 获取位置副本。
`SceneFrame` 和 `WorldBounds` 的 internal 调用方改用只读查询及 `copyModel`。
`WorldBounds.Scratch` 是构建器和实例批次使用的 internal 写入能力。
`ShadowPrograms` 分类为 internal，代次只在帧边界转交程序组。

v0.27.4 没有删除 stable 类型或修改稳定签名。新增 advanced `RenderFeaturesControl`、
`RenderFeatureSettings`、`RenderFeatureState`、`RenderFeatureStatus`、`RenderFeature`、
`RenderFeatureActivity`、`RenderFeatureReason`，由 `RenderPipeline.features()` 暴露；
策略类型 `RenderFeaturePolicy` 保持 internal。旧 `applyVisualSettings`、`applyGtao`、
`applyVolumetricFog`、`applyOutdoorEnvironment` 签名不变，继续同步提交并刷新统一
观察状态；新代码应使用 `features().submit(...)` 的完整快照入口。`PresentationResult`
新增 `SKIPPED_PAUSED`，仅在 master 暂停时由 `execute` 返回；已有的宿主分支无需修改，
但若对枚举做穷举 switch 需补该分支。`PipelineTopology.capture` 新增带三类阴影请求
参数的重载，旧重载等价于全部请求为 true。控制器不持有 GL 资源，暂停不释放资源。

v0.28.0 没有删除 stable 类型或修改稳定签名。advanced `EffectSnapshot` canonical record 新增
`effectTimeSeconds`、`dissolveProgress`、`lights` 组件，`MeshInstance` 新增
`lifetimeProgress` 组件；旧 4/5/6 参数快照构造器和旧 5 参数 mesh 构造器保留并填入零值。
advanced `Render3dDiagnostics` canonical record 新增 `vfxLights` 组件，旧构造器保留并填入
`VfxLightDiagnostics.UNAVAILABLE`。新增 advanced `VfxRimLight`、`VfxUvFlow`、
`VfxDissolve`、`EffectLight`、`VfxLight`、`VfxLightDiagnostics`；`LightIdAllocator`
与 `VfxLightBridge` 为包内 internal。`RenderPipeline` 新增 `vfxLights`/`maxVfxLights`/
`vfxLightDiagnostics`，`VfxRenderer` 新增 `prepare` 与 `Preparation`；旧 `record` 入口
继续懒加载并保持源兼容。二进制兼容影响：直接实现或反射读取 record canonical 组件的调用方需要
更新；仅使用旧构造器和访问器的调用方保持兼容。
v0.28.1 adds advanced VignetteSettings, FilmGrainSettings, ChromaticAberrationSettings and the corresponding postprocess passes. PostProcessSettings canonical record expanded from 3 to 6 components (binary break for direct canonical-constructor callers); the 2-arg, 3-arg and 4-arg source-compatible constructors remain. withGtao preserves all screen effects. Use PostProcessSettings.builder() for new code.

v0.28.1（候选）接触阴影没有删除 stable 类型。`ShadowSettings` 是 advanced record，新增
`contactShadows` 组件；原四参数和五参数构造器保留并默认使用
`ContactShadowSettings.disabled()`，所以既有调用方无需修改。新增 advanced
`render3d.shadow.ContactShadowSettings(enabled,distance,thickness,steps,strength)`，
默认关闭，参数作为一次 `RenderFeatureSettings.directionalShadowMap(...)` 请求发布；
它不新增 GPU 资源或 Pass，关闭时 PBR 不执行任何接触阴影采样。

v0.28.1（候选）云影没有删除 stable 类型。`ShadowSettings` 的 advanced record 再次新增
`cloudShadows` 组件；原四参数、五参数和六参数（带 `contactShadows`）构造器保留，并默认使用
`CloudShadowSettings.disabled()`。新增 advanced
`render3d.shadow.CloudShadowSettings(enabled,coverage,softness,scale,speed,strength)`：
默认关闭，`enabled=false` 或 `strength=0` 时 PBR 不执行噪声采样且图像精确不变。设置随
`RenderFeatureSettings.directionalShadowMap(...)` 一次发布，不新增 GPU 资源、Pass 或逐帧 setter。
`ShadowFramePlan`（internal）新增一个带 `FrameTiming` 的 canonical 构造器和一个保留原签名的
重载，帧时间来自 `RenderFrameContext.frameSequence()/deltaSeconds()`，供 `FrameLightBinder`
在不修改 `RenderPipeline` 的前提下累计云层滚动时间。

v0.28.1（候选）屏幕空间扭曲没有删除 stable 类型。`PostProcessSettings` 的 advanced canonical
record 从 6 个组件扩展为 7 个（新增 `screenDistortion`）；原 2/3/4 参数构造器和 v0.28.1
屏幕效果使用的 6 参数构造器保留，默认填入 `ScreenDistortionSettings.disabled()`，因此旧构造器、
builder 与访问器调用保持源码兼容；直接调用 canonical 构造器或反射读取组件的调用方需要更新。
新增 advanced `postprocess.ScreenDistortion`（稳定 `(effectId,distortionIndex)` 身份、
`centerUv/extentUv/uvOffset`、`mask`、`effectDepth/depthThickness`、`ring/haze/phase`，以及
`orderForComposite` 的 bounded far-to-near 合同）、`ScreenDistortionSettings(enabled,
maximumOffsetUv,maximumContributions,edgeFeatherUv,source)`、`ScreenDistortionSource`、
`postprocess.distortion.ScreenDistortionPass`，GL-free `vfx.VfxDistortion` 与
`render3d.vfx.VfxDistortionProjector`。效果默认关闭；开启要求 HDR tone mapping 与 HDR VFX
composite，否则装配期明确拒绝。逐帧贡献通过 `ScreenDistortionSource` 注入设置；本包没有修改
`RenderPipeline` 的公共入口，逐帧 `vfxDistortions` 通道仍待渲染管线所有者接入（见
[技术卡](../planning/v0.28.1-s1-screen-distortion.md)）。

v0.28.1（候选）景深没有删除 stable 类型。`PostProcessSettings` 的 advanced canonical record
从 7 个组件扩展为 8 个（新增 `depthOfField`）；v0.28.1 屏幕效果使用的 7 参数构造器和更早的
2/3/4/6 参数构造器保留，默认填入 `DepthOfFieldSettings.disabled()`，因此旧构造器、builder
与访问器调用保持源码兼容；直接调用 8 参数 canonical 构造器或反射读取 8 个组件的调用方需要
更新，7 参数构造器的 descriptor 不变。新增 advanced
`postprocess.DepthOfFieldSettings(enabled,focusDistance,aperture,maxCocPixels,focalLengthMm)` 与
`postprocess.depthoffield.DepthOfFieldPass`；原 4 参数构造器保留并默认 50 mm 焦距。效果默认
关闭，`maxCocPixels=0` 也是精确 no-op；
开启要求 HDR tone mapping 与共享单采样场景深度，否则装配/依赖校验明确拒绝。`PipelineTopology`
仅在景深 active 时把既有 depth 通道并入表面请求；本包没有修改 `RenderPipeline` 公共入口，
景深设置是装配期值，改变焦点等标量需要重新 build/重建候选（见
[技术卡](../../planning/v0.28.1-s3-depth-of-field.md)）。

v0.28.1（候选）运动模糊没有删除 stable 类型。`PostProcessSettings` 的 advanced canonical record
从 8 个组件扩展为 9 个（新增 `motionBlur`）；景深使用的 8 参数构造器和更早的 2/3/4/6/7 参数
构造器保留，默认填入 `MotionBlurSettings.disabled()`，因此旧构造器、builder 与访问器调用保持
源码兼容；直接调用 canonical 构造器或反射读取组件的调用方需要更新。新增 advanced
`postprocess.MotionBlurSettings(enabled,shutterFraction,maxRadiusPixels)` 与
`postprocess.motionblur.MotionBlurPass`。效果默认关闭，`shutterFraction=0` 或
`maxRadiusPixels=0` 也是精确 no-op；开启要求 HDR tone mapping 与共享单采样 velocity/validity，
否则装配/依赖校验明确拒绝。`PipelineTopology` 仅在运动模糊 active 时请求 velocity 及其
previous-surface-depth/validity 校验通道，MSAA 下经共享 surface resolve 提供单采样结果。
Pass 不采样任何历史：相机切镜由 validity=0 精确还原输入，无 velocity 的透明/VFX 采用其身后
不透明表面的 velocity；身后没有不透明表面（天空等）时同样由 validity=0 保持清晰。本包没有修改
`RenderPipeline` 公共入口；曝光比例和最大半径是装配期值，
改变后需要重新 build/重建候选（见[技术卡](../../planning/v0.28.1-s3-motion-blur.md)）。
`MotionBlurPass.recordIntoCurrentTarget` 在本候选内移除了 jitter 参数；调用方改用
`(cmd, colorTexture, velocityTexture, validityTexture, width, height)`，因为三个输入都按当前
raster 坐标采样，velocity 值本身已去 jitter。
v0.28.1（候选）有限水面没有删除 stable 类型。advanced `RenderFeatureSettings` canonical
record 从 16 个组件扩展为 17 个（末尾新增 `waterSurface`）；既有 builder、`defaults()`、
`toBuilder()` 和访问器调用保持源码兼容，直接调用 canonical 构造器的调用方需要更新。新增
advanced `render3d.water.WaterSurfaceSettings`（`enabled, level, minX/minZ/maxX/maxZ,
waveWavelength, waveSpeed, waveAmplitude, waveDirectionRadians, waterColor,
absorptionDensity, shoreFadeDistance, reflectionFallback`），默认 `disabled()` 是精确
no-op。新增 internal `render3d.water.WaterSurfacePass` 与 `RenderPipeline.waterSurface(...)`/
`waterSurface()`/`applyWaterSurface(...)`；`PipelineTopology` 仅在水面 active 时把 DEPTH
并入 raster 表面请求，并在 topology 中新增 `waterSurface` 组件（canonical 构造器为
public internal 类型，不构成兼容承诺）。水面开启要求 HDR tone mapping，且与体积雾互斥，
非法组合在 `RenderFeaturePolicy`/`PipelineFeaturePolicy` 明确拒绝。范围、限制和验证见
[有限水面技术卡](../../planning/v0.28.1-s3-water-surface.md)。
