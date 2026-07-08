# Controlling Abstraction Density

本文说明本项目如何控制抽象密度，避免在渲染框架演进过程中出现“接口很多、行为很薄、实现不可验证”的问题。

当前项目已经具备较多抽象：`RenderDevice`、`RenderGraph`、`CommandBuffer`、各类 `Descriptor`、资产管理、Scene、Pipeline、后处理、统计与调试系统。后续开发的重点不是继续增加概念数量，而是确保每个抽象都有明确职责、真实使用场景、测试覆盖和退场机制。

## Core Principle

抽象只能解决已经出现的重复、边界或演进压力，不能用于预支未来可能性。

判断标准：

| 问题 | 合理抽象 | 不合理抽象 |
|---|---|---|
| 是否有两个以上真实调用点？ | 是 | 否 |
| 是否减少重复或隔离变化？ | 是 | 否，只是换了名字 |
| 是否有测试能证明行为？ | 是 | 否 |
| 是否让调用方更简单？ | 是 | 否，调用方需要理解更多概念 |
| 是否有明确不做什么？ | 是 | 否，职责持续膨胀 |

## Abstraction Budget

每引入一个新抽象，必须同时交付以下内容：

- [ ] 至少一个真实生产代码调用点。
- [ ] 至少一个测试，覆盖该抽象的核心行为。
- [ ] 明确它替代了什么重复逻辑或隔离了什么变化。
- [ ] 明确它不负责什么。
- [ ] 明确失败模式，例如非法参数、缺失资源、生命周期错误。
- [ ] 不引入只被单个类使用的 public interface，除非它用于测试隔离或后端边界。

## When To Add An Abstraction

### 可以新增抽象的场景

- [ ] 同一类逻辑已经在 2-3 个地方重复出现。
- [ ] 当前实现已经阻碍测试，例如必须创建真实 GL context 才能验证纯逻辑。
- [ ] 后端边界真实存在，例如 `RenderDevice`。
- [ ] 生命周期需要集中管理，例如 `RenderTargetManager`。
- [ ] 数据结构需要稳定协议，例如 `FramebufferDescriptor`。
- [ ] 调用方正在泄漏底层细节，例如到处直接传 GL 常量或手动管理 FBO attachment。

### 不应新增抽象的场景

- [ ] 只是为了“未来可能支持 Vulkan/Metal”。
- [ ] 只有一个实现、一个调用点、没有测试。
- [ ] 抽象名比原代码更难理解。
- [ ] 新接口只是把一行构造函数包起来。
- [ ] 需要读 3 个文件才能理解原本 20 行代码。
- [ ] 抽象层之间没有真实策略差异，只是机械转发。

## Project-Specific Rules

### RenderDevice

`RenderDevice` 是后端边界，可以保留，但新增方法必须谨慎。

新增 `RenderDevice` API 前必须满足：

- [ ] OpenGL 实现能给出实际行为。
- [ ] 未来显式 API 后端确实需要该概念。
- [ ] 测试能验证接口契约。
- [ ] 不把 OpenGL 专属概念暴露到通用接口中。

避免：

- [ ] 为每个 GL 调用都加一层 `RenderDevice` 方法。
- [ ] 在接口里暴露过早的 Vulkan 式细节。
- [ ] 添加当前 OpenGL 后端永远空实现的方法，除非它是明确的兼容占位，例如 `transition(ResourceBarrier...)`。

### Descriptor

Descriptor 适合描述稳定资源，不适合描述临时调用参数。

适合使用 Descriptor：

- [ ] Framebuffer。
- [ ] 多调用点验证过的 Buffer、Texture 或 Pipeline state 配置。
- [ ] 长生命周期资源。
- [ ] 需要测试构造合法性的配置对象。

不适合使用 Descriptor：

- [ ] 单次 draw call 参数。
- [ ] 临时矩阵或 uniform 值。
- [ ] 只有一个字段的包装对象。
- [ ] 只是为了让构造函数看起来更“架构化”。

### RenderGraph

`RenderGraph` 应保持为 pass 编排系统，不应变成万能渲染引擎。

它应该负责：

- [ ] Pass 注册。
- [ ] Pass 依赖排序。
- [ ] Render target 生命周期。
- [ ] Pass 级 profiling。
- [ ] resize 后资源重建。

它不应该负责：

- [ ] 场景遍历策略。
- [ ] 材质绑定细节。
- [ ] 光照模型。
- [ ] asset loading。
- [ ] shader variant 决策。

### CommandBuffer

`CommandBuffer` 应逐步减少 `custom()` 的使用。

允许 `custom()` 的情况：

- [ ] 临时接入尚未正式建模的 GL 操作。
- [ ] 调试计时器 begin/end。
- [ ] 明确不会捕获跨线程可变对象。
- [ ] 已经有注释说明未来替换方向。

需要转正为正式命令的情况：

- [ ] 同类 `custom()` 出现 2 次以上。
- [ ] 操作会影响 `StateCache`。
- [ ] 操作是渲染主路径的一部分。
- [ ] 操作需要测试或统计。

