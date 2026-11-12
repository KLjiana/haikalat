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
v0.28.1（候选）单层折射没有删除 stable 类型，也没有修改现有构造器。新增 advanced GL-free
`core.material.RefractionSettings(enabled,ior,thickness,roughness,transmission,reflection)`，
默认 `disabled()`；`Material`/`Material.Builder` 新增 `refraction(RefractionSettings)` 与
`refraction()`，默认 disabled，因此既有 `Material` 构建与绘制路径保持源码/行为兼容。新增
advanced `pbr.PbrMaterials.createGlass(...)` 与 internal `pbr.RefractionPass`；玻璃材质强制
`BlendMode.ALPHA` 并参与既有透明队列排序，管线在标准 forward 或体积雾 transparent pass 之后
插入单采样快照复制与一个折射 pass。玻璃必须由调用方提供 `pbr-forward.vert` +
`refraction-forward.frag` 程序；`RefractionSettings.disabled()` 或场景中没有启用折射的材质时
不新增 Pass、附件或 shader。玻璃材质是否存在于场景是 build 期拓扑输入；运行期切换需要重新
`build()`。多层玻璃、深度拒绝和逐层历史不承诺（见
[技术卡](../../planning/v0.28.1-s3-refraction.md)）。
v0.28.1（候选）主方向光 PCSS 没有删除 stable 类型。`ShadowSettings` 的 advanced record 再次
新增 `pcss` 组件；原四/五/六参数构造器和 v0.28.1 S1 使用的七参数（带
`contactShadows`、`cloudShadows`）构造器保留，并默认使用 `PcssSettings.disabled()`。新增
advanced `render3d.shadow.PcssSettings(enabled,lightWorldSize,blockerRadiusTexels,
blockerTaps,filterRadiusTexels,filterTaps,depthBias)`：默认关闭；`enabled=false` 或
`lightWorldSize=0` 时 PBR 继续执行原 hard/PCF 采样序列且图像精确不变。深度比较沿用方向光级联
现有的归一化 window depth `[0,1]`，`depthBias` 使用同一单位；`lightWorldSize` 为世界单位，
penumbra 经该级联的归一化正交深度比值和 world-texel 尺寸换算。所有 blocker/PCF tap 都 clamp 到
当前级联 tile，且 PCF 半径上限为 `filterRadiusTexels`。设置随
`RenderFeatureSettings.directionalShadowMap(...)` 一次发布，不新增 GPU 资源、Pass、管理器或
第二套阴影图集；启用但无方向光阴影或非 PBR 消费者时由装配策略明确拒绝。技术范围、验证边界
与已知限制见[PCSS 技术卡](../../planning/v0.28.1-s2-pcss.md)。
v0.28.1（候选）SMAA 没有删除 stable 类型。advanced `AntiAliasingMode` 新增 `SMAA` 常量；
它是单值 AA 选择，因此与 NONE/MSAA/FXAA/TAA 天然互斥，不新增组合式配置。新增 advanced
`postprocess.smaa.SmaaPass`（SMAA 1x 的 edge detection / blending weight / neighborhood
blending 三 Pass，area/search 查找纹理在程序内由上游 MIT 生成器移植算法生成，不提交二进制
资产；provenance/许可见 [SMAA licence](../../licenses/smaa.md)）。`RenderSettings.Builder`
对 `SMAA + ToneMappingMode.NONE` 抛 `IllegalStateException`，`PostProcessPipeline` 的装配与
pass-plan 查询同样拒绝；SMAA 只消费 HDR tone mapping 之后的 display-referred RGBA8，关闭时
不创建 program、查找纹理或 Pass，`NONE` 保持原颜色链。`PipelineTopology` 仍以 AA mode 作为
重建条件，SMAA 使用单采样（`sampleCount=1`）且不创建 TAA history/velocity/jitter。迁移方
只需把 `--aa=`/profile 的 `smaa` 映射到新枚举值，并在 LDR profile 中继续使用 NONE/MSAA/FXAA；
技术范围与 shader/查找表指纹见[技术卡](../../planning/v0.28.1-s2-smaa.md)。
v0.28.1（候选）清漆层没有删除 stable 类型，也没有修改既有构造器。新增 advanced GL-free
`core.material.ClearcoatSettings(enabled,roughness,normalStrength)`，默认 `disabled()`；
`Material`/`Material.Builder` 新增 `clearcoat(ClearcoatSettings)` 与 `clearcoat()`，默认
disabled，因此既有 `Material` 构建与绘制路径保持源码/行为兼容。新增 advanced
`pbr.PbrMaterials.createWithClearcoat(...)` 与带 `ClearcoatSettings`/`PbrTextureBinding` 的
`createWithBindings(...)` 重载；既有工厂重载保留并默认 `ClearcoatSettings.disabled()`，
glTF 材质路径行为不变。清漆法线贴图固定 unit 5
（`PbrMaterials.CLEARCOAT_NORMAL_UNIT`，线性色彩空间），未提供时借用 `PbrFallbackTextures`
的扁平法线；材质关闭时不新增 Pass、纹理、附件或 GPU owner。清漆是材质值，不是
`RenderFeatureSettings` 拓扑项；运行期逐对象切换使用 `MaterialInstance` 覆盖
`uClearcoatEnabled`/`uClearcoatRoughness`/`uClearcoatNormalStrength` 或 unit 5，无需重建
候选。`KHR_materials_clearcoat` 不承诺、不加载：默认严格策略拒绝材质级扩展 payload，
lenient 策略仅警告并忽略。能量分割、法线空间与零值还原见
[技术卡](../../planning/v0.28.1-s2-clearcoat.md)。
v0.28.1（候选）S3 #20 屏幕空间反射没有删除 stable 类型。`PostProcessSettings` 的 advanced
canonical record 由 9 个组件扩展为 10 个（新增 `ssr`）；原 2/3/4/6/7/8/9 参数构造器保留并默认
`SsrSettings.disabled()`，因此旧构造器、`with*` 与访问器调用保持源码兼容，直接调用 canonical
构造器或反射读取组件列表的调用方需要更新。新增 advanced `SsrSettings`、
`SsrEnvironmentSource` 与 `postprocess.ssr.SsrPass`；`SceneBufferChannel` 追加 `ROUGHNESS`
（既有枚举值不变，只有请求该通道的布局才分配 R8 附件），`SurfaceInputs.Channel` 同步追加
`NORMAL` 与 `ROUGHNESS`（既有取值不变）。`PostProcessPipeline.create` 保留原签名并新增带
`SsrEnvironmentSource` 的重载；`replacementAssembly` 增加环境参数，仅生产装配调用。
`RenderFeaturePolicy` 在统一请求入口拒绝非 HDR 或没有 PBR 材质的 SSR 请求。禁用时不创建任何
SSR 资源，pass 顺序与 `passNamesFor` 保持原语义。技术范围与指纹见
[技术卡](../../planning/v0.28.1-s3-ssr.md)。
v0.28.1（候选）S2 #17 各向异性高光没有删除 stable 类型，也没有修改既有构造器。新增
advanced GL-free `core.material.AnisotropicSettings(strength, rotation)`（strength 有限
`[0,1]`，rotation 有限弧度；0 强度精确走各向同性路径），并在 `core.allowlist` 登记。
`Material`/`Material.Builder` 新增 `anisotropic(AnisotropicSettings)` 与
`anisotropic()`，默认 `AnisotropicSettings.disabled()`；既有 `Material` 构建与绘制路径
保持源码/行为兼容。`pbr.PbrMaterials` 新增 `createWithAnisotropy(...)` 与末位带
`AnisotropicSettings` 的 `createWithBindings(...)` 重载；既有重载保留并默认
`AnisotropicSettings.disabled()`，glTF 材质路径行为不变。各向异性是材质值，不是
`RenderFeatureSettings` 拓扑项；不新增 Pass、纹理、附件或 GPU owner，逐对象切换使用
`MaterialInstance` 覆盖 `uAnisotropicEnabled`/`uAnisotropicStrength`/
`uAnisotropicRotation` 即可，无需重建候选。Burley D/V、切线帧回退、镜像/负行列式与
有界 IBL 近似见[技术卡](../../planning/v0.28.1-s2-anisotropic.md)。

