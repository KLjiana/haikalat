# 渲染路线图逻辑复查与合并问题清单

**候选 `e3bb38d0`：在第三次验收的两个缺陷之外，又确认三个逻辑缺陷，并复现一个技术卡已披露的反射节流限制。整体验收结论仍为不通过。** 下表合并汇报六项，区分新增缺陷、原有缺陷和已知限制。

本次按用户“再复查逻辑，一起汇报”的请求，继续检查控制入口、候选代发布/退役、成功帧提交、历史有效性、反射节流与消费者时序。未改生产代码或正式测试；补充 GL 探查源码和证据仅写入 `build/`。不是全仓无缺陷保证，也没有重跑全部 46 项矩阵。

## 合并清单

| ID | 优先级 / 分类 | 问题与触发条件 | 实际证据 |
|---|---|---|---|
| T1 | P1，第三次验收已确认 | #9 接触阴影乘暗整个颜色；directLighting 开启时 unlit 地面仍受阴影影响 | 地面亮度从 `0.17152537` 降到 `0.046492055` |
| T2 | P1，第三次验收已确认 | #46 发束持续投影时，通过统一提交修改长度，未失效旧阴影 | 长度 2.0→0.3 m；`rendered=0/reused=1`，与相同最终配置直接 apply 的接收面相差 3,000 像素 |
| L1 | P2，新增缺陷 | #9 批量“暂停并修改接触阴影配置”后，仅恢复总开关，配置启用但屏幕空间 pass 缺失 | effective 的 master/direct/contact 均启用，`hasPass=false` |
| L2 | P2，新增缺陷 | #46 新代已发布，但旧代退役 fence 创建失败时，跳过发束缓存失效 | 故障注入后启用仍被发布；预期刷新 1 个 tile，实际 0 |
| L3 | P1，新增缺陷 | #30 反射在命令录制时提前标为有效；GPU 提交失败后不撤销，恢复帧又被节流 | `valid=true`，失败后更新计数 1，恢复后仍为 1；首份反射绘制从未提交 |
| L4 | P2，已知限制 / 验证不足 | #30 更新间隔大于 1 时，相机突变仍复用旧视角反射；现有“切镜”测试只验证每帧更新配置 | 将同一正式 fixture 的间隔改为 3，原“切镜必须刷新”断言失败；技术卡已承认该行为 |

T1/T2 的完整复现和 46 项状态见 [第三次验收报告](2026-11-13-render-roadmap-third-acceptance.md)。本次不将它们重新计为新执行，也不把 L4 冒充为未披露的新缺陷。

## L1：暂停批量修改后，恢复留下错误拓扑

位置：[RenderPipeline.java:3837](D:/project/Java/learnopengl-worktrees/integration2/src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderPipeline.java:3837)、[同文件:3858](D:/project/Java/learnopengl-worktrees/integration2/src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderPipeline.java:3858)。

`effectiveContactShadowSettings()` 把 `featureMasterEnabled` 纳入资源/拓扑判断；但总开关恢复走 `sameFeatureIntentExceptMaster` 快速路径，只更新字段、发布 effective、重置历史，不重建候选。这两条规则互相矛盾。

真实 GL 事件序列：

1. 初始 pipeline 正常运行，接触阴影关闭。
2. 同一请求提交接触阴影开启和 `enabled(false)`。此时旧 effective 仍开启，`executeFrame` 会处理该请求并生成新代；新代因 master=false 没有接触阴影 pass。
3. 再提交同一配置的 `enabled(true)`，执行恢复帧。快速路径复用缺 pass 的新代。
4. 检查 effective：master、directLighting、contactShadows 均开启；图里却没有 `ScreenSpaceContactShadowPass`。

失败：`ContactPauseResumeLogicGlTest.java:173`，期望 pass 存在，实际不存在。普通“只暂停→只恢复”可能正常，不能覆盖这个批量编辑路径。

**修复方向：** 将“暂停执行”与“子功能拓扑存在”分开，或让恢复路径核对并修复实际拓扑。保留总开关的配置/资源语义，补批量暂停修改及初始暂停配置的恢复回归。

## L2：退役异常导致已经发布的新代漏做必要失效

