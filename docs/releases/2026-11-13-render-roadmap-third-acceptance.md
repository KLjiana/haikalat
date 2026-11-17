# 46 项渲染功能第三次验收

**结论：仍不通过，不能签收为 46/46 ACCEPTED。** 候选 `e3bb38d0` 修复了上轮 direct-light-off、发束启用和 SSGI jitter 的具体问题，但 #9 的光照分量隔离、#46 的几何变更缓存身份仍不完整。本轮两个补充真实 GL 反例失败；S6 和粒子碰撞消费者的剩余范围没有新交付。

本轮新执行 **77 个用例：75 通过、2 失败，0 error/skip**。另核验可复用的 Focused 归档 **1,342 个通过用例**，不与新执行相加为独立覆盖数。生产代码和仓库测试源码均未修改；仅新增本报告，临时反例与证据写入交付目录的 `build/`。

## 候选与范围

| 项目 | 身份 |
|---|---|
| 验收依据 | 用户指定的 `docs/planning/haikalat-multi-agent-full-delivery.md`；SHA-256 `9882B06BF5454BF0542E4560674D887840D76320E112A306AE1015E3712D07B7` |
| 实际交付目录 | `D:/project/Java/learnopengl-worktrees/integration2` |
| 分支 / HEAD | `codex/render-roadmap-integration-lf` / `e3bb38d0f7ade59886f12bfe91780307e34ae799` |
| 二次候选 | `52a39814691f0ed61c3ba6d19d6f1708b4989e75` |
| 差异 | 19 个文件；生产修复集中在 RenderPipeline、SSGI Java/shader，另有边界测试、指纹、调试策略和文档 |
| 构建版本 | `0.28.0` |
| GPU | NVIDIA GeForce RTX 3050 Laptop GPU / OpenGL 4.6 / NVIDIA 576.88 |
| 日期 | 宿主证据日期为 2026-11-13（UTC+08）；开始核验时间为 2026-11-13 02:43 UTC，即 America/Chicago 2026-11-12 晚间 |
| 工作树 | 交付候选 tracked/untracked 状态验收前后干净（忽略 build 产物）；主目录保留五个原有未跟踪文件，仅新增此报告 |

本轮是验收，不执行被审查计划中的开发、多智能体调度、提交或发布步骤。范围沿用 [首次验收](2026-11-12-render-roadmap-independent-acceptance.md) 与 [二次验收](2026-11-13-render-roadmap-second-acceptance.md)，优先复验未关闭事项及本轮直接消费者。

## 上轮问题复核

| 问题 | 三次判定 | 证据及范围 |
|---|---|---|
| R1 / #9：direct-off 时压暗 unlit | 该场景修复，但根因未关闭 | 新增 directLighting gate；正式 GL 的关闭直接光照对照通过。direct-on 时 unlit 仍被压暗，见 T1 |
| R2 / #46：统一提交启用不刷新阴影 | 启用/关闭修复，几何变更仍失败 | 新增布尔参与状态比较；正式启用测试和上轮完整启用→移动→关闭反例通过。保持投影开启时改长度仍复用旧 tile，见 T2 |
| R3 / #39：GI 历史遗漏 jitter | 对该发现关闭 | Java 传入当前 jitter，按成功帧保存上一帧 jitter；shader 使用 `uv + velocity - currentJitter + previousJitter`。9 个 GL 用例通过，含两个新增 jitter 用例和独立错误公式对照 |
| #6：薄墙、移动、resize 边界 | 上轮列出的接收面边界补证关闭 | 4 个 GL 用例通过：背面拒绝/接受、移动前后真实深度和颜色、resize 后世界位置、透明覆盖层合成。人工构造透明层不等于真实宿主全局透明队列已验；S6 仍待补 |
| #8：MASK、背面、厚度及阴影 | 大部分边界补证通过，组合限制保留 | 4 个 GL 用例通过：alpha cutoff、背面视角、侧光零贡献、厚度吸收和波瓣收紧、cascade 可见性一次衰减。MASK 颜色与非 MASK 阴影接收分别验证，不能推导 masked 薄叶 caster 的颜色/阴影一致性已覆盖；self-shadow 限制仍在 |
| #15/#26/#27 消费者及透明策略 | 与二次验收相同 | 本轮无相关实现/测试变更；碰撞启用的生产消费者、真实透明集合策略联合像素仍缺证据 |
| S6 代表组合 | 仍部分完成 | 源码/测试未变，本轮 7 个现有子组合通过；二次报告的覆盖差额及恒真顺序断言未修复 |

