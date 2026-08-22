# Demo 职责与 API 覆盖

Demo 不要求每个公开 API 都重复出现，而是用互不重叠的场景证明一组能力；纯数据、失败路径、资源关闭和边界校验由 unit/GL smoke tests 覆盖。

| Demo | 主要职责 | 重点 API / 指标 | 不负责 |
|---|---|---|---|
| `EmptyWindowDemo` | 统一分辨率下的 clear/present 基线 | `GlfwWindow`、空 `RenderGraph`、present/CPU/GPU timing、state skip | shader、VAO、buffer、draw |
| `MinimalDemo` | 最小同步渲染入口和 API 教学 | command buffer、material、texture、framebuffer、mesh、instanced batch、resize | 阴影、异步线程、极限性能 |
| `AnimationDemo` | CPU 动画兼容入口与确定性 runtime 场景 | Player/Clip、Graph、1D Blend、Override/Additive layer、Look-at/FABRIK、诊断与固定步长 | glTF 资产加载和 GPU Morph/skinning |
| `LearnOpenGlDemo` | 完整功能正确性、正式场景基准与统一诊断入口 | asset、model、sRGB texture、lighting、shadow、HDR/AA/Bloom、camera、F2 diagnostics、capture/export | GPU procedural 极端吞吐、RenderGraph 编辑 |
| `AsyncDemo` | 双线程所有权和异步上传正确性 | GL render thread、latest-frame mailbox、upload queue、UBO、关闭顺序 | 大规模几何和完整光照 |
| `StressDemo` | 可重复的实例吞吐与 A/B 性能诊断 | procedural/indexed/SSBO/Matrix4f、GPU timer、pipeline statistics、state skip | 画面功能验收、复杂材质 |
| `PbrDemo` | 现代材质与后处理纵向闭环 | tangent、五纹理 metallic-roughness、direct/shadow、GPU IBL、HDR/ACES/Bloom/exposure、Color Grading LUT、距离/高度雾、retained UI | glTF、PBR instancing、高级材质扩展 |
| `Render3dV023Demo` | v0.23 Render3D M3–M7 可交互纵向验收 | 四类 queue、故意逆序的重叠 ALPHA、MASK 主/阴影 pass、Fog + 4×MSAA depth resolve、4 级 CSM、culling/cache/generation diagnostics、resize | glTF parser/animation、点光/聚光 shadow、性能基准 |
| `Render3dShadowBudgetDemo` | v0.23.1 多局部光预算、质量与 atlas cache 纵向验收 | 室内 floor/ceiling/wall 接收面、1 directional + 2 point + 4 spot、稳定 ID/槽位、预算拒绝、MASK + skin/morph caster、局部 dirty tile、三档 filter 合同、resize、shadow diagnostics/performance | clustered/Forward+、第二盏方向光 CSM、无限动态光、软阴影/透明阴影 |
| `GltfDemo` | 静态、蒙皮与 Morph glTF 端到端证明 | accessor/node/material/skin/animation/weights、joint palette、per-instance Morph weights、PBR/shadow Morph-before-skin、生命周期 | Graph/IK、第二组关节权重、compute morph |
| `UiDemo` | retained UI、文本、输入与 UI 动画证明 | Yoga、widgets、glyph atlas、IME、visual fade、spring layout transition、resize | editor docking、完整 accessibility bridge |
| `ModernUiDemo` | 独立现代游戏主界面与 v0.19 UI 纵向闭环 | 1280×720 双语战役主菜单、设置页、style class 主题、SDF 圆角、property timeline、显式 layer、UI VFX、reduced motion、像素证明 | editor timeline、offscreen subtree replay/cache、GPU particle |
| `VfxDemo` | 通用效果模拟与透明绘制证明 | `EffectAsset/EffectInstance`、粒子/Ribbon/Decal over-life、纹理 mask、Alpha/Additive、HDR/Bloom/soft particle、双 `BezierPath3f` 闭合无限符号、CPU/GPU/uniform 基线 | Compute 粒子、体积效果、flipbook、Ribbon repeat UV 和曲线编辑器 |
| `HaikalatShowcaseDemo` | Milestone 4 同帧综合验收与稳定性 | 高级动画、PBR/shadow、Bloom/LUT/fog、HDR CPU VFX、MSAA depth resolve、自动曝光顺序、GPU VFX、volume、UI diagnostics、资源集合 | HaikalatHost、宿主事件/网络；不把受控 GPU 实验声明为完整编辑型系统 |