## Abstraction Review Checklist

新增类、接口、descriptor、manager、factory 前，先过这份 checklist。

- [ ] 这个抽象的调用方是谁？
- [ ] 当前至少有几个调用点？
- [ ] 如果不加这个抽象，重复或耦合具体在哪里？
- [ ] 这个抽象隐藏了复杂度，还是把复杂度转移给调用方？
- [ ] 是否能用 package-private 方法先解决？
- [ ] 是否能先用具体类，等第二个实现出现后再抽接口？
- [ ] 是否有测试覆盖它的核心行为？
- [ ] 是否有明确的删除条件？
- [ ] 名称是否表达职责，而不是表达愿景？

## Naming Rules

名称必须描述当前事实，不描述未来野心。

推荐：

- `FramebufferDescriptor`
- `RenderTargetManager`
- `TextureAssetCache`
- `ForwardPipeline`
- `InstanceDataLayout`

谨慎使用：

- `UniversalRenderer`
- `RenderSystem`
- `EngineCore`
- `AbstractResourceManager`
- `PipelineOrchestrator`

规则：

- [ ] 名称中有 `Manager` 时，必须说明它管理什么生命周期。
- [ ] 名称中有 `Factory` 时，必须有多种创建策略或后端隔离需求。
- [ ] 名称中有 `Descriptor` 时，必须是稳定、可验证的配置数据。
- [ ] 名称中有 `Plan` 时，不能混入运行时行为。
- [ ] 名称中有 `System` 时，必须跨多个对象协调真实流程。

## Refactoring Strategy

控制抽象密度不是禁止抽象，而是让抽象按证据增长。

推荐流程：

1. 先写具体实现。
2. 出现重复后提取私有方法。
3. 多个类需要共享时提取 package-private helper。
4. 出现稳定数据协议时提取 record/descriptor。
5. 出现真实边界或多实现时提取 interface。
6. 抽象稳定后补文档和测试。
7. 如果抽象 2-3 次迭代仍只有一个调用点，考虑内联或降级为具体类。

## Warning Signs

出现以下情况时，应暂停继续加抽象：

- [ ] 新增功能需要同时改 5 个以上抽象层。
- [ ] 测试只能验证构造，不验证行为。
- [ ] 接口比实现更复杂。
- [ ] 大量类只有字段和 getter，没有稳定行为。
- [ ] 文档里解释的是“未来会怎样”，代码里没有当前收益。
- [ ] Demo 没有使用新抽象。
- [ ] 新抽象只是为了绕开已有抽象的限制。
- [ ] 一个 bug 需要跨 `backend / core / subsystems / runtime` 四层排查。

## Practical Targets For This Project

### 近期

- [x] 盘点所有只有一个实现的 public interface。
- [x] 盘点所有只有一个调用点的 descriptor、manager、factory。
- [x] 给 `cmd.custom()` 使用点建立 issue 或 TODO，注明是否需要正式命令。
- [x] 将 `future-plans.md` 中已完成项迁移到 changelog 或 milestone，避免 roadmap 变成历史清单。
- [x] 保留能被 demo 和测试同时证明的抽象，降级没有实际行为支撑的抽象。

### 中期

- [x] 为 `RenderDevice` 定义最小稳定接口，避免继续膨胀。
- [x] 为 `RenderGraph` 明确 pass/resource/profile 三个职责边界。
- [x] 将资产系统从自定义 DSL 逐步迁移到更标准、可校验的格式。
- [x] 为 shadow、postprocess、scene pipeline 各保留一个高质量 demo，证明抽象价值。
- [x] 建立真实 GL context 下的集成测试或截图验证。

### 长期

- [ ] 只有当 OpenGL 后端稳定后，再开始第二后端实验。
- [ ] 第二后端实验应先验证 `RenderDevice` 边界，而不是一次性重写完整 renderer。
- [ ] 保持 core 层数据协议稳定，避免为单个 backend 泄漏特殊分支。
- [ ] 每个长期抽象都必须有 demo、测试、文档三者之一作为最低证明，核心抽象必须三者都有。

## Definition Of Done

一个抽象只有满足以下条件，才算真正完成：

- [ ] 有真实调用方。
- [ ] 有自动化测试。
- [ ] 有清楚的职责边界。
- [ ] 有错误处理策略。
- [ ] 有资源生命周期规则。
- [ ] 没有把底层细节泄漏给不该知道的层。
- [ ] 删除它会让代码明显更差，而不是更简单。

## Summary

控制抽象密度的核心不是少写接口，而是让每个接口都付房租。

在这个项目里，合理的方向是：

- 保留已经有行为、有测试、有 demo 的抽象。
- 收缩只有愿景、没有行为的抽象。
- 先让 OpenGL 路径稳定，再讨论多后端。
- 先让 demo 证明能力，再把能力提升为框架概念。
- 每个新抽象都必须降低复杂度，而不是移动复杂度。
