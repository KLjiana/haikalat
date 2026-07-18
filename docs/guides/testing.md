# Testing

This project uses three test categories. The default Gradle `test` task must stay safe for CI machines without a desktop OpenGL environment.

## Unit

Pure JVM tests that do not create a GLFW window, initialize GL capabilities, compile real shaders, upload textures, or allocate real framebuffers.

Use this category for:

- data structures and descriptors
- command recording order
- asset/config parsing
- pure mesh data and model parsing
- RenderGraph topology and no-context behavior
- runtime statistics and error classification

Naming/package convention: ordinary `*Test` classes outside `com.kaleblangley.haikalat.integration`, with no special system property.

Default command:

```powershell
.\gradlew.bat test
```

## GL Smoke

Opt-in tests that create a hidden GLFW window and a minimal OpenGL context. These verify that the local graphics stack can execute basic GL work and that runtime GL resources enforce their lifecycle boundaries.

Use this category for:

- shader compile/link checks that need real GL
- framebuffer creation, resize, completeness, and lifecycle checks
- texture upload and sampler behavior
- asset cache behavior that depends on real `Texture2D` or `ShaderProgram`
- minimal pixel readback or clear validation

Naming/package convention: `*SmokeTest` under `com.kaleblangley.haikalat.integration`, guarded by:

```java
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
```

需要访问 package-private 渲染内部状态的真实 GL 回归可以与被测类同包放置，但仍必须使用相同的
`EnabledIfSystemProperty` 守卫，保证默认 CI 不创建窗口或 OpenGL context。

Command:

```powershell
.\gradlew.bat test "-Dhaikalat.glSmoke=true" --rerun-tasks
```

## Integration

Manual or environment-specific checks that exercise full demo or pipeline behavior. These may open visible windows, depend on GPU/driver behavior, or require screenshot/pixel comparison beyond a minimal smoke check.

Use this category for:

- demo startup and resize workflows
- end-to-end scene pipeline behavior
- shadow/postprocess visual validation
- longer-running frame loop checks

Naming/package convention: `*IntegrationTest` under `com.kaleblangley.haikalat.integration`, or manual demo entry points in `src/demo/java`. Integration tests must remain opt-in through a system property or a separate Gradle task if they are automated later.

CI should run only the default unit path unless the environment explicitly provides a desktop GL context.

The deterministic baseline integration uses a hidden 1280x720 window, fixed camera and frame indices, disabled VSync, and exits after eight frames:

```powershell
.\gradlew.bat runDemoIntegration
```

Deterministic resize and async render-thread integrations:

```powershell
.\gradlew.bat runDemoResizeIntegration
.\gradlew.bat runMinimalIntegration
.\gradlew.bat runAsyncIntegration
.\gradlew.bat runEmptyWindowIntegration
.\gradlew.bat runAutoExposureIntegration
.\gradlew.bat runStressIntegration
.\gradlew.bat runPbrIntegration
.\gradlew.bat runPbrResizeIntegration
.\gradlew.bat runPbrCompatibilityIntegration
.\gradlew.bat runPbrFailureIntegration
.\gradlew.bat localPbrVerification
.\gradlew.bat runGltfIntegration
.\gradlew.bat runGltfResizeIntegration
.\gradlew.bat localGltfVerification
```

v0.13 glTF 默认测试保持无窗口：`.gltf/.glb`、external/data/GLB embedded 资源、interleaved
与 normalized attribute、sparse accessor、node graph/transform、normal/tangent 生成、URI root
约束、结构化错误、百万元素零基底 sparse accessor、sparse indices/values 完整范围、
`radio.gltf` MASK/cutoff 保留和 BLEND 拒绝都在普通 `test` 中执行。
`localGltfVerification` 额外创建隐藏 OpenGL 4.6 context，验证 embedded PNG、同一 image 的
sRGB/linear 双变体、PBR/IBL/HDR/ACES 最终像素、四阶段上传失败清理、library active-asset
guard、未选 MASK 场景不阻止 OPAQUE shadow instantiate，以及只含 showcase/radio 共 10 个
对象的独立 Demo/resize 链路。

v0.11 UI 的四条有限帧、隐藏窗口验证和聚合入口：

```powershell
.\gradlew.bat runUiIntegration
.\gradlew.bat runUiResizeIntegration
.\gradlew.bat runUiAsyncIntegration
.\gradlew.bat runUiTextIntegration
.\gradlew.bat localUiVerification
```

其中 async 入口由独立 update owner 连续发布三张 snapshot，再由 GL/window 线程消费最新值；
验证会断言 12 个 render frame 中发布 36 张、丢弃 24 张 stale snapshot，并按
render-resource → update/native-resource 顺序等待清理完成。

