# Haikalat 架构收敛计划书

## 一、当前状态诊断

### 1.1 已完成的工作

| 阶段 | 产出 |
|---|---|
| Phase 1-2 | CommandBuffer + StateCache + RenderDevice + Material + Mesh 非交错 |
| Phase 3 | RenderGraph（声明式 Pass + 拓扑排序） |
| Phase 4 | InstanceBufferRing + UploadSystem 批处理上传 |
| Phase 5 | AntiAliasPipeline + FXAA/TAA 全接入 CommandBuffer |
| Phase A | Camera 封装、GlDebug 全量+回调 |
| Phase B | GlRenderThread 现代化 + GlfwWindow + AsyncDemo 双层 TripleBuffer |
| Phase C | GpuTimer + GL debug output + Multi-Mesh Instanced + Framebuffer MRT + RenderDevice→接口 |
| 清理 | 删除 BatchedRenderQueue / DrawSortKey / RenderCommand / RenderCommandQueue，精简 ScreenQuad |

### 1.2 现状评估

**已接近架构收敛，差一步——当前包结构不反映层级关系。**

当前 `gl/render/` 是混乱的：窗口（GlfwWindow）、线程（GlRenderThread）、管线（RenderGraph）、后处理（FXAA/TAA）、帧调度（RenderLoop）全挤在一起。"gl 封装"和"引擎功能"混在一个包树下，没有分层。

### 1.3 目标四层架构

```
Haikalat Engine
│
├── backend/          ← 零渲染语义，纯 GL 能力封装
│   ├── buffer/       GlBuffer
│   ├── sync/         GpuFence
│   ├── framebuffer/  Framebuffer
│   ├── state/        StateCache
│   ├── shader/       ShaderProgram
│   ├── texture/      Texture2D
│   ├── vertex/       VertexArray
│   ├── UniformBlock
│   ├── GlResource / GlException / GlDebug
│
├── core/             ← 渲染调度 + 数据抽象
│   ├── command/      CommandBuffer
│   ├── device/       RenderDevice (interface) + GlRenderDevice (impl)
│   ├── graph/        RenderGraph + PassResources
│   ├── material/     Material + MaterialInstance + UniformValue
│   ├── mesh/         Mesh + VertexAttribute + VertexLayout + VertexPacking + InstancedMeshBatch
│   ├── buffer/       InstanceBufferRing + TripleBuffer
│   ├── upload/       UploadSystem
│   ├── BlendMode / AntiAliasingMode
│
├── subsystems/       ← 具体绘制逻辑（不直接碰 GL，通过 CommandBuffer）
│   ├── render3d/     Camera + SceneObject + InstancedRenderer + RenderPipeline
│   │                 AntiAliasPipeline + ScreenQuad
│   └── postprocess/  FxaaPostProcessor + TemporalAccumulationPass
│
├── runtime/          ← 线程 + 帧驱动 + 窗口
│   ├── RenderLoop.java       帧驱动
│   ├── GlRenderThread.java   渲染线程
│   ├── GlfwWindow.java       异步窗口（无 Camera）
│   ├── GpuTimer.java         GPU 耗时
│   ├── RenderSettings.java   渲染配置
│   └── RenderStatistics.java 帧统计
│
├── demo/             ← 单线程演示（不变）
│   ├── AppWindow.java
│   ├── LearnOpenGlDemo.java
│   ├── MinimalDemo.java
│   └── async/        ← 多线程演示（不变）
│       ├── AsyncDemo.java
│       └── FrameState.java
│
└── util/             ← 不变
    └── DirectBuffers.java
```

### 1.4 四层职责冻结

| 层 | 职责 | 禁止做的事 |
|---|---|---|
| **backend** | GPU 能力：创建/绑定/销毁 GL 对象。零渲染语义。 | 不做 blend/depth 设置、不调度 Pass、不管理帧结构 |
| **core** | 渲染调度 + 数据抽象：CommandBuffer 录制、RenderGraph 编排、Material/Mesh 定义 | 不直接调 GL（通过 CommandBuffer → backend 链路） |
| **subsystems** | 具体绘制逻辑：3D 相机、场景对象、后处理 Pass。用 core 的抽象组装渲染内容 | 不直接调 GL（通过 CommandBuffer）、不创建 GL 资源（通过 core 的抽象） |
| **runtime** | 线程 + 帧驱动 + 窗口：窗口生命周期、渲染线程管理、帧统计 | 不包含具体的绘制逻辑 |

