# 后续计划

本文只记录尚未完成的工作。当前实现状态参见
[`capability-matrix.md`](capability-matrix.md)，已完成工作统一记录在
[`../history/changelog.md`](../history/changelog.md)。

## 当前重点

v0.20.1 已完成可嵌入渲染契约、非零 FBO 输出、宿主生命周期和正式发布验收。
Haikalat 主工程继续保持宿主无关，不直接依赖 Minecraft/NeoForge。下一阶段以实际
HaikalatHost 集成反馈为输入，不预先把宿主适配写回通用引擎；缺乏性能证据的
GPU-driven 扩张继续暂缓。

### 近期

- [x] 完成 v0.20.1 `PresentationTarget`、external attachment、external camera 和 embedded runtime 公共契约。
- [x] 完成非零 FBO、borrowed 生命周期、zero extent、target replacement 与 GL 状态恢复真实测试。
- [x] HaikalatHost 的维护者已确认 v0.20.1 可发布；宿主侧 Minecraft 客户端验收日志不纳入 Haikalat 主仓库。
- [ ] 在每个正式版候选上重新运行 `runUiNativeSoak` 与 `runUiSyntheticImeSoak`，保留失败日志；当前候选已分别通过 50 轮真实 native 生命周期和 100 轮 synthetic IME 生命周期。
- [x] 正式版前已复核 advanced 列表；新增 public 类型已通过 allowlist 架构测试，未无意扩大 stable 兼容面。
- [ ] 对 10,000 quad 剩余约 600 KiB/frame 做 allocation profile，优先消除 retained-tree 遍历与 record 热路径分配；目标仍为 256 KiB/frame 以下。
- [ ] 在可用的远端仓库中确认 Windows/Linux CI 实际运行并保持通过。
- [ ] 运行 `runPbrBenchmarks` 的完整四 AA 五轮矩阵并归档，而不只保留代表性 FXAA 组合。
- [ ] 在配置远端仓库后复验 v0.18.0 的 Windows/Linux CI。
- [x] 完成 v0.12 人工视觉清单：mirrored UV、non-uniform scale、environment rotation、shadow/IBL 分离及 UI 不受曝光影响。

### 中期

- [x] 以 UI subsystem 建立只读调试面板、资源/消息检查和 frozen capture；没有绕过正式生命周期边界。
- [ ] 为 RenderGraph texture preview 单独设计 HDR/depth/MSAA/cubemap 可视化策略，不在通用 diagnostics 中裸采样。
- [ ] 继续监控 backend/core seam；只有出现真实维护阻力时才进一步拆分模块。
- [x] 用同一 10,000 renderer 数据判断 GPU frustum/Hi-Z culling 与 `glMultiDraw*IndirectCount`：
  v0.17 数据不满足联合门槛，决定暂缓；未来只有 CPU 持续超过约 2 ms、可见 draw 超过 3,000、
  至少 70% draw 可批处理且 GPU 非主要瓶颈时重新评估。
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
- 不把现有 glTF skin/animation 主路径扩张成完整材质编辑器、宿主资产管线或网络动画系统。