v0.28.1（候选）S3 #31 视差遮蔽贴图没有删除 stable 类型，也没有修改既有构造器或
枚举常量。新增 advanced GL-free
`core.material.ParallaxOcclusionSettings(enabled,heightScale,minSteps,maxSteps,fadeStart,fadeEnd,fadeStartDistance,maxDistance,heightChannel,flipHeight)`
（嵌套 `HeightChannel`；高度/步骤/角度窗/距离窗构造期校验；`disabled()` 与零
`heightScale` 精确走原 UV），并在 `core.allowlist` 登记。`Material`/`Material.Builder`
新增 `parallaxOcclusion(ParallaxOcclusionSettings)` 与 `parallaxOcclusion()`，默认
`disabled()`；既有 `Material` 构建与绘制路径保持源码/行为兼容。`PbrTextureRole`
追加 `HEIGHT`（`LINEAR`，既有常量与顺序不变）；`PbrFallbackTextures` 的 1×1 fallback 追加
RGBA `(0,0,0,0)` 高度图（四个通道含 ALPHA 均为 0；`flipHeight=false` 时缺图为平面）。`pbr.PbrMaterials` 新增 `createWithParallax(...)`、末位带
`ParallaxOcclusionSettings` 的完整 `createWithBindings(...)` 重载、固定
`HEIGHT_UNIT = 6`；既有工厂保留并默认 `ParallaxOcclusionSettings.disabled()`，glTF
材质路径行为不变。POM 是材质值，不是 `RenderFeatureSettings` 拓扑项；不新增 Pass、
FBO、历史或 GPU owner，逐对象切换使用 `MaterialInstance` 覆盖 `uParallax*` 或 unit 6
即可。首版只改后续材质采样 UV，不改几何深度、shadow map、velocity 或轮廓，也没有
silhouette/自遮挡/tessellation；clearcoat 法线贴图不参与 POM。算法、独立 double
oracle、限制与验证状态见[技术卡](../../planning/v0.28.1-s3-pom.md)。

