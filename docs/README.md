# 项目文档

本目录按用途组织项目文档。新文档应放入对应分类，避免继续堆放在 `docs` 根目录。

## 当前入口

2026-11-20 复核：当前构建版本为 0.28.1（未发布候选）；0.28 战斗 VFX 材质与动态光照已接入 Showcase 生产调用链。
正式性能、完整视觉、真实宿主与自动选测仍未完成。

- 开发与验证：[测试指南](guides/testing.md)、[测试合同索引](guides/test-contracts.md)、[有侧重验收规范](guides/focused-acceptance-policy.md)。
- 仓库协作：[根级 AGENTS.md](../AGENTS.md)，按任务路由到构建、测试和 Render3D 局部指引。
- 当前实现：[能力矩阵](planning/capability-matrix.md)、[0.27.3 模块合同](architecture/v0.27.3-render3d-module-contracts.md)、[包迁移清单](architecture/v0.27.3-render3d-package-migration.md)、[0.27.4 统一控制合同](architecture/v0.27.4-render3d-feature-control.md)、[0.28 战斗 VFX 指南](guides/render3d-combat-vfx.md)。
- 当前结果：[0.28.1 候选变更记录](history/changelog.md)、[0.28.0 验收](releases/v0.28.0-review.md)、[0.27.5 验收（治理范围）](releases/v0.27.5-review.md)、[0.27.4 验收](releases/v0.27.4-review.md)；聚焦通过不等于正式发布就绪。
- 治理进度：[0.27.5 Agent 指引与测试治理计划](planning/v0.27.5-agent-and-test-governance.md)、[测试治理实施记录](history/v0.27.5-test-governance-2026-11-09.md)。
- 本轮整理：[文档清理记录](history/v0.27.5-documentation-cleanup-2026-11-09.md)。

阅读规则：未标历史的指南用于当前操作；带版本的计划、性能和发布报告保留原候选身份，其中“当前”“必须”和测试矩阵均限定于原记录。旧源码路径可能因迁移失效，`build/` 证据可能未随检出保留；缺失证据不能作为本次可复用通过，也不能替换为新路径冒充原证明。代码/合同已演进时先看上述当前入口。

## 规划

项目目标、当前能力与后续工作：

- [项目目标](planning/project-goals.md)
- [核心能力矩阵](planning/capability-matrix.md) — 实现状态、证明入口与当前限制
- [后续计划](planning/future-plans.md)
- [实时渲染技术未来计划大纲](planning/realtime-rendering-roadmap.md) — 46 项技术分组、依赖、模块归属、验收与近期版本建议
- [46 项渲染功能展览馆计划书](planning/render-feature-exhibition-plan.md) — 统一可交互视觉观察入口、46 个展位映射、实施批次与人工验收合同
- [46 项渲染功能展览馆人工视觉验收记录](planning/render-feature-exhibition-acceptance.md) — 逐站 A/B 截图归档、观察人登记与检查清单

### 版本计划与设计快照

以下保存各阶段的设计依据与未完成项，不是每次开发必须重做的任务清单；计划是否实现以能力矩阵和对应验收记录为准。

