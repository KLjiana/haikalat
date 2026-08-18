# GeckoLib / Blockbench glTF 模型接入

## Zombie Demo

用户提供的 `zombie.gltf` 已保留为单文件资源：

```text
src/demo/resources/scenes/gltf/zombie.gltf
```

它包含 7 个刚性网格、内嵌 PNG 纹理，以及一个长度为 3.75 秒的 `run` 动画。可直接运行：

```powershell
.\gradlew.bat runZombieDemo
```

确定性隐藏窗口验证：

```powershell
.\gradlew.bat runZombieIntegration
```

也可以通过通用入口启动：

```powershell
.\gradlew.bat runGltfDemo --args="--asset=zombie"
```

## Crouch Walk Demo

`crouch_walk.glb` 是 Blender glTF I/O 导出的单文件资源，包含 11 个刚性网格、1 张内嵌 JPEG
纹理和 10 个长度均为 `1/6` 秒的 object action：

```text
src/demo/resources/scenes/gltf/crouch_walk.glb
```

该资源使用像素风 JPEG 图集；导入时已将导出器写入的
`NEAREST_MIPMAP_NEAREST` 缩小过滤校正为 `NEAREST`，避免低分辨率 mip
把模型采样成近黑色。这个校正只写入该 GLB 的 sampler，不改变运行时的全局 glTF 采样规则。

Blender 把各部件 action 导出成了 10 个独立 glTF animation；这些 animation 的目标互不重叠，
因此 Demo 通过 `GltfSceneInstance.playCombined(...)` 将 11 条 TRS channel 合成同一时间轴并循环
播放。该过程不改写原始 GLB，也不需要为模型增加 skin。

资产还声明了可选 `KHR_materials_specular` 与 `KHR_materials_ior`。Demo 对该资产显式使用 lenient
扩展策略并记录 warning；当前 PBR 路径忽略这两个附加参数，但仍读取标准 base color texture、
metallic 与 roughness。默认严格加载策略以及 `extensionsRequired` 的拒绝行为保持不变。

```powershell
.\gradlew.bat runCrouchWalkDemo
.\gradlew.bat runCrouchWalkIntegration
.\gradlew.bat runGltfDemo --args="--asset=crouch_walk"
```

`crouch_walk` 和 `zombie` 启动时默认收起检查面板，避免遮住模型；按 `F2` 可随时展开。

## glTF 模型 + 外部 JSON 动画库

支持 `haikalat_animation_tools` 1.4.0 导出的
`haikalat.gltf-animation-library/1` 资源库。压缩包可保持导出时的结构：

```text
player.gltf-animations.zip
├── animation-library.json
├── model/
│   └── player.gltf
└── animations/
    ├── idle.animation.gltf.json
    ├── attack.animation.gltf.json
    └── move.animation.json
```

ZIP 可以直接按需读取，不会先整体解压到临时目录：

```java
LoadedGltfScene scene = GltfAssetLoader.loadAnimationLibrary(
        Path.of("player.gltf-animations.zip"));
```

资源位于 classpath、资源目录或自定义 `AssetByteResolver` 时，也可以保持解包结构并从清单加载：

```java
GltfAssetLoader loader = new GltfAssetLoader(resourceLocator);
LoadedGltfScene scene = loader.loadAnimationLibrary(
        AssetRef.of("characters/player/animation-library.json"));
```

加载器先正常解码 `model`，再按清单顺序把所有外部动画追加到模型已有的
`LoadedGltfScene.animations()`。每条 channel 的 sidecar 节点会通过显式
`sidecarNode` / `sourceNode` 映射到模型节点，并同时校验两侧节点名称；过期绑定不会静默驱动
错误的骨骼。动画名称必须全局唯一，外部 sidecar 中的 marker、插值方式和目标起始时间保持不变。
`GltfAnimationRig.from(scene)`、`GltfSceneAsset.upload(...)` 与
`GltfSceneInstance.play(...)` 无需使用另一套 API。

## 共享骨架的独立 GLB 动画源

当多个模型只更换 Mesh、UV、材质和贴图，而节点/父子关系、bind TRS、skin joint 顺序及
inverse bind matrices 完全一致时，可把一个动画 GLB 作为不可变共享资产：

```java
GltfAnimationSet animations = loader.loadAnimationSet(AssetRef.of("player.glb"));
LoadedGltfScene wildData = animations.bind(loader.load(AssetRef.of("player_wild.gltf")));
LoadedGltfScene sileData = animations.bind(loader.load(AssetRef.of("player_sile.gltf")));
```