---

## 二、四层文件迁移表

### backend/（~10 个文件）

| # | 当前路径 | → 目标路径 | 理由 |
|---|---|---|---|
| 1 | `gl/buffer/GlBuffer.java` | `backend/buffer/GlBuffer.java` | 纯 VBO/EBO 封装 |
| 2 | `gl/buffer/GpuFence.java` | `backend/sync/GpuFence.java` | 纯 GL fence |
| 3 | `gl/fb/Framebuffer.java` | `backend/framebuffer/Framebuffer.java` | 纯 FBO 封装 |
| 4 | `gl/command/StateCache.java` | `backend/state/StateCache.java` | 纯 GL 状态缓存 |
| 5 | `gl/material/ShaderProgram.java` | `backend/shader/ShaderProgram.java` | 纯 GLSL 编译链接 |
| 6 | `gl/material/Texture2D.java` | `backend/texture/Texture2D.java` | 纯纹理加载 |
| 7 | `gl/mesh/VertexArray.java` | `backend/vertex/VertexArray.java` | 纯 VAO 封装 |
| 8 | `gl/material/UniformBlock.java` | `backend/UniformBlock.java` | 纯 UBO 封装 |
| 9 | `gl/GlResource.java` | `backend/GlResource.java` | 所有 GPU 对象的根接口 |
| 10 | `gl/GlException.java` | `backend/GlException.java` | GL 错误异常 |
| 11 | `gl/GlDebug.java` | `backend/GlDebug.java` | GL 错误检查+回调 |

### core/（~17 个文件）

| # | 当前路径 | → 目标路径 | 理由 |
|---|---|---|---|
| 12 | `gl/command/CommandBuffer.java` | `core/command/CommandBuffer.java` | 渲染语义录制 |
| 13 | `gl/command/RenderDevice.java` | `core/device/RenderDevice.java` | 设备抽象接口 |
| 14 | `gl/command/GlRenderDevice.java` | `core/device/GlRenderDevice.java` | GL 实现 |
| 15 | `gl/render/RenderGraph.java` | `core/graph/RenderGraph.java` | Pass 调度+拓扑排序 |
| 16 | `gl/render/PassResources.java` | `core/graph/PassResources.java` | Graph 内资源查询 |
| 17 | `gl/material/Material.java` | `core/material/Material.java` | 材质（暂不拆） |
| 18 | `gl/material/MaterialInstance.java` | `core/material/MaterialInstance.java` | 材质实例 |
| 19 | `gl/material/UniformValue.java` | `core/material/UniformValue.java` | sealed 类型化 uniform |
| 20 | `gl/mesh/Mesh.java` | `core/mesh/Mesh.java` | 网格 |
| 21 | `gl/mesh/VertexAttribute.java` | `core/mesh/VertexAttribute.java` | 顶点属性描述 |
| 22 | `gl/mesh/VertexLayout.java` | `core/mesh/VertexLayout.java` | 顶点布局 |
| 23 | `gl/mesh/VertexPacking.java` | `core/mesh/VertexPacking.java` | 顶点数据压缩 |
| 24 | `gl/mesh/InstancedMeshBatch.java` | `core/mesh/InstancedMeshBatch.java` | 实例化批处理 |
| 25 | `gl/buffer/InstanceBufferRing.java` | `core/buffer/InstanceBufferRing.java` | 实例缓冲环 |
| 26 | `gl/buffer/TripleBuffer.java` | `core/buffer/TripleBuffer.java` | 三重缓冲（线程安全数据交换） |
| 27 | `gl/buffer/UploadSystem.java` | `core/upload/UploadSystem.java` | CPU→GPU 上传调度 |
| 28 | `gl/BlendMode.java` | `core/BlendMode.java` | 混合模式枚举 |
| 29 | `gl/AntiAliasingMode.java` | `core/AntiAliasingMode.java` | AA 模式枚举 |

### subsystems/（~8 个文件）

