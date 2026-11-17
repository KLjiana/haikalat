# 46 项渲染功能二次验收

**结论：仍不通过，不能签收为 46/46 ACCEPTED。** 本轮原有定向回归 87 个通过，但补充的两个真实 GL 反例均失败：#46 统一功能提交入口绕过发束阴影缓存失效；#9 接触阴影在关闭直接光照时仍压暗 unlit 接收面。另发现 #39 GI 历史重投影遗漏 jitter 修正的静态正确性问题。S6 真实组合已有进展，仍未覆盖原计划的代表交互。

本报告针对“修复完成，二次验收”。未实施生产修复、修改交付状态或提交代码；计划里的开发与多智能体调度步骤只是审查对象。上轮逐项范围见 [首次独立验收](2026-11-12-render-roadmap-independent-acceptance.md)。

## 候选身份

| 项目 | 本轮身份 |
|---|---|
| 验收依据 | `docs/planning/haikalat-multi-agent-full-delivery.md`，SHA-256 `9882B06BF5454BF0542E4560674D887840D76320E112A306AE1015E3712D07B7` |
| 实际交付目录 | `D:/project/Java/learnopengl-worktrees/integration2` |
| 分支 / HEAD | `codex/render-roadmap-integration-lf` / `52a39814691f0ed61c3ba6d19d6f1708b4989e75` |
| 上轮候选 | `d94cb6bd22cfb629fd4c0ee0e6da38829ea48dda` |
| 构建版本 | `0.28.0`；计划标题不代替构建身份 |
| GPU | NVIDIA GeForce RTX 3050 Laptop GPU，OpenGL 4.6，NVIDIA 576.88 |
| 时间 | 证据目录按宿主 UTC+08 日期命名为 2026-11-13；对应 UTC / America/Chicago 日期为 2026-11-12 |
| 工作树 | 交付目录 tracked 文件验收前后干净；探查源码、日志、XML 仅写入其 `build/` |
| 主目录 | `D:/project/Java/learnopengl` 仍为 `new-engine/b9911aeb`；本轮只新增此报告，保留原有未跟踪文件及暂存状态 |

## 上轮问题关闭情况

“关闭”仅指对应发现，不代表关联功能的所有组合和发布门槛均通过。

| 原发现 | 二次判定 | 依据 / 剩余范围 |
|---|---|---|
| F1：#9 不是屏幕空间算法 | 原算法缺口已修，正确性仍阻塞 | 新 pass 确实 ray march 场景深度；但乘暗整个场景颜色，见 R1 |
| F2：#15/#26/#27 没有生产调用方 | 接线缺口已修，消费者验收部分完成 | 新增 `GpuParticleVfx`、`VfxRenderer.recordGpuParticles` 与 Showcase 调用；13 个 JVM 合同和 3 个 GL 消费者测试通过。碰撞启用的消费者读回及完整透明策略交互仍缺证据；深度遮挡不能替代碰撞响应 |
| F3：#30 没有真实表面消费者 | 对水面消费者关闭 | `waterExecutor` 传入有效平面反射纹理，水面 shader 采样；5 个 pipeline GL 用例覆盖像素贡献、关闭、resize、节流保留和无有效内容回退。未据此宣称 SSR 也消费该纹理 |
| F4：#39 没有 GI 专用时域适配 | 主要实现补齐，仍未关闭 | 新增 GI staging、双缓冲历史、拒绝/提交/失败/resize/cut；7 个 GL 用例通过。TAA 下的 jitter 坐标转换遗漏，见 R3 |
| F5：#46 移动发束复用旧阴影 | 部分修复，仍阻塞 | 模型移动与直接 `applyHairStrands` 路径已失效缓存；`features().submit(...)` 遗漏，见 R2 |
| F6：#2 图集过滤/缩小证据 | 按已冻结 sampler 合同关闭 | 5 个新 GL 用例核对实际参数、线性放大、独立 mip 平均、生产 sprite 缩小、flipbook LOD0 隔离。LOD0 不提供 mip 抗锯齿；非 flipbook 子区域粗 mip 仍可能跨区域，不扩展为任意过滤方式保证 |
| F7：#41 预算对照和 residual 判别 | 本项证据缺口关闭 | 10 个 GL 用例通过；新增 floor+extra 成本核算、加权双档及近似等预算固定控制、仅 residual 改变的判别。加权参考属于数值对照，不是实际单帧耗时或性能收益证明 |
| F8：S6 缺真实组合像素 | 部分修复 | 7 个真实 GL 组合用例通过；仍为子组合，见下表，不能将原计划全部组合标为已验 |
| #6 / #8 边界缺口 | 未关闭 | 未找到薄墙/移动/resize 完整贴花补证，及薄透射 MASK/阴影、背面视角等补证；状态文件仍明确保留这些 scope notes |