SSGI 新用例使用固定交替的 ±0.5 像素 jitter 和零 velocity：与缺失 jitter、反号 jitter 的独立预测差异可辨，覆盖显露拒绝、切镜、收敛和零 blend。它直接补上了原发现的反例；本轮不把尚未验证的多相位 Halton、运动+jitter 组合升级为新增产品失败，也不宣称完整 TAA 场景验收。

## T1 / P1：#9 仍乘暗整个场景颜色

代码位置（交付目录）：

- `src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderPipeline.java:3837`：新增的 `effectiveContactShadowSettings` 只判断全局 directLighting/master 开关。
- `src/main/java/com/kaleblangley/haikalat/subsystems/render3d/shadow/ScreenSpaceContactShadowPass.java:209`、`:233`：仍复制全场景颜色并执行 `dst *= (1 - occlusion)`。
- `src/test/java/com/kaleblangley/haikalat/integration/ScreenSpaceContactShadowsGlTest.java:86`：地面 fragment shader 恒定输出 `(0.12, 0.18, 0.24, 1)`，不消费任何光照。

修复只使 direct-off 时整个 pass 消失。direct-on 是正常受支持配置，此时场景里的 unlit 接收面仍不应受到方向光接触阴影影响。新反例直接复制正式 fixture，保留 direct-on 和全部场景输入，只将该恒定地面的“应变暗”断言改为“应保持不变”：

```text
ContactUnlitDirectOnAcceptanceGlTest.java:183
expected luminance: 0.17152537
actual luminance:   0.046492055
```

该测试失败。原正式测试仍把 unlit 地面变暗当作成功条件，因此其 PASS 无法证明光照分量正确。应让接触可见性作用于方向光直射贡献，保留 unlit/发光/IBL，并验证与已有阴影重叠时不重复过暗。这里实际复现的是 unlit；发光/IBL 风险来自相同全色乘法的代码路径，不冒称各自已运行反例。

## T2 / P1：#46 投影保持开启时，几何参数变化不刷新阴影

位置：`src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderPipeline.java:3843`、`:3876`。

新失效条件仅比较 `hair.active() && hair.castsShadow()` 的布尔值。若前后均为 true，改变长度、几何种子等阴影形状输入不会触发失效。直接 `applyHairStrandsInternal` 则在旧/新配置任一投影时失效，因此两条公共入口产生不同可见结果。

反例沿用已有固定相机、灯光、静态阴影接收面 fixture：

1. 统一提交长度 2.0 m 的投影发束，连续执行至静态 tile 命中。
2. 保持 enabled/castsShadow=true，仅通过统一提交将长度改为 0.3 m，确认 effective 已发布该配置，并读回接收面。
3. 通过直接 apply 先恢复旧配置、再设置同一 0.3 m 配置，触发完整刷新；场景最终配置相同，读回作入口一致性对照。

实际结果：

```text
hair geometry submit: rendered=0 reused=1 geometryEffect=3000 mismatch=3000
HairGeometrySubmissionAcceptanceGlTest.java:452
expected mismatching receiver pixels: 0
actual: 3000
```

刷新后的短发束阴影与原长发束相差 3,000 像素，证明这次几何编辑确实会改变阴影；统一提交仍留下全部这些旧阴影像素。应将阴影相关几何/材质参数变化纳入统一发布的失效身份，并补“投影持续开启→修改几何→阴影更新→再次稳定复用”测试。仅比较投影开关不足以关闭原问题。

## 仍未完成的整体出口

