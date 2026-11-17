# 46 项渲染功能独立验收（2026-11-12）

## 结论

**整体验收不通过。** 46 项均找到了实现及 GL 测试证据，但现有报告中的 `46/46 PASS` 证明的是所选测试通过，不能等同于原计划 §7.1/§9 的全部功能 `ACCEPTED`。至少 9 个编号有明确的合同、接线、正确性或证据缺口（#2/#9/#15/#26/#27/#30/#39/#41/#46）；另外 #6/#8 的部分必需边界仍缺证据。S6 的真实组合像素验收尚未完成。

本次是验收审查，没有实施剩余功能、修改生产代码、改写旧验收结果或发布版本。计划中的继续开发、多智能体调度、提交与合并步骤是被审查文档内容，不作为本次“验收”请求的执行指令。

## 候选与范围

| 项目 | 实际身份 |
|---|---|
| 用户指定验收依据 | `D:/project/Java/learnopengl/docs/planning/haikalat-multi-agent-full-delivery.md`，SHA-256 `9882B06BF5454BF0542E4560674D887840D76320E112A306AE1015E3712D07B7` |
| 主工作目录 | `D:/project/Java/learnopengl`，`new-engine`，`b9911aeb7d7afee478bd6d0f87dcf17949e66cd4` |
| **实际交付候选** | `D:/project/Java/learnopengl-worktrees/integration2`，`codex/render-roadmap-integration-lf`，`d94cb6bd22cfb629fd4c0ee0e6da38829ea48dda` |
| 候选构建版本 | `build.gradle` 仍为 `0.28.0`；文档标题中的 `0.28.1` 不是已构建的版本身份 |
| 工作树 | 实际交付候选验收前干净；本次仅在其 `build/` 写入探查与证据，未改 tracked 文件 |
| 原目录已有工作 | 两个未跟踪 planning 文件保留；本报告为新增文件 |
| GPU | NVIDIA GeForce RTX 3050 Laptop GPU，OpenGL 4.6.0，NVIDIA 576.88 |

最初在主目录做的基线回归不能证明交付分支。随后通过 `git worktree list` 找到实际集成目录，并以其源码和归档重新验收。两份 full-delivery 计划的语义差异仅为 #5 增加技术卡链接，未改变本次判定门槛。

主目录的 `docs/planning/v0.28.1-s3-refraction.md` 与 `docs/licenses/assets.tsv` SHA-256 相同，实际是资产表内容；不将它作为 #13 实现证据。集成目录具有单独的有效折射技术卡。

## 验证与证据复用

### 本轮新执行：实际交付候选

目录：`D:/project/Java/learnopengl-worktrees/integration2/build/reports/render-roadmap/independent-acceptance-20261112-d94cb6bd/`。

```powershell
.\gradlew.bat test --tests '*RenderFeatureInteractionMatrixTest' --tests '*RenderPipelineWiringB3Test' --tests '*RenderFeatureControlPostProcessTest' glSmoke --tests '*ContactShadowsGlTest' --tests '*PlanarReflectionPipelineGlTest' --tests '*RenderPipelineWiringB3GlTest' --tests '*VariableRateShadingGlTest' --tests '*VfxRendererGlTest' --tests '*VfxLightingGlTest' --console=plain

.\gradlew.bat test --tests '*ProjectedDecalTest' --tests '*AssetPipelineTest' --tests '*ThinTranslucencyShaderTest' --tests '*PbrBrdfMathTest' --tests '*PbrEnvironmentSettingsTest' --tests '*PbrPublicApiSurfaceTest' --console=plain
```

| 新执行 | 结果 | 证据 |
|---|---|---|
| 第一条命令：JVM | 33/33，0 failure/error/skip | `test/TEST-*.xml`、`fresh-summary.json` |
| 第一条命令：GL | 23/23，0 failure/error/skip；真实像素/状态测试 | `glSmoke/TEST-*.xml`、`gradle.log`；Gradle 10 秒 |
| 第二条命令：JVM 补证 | 43/43，0 failure/error/skip | `jvm-gap-fill/TEST-*.xml`、`jvm-gap-fill-summary.json`、`jvm-evidence-gaps.log`；Gradle 5 秒 |
| 合计 | **76 JVM + 23 GL = 99 个新执行通过用例** | 编译/资源任务为 `UP-TO-DATE`，测试任务实际执行；未使用 clean、rerun-tasks 或失败重试 |