v0.28.1（候选）S3 #28 加权混合 OIT 没有删除 stable 类型，也没有修改既有构造器或枚举取值。新增 advanced
`render3d.transparency.WeightedOitSettings`（`enabled, depthWeighting, depthScale, referenceDepth,
minimumWeight, maximumWeight, alphaThreshold, maximumSurfaces`，全部构造期校验，`disabled()` 是精确
no-op）与 advanced `render3d.transparency.OitSurface`（世界空间平行四边形 `center/halfRight/halfUp`
+ 线性 HDR 颜色 + straight alpha），以及 internal `render3d.transparency.WeightedOitPass`。
`enabled=false` 或 `settings.active()==false` 时不创建 Pass/附件/绘制；`alphaThreshold` 是逐层
discard 阈值，不是 alpha 下限 clamp。附件 0 `RGBA16F` 的 RGB 存 `Σ(c·α·w)`、A 存 `Π(1-α)`
（revealage）；附件 1 `R16F` 存 `Σ(α·w)`（权重和，revealage 不在 R16F）。surface 端把单层权重与
贡献 clamp 到 `3.0e4`，resolve 对非有限/超过 `6.5e4` 的附件回退为 coverage=0（目标不变）并把平均颜色
clamp 到 `65504`，因此任意合法输入产生有限 target。本包没有修改 `RenderPipeline` 公共入口；
`RenderFeatureSettings`/`RenderPipeline` 的字段与 `weightedOit(...)`/`applyWeightedOit(...)` 接线
由 Integrator 完成。共享 typed 命令层新增 `CommandBuffer.blendFuncSeparate(...)`、对应
`PipelineStateSink`/`StateCache` 四因子缓存与 `PendingPipelineState` 4 整数编码/解码；既有
`blendFunc` 的调用语义和源码兼容性不变（alpha 因子等于 RGB 因子时仍编码为 `glBlendFunc`）。
技术范围、有限输出保护与误差界见 [技术卡](../../planning/v0.28.1-s3-oit.md)。

v0.28.1（候选）S3 #11 屏幕空间水面折射没有删除 stable 类型，也没有修改既有构造器或枚举取值。新增 advanced
`render3d.water.WaterRefractionSettings`（IOR、折射强度、最大屏幕偏移、厚度缩放、前景拒绝 bias、离屏回退，
全部构造期校验；`disabled()` 是精确 no-op），`WaterSurfacePass` 新增带折射设置的重载/构造函数与
`recordBackgroundCopy(...)`，以及 `WaterSurfacePass.REFRACTION_BACKGROUND_PASS` 提供的受控背景颜色拷贝。
`WaterSurfaceSettings` 与旧 `record`/构造函数保持不变；`enabled=false` 时水面 shader 源与 #14 基线逐字节一致，
GL baseline 用例逐分量对照。首版是屏幕空间近似：无真实水下体积/色散/多层水体/焦散，前景拒绝仅用共享不透明深度，
折射位移不受 shore alpha 门控；与 #13 玻璃折射互斥、与 SSR/体积雾的水面限制不变。RenderPipeline 的
`WaterRefractionSettings` 字段与 companion pass 接线由 Integrator 完成。算法、限制与验证状态见
[技术卡](../../planning/v0.28.1-s3-water-refraction.md)。

