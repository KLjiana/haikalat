# 后续计划

本文只记录尚未完成的工作。当前实现状态参见
[`capability-matrix.md`](capability-matrix.md)，已完成工作统一记录在
[`../history/changelog.md`](../history/changelog.md)。

## 当前重点

保持现有 OpenGL 主路径、基准场景和真实 GL 回归稳定，新增工作必须有明确的运行行为和验证入口。

### 近期

- [ ] 在可用的远端仓库中确认 Windows/Linux CI 实际运行并保持通过。
- [ ] 在具备多 GPU/驱动环境后，补充操作系统级 iconify/restore 事件验证；渲染器的 0×0 extent 忽略和正尺寸恢复已有真实 GL 测试。

### 中期

- [ ] 评估 RenderGraph profiling 的 `cmd.custom()` 是否需要正式命令或 observer 边界。
- [ ] 继续监控 backend/core seam；只有出现真实维护阻力时才进一步拆分模块。
- [ ] 评估 instanced shadow caster，要求复用现有实例化数据和命令路径，不新增平行实现。
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
- 不做大型编辑器，优先保证运行时、Demo 和调试信息稳定。
- 不引入复杂 PBR 管线，先保持基础光照、阴影、材质和后处理链稳定。