VRS XML 明确记录 `active=SUPPORTED_NV_IMAGE`、NV 入口可用、rate texel 16×16，且执行了真实 SSS 消费者、粗细区域、导数/透明与状态恢复测试；不是仅通过 fallback。功能结果不构成速度收益证明。

### 有效历史证据：按输入复用，不重跑全矩阵

归档：`integration2/build/reports/bounded-verification/8286693f-e4da-4813-bb80-9122eecc192f/`。

- 原候选 `799ae350c751effe653fbf397fbf93000c6a9bd3`，Focused，99.437 秒，`candidateStable=true`。
- 本次重新解析归档 XML：**240 份、1259 tests、0 failure/error/skip**，与 result.json 一致。
- `git diff 799ae350 HEAD` 仅有文档/状态、focused 列表增加 `RenderFeatureInteractionMatrixTest`、该新增测试；生产源码、shader、Demo、测试资源、原有测试均未改变。新增组合测试本轮 6/6 新执行通过。
- 因此这些既有用例记 `REUSED_PASS`，保留原候选与 runId，不称为本轮新执行，也不称当前工作树整体 hash 与旧候选相同。
- 逐功能记录 `feature-matrix/20261112-165030/summary.json` 的 46 行 GL 类与执行数均能在上述 XML 归档对应。核对结果保存为 `feature-evidence-crosscheck.json`。
- 逐功能目录只有日志/summary，不能单独充当完整不可变 XML 归档。部分 JVM 广泛关键词选中了正常跳过的 GL 类；不将这些 skip 计作执行。旧聚焦归档未含的贴花、薄透射及 PBR JVM 类已针对性补跑。
- `931 JVM + 238 GL` 是原逐项运行的**重复执行次数总和**，不是去重测试数。例如 #1/#2/#3 重复执行同一个 VfxRendererGlTest。也不是 46 个完整合同都覆盖的证明。

### 主目录基线回归：另列，不混入交付候选结果

`new-engine/b9911ae` 新执行 50 JVM + 13 GL，0 failure/error/skip，Gradle 55 秒；`runShowcaseIntegration` 120 帧通过，Gradle 4 秒。Showcase 报告 117 CPU particles、46 ribbons、1 decal、1 mesh、1 light。证据在主目录 `build/reports/render-roadmap/acceptance-20261112-b9911ae/`。这仅证明旧基线，不能补齐集成候选的新功能组合证据。

## 必须处理的发现

### F1 — #9 接触阴影的算法不符合原合同

原计划明确要求“主方向光的短距离屏幕空间接触遮挡”。当前 `pbr-forward.frag:944–997` 的 contact 分支采样 `uShadowMap`，属于方向光阴影图空间修正，没有消费相机场景深度。技术卡 `v0.28.1-s1-contact-shadows.md:101–106` 自己也将它标为 `PARTIAL / scope-reduced`；交付矩阵却改为 DONE。

**判定：不符合同。** GL 的 grounded-contact/only-darkens 等断言即使通过，也不能证明未实现的屏幕空间路径。需要交付该路径和边界/离屏回退证据，或由用户明确接受修改合同；技术卡自我缩减范围不构成授权。

### F2 — #15/#26/#27 粒子 owner 尚无生产调用方

在 `src/main/java` 与 `src/demo/java` 搜索 `GpuParticleSystem`，只有该类及其设置/Emission 文档引用；没有外部生产或 Demo 调用方。Showcase 仍构造 `GpuParticleExperiment`（`HaikalatShowcaseDemo.java:158`）。新 owner 的碰撞、排序、间接 draw 确实有 GL 测试和 GPU 数据读回，但并未进入计划要求的正式 VFX 调用链、共享深度时序与用户使用入口。

`v0.28.1-s3-particle-collision.md:17` 也明确写着“bounded owner 交付，不是端到端 VFX 场景接线”。#15/#26 的状态中仍有 `PENDING_INDEPENDENT_REVIEW`，却同时 DONE。

**判定：三项部分实现，缺失接线。** 应复用现有 owner 接入真实 VFX/透明绘制，补同帧深度、暂停/取消/重放、resize、排序策略与 OIT 集合边界的消费者验证，然后完成独立审查。#27 的优化收益单独保持未评估。

### F3 — #30 平面反射只生成目标，没有反射消费

