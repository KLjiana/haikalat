# UI Compositor 接入与支持范围

更新：2026-11-05，v0.26 功能交付。构建版本仍为 `0.25.0`；功能验证不代表正式性能、人工体验或发布就绪。完整证据见[验收记录](../releases/v0.26.0-ui-compositor-acceptance.md)。

## 从一个子树开始

通过 `UiSystem` 接入，不直接创建 `UiCompositor`、纹理或 FBO。下面的片段在已有 current GL context、`window`、`device` 和显式 `presentationTarget` 下使用；示例按创建顺序反向关闭，graph 不拥有 presentation target。

```java
try (var graph = new RenderGraph(window.width(), window.height());
     var ui = UiSystem.create(window, UiConfig.builder().build())) {
    graph.addPass("Display").writeToBackbuffer().noClear()
            .execute((r, c) -> c.enableScissor(false)
                    .clearColor(0, 0, 1, 1).clear(true, false));

    var layer = UiLayerDescription.builder("panel", new UiScreenRect(0, 0, 160, 100))
            .opacity(0.5f).build();
    var panel = new Panel();
    panel.style(UiStyle.builder().width(UiLength.points(160))
            .height(UiLength.points(100)).build());
    panel.layerDescription(layer);
    panel.add(new Button("Click"));
    ui.document().root().add(panel);

    ui.attachTo(graph, "Display", UiAttachmentOptions.builder()
            .enableCompositor(true).layers(List.of(layer)).build());
    graph.compile();
    // 每帧：先消费新的 WindowInputSnapshot，再执行 graph；宿主自行 swap/present。
    ui.update(window.inputSnapshot(), 1.0 / 60.0);
    graph.execute(device, presentationTarget);
}
```

导入来自 `core.graph`、`subsystems.ui`、`subsystems.ui.render`、`subsystems.ui.style`、`subsystems.ui.widget` 和 `java.util.List`。实际可运行消费者是 `UiCompositorDemo`；该 Demo 使用相同门面并覆盖按钮、Slider、文字和图片。`UiSystem.attachTo(graph, "Display")` 保持 direct 绘制，不分配图层存储、不注册模糊 pass。direct 模式忽略节点的图层效果描述，普通节点与输入继续工作。

节点标记规定实际子树范围，attachment 列表规定可分配拓扑。稳定 ID 必须唯一，`parent` 必须与 snapshot 中的实际嵌套一致；隐藏的已注册层可暂时不出现在 snapshot。未注册、重复、父子不匹配、效果种类不匹配和深度超限均明确拒绝。绘制与 hit test 使用现有树顺序；`zOrder` 只接受零，不另建一套视觉排序。

## 支持矩阵

以下是 **图层** 选项；普通 SDF 图元、主题阴影和文字特效沿用原协议，不能据此声称支持图层 Glow 或任意遮罩。

| 选项 | 状态与行为 |
| --- | --- |
| 无 effect | 已完成：子树离屏及原位置合成，嵌套最多 8 层 |
| `BLUR` | 已完成：整个子树一起模糊，包括文字 |
| `BACKDROP_BLUR` | 已完成：显式 UI 前颜色源模糊后，叠加清晰子树；背景每帧更新 |
| `DROP_SHADOW`、`INNER_SHADOW`、`GLOW` | 未支持：attachment/新 snapshot 报 `UNSUPPORTED_EFFECT` 与 layerId |
| `COLOR_TRANSFORM`、`TINT` | 未支持：调用对应 builder 会加入 effect，接入时明确拒绝 |
| 同层 `BLUR` + `BACKDROP_BLUR` | 未支持：明确拒绝；可用不同父子层分别表达 |
| mask `NONE` | 已完成：保留可见 overflow，栅格范围包含实际图元和子层 effect outsets |
| mask `CLIP` | 已完成：轴对齐矩形 scissor；绘制与输入范围一致 |
| mask `ROUNDED` | 未支持：明确拒绝；圆角 SDF 外观不等于圆角子树裁剪 |
| blend `PREMULTIPLIED_ALPHA` | 已完成：`ONE / ONE_MINUS_SRC_ALPHA` |
| blend `OPAQUE`、`ADDITIVE` | 未支持：图层及普通 UI renderer 不接受这两种 batch |
| 非零 `zOrder` | 未支持：明确拒绝，使用现有节点顺序 |
| cache `NONE` | 每帧重绘；缓存它的父层也不能宣称静态 |
| cache `WHEN_CLEAN` | 依赖完整且未变化时复用；默认值 |
| cache `ALWAYS` | 仍须完整验证依赖，与 `WHEN_CLEAN` 使用相同安全失效规则；不冻结变化内容 |

`opacity` 为 `[0,1]` 的组属性，子树内部遮挡先完成，再同时乘预乘 RGB 和 Alpha 一次；原节点 opacity 语义保持。`dirtyRevision` 是额外失效信号，不能替代依赖追踪。`bounds` 为初始逻辑尺寸估算，运行时取 snapshot 的实际栅格范围，不能把描述 bounds 当固定屏幕裁剪。