- [v0.16 场景扩展、可见性裁剪与 Render Queue 实施计划](planning/v0.16-scene-scalability-and-visibility.md)
- [v0.17 真实场景压力与 CPU 提交收敛实施计划](planning/v0.17-real-scene-cpu-submission-consolidation.md)
- [v0.17.1 RenderGraph 资源预览与帧调试可视化实施计划](planning/v0.17.1-rendergraph-resource-preview.md)
- [v0.17.2 发布工程、GL 生命周期与静态提交加固实施计划](planning/v0.17.2-release-lifecycle-static-submission-hardening.md)
- [v0.18.1 VFX 曲线动画、缓动与过渡实施计划](planning/v0.18.1-vfx-animation-curves.md)
- [v0.18.2 纹理化 HDR VFX 与 Bloom 合成实施计划](planning/v0.18.2-textured-hdr-vfx-and-bloom.md)
- [v0.18.2 资源目录整理与打包规范](planning/v0.18.2-resource-layout-and-packaging.md)
- [v0.18.3 动画运行时、约束与 Morph Target 实施计划](planning/v0.18.3-animation-runtime-graph-and-morph.md)
- [v0.19 序列化场景、统一资产身份、异步加载与热重载实施计划](planning/v0.19-serialized-scene-assets-async-hot-reload.md)
- [v0.19.x UI SDF、Compositor、现代动画与 VFX 深化计划](planning/v0.19-ui-sdf-compositor-animation-vfx.md)
- [v0.20 代码整理与逻辑优化计划](planning/v0.20-code-consolidation-and-logic-optimization.md)
- [v0.20.1 可嵌入渲染与外部宿主契约实施计划](planning/v0.20.1-embedded-rendering.md)
- [v0.21 序列化游戏场景与角色内容管线](planning/v0.21-serialized-gameplay-scene-character-pipeline.md)
- [v0.23 Render3D 框架加固与能力扩展计划](planning/v0.23-render3d-framework-hardening-and-capability-expansion.md)
- [v0.23.1 Render3D 光源与阴影深化计划](planning/v0.23.1-render3d-lighting-and-shadow-enhancement.md)
- [v0.23.2 Render3D GTAO 与时域环境遮蔽实施计划](planning/v0.23.2-render3d-gtao-temporal-ambient-occlusion.md)
- [v0.23.4 Render3D 动态阴影精细裁剪与失效计划](planning/v0.23.4-render3d-dynamic-shadow-culling-and-invalidation.md)
- [v0.24 风格化室外环境与体积阳光计划](planning/v0.24-stylized-outdoor-environment-and-volumetric-lighting.md)
- [v0.24.1 统一 Scene Buffers 与 TAA 实施指南](planning/v0.24.1-scene-buffers-and-taa.md)
- [v0.24.2 Clustered Forward 实施规划书](planning/v0.24.2-clustered-forward.md)
- [v0.24.3 渲染收尾与视觉基线执行指南](planning/v0.24.3-rendering-consolidation-and-visual-baseline.md)
- [v0.25.0 多光源体积雾执行计划](planning/v0.25.0-multilight-volumetric-fog.md)
- [v0.25.1 风格化场景控制执行规划](planning/v0.25.1-stylized-scene-control.md)
- [v0.25.1 最小能力验证 Demo 设计](planning/v0.25.1-capability-demos.md)
- [26.0 完善ui compositor](planning/v0.26.0-ui-compositor.md)
- [27.0 Subsystems 模块边界与生命周期重构：第一阶段](planning/v0.27.0-subsystems-modularity.md)
- [0.27.1 GTAO 模块边界与渲染装配收敛计划](planning/v0.27.1-gtao-module-boundaries.md)
- [0.27.2 共享场景表面与输入合同收敛计划](planning/v0.27.2-shared-scene-surface-contracts.md)
- [0.27.3 Render3D 模块边界收敛与包结构重组](planning/v0.27.3-render3d-module-boundaries-and-packages.md)
- [0.27.4 Render3D 统一功能控制与模块开关计划](planning/v0.27.4-render3d-feature-control.md)
- [0.27.5 Agent 指引与测试治理计划](planning/v0.27.5-agent-and-test-governance.md)
- [0.27.6 可选任务与范围建议](planning/v0.27.6-candidate-tasks.md)
- [0.28 战斗特效材质与动态光照实施计划](planning/v0.28.0-combat-vfx-materials-and-lighting.md)
- [Haikalat OpenGL 4.6 后续路线图](planning/haikalat-future-opengl46.md)

## 架构

渲染边界、抽象准入原则和已有抽象审计。版本合同的替代关系与当前入口见页首；旧审计保留当时结果。

- [诊断系统合同](architecture/diagnostics-contract.md)

