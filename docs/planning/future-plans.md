# 后续计划

本文只记录尚未完成的工作。当前实现状态参见
[`capability-matrix.md`](capability-matrix.md)，已完成事项记录在
[`../history/changelog.md`](../history/changelog.md)。

## 当前重点

当前重点是 v0.22 文本系统的合同、性能与生命周期稳定性。v0.22.1 只做加固：固定受控
CommonMark 显示子集，建立 Markdown/layout/glyph atlas/文字特效的 1080p 与 4K 基线，补齐
字体切换、失败回滚和灯光缓存失效回归；不引入新的渲染或 UI 功能。

### 近期

- [ ] 完成 v0.22.1 的完整 `runTextBenchmarks`、真实 GL、release readiness 与正式 tag。
- [ ] 每个正式候选重新运行 `runUiNativeSoak` 与 `runUiSyntheticImeSoak` 并保留失败日志。
- [ ] 在远端仓库确认 Windows/Linux CI 实际运行并保持通过。
- [ ] 运行并归档 `runPbrBenchmarks` 的四种 AA、五轮完整矩阵。
- [ ] 按新的文字场景继续观察 UI allocation；v0.20 收敛后的可信普通容量值约为
  `255 KiB/frame`，不再使用早期约 `600 KiB/frame` 的调查值描述当前状态。
- [ ] 根据 HaikalatHost 的真实集成反馈维护 embedded rendering 合同；宿主专用适配仍留在宿主工程。

### 中期候选

- [ ] 只有在真实 profile 证明材质绑定为瓶颈后，再评估 bindless texture 或 texture array。
- [ ] 只有在 CPU 持续超过约 2 ms、可见 draw 超过 3,000、至少 70% draw 可批处理且
  GPU 不是主要瓶颈时，重新评估 GPU culling/indirect submission。
- [ ] 继续监控 backend/core seam；只有出现真实维护阻力时才拆分模块。

RenderGraph texture preview 已在 v0.17.1 完成，不再列为待办。

### 长期候选

- [ ] 延迟渲染、GPU-driven rendering、Vulkan/第二后端均保持候选，不是既定路线。
- [ ] 第二后端实验若启动，应先验证 `RenderDevice` 边界，而不是一次性重写 renderer。
- [ ] 每个长期抽象至少要有 demo、测试、文档之一作为证据；核心抽象必须三者齐全。

## 非目标

- 不引入复杂 ECS，除非当前 `SceneObject` 模型出现明确且可复现的瓶颈。
- 不一次性建设封闭、单体的生产级编辑器。
- 不把 glTF skin/animation 与现有 Animation Graph 扩张成材质编辑器、宿主资产管线或网络动画系统。
- v0.22.1 不包含完整 BiDi、彩色 Emoji、variable font axis UI、富文本编辑器、Markdown
  link 点击或图片加载、UI offscreen compositor 和新的文字/后处理/VFX 特效。
