# Render3D 高级功能逐类审查（2026-11-04）

审查基线：工作区版本 0.25.0，HEAD ed8b4cd。审查对象为 `src/main/java/com/kaleblangley/haikalat/subsystems/render3d` 递归目录的 128 个 Java 文件（约 20,666 行），包含其中的内部类型与方法。已逐文件阅读全文，并追踪关键 shader、RenderGraph 和 glTF 上传消费者。shader 是按关键链路补充检查，未声称逐行审查整个项目的所有 shader、资产加载器或测试。

这是静态源码审查，不是本轮 GPU、视觉、性能或重启验收；本轮没有运行构建和 GL 测试。既有发布报告不作为当前组合已通过的替代证据。仅新增本报告，不修改实现或覆盖工作区并行改动。

## 结论

主干高级渲染不是空架子：PBR/IBL、Clustered Forward、CSM/局部阴影、GTAO、共享场面缓冲、TAA、体积雾、glTF Skin/Morph 均有真实资源、计算或绘制和主入口接入。

但“有实现”不能等同于“所有组合已完成”。目前存在明确的预留项、实验能力、参数入口缺口和跨功能错误。最值得优先修复的是 TAA+自动曝光、MSAA+GTAO 和 MASK 的跨 pass 一致性。项目更接近功能较丰富的渲染框架，不宜直接描述为全部高级能力都已生产就绪。

## 已确认的源码缺陷

### F1 — 高：TAA + 自动曝光时，亮度采样 pass 静默不录制

- `PostProcessPassBuilder.java:435` 起：TAA 使用 `writeToExternalTarget()`，之后 HDR producer 改成 TAA pass。
- 同文件 `608–620`：自动曝光取 `framebufferOfPass(sourcePass)`，只有非 null 才调用 `recordLuminance`。
- `core/graph/PassResources.java:33`、`RenderGraph.java:230`：此 API 查询 graph 管理的目标；TAA 历史目标由 TaaHistory 自己持有，没有对应的 managed framebuffer。

结果：TAA 图像本身可生成，AUTO luminance 却跳过。后续 reduction/adaptation 仍继续，读取没有在该帧写入的 luminance 内容，曝光结果不可靠。不能保证具体画面一定变黑或出现某个固定数值。

修复方向：像已有 Bloom 的 TAA 分支一样，使用实际纹理和图尺寸，或建立统一的图像输入描述。验收必须使用真实 RenderPipeline 的 TAA+AUTO，输入亮暗变化并检查曝光响应；只测 AutoExposure 独立组件不够。

### F2 — 高：MSAA + GTAO 缺少 resolve producer 依赖

- `PipelineTopology.java:143` 起为 GTAO 请求共享深度/法线，最终带 MSAA sample count。
- `ForwardPassBuilder.java:97` 起：单样本 sceneDepth/sceneNormal 由 SceneSurfaceResolvePass 写入。
- `GtaoPasses.java:150`：estimate 仅依赖 SceneSurfacePass；`454` 起却读取单样本 sceneDepth 和 sceneNormal。
- `RenderPipeline.java:675` 的 AO pass 注册早于 forward pass 注册；`RenderGraphCompiler.java` 按声明次序稳定拓扑排序，只消费显式依赖。

因此 surface 完成后，AO 链可以先于 resolve 执行；首帧可能读取未初始化纹理，后续读取旧帧内容。这是实际 producer-consumer 次序错误，不是“GTAO 没有算法”。

修复方向：MSAA 下 AO 依赖 SceneSurfaceResolvePass，其他模式依赖 SceneSurfacePass。验收除图顺序断言外，还应覆盖首帧、移动、resize 的生产路径图像。

### F3 — 高：MASK 顶点 alpha 在颜色、阴影和表面通道不一致