- [渲染边界约定](architecture/render-boundaries.md)
- [v0.25.0 体积雾坐标、绑定、合成与资源合同](architecture/v0.25.0-volumetric-contract.md)
- [v0.27.0 体积雾模块、借用输入与候选所有权合同](architecture/v0.27.0-volumetric-module-contract.md)
- [v0.27.1 GTAO 模块、参数与两模块共性合同](architecture/v0.27.1-gtao-module-contract.md)
- [v0.27.1 GTAO 开工与修正基线](releases/v0.27.1-baseline.md)
- [v0.27.1 GTAO 复核记录](releases/v0.27.1-review.md)
- [v0.27.2 共享场景表面合同](architecture/v0.27.2-shared-scene-surface-contract.md)
- [v0.27.3 Render3D 模块边界合同](architecture/v0.27.3-render3d-module-contracts.md)
- [v0.27.3 Render3D 包迁移清单](architecture/v0.27.3-render3d-package-migration.md)
- [v0.27.4 Render3D 统一功能控制合同](architecture/v0.27.4-render3d-feature-control.md)
- [v0.27.2 开工基线](releases/v0.27.2-baseline.md)
- [v0.27.2 共享场景表面验收](releases/v0.27.2-review.md)
- [v0.27.2 独立验收（2026-11-09）](releases/v0.27.2-independent-review-2026-11-09.md)
- [v0.27.3 基线](releases/v0.27.3-baseline.md)
- [v0.27.3 验收记录](releases/v0.27.3-review.md)
- [v0.27.3 独立验收（2026-11-09）](releases/v0.27.3-independent-review-2026-11-09.md)
- [v0.27.4 基线](releases/v0.27.4-baseline.md)
- [v0.27.4 验收记录](releases/v0.27.4-review.md)
- [v0.27.4 复审修复](releases/v0.27.4-review-fixes-2026-11-09.md)
- [v0.27.5 验收记录（治理范围）](releases/v0.27.5-review.md)
- [v0.27.0 模块化开工基线](performance/v0.27.0-modularity-baseline.md)
- [v0.27.0 RTX 3050 Laptop 本机预算政策与已有样本复核](performance/v0.27.0-rtx3050-budget-policy.md)
- [v0.27.0 第一阶段验收记录（体积雾边界与生命周期试点）](releases/v0.27.0-phase1-review.md)
- [v0.27.0 当前候选补充验收（2026-11-07：新预算、完整视觉与本轮 Minecraft）](releases/v0.27.0-current-candidate-review-2026-11-07.md)
- [AsyncDemo 渲染线程契约](architecture/async-render-thread.md)
- [抽象密度控制](architecture/abstraction-density.md)
- [代码职责与重设计评估](architecture/code-design-review.md)
- [v0.23 Render3D M0 基线与合同清单](architecture/v0.23-render3d-m0-contract-inventory.md)
- [v0.23 Render3D M1 revision 与 frame context 合同](architecture/v0.23-render3d-m1-frame-context.md)
- [v0.23 Render3D M2 PipelineGeneration 合同](architecture/v0.23-render3d-m2-pipeline-generation.md)
- [v0.23 Render3D M3 Render Queue 合同](architecture/v0.23-render3d-m3-render-queues.md)
- [v0.23 Render3D M4 glTF alpha 与 MASK shadow 合同](architecture/v0.23-render3d-m4-gltf-alpha-shadow.md)
- [v0.23 Render3D M5 MSAA depth resolve 合同](architecture/v0.23-render3d-m5-depth-resolve.md)
- [v0.23 Render3D M6 阴影与 CSM 合同](architecture/v0.23-render3d-m6-cascaded-shadows.md)
- [v0.23 Render3D M7 可见性与诊断合同](architecture/v0.23-render3d-m7-visibility-diagnostics.md)
- [v0.23 Render3D M7 可见性与空间索引结论](performance/v0.23-render3d-visibility-index-decision-2026-08-18.md)
- [v0.23.1 Render3D 阴影帧计划与缓存合同](architecture/v0.23.1-render3d-shadow-frame-plan.md)
- [v0.20 UI 内部收敛](architecture/v0.20-ui-consolidation.md)
- [v0.20 动画运行时收敛](architecture/v0.20-animation-convergence.md)
- [v0.20 渲染核心与资源生命周期](architecture/v0.20-render-core-lifecycle.md)
- [Stress indexed / compact SSBO 渲染路径](architecture/stress-render-path.md)
- [全项目公共 API 分类](architecture/public-api/README.md)
- [资源代次与异步解码合同](architecture/resource-generations.md)
- [GPU 与 native 资源所有权合同](architecture/resource-ownership.md)

