# 后续计划

本文只记录尚未完成的工作。当前实现状态参见
[`capability-matrix.md`](capability-matrix.md)，已完成工作统一记录在
[`../history/changelog.md`](../history/changelog.md)。

## 当前重点

v0.11 UI subsystem 已完成首轮功能实现，当前处于 RC 稳定阶段。发布重点是证明 native/IME
生命周期可靠、收口公共 API，并在不改变绘制顺序与同步语义的前提下降低 UI 帧分配和 batch break。

### 近期

- [ ] 完成至少 50 轮窗口、WndProc hook、事件轮询、hook 恢复和窗口销毁的 native 生命周期 soak，并在轮次间施加 GC 压力。
- [ ] 完成 synthetic IME soak，覆盖 composition start/update/end、焦点丢失、resize/content-scale、活动输入框删除和 popup 内输入框。
- [ ] 完成 60～300 秒交互 UiDemo soak，并记录 Windows IME 人工矩阵；人工结果完成前保持 `0.11.0-rc.1`。
- [ ] 建立 UI 公共 API allowlist 或内部 API 标记与架构测试，避免 RC 后继续无意扩大兼容面。
- [ ] 增加 UI batch-break 原因和每帧分配统计，优先消除逐 glyph/quad 临时对象与 display-list 快照复制。
- [ ] 在可用的远端仓库中确认 Windows/Linux CI 实际运行并保持通过。

### 中期

- [ ] 以 UI subsystem 为基础逐步建设调试面板、资源检查器和编辑工具；工具需求应反向验证稳定 API，而不是绕过生命周期边界。
- [ ] 继续监控 backend/core seam；只有出现真实维护阻力时才进一步拆分模块。
- [ ] 当场景出现多个 mesh/material 的真实压力数据后，评估 GPU frustum/Hi-Z culling 与 `glMultiDraw*IndirectCount`；单 mesh 单 draw 场景不提前引入 indirect command 复杂度。
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
- 不引入复杂 PBR 管线，先保持基础光照、阴影、材质和后处理链稳定。