- `shaders/render3d/pbr/pbr-forward.frag:403–405`：裁剪 alpha = baseColor 纹理 × factor × vertexColor。
- `shaders/shadows/masked-directional-depth.frag:7`：只有纹理 × factor。
- `shaders/render3d/gtao/gtao-masked-depth.frag:9`：同样缺 vertexColor。
- `shaders/render3d/surface/scene-surface.frag:43`：共享表面也缺 vertexColor。

当 MASK 材质使用顶点透明度时，主画面已经镂空的位置仍可能写阴影、深度、法线或运动数据，连带影响 AO、TAA 和雾。普通纹理 alpha 的 MASK 不代表全部 MASK 语义已完成。

修复方向：统一 alpha 判定和属性传递；使用有顶点 alpha 的 glTF 网格对比颜色、阴影和共享表面覆盖，不只验 shader 编译。

### F4 — 中：glTF Morph 上传没有计入字节预算

- `gltf/GltfSceneAsset.java:139–143`：estimatedNextBytes 对 MORPH 返回 0。
- 同类 MORPH 阶段实际构造 GltfMorphTargetBuffer。
- `gltf/GltfMorphTargetBuffer.java:27–37`：分配并上传 targetCount × vertexCount × 12 × 4 字节。
- `subsystems/scene/GltfGpuAssetCache.java:117` 起，pump 正是按该估计累计和检查 maxBytes。

例如 10 万顶点、8 个 targets 的 delta 为 38,400,000 字节，但该阶段预算记 0。预算本来是软预算，允许第一个大步骤前进；问题在于 Morph 连计数都没有，无法正确阻止后续步骤，可能加重上传卡顿。

修复方向：准确估计 Morph 字节并保留既有软预算前进语义；测试多资产轮转和大 Morph 步骤。

### F5 — 中：maxLocalLights=0 被设置层允许，却被资源层拒绝

- `ClusteredLightingSettings.java:32` 接受 [0,65536]。
- `LightTablePacker.java:34–38` 对零灯容量计算零字节 bounds。
- `ClusteredLightingResources.java:267–273` 拒绝 size<=0 的任何缓冲。

注意“场景当前没有局部灯”与“配置局部灯容量为零”不同；默认容量下的零灯场景不受此项结论影响。

修复方向：明确是否支持零容量；支持则提供最小占位存储并保持逻辑容量零，否则设置构造时就拒绝。

### F6 — 中：ClusterMath.bounds 的 CPU 参考角点计算错误

`ClusterMath.java:122` 起使用数组 {x0,y0,x1,y1}，但两轴分别只访问 index&1 与 index>>1，实际都只会读到 x0/y0，x1/y1 从未参与。即使是 x0=y0 的方形 tile，四个角也退化为同一个 XY。

生产 GPU `cluster-bounds.comp` 显式列出四个正确角点，且搜索发现 ClusterMath 的消费者为测试。因此本项是 CPU 参考实现及验证可信度问题，不能据此认定生产 clustered shader 的包围盒也错误。

修复方向：改成明确四角，并用独立数学预期测试非方形网格、非对称 tile 和边缘扩展。

## 还需要故障注入确认的风险

### R1 — 无共享表面 pass 时，Morph 上传缓存的失败恢复可能遗漏

`MorphSceneDrawBinding.record` 在录制 upload 时就更新 uploadedRevision；清除它依赖 discardTemporalFrame。RenderPipeline 在失败时只对存在 sceneSurfacePass 的管线调用 temporalSceneState.discardFrame。

对 NONE/FXAA、无 AO/雾等不需要共享表面的管线，如果 Morph upload 录制后发生异常且命令未提交，下一帧同 revision 可能跳过必要上传。需要在生产图“录制后、执行前”注入失败验证；本报告将其列为风险，不声称已有运行复现。

## 预留、受限和实验能力