## 坐标、颜色与模糊

UI 逻辑坐标左上为原点；按 `PresentationTarget.width / snapshot.windowWidth` 和对应高度比映射到像素，layer-local 原点来自该层有效栅格范围。分配先加入逻辑滤波 padding，再按像素比例与 `framebufferScale` 向上取整；两遍滤波尺寸分别为 `ceil(width / downsample)`、`ceil(height / downsample)`。GL scissor 与采样 UV 负责 Y 翻转。奇数尺寸、非零 FBO 和 HiDPI 2 倍已有定向检查；不推导为所有平台或 4K 验收。

`framebufferScale` 范围 `(0,1]`，用于有意降低层分辨率。blur 半径以逻辑像素表示，builder 将有限值 clamp 至 `0..64`，降采样支持 `1/2/4`。每轴核半径等于逻辑半径乘该滤波目标每逻辑像素的实际比例，核半宽取 ceil，`sigma=max(0.5,radius/3)`，归一化两侧权重。低层核支持 `0..256` 个滤波像素，换算后超限明确失败。radius=0 跳过滤波绘制；预算关闭滤波时，已注册的 H/V target 可缩为 1×1 保留拓扑，不承诺释放全部注册存储。

图层颜色和滤波附件统一为线性预乘 `RGBA16F`，每像素 8 字节。`UiColor` 输入为线性直 Alpha，renderer 写入预乘颜色；sRGB 颜色 token 用 `UiColor.fromSrgbHex` 转换。图片与背景的 sRGB 纹理按实际格式解码；最终 `SRGB8_ALPHA8` 目标只编码一次，线性目标不做 sRGB 编码。UI 在 tone mapping 之后，不重复曝光，也不进入场景 Bloom。宿主须提供真实的目标格式；普通默认 `RGBA8` 描述不构成 sRGB 格式证明。

滤波采用显式双线性采样、两遍可分离 Gaussian 和 clamp-to-edge。透明 padding 保持预乘语义；背景在场景边缘 clamp，不读取已经绘制的 UI。旋转仍使用保守 scissor AABB，未提供精确旋转、圆角、路径或任意 Alpha mask；移动和缩放与输入逆变换共享既有几何，滤波外扩区域不扩大点击范围。

## 显式背景来源

宿主先把完成场景后处理的显示阶段颜色写入 managed graph attachment，例如 `PreUiColor`，再通过最终 `Display` pass 呈现。接入使用：

```java
var glass = UiLayerDescription.builder("glass", new UiScreenRect(40, 70, 260, 160))
        .backdropBlur(12, 2).build();
// glass 同时设置在实际 Panel 上。
var options = UiAttachmentOptions.builder().enableCompositor(true)
        .layers(List.of(glass))
        .backdropSource(UiBackdropSource.available("PreUiColor")).build();
ui.attachTo(graph, "Display", options);
```

`available` 只声明逻辑资源名。接入校验实际 producer、执行先后、managed 可读附件和 `RGBA8/RGBA16F/SRGB8_ALPHA8` 编码；MSAA 来源先显式 resolve 到单采样线性 target。当前不支持直接把外部 imported/backbuffer 当作背景源；宿主先提供 managed 显示颜色 pass。运行时拒绝来源纹理与当前 presentation color alias，滤波也不允许输入输出同纹理。

所有玻璃面板使用同一个本帧 UI 前来源，互相叠加按 source-over；不能模糊已经画好的其他窗口。缺失的可选源产生 `BACKDROP_SOURCE_UNAVAILABLE`，保留内容、输入与组透明度。需要拒绝缺失源时设置 `backdropRequired(true)`；预算依然按下述受限分配合同处理。来源可用性或逻辑名变化通过 attachment 候选重配，不修改旧 graph 的 live 配置。

## 缓存与重配

缓存同时验证实例/ID、实际 paint 与几何、clip/有效尺寸、缩放和像素相位、目标格式、图片逻辑 ID/generation/region、文字样式和 glyph atlas generation、子层内容/opacity/位置、效果参数及 `dirtyRevision`。原生 handle 无可靠 generation 的图片使该层不可缓存。图层自身 opacity 或满足像素映射条件的纯平移只重合成；缩放与子层变化会重绘。动画、输入、文字和 UI VFX 最终改变实际 snapshot，不被缓存遮蔽。背景层固定每帧更新。

`UiSystem.prepareAttachment(candidateGraph, dependency, newOptions)` 先编译候选并准备程序，旧 graph 可继续绘制；随后 `candidate.commit()` 发布，再关闭旧 graph。候选没有 commit 时 close 只放弃其 UI 状态；**graph 始终由调用方关闭**，包括失败或放弃的候选。不能先关闭旧 graph 再声称有事务回滚。

这些操作必须在 update owner、current GL context 和双方 graph 空闲时执行；异步宿主先协调帧边界。`RenderGraph.isExecuting()` 覆盖从执行入口到提交及完成回调，只是状态查询，不是线程锁。宿主或候选 graph 的回调中发布均被拒绝。固定拓扑可以继续异步传递不可变 snapshot，配置重建不承诺并发跨线程事务。