| # | 当前路径 | → 目标路径 | 理由 |
|---|---|---|---|
| 30 | `gl/camera/Camera.java` | `subsystems/render3d/Camera.java` | 3D FPS 相机 |
| 31 | `demo/SceneObject.java` | `subsystems/render3d/SceneObject.java` | 场景对象 |
| 32 | `demo/InstancedRenderer.java` | `subsystems/render3d/InstancedRenderer.java` | 实例化渲染器 |
| 33 | `demo/RenderPipeline.java` | `subsystems/render3d/RenderPipeline.java` | 渲染管线构建 |
| 34 | `gl/render/AntiAliasPipeline.java` | `subsystems/render3d/AntiAliasPipeline.java` | AA 管线 |
| 35 | `gl/render/ScreenQuad.java` | `subsystems/render3d/ScreenQuad.java` | 全屏四边形 |
| 36 | `gl/render/FxaaPostProcessor.java` | `subsystems/postprocess/FxaaPostProcessor.java` | FXAA 后处理 |
| 37 | `gl/render/TemporalAccumulationPass.java` | `subsystems/postprocess/TemporalAccumulationPass.java` | TAA 后处理 |

### runtime/（~6 个文件）

| # | 当前路径 | → 目标路径 | 理由 |
|---|---|---|---|
| 38 | `gl/render/RenderLoop.java` | `runtime/RenderLoop.java` | 帧驱动 |
| 39 | `gl/render/GlRenderThread.java` | `runtime/GlRenderThread.java` | 渲染线程 |
| 40 | `gl/render/GlfwWindow.java` | `runtime/GlfwWindow.java` | GLFW 窗口（异步 Demo 用） |
| 41 | `gl/render/GpuTimer.java` | `runtime/GpuTimer.java` | GPU 耗时测量 |
| 42 | `gl/RenderSettings.java` | `runtime/RenderSettings.java` | 渲染配置 |
| 43 | `gl/RenderStatistics.java` | `runtime/RenderStatistics.java` | 帧统计 |

### 不动

| 当前路径 | 理由 |
|---|---|
| `demo/AppWindow.java` | 单线程 Demo 专供，带内嵌 Camera + run() 回调 |
| `demo/LearnOpenGlDemo.java` | 单线程入口 |
| `demo/MinimalDemo.java` | 最小验证入口 |
| `demo/async/FrameState.java` | 异步 Demo 状态载体 |
| `demo/async/AsyncDemo.java` | 异步 Demo 入口 |
| `util/DirectBuffers.java` | 纯工具类 |

---

## 三、分步执行计划

### Step 0：四层文件迁移（一次性批量）

**预期：** 所有源文件按四层模型重新放置，包结构从扁平 `gl/` 变为四层树。

**做法：**

1. 创建 `backend/`、`core/`、`subsystems/`、`runtime/` 及其所有子目录
2. 按迁移表逐文件移动（用 Git `mv` 保留历史）
3. 更新每个文件的 `package` 声明
4. 批量替换 import 路径（约 200+ 行——按旧→新路径映射全局替换）

**改动量：** ~43 个文件移动，~200 行 import 更新。

**风险：** 中高。一次性改动大，但自动化程度高。分两个子步骤：
- A：移动文件 + 更新 package 声明
- B：全局替换 import 路径

每步编译一次，确保不垮。

**时间：** 2-3 小时。

---

### Step 1：RenderLoop 内嵌 RenderGraph（消除"双调度器"）

**预期：** 调用者不再手动串 `beginFrame → graph.execute → endFrame`。`RenderLoop` 内部驱动 `RenderGraph`。

**做法：**

```java
// 旧（调用者手动串）
renderLoop.beginFrame();
graph.execute(device);
renderLoop.endFrame();

// 新（RenderLoop 驱动）
renderLoop.frame(graph);  // 内部：beginFrame → graph.execute → endFrame
```

**改动范围：**
- `RenderLoop` 新增 `frame(RenderGraph graph)` 方法
- 修改 `LearnOpenGlDemo`：`window.run` 内简化为 `renderLoop.frame(pipeline.graph())`
- `RenderPipeline` 暴露 `graph()` 访问器
- `GlRenderThread` 的 `runLoop` 保持不变（它不直接使用 RenderGraph）

**风险：** 低。只改变调用方式，不改变语义。

**时间：** 30 分钟。

---

### Step 2：冻结边界（文档化）

**预期：** 四层职责通过注释固化为契约。

**做法：**

在每个关键类的 Javadoc 顶部添加层归属声明：

```java
/**
 * Layer: BACKEND — GPU capability only.
 * Wraps an OpenGL buffer object. Does not interpret rendering semantics.
 */
public final class GlBuffer implements GlResource { ... }
```

层标签统一为：`BACKEND` / `CORE` / `SUBSYSTEM` / `RUNTIME`。