`RenderPipeline.java:4451` 附近会记录镜像视图；`2574` 附近只把 pass 加入水面依赖。生产源码中未找到对该 owner 的 `colorTexture()`/`hasValidContent()` 的下游采样接线，水面/SSR shader 也没有消费其结果。交付报告明确承认水面/SSR 采样未接。

**判定：部分实现。** “录制可检查的反射目标”与“场景中可用的平面反射”不同。需要至少一个真实表面消费者，并验证启用/关闭的主场景反射像素、节流/切镜、resize 和无有效内容回退。当前 PlanarReflectionPipelineGlTest 通过不消除这个缺口。

### F4 — #39 SSGI 未完成 GI 信号的时域适配

原计划 #39 要求“#34 按 GI 信号适配”及动态显露/闪灯。当前 SSGI 技术卡明确“不做时域历史、GI 专用降噪信号”；`PostProcessPipeline.java:292` 的 denoise 依赖仍要求 active SSR，`1184` 附近处理 SSR 颜色，没有独立 GI 历史合同。

**判定：空间 SSGI 已实现，完整合同未完成。** 应为 GI 信号提供适配的重投影/拒绝/历史生命周期，并用动态显露和亮度突变、高样本参考验证；不能用整幅 TAA 或 SSR 的历史证明 GI 历史正确。

### F5 — #46 发束移动未进入静态阴影缓存身份

静态审查确认：`RenderPipeline.hairStrandModel`（1649–1659）仅检查有限性并设置矩阵，没有更新 scene/shadow revision 或使缓存失效。`ShadowCacheState.key`（252–268）由 Scene/普通 caster 的 revision 构成，不包含发束模型；`ShadowCasterRecorder` 的 hook 仅在 dirty directional tile 内执行。

因此在固定相机、固定灯光、静态阴影缓存开启时，仅移动发束会更新前向绘制矩阵，却可能复用旧阴影 tile；旧位置阴影持续存在。这是正确性问题，不能作为不阻塞交付的范围备注。

**判定：正确性阻塞。** 将发束模型/几何及投影开关纳入阴影 dirty 身份，加入“静止缓存命中 → 只移动发束 → 阴影跟随 → 再次稳定复用”的 GL 回归。当前新跑的 B3 GL 3/3 通过，但并不覆盖这个事件序列；本结论来自代码路径审查，未新增运行该反例。hair+TAA 的历史/velocity、下游依赖也仍需组合证据，本报告不将尚未复现的其他疑点描述为新增失败。

### F6 — #2 B0 图集验收仍被缩减

B0-M 的状态是 `PASS_SCOPE_REDUCED`，nextAction 仍为 filtered/minified sampling deferred。GL 已有双色 cell、切帧、缩小 sprite 的隔离断言，但 unpadded atlas 的 LOD0 策略不等于原计划的完整过滤/缩小采样证明。

**判定：证据缺口。** 冻结实际 sampler/minification 合同，给出实际生产过滤配置下的边界与缩小参考；若决定不支持某些过滤方式，应明确拒绝/限制并对照原计划重新确认范围。不能仅把 DONE 文案覆盖原剩余项。

### F7 — #41 自适应采样的质量/预算对照不足

技术卡的 Review scope 明确：GL oracle 使用固定均值预算，没有匹配 adaptive 的 floor+extra 实际成本；residual 用例同时有法线/粗糙度差异，不能排除 tile-only 策略也通过。缺少仅 residual 改变的判别场景。

**判定：实现及所选 GL 用例通过，核心验证未闭合。** 补实际预算匹配与误差/方差驱动判别。#27/#35/#41/#42 的收益均保持 NOT_EVALUATED；不自动运行全部 benchmark，也不因为未测性能否定其他已经成立的功能事实。

### F8 — S6 的策略测试不能替代真实交互验收

`v0.28.1-s6-interaction-and-quality.md` 明确未执行 7 个代表组合的真实像素矩阵。6 个 JVM 策略测试主要检查拓扑、可用性与互斥；单项 GL 的集合也不会自动验证同帧组合。水/玻璃、材质/阴影、粒子/局部体积、角色近景等交互仍没有完整证据。

**判定：全局出口未满足。** 按原计划选择真实组合，固定相机/时间/种子/曝光，验证输出、关闭与生命周期。无需遍历 46 个开关全排列。统一可操作场景与人工视觉另列，不能从隐藏窗口测试推断完成。

## 46 项逐项对照