## 本轮阻塞发现

### R1 / P1 — #9 接触阴影压暗直接光照以外的颜色（真实 GL 失败）

位置（相对交付目录）：`src/main/java/com/kaleblangley/haikalat/subsystems/render3d/shadow/ScreenSpaceContactShadowPass.java:209–233`、`PipelineTopology.java:612–620`。

Pass 先复制最终场景颜色，再用 `GL_ZERO, GL_ONE_MINUS_SRC_COLOR` 乘暗整个目标。它没有隔离方向光直射分量；拓扑启用条件也不检查 directLighting。因而 unlit、发光、间接光以及已经被已有阴影衰减的颜色都可能被再次压暗，未满足 #9 与既有阴影组合不重复过暗的合同。

反例沿用 `ScreenSpaceContactShadowsGlTest` 的固定场景和读回，只在统一功能提交里添加 `.directLighting(false)`，将恒定颜色 unlit 地面预期改为不受方向光接触阴影影响。结果：

```text
ContactDirectLightingOffAcceptanceGlTest.java:183
expected luminance: 0.17152537
actual luminance:   0.046492055
```

关闭直接光照时仍产生明显变暗。本次失败直接证明该事件；发光/IBL 受影响来自同一合成公式的静态分析，不冒称分别运行了独立像素用例。修复应约束接触可见性作用的光照分量，并补 direct-off、unlit/发光/IBL 与已有阴影重叠对照。

### R2 / P1 — #46 统一提交绕过发束阴影缓存失效（真实 GL 失败）

位置：`src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderPipeline.java:3831–3874`。对照同文件 `applyHairStrandsInternal:1533–1559` 和 `hairStrandModel:1662–1676`。

直接 apply 和模型移动现在会使缓存失效，但统一入口 `applyPendingFeatureRequest` 直接更新字段、构建并激活候选，没有在发束 caster 变化时同步失效。候选会复用静态阴影缓存，导致启用发束的第一帧仍使用没有发束的旧 tile。

反例复制已有 `RenderPipelineWiringB3GlTest` 的固定相机/灯光/接收面事件序列，仅将启用、关闭发束改成 `pipeline.features().submit(...hairStrands(...).build())`。按统一入口的帧边界语义执行下一帧后检查：

```text
HairFeatureSubmissionAcceptanceGlTest.java:275
the first hair shadow render must be a cache miss
expected: 1
actual:   0
```

失败发生在首次启用，后续移动/关闭断言未执行，不能将它们计为通过。应在共享发布路径处理 caster 变化，覆盖统一提交的启用、关闭、几何设置变化及再次稳定复用。

### R3 / P1 — #39 SSGI 历史重投影遗漏 TAA jitter（静态确认，未新增 GL 反例）

位置：`src/main/resources/shaders/postprocess/ssgi-temporal.frag:127`；输入接线为 `PostProcessPipeline.java:1114–1128`、`ssgi/SsgiPass.java:674–688`。

共享 surface 的 velocity 来自 stable VP（`scene-surface.vert:211–212`、`scene-surface.frag:78`），不包含 jitter；GI 当前图与历史图来自 jittered raster。当前 shader 却只使用 `previousUv = uv + velocity`，输入也未传递当前和上一帧 jitter。正确的 raster 历史映射需要 `uv + velocity - currentJitter + previousJitter`；同仓库 `temporal-denoise.frag:134–137` 已遵守这一约定。

静态相机/物体的 stable velocity 为零，但 TAA 两帧 jitter 不同时，当前代码仍采样相同 UV，发生历史错位，可能造成薄结构串色或额外拒绝。SSGI 允许与 TAA 共存，其 cut 检测还刻意忽略 jitter；因此不能靠每帧失效规避。现有 `SsgiTemporalGlTest` pipeline fixture 使用 NONE，7/7 通过并不覆盖此分支。本项是坐标合同审查结论，**不计作第三个已执行失败测试**。应补 jitter 输入和静止薄结构的 TAA+SSGI 回归。

## S6 组合覆盖差额