## AnimationDemo

```powershell
.\gradlew.bat runAnimationDemo
.\gradlew.bat runAnimationIntegration
.\gradlew.bat runAnimationGraphIntegration
.\gradlew.bat runAnimationAdditiveIntegration
.\gradlew.bat runAnimationConstraintIntegration
.\gradlew.bat localAnimationVerification
```

该 Demo 用手工构造的三段关节链证明纯 JVM 动画 subsystem。`--scenario` 接受
`clip|graph|additive|constraints|all`，`--characters=N` 可批量求值。交互入口按真实帧间隔播放，
隐藏兼容入口继续使用固定 `1/30 s` 步长运行 8 帧并断言末端关节发生位移。Graph、layer 和
constraint 均只写复用的 `PoseBuffer`；OpenGL 命令不进入 animation subsystem。

Morph 专项由 `runGltfMorphIntegration` 解码自有生成 fixture，由
`runGltfMorphSkinningIntegration` 在隐藏 OpenGL 4.6 窗口中验证非蒙皮/蒙皮实例、PBR、
directional shadow、per-instance isolation、上传失败回滚和关闭归零。动画 marker 到 VFX 的单向
编排由 `runAnimationVfxBridgeIntegration` 验证；`runShowcaseAnimationIntegration` 将它与综合
Showcase 像素入口串联。

## VfxDemo 与曲线性能入口

```powershell
.\gradlew.bat runVfxIntegration
.\gradlew.bat runVfxCurveBenchmark
```

`VfxDemo` 默认展示 curved preset，可通过 `--linear` 切换到 0.18.0 线性属性路径。固定
`BezierPath3f` 的弧长表把 emitter 的归一化距离映射回参数；两段路径在中心交点保持切线连续，
使完整闭合的无限符号 Ribbon 近似匀速且不会用直线连接首尾。该路径只在
Demo/application 层驱动 origin。`runVfxCurveBenchmark` 不创建 GL context，在同一 JVM 内交替测量
10,000 粒子的 linear/curved snapshot，并输出时间、线程分配量和累计曲线采样数。

## PbrDemo

```powershell
.\gradlew.bat runPbrDemo
.\gradlew.bat runPbrIntegration
.\gradlew.bat localPbrVerification
```

专用场景固定包含 5×5 metallic/roughness sphere、五纹理 OBJ、legacy 对照、方向光阴影、
两个点光、HDR environment 和 UI 参数面板。确定性参数包括
`--frames=N --hidden --environment-quality=test|default --auto-exposure=on|off`
` --bloom=on|off --color-grading=on|off --fog=on|off`
` --aa=none|fxaa|msaa|msaa-fxaa|taa`；Fog + MSAA 会走显式 depth resolve，性能入口另支持
`--warmup=N --size=WIDTHxHEIGHT`。

## Render3dV023Demo

```powershell
.\gradlew.bat runRender3dV023Demo
.\gradlew.bat runRender3dV023Integration
```

窗口默认使用 4×MSAA、ACES、距离/高度 Fog 和 4 级 4096² 固定 CSM atlas，每级 tile 为
2048²。前排依次展示
OPAQUE、带 checker cutoff 的 MASK、故意按 near-before-far 插入的重叠 ALPHA，以及 ADDITIVE；
远处 landmark 用于观察 Fog 和 cascade 过渡，另有一个视锥外对象固定产生 culling 统计。
`WASD + 鼠标` 移动相机，`ESC` 退出；标题栏显示四类 queue、visible/culled、cascade count、
depth resolve sample 数和 forward queue cache hit/rebuild。隐藏入口固定运行 12 帧，在第 6 帧从
640×360 resize 到 800×450，并断言上述渲染与 diagnostics 合同。可用参数为
`--hidden --frames=N --size=WxH --resize=FRAME:WxH --environment-quality=test|default --verify`。