v0.28.1（候选）S4 #34/#35 没有删除 stable 类型或修改稳定签名。advanced `PostProcessSettings` 的
canonical record 由 10 个组件扩展为 11 个（S4 #34 新增 `temporalDenoise`），再扩展为 12 个（S4 #35
新增 `temporalUpscaling`）；原有 2/3/4/6/7/8/9/10/11 参数构造器全部保留，新组件默认 `disabled()`，
源码兼容。S4 #34 新增 advanced `postprocess.TemporalDenoiseSettings`、`denoise.TemporalDenoiseInputs`
与 `denoise.TemporalDenoisePass`；S4 #35 新增 advanced `postprocess.TemporalUpscalingSettings`、
`postprocess.TemporalUpscalingSource`、`upscale.TemporalUpscalingInputs` 与
`upscale.TemporalUpscalingPass`，`TemporalUpscalingHistory` 为包内实现。两个 temporal owner 都以
pass-owned external target 发布颜色：`PostProcessPipeline` 在装配期拒绝 `Bloom` 与
`ExposureMode.AUTO`（external-target producer 没有 managed colour/luminance source），
temporal upscaling 还拒绝全分辨率 surface consumer、late reactive producer、非 HDR 和 TAA/MSAA。
这些是首版 fail-closed 限制，不是静默回退。技术范围、限制与 GL 证据见
[时域放大技术卡](../../planning/v0.28.1-s4-temporal-upscaling.md)与
[时域降噪技术卡](../../planning/v0.28.1-s4-temporal-denoise.md)。

v0.28.1（候选）S4 #32/#33/#38 没有删除 stable 类型或修改稳定签名。advanced `PostProcessSettings` 的
canonical record 由 12 个组件扩展为 13 个（S4 #32/#33 新增 `subsurfaceScattering`）；原有 2/3/4/6/7/8/9/10/11/12
参数构造器全部保留，新组件默认 `disabled()`，源码兼容。S4 #32/#33 新增 advanced
`core.material.SubsurfaceScatteringSettings` 与 `postprocess.sss.SubsurfaceScatteringPass`，`Material` 追加
`subsurfaceScattering` 组件；共享 surface 追加 `SUBSURFACE` mask 通道，`SceneSurfacePass`/resolve 生产者与
`SubsurfaceScatteringPass` 消费者成对启用。S4 #38 新增 advanced
`render3d.volumetric.VolumetricParticleSettings` 与 `render3d.volumetric.VolumetricParticlePass`；pass 只消费
调用方已合成的 HDR 颜色与同帧不透明深度，不接管体积雾 owner，也不包含 #43 烟雾模拟。技术范围、限制与 GL
证据见[次表面散射技术卡](../../planning/v0.28.1-s4-sss.md)与
[体积粒子技术卡](../../planning/v0.28.1-s4-volumetric-particles.md)。

v0.28.1（候选）S4 #38/#37 生产接线没有删除 stable 类型或修改稳定签名。advanced
`RenderFeatureSettings` canonical record 由 17 个组件扩展为 19 个（末尾新增 `volumetricParticles`
与 `stochasticTransparency`，默认 `disabled()`）；既有 builder、`defaults()`、`toBuilder()` 与访问器
调用保持源码兼容，直接调用 canonical 构造器的调用方需要更新。`RenderFeature` 末尾追加
`VOLUMETRIC_PARTICLES`、`STOCHASTIC_TRANSPARENCY`；`TaaInvalidationReason` 末尾追加
`VOLUMETRIC_PARTICLE_SETTINGS`、`STOCHASTIC_TRANSPARENCY_SETTINGS`（既有常量与序号不变）。
`RenderPipeline` 新增 `volumetricParticles(...)`/`volumetricParticles()`/
`applyVolumetricParticles(...)`、`stochasticTransparency(...)`/`stochasticTransparency()`/
`applyStochasticTransparency(...)` 与 `stochasticTransparencySurfaces(...)`/
`stochasticTransparencySurfaces()`；`RenderPipeline` 其余构造器与 `passNamesFor` 入口不变。
`PipelineTopology` 追加 `volumetricParticles`/`stochasticTransparency` 两个 internal 组件，并提供
保留旧参数列表的 `capture(...)` 重载。粒子接线只在体积雾合成之后、透明 replay/HDR VFX 之前插入；
随机透明在最终场景颜色上原位合成，图节点不声明深度附件。关闭时拓扑与基线逐字节一致；MSAA
（粒子/随机透明）与 TAA（随机透明）保持 `UNAVAILABLE`，不改变图。没有新增 public 顶层类型，
`docs/architecture/public-api/render3d.allowlist` 无需新增条目。技术范围、顺序、限制与接线验证见
[体积粒子技术卡](../../planning/v0.28.1-s4-volumetric-particles.md)与
[随机透明技术卡](../../planning/v0.28.1-s4-stochastic-transparency.md)。