| 编号 | 能力 | 当前状态与边界 |
|---|---|---|
| L1 | Deferred rendering | RenderPipelineKind.DEFERRED 只有枚举；RenderPipeline.kind() 固定返回 FORWARD，未发现 deferred 管线构建分支。不能算已实现的延迟渲染。 |
| L2 | 高层 TAA 调参 | TaaSettings 参数真实被低层 resolve 消费，但 PostProcessPassBuilder 将其固定为 private final defaults()，高层 RenderPipeline 没有对应注入入口。属于封装未开放，不是 TAA 算法空壳。 |
| L3 | GPU 粒子 | GpuParticleExperiment 有真实 compute、SSBO、barrier 和点绘制，demo 有消费者；shader 的重生区域和运动规则固定。属于受控实验，不是可配置通用 GPU 特效系统。 |
| L4 | VFX 批处理 | VfxRenderer 在 transparentDrawOrder 循环中逐 primitive drawMesh。粒子、Ribbon、平面 Decal、Mesh、soft particle 有实现，但不能把它称为大规模实例批处理；这里的 Decal 也不是通用体积投射贴花。 |
| L5 | 资源预览 | 有真实转换和 resolve；backbuffer 和未注册 external target 显式不可预览，不能理解为所有 graph 资源都能看。 |
| L6 | glTF Graph Morph | 真实 Morph 和动画绑定存在，Graph 驱动路径对 morph 节点数有限制；限制会显式拒绝，不是默默空跑。 |
| L7 | 阴影预算 | 一个方向阴影选择，局部上限 2 个点光、4 个聚光；其余灯仍可照明。是有界设计，不是多灯光照没有实现。 |
| L8 | PBR/GTAO 范围 | PBR 要求 HDR 和显式环境；GTAO 要求 PBR，shader 作用于间接光。不是全材质通用 AO。 |

合理的空方法不应误判成“半成品功能”：SceneDrawBinding.NONE 是无附加绑定的 Null Object；TemporalDrawBinding 默认 pre-commit 是可选扩展；PostProcessPassBuilder.prepareFrameSuccess 是空的生命周期 hook，实际历史提交在 frameSucceeded。它们与“有公开功能开关但整个算法为空”不同。

## 功能实现状态树

```text
Render3D
├── 主渲染
│   ├── Forward / Clustered Forward：实际实现
│   ├── PBR + IBL：实际实现
│   └── Deferred：仅预留枚举
├── 场景与资产
│   ├── 模型/包围盒缓存、视锥剔除、透明排序：实际实现
│   ├── glTF Skin / Morph：实际实现，预算与失败恢复待完善
│   └── InstancedRenderer：实际实现
├── 阴影
│   ├── CSM、点光/聚光 atlas、PCF：实际实现
│   ├── 预算选择、滞回、逐 tile 缓存、caster 剔除：实际实现
│   └── 顶点 alpha MASK 一致性：缺口
├── 表面与时域
│   ├── 共享深度/法线/速度/历史有效性：实际实现
│   ├── GTAO：实际实现，MSAA 组合依赖错误
│   └── TAA：实际实现，高层参数入口不足
├── 视觉与后处理
│   ├── 天空、解析雾、Bloom、色调映射/调色：有实际执行链
│   ├── 自动曝光：有实现，TAA 组合亮度采样被跳过
│   └── 体积雾：介质→光照→时域→积分→合成链实际存在
├── VFX
│   ├── CPU 快照适配绘制：实际实现，逐 primitive draw
│   └── GPU 粒子：独立受控实验
└── 诊断
    ├── 帧统计、灯光/阴影/体积统计：实际数据路径
    └── Graph 预览：实际实现，外部目标覆盖有限
```

## 是否臃肿，以及下一步

没有证据支持“大部分代码只是堆接口”。大量值对象、资源 owner、快照、缓存键承担了具体职责。真正需要警惕的是组合复杂度：RenderPipeline 2691 行、PostProcessPassBuilder 959 行、ShadowCasterPlanner 807 行，主入口同时协调资源代际、阴影、时域、体积与后处理，容易出现 F1/F2 这种单项存在、跨项接错的问题。

