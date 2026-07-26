# Animation Runtime、约束与 Morph 使用边界

v0.18.3 在保留 `AnimationPlayer/AnimationMixer` 的前提下新增确定性 Graph runtime。共享
definition 与逐角色 state 分离：`AnimationGraph`、clip、Blend Tree、mask 和 marker 可共享；
`AnimationController`、`AnimationLayerStack`、parameter/trigger、cursor、signal queue、
`PoseBuffer` 与 `MorphWeightBuffer` 必须由角色实例持有。

## 求值顺序

推荐每个固定 update tick 使用同一顺序：

1. 写入 float/int/bool parameter，最后 fire trigger。
2. 调用 controller 或 layer stack 求值得到 local pose、Morph weights 和 root motion。
3. 按固定声明顺序运行 constraint stack。
4. 更新 global matrices/joint palette。
5. drain `AnimationSignal`，由 application bridge 驱动 VFX、UI 或声音。

Graph 支持 Clip、1D Blend Tree 和显式 triangle 2D Blend Tree。Transition 支持 exit time、
destination offset、queued 与 source/destination/any interruption。Trigger 只有在对应 transition
真正被选中时才消费。负 playback speed 同样适用于 state/child；Event、Marker 与 root motion
在倒放和跨 loop 时保持确定顺序。

`AnimationLayerStack` 最多 8 层，支持 Override/Additive、Bone Mask、fade、pause、自动移除和句柄式
控制。Additive pose 相对 reference pose 求 delta；Morph 同样使用
`sampledWeight - referenceWeight`。只有 full-body layer 可以选择 override root motion，其他 layer
应显式使用 ignore 或 additive policy。

## Signal 与 VFX

animation subsystem 不引用 VFX。`AnimationSignal` 只包含稳定 sequence、source、state/motion、
normalized time、loop、name/payload 与 priority。Demo 的 `CharacterEffectBridge` 是 application 层
适配器：它消费 signal 并操作 `EffectInstance`；VFX 不回写 Graph。

Signal queue 有固定容量。普通 signal 满载时会计入 dropped；高优先级 signal 会先替换队列中的普通
signal。需要网络复制时，应复制 parameter/trigger 或业务事件，而不是序列化 controller 内部对象。

## Constraint

Look-at、JointLimit、FABRIK、双手与 Foot IK 只接收 model-space 纯输入。raycast、导航、碰撞、
角色 world transform 和目标选择必须在外部完成。FABRIK 只接受连续单链，最多 16 iterations；
Foot IK 不执行 raycast，调用方提供 target、surface normal、pole 和 pelvis offset。

Constraint 应在 Graph/layer 后、palette 前以固定顺序运行。`FabrikSolver` 持有复用 scratch，
因此按逐角色实例使用；其公开不可变 `Result` 是当前 constraint benchmark 中审计过的固定
约 32 B/角色/帧返回值。

## glTF Morph

支持 POSITION、NORMAL 与 TANGENT xyz delta、mesh default/node override weights，以及
STEP/LINEAR/CUBICSPLINE weights animation。每 primitive 最多 8 个 targets；target 数和独立
Morph delta byte budget 由 `GltfAssetLimits` 控制。运行权重安全范围是 `[-8, 8]`，用于手工 envelope
和 conservative bounds。

共享 delta 由 `GltfSceneAsset` 的 SSBO 持有，每实例 weight SSBO 由 `GltfSceneInstance` 持有。
object-space shader 顺序固定为 Morph 后 skinning。zero-target 不创建/绑定 Morph buffer；resize
不重建 Morph buffer；asset 与 instance 按各自所有权关闭。静态 `instantiate` 不接受带 Morph 的
asset，必须使用 animated instance 入口以避免静默丢失实例权重。

第一版不包括 Graph 序列化/编辑器、自动 2D triangulation、motion matching、full-body/branching IK、
动画内部 Scene/Physics 查询、GPU animation sampling、compute morph、mesh shader、第二组 skin
weights 或动态增加 Morph Target。

## 验证入口

```powershell
.\gradlew.bat animationJvmVerification
.\gradlew.bat animationGlVerification
.\gradlew.bat animationPerformanceVerification
.\gradlew.bat runShowcaseAnimationIntegration
.\gradlew.bat localAnimationVerification
```

性能入口固定五轮，每轮至少预热 60 帧且至少完成 10,000 次角色 update 后取中位；
Graph no-event/no-transition 与 Morph CPU sampling 的
steady-state 门槛为不高于 16 B/角色/帧。当前实测与图形环境见
`docs/performance/v0.18.3-animation-runtime-and-morph-2026-07-27.md`。
