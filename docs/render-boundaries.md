# Render Boundary Contracts

本文件记录当前稳定化阶段保留的最小边界，避免继续扩张薄抽象。

## RenderDevice

`RenderDevice` 只表示命令提交边界：

- `backendKind()` 标识当前后端。
- `executionModel()` 标识立即式或未来显式提交模型。
- `createCommandBuffer()` 创建当前后端可执行的命令缓冲。
- `execute(CommandBuffer)` 提交并执行命令。
- `transition(ResourceBarrier...)` 保留资源状态转换协议，OpenGL 后端当前为空实现。

`RenderDevice` 不暴露 `StateCache`、framebuffer invalidation、资源创建 factory 或具体 GL 对象。需要这些能力时应使用具体 OpenGL 类型，或等第二个后端实验证明边界后再提升到接口。

## RenderGraph

`RenderGraph` 只负责三类职责：

- Pass：注册 pass、声明依赖、拓扑排序、执行 pass executor。
- Resource：根据 pass 声明创建、查询、resize、释放 render target。
- Profile：记录 pass 级 CPU/GPU 时间，并输出 `FrameProfile`。

`RenderGraph` 不负责场景遍历、材质绑定、光照模型、shader variant、asset loading 或 demo 输入逻辑。

## Demo Proof

当前保留一个综合 demo 作为能力证明：

- `LearnOpenGlDemo`：读取标准 `.properties` 场景配置，运行 scene pipeline，默认启用 FXAA postprocess，并通过配置中的 shadow-casting directional light 创建 shadow pass。

专项 demo 保留为调试入口：

- `MinimalDemo`：验证最小窗口、命令提交和实例化批处理。
- `AsyncDemo`：验证异步更新、上传队列和 render thread 交互。

## GL Smoke Verification

默认单元测试不要求真实 OpenGL context。需要验证本机图形环境时运行：

```powershell
.\gradlew.bat test "-Dhaikalat.glSmoke=true" --rerun-tasks
```

该 smoke test 会创建隐藏 GLFW 窗口，初始化 GL capabilities，执行 clear，并用 `glReadPixels` 验证回读像素。
