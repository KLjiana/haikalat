# Future Plans

本文件只记录后续计划。项目目标和非目标记录在 `docs/project-goals.md`。已经完成的功能扩展和稳定化工作迁移到 `docs/changelog.md`，抽象密度盘点记录在 `docs/abstraction-audit.md`。

## Current Focus

当前阶段从内容扩展转向稳定现有内容，重点是减少没有行为支撑的抽象，保留能被 demo、测试或明确生命周期职责证明的边界。

### 近期

- [x] 为 `RenderDevice` 定义最小稳定接口，避免继续膨胀。
- [x] 为 `RenderGraph` 明确 pass、resource、profile 三个职责边界。
- [x] 将资产系统从自定义 DSL 逐步迁移到更标准、可校验的格式。
- [x] 为 shadow、postprocess、scene pipeline 各保留一个高质量 demo，证明抽象价值。
- [x] 建立真实 GL context 下的集成测试或截图验证。

### 中期

- [ ] 根据 `cmd.custom()` TODO，把 instanced batch upload/draw 收敛为正式命令 API。
- [x] 继续压缩只服务单一调用点的 descriptor、manager、factory。
- [ ] 为保留的核心抽象补充文档中的职责边界和删除条件。
- [ ] 建立资源生命周期错误的集成验证，覆盖 framebuffer、texture、shader、asset cache。
- [ ] 为 demo 选择一组稳定基准场景，避免每个功能都新增长期维护入口。

### 长期

- [ ] 只有当 OpenGL 后端稳定后，再开始第二后端实验。
- [ ] 第二后端实验应先验证 `RenderDevice` 边界，而不是一次性重写完整 renderer。
- [ ] 保持 core 层数据协议稳定，避免为单个 backend 泄漏特殊分支。
- [ ] 每个长期抽象都必须有 demo、测试、文档三者之一作为最低证明，核心抽象必须三者都有。

## Non-Goals

以下事项暂不作为优先目标，避免项目过早扩大范围。

- [ ] 暂不追求完整游戏引擎功能，例如物理、音频、脚本、动画状态机。
- [ ] 暂不引入复杂 ECS，除非当前 `SceneObject` 模型无法支撑后续需求。
- [ ] 暂不直接重写 Vulkan 后端，先把 `RenderDevice` 和资源边界打稳。
- [ ] 暂不做大型编辑器，优先保证运行时框架、Demo 和调试信息稳定。
- [ ] 暂不引入复杂 PBR 管线，先完成基础光照、阴影、材质和后处理链。