## Render3dShadowBudgetDemo

```powershell
.\gradlew.bat runRender3dShadowBudgetDemo
.\gradlew.bat runRender3dShadowBudgetIntegration
.\gradlew.bat runRender3dShadowBudgetPerformance1080p
.\gradlew.bat runRender3dShadowBudgetPerformance4k
```

窗口默认使用 balanced policy 和固定 4 级 4096² CSM，加载真实 glTF OPAQUE/MASK、two-joint skin 与
四 target morph caster；灯光固定为 1 directional + 2 point + 4 spot，另放置两个低优先级 candidate
证明预算拒绝。标题栏显示 D/P/S 入选数、tile redraw/reuse、cache 和 filter；`WASD + 鼠标` 移动相机，
`ESC` 退出。需要复现 1024² 预算基线时传入 `--csm-atlas=1024`；内置隐藏集成和 benchmark
任务固定使用该低成本档位。

隐藏验证在固定帧移动 point、spot、camera 和 skin/morph，再执行同宽高比 resize，硬断言 6/1/4/20
的 dirty tile 数、fixed atlas generation、最终 0 redraw / 20 reuse、2+4 atlas 尺寸和 20 MiB depth
估值。参数为 `--hidden --frames=N --size=WxH --resize=FRAME:WxH`
` --environment-quality=test|default --verify --benchmark --profile=legacy|balanced --warmup=N`。
benchmark 模式不执行确定性变更，用于五轮 1080p/4K 静态 cache 记录。

## GltfDemo

```powershell
.\gradlew.bat runGltfDemo
.\gradlew.bat runZombieDemo
.\gradlew.bat runZombieIntegration
.\gradlew.bat runCrouchWalkDemo
.\gradlew.bat runCrouchWalkIntegration
.\gradlew.bat runGltfSkinningIntegration
.\gradlew.bat localGltfVerification
```

专用 animated two-joint fixture 使用 CUBICSPLINE 通道驱动每实例 `JointPalette`，同一 SSBO 姿态
分别进入 PBR forward 与方向光 shadow pass；集成入口断言末端位移、画面变化和资产先于实例关闭时的保护。
`crouch_walk.glb` 额外覆盖 Blender 将同一时间轴拆成多个 object action 的情况；运行时合并目标
互不重叠的 TRS channel，并让 11 个刚性部件同步播放。

## UiDemo

```powershell
.\gradlew.bat runUiDemo
.\gradlew.bat runUiIntegration
```

确定性脚本在首帧启动 header visual fade 与 controls spring layout transition；动画求值位于样式解析
和 Yoga layout 之间，集成入口继续验证 snapshot、最终像素和 resize。

## ModernUiDemo

```powershell
.\gradlew.bat runModernUiDemo
.\gradlew.bat runModernUiIntegration
```

默认展示一套 1280×720 的双语游戏主界面：玩家档案、战役继续、新游戏、装备、设置、退出、
任务进度与状态卡均由 retained UI 组件搭建。设置页包含动态效果档位、显示模式、音量与界面缩放；
主菜单按钮、页面切换、呼吸动效和 UI VFX 走正式事件、timeline 与 effect runtime，不是截图贴图。

支持 `--sdf=on|off --animation=on|off --compositor=on|off --backdrop=on|off`
` --ui-vfx=on|off --effects=... --frames=N --hidden --size=WxH`
` --reduced-motion=on|off --no-vsync --capture=PATH`。该入口拥有独立窗口、RenderGraph 和完整场景，
不依赖或启动 `UiDemo`；窗口会在首帧 UI GL 资源预热完成后才显示。隐藏集成固定运行 90 帧，
脚本覆盖设置页、reduced motion、音量修改、返回战役和继续游戏，并执行 framebuffer readback；
普通 `UiDemo` 仍是兼容与输入/IME 责任入口。

