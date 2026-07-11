# learnopengl

[English](README.md) | [简体中文](README.zh-CN.md)

这是一个基于 Java、LWJGL 和 OpenGL 的学习型实时渲染项目，内部划分为 `backend`、
`core`、`subsystems` 和 `runtime` 四个主要区域。

项目目标是在“教学实验”和“工程约束”之间保持平衡：它需要足够稳定，以验证渲染架构和资源生命周期；
同时保持规模可控，不扩张成完整游戏引擎。当前目标、能力边界和非目标参见
[`docs/planning/project-goals.md`](docs/planning/project-goals.md)，后续工作参见
[`docs/planning/future-plans.md`](docs/planning/future-plans.md)。核心能力的实现状态和验证入口参见
[`docs/planning/capability-matrix.md`](docs/planning/capability-matrix.md)。

## 当前能力

- OpenGL 后端资源：shader、buffer、texture、sampler、framebuffer、vertex array、uniform block、
  GPU fence、状态缓存和错误报告。
- 核心渲染协议：命令记录、渲染设备、RenderGraph、mesh layout、instancing、上传流程、
  不可变帧快照和材质系统。
- Forward 3D 场景管线，包含基础 Blinn-Phong 光照、固定分辨率方向光阴影、3x3 PCF，以及
  none、MSAA、FXAA、TAA 后处理路径。
- 资产辅助能力：classpath 资源定位、shader asset、纹理缓存、`.properties` 场景配置，
  以及已接入主 Demo 的 OBJ 模型链路；Assimp 入口仍为实验能力。
- 三条 Demo 证明路径：综合场景管线、最小窗口与命令流、异步更新与渲染线程协作。

主 Demo 是稳定基准场景，包含 receiver、投射/不投射阴影的对象、纹理/纯色材质、方向光、点光和实例化对象。

## 环境要求

- JDK 21 或更高版本。
- 支持 OpenGL 4.6 Core Profile 的 GPU 和驱动。
- Windows 或 Linux 桌面环境。
- Demo 会创建 GLFW 窗口，因此不能在没有桌面图形环境的会话中直接运行。

## 构建与测试

默认测试是纯 JVM/单元检查，不需要桌面 OpenGL context：

```powershell
.\gradlew.bat compileJava test
```

CI 使用相同的非窗口路径，并额外编译 Demo 源码：

```powershell
.\gradlew.bat compileJava demoClasses test
```

真实 GL smoke test 会创建隐藏的 GLFW 窗口，验证最小 GL 工作和资源生命周期：

```powershell
.\gradlew.bat test "-Dhaikalat.glSmoke=true" --rerun-tasks
```

隐藏窗口运行固定 8 帧基准场景：

```powershell
.\gradlew.bat runDemoIntegration
```

执行 resize、异步渲染线程和全部本地 GL 验收：

```powershell
.\gradlew.bat runDemoResizeIntegration
.\gradlew.bat runAsyncIntegration
.\gradlew.bat localGlVerification
```

测试分层和适用范围参见 [`docs/guides/testing.md`](docs/guides/testing.md)。

## 运行 Demo

在 IDE 中导入 Gradle 项目，然后直接运行对应的 `main` 方法：

- 综合场景：`com.kaleblangley.haikalat.demo.LearnOpenGlDemo`
- 最小渲染检查：`com.kaleblangley.haikalat.demo.MinimalDemo`
- 异步上传与渲染线程：`com.kaleblangley.haikalat.demo.async.AsyncDemo`

Demo 源码位于 `src/demo/java`，资源位于 `src/demo/resources`。

## 文档

完整文档导航参见 [`docs/README.md`](docs/README.md)。文档按以下类别组织：

- `docs/planning`：目标、路线和开发任务。
- `docs/architecture`：渲染边界和抽象审计。
- `docs/guides`：测试与开发指南。
- `docs/history`：变更记录。
