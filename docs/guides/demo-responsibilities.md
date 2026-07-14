# Demo 职责与 API 覆盖

Demo 不要求每个公开 API 都重复出现，而是用互不重叠的场景证明一组能力；纯数据、失败路径、资源关闭和边界校验由 unit/GL smoke tests 覆盖。

| Demo | 主要职责 | 重点 API / 指标 | 不负责 |
|---|---|---|---|
| `EmptyWindowDemo` | 统一分辨率下的 clear/present 基线 | `GlfwWindow`、空 `RenderGraph`、present/CPU/GPU timing、state skip | shader、VAO、buffer、draw |
| `MinimalDemo` | 最小同步渲染入口和 API 教学 | command buffer、material、texture、framebuffer、mesh、instanced batch、resize | 阴影、异步线程、极限性能 |
| `LearnOpenGlDemo` | 完整功能正确性基准 | asset、model、scene、lighting、directional shadow、AA、camera | 极端实例吞吐 |
| `AsyncDemo` | 双线程所有权和异步上传正确性 | GL render thread、latest-frame mailbox、upload queue、UBO、关闭顺序 | 大规模几何和完整光照 |
| `StressDemo` | 可重复的实例吞吐与 A/B 性能诊断 | procedural/indexed/SSBO/Matrix4f、GPU timer、pipeline statistics、state skip | 画面功能验收、复杂材质 |

## StressDemo 模式

默认运行 10 万个 GPU procedural triangle：

```powershell
.\gradlew.bat runStressDemo
```

四种模式保持相同窗口、clear、present、VSync 和 debug 设置：

- `gpu`：原有展开式 procedural 路径；Cube 每实例 36 个展开顶点。
- `indexed`：无 VBO 的空 VAO + 初始化一次的 uint8 EBO；Cube 用 `gl_VertexID` 的 XYZ bit 解码 8 个逻辑角点，Quad 使用 4 个角点/6 个索引。
- `indexed-ssbo`：indexed topology 加 16-byte `std430 uvec4` 实例；静态场景只上传一次，颜色、平移、缩放和基础旋转均由 packed 数据解码。
- `dynamic`：保留通用 `Matrix4f` persistent-mapped 实例路径，作为 64-byte 兼容与性能对照。

示例：

```powershell
.\gradlew.bat runStressDemo -PstressShape=cube -PstressInstances=100000 -PstressMode=indexed-ssbo
.\gradlew.bat runStressDemo -PstressShape=cube -PstressInstances=100000 -PstressMode=indexed -PstressFrames=1000 -PstressWarmup=100 -PstressHidden=true
```

main class 参数：

```text
--mode=gpu|indexed|indexed-ssbo|dynamic
--shape=triangle|quad|cube
--instances=1..1000000
--vsync
--hidden
--frames=N
--warmup=N
--deterministic
```

有限帧运行输出 present FPS、CPU/GPU 平均与中位帧时间、draw calls、state-cache skip ratio 和硬件支持时的实际 VS invocation。比较优化时以帧时间为主，不以 FPS 差值代替时间差。

`generateProceduralShaders` 从 `BuiltinMeshData` 和专用 topology 定义生成三套 GLSL、Java catalog、index count/type；输出仅位于 `build/generated`，`compileDemoJava` 与 `processDemoResources` 自动依赖生成任务。

## EmptyWindowDemo

```powershell
.\gradlew.bat runEmptyWindowDemo -PemptyFrames=1000 -PemptyWarmup=100 -PemptyHidden=true
```

它使用与 StressDemo 相同的 1280×720 clear/present 基准，但不创建 shader、VAO、VBO/EBO/SSBO，也不调用 draw；用于分离窗口、swap、驱动与 overlay 的固定成本。
