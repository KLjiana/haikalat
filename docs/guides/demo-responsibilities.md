# Demo Responsibilities And API Coverage

Demo 的目标不是让每个公开 API 都被每个 Demo 调用，而是用最小、互不重叠的场景证明一组稳定能力。纯数据解析、失败路径、资源关闭和边界校验继续由 unit/GL smoke tests 覆盖。

| Demo | 主要责任 | 重点 API/指标 | 不负责 |
|---|---|---|---|
| `MinimalDemo` | 最小同步渲染入口和 API 教学样例 | window/context、command buffer、material、texture、framebuffer、mesh、instanced batch、resize | 场景配置、阴影、异步线程、极限性能 |
| `LearnOpenGlDemo` | 完整功能正确性基准 | asset manifest、OBJ/builtin model、scene、lighting、directional shadow、AA、RenderGraph、camera、resize | 线程吞吐、极端实例数 |
| `AsyncDemo` | 双线程所有权和异步上传正确性 | GL render thread、latest-frame mailbox、upload queue、UBO、线程安全 timing snapshot、关闭顺序 | 完整光照管线、大规模几何 |
| `StressDemo` | 可重复的实例吞吐与性能诊断 | 100000+ instance、triangle/quad/cube、CPU submit、GPU timer、present FPS、state-cache skip ratio | 画面功能验收、资产格式、复杂材质 |

## StressDemo

默认运行 100000 个三角形实例：

```powershell
.\gradlew.bat runStressDemo
```

Gradle 参数可覆盖形状和数量，例如：

```powershell
.\gradlew.bat runStressDemo -PstressShape=quad -PstressInstances=250000
```

运行 100000 个 cube（约 120 万个三角形）：

```powershell
.\gradlew.bat runStressCubeDemo
```

自动验证两个 100000-instance 场景：

```powershell
.\gradlew.bat runStressIntegration
```

直接运行 main class 时支持：

```text
--shape=triangle|quad|cube
--instances=1..1000000
--vsync
--hidden
--frames=N
--deterministic
```

标题中的 `FPS` 是完成 swap 后的一秒采样值；`CPU` 包含 instance transform 快照、buffer upload 和命令提交；`GPU` 来自 RenderGraph query；`state skip` 是状态缓存跳过比例。StressDemo 当前刻意走动态 instance upload 主路径，因此能够暴露每帧矩阵复制、直接内存分配和 GPU fence 等真实成本，不代表静态场景的理论上限。
