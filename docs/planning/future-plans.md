# 后续计划

本文只记录尚未完成的工作。当前实现状态参见
[`capability-matrix.md`](capability-matrix.md)，已完成事项记录在
[`../history/changelog.md`](../history/changelog.md)。

## 当前重点

当前代码基线为 v0.24.0 候选及其后续阴影修复。最近完成的功能版本聚焦
[v0.24 风格化室外环境与体积阳光](v0.24-stylized-outdoor-environment-and-volumetric-lighting.md)，
用“晨雾林地”样板补齐环境表现，并以移动画面、性能和生命周期合同共同验收。
详细范围和阶段以该计划为准；v0.24.0 候选主路径和专项证据已经完成，正式 tag 仍以发布门禁为准。

### 近期

- [x] M0：验证 diffuse IBL 归一化、色彩与曝光，建立室外灰盒及性能基线。
- [x] M1：统一天空、太阳、IBL 和雾预设，补齐资源候选、帧快照及 UI 重绑合同。
- [x] M2：接入深度与 CSM 感知的 HDR 体积阳光，明确消光/散射和兼容性边界。
- [x] M3：完成降采样、独立历史重投影、深度感知上采样和 GPU 成本诊断。
- [x] M4：增加局部雾体、世界噪声，完成晨雾/晴天/黄昏样板与参数保存。
- [x] M5：完成 AA/UI/宿主组合、失败回滚、运动画面、五轮性能与发布验收（待最终门禁）。

### 持续维护与发布验证

- [ ] 每个正式候选重新运行 `runUiNativeSoak` 与 `runUiSyntheticImeSoak` 并保留失败日志。
- [ ] Windows/Linux CI 纳入可无窗口执行的完整 `check` 和 demo 编译，确认远端实际通过。
- [ ] 运行并归档 `runPbrBenchmarks` 的四种 AA、五轮完整矩阵。
- [ ] 保留文字专项性能、真实 GL 与 `releaseReadiness` 验证；旧版本完成状态查阅发布报告。
- [ ] 按新的文字场景继续观察 UI allocation；v0.20 收敛后的可信普通容量值约为
  `255 KiB/frame`，不再使用早期约 `600 KiB/frame` 的调查值描述当前状态。
- [ ] 根据 HaikalatHost 的真实集成反馈维护 embedded rendering 合同；宿主专用适配仍留在宿主工程。

### 中期候选

- [ ] 室外首版稳定后，评估透明/VFX 雾化、薄叶背光与风动，再推进云影、连续昼夜或大气。
- [ ] 只有真实场景证明间接照明不足时，评估局部环境探针或 GI。
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
- v0.24.0 不包含透明/VFX 与新体积模式的组合、实时 GI、体积云、完整物理大气、连续昼夜、
  地形流送、Clustered Lighting 或 Shader Graph；相关限制仅针对新能力，旧路径保持其已有合同。
