# 后续计划

本文只记录尚未完成的工作。当前实现状态参见
[`capability-matrix.md`](capability-matrix.md)，已完成工作统一记录在
[`../history/changelog.md`](../history/changelog.md)。

## 当前重点

v0.16 场景扩展与可见性闭环已作为 `0.16.0` 验收。普通 renderer 已具备 bounds、SceneFrame、
camera/shadow frustum 和 primitive Render Queue；本机 10,000 renderer / 10% 可见场景把 draw
从 10,000 降到 1,000。下一版本先用真实多 mesh/material glTF 场景复核 CPU queue、显存和 draw
分布，再决定是否进入 GPU culling + MDI；不因单个程序化基准直接扩张协议。

### 近期

- [ ] 在每个正式版候选上重新运行 `runUiNativeSoak` 与 `runUiSyntheticImeSoak`，保留失败日志；当前候选已分别通过 50 轮真实 native 生命周期和 100 轮 synthetic IME 生命周期。
- [ ] 在正式版前复核 advanced 列表；新增 UI public 类型必须通过 allowlist 架构测试，不得无意扩大 stable 兼容面。
- [ ] 对 10,000 quad 剩余约 600 KiB/frame 做 allocation profile，优先消除 retained-tree 遍历与 record 热路径分配；目标仍为 256 KiB/frame 以下。
- [ ] 在可用的远端仓库中确认 Windows/Linux CI 实际运行并保持通过。
- [ ] 运行 `runPbrBenchmarks` 的完整四 AA 五轮矩阵并归档，而不只保留代表性 FXAA 组合。
- [ ] 在正式发布流水线复验 v0.16 的远端 Windows/Linux CI。
- [x] 完成 v0.12 人工视觉清单：mirrored UV、non-uniform scale、environment rotation、shadow/IBL 分离及 UI 不受曝光影响。

### 中期

- [x] 以 UI subsystem 建立只读调试面板、资源/消息检查和 frozen capture；没有绕过正式生命周期边界。
- [ ] 为 RenderGraph texture preview 单独设计 HDR/depth/MSAA/cubemap 可视化策略，不在通用 diagnostics 中裸采样。
- [ ] 继续监控 backend/core seam；只有出现真实维护阻力时才进一步拆分模块。
- [ ] 用同一 10,000 renderer 数据判断 GPU frustum/Hi-Z culling 与 `glMultiDraw*IndirectCount`：
  只有 CPU SceneFrame 持续成为目标硬件瓶颈、draw 仍高且材质协议能批处理时才进入；否则优先
  缓存静态 transform/bounds 和减少 per-draw command/uniform allocation。
- [ ] 当纹理绑定成为实测瓶颈后，评估 bindless texture 或 texture array；在当前 state-cache skip 已接近饱和前不扩大材质协议。

### 长期

- [ ] 只有当 OpenGL 后端稳定后，再开始第二后端实验。
- [ ] 第二后端实验应先验证 `RenderDevice` 边界，而不是一次性重写完整 renderer。
- [ ] 保持 core 层数据协议稳定，避免为单个 backend 泄漏特殊分支。
- [ ] 每个长期抽象都必须有 demo、测试、文档三者之一作为最低证明，核心抽象必须三者都有。

## 非目标

以下事项暂不作为优先目标，避免项目过早扩大范围。

- 不追求完整游戏引擎功能，例如物理、音频、脚本和动画状态机。
- 不引入复杂 ECS，除非当前 `SceneObject` 模型出现明确瓶颈。
- 不直接重写 Vulkan 后端；第二后端只用于验证稳定边界。
- 不在稳定公共组件之前一次性建设封闭、单体的生产级编辑器；允许调试器和编辑工具作为 UI/runtime API 的真实使用者逐步演进。
- 不将 v0.13 静态 glTF 主路径扩大为动画/骨骼、透明/折射、高级材质扩展或完整材质编辑器。