## 指南

- [新人快速上手：从第一个窗口到渲染管线](guides/newcomer-guide.md)
- [调试与诊断指南](guides/diagnostics.md)
- [可嵌入渲染接入指南](guides/embedded-rendering.md)

开发、运行和验证项目时使用的操作指南。

- [测试指南](guides/testing.md)
- [测试合同索引](guides/test-contracts.md)
- [有侧重验收规范](guides/focused-acceptance-policy.md)
- [UI Compositor](guides/ui-compositor.md)
- [现代 UI 动画、合成与 VFX](guides/ui-animation-compositor-vfx.md)
- [序列化场景 v1](guides/serialized-scenes.md)
- [glTF Animation Graph sidecar](guides/gltf-animation-state-machine.md)
- [场景异步加载与热重载](guides/scene-asset-loading.md)
- [Demo 职责与 API 覆盖](guides/demo-responsibilities.md)
- [Render3D 材质、透明与阴影指南](guides/render3d-materials-and-shadows.md)
- [Render3D 天空与室外迁移](guides/render3d-outdoor-environment.md)
- [多光源物理体积雾](guides/render3d-volumetric-fog.md)
- [Milestone 4 API、性能与限制](guides/milestone4-api-performance-limitations.md)
- [Windows IME 适配与人工验收矩阵](guides/windows-ime.md)

## 历史

版本演进与历史验收记录，包括失败、未测和未发布项；不以版本号推断已经完成。

