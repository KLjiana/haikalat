---

# Haikalat 渲染系统评审：7/10 的根因与改进路线

## 总体评估

**评分：7/10**

架构方向正确——分层清晰、RAII 统一、CommandBuffer + StateCache + RenderGraph 三板斧站位准确。丢的 3 分主要在**功能深度不足**和**成熟度不够**两个维度。

---

## 一、分数拆解

| 维度 | 满分 | 得分 | 说明 |
|---|---|---|---|
| 架构分层 | 2 | **2** | 单向依赖、包结构合理 |
| 资源管理 | 2 | **1.5** | RAII 统一，但 Material 所有权模糊、无 BufferPool |
| 渲染抽象 | 2 | **1.5** | CommandBuffer + RenderGraph 设计正确，但缺拓扑排序、多线程验证 |
| 性能优化 | 2 | **1** | StateCache 有效，但 InstanceBufferRing 降级无 fence、无 UBO、无实例合并 |
| 健壮性 | 1 | **0.5** | GlDebug 只取一个 error、cmd.custom() 脆弱依赖 ID 差异恢复、无 validation layer |
| 可扩展性 | 1 | **0.5** | 接口清晰但缺多后端抽象、Vulkan/Metal 迁移需要全部重写 RenderDevice |

---

## 二、模块逐项分析

### gl/command/ — 命令系统

| 问题 | 严重度 | 说明 |
|---|---|---|
| `cmd.custom()` 与 StateCache 无同步 | 中 | 绕过 StateCache 的 GL 调用不更新缓存。侥幸靠资源 ID 不同恢复，但布尔态（blend/depth）没保障 |
| Uniform 位置立即解析 | 低 | `setUniformMat4` 在录制时调 `uniformLocation()`，若 shader 未 link 会提前抛错 |
| 多线程未验证 | **高** | `GlRenderThread` + `RenderCommandQueue` 存在但 Demo 全程单线程，异步路径从来没人跑过 |
| 无命令类型抽象 | 中 | 全部 `Consumer<StateCache>` lambda，无法做命令分析/重排/合并 |

### gl/render/ — 渲染管线

| 问题 | 严重度 | 说明 |
|---|---|---|
| RenderGraph 无拓扑排序 | 中 | 纯注册顺序执行。两个 Pass 读同一纹理时依赖关系全凭开发者自觉 |
| TAA 路径未测试 | **高** | `AntiAliasPipeline.record()` 的 TAA 分支只在代码里存在，Demo 始终用 NONE |
| 无 Resource Barrier | 中 | Vulkan 迁移时这是致命问题。当前靠 GL 隐式同步掩盖 |
| 无 Pass 条件跳过 | 低 | 每帧执行全部 Pass，不能根据场景动态启停 |

### gl/material/ — 材质系统

| 问题 | 严重度 | 说明 |
|---|---|---|
| 无 UBO | **高** | 所有 uniform 逐 draw 调 `glUniform*`。1000+ draw call 时 CPU 侧 uniform 设置成为瓶颈 |
| Uniform 值无类型安全 | 中 | `Map<String, Object>` + `instanceof` 分发，编译器不会检查传错了类型 |
| Material 所有权模糊 | 低 | `close()` 默认不关 shader/texture，除非 `.ownResources()` |
| 无 Sampler 抽象 | 中 | 滤波/wrap 参数绑在 Texture2D 上，不能同一纹理用不同采样方式 |

### gl/mesh/ — 网格数据

| 问题 | 严重度 | 说明 |
|---|---|---|
| 非交错网格 vertexCount 无校验 | 中 | `.attribute()` 两次传不同长度不会在 build() 报错 |
| InstancedMeshBatch 只有单 Mesh | 中 | 多 Mesh 同批实例化需要多个 batch 对象 |
| 无批量合并 | 低 | 无 API 把多个小 Mesh 合并到一个大 VBO |

### gl/buffer/ — 缓冲管理

| 问题 | 严重度 | 说明 |
|---|---|---|
| InstanceBufferRing 无同步 | **高** | 删除 persistent mapping 后纯靠三重缓冲"赌" GPU 不落后 3 帧。极端帧率波动会撕裂 |
| UploadQueue 无人用 | 中 | 代码库零调用点 |

### gl/fb/ + gl/camera/

| 问题 | 严重度 | 说明 |
|---|---|---|
| Camera 字段全 public | 中 | 外部 `position.set()` 绕过 `updateCameraVectors()` |
| Framebuffer 不支持 MRT | 中 | 无多 color attachment |

### gl/ 根包

| 问题 | 严重度 | 说明 |
|---|---|---|
| GlDebug 只消费一个 error | 中 | `glGetError()` 调一次，累积多个只报第一个 |
| 无 GPU 计时 | 低 | `RenderStatistics` 只有 CPU `nanoTime` |
| 无 GL debug output | 中 | OpenGL 4.3+ 的 `glDebugMessageCallback` 可实时报错，比事后 `glGetError` 快 |

---

## 三、优先级改进路线

```
Phase A（即刻）
  ├── Camera 字段封装
  ├── GlDebug 改为 while 循环消费所有 error
  └── UploadQueue 接入 RenderLoop + Demo 演示

Phase B（1-2 周）
  ├── InstanceBufferRing 恢复持久映射（ARB_buffer_storage + glFlushMappedBufferRange）
  ├── RenderGraph 添加 .dependsOn() + 拓扑排序
  ├── Material Uniform 改为 sealed interface
  ├── UBO 支持
  └── GlRenderThread 异步 Demo

Phase C（长期）
  ├── Multi-Mesh InstancedBatch
  ├── Framebuffer MRT
  ├── GpuTimer + GL debug output callback
  ├── Shader Variant 系统
  └── Vulkan 后端抽象（RenderDevice → 接口）
```

---