`GltfAnimationSet` 不保留动画 GLB 的 Mesh、材质、图片或 sampler；绑定只复用不可变
`AnimationDef`/channel 数据。每个上传后的 `GltfSceneInstance` 仍独立拥有播放时间、姿态、
状态机和蒙皮 palette。绑定会严格拒绝节点名重复、节点顺序/父节点、bind TRS、skin joints
或 inverse bind matrices 的任何差异，Mesh/UV/材质差异不会参与判断。

`haikalat.gltf-animation-library/1` 的 `animations[].file` 也可指向 `.glb`。多 clip GLB
必须指定 `animations[].clip`，同一个 GLB 可由多个条目按名称选择不同 clip；GLB 条目不需要
`nodeBindings`，因为使用完整 Rig 兼容校验。JSON sidecar 的旧格式保持不变。

外部动画也可以使用 `haikalat.animation-clip/1` 紧凑 JSON。该格式直接保存节点名、
translation / rotation / scale / weights 轨道、关键帧和 events，不需要 glTF accessor 或
base64 buffer。清单项省略 `nodeBindings` 时，加载器会按精确且唯一的模型节点名绑定；节点缺失、
重名、重复目标、越界时间和非法四元数都会在 CPU 解码阶段失败。普通
`.animation.gltf.json` 仍要求显式 `sidecarNode` / `sourceNode` 绑定。

序列化场景也可以直接把它作为 `gltf` renderable；异步场景服务会把清单、主模型和每个外部
动画 sidecar 都登记为热重载依赖：

```json
{
  "type": "gltf",
  "asset": "./characters/player/animation-library.json",
  "animated": true,
  "initialAnimation": "idle"
}
```

对于 animation-only glTF，`selfContained: true` 要求 buffer 使用 data URI；设为 `false` 时
也允许 sidecar 引用资源库内的相对 buffer 文件。紧凑 clip JSON 不含外部 buffer，因此可直接
标记为 `selfContained: true`。目录与 ZIP 两种入口都会拒绝绝对 URI、根目录逃逸、ZIP 重复
条目和超出 `GltfAssetLimits` 的输入。

当前 `player_wild` 真实资产包含 12 个模型节点、1 个 skin、`steve.png` 像素纹理，以及
8 个紧凑外部 clip（`stand`、`move`、`run`、`idle_sword`、`attack_light`、`start`、
`idle_dash`、`end`），合计 98 条 TRS channel，均可转换为运行时 clip。

仓库内的解包副本可由专用 Demo 直接加载。Demo 以 `0.65x` 速度按顺序展示全部 8 个 clip，
每次切换使用运行时生成的 `0.22` 秒姿势过渡；循环动画保持循环，攻击与 dash 阶段在末帧
停留后切换：

```powershell
.\gradlew.bat runPlayerWildDemo
.\gradlew.bat runPlayerWildIntegration
.\gradlew.bat runGltfDemo --args="--asset=player_wild"
```

## 为什么不依赖 GeckoLib

模型的 Blockbench 工程使用 GeckoLib 格式，但 glTF 导出器已经把骨骼旋转烘焙成标准 glTF
四元数 animation channel。GeckoLib 4.9.2 源码中的约定是旋转 X/Y 取反、Z 保持方向并从度
转换为弧度；`zombie.gltf` 的四元数已经包含该转换，所以运行时无需引入 Minecraft、
NeoForge 或 GeckoLib JAR。

这个资产没有 `skin`、`JOINTS_0` 或 `WEIGHTS_0`。Blockbench 为每个身体部件生成一个动画
pivot 节点，并把对应网格作为子节点。`GltfSceneInstance` 原本就会为所有节点计算动画后的
全局矩阵，因此可以让父 pivot 带动刚性网格，不需要修改 PBR shader 或 glTF 主解析器。

## 当前边界

- 支持导出后 glTF 中已有的 translation、rotation、scale channel。
- `run` 默认循环播放。
- 原 GeckoLib `.animation.json` 中的 Molang、声音、粒子、timeline 和自定义 easing 不会由
  glTF 运行时重新解释；当前资产由 Blockbench 导出为标准 `LINEAR` channel。
- `haikalat.gltf-animation-library/1` 同时支持 `.animation.gltf.json` 和带
  `haikalat.animation-clip/1` schema 的紧凑 `.animation.json`；后者仍不是 GeckoLib 原始
  Molang 动画格式。
- 若需要完全复刻 GeckoLib easing 或事件，应在导出阶段烘焙采样；不应把
  GeckoLib/Minecraft 运行时耦合进渲染核心。
