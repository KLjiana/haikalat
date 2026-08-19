# Render3D 材质、透明与阴影指南

本文描述 v0.23.1 的稳定合同，面向资产制作者和直接构建 `Scene` 的调用方。四类 queue 与 CSM
窗口为 `runRender3dV023Demo`；多局部光预算与缓存窗口为 `runRender3dShadowBudgetDemo`，有限帧自动
验证为 `runRender3dShadowBudgetIntegration`。

## 四类 forward queue

| queue | 材质来源 | depth write | blend | 顺序 |
| --- | --- | --- | --- | --- |
| OPAQUE | 默认不透明材质 | 开 | 关 | shader/material/mesh 稳定排序 |
| MASKED | 正 alpha cutoff / glTF MASK | 开 | 关 | shader/material/mesh 稳定排序 |
| ALPHA | `BlendMode.ALPHA` / glTF BLEND | 关 | source alpha | camera-space back-to-front，稳定平局 |
| ADDITIVE | `BlendMode.ADDITIVE` | 关 | additive | 保持提交顺序 |

ALPHA 是对象级排序，不会拆分 mesh 三角形。半透明物体内部自相交、相互穿插或相机位于物体内部时，
应把内容拆成独立对象并控制 pivot/bounds；本版本不承诺 OIT、transmission 或折射。

## glTF alpha

- OPAQUE 保持默认不透明路径。
- MASK 在主 pass 和方向光 shadow pass 使用同一个 `alphaCutoff`。贴图 alpha 是数据的一部分，
  不要通过颜色空间转换改变 cutoff 语义。
- BLEND 映射到 ALPHA queue，并沿用 glTF 的 `doubleSided`。不要用 BLEND 表达硬边叶片、栅栏或
  头发卡片；这类内容应使用 MASK，才能获得稳定 depth 和剪影阴影。

非法 alpha mode、非有限 cutoff 或缺少所需纹理输入会在资产准备阶段失败，不会静默降级为 opaque。

## 阴影策略

OPAQUE 和 MASKED 在 `castShadows=true` 时可进入方向光、点光和聚光 caster 路径。ALPHA/ADDITIVE 默认不投射
阴影，即使 `SceneObject` 沿用 `castShadows=true` 默认值也不会被普通 depth shader 当作不透明物体。
v0.23 不提供透明度积分阴影；需要近似时应由宿主建立独立的 opaque/MASK proxy，而不是修改通用
透明 queue 合同。

方向光默认仍为单级 shadow map。高级设置支持 2～4 级 fixed atlas、practical split、
world-texel snapping 和 split blend。窗口 resize 不重建 fixed atlas；atlas size 或 cascade count
变化属于 pipeline topology 变化。PBR 3x3 PCF 的 tap 被限制在当前 tile 内，不会跨 cascade 采样。

`runRender3dV023Demo` 使用 4096x4096 atlas；4 级布局下每个 tile 为 2048x2048。更高的 atlas 会
增加显存和 shadow fill 成本，应按目标硬件、可见距离与 caster 数量测量后选择。

## v0.23.1 局部光预算与质量

默认设置继续使用 v0.23.0 的一盏 point + 一盏 spot、3×3 PCF 和逐帧更新合同。高级 balanced preset
显式开启最多两盏 point、四盏 spot、稳定槽位和静态 tile cache：

```java
scene.addLight(keyPoint, ShadowLightHints.priority(100));
scene.addLight(fillPoint, ShadowLightHints.priority(80));
scene.setShadowLightHints(1, ShadowLightHints.priority(120));

RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment)
        .localShadows(LocalShadowPipelineSettings.balanced());
```

内置 glTF 高级路径由 `GltfDemoAssets.loadShadowBudget()` / `GltfRuntimeLibrary.createShadowBudget()`
选择 `pbr-forward-shadow-budget.frag`；普通 `GltfRuntimeLibrary.create()` 继续编译 v0.23.0 的
`pbr-forward.frag`。自定义 shader 若使用 balanced 配置，必须实现 binding 5 的
`ShadowSamplingBlock` 合同，否则 pipeline 会在准备阶段拒绝不兼容组合。

