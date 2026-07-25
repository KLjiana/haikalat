# Milestone 4 API、性能与限制

本文记录 Haikalat 本体第四阶段的高级动画、GPU 特效实验、UI 动画诊断与综合场景事实。
不包含 HaikalatHost、Minecraft、模组事件、网络同步或宿主适配。

## API 边界

高级动画全部位于无 OpenGL 调用的 `subsystems.animation`：

- `AnimationEvent` 与 `AnimationClip.Builder.event(...)` 定义有序动作事件；
  `AnimationPlayer.pendingEvents()/drainEvents()` 按 `(previous, current]` 语义发布，循环边界会发布
  新周期的零时刻事件，并限制单次更新最多跨越 4096 个带事件周期。
- `BoneMask` 使用逐关节 0～1 权重，`subtree(...)` 根据真实父子链选择后代，不依赖数组排列。
- `PoseBlender` 支持目标缓冲与任一输入别名，平移/缩放线性插值，旋转使用归一化球面插值。
- `AnimationMixer` 提供 base + layer 双层混合、Bone Mask 和归一化时间同步；动作事件由两个
  `AnimationPlayer` 收集。
- `AnimationPlayer.updateWithRootMotion(...)` 返回模型空间 `RootMotionDelta`，支持跨循环累积并可把
  根关节恢复为绑定平移/旋转以进行原地播放。根运动关节必须是骨架根。
- `TwoBoneIkSolver` 接收模型空间 target、pole 和权重，支持非拓扑索引骨架、不可达距离夹取和部分权重。

实验性 GPU 接口仍分类为 `advanced`：

- `GpuParticleExperiment` 使用一个 32-byte/particle SSBO、64 线程 compute workgroup、显式
  `GL_SHADER_STORAGE_BARRIER_BIT` 和单次点批次绘制。容量范围为 1～1,048,576；生产路径没有 map、
  buffer readback 或 CPU 模拟镜像。
- `VolumetricLightPass` 对当前 framebuffer 执行 4～128 步的有界屏幕空间聚光积分，并在结束后恢复
  opaque/depth 状态。`VolumetricLightSettings` 防御性复制位置、方向和颜色。
- `UiAnimationHandle` 增加 pause/resume 与归一化进度；`UiAnimationDiagnostics` 记录 update、start、
  complete、cancel、replace、当前通道分布、暂停数和峰值并发，不引入非确定性 wall-clock 时间。

所有新增 public 顶层类型均登记在 `docs/architecture/public-api/*.allowlist`，架构测试继续禁止
`subsystems.animation`、`subsystems.vfx` 和 `subsystems.resources` 引用 backend 或直接调用 OpenGL。

## 自动化入口

```powershell
.\gradlew.bat test
.\gradlew.bat glSmoke --tests com.kaleblangley.haikalat.integration.GpuEffectsGlTest
.\gradlew.bat runShowcaseIntegration
.\gradlew.bat runShowcasePerformanceBaseline
.\gradlew.bat runShowcaseStabilityIntegration
.\gradlew.bat localMilestone4Verification
```

`HaikalatShowcaseDemo` 在同一帧图中执行动画混合/根运动/IK、PBR 与阴影、Bloom/LUT/雾、
体积光、CPU VFX、Compute 粒子和 retained UI。24 帧隐藏窗口入口还读取最终 backbuffer，要求实际像素、
每类 VFX、动作事件、根运动、GPU 帧数、体积帧数和 UI quad 同时存在。

## 2026-07-25 实测基线

测试环境：Windows、NVIDIA GeForce RTX 3050 Laptop GPU、驱动 576.88、OpenGL 4.6、JDK 21。
这些数据是单机受控基线，不是跨硬件性能承诺。

| 场景 | 尺寸 / 帧 | 负载 | CPU update 平均 | RenderGraph GPU 平均 | 结果 |
| --- | --- | --- | ---: | ---: | --- |
| 综合像素验收 | 640×360 / 24 | CPU VFX 72、GPU 粒子 256、体积 24 步 | 5.3876 ms | 4.7886 ms | 230,400 个非黑像素，资源关闭后归零 |
| 综合性能基线 | 960×540 / 360，预热 60 | CPU VFX 256、GPU 粒子 2,048、体积 24 步 | 0.7596 ms | 0.6088 ms | 9 个动作事件、47 段 Ribbon、4 个 Decal |
| 长时间稳定性 | 320×180 / 3,600，预热 120 | CPU VFX 256、GPU 粒子 256、体积 24 步 | 0.2066 ms | 0.2275 ms | 预热后资源序列集合与估算显存完全不变，退出后归零 |

综合像素入口的 CPU/GPU 时间包含首次运行预热影响；性能与稳定性入口分别排除了 60/120 帧预热。
`runVfxPerformanceBaseline` 的 512 粒子独立基线仍用于观察逐 primitive CPU VFX adapter 成本。

## 当前限制

- `AnimationMixer` 当前是双层混合器，不是可编辑状态机或通用 `AnimationGraph`；同步层通过归一化时间
  采样，未发布同步层 seek 过程中跨过的事件。
- 根运动只接受骨架根，返回模型空间平移与旋转；不处理导航碰撞、角色控制器、网络校正或宿主坐标系。
- Two-bone IK 只旋转直接的 root-middle-tip 链；不处理拉伸、关节角限制、非直接链、全身约束或迭代 IK。
- GPU 粒子是单 emitter 受控实验，没有透明排序、碰撞、深度软粒子、mesh particle、indirect compact、
  多发射器调度、序列化资产或 CPU fallback；正式通用 VFX 仍以 `EffectAsset/EffectInstance` 为准。
- 体积光未采样场景深度或阴影图，也没有 temporal reprojection、分辨率缩放、多个 volume 或物理介质管理；
  它证明固定预算积分和组合状态，不等同于完整体积雾系统。
- UI 动画仍只有 visual/layout 两个互斥通道；没有 sequence graph、共享元素、关键帧编辑器或宿主时间轴。
- 默认方向光运行时仍使用单张阴影图；`DirectionalCascadePlan` 提供经过测试的分割与稳定矩阵方案，
  但完整级联纹理数组、选择与混合尚未接入默认 renderer。