| 原计划代表组合 | 本轮已看到的真实组合 | 仍欠缺的关键交互 |
|---|---|---|
| 战斗 | MeshVfx 基础效果、灯、AA 等 | #4 屏幕扭曲、#6 投影贴花未进入该组合 |
| 材质与阴影 | 薄透射、清漆、各向异性、POM、接触阴影、云影 | #5 位移、#19 PCSS 及其共享 shadow/velocity 交互 |
| 水域与玻璃 | 水面、平面反射、焦散；另验证 SSR 相关拒绝 | 水体折射、玻璃、局部探针的受支持组合；拒绝不支持组合不等于其余受支持组合已测 |
| 粒子与局部体积 | 解析体积粒子、云、OIT、真空雾 | 新 GPU 粒子碰撞/排序/间接 draw、随机透明、烟雾及非零雾介质交互 |
| 镜头与重建 | TAA、运动模糊、DOF；另选 SMAA | 时域放大/降噪、颗粒/色差/暗角和 UI 联合合同 |
| 天空与 GI | 大气、SSGI、云；DDGI/VCT 互斥拒绝 | DDGI/VCT 各自启用的动态 GI 代表场景，不能用拒绝测试替代 |
| 角色近景 | 发束、薄透射、SSS、各向异性 | 该子组合已有证据；仍受 R2 统一控制路径影响，不能推导所有切镜/透明/生命周期均完成 |

此外，`S6InteractionMatrixGlTest.java:625–630` 用 `A 在 B 前 || B 在 A 前` 检查两个已存在的不同 pass，逻辑恒真，只能说明两者存在，不能约束透明合成次序。应按实际受支持顺序与像素贡献给出有判别力的断言。无需全排列 46 个开关，但不能把未经用户确认的范围缩减视为完整 S6 签收。

## 46 项状态对照

“保留已测”沿用首次报告的范围限制，并复用本轮核验的历史证据；不是完整 ACCEPTED。全表均受 S6、人工视觉与发布门槛约束。

| # | 功能 | 二次状态 |
|---|---|---|
| 1 | Fresnel / Rim Lighting | 保留已测 |
| 2 | UV Flow / Distortion | sampler 补证关闭，支持范围见 F6 |
| 3 | Dissolve / Erosion | 保留已测 |
| 4 | Screen-Space Distortion | 保留已测；组合待补 |
| 5 | Vertex Displacement | 保留已测；组合待补 |
| 6 | Projected Decals | 边界证据未闭合 |
| 7 | VFX Dynamic Lighting | 保留已测 |
| 8 | Thin Translucency | 边界证据未闭合 |
| 9 | Contact Shadows | 阻塞：R1 |
| 10 | Cloud Shadows | 保留已测 |
| 11 | Water Refraction | 保留已测；水/玻璃组合待补 |
| 12 | DOF | 保留已测 |
| 13 | Refraction | 保留已测；水/玻璃组合待补 |
| 14 | Water Surface | 保留已测；新增平面反射消费通过 |
| 15 | Particle Collision | 已接生产入口；碰撞启用的消费者证据待补 |
| 16 | Clearcoat | 保留已测 |
| 17 | Anisotropic Shading | 保留已测 |
| 18 | Local Reflection Probes | 保留已测；组合待补 |
| 19 | PCSS | 保留已测；组合待补 |
| 20 | SSR | 保留已测 |
| 21 | Motion Blur | 保留已测 |
| 22 | SMAA | 保留已测 |
| 23 | Vignette | 保留已测；组合待补 |
| 24 | Film Grain | 保留已测；组合待补 |
| 25 | Chromatic Aberration | 保留已测；组合待补 |
| 26 | GPU Particle Sorting | 生产入口缺口关闭；透明策略交互待补 |
| 27 | GPU Particle Indirect | 生产入口缺口关闭；收益未评估 |
| 28 | Weighted OIT | 保留已测；完整粒子策略组合待补 |
| 29 | Atmospheric Scattering | 保留已测 |
| 30 | Planar Reflections | 水面消费者缺口关闭 |
| 31 | POM | 保留已测 |
| 32 | SSS 材质 | 保留已测 |
| 33 | Screen-Space SSS | 保留已测 |
| 34 | Temporal Denoising | 保留已测；不可替代 #39 的 jitter 验证 |
| 35 | Temporal Upscaling | 保留已测；收益未评估 |
| 36 | Caustics | 保留已测 |
| 37 | Stochastic Transparency | 保留已测；组合待补 |
| 38 | Volumetric Particles | 保留已测；完整体积交互待补 |
| 39 | SSGI | GI temporal 已实现；R3 未关闭 |
| 40 | Volumetric Clouds | 保留已测 |
| 41 | Adaptive Sampling | 预算/residual 补证关闭；性能未评估 |
| 42 | VRS | 保留 NV 功能证据；性能未评估 |
| 43 | Volumetric Smoke | 保留已测；完整体积交互待补 |
| 44 | DDGI | 保留已测；动态 GI 组合待补 |
| 45 | VCT | 保留已测；动态 GI 组合待补 |
| 46 | Hair Strands | 阻塞：R2 |

