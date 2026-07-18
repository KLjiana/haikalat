# 诊断系统合同

v0.15 的诊断链路只观察正式渲染、资源和 UI 路径，不拥有 RenderGraph、GL 资源或用户的
`UiSystem`，也不改变 pass 顺序、attachment、shader variant 和最终像素。

## 分层与所有权

- backend 只发布 query、结构化 GL message 和 context-scoped resource 元数据；registry 只保存值，
  不强引用 `GlResource`。
- core 的 `RenderGraph.description()` 直接来自 compiled plan 和 framebuffer descriptor，返回深度不可变
  description，不暴露 framebuffer、texture owner 或 native collection。
- runtime 的 `FrameDriver` 独占写入 `FrameDiagnostics`。history 默认 240 帧，合法范围 16～4096；
  `clear()` 开启新 epoch，但全局 frame sequence 不回退。
- Demo 组合层的 `DiagnosticsPanel` 消费只读快照，并与原 HUD 共用一个 `UiSystem` 和
  `UiOverlayPass`。UI subsystem 不依赖 runtime diagnostics；资源和消息页只读取
  `FrameDiagnostics.read()` 返回的同一发布边界，不直接查询 backend。
- exporter 只接受 `FrozenDiagnostics`，文件 I/O 不会发生在 render pass 或 GL callback 中。
- `FrozenDiagnostics` 的公共值类型不暴露 `GlDebug`、LWJGL 或其他 backend 类型；runtime 在发布边界
  将 backend 快照适配成自身不可变 DTO。

## GPU 时间语义

`PassProfile` 使用 `PENDING / AVAILABLE / SKIPPED / UNSUPPORTED / FAILED`。只有
`AVAILABLE` 才有 `gpuNanos`、`sampleFrameSequence` 和 `sampleAgeFrames`；其余状态在 UI 中显示状态名，
在 JSON 中导出 `null`，不会伪装为 `0.000 ms`。query ring 满时跳过采样而不阻塞 CPU。

`FrameProfile.totalGpuNanos()` 只累加可用样本，调用方必须同时检查 `gpuTotalComplete()`。旧的
`PassProfile(String,long,long)` 构造器保留，并把显式 GPU 值视为兼容的 available 样本。

命令执行在异常收尾时会中止仍处于 active 状态的 timer query，再恢复 prepared batch 和 debug group。
RenderGraph 无论在 pass callback 还是命令执行阶段失败，都会先发布使用当前 frame sequence 的
`FAILED` profile；诊断发布自身失败只能作为原始渲染异常的 suppressed failure，不能覆盖根因。

## GL 消息和资源

- 每个 LWJGL capabilities/context 拥有独立的 256 项消息环和资源表。
- 相邻相同消息折叠为 repeat count；满环优先淘汰 notification/low，并累计 dropped count。
- push/pop/marker 本身不进入消息环，避免 RenderGraph 分组淹没驱动错误；分组名仍传给 OpenGL，供
  Nsight/RenderDoc 使用，并成为真实 driver message 的 phase。
- resource identity 使用框架单调 sequence，不以可能复用的 native id 为主键。重复 close 幂等。
- DETAILED driver 通过 context-scoped 引用计数 lease 开启资源跟踪；BASIC/OFF driver 不修改其他
  session 的跟踪状态，最后一个 lease 关闭后才停止并清理该 context 的 registry。
- 已覆盖 buffer、2D/cube texture、framebuffer 及附件、program、VAO、query 和 sampler。
- estimated bytes 是按已知 storage 计算的估值，不等同驱动真实显存；裸 OpenGL escape hatch 不在完整性承诺内。

## 冻结与导出

`freeze()` 在同一 epoch 捕获 history、资源清单和消息清单。live ring 随后可以继续推进或清空，已返回的
capture 不变化。`clear()` 同时丢弃尚未发布的 scene/UI 摘要，避免旧 epoch 数据进入新 epoch；owner
关闭后全部读取、freeze 和 clear 入口统一抛出 closed 异常。

JSON schema v1 固定字段顺序，使用 UTF-8 临时文件并在 flush/close 后原子替换目标。写文件前验证
所有数值和 section 的结构一致性，元数据包含引擎版本、构建修订和 OpenGL 环境。默认省略 native id；
Demo 自动路径只允许位于 `build/diagnostics/`。

关闭 `FrameDriver` 会关闭 diagnostics owner。`OFF` 不写历史；`BASIC` 发布帧、pass、present、state 和
严重消息摘要；`DETAILED` 额外发布 graph、资源和完整允许严重度消息。