Run every local check that requires a desktop OpenGL environment:

```powershell
.\gradlew.bat localGlVerification
```

`localGlVerification` also depends on `localUiVerification`, runs MinimalDemo's sRGB LDR target, the deterministic full-GPU automatic-exposure transition, the no-draw empty-window check, the 100000-instance triangle/quad/flattened-cube checks, and dedicated indexed Cube plus indexed+compact-SSBO Cube integrations. It is intentionally not attached to the default `check` task, so headless CI remains safe.

The current GL smoke path additionally verifies project shader compilation, a compute dispatch writing through a named SSBO, an indexed procedural Cube drawn from a compact SSBO with no VBO, aligned persistent mapped compact-ring slots and dirty-range propagation, pending-state collapse/custom barriers, depth-mask-controlled depth clear, opaque/transparent ordering across RenderGraph passes, depth-only framebuffer writes and shader sampling, resource use-after-close behavior, linear/sRGB texture sampling, single-encoded mid-gray output across all LDR AA modes and HDR/ACES, RGBA16F values above 1.0, ACES exposure changes, R16F log-luminance plus RG32F sum/weight reduction/history, 9×1 and 1280×1 edge weighting, a 1×1-to-47×33 resize topology regression, fullscreen state ownership, HDR resize, disabled/enabled Bloom pixels across all four AA paths, the 40-case legal UI/pipeline matrix, the complete scene-to-shadow-to-lighting pixel chain, opt-in instanced shadows with one upload reused by two passes, reverse cleanup after a geometry-stage failure, next-frame ring reuse, an async UBO upload that drives instanced final pixels, PBR device-cache recovery after runtime environment preprocessing, shared legacy/PBR model variants, canonical tangent fail-fast, and three injected IBL preprocessing cleanup stages. Unit tests verify primitive-only state packets, boundary separation, relative target sizing, packed-instance quantization, uint8 topology/winding, HDR/Bloom/LDR-present pass order, exposure/Bloom validation, frame-rate-independent adaptation, topology-preserving dynamic light replacement, ACES reference behavior, range-limited inverse-square PBR falloff, sRGB/float framebuffer metadata, and synchronization between generated GLSL and the Java catalog.

Windows 上同一开关还会启用 `Win32TextInputAdapterSmokeTest`：它只验证隐藏 GLFW 窗口的
WndProc hook 安装、消息链和恢复，不会自动打开真实输入法。Microsoft Pinyin 的候选、DPI、
焦点和多显示器矩阵见 [Windows IME 适配与验收](windows-ime.md)，发布前必须手工记录。

正式自动曝光性能复现：

```powershell
.\gradlew.bat runAutoExposureBenchmarks
```

该任务在 1080p 与 4K 下分别测试 MANUAL/AUTO，每项执行 5 轮，每轮预热 100 帧并统计 1000 帧。

正式 UI 容量性能复现：

```powershell
.\gradlew.bat runUiBenchmarks
```

该任务在 1080p/4K 下分别运行 100 nodes、1,000 nodes、10,000 logical virtual list、
10,000 quads 和 2,000 visible CJK/Latin glyphs。每项执行 5 轮，每轮记录 cold frame、
预热 30 帧并统计 120 帧；当前基准见
[`docs/performance/v0.11-ui-2026-07-16.md`](../performance/v0.11-ui-2026-07-16.md)。

正式 PBR 1080p/4K 五轮性能矩阵：

```powershell
.\gradlew.bat runPbrBenchmarks
```

每个场景预热 100 帧并统计 1000 帧，覆盖 NONE/FXAA/MSAA/TAA 及 manual/auto exposure、
Bloom off/on 的代表组合。当前代表性 FXAA+AUTO+Bloom 五轮记录见
[`docs/performance/v0.12-pbr-2026-07-17.md`](../performance/v0.12-pbr-2026-07-17.md)。

v0.13 glTF 五轮 decode/upload 与 1080p/4K 稳态矩阵：

```powershell
.\gradlew.bat runGltfBenchmarks
```

decode/upload 直接使用原始 MASK `radio.gltf`，不生成或改写 OPAQUE 副本；GPU upload 单独测量
sampler、sRGB image、material 与 mesh 创建，稳态部分使用主 Demo 的 glTF/OBJ/legacy
共存场景，每轮预热 100 帧并正式统计 1000 帧。
当前本机结果见 [`docs/performance/v0.13-gltf-2026-07-18.md`](../performance/v0.13-gltf-2026-07-18.md)。