S6 本轮没有新增代码或测试。二次报告列出的差额仍成立：战斗组缺扭曲/贴花；材质组缺位移/PCSS；水域组缺折射/玻璃/探针的受支持交互；粒子组缺新 GPU 碰撞/排序/间接 draw、随机透明/烟雾及非零介质；镜头组缺重建/降噪与部分后处理/UI；DDGI/VCT 的互斥拒绝不能替代各自动态 GI 场景。

`S6InteractionMatrixGlTest.java:625–630` 仍以 `A 在 B 前 || B 在 A 前` 验证两个已存在的不同 pass，不能证明正确透明次序。当前 7/7 只能记为已有子组合通过。此处沿用原计划代表交互要求，没有新增全排列或正式性能要求。

技术卡中列出的“不支持/未验证”应分别对应实际合同。新增厚度贴图、光线追踪或新的 transmission shadow pass 并不是本轮自动追加的要求；已有薄叶作为 shadow caster 的自遮挡限制、MASK/阴影一致性及宿主交互则仍需按原计划说明或补证。

## 46 项逐项状态

“保留已测”沿用前两次报告的支持范围及仍有效的归档，不等于完整 ACCEPTED。所有行都受整体交互出口约束。

| # | 功能 | 三次状态 |
|---|---|---|
| 1 | Fresnel / Rim Lighting | 保留已测 |
| 2 | UV Flow / Distortion | 保留 sampler 补证；过滤限制不变 |
| 3 | Dissolve / Erosion | 保留已测 |
| 4 | Screen-Space Distortion | 保留已测；组合待补 |
| 5 | Vertex Displacement | 保留已测；组合待补 |
| 6 | Projected Decals | 薄墙/移动/resize 补证通过；宿主组合待补 |
| 7 | VFX Dynamic Lighting | 保留已测 |
| 8 | Thin Translucency | 新增边界通过；MASK caster/阴影联合证据与既有限制保留 |
| 9 | Contact Shadows | 阻塞：T1 |
| 10 | Cloud Shadows | 保留已测 |
| 11 | Water Refraction | 保留已测；水/玻璃组合待补 |
| 12 | DOF | 保留已测 |
| 13 | Refraction | 保留已测；水/玻璃组合待补 |
| 14 | Water Surface | 保留已测；平面反射消费已验证 |
| 15 | Particle Collision | 生产入口已接；碰撞启用消费者证据待补 |
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
| 26 | GPU Particle Sorting | 生产入口已接；透明策略交互待补 |
| 27 | GPU Particle Indirect | 生产入口已接；收益未评估 |
| 28 | Weighted OIT | 保留已测；完整粒子策略组合待补 |
| 29 | Atmospheric Scattering | 保留已测 |
| 30 | Planar Reflections | 水面消费者缺口已关闭 |
| 31 | POM | 保留已测 |
| 32 | SSS 材质 | 保留已测 |
| 33 | Screen-Space SSS | 保留已测 |
| 34 | Temporal Denoising | 保留已测 |
| 35 | Temporal Upscaling | 保留已测；收益未评估 |
| 36 | Caustics | 保留已测 |
| 37 | Stochastic Transparency | 保留已测；组合待补 |
| 38 | Volumetric Particles | 保留已测；完整体积交互待补 |
| 39 | SSGI | jitter 缺陷关闭；限定时域像素证据通过 |
| 40 | Volumetric Clouds | 保留已测 |
| 41 | Adaptive Sampling | 保留预算/residual 补证；性能未评估 |
| 42 | VRS | 保留 NV 功能证据；性能未评估 |
| 43 | Volumetric Smoke | 保留已测；完整体积交互待补 |
| 44 | DDGI | 保留已测；动态 GI 组合待补 |
| 45 | VCT | 保留已测；动态 GI 组合待补 |
| 46 | Hair Strands | 阻塞：T2；原启用/移动/关闭反例已通过 |

## 验证命令与证据

本轮目录：`D:/project/Java/learnopengl-worktrees/integration2/build/reports/render-roadmap/third-acceptance-20261113-e3bb38d0/`。

| 证据 | XML / tests | 结果 |
|---|---|---|
| `test/` | 7 / 43 | 新执行，全部通过，无 skip/error |
| `glSmoke/` | 6 / 31 | 新执行，全部通过，无 skip/error |
| `probes-junit/` | 3 / 3 | 新执行，1 通过、2 失败，无 skip/error |
| 历史 Focused | 250 / 1342 | REUSED_PASS，无 failure/error/skip |

