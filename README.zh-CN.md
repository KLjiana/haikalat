# learnopengl

[English](README.md) | [简体中文](README.zh-CN.md)

这是一个基于 Java、LWJGL 和 OpenGL 的学习型实时渲染项目，内部划分为 `backend`、
`core`、`subsystems` 和 `runtime` 四个主要区域。
依赖方向固定为 `subsystems/runtime -> core -> backend`，底层不反向引用上层。

项目目标是在“教学实验”和“工程约束”之间保持平衡：它需要足够稳定，以验证渲染架构和资源生命周期；
同时保持规模可控，不扩张成完整游戏引擎。当前目标、能力边界和非目标参见
[`docs/planning/project-goals.md`](docs/planning/project-goals.md)，后续工作参见
[`docs/planning/future-plans.md`](docs/planning/future-plans.md)。核心能力的实现状态和验证入口参见
[`docs/planning/capability-matrix.md`](docs/planning/capability-matrix.md)。

## 当前能力

- OpenGL 后端资源：支持图形/计算各 stage 的 shader、buffer、texture、sampler、framebuffer、vertex layout/array、render format、uniform/SSBO block、
  GPU fence/timer、状态缓存和错误报告。
- 核心渲染协议：命令记录、渲染设备、RenderGraph、mesh data、instancing、上传流程、
  不可变帧快照和材质系统。
- 无 GL 依赖的共享曲线基础：cubic-bezier easing、Hermite 属性轨道、HDR 颜色渐变、
  固定弧长表三次 Bezier 空间路径和曲线 LUT。
- Forward 3D 场景管线，包含基础 Blinn-Phong 光照、普通/实例化方向光阴影、3x3 PCF、
  显式 linear/sRGB 纹理、线性 HDR/ACES 色调映射、可选多级 Bloom，以及 none、MSAA、FXAA、TAA 路径。
- 资产辅助能力：classpath 资源定位、shader asset、纹理缓存、`.properties` 场景配置，
  以及已接入主 Demo 的 OBJ 模型链路和专用 Demo 验证的静态/蒙皮 glTF 2.0 链路。
- 无 GL 依赖的骨架动画 subsystem：任意索引层次、STEP/LINEAR/CUBICSPLINE、双层/Bone Mask 混合、base cross-fade、layer fade、归一化动作同步、有序动作事件、跨循环根运动和 Two-bone IK；glTF skin/animation、每实例 joint palette、PBR forward 与方向光 shadow 四影响 GPU 蒙皮均已闭环。
- OpenGL 4.6 启动与提交能力契约：Core Profile、DSA、SSBO、Compute、Image Load/Store、Buffer Storage、MDI、Shader Draw Parameters 和 debug output 缺一即明确终止，不走旧版降级。
- 无 GL 依赖的通用资源基础：规范化 `AssetId`、有界 directory/classpath source、逐资源 generation，以及能拒绝过期结果的异步 CPU 解码协议。
- 后处理新增 tiled 2D Color Grading LUT，以及根据场景深度重建世界坐标的距离/高度雾，并覆盖确定性像素与 resize 验证。
- UI 新增无 GL 依赖的 visual/layout Tween 与 Transition，支持 linear/cubic/spring easing、延迟、取消和同通道替换。
- 无 GL 依赖的确定性 VFX subsystem：有界粒子、Ribbon、Decal、over-life 和纯值纹理材质；render3d adapter 支持 R8/sRGB mask、Alpha/Additive emissive、billboard/stretch、场景深度 soft particle，以及 tone mapping 前的 HDR/Bloom 合成，UI 保持在 Bloom 之外。
- OpenGL 4.6 受控实验：无 CPU readback 的 Compute/SSBO GPU 粒子，以及固定 4～128 步的屏幕空间体积聚光；真实像素和资源稳定性均有自动验证。
- 阴影支持方向光、3×2 六面点光 atlas 和聚光 depth map，并提供 practical split、world-texel 稳定的方向光级联方案。
- Demo 证明路径包括空窗口/present 基线、综合场景管线、最小命令流、CPU 骨架动画、异步更新与渲染线程协作、Milestone 4 动画/PBR/后处理/VFX/UI 同帧场景，以及自动生成 GPU procedural shader 的 100 万实例压力分析。
- 有界运行时诊断：帧/pass 样本身份、RenderGraph 检查、结构化 GL 消息、资源追踪、F2 retained UI 面板、冻结历史和确定性 schema-v1 JSON 导出。

