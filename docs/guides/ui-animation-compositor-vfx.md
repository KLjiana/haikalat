# 现代 UI 动画、合成与 VFX（0.19.2～0.26）

`0.19.4` 在保留普通 quad/image/glyph 路径的前提下，增加三条相互解耦的能力：

- `UiVisualTransform`、`UiPropertyTrack` 与 `UiTimeline` 只修改 UI snapshot 属性，不修改
  Yoga 尺寸，也不持有 GL 状态；
- `UiLayerDescription` 最初只注册图层拓扑；0.26 已实现实际子树离屏、组透明度、两类模糊与静态缓存，
  附件由 RenderGraph 所有，预算不足会明确降级；
- `UiEffectBridge` 只消费稳定 `UiAnimationSignal`，固定种子 CPU effect 由
  `UiEffectRenderer` 转换为已有 SDF/quad primitive。

## 动画

节点视觉变换以归一化 origin 表示，`(0.5, 0.5)` 是 bounds 中心。父变换由子节点继承，
paint snapshot 保存每个 quad 的仿射矩阵；命中测试对同一矩阵求逆。零或非有限 scale 在构造
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

`UiAttachmentOptions` 在 topology seal 前传给 `UiSystem.attachTo`。同一不可变 snapshot 按原 paint
顺序拆成 direct 与离屏区间，子树只绘制一次；普通层和滤波均使用线性预乘 `RGBA16F`，最终按目标
真实格式编码一次。关闭 compositor 时没有图层存储与滤波 pass。

背景模糊只读取显式 UI 前显示颜色源，缺失可选源会报告 `BACKDROP_SOURCE_UNAVAILABLE`，保留内容与组透明度。
缓存追踪实际内容、图片/glyph generation、父子几何与效果参数，GPU 失败后重绘。配置结构变化使用候选
graph 帧边界发布，不能在回调中切换；受限预算优先关闭可选模糊，再明确报告 direct fallback。

完整枚举支持矩阵、代码示例、颜色/坐标、缓存及所有权见 [UI Compositor 指南](ui-compositor.md)。
图层 Glow、shadow、tint、color transform、ROUNDED mask 和非预乘 blend 均明确拒绝；既有 SDF 与文字特效独立保留。

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

`runModernUiIntegration` 使用独立的隐藏 OpenGL 4.6 窗口执行 90 帧、
SDF/property animation、实际子树合成、shimmer/ripple/confetti 和最终
backbuffer 像素证明；它不会创建旧 `UiDemo` 场景。

0.26 的普通功能签收使用 `./verify.ps1 -Mode UiConsumers -TimeoutSeconds 300`；定向缓存长测单独使用
`-Mode UiSoak`，不自动运行完整 UI、IME 或性能矩阵。证据与人工/性能状态见 [0.26 验收记录](../releases/v0.26.0-ui-compositor-acceptance.md)。
