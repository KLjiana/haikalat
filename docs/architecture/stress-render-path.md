# Stress 渲染路径设计

## 边界

- `ProceduralShaderGenerator` 位于独立 build-time source set，负责 topology、专用 GLSL 和 Java catalog；生成物只进入 `build/generated`。
- `PackedInstanceLayout` 是纯 JVM codec，属于 core mesh data，不调用 OpenGL。
- `PackedInstanceBuffer` 位于 core buffer 层，通过 backend 的 `GlBuffer`/`GpuFence` 实现静态一次上传和动态三槽 ring。
- `StressIndexedGeometry` 只管理空 VAO 和一次性 EBO；它不创建 VBO，也不在帧循环重新绑定 EBO。
- `StressDemo` 只选择模式、构建资源、运行统一帧循环并输出统计。

依赖方向仍为 `subsystems/runtime -> core -> backend`，backend 没有新增对 core 的引用。

## 数据契约

```glsl
struct PackedInstance {
    uvec4 data;
};
```

| word | 编码 | 含义 |
|---|---|---|
| `x` | `half2` | translation.xy |
| `y` | `half2` | translation.z、scale |
| `z` | `snorm16x2` | 基础 rotation cos、sin |
| `w` | `unorm8x4` | RGBA |

静态数据只上传一次。动态数据的每个 ring slot 以 `GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT` 对齐，并由独立 fence 保护；CPU shadow 的 change journal 只把尚未传播到当前槽的 dirty ranges 复制过去。统一动画使用 `uTimeRotation`，不触发实例重传。

## Cube topology

Cube 的 index 值只在 0..7。`gl_VertexID` 的 bit 0/1/2 分别解码 X/Y/Z 的 ±0.5；36 个 uint8 indices 按 post-transform cache 局部性排序，所有 12 个三角形保持朝外逆时针绕序。当前无光照 shader 可以共享 8 个角点；未来若增加逐面法线，应改为 24 个逻辑顶点，或在 fragment shader 使用 derivative 法线，不能给共享角点错误地绑定单一面法线。

## 继续重设计的门槛

当前没有整体重写必要。以下情况出现后再增加更复杂架构：

- packed contract 需要超过 16 bytes：引入显式 layout version，而不是静默改变现有 shader ABI。
- 多 mesh/material 的 draw 数量成为瓶颈：再评估 indirect draw、GPU culling 和命令排序；当前单 draw 压测不会从 MDI 获益。
- compute 更新实例：dispatch 后、draw 前通过正式 `CommandBuffer.memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)` 建立可见性；当前静态 SSBO 不需要 barrier。
- 需要跨机器回归：为现有平均/中位统计增加 CSV/JSON，而不是把序列化逻辑放进 Demo 主循环。
