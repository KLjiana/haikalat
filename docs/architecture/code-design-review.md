# 代码职责与重设计评估

本轮审查结论：项目不需要整体重写。现有 `subsystems/runtime -> core -> backend` 依赖方向仍然合理，真正需要调整的是 shader 资源模型、压力测试资源生成和 RenderGraph 每帧临时对象；其余模块应以数据证明为前提再拆分。

## 已执行的调整

| 类/区域 | 发现的问题 | 本轮处理 | 当前结论 |
|---|---|---|---|
| `ShaderProgram` | 只接受 vertex+fragment；`glUniform*` 隐式依赖当前 program；没有 SSBO/image/compute 入口 | 改为 stage-aware builder，支持 vertex、tessellation、geometry、fragment、compute；name lookup 缓存改为 GL 线程本地 `HashMap`；公开 DSA uniform、UBO/SSBO block 绑定 | 保留该类，职责仍是“编译、链接、反射和 program 级绑定” |
| `ShaderAsset` / `HotReloadableShader` | 资源描述和热重载写死两个 stage | 改为 `ShaderStage -> AssetRef`，热重载遍历所有 stage；properties manifest 接受 compute/geometry/tessellation | 保留，两者分别负责纯资源描述和运行时 program 交换 |
| `CommandBuffer` | `List<Consumer<StateCache>>` 每条命令创建捕获对象；连续 pipeline setter 即使最终被覆盖也会逐条检查 | 改为可复用 primitive opcode stream；录制期 pending state 在 draw/clear/blit/dispatch/query/custom 等边界压成一个 state packet；RenderGraph timer 使用正式 query opcode | 保留单类公共 API；内部 typed stream 已消除主要短命命令对象，不需要“一命令一类” |
| `StateCache` | 固定写死 32 个 binding，既可能限制高端硬件，也不能表达真实驱动上限 | binding 数量在首次使用时查询 GL 4.6 capability；增加 SSBO range 和 image unit 缓存 | 保留；它是 OpenGL backend 内聚类，不应上移到 core |
| `RenderGraph` | 每帧创建 command list、`PassResources`、pass timing `HashMap`，轻负载时污染 CPU 基准 | immediate backend 复用 command buffer；复用 `PassResources` 和 timing 数组；timer 直接归属 pass | 保留；资源、调度、profile 三项职责仍围绕同一 pass 生命周期 |
| `StressDemo` | 三种图元坐标与 `BuiltinMeshData` 重复；一个通用 shader 含 shape 分支和无关常量 | 独立 build-time generator 生成展开、indexed、indexed+compact SSBO 三套专用 GLSL/topology/catalog；实例 codec、EBO 和 buffer 生命周期移入独立类 | Demo 只保留配置、运行与测量逻辑；生成和 packing 不进入主循环 |
| `Camera` | `getViewMatrix()` 和向量更新产生短命对象 | 增加 caller-owned matrix 重载，原地更新 front | 保留，当前职责单一 |

## 暂不执行的大改

### CommandBuffer opcode arena

当前已从 `List<Consumer<StateCache>>` 迁移为连续 opcode/data arena，并加入 pending pipeline state。不要继续扩张为“一种命令一个类”；只有满足以下任一条件才值得进一步改为 off-heap/native command pages：

- CPU command recording 稳定超过帧时间的 10%；
- 单帧达到数千 draw/dispatch，profile 明确指向 on-heap command arrays 或 object payload；
- 引入真正 deferred backend，需要可序列化命令数据。

届时应保留现有 opcode 协议并替换底层 page allocator，不应创建“一种命令一个类”的对象层级。

### RenderGraph 拆成 scheduler/resource manager/profiler

当前三项工作共享 pass 定义和生命周期，强行拆分会增加协调对象。只有第二 backend 要求不同资源分配策略，或 profiler 需要完全独立开关时再拆。

### Geometry shader 或 indirect draw

单 mesh、单 draw 的 StressDemo 不会从 multi-draw indirect 获益；geometry shader 扩建立方体通常降低吞吐。应在真实多 mesh/material 压力场景出现后，再评估 GPU culling 和 `glMultiDraw*IndirectCount`。

## 下一阶段建议

1. compact SSBO 与 indexed procedural 路径已落地；继续新增字段时必须保持 16-byte contract，或引入显式 layout version，不能静默改变 shader ABI。
2. benchmark 已输出平均/中位 CPU/GPU 时间和实际 VS invocation；需要跨机器回归时再增加 CSV/JSON 与多轮汇总。
3. 当前实测 indexed-ssbo 比纯 indexed 多约 0.034 ms；下一步用 Nsight 检查 SSBO load latency/occupancy，只在真实非规则、动态实例场景中继续优化，不为规则网格强行增加抽象。