还保留了 GltfSceneAsset.uploadDirect 等旧实现以及 RenderPipeline.directionalCascadePlan 私有辅助，搜索未找到调用。它们属于维护噪声，应在独立清理中确认后移除，不能算当前有效能力。

建议顺序：

1. 先补 F1/F2/F3 的生产组合回归，并修复问题。
2. 完成 F4/F5/F6 的资源预算与边界契约，故障注入确认 R1。
3. 明确高级功能状态和高层设置入口，避免将 Deferred/GPU 实验列成完整产品功能。
4. UI Compositor 计划可以继续，核心重点应是现有渲染能力可观察、可配置；暂缓同时增加一批新高级效果。
5. 再按实际项目场景决定 VFX 批处理、更多阴影预算或新算法。性能提升需实测，源码结构不能给出帧率承诺。

现有主干足以作为有范围约束的 3D 示例、可视化应用或游戏渲染模块的基础；本审查不等于完整项目的输入、编辑器、资产工作流、跨设备兼容和发布验收已经完成。

## 逐类阅读清单

“已读”表示本次阅读了该 Java 文件的实现，不表示已经逐类执行测试或证明无缺陷。下面每行对应一个实际文件，路径以 render3d 目录为基准。

| 文件 | 行数 | 审查结果 |
|---|---:|---|
| [BoundsTransforms.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/BoundsTransforms.java) | 79 | 已读：有限仿射矩阵与世界 AABB 变换 |
| [Camera.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/Camera.java) | 228 | 已读：相机姿态、投影输入及修订管理 |
| [CameraProjection.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/CameraProjection.java) | 23 | 已读：稳定投影公共入口 |
| [CameraUniforms.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/CameraUniforms.java) | 48 | 已读：相机 UBO 上传及抖动投影 |
| [ClusterDebugMode.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ClusterDebugMode.java) | 11 | 已读：调试模式枚举，PBR shader 有对应分支 |
| [ClusteredLightingBinder.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ClusteredLightingBinder.java) | 351 | 已读：灯表/网格上传、compute 调度与统计 fence |
| [ClusteredLightingDiagnostics.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ClusteredLightingDiagnostics.java) | 39 | 已读：有界诊断快照 |
| [ClusteredLightingPassBuilder.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ClusteredLightingPassBuilder.java) | 55 | 已读：灯表上传与 cluster compute pass 接入 |
| [ClusteredLightingResources.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ClusteredLightingResources.java) | 507 | 已读：实际 SSBO/UBO、resize 与释放；见 F5 |
| [ClusteredLightingSettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ClusteredLightingSettings.java) | 101 | 已读：容量/预算校验；零局部灯容量与资源层不一致，见 F5 |
| [ClusterGrid.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ClusterGrid.java) | 186 | 已读：网格尺寸、投影及内存估计 |
| [ClusterMath.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ClusterMath.java) | 171 | 已读：CPU 参考计算；bounds 角点索引错误，见 F6 |
| [DepthResolveDescriptor.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/DepthResolveDescriptor.java) | 27 | 已读：深度 resolve 格式与拓扑约束 |
| [DirectionalCascadePlan.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/DirectionalCascadePlan.java) | 171 | 已读：真实 CSM 分割和光空间矩阵 |
| [DirectionalCascadeSettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/DirectionalCascadeSettings.java) | 40 | 已读：CSM 参数校验 |
| [DirectionalShadowMap.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/DirectionalShadowMap.java) | 54 | 已读：单方向阴影投影 |
| [ExternalCamera.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ExternalCamera.java) | 157 | 已读：外部相机矩阵冻结与验证 |
| [FogMediumSettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/FogMediumSettings.java) | 47 | 已读：介质消光、反照率、自发光参数 |
| [ForwardPassBuilder.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ForwardPassBuilder.java) | 159 | 已读：阴影、共享表面、MSAA resolve、主场景 pass；见 F2 |
| [FrameInvalidation.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/FrameInvalidation.java) | 79 | 已读：按修订域计算失效原因 |
| [FrameLightTable.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/FrameLightTable.java) | 144 | 已读：统一灯表冻结及稳定身份映射 |
| [Frustum.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/Frustum.java) | 106 | 已读：视锥平面和 AABB 剔除 |
| [gltf/CloseStack.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/gltf/CloseStack.java) | 47 | 已读：资源创建失败回滚 |
| [gltf/GltfMorphTargetBuffer.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/gltf/GltfMorphTargetBuffer.java) | 77 | 已读：真实 Morph delta SSBO 打包上传 |
| [gltf/GltfRuntimeLibrary.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/gltf/GltfRuntimeLibrary.java) | 65 | 已读：共享程序及材质后端资源 |
| [gltf/GltfSceneAsset.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/gltf/GltfSceneAsset.java) | 641 | 已读：分阶段 GPU 上传、材质/网格/Morph；预算缺口 F4，保留旧上传实现 |
| [gltf/GltfSceneInstance.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/gltf/GltfSceneInstance.java) | 773 | 已读：实例姿态、动画与场景绑定；Graph Morph 节点范围有限 |
| [gltf/MorphSceneDrawBinding.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/gltf/MorphSceneDrawBinding.java) | 189 | 已读：当前与上一帧 Morph 权重上传；失败恢复风险 R1 |
| [gltf/SkinSceneDrawBinding.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/gltf/SkinSceneDrawBinding.java) | 164 | 已读：骨骼 palette 与上一帧 palette 上传 |
| [GtaoHistory.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/GtaoHistory.java) | 191 | 已读：双缓冲 AO 历史、resize 和提交 |
| [GtaoPasses.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/GtaoPasses.java) | 645 | 已读：AO 估计/时域/去噪/上采样有实现；MSAA 依赖缺失 F2 |
| [InstancedRenderer.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/InstancedRenderer.java) | 268 | 已读：实例矩阵上传、绘制和历史状态 |
| [LightTablePacker.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/LightTablePacker.java) | 163 | 已读：灯表和包围球字节布局 |
| [LightType.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/LightType.java) | 7 | 已读：方向/点/聚光灯类型 |
| [LightVolumeHints.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/LightVolumeHints.java) | 13 | 已读：逐灯体积散射参与设置 |
| [LightVolumePacker.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/LightVolumePacker.java) | 24 | 已读：体积灯提示 SSBO 数据打包 |
| [LocalFogVolume.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/LocalFogVolume.java) | 75 | 已读：球/盒局部雾、噪声及介质参数 |
| [LocalShadowPipelineSettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/LocalShadowPipelineSettings.java) | 73 | 已读：局部阴影预算、筛选和缓存设置 |
| [LocalShadowSettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/LocalShadowSettings.java) | 18 | 已读：局部阴影分辨率与 bias |
| [MeshRenderer.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/MeshRenderer.java) | 73 | 已读：网格材质、模型与 deformation 绑定 |
| [OutdoorEnvironmentSettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/OutdoorEnvironmentSettings.java) | 34 | 已读：天空与解析雾组合设置 |
| [pbr/EnvironmentBackgroundRenderer.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/pbr/EnvironmentBackgroundRenderer.java) | 70 | 已读：环境 cubemap 背景绘制 |
| [pbr/EnvironmentPreprocessor.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/pbr/EnvironmentPreprocessor.java) | 235 | 已读：IBL compute 预处理、mip 和 barrier |
| [pbr/PbrBrdfMath.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/pbr/PbrBrdfMath.java) | 72 | 已读：CPU BRDF 数学辅助 |
| [pbr/PbrEnvironment.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/pbr/PbrEnvironment.java) | 80 | 已读：IBL 资源集合与所有权 |
| [pbr/PbrEnvironmentLoader.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/pbr/PbrEnvironmentLoader.java) | 70 | 已读：HDR/天空环境加载与生成 |
| [pbr/PbrEnvironmentSettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/pbr/PbrEnvironmentSettings.java) | 44 | 已读：IBL 生成规格和校验 |
| [pbr/PbrFallbackTextures.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/pbr/PbrFallbackTextures.java) | 79 | 已读：PBR 默认纹理实际分配 |
| [pbr/PbrMaterialBinder.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/pbr/PbrMaterialBinder.java) | 82 | 已读：IBL 纹理及参数绑定 |
| [pbr/PbrMaterials.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/pbr/PbrMaterials.java) | 149 | 已读：金属粗糙度材质、纹理和 alpha 模式映射 |
| [pbr/PbrTextureBinding.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/pbr/PbrTextureBinding.java) | 14 | 已读：材质纹理与 sampler 值对象 |
| [PipelineFeaturePolicy.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/PipelineFeaturePolicy.java) | 34 | 已读：跨功能兼容性校验，不是实现占位 |
| [PipelineGeneration.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/PipelineGeneration.java) | 282 | 已读：一代管线资源与关闭管理 |
| [PipelineTopology.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/PipelineTopology.java) | 183 | 已读：按功能生成拓扑与共享缓冲需求 |
| [PointShadowAtlas.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/PointShadowAtlas.java) | 130 | 已读：点光六面矩阵和 atlas 布局 |
| [PointShadowSlotPlan.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/PointShadowSlotPlan.java) | 70 | 已读：点光槽位、逐面缓存计划 |
| [PostProcessPassBuilder.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/PostProcessPassBuilder.java) | 959 | 已读：实际后处理 pass 构造；TAA+AUTO 缺口 F1，设置入口限制 L2 |
| [preview/GraphPreviewController.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/preview/GraphPreviewController.java) | 167 | 已读：UI/render 请求交换与输出发布 |
| [preview/GraphPreviewRenderer.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/preview/GraphPreviewRenderer.java) | 608 | 已读：惰性预览资源、resolve、转换绘制；外部目标受限 |
| [preview/PreviewAspect.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/preview/PreviewAspect.java) | 8 | 已读：预览纹理 aspect 枚举 |
| [preview/PreviewOptions.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/preview/PreviewOptions.java) | 111 | 已读：预览参数校验和不可变修改 |
| [preview/PreviewSourceCatalog.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/preview/PreviewSourceCatalog.java) | 176 | 已读：图附件与 cubemap 目录；外部目标标记不可预览 |
| [preview/PreviewSourceDescription.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/preview/PreviewSourceDescription.java) | 45 | 已读：纯值预览元数据 |
| [preview/PreviewSourceKey.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/preview/PreviewSourceKey.java) | 67 | 已读：带 generation 的逻辑资源身份 |
| [Render3dDiagnostics.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/Render3dDiagnostics.java) | 144 | 已读：实际管线统计快照；不是绘制功能 |
| [RenderFrameContext.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderFrameContext.java) | 93 | 已读：帧输入冻结与场景稳定性检查 |
| [RenderPipeline.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderPipeline.java) | 2691 | 已读：实际主入口、资源代际、执行、提交与失败处理；体量较大 |
| [RenderPipelineKind.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderPipelineKind.java) | 6 | 已读：FORWARD 实用，DEFERRED 仅预留枚举，见 L1 |
| [RenderQueueClass.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderQueueClass.java) | 36 | 已读：OPAQUE/MASK/ALPHA/ADDITIVE 分类 |
| [RenderQueueSorter.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderQueueSorter.java) | 99 | 已读：稳定索引排序，alpha 按深度排序 |
| [RevisionedModelSource.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RevisionedModelSource.java) | 41 | 已读：模型修订接口及固定/Transform 实现 |
| [Scene.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/Scene.java) | 217 | 已读：场景成员、灯光及修订；未提供 renderer 删除 API |
| [SceneBufferChannel.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneBufferChannel.java) | 22 | 已读：共享表面通道语义 |
| [SceneBufferRequirements.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneBufferRequirements.java) | 135 | 已读：按消费者合并通道需求 |
| [SceneBufferView.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneBufferView.java) | 98 | 已读：借用的帧级纹理句柄视图 |
| [SceneDrawBinding.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneDrawBinding.java) | 55 | 已读：自定义绘制绑定协议；NONE 空操作合理 |
| [SceneDrawOrder.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneDrawOrder.java) | 67 | 已读：旧对象列表排序辅助；新 arena 排序另由 RenderQueueSorter 完成 |
| [SceneFrame.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneFrame.java) | 87 | 已读：借用帧 arena 与队列统计 |
| [SceneFrameBuilder.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneFrameBuilder.java) | 558 | 已读：模型/AABB 缓存、可见性与透明排序实际实现 |
| [SceneLight.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneLight.java) | 93 | 已读：灯光参数验证和工厂 |
| [SceneLightEntry.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneLightEntry.java) | 35 | 已读：稳定灯 ID、独立修订与冻结 |
| [SceneObject.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneObject.java) | 86 | 已读：固定/动态/带修订模型入口 |
| [SceneReactivePass.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneReactivePass.java) | 90 | 已读：按材质 distrust 重画 reactive mask，保守覆盖 |
| [SceneRevisionSnapshot.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneRevisionSnapshot.java) | 40 | 已读：场景六域修订快照 |
| [SceneSurfacePass.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneSurfacePass.java) | 273 | 已读：共享深度/法线/运动数据绘制；MASK 一致性缺口 F3 |
| [SceneSurfaceResolvePass.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SceneSurfaceResolvePass.java) | 96 | 已读：MSAA 选最近样本并保持通道对应 |
| [ScreenQuad.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ScreenQuad.java) | 62 | 已读：全屏 quad VAO/VBO |
| [ShadowAtlasAllocator.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowAtlasAllocator.java) | 83 | 已读：稳定 ID 到 atlas 槽位分配 |
| [ShadowCacheKey.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowCacheKey.java) | 37 | 已读：逐域阴影缓存失效分类 |
| [ShadowCacheState.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowCacheState.java) | 329 | 已读：逐 tile 事务缓存与失败重画 |
| [ShadowCasterPlanner.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowCasterPlanner.java) | 807 | 已读：20 视图 arena、稀疏 caster 更新和剔除 |
| [ShadowCullingStatistics.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowCullingStatistics.java) | 40 | 已读：有界剔除统计 |
| [ShadowDecision.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowDecision.java) | 15 | 已读：阴影筛选结果分类 |
| [ShadowFilterMode.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowFilterMode.java) | 23 | 已读：硬阴影/3x3/5x5 PCF 枚举，有 shader 消费 |
| [ShadowFrameBinder.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowFrameBinder.java) | 57 | 已读：CSM 与灯表阴影索引上传 |
| [ShadowFramePlan.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowFramePlan.java) | 83 | 已读：不可变逐帧阴影分配计划 |
| [ShadowLightHints.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowLightHints.java) | 10 | 已读：阴影优先级提示 |
| [ShadowLightScheduler.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowLightScheduler.java) | 350 | 已读：真实预算排序、滞回、槽位和投影规划 |
| [ShadowSamplingBlock.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowSamplingBlock.java) | 119 | 已读：局部阴影矩阵/atlas rect/采样参数 UBO |
| [ShadowSelectionMode.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowSelectionMode.java) | 9 | 已读：场景顺序/相机重要性策略 |
| [ShadowSettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowSettings.java) | 26 | 已读：方向阴影参数 |
| [ShadowTileRect.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/ShadowTileRect.java) | 17 | 已读：atlas 像素与 UV 范围 |
| [SpotShadowAtlas.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SpotShadowAtlas.java) | 85 | 已读：聚光灯 atlas 布局 |
| [SpotShadowMap.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SpotShadowMap.java) | 45 | 已读：与光锥一致的透视阴影矩阵 |
| [SpotShadowSlotPlan.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/SpotShadowSlotPlan.java) | 25 | 已读：聚光灯槽位与缓存计划 |
| [StylizedSkyRenderer.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/StylizedSkyRenderer.java) | 53 | 已读：渐变天空、太阳和光晕绘制 |
| [StylizedSkySettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/StylizedSkySettings.java) | 93 | 已读：天空参数和预设 |
| [TaaHistory.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/TaaHistory.java) | 214 | 已读：颜色/深度双缓冲，成功帧提交 |
| [TaaResolveInputs.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/TaaResolveInputs.java) | 60 | 已读：TAA 输入完整性验证 |
| [TaaSettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/TaaSettings.java) | 56 | 已读：参数真实被 resolve 使用；高层入口固定默认，见 L2 |
| [TemporalDrawBinding.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/TemporalDrawBinding.java) | 47 | 已读：历史 deformation 协议；默认空 pre-commit 合理 |
| [TemporalFrameState.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/TemporalFrameState.java) | 129 | 已读：成功帧相机历史与 camera-cut 判断 |
| [TemporalJitter.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/TemporalJitter.java) | 70 | 已读：真实投影抖动和 UV 偏移 |
| [TemporalSceneState.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/TemporalSceneState.java) | 160 | 已读：按 renderer 身份保存模型和 deformation 历史 |
| [Transform.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/Transform.java) | 95 | 已读：TRS 矩阵和修订 epoch |
| [vfx/GpuParticleExperiment.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/vfx/GpuParticleExperiment.java) | 165 | 已读：真实 compute+SSBO+绘制；固定实验效果，见 L3 |
| [vfx/VfxRenderer.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/vfx/VfxRenderer.java) | 505 | 已读：粒子/ribbon/decal/mesh 与软交界、体积雾；逐 primitive draw，见 L4 |
| [vfx/VfxTextureCache.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/vfx/VfxTextureCache.java) | 127 | 已读：同步纹理解码和缓存，资源随 renderer 释放 |
| [VisualSettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/VisualSettings.java) | 97 | 已读：全局视觉配置值对象 |
| [VolumetricFogDiagnostics.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/VolumetricFogDiagnostics.java) | 22 | 已读：真实已完成帧元数据和可选 GPU 计数 |
| [VolumetricFogSettings.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/VolumetricFogSettings.java) | 53 | 已读：质量、介质、历史、风和噪声参数 |
| [VolumetricFogView.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/VolumetricFogView.java) | 63 | 已读：帧限定雾绑定与 reactive replay 契约 |
| [VolumetricFrameState.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/VolumetricFrameState.java) | 59 | 已读：时间/抖动/相机成功帧事务 |
| [VolumetricGrid.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/VolumetricGrid.java) | 126 | 已读：透视/正交 froxel 坐标和射线长度 |
| [VolumetricHistoryPlan.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/VolumetricHistoryPlan.java) | 237 | 已读：介质/灯/阴影变化脏域与历史拒绝 |
| [VolumetricPassBuilder.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/VolumetricPassBuilder.java) | 635 | 已读：实际介质/光源/历史/积分/合成/透明雾与 reactive pass |
| [VolumetricResources.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/VolumetricResources.java) | 235 | 已读：3D 纹理、参数缓冲、预算及候选 resize |
| [WorldBounds.java](../../src/main/java/com/kaleblangley/haikalat/subsystems/render3d/WorldBounds.java) | 23 | 已读：可复用世界 AABB 槽 |
