# Future Plans

本文件只记录后续计划。项目目标和非目标记录在 `docs/planning/project-goals.md`，一线反馈转化后的执行任务记录在 `docs/planning/developer-task-plan.md`。已经完成的功能扩展和稳定化工作迁移到 `docs/history/changelog.md`，抽象密度盘点记录在 `docs/architecture/abstraction-audit.md`。

## Current Focus

当前阶段从内容扩展转向稳定现有内容，重点是减少没有行为支撑的抽象，保留能被 demo、测试或明确生命周期职责证明的边界。执行顺序以 `docs/planning/real-capabilities-roadmap.md` 为准。

### 近期

- [x] 把 `InstancedRenderer`、`MinimalDemo`、`AsyncDemo` 中的实例化 batch upload/draw 收敛为正式 `CommandBuffer` API。
- [x] 将 `RenderPipeline` 拆成 package-private helper，优先提取 camera uniforms、lighting binder、postprocess builder、TAA history。
- [x] 为 `PassResources` 增加窄 API，并迁移现有内置 pass 优先使用逻辑 attachment 入口。
- [x] 处理旧顶层 demo 入口，避免 `Main`、`Light`、`Camera`、`Shader` 与新 demo 体系并列。
- [x] 建立测试分类约定，区分纯 JVM unit、opt-in `glSmoke`、demo/pipeline integration。

### 中期

- [x] 推广纯数据 `MeshData` 到 demo 和资产路径，明确 `Mesh` 只表示 GL 上传后的运行时资源。
- [x] 明确 `Material` 是当前 OpenGL runtime material；如需配置驱动材质，新增 `MaterialDef` 作为非 GL 定义层。
- [x] 将 `.properties` 目标收敛为 asset manifest + 简单 scene manifest，不承担动画、脚本或编辑器职责。
- [x] 建立资源生命周期错误的 GL smoke 验证，覆盖 framebuffer、texture、shader、asset cache。
- [x] 继续降低 `PassResources` backend-facing 兼容 API 的默认可见度，避免普通 pass 误用具体 OpenGL 类型。
- [x] 为 demo 选择一组稳定基准场景，避免每个功能都新增长期维护入口。
- [x] 增加源码包依赖守卫，阻止 backend/core seam 扩张以及 core/runtime 反向依赖 subsystem。

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
- [ ] 暂不引入复杂 PBR 管线，保持当前基础光照、阴影、材质和后处理链稳定。