- [变更记录](history/changelog.md)
- [v0.8–v0.15 已归档实施计划](history/archived-plans.md)
- [v0.14 发布验收报告](releases/v0.14-release-report.md)
- [v0.15 发布验收报告](releases/v0.15-release-report.md)
- [v0.16 发布候选验收报告](releases/v0.16-release-report.md)
- [v0.17 本地发布候选验收报告](releases/v0.17-release-report.md)
- [v0.17.1 RenderGraph 资源预览正式版验收报告](releases/v0.17.1-release-report.md)
- [v0.18.0 Haikalat OpenGL 4.6 路线图发布验收报告](releases/v0.18.0-release-report.md)
- [v0.18.1 VFX 曲线动画、缓动与过渡验收报告](releases/v0.18.1-release-report.md)
- [v0.18.2 纹理化 HDR VFX 与 Bloom 合成发布验收报告](releases/v0.18.2-release-report.md)
- [v0.19.4 UI 深化正式版验收报告](releases/v0.19.4-release-report.md)
- [v0.20.0 代码收敛与外部 glTF 动画正式版验收报告](releases/v0.20.0-release-report.md)
- [v0.20.1 可嵌入渲染正式版验收报告](releases/v0.20.1-release-report.md)
- [v0.20.1 可嵌入渲染 snapshot 验收报告](releases/v0.20.1-snapshot-report.md)
- [v0.20.2 外部动画与现代游戏 UI 正式版验收报告](releases/v0.20.2-release-report.md)
- [v0.22.0 文本子系统与 Markdown/UI 字体特效正式版验收报告](releases/v0.22.0-release-report.md)
- [v0.23.0 Render3D 框架加固与能力扩展正式版验收报告](releases/v0.23.0-release-report.md)
- [v0.23.1 Render3D 光源与阴影深化正式版验收报告](releases/v0.23.1-release-report.md)
- [v0.24.0 室外环境与体积阳光发布报告](releases/v0.24.0-release-report.md)
- [v0.24.2 Clustered Forward 发布准备报告](releases/v0.24.2-release-report.md)
- [v0.24.3 渲染收尾与视觉基线发布候选报告](releases/v0.24.3-release-report.md)
- [v0.25.0 多光源体积雾实现与验收状态](releases/v0.25.0-release-report.md)
- [v0.25.0 版本身份与打包核对](releases/v0.25.0-version-identity-2026-10-08.md)
- [v0.25.0 合并收尾与 v0.25.1 开工记录](releases/v0.25.0-merge-cleanup-2026-10-08.md)
- [v0.25.0 体积雾开工基线](performance/v0.25.0-volumetric-baseline.md)
- [v0.21 M0 基线报告](releases/v0.21-m0-baseline.md)
- [2026-08-06 v0.20.2 外部动画与现代 UI 性能记录](performance/v0.20.2-modern-ui-2026-08-06.md)
- [2026-08-08 v0.22.0 文本与字体特效专项记录](performance/v0.22.0-text-effects-2026-08-08.md)
- [2026-08-19 v0.23.1 Render3D 阴影性能与缓存记录](performance/v0.23.1-render3d-shadows-2026-08-19.md)
- [2026-09-08 v0.24 室外环境阶段性能记录](performance/v0.24-outdoor-environment-2026-09-08.md)
- [2026-09-08 v0.24 室外渲染优化复测](performance/v0.24-outdoor-improvements-2026-09-08.md)
- [2026-07-28 v0.20 M7 热路径性能与回归记录](performance/v0.20-m7-2026-07-28.md)
- [2026-07-28 v0.19.4 Modern UI 验证记录](performance/v0.19.4-modern-ui-2026-07-28.md)
- [2026-07-26 v0.18.2 纹理化 HDR VFX 性能与资源报告](performance/v0.18.2-textured-hdr-vfx-2026-07-26.md)
- [2026-07-25 v0.18.1 VFX 曲线性能记录](performance/v0.18.1-vfx-curves-2026-07-25.md)
- [2026-07-25 v0.18.0 综合场景性能与稳定性报告](performance/v0.18.0-haikalat-roadmap-2026-07-25.md)
- [2026-07-20 v0.17.1 RenderGraph 资源预览 1080p/4K 五轮基准](performance/v0.17.1-rendergraph-preview-2026-07-20.md)
- [2026-07-19 v0.17 真实场景 CPU 提交五轮基准](performance/v0.17-scene-submission-2026-07-19.md)
- [2026-07-19 v0.16 scene visibility 五轮基准](performance/v0.16-scene-visibility-2026-07-19.md)
- [2026-07-19 v0.15 diagnostics 五轮开销基准](performance/v0.15-debugging-2026-07-19.md)
- [2026-07-15 Stress 百万实例三轮基准](performance/stress-one-million-2026-07-15.md)
- [2026-07-15 post-v0.8 色彩、Bloom 与实例阴影基准](performance/post-v0.8-bloom-2026-07-15.md)
- [2026-07-15 v0.10 自动曝光 1080p/4K 五轮基准](performance/v0.10-auto-exposure-2026-07-15.md)
- [2026-07-16 v0.11 UI 1080p/4K 五轮容量基准](performance/v0.11-ui-2026-07-16.md)
- [2026-07-15 Stress 矩阵快照修复后五轮基准](performance/stress-matrix-copy-fix-2026-07-15.md)
- [2026-07-14 Stress indexed / compact SSBO 基准](performance/stress-indexed-ssbo-2026-07-14.md)
- [2026-07-17 v0.12 PBR / IBL 1080p/4K 五轮代表基准](performance/v0.12-pbr-2026-07-17.md)
- [2026-07-18 v0.13 glTF decode/upload 与 1080p/4K 五轮基准](performance/v0.13-gltf-2026-07-18.md)
- [2026-07-18 v0.14 收口、百万实例与 UI allocation 复测](performance/v0.14-consolidation-2026-07-18.md)