位置：[RenderPipeline.java:3872](D:/project/Java/learnopengl-worktrees/integration2/src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderPipeline.java:3872)、[同文件:3632](D:/project/Java/learnopengl-worktrees/integration2/src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderPipeline.java:3632)。直接兼容入口 `applyHairStrandsInternal` 也将同类失效放在 `activateGeneration` 之后。

`activateGeneration(candidate)` 先发布 activeGeneration，再退役旧代。退役创建 fence 失败会抛异常；统一提交入口识别“新代已发布”，保留新 effective 并继续使用新代，这符合现有事务约定。然而发束缓存失效写在调用返回之后，因此异常会跳过它，新代继续借用旧 tile。

反例沿用正式 `featureRequestEnablingHairInvalidatesTheStaticShadowCache`。基线帧成功后提交启用发束，在执行该提交期间启用仓库既有的 `haikalat.test.failRetirementFence` 故障注入，并在 finally 清除。期望发束首次投影刷新一个 tile，实际 `tilesRendered=0`，断言于 `HairRetirementFailureLogicGlTest.java:433` 失败。

这与 T2 是不同故障模式：即使把布尔比较改为完整几何比较，只要失效仍在可能抛错的退役操作之后，异常路径依然漏掉它。静态检查确认直接 apply 也存在相同顺序；本轮实际执行的是统一入口反例，不宣称两个入口都跑了故障注入。

**修复方向：** 把新代正确性所需的失效纳入发布过程，保证旧代退役失败不跳过新代初始化；不要回滚已经发布的新代。正常提交和发布后异常应共用同一正确性处理。

## L3：反射成功录制被错误当作成功提交

位置：[PlanarReflectionPass.java:293](D:/project/Java/learnopengl-worktrees/integration2/src/main/java/com/kaleblangley/haikalat/subsystems/render3d/planar/PlanarReflectionPass.java:293)、[RenderPipeline.java:3450](D:/project/Java/learnopengl-worktrees/integration2/src/main/java/com/kaleblangley/haikalat/subsystems/render3d/RenderPipeline.java:3450)。

`PlanarReflectionPass.record` 在 CPU 录制完成时立刻设置 `contentValid=true`、递增 `updateCount`、启动更新间隔。`RenderGraph` 在所有 pass 录制结束后才调用 `device.execute(cmd)`；因此 recorded 不等于 GPU 已写入纹理。pipeline 的失败回调覆盖 SSGI、TAA、云、雾、阴影等 owner，但没有处理 planar owner。

反例使用已存在的水面+平面反射 fixture，更新间隔为 3。设备包装器在反射已录制、调用真实 `GlRenderDevice.execute` 之前抛异常；它没有修改生产代码或吞掉 GL 错误。随后用正常设备执行一个恢复帧：

```text
planar failed submission: valid=true countAfterFailure=1 countAfterRecovery=1
```

第一次反射绘制没有提交，纹理却对水面标为可采样；恢复帧仍跳过反射更新。`PlanarFailedSubmissionLogicGlTest.java:324` 对“失败提交不应发布有效内容”的断言失败。这不是仅有“可能显示旧内容”的理论风险，而是已验证的有效性和恢复调度错误；未把未初始化纹理的任意颜色当作稳定像素真值。

**修复方向：** 区分本帧可供后续 pass 消费的候选输出与跨帧已提交的有效内容。成功提交后再确认有效性/节流；失败时首份内容必须无效，并强制下一帧更新。已有纹理可能被部分 GPU 写入时也应采用明确的失效/回退策略，不能仅保留 valid 标志。

## L4：节流与切镜没有联动，现有测试范围有限

位置：[PlanarReflectionPass.java:244](D:/project/Java/learnopengl-worktrees/integration2/src/main/java/com/kaleblangley/haikalat/subsystems/render3d/planar/PlanarReflectionPass.java:244)、[PlanarReflectionPipelineGlTest.java:279](D:/project/Java/learnopengl-worktrees/integration2/src/test/java/com/kaleblangley/haikalat/subsystems/render3d/PlanarReflectionPipelineGlTest.java:279)。