新执行合计 77 个用例；上述计数不代表功能合同数。`targeted.log` 的 Gradle 耗时 13 秒，`probes.log` 7 秒；编译/资源任务部分 UP-TO-DATE，测试任务实际执行。两个失败各执行一次，没有自动重试、放宽断言或修改生产代码。计数存于 `independent-counts.json`。

交付目录下的新执行命令：

```powershell
.\gradlew.bat test --tests '*ScreenSpaceContactShadowPipelineWiringTest' --tests '*ShadowCacheStateTest' --tests '*SsgiTemporalReferenceTest' --tests '*SsgiPipelineCompositionTest' --tests '*SsgiShaderTest' --tests '*ProjectedDecalTest' --tests '*ThinTranslucencyShaderTest' glSmoke --tests '*ScreenSpaceContactShadowsGlTest' --tests '*RenderPipelineWiringB3GlTest' --tests '*SsgiTemporalGlTest' --tests '*ProjectedDecalGlTest' --tests '*ThinTranslucencyGlTest' --tests '*S6InteractionMatrixGlTest' --console=plain

.\gradlew.bat -I build/reports/render-roadmap/third-acceptance-20261113-e3bb38d0/probes.init.gradle glSmoke --tests '*ContactUnlitDirectOnAcceptanceGlTest.screenSpaceContactShadowDarkensTheContactAndFallsBackOffScreen' --tests '*HairGeometrySubmissionAcceptanceGlTest.featureRequestChangingHairGeometryRefreshesTheCachedShadow' --tests '*HairFeatureSubmissionAcceptanceGlTest.movingOnlyTheHairLockInvalidatesTheStaticShadowCacheAndTheShadowFollows' --console=plain
```

`probe-src/` 保存补充源码，`create-probes.ps1` 保存生成过程；其中上一轮 `HairFeatureSubmissionAcceptanceGlTest` 原样复制复验并通过。临时 init script 使用绝对路径加入测试源，不带 `-I` 的正常构建不引入这些临时源。正式用例 XML 在反例执行前已独立归档，未被失败报告覆盖。

历史证据位于 `build/reports/bounded-verification/a524d782-c88c-4359-946c-d2b70c1d8730/`，原候选 `79890835f2ed782095113f79645825a7ef5e1379`，148.069 秒、candidateStable=true、releaseReady=false。本轮重新解析全部 250 份 XML，计数与 result.json 一致；该候选至当前 HEAD 仅修改状态 JSON，生产、shader、测试、构建输入和调试策略未变，因此可复用。不得将之前二次候选的 1,331 个旧结果不加影响分析地整体替代此归档。

本轮还核对 SSGI shader SHA-256 `2e2c7a411061400d3df7598c9ea81e6b8b6d5ab3ef33e96c621f5b2447baa392` 与资产清单一致。调试策略仅将 ThinTranslucency 的 NVIDIA 性能消息 131218 上限由 2 改为 4；规则仍限定设备、类、消息种类，不豁免 GL 正确性错误。新测试增加独立 context/管线，不能把这一性能消息计数调整描述为修复像素错误。

## 下一次验收的收口条件

1. 修正 T1 的光照分量合同及正式 fixture 的错误预期；以受光 PBR 接收面证明正确阴影贡献，以 unlit/发光/IBL 和已有阴影重叠作负对照。
2. 修正 T2 的阴影形状身份；统一提交覆盖持续投影状态下的几何编辑、可见阴影变化和稳定复用。
3. 补已有 #8 MASK caster/阴影联合证据、#15 碰撞消费者及原计划 S6 代表交互，或由用户明确接受范围调整；DONE 文案本身不能代替验收。
4. 保留本轮已关闭的 jitter、贴花接收面等证据，下一轮按修改影响选择复测。人工视觉、正式性能、多硬件、长 soak 和完整发布矩阵本轮未执行，不自动要求补跑，也不声称发布就绪。