主 Demo 是稳定基准场景，包含 receiver、普通与实例化 caster、纹理/纯色材质、方向光、点光，以及显式启用的 ACES HDR/FXAA。

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
.\gradlew.bat runAnimationIntegration
.\gradlew.bat runGltfSkinningIntegration
.\gradlew.bat runPostProcessEffectsIntegration
.\gradlew.bat runVfxIntegration
.\gradlew.bat runVfxCurveBenchmark
.\gradlew.bat runShowcaseIntegration
.\gradlew.bat runShowcaseStabilityIntegration
.\gradlew.bat runLocalShadowsIntegration
.\gradlew.bat runAsyncIntegration
.\gradlew.bat localGlVerification
.\gradlew.bat localDiagnosticsVerification
```

主 Demo 中按 F2 打开诊断面板；自动 capture 和五轮开销对比使用：

```powershell
.\gradlew.bat runDiagnosticsIntegration
.\gradlew.bat runDiagnosticsBenchmarks
```

交互查看 Bloom 或执行五轮正式性能基准：

```powershell
.\gradlew.bat runBloomDemo
.\gradlew.bat runPostV08Benchmarks
```

测试分层和适用范围参见 [`docs/guides/testing.md`](docs/guides/testing.md)。

## 运行 Demo

在 IDE 中导入 Gradle 项目，然后直接运行对应的 `main` 方法：

- 综合场景：`com.kaleblangley.haikalat.demo.LearnOpenGlDemo`
- 不执行 clear/draw 的空窗口：`com.kaleblangley.haikalat.demo.EmptyWindowDemo`
- 最小渲染检查：`com.kaleblangley.haikalat.demo.MinimalDemo`
- CPU 骨架动画：`com.kaleblangley.haikalat.demo.animation.AnimationDemo`
- 静态/蒙皮 glTF 与 PBR：`com.kaleblangley.haikalat.demo.gltf.GltfDemo`
- PBR、颜色分级与雾：`com.kaleblangley.haikalat.demo.pbr.PbrDemo`
- 粒子、Ribbon 与 Decal VFX：`com.kaleblangley.haikalat.demo.vfx.VfxDemo`
- 动画/PBR/后处理/CPU+GPU VFX/UI 综合验收：`com.kaleblangley.haikalat.demo.pbr.HaikalatShowcaseDemo`
- 异步上传与渲染线程：`com.kaleblangley.haikalat.demo.async.AsyncDemo`

Demo 源码位于 `src/demo/java`，资源位于 `src/demo/resources`。

## 文档

完整文档导航参见 [`docs/README.md`](docs/README.md)。文档按以下类别组织：

- `docs/planning`：目标、路线和开发任务。
- `docs/architecture`：渲染边界和抽象审计。
- `docs/guides`：测试与开发指南。
- `docs/history`：变更记录。

## 发布制品与许可证

执行 `./gradlew jar` 会在 `build/libs` 同时生成 `haikalat-<version>.jar` 和
`haikalat-<version>-sources.jar`。两个归档均在 `META-INF/LICENSE` 中携带项目许可证。

Haikalat 采用 GNU Affero General Public License v3.0 only（
[AGPL-3.0-only](LICENSE)）。随项目分发的第三方资产和依赖继续遵循
[`docs/licenses`](docs/licenses) 中记录的各自许可证。