数值 opacity、半径、downsample、scale、cache 和 dirtyRevision 更新使用新的不可变节点描述；ID、父子关系、effect 集合变化必须重配。内容模糊要保留 `Effect.BLUR` 才能把半径归零后再次开启；`.blur(0, ...)` 本身不会添加 BLUR。rebind 兼容入口仍要求旧 graph 已关闭；需要保留旧画面时使用候选协议。

## 预算、失败与诊断

默认上限为 64 个离屏层、8 层深度、16,000,000 个中间像素、单附件 4096×4096；超出层数的请求明确 direct fallback。预算先预留各层能够负担的组颜色，再分配可选模糊，避免较早层的滤波挤掉后续层的组透明度。预算包括颜色、H/V 滤波、MSAA resolve、padding、缓存和 active/pending/retiring storage；resize 的旧+新分配峰值也计入，宿主目标、场景附件、UI vertex ring 和 glyph atlas 不计入这项专用图层预算。

先关闭可选模糊、保留正确组透明度；若仍不够，整层 direct fallback 并报告组语义降级。运行时扩容可在尺寸恢复后重新启用已注册滤波；初始预算未注册的效果须重配才能恢复。层数/尺寸超限分别报告 `LAYER_LIMIT/EXTENT_LIMIT`，预算为 `OPTIONAL_EFFECT_BUDGET/PIXEL_BUDGET`。candidate 也会先按 old+new+retiring 峰值关闭可选模糊；若还会丢掉本来可以执行的组，则发布前拒绝，不销毁旧配置。

真实分配失败抛出异常；部分候选先清理，旧存储保持可用，不自动假装 OOM 已降级成功。`TARGET_CREATION_FAILED` 保留为枚举值，当前不作为分配错误的自动恢复保证。GPU 部分写入或记录失败会失效缓存，下一成功帧重绘；不能继续信任失败帧纹理。零尺寸目标跳过命令和分配，恢复后重新验证尺寸。

graph 独占 UI 附件；UI 只拥有自己的程序、ring 和 glyph 资源。借用 presentation FBO/color/depth 不删除、不重建 storage。通过 `RenderPipeline` embedded 入口恢复成功/异常出口的宿主 GL 状态；直接使用 `RenderGraph.execute` 的宿主仍按既有 graph 调用合同管理外围状态。

`ui.compositorDiagnostics()` 提供 active IDs、fallback 及像素数量；`compositorRuntimeStatistics()` 提供实际存储 bytes、候选峰值 peakBytes、primitive draws、blurDraws、composites 和逐层 cache reason。draws 不包含另外两个绘制计数，均为最近帧的记录量，缓存只在提交成功后有效。`HIT`、`FAILED_FRAME`、`TARGET_REPLACED`、`INSTANCE`、`AUTHOR_DIRTY`、`EFFECT_PARAMETERS`、`CLIP_LAYOUT`、`IMAGE_DEPENDENCY`、`GLYPH_ATLAS`、`CHILD_CHANGED`、`PAINT_CONTENT` 等原因由真实依赖决定；这些诊断不等于正式性能测量。

## 独立 Demo 与定向检查

```powershell
.\gradlew.bat runUiCompositorDemo --args="--case=layers"
.\gradlew.bat runUiCompositorDemo --args="--case=content-blur"
.\gradlew.bat runUiCompositorDemo --args="--case=backdrop-blur"
.\gradlew.bat runUiCompositorDemo --args="--case=cache-lifecycle"
.\verify.ps1 -Mode UiConsumers -TimeoutSeconds 300
# 单独安排的缓存长测，不属于上面的普通入口：
.\verify.ps1 -Mode UiSoak -TimeoutSeconds 300
```

默认可见 960×540，有限回放为每例 96 帧，640×360 并实际 resize 到 641×361。各例有 Effect、Reset、Pause、Step、Replay、Capture、Resize、参数 Slider 及专用控制；D01 Move/Scale、D02 Down/Zero、D03 Source/Advance、D04 Image/Child 和开发失败注入。诊断显示在独立 DEV 行，业务操作不要求 native ID 或绑定槽。

`UiJvm` 仅 JVM，`Ui` 为核心 JVM+GL+guard，`UiDemos` 仅四例及 guard；不要机械叠加重复执行。统一报告归档到 `build/reports/ui-compositor-v0260/<run-id>/`，包含输入身份/patch/untracked、门禁后的任务状态、JUnit XML、GL 日志和 Demo 的 `evidence.json/final.png/final.rgba32f`。raw 为左上起始、小端 float32、线性预乘 RGBA；PNG 只作可查看输出，数值 oracle 比较线性结果。

本机只验证了实际列出的尺寸与 NVIDIA OpenGL 环境。人工可见交互、Minecraft 客户端及正式性能矩阵状态独立记录；隐藏 GL、自动输入回放和图片查看不能替代它们。
