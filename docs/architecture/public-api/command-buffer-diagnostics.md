# CommandBuffer diagnostics API

状态：v0.17 advanced 公共 API。

`CommandBuffer` 从 v0.17 起正式公开以下只读录制统计：

- `recordedMatrixSnapshotCount()`：primitive mat4 arena 中已经完成录制的矩阵快照数；不包含 legacy
  对象 payload 中的矩阵。
- `recordedObjectPayloadCount()`：命令流当前持有的对象引用 payload 数；不包含 primitive arena 和
  尚未 flush 的 pending pipeline state。

两个值只用于 diagnostics、容量观察和性能回归，不是命令协议反射接口。调用 `reset()` 后二者归零；
执行命令不会改变计数。具体 opcode、payload 排列以及一条逻辑命令对应多少 payload 不属于兼容合同。

这两个方法随 `CommandBuffer` 一起归类为 advanced API：次版本可以扩展统计口径，但任何语义收紧、
改名或移除都必须更新 changelog 和迁移文档。
