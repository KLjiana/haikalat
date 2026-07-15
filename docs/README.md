# 项目文档

本目录按用途组织项目文档。新文档应放入对应分类，避免继续堆放在 `docs` 根目录。

## 规划

项目目标、当前能力和真正尚未完成的工作。

- [项目目标](planning/project-goals.md)
- [核心能力矩阵](planning/capability-matrix.md) — 实现状态、证明入口与当前限制
- [实例化阴影与 HDR/ACES 实施计划](planning/instanced-shadow-hdr-roadmap.md) — v0.8 已完成里程碑
- [post-v0.8 色彩与 Bloom 路线图](planning/post-v0.8-roadmap.md) — v0.9 已完成主线与条件基准
- [v0.9 收尾计划](planning/v0.9-closeout.md) — 发布候选异常生命周期与 LDR sRGB 闭环
- [v0.10 全 GPU 自动曝光实施计划](planning/v0.10-gpu-auto-exposure.md) — 已完成 GPU 测光、时间适应与发布验收
- [后续计划](planning/future-plans.md)

## 架构

渲染边界、抽象准入原则和已有抽象审计。

- [渲染边界约定](architecture/render-boundaries.md)
- [AsyncDemo 渲染线程契约](architecture/async-render-thread.md)
- [抽象密度控制](architecture/abstraction-density.md)
- [代码职责与重设计评估](architecture/code-design-review.md)
- [Stress indexed / compact SSBO 渲染路径](architecture/stress-render-path.md)

## 指南

开发、运行和验证项目时使用的操作指南。

- [测试指南](guides/testing.md)
- [Demo 职责与 API 覆盖](guides/demo-responsibilities.md)

## 历史

已完成工作和版本演进记录。

- [变更记录](history/changelog.md)
- [2026-07-15 Stress 百万实例三轮基准](performance/stress-one-million-2026-07-15.md)
- [2026-07-15 post-v0.8 色彩、Bloom 与实例阴影基准](performance/post-v0.8-bloom-2026-07-15.md)
- [2026-07-15 v0.10 自动曝光 1080p/4K 五轮基准](performance/v0.10-auto-exposure-2026-07-15.md)
- [2026-07-15 Stress 矩阵快照修复后五轮基准](performance/stress-matrix-copy-fix-2026-07-15.md)
- [2026-07-14 Stress indexed / compact SSBO 基准](performance/stress-indexed-ssbo-2026-07-14.md)
