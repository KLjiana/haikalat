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
.\gradlew.bat runStressDemo -PstressShape=quad -PstressInstances=250000 -PstressMode=gpu
```

固定帧数、隐藏窗口 benchmark：

```powershell
.\gradlew.bat runStressDemo -PstressShape=cube -PstressInstances=100000 -PstressMode=gpu -PstressFrames=300 -PstressHidden=true
```

运行 100000 个 cube（约 120 万个三角形）：

```powershell
.\gradlew.bat runStressCubeDemo
```

自动验证三个 100000-instance 场景：

```powershell
.\gradlew.bat runStressIntegration
```

直接运行 main class 时支持：

```text
--mode=gpu|dynamic
--shape=triangle|quad|cube
--instances=1..1000000
--vsync
--hidden
--frames=N
--deterministic
```

标题中的 `FPS` 是完成 swap 后的一秒采样值；`CPU` 是上传/命令提交时间；`GPU` 来自非阻塞 query ring；`state skip` 是状态缓存跳过比例。

- `gpu`（默认）：不创建 vertex/instance buffer。vertex shader 用 `gl_VertexID` 重建 triangle/quad/cube，用 `gl_InstanceID` 和整数位运算生成位置、旋转和颜色；每帧只有 uniforms + 一次 draw。
- `dynamic`：保留通用 `Matrix4f` instance API，用于对比 CPU 快照成本；底层使用 persistent coherent mapped 三槽 ring 和 fence，不再每帧分配 direct buffer。

立方体有 8 个唯一角点和 12 个三角形。标准 triangle pipeline 至少执行 36 个索引后的顶点调用；“6 顶点立方体”只能借助 geometry shader 扩展，而 geometry shader 通常会降低大规模实例吞吐，因此这里使用 shader 常量角点+索引查表。