## VfxDemo

```powershell
.\gradlew.bat runVfxDemo
.\gradlew.bat runVfxIntegration
.\gradlew.bat runVfxLegacyIntegration runVfxFireIntegration
.\gradlew.bat runVfxImpactIntegration runVfxTrailIntegration
.\gradlew.bat runVfxPerformance1080p runVfxPerformance4k
```

模拟逻辑和 `VfxMaterial` 只位于无 GL 依赖的 `subsystems.vfx`，render3d adapter 消费不可变透明排序
snapshot。参数支持 `--effect=legacy|fire|impact|trail|all`、`--hdr=on|off`、`--bloom=on|off`、
`--soft-particles=on|off` 与 `--size=WxH`。专项入口分别证明 legacy 白 mask LDR、fire/smoke
Alpha+Additive、impact particle+Decal 和完整闭合 textured Ribbon；性能入口报告 CPU update、
RenderGraph GPU、draw 和 uniform payload。
多类阴影场景使用 `PbrDemo --local-shadows=on`，正式入口为 `runLocalShadowsIntegration`。

## HaikalatShowcaseDemo

```powershell
.\gradlew.bat runHaikalatShowcaseDemo
.\gradlew.bat runShowcaseIntegration
.\gradlew.bat runShowcaseVfxMsaaIntegration
.\gradlew.bat runShowcaseVfxAutoExposureIntegration
.\gradlew.bat runShowcasePerformanceBaseline
.\gradlew.bat runShowcaseStabilityIntegration
```

综合场景只使用 Haikalat 本体 API。`RenderPipeline` 先执行 PBR、方向光阴影、Color Grading LUT 和
距离/高度雾；自动曝光从 base HDR 取样，CPU VFX 随后写入带 resolved scene depth 的 HDR composite，
Bloom 与 tone mapping 消费 composite。最终 backbuffer overlay 只记录体积光、Compute/SSBO 粒子与
retained UI，CPU VFX 不再位于 LDR overlay。
动画对象每帧执行双层混合、Bone Mask、根运动、动作事件和 Two-bone IK。24 帧入口读取最终像素并断言
每个 subsystem 均实际产出；3600 帧入口在第 120 帧冻结 GL 资源序列和估算显存，结束时要求完全一致，
随后关闭全部资源并要求 live 表归零。详细边界和实测数字见
`docs/guides/milestone4-api-performance-limitations.md`。

## StressDemo 模式

默认运行 100 万个 GPU procedural triangle：

```powershell
.\gradlew.bat runStressDemo
```

`runStressDemo` 和各 `runStress*Demo` 性能快捷入口默认使用 100 万实例；仍可通过
`-PstressInstances=N` 覆盖。`localGlVerification` 中的 2 帧功能回归固定使用 10 万实例，
避免日常正确性验证承担性能基准的显存与时间成本。

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

## LearnOpenGlDemo 与 post-v0.8 基准

`LearnOpenGlDemo` 默认保持 Bloom 关闭；交互查看使用：

```powershell
.\gradlew.bat runBloomDemo
```

有限帧入口把 8 帧输出标记为 `[INTEGRATION-ONLY]`。正式性能结论必须使用五轮、预热 100 帧、
采样 1000 帧的任务：

```powershell
.\gradlew.bat runPostV08Benchmarks
.\gradlew.bat runInstanceShadowBenchmarks
```

详细数据见 `docs/performance/post-v0.8-bloom-2026-07-15.md`。

诊断面板与原 HUD 共用同一个 `UiSystem` 和最终 `UiOverlayPass`。F2 打开 Overview、Passes、Graph、
Resources、Messages；自动化使用 `--diagnostics=off|basic|detailed`、`--diagnostics-panel` 和
`--diagnostics-export=<path>`。诊断资源生成、统计和 JSON 序列化不放在 Demo 主循环中；Demo 只负责
参数、输入、附加面板和有限帧验收。详见 `docs/guides/diagnostics.md`。