Pass 在检查当前相机之前就根据 `framesUntilUpdate` 返回。生产 pipeline 没有在相机突变时调用 `requestUpdate()`。正式 `cameraCutRefreshesThePlanarReflectionUsedByWater` 使用默认间隔 1，本来每帧都会更新；它无法证明切镜能打断节流。

本轮保留该 fixture 的相机变化，只将间隔改为 3，下一帧 `updateCount` 未增加，原断言失败。这里沿用测试作者命名的“camera cut”事件，不将其声称为已验证某个公共切镜阈值。代码没有相机变化检测这一事实独立成立。

[技术卡:87](D:/project/Java/learnopengl-worktrees/integration2/docs/planning/v0.28.1-s3-planar-reflections.md:87) 已明确“切镜/相机突变若命中节流，可能短暂显示旧反射”。因此本项归为**已披露限制与签收范围问题**，不是新增未披露缺陷。当前五个 pipeline GL 用例通过不能推广为“所有更新频率下切镜正确”。

**建议：** 若要满足切镜及时刷新，令突变打断节流，并用间隔大于 1 的用例验证；若保留设计，需明确允许的旧反射时长及使用限制，避免用默认间隔的测试宣称覆盖该分支。

## 其他审查结果与未完成范围

- 未发现本轮足够证据推翻 SSGI jitter 修复结论：上一帧 jitter 在成功帧提交，失败路径丢弃 pending；第三次验收的独立 jitter 反例仍有效。本轮未重跑它。
- GPU 粒子消费适配器的碰撞启用、暂停/取消像素和宿主 OIT/随机透明顺序仍属于既有缺证范围。`BEFORE_TRANSPARENT_COLLECTORS` 是调用方声明，不能由枚举值推导 pipeline 时序已验证；本轮没有将这些缺证另计为已复现缺陷。
- 平面反射只取主视图可见集、TAA 水面采样与非 jitter 反射纹理的组合，技术卡已有范围备注。本轮未新增对应像素反例，不将它们计入上面六项。
- S6 代表组合差额、#8 MASK caster/阴影联合证据、人工视觉和性能边界，继续沿用第三次报告。没有因为本次逻辑审查自动扩展为 benchmark、长 soak 或发布矩阵。

## 验证与候选保持

实际目录：`D:/project/Java/learnopengl-worktrees/integration2`；HEAD 始终为 `e3bb38d0f7ade59886f12bfe91780307e34ae799`，tracked/untracked 工作树干净，构建版本 `0.28.0`。

新增证据：`build/reports/render-roadmap/logic-review-20261113-e3bb38d0/`，含 `probe-src/`、生成脚本、init script、`probes.log`、`junit/` 和 `case-summary.json`。

```powershell
.\gradlew.bat -I build/reports/render-roadmap/logic-review-20261113-e3bb38d0/probes.init.gradle glSmoke --tests '*ContactPauseResumeLogicGlTest.screenSpaceContactShadowDarkensTheContactAndFallsBackOffScreen' --tests '*PlanarCutIntervalLogicGlTest.cameraCutRefreshesThePlanarReflectionUsedByWater' --tests '*PlanarFailedSubmissionLogicGlTest.cameraCutRefreshesThePlanarReflectionUsedByWater' --tests '*HairRetirementFailureLogicGlTest.featureRequestEnablingHairInvalidatesTheStaticShadowCache' --console=plain
```

实际 **4 个新执行、4 个断言失败、0 error/skip**，Gradle 18 秒；其中三项确认新增缺陷，一项复现已披露限制。首次生成探查源码时，换行匹配保护主动停止，尚未启动 Gradle；修正临时脚本的匹配方式后四项各执行一次。记录见 `harness-note.txt`，不计为产品失败或测试通过，也没有对产品失败自动重试。

第三次报告的 75 个通过用例、2 个失败反例以及 1,342 个历史通过证据本轮仅引用，不称为新执行。两个报告的新执行合计 81 次（75 通过、6 个断言失败），该数值不等于独立功能覆盖数；其中一个失败是更强预期与已披露限制的冲突。

建议优先收口 T1、T2 和 L3；同步修复 L1/L2 的入口和事务一致性，最后确定 L4 的切镜策略与证据。修复应覆盖根因，而不是只让各自的单个反例变绿。
