# 可嵌入渲染接入指南

本文说明宿主如何让 Haikalat 渲染到已有 OpenGL FBO。嵌入模式不会创建 GLFW 窗口、
OpenGL context 或渲染线程，也不会接管主循环或调用 `swapBuffers`。

## 职责边界

- Host 提供当前 OpenGL context、唯一渲染线程、`RenderDevice`、帧时序、相机和输出目标；
- Haikalat 负责记录并执行场景、后处理、VFX 与 UI 命令；
- Host 创建的 FBO/texture 必须声明为 `BORROWED`，Haikalat 只使用，不删除、不重分配、
  不修改 storage 或采样参数；
- Haikalat 会恢复原生 GL 状态。Minecraft 等带 Java 状态缓存的宿主，仍需在外层通过自身
  API 同步其缓存；
- 所有创建、`build()`、每帧渲染、resize 和 `close()` 都在宿主渲染线程执行。

## 建立目标

普通单 FBO 宿主可直接使用 borrowed 工厂：

```java
ExternalAttachment color = ExternalAttachment.borrowedColor(
        hostColorTexture, RenderFormat.RGBA8, width, height);
ExternalAttachment depth = ExternalAttachment.borrowedDepthStencil(
        hostDepthStencilTexture, width, height);

PresentationTarget target = PresentationTarget.borrowed(
        hostFramebuffer, color, depth, width, height, targetGeneration);
```

如果 draw/read FBO 分离或 stencil 是独立附件，使用 builder：

```java
PresentationTarget target = PresentationTarget.builder(width, height)
        .framebuffers(hostDrawFbo, hostReadFbo)
        .samples(samples)
        .generation(targetGeneration)
        .framebufferOwnership(ResourceOwnership.BORROWED)
        .color(color)
        .depth(depth)
        .stencil(stencil)
        .build();
```

附件的尺寸、采样数、role、format 和 ownership 必须与目标一致。宿主重建资源后创建一份
新的 descriptor，并增加 `generation`；同一 pipeline 下一帧即可接收新 ID，不需要重建 runtime。
Pipeline 需要 HDR 或其他 owned intermediate 时，会从已声明的 host color/depth 做 source
transfer；该过程只读 borrowed attachment，不接管或修改其 storage。

## 建立 runtime 与 pipeline

下面的代码在宿主渲染线程、当前 GL context 已就绪时执行：

```java
HaikalatRuntime runtime = HaikalatRuntime.createEmbedded(hostRenderDevice);
RenderPipeline pipeline = new RenderPipeline(
        target, scene, instancedRenderer, RenderSettings.builder().build());

runtime.execute(pipeline::build);
```

`RenderDevice` 由 Host 所有。关闭 embedded runtime 不会关闭它。

## 提交一帧

每帧从宿主数据复制不可变相机快照，再显式提交目标：

```java
ExternalCamera camera = new ExternalCamera(
        hostView,
        hostProjection,
        hostViewProjection,
        hostCameraPosition,
        partialTick,
        nearPlane,
        farPlane,
        cameraRevision);

PresentationResult result = runtime.execute(() ->
        pipeline.render(hostRenderDevice, camera, target, deltaSeconds));
```

`ExternalCamera` 会复制矩阵和位置；宿主后续修改原对象不会改变已经提交的帧。几何、
可见性、光照、阴影、雾和 camera-aware HDR VFX 使用这同一份快照。

当目标尺寸为 `0×0` 时返回 `SKIPPED_ZERO_EXTENT`，不录制 pass、不上传帧数据，也不触发
fatal error。窗口恢复后直接提交新的非零目标即可。

## VFX 与 UI

需要宿主相机的 HDR VFX 使用：

```java
pipeline.hdrVfxWithCamera((resources, commands, frameCamera) -> {
    PresentationTarget frameTarget = resources.framePresentationTarget();
    vfxRenderer.record(
            commands,
            effectSnapshot,
            frameCamera.projection(),
            frameCamera.view(),
            sceneDepthTexture,
            frameTarget.width(),
            frameTarget.height());
});
```

该配置必须在 `pipeline.build()` 前完成。最终合成与后处理仍写入本帧 `PresentationTarget`。

独立 UI 提交使用显式目标：

```java
uiRenderer.record(snapshot, commands, target);
```

UI 的 viewport、scissor 和 sRGB 策略取自目标，不绑定默认 framebuffer，也不 present。
由 `UiSystem` 挂接到 pipeline 时，会自动使用 RenderGraph 当前帧的 presentation target。

## Resize、世界切换与关闭

1. Host 停止向旧目标提交新帧；
2. Host 创建新的 FBO/texture；
3. 构造新的 `PresentationTarget` 并增加 generation；
4. 在渲染线程提交新目标；
5. 确认旧目标不再被宿主使用后，由 Host 自己删除旧资源。

Haikalat 不会删除旧或新 borrowed handle。Haikalat 自建目标应使用
`OwnedPresentationTarget`，并由对应 owner 在创建线程关闭一次。

关闭顺序示例：

```java
runtime.execute(pipeline::close);
runtime.close();
// Host 最后按自己的生命周期关闭 RenderDevice、FBO、texture 和 context。
```

## 验证

```text
gradlew embeddedJvmVerification
gradlew embeddedGlVerification
```

第二个任务需要本地 OpenGL 4.6 桌面环境，覆盖非零 FBO、color/depth、目标替换、
borrowed 生命周期、zero extent、UI/VFX/postprocess 和 GL 状态恢复。