## 验证记录与复现

本轮归档根目录：

`D:/project/Java/learnopengl-worktrees/integration2/build/reports/render-roadmap/second-acceptance-20261113-52a39814/`

| 证据 | XML / tests | 结果与性质 |
|---|---|---|
| `test/` | 7 / 44 | 本轮新执行，0 failure/error/skip |
| `glSmoke/` | 8 / 43 | 本轮新执行，0 failure/error/skip |
| `probes-junit/` | 2 / 2 | 本轮新执行，2 failures、0 error/skip |
| 历史 Focused 归档 | 250 / 1331 | REUSED_PASS，0 failure/error/skip；不与上面相加为去重覆盖数 |

独立计数保存在 `independent-counts.json`。本轮合计 **89 个新执行：87 通过、2 失败**。`targeted.log` 为 14 秒；`probes.log` 为 5 秒。编译/资源任务部分 UP-TO-DATE，测试任务实际执行。

原有定向回归，在交付目录运行：

```powershell
.\gradlew.bat test --tests '*ScreenSpaceContactShadowPipelineWiringTest' --tests '*GpuParticleVfxContractTest' --tests '*SsgiTemporalReferenceTest' --tests '*SsgiPipelineCompositionTest' --tests '*ShadowCacheStateTest' --tests '*VfxSamplerReferenceTest' --tests '*AdaptiveSamplingReferenceTest' glSmoke --tests '*ScreenSpaceContactShadowsGlTest' --tests '*GpuParticleVfxGlTest' --tests '*PlanarReflectionPipelineGlTest' --tests '*SsgiTemporalGlTest' --tests '*RenderPipelineWiringB3GlTest' --tests '*VfxSamplerContractGlTest' --tests '*SsrAdaptiveSamplingGlTest' --tests '*S6InteractionMatrixGlTest' --console=plain
```

补充反例源码与生成脚本位于 `probe-src/`、`create-probes.ps1`；临时 init script 仅将该目录加入测试 source set，未改仓库测试源码或生产代码。复现命令：

```powershell
.\gradlew.bat -I build/reports/render-roadmap/second-acceptance-20261113-52a39814/probes.init.gradle glSmoke --tests '*HairFeatureSubmissionAcceptanceGlTest.movingOnlyTheHairLockInvalidatesTheStaticShadowCacheAndTheShadowFollows' --tests '*ContactDirectLightingOffAcceptanceGlTest.screenSpaceContactShadowDarkensTheContactAndFallsBackOffScreen' --console=plain
```

首次临时 harness 用了相对 source 路径，造成空选择。该次为 **INVALID（未执行测试）**，保留 `probes-empty-selection.log`；改为绝对路径后，两个用例各实际执行一次并失败。没有重试产品失败或放宽断言。后续普通 Gradle 调用不带 `-I` 时不会引入这些临时测试源。

历史归档：`build/reports/bounded-verification/398d45bf-3c41-4054-824e-669e535597ea/`，原候选 `ecedac8abc510eb547f1a40b130b63e4a07a5ffb`，104.411 秒、candidateStable=true、releaseReady=false。本轮重新解析 250 份 XML，与 result.json 一致。`git diff ecedac8a HEAD --name-only` 只有 `docs/planning/haikalat-multi-agent-status.json`，生产代码、shader、测试与构建输入未变，允许复用其已有用例；不将复用描述为当前新运行。逐功能矩阵的 `962 JVM + 243 GL` 是含重复用例的运行次数，也不是 46 项完整合同通过数。

## 后续收口条件

1. 修复 R1/R2，并将两个反例转为正式回归；修复 R3，补 TAA+SSGI 坐标合同用例。
2. 补 #6/#8 必需边界、#15 碰撞启用的真实消费者，以及 S6 代表交互差额；无需重跑全部历史矩阵。
3. 校正交付状态和技术卡。当前状态仍用 DONE 承载未完成 scope notes；例如平面反射技术卡开头仍写未接生产/水面，与本次已确认的实现矛盾。应区分旧阶段记录和当前能力，保留原候选身份。
4. 人工视觉、正式性能、长 soak、完整发布矩阵本轮未执行。功能局部通过、性能收益与发布就绪分别报告；本轮不批准发布。