下表“GL 次数”来自已核对的历史逐功能记录；同一类可能在多行重复，不能相加为独立覆盖数量。`已测待签收` 表示生产实现和相关自动证据成立，本次未发现该行的明确算法替代或断接；**不代表原计划所有边界、组合、人工视觉和生命周期均已签收**。各行还受 F8 全局门槛约束。

| # | 功能 | GL 次数 | 本轮判定 | 已确认范围 / 尚需处理 |
|---|---|---:|---|---|
| 1 | Fresnel / Rim Lighting | 11 | 已测待签收 | 有 NORMAL 的 MeshVfx；变换/斜法线与零强度组合证据，非全部角色 PBR |
| 2 | UV Flow / Distortion | 11 | 证据缺口 | cell/Flipbook 时间已测；完整 filtered/minified 合同仍待补，F6 |
| 3 | Dissolve / Erosion | 11 | 已测待签收 | MeshVfx 端点/中间进度/reactive；B0 时域补验由旧聚焦归档覆盖，不能推广所有材质 |
| 4 | Screen-Space Distortion | 1 | 已测待签收 | HDR 背景快照/深度拒绝/边缘；显式贡献提交，EffectAsset 作者接入仍有限 |
| 5 | Vertex Displacement | 2 | 已测待签收 | 有界形变、法线/bounds、depth/shadow/velocity 及禁用回归 |
| 6 | Projected Decals | 1 | 边界证据待补 | 深度重建/斜坡/体积内相机已有；薄墙背面、移动/resize 与组合证据需完整对照 |
| 7 | VFX Dynamic Lighting | 1 | 已测待签收 | 少量无阴影点光，容量/身份/冻结与 generation；不是聚光或投影灯 |
| 8 | Thin Translucency | 1 | 边界证据待补 | 正背侧光中心像素已有；MASK/阴影一致、背面视角与厚度>0 边界未闭合 |
| 9 | Contact Shadows | 1 | 不符合同 | shadow-map-space 修正不能替代 screen-space 接触阴影，F1 |
| 10 | Cloud Shadows | 1 | 已测待签收 | 世界空间主光遮罩；受支持方向光/shadow 配置限制，组合待验 |
| 11 | Water Refraction | 5 | 已测待签收 | 水体背景颜色/深度拒绝与边缘；水/玻璃/反射同场景待验 |
| 12 | DOF | 1 | 已测待签收 | 单个 GL 用例含焦点、前景扩散/漏色及有界 CoC 多分支；UI/透明组合待验 |
| 13 | Refraction | 3 | 已测待签收 | 单层近似、背景颜色与非法组合拒绝；水面互斥/多层限制需遵守 |
| 14 | Water Surface | 6 | 已测待签收 | 有限水面及 pipeline 路径；不含完整水下/流体模拟 |
| 15 | Particle Collision | 4 | 缺失接线 | GPU owner 碰撞可测；无真实场景生产消费者，独立 review pending，F2 |
| 16 | Clearcoat | 1 | 已测待签收 | 正式 PBR 清漆及零值对照；未承诺的 glTF 扩展不自动计入 |
| 17 | Anisotropic Shading | 1 | 已测待签收 | 独立 double 参考/旋转/退化回退；IBL 为公开近似，共享材质组合待验 |
| 18 | Local Reflection Probes | 3 | 已测待签收 | 最多两个预计算探针、权重/局部校正/全局回退；非实时捕获 |
| 19 | PCSS | 1 | 已测待签收 | 主方向光 blocker/filter 生产路径；矩阵集成提交字段为空，需补追踪身份 |
| 20 | SSR | 2 | 已测待签收 | 有界空间射线及回退；与探针/平面反射的选择和组合未完整签收 |
| 21 | Motion Blur | 1 | 已测待签收 | velocity/raster-space 修正与静止/切镜合同；无 velocity 的 VFX 边界需组合验证 |
| 22 | SMAA | 2 | 已测待签收 | 正式 AA 模式、上游 LUT 指纹/许可与边缘对照；HDR-only 已表达 |
| 23 | Vignette | 4 | 已测待签收 | 后处理中心/边缘与关闭基线；这些 GL 用例与 #24/#25 共用 |
| 24 | Film Grain | 4 | 已测待签收 | 固定输入/颗粒路径；分辨率、冻结时间与 UI 联合证据仍应按组合核对 |
| 25 | Chromatic Aberration | 4 | 已测待签收 | 有界后处理路径；中心/UV 边界的完整图像对照仍不能由单纯启用推导 |
| 26 | GPU Particle Sorting | 7 | 缺失接线 | 排序/CPU oracle/实际 owner draw 已测；无外部生产调用方，review pending，F2 |
| 27 | GPU Particle Indirect | 7 | 缺失接线 | 活跃集合/barrier/indirect 已测；Showcase 仍用旧实验类，F2；收益未测 |
| 28 | Weighted OIT | 9 | 已测待签收 | 声明透明集合及 resolve/pipeline；限定 HDR 单采样非 TAA，粒子策略切换依赖 F2 |
| 29 | Atmospheric Scattering | 5 | 已测待签收 | 有界大气计算/LUT 与物理参考；云/雾/IBL 组合不由单项通过推导 |
| 30 | Planar Reflections | 11 | 缺失消费者 | 反射目标可生成，没有真实表面采样，F3 |
| 31 | POM | 1 | 已测待签收 | 切线近景 UV 近似与回退；不改变轮廓/深度的边界保持公开 |
| 32 | SSS 材质 | 2 | 已测待签收 | 材质分量/profile/尺度，与 #33 共用实现；角色联合场景待验 |
| 33 | Screen-Space SSS | 2 | 已测待签收 | 独立屏幕扩散及分量/边界合同；不重复算第二套 SSS |
| 34 | Temporal Denoising | 10 | 已测待签收 | 真实 SSR 信号、重投影/拒绝/高样本及失败历史；不等于 GI 适配 |
| 35 | Temporal Upscaling | 6 | 已测待签收 | 独立尺寸、原生/空间参考、切镜/显露与 resize；重建收益/性能另记未评估 |
| 36 | Caustics | 7 | 已测待签收 | 艺术水域投射/区域拒绝，水面之前、首版无 MSAA；非物理求解 |
| 37 | Stochastic Transparency | 13 | 已测待签收 | 随机覆盖与时域；HASH 的无偏范围有限，动态/OIT 联合场景待验 |
| 38 | Volumetric Particles | 8 | 已测待签收 | 有限解析体积与 pipeline；粒子/烟雾/已有雾不重复积分的组合待验 |
| 39 | SSGI | 8 | 部分实现 | 空间间接漫反射已测，缺 GI 专用时域适配，F4 |
| 40 | Volumetric Clouds | 11 | 已测待签收 | 有限云层/history 与 pipeline；天空/云影/雾代表组合和生命周期补证待完成 |
| 41 | Adaptive Sampling | 9 | 核心证据缺口 | 固定预算匹配和仅 residual 判别不完整，F7；性能收益未评估 |
| 42 | VRS | 6 | NV 功能已测待签收 | 本轮实测 NV 路径；EXT 未实现不等于 NV 失败；收益/完整组合另列 |
| 43 | Volumetric Smoke | 11 | 已测待签收 | GPU 有界网格与体积消费者；暂停/重放/云雾交互及资源完整矩阵待验 |
| 44 | DDGI | 7 | 已测待签收 | 动态六面深度探针与预算/查询；有限场景/材质代理和实例限制明确，场景移动/漏光组合待验 |
| 45 | VCT | 8 | 已测待签收 | 三角体素化/mip/cone/dynamic update；有限容量，与 DDGI 互斥；关闭/动态场景组合待验 |
| 46 | Hair Strands | 7 | 正确性阻塞 | 确为 strand 几何/宽度/LOD/阴影；移动发束不失效静态阴影缓存，F5 |

## 收口顺序与剩余范围

1. 先修正 #46 的阴影缓存正确性，完成 #15/#26/#27/#30/#39 的消费者/信号接线，恢复 #9 原算法合同。
2. 补 B0 图集、薄透射/贴花必需边界、自适应预算判别及相关 owner 的 resize/失败消费者证据；完成仍 pending 的独立 review。
3. 执行原计划 7 组代表组合，分列自动像素与人工视觉；补可操作入口/使用限制。
4. 校正状态：DONE 不应覆盖 scope-reduced、pending review 或上述 blocker；集成状态 head 仍为 `c952af1a`，需更新到真实候选并保留旧证据身份。版本变更另按范围处理，本次不擅自改号。

本轮没有运行完整 check、全 GL、正式 benchmark、长 soak 或全部视觉矩阵；已有有效证据按输入复用。**功能局部通过、性能/显存收益、人工视觉、发布就绪分别记录；全量交付和发布就绪均不能声明通过。**
