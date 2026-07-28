# 现代 UI 动画、合成与 VFX（0.19.2～0.19.4）

`0.19.4` 在保留普通 quad/image/glyph 路径的前提下，增加三条相互解耦的能力：

- `UiVisualTransform`、`UiPropertyTrack` 与 `UiTimeline` 只修改 UI snapshot 属性，不修改
  Yoga 尺寸，也不持有 GL 状态；
- `UiLayerDescription` 与 `UiCompositor` 只为显式 layer 注册 UI-owned RenderGraph
  target，超出 layer/pixel budget 或缺少 backdrop source 时继续走 direct UI；
- `UiEffectBridge` 只消费稳定 `UiAnimationSignal`，固定种子 CPU effect 由
  `UiEffectRenderer` 转换为已有 SDF/quad primitive。

## 动画

节点视觉变换以归一化 origin 表示，`(0.5, 0.5)` 是 bounds 中心。父变换由子节点继承，
paint snapshot 保存每个 quad 的仿射矩阵；命中测试对同一矩阵求逆。非零或非有限 scale 在构造
阶段拒绝。

```java
UiAnimationSequence sequence = UiAnimationSequence.builder()
        .then(panel, 0.4f, UiEasing.EASE_OUT_CUBIC,
                UiPropertyTrack.numeric(
                        UiPropertyTrack.Property.TRANSLATION_Y, 12.0, 0.0),
                UiPropertyTrack.numeric(
                        UiPropertyTrack.Property.OPACITY, 0.0, 1.0))
        .build();
ui.timeline().play(sequence);
```

`UiTimeline` 支持 sequence、ALL/ANY group、stable-order stagger、repeat/reverse、marker、
取消、enter/exit 和 reduced motion。`UiAnimationSystem` 的旧 visual/layout API 保持兼容。

## Layer 与 compositor

`UiAttachmentOptions` 必须在 RenderGraph topology seal 前传给 `UiSystem.attachTo`。没有显式
layer 时不会创建 target。blur 使用线性 `RGBA16F` 双轴 managed target；普通 layer 使用
`SRGB8_ALPHA8`。当前 snapshot 以 direct UI 作为始终可用的视觉 fallback；managed layer
拓扑负责预算、resize、格式、顺序和生命周期，后续可在不修改 tree API 的情况下替换 record
阶段。

backdrop 不可用时会记录 `BACKDROP_SOURCE_UNAVAILABLE`，不会读取 backbuffer 或绑定虚构纹理。
默认上限是 64 个 layer、16M intermediate pixels、单 layer 4096×4096、blur radius 64。

## UI VFX

screen-space UI VFX 不依赖 Scene、Camera、depth 或世界 VFX renderer。首版支持 shimmer、
ripple、dissolve、spark、confetti、trail、scanline 和 glitch；粒子容量最多 4096/instance，
bridge 默认最多 256 个 active effect。

```java
ui.effects()
        .register(button)
        .bindMarker("reward", confettiDefinition);
```

同一 `(sequence, type, payload)` 只触发一次。节点关闭时 effect 取消，hidden 节点暂停更新，
reduced motion 把效果换为 80 ms color/opacity feedback 或直接关闭。effect 最终仍位于
tone mapping 之后的 UI overlay，不进入 scene auto exposure 或 Bloom。

## 验证

```powershell
.\gradlew.bat test --tests "*UiModernAnimationTest" --tests "*UiCompositorFoundationTest" --tests "*UiEffectRuntimeTest"
.\gradlew.bat runModernUiIntegration
.\gradlew.bat localUiVerification
```

`runModernUiIntegration` 使用独立的隐藏 OpenGL 4.6 窗口执行 60 帧、
SDF/property animation、managed layer topology、shimmer/ripple/confetti 和最终
backbuffer 像素证明；它不会创建旧 `UiDemo` 场景。