**改动范围：** 所有层的根目录关键类（约 30 个文件，每类加 1-2 行）。

**风险：** 零。纯注释。

**时间：** 30 分钟。

---

### Step 3：命名规范化（甜点，非必须）

**预期：** 类名反映实际职责。

| 旧名              | 新名                            | 理由                                             |
|-----------------|-------------------------------|------------------------------------------------|
| `RenderLoop`    | `FrameDriver`                 | 不叫 Loop——它驱动一帧的生命周期（upload→render→stats→debug） |
| `Material` → 保留 | 已拆为 Template + State（后续 Plan） | `Material` 废弃后删除                               |

**改动范围：** `RenderLoop.java` → `FrameDriver.java`，修改约 4 个引用处。

**风险：** 低。IDE rename 一键完成。

**时间：** 15 分钟。

---

## 四、后续 Plan（独立于本次收敛）

| 计划                         | 内容                                             | 依赖               |
|----------------------------|------------------------------------------------|------------------|
| Material Template + State  | Material 拆为不可变模板 + 可变状态，避免"接近 pipeline object" | Step 0 完成后       |
| RenderGraph + FXAA/TAA 真合体 | 后处理直接作为 Graph Pass，不通过 `cmd.custom()` 绕路       | Step 1 完成后       |
| UploadQueue 彻底删除           | 旧 `@Deprecated` UploadQueue 移除                 | UploadSystem 稳定后 |

---

## 五、时间线

| 步骤                 | 预计改动文件数 | 预计时间   | 阻塞项   |
|--------------------|---------|--------|-------|
| Step 0A（文件移动）      | ~43     | 1.5 小时 | 无     |
| Step 0B（import 修复） | ~43     | 1 小时   | 0A 完成 |
| Step 1             | 3       | 30 分钟  | 0B 完成 |
| Step 2             | ~30     | 30 分钟  | 无     |
| Step 3             | ~5      | 15 分钟  | 无     |

**总计：约 3.5 小时。**

---

## 六、收敛后最终架构图

```
┌─────────────────────────────────────────────────────┐
│  subsystems/                                        │
│  render3d/  postprocess/                            │
│  Camera     FxaaPostProcessor                       │
│  SceneObj   TemporalAccumulationPass                │
│  InstancedRenderer  AntiAliasPipeline               │
│  RenderPipeline  ScreenQuad                         │
│                                                     │
│        ↓ (uses)                                     │
├─────────────────────────────────────────────────────┤
│  core/                                              │
│  command/  device/  graph/  material/  mesh/        │
│  CommandBuffer  RenderDevice  RenderGraph           │
│  Material  MaterialInstance  Mesh  InstancedBatch   │
│  buffer/  upload/  BlendMode  AntiAliasingMode      │
│  InstanceBufferRing  TripleBuffer  UploadSystem     │
│                                                     │
│        ↓ (records into → executes through)           │
├─────────────────────────────────────────────────────┤
│  backend/                                           │
│  buffer/  sync/  framebuffer/  state/               │
│  shader/  texture/  vertex/  UniformBlock           │
│  GlResource  GlException  GlDebug                   │
│                                                     │
│        ↓ (issues GL calls)                          │
│  OpenGL ═══════════════════════════════════════════ │
└─────────────────────────────────────────────────────┘

                    ┌──────────────────┐
                    │   runtime/       │
                    │   RenderLoop     │  ← 帧驱动
                    │   GlRenderThread  │  ← 渲染线程
                    │   GlfwWindow     │  ← 窗口
                    │   GpuTimer       │
                    │   RenderSettings │
                    │   RenderStats    │
                    └──────────────────┘
```

---

## 七、不改的东西

| 模块                         | 原因                                      |
|----------------------------|-----------------------------------------|
| `CommandBuffer`            | 核心正确，稳定                                 |
| `StateCache`               | 核心正确，稳定                                 |
| `RenderGraph`              | 已收敛，编译/拓扑/执行逻辑完整                        |
| `UploadSystem`             | 批处理合并逻辑正确                               |
| `GlfwWindow` / `AppWindow` | 两个窗口类各司其职（Async vs Sync）                |
| `GlRenderThread`           | onInit/onCleanup/CompletableFuture 模式稳定 |
| `TripleBuffer`             | 数据交换核心，验证通过                             |
| 所有 Demo                    | 仅更新 import，不改变渲染逻辑                      |
