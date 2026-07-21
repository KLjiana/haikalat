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

## 架构

- [诊断系统合同](architecture/diagnostics-contract.md)

渲染边界、抽象准入原则和已有抽象审计。

- [渲染边界约定](architecture/render-boundaries.md)
- [AsyncDemo 渲染线程契约](architecture/async-render-thread.md)
- [抽象密度控制](architecture/abstraction-density.md)
- [代码职责与重设计评估](architecture/code-design-review.md)
- [Stress indexed / compact SSBO 渲染路径](architecture/stress-render-path.md)
- [全项目公共 API 分类](architecture/public-api/README.md)
- [GPU 与 native 资源所有权合同](architecture/resource-ownership.md)

## 指南

- [调试与诊断指南](guides/diagnostics.md)

开发、运行和验证项目时使用的操作指南。

- [测试指南](guides/testing.md)
- [Demo 职责与 API 覆盖](guides/demo-responsibilities.md)
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
