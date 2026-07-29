# 项目文档

本目录按用途组织项目文档。新文档应放入对应分类，避免继续堆放在 `docs` 根目录。

## 规划

项目目标、当前能力和真正尚未完成的工作。

- [项目目标](planning/project-goals.md)
- [核心能力矩阵](planning/capability-matrix.md) — 实现状态、证明入口与当前限制
- [后续计划](planning/future-plans.md)
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
- [Haikalat OpenGL 4.6 后续路线图](planning/haikalat-future-opengl46.md)

## 架构

- [诊断系统合同](architecture/diagnostics-contract.md)

渲染边界、抽象准入原则和已有抽象审计。

- [渲染边界约定](architecture/render-boundaries.md)
- [AsyncDemo 渲染线程契约](architecture/async-render-thread.md)
- [抽象密度控制](architecture/abstraction-density.md)
- [代码职责与重设计评估](architecture/code-design-review.md)
- [v0.20 UI 内部收敛](architecture/v0.20-ui-consolidation.md)
- [v0.20 动画运行时收敛](architecture/v0.20-animation-convergence.md)
- [v0.20 渲染核心与资源生命周期](architecture/v0.20-render-core-lifecycle.md)
- [Stress indexed / compact SSBO 渲染路径](architecture/stress-render-path.md)
- [全项目公共 API 分类](architecture/public-api/README.md)
- [资源代次与异步解码合同](architecture/resource-generations.md)
- [GPU 与 native 资源所有权合同](architecture/resource-ownership.md)

## 指南

- [调试与诊断指南](guides/diagnostics.md)

开发、运行和验证项目时使用的操作指南。

- [测试指南](guides/testing.md)
- [现代 UI 动画、合成与 VFX](guides/ui-animation-compositor-vfx.md)
- [序列化场景 v1](guides/serialized-scenes.md)
- [场景异步加载与热重载](guides/scene-asset-loading.md)
- [Demo 职责与 API 覆盖](guides/demo-responsibilities.md)
- [Milestone 4 API、性能与限制](guides/milestone4-api-performance-limitations.md)
- [Windows IME 适配与人工验收矩阵](guides/windows-ime.md)

## 历史

已完成工作和版本演进记录。

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