`setLight(index, replacement)` 保留灯光 stable ID，因此参数动画不会无条件换槽。调度器先比较显式
priority，再按 `SCENE_ORDER` 或 `CAMERA_IMPORTANCE` 评分，最终以 stable ID 打破平局；超过预算、超出
shader 数组上限、near/range 非法或关闭投影的灯会在 diagnostics 中留下确定原因。

自定义质量配置仍组合已有 `LocalShadowSettings`：

```java
LocalShadowPipelineSettings quality = new LocalShadowPipelineSettings(
        new LocalShadowSettings(1024, 0.1f, 0.0015f),
        new LocalShadowSettings(1024, 0.1f, 0.0015f),
        2, 4,
        ShadowSelectionMode.CAMERA_IMPORTANCE,
        ShadowFilterMode.PCF_5X5,
        0.002f, 1.15f, true);
```

`HARD`、`PCF_3X3` 和 `PCF_5X5` 分别采 1、9、25 个 tap。point face、spot tile 和 CSM tile 都按
kernel 半径保留 guard band，不能跨槽采样。`LocalShadowSettings.bias()` 是 depth base bias；
`normalBias` 是沿接收面法线的世界空间偏移。先保持接近默认值并在实际模型尺度下观察 acne，再逐步
调整；过大的 base/normal bias 会产生 peter-panning。

balanced 默认 atlas 为两个 512² point block（总 1536×2048）和四个 512² spot tile（总
1024×1024），加上 4 级 1024² CSM 时估算 depth memory 为 20 MiB。容量或分辨率变化会事务式重建
pipeline generation；filter、bias、priority 和灯光参数只更新 frame plan/cache。窗口同宽高比 resize
不会重建 fixed atlas。

排查阴影预算时读取 `pipeline.lastRender3dDiagnostics().shadows()`：

- `selectedLights/rejectedLights` 回答 stable ID、shader index、slot、分数和拒绝原因；
- `tilesRendered/tilesReused` 与 `cacheHits/cacheMisses/missReasons` 回答为什么重画；
- `point/spotCapacity`、atlas 尺寸、filter 和 `estimatedDepthBytes` 回答当前资源合同。

静态 balanced 场景应稳定为 0 个 redraw、20 个 reuse。移动一盏 point 应只重画 6 face，移动一盏
spot 只重画 1 tile。skin/morph 姿态、MASK 材质或 caster transform 变化会使相关内容失效；BLEND 和
`castShadows=false` 不参与 cache key。

## Fog、MSAA 与资源生命周期

Fog 需要单采样 scene depth。启用 MSAA 时，pipeline 创建显式 depth resolve 节点并校验源/目标的
format、sample 和 extent；关闭 MSAA 时直接复用 geometry depth，不分配 resolve target。窗口 resize
先构建完整 candidate generation，成功后再替换 active generation；构建或 pass 失败不会发布半成品。

## 验证

```powershell
.\gradlew.bat compileJava demoClasses test
.\gradlew.bat runRender3dV023Integration --rerun-tasks
.\gradlew.bat runRender3dShadowBudgetIntegration --rerun-tasks
.\gradlew.bat localGlVerification --rerun-tasks
```

隐藏集成入口固定 12 帧，中途从 640x360 resize 到 800x450，并断言四类 queue、MASK caster、
4x depth resolve、4 级 cascade、固定 atlas、缓存和 diagnostics。无桌面环境只运行前三项中的
JVM 编译/测试；GL 入口需要 OpenGL 4.6 Core 与 GLFW 窗口系统。窗口化多灯检查可执行：

```powershell
.\gradlew.bat runRender3dShadowBudgetDemo
```

标题栏显示入选 D/P/S、重画/reuse、cache 和 filter；WASD + 鼠标移动相机，ESC 退出。
