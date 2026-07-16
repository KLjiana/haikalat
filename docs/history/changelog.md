# 变更记录

## Unreleased

- 清理 v0.11 RC 发布资产：忽略 HotSpot 崩溃产物，并将 Unifont 与 Noto Sans SC 统一归档到内建字体目录和许可清单。
- 更新项目目标与后续计划，记录 UI subsystem 已完成首轮实现；调试器和编辑工具改为允许基于稳定公共 API 渐进演进。
- 将 native/IME soak、Windows 人工矩阵、公共 API 收口和 UI 分配/批处理优化列为 `0.11.0` 正式版门槛。
- 增加 `runUiSyntheticImeSoak`、`runUiNativeSoak` 和按真实时间退出的 `runUiInteractiveSoak`；当前候选已通过 100 轮 synthetic IME 与 50 轮 Win32 窗口/hook/GC 生命周期。
- 修复活动 `TextField` 已关闭后文本输入同步仍尝试取消其 composition 的失效节点访问，并把内建 Unifont 回归改为 classpath 资源验证。

## 已完成路线图

已完成的里程碑集中记录在这里，规划文档只保留后续工作和仍需验证的内容。

### v0.1-stable-demo

- 精简 LWJGL 依赖，并按操作系统选择原生库。
- 将 Demo 源码拆分到 `src/demo/java`，保留可直接从 IDE 启动的入口。
- 在 README 中补充运行环境和启动方式。
- 修复窗口 resize、零高度投影、空场景 clear/present、异步初始化和资源清理问题。

### v0.2-tested-core

- 为 `VertexPacking`、`RenderGraph`、`UploadSystem` 和 `TripleBuffer` 增加纯 JVM 测试。
- 增加 Windows/Linux CI 编译和无 OpenGL 上下文单元测试。
- 收紧 RenderGraph pass 依赖与命令捕获规则。
- 增加正式 framebuffer blit 命令，减少 `custom()` 使用。

### v0.3-materials

- 分离材质模板和材质实例状态。
- 增加类型化 `UniformValue`、UBO、采样器配置和明确的资源所有权规则。

### v0.4-postprocess-graph

- 将 FXAA 和 TAA 接入 RenderGraph pass。
- 增加后处理模式选择、纹理命名约定、TAA jitter/history 验证和 resize history reset。

### v0.5-instancing-framebuffer

- 扩展实例批处理，支持按 mesh 分组提交、同步、persistent mapping 评估、统计和可替换实例布局。
- 增加 MRT、深度纹理、framebuffer descriptor、render target 生命周期和 resize 重建流程。

### v0.6-engine-surface

- 建立最小 backend 边界，引入 `RenderDevice` 和资源 barrier 概念。
- 增加场景数据、forward pipeline、基础光照、deferred pipeline 评估和方向光阴影。
- 增加模型加载、纹理缓存、shader 热重载、资源定位和可配置 Demo 场景。
- 增加 pass 级 GPU 计时、CPU/GPU frame profile、调试叠加数据、GL object label 和渲染错误分类。

### v0.7-stable-assets

- 完成稳定资产闭环：校验 manifest 引用，打包自有 OBJ 基准模型，并通过 `ModelAssetManager`、`ObjModelLoader` 映射到场景 renderer。
- 定义 `.properties` 作为资产 manifest 和简单 Demo 场景绑定格式，统一 builtin mesh 名称并清理重复配置。
- 引入纯数据 `MeshData`、`MaterialDef`，将 `Mesh.from(MeshData)` 和 `Material` 明确为 OpenGL 资源边界。
- 将 Minimal、Async Demo 的窗口、render target、mesh、frame driver、上传和清理迁移到引擎 API，移除默认包旧入口及私有重复 shader。
- 用不可变 latest-frame snapshot 替代裸引用 `TripleBuffer`；AsyncDemo 只在矩阵 UBO 上传成功后发布匹配的帧元数据。
- 增加 `FrameClock`、`PeriodicTimer` 和统一的 `RenderStatistics`，分离 present FPS、CPU submit 与 GPU 时间，避免输入事件改变动画速度。
- 固定 `subsystems/runtime -> core -> backend` 依赖方向，清除 backend 对 core 以及 core 对 runtime 的反向依赖。
- 拆分 `RenderPipeline` 内部职责，收紧 `PassResources`、`RenderDevice` 和 RenderGraph 的公开边界。
- 增加 resize、异步线程失败/超时/清理、资源生命周期、架构依赖和隐藏窗口像素回读测试。

### v0.8-instanced-shadow-hdr

- 为实例 renderer 增加显式 `castShadows`，普通 caster 与实例 caster 共享方向光 shadow pass，并分别统计数量。
- 修复多方向光阴影关联：上传明确且有界的 shadow light index，最终 shader 不再默认把阴影套到第 0 盏灯。
- 增加完整的最终像素回归，覆盖有光/无光、受光区/阴影区、移动 caster 和改变方向光后的阴影更新。
- 建立兼容优先的 HDR 配置：默认维持 LDR，显式 `ToneMappingMode.ACES` 使用 exposure、ACES fitted curve、clamp 和 gamma 编码。
- HDR geometry、MSAA resolve、TAA history/accumulation 使用 `RGBA16F`，tone-mapping 输出使用 `RGBA8`；TAA 在线性空间累积，FXAA 在显示空间执行。
- 完成 NONE、MSAA、FXAA、TAA 四条 LDR/HDR pass 数据流、resize 重建和真实 GL 像素验收。
- 将 fullscreen pass 的 blend、depth test、depth mask、cull 和 framebuffer sRGB 状态改为 pass 显式所有，避免继承上一 pass 或上一帧状态。
- 将 `CommandBuffer` 改为紧凑 typed opcode stream，并引入 pending pipeline state；只在 draw、clear、blit、dispatch、query、pass 和 `custom()` 等可观察边界提交最终差异。
- 保留 `StateCache` 的跨命令、跨帧去重；`custom()` 成为 flush 后完整失效屏障，透明排序和 RenderGraph pass 顺序语义保持不变。
- 增加正式 indexed-instanced draw：Cube 使用 8 个 bit 解码角点和 36 个 `GL_UNSIGNED_BYTE` 索引，Quad 使用 4 个逻辑顶点和 6 个索引，均不需要 Cube/Quad VBO。
- 增加 16 字节 `std430 uvec4` compact instance SSBO、对齐的 persistent-mapped ring、slot fence 和 dirty-range 更新，保留 Matrix4f dynamic 模式作 A/B 对照。
- 扩展 procedural shader generator，从 topology 生成专用 GLSL、Java catalog、索引数量与类型，生成结果只进入 `build/generated`。
- StressDemo 增加 `gpu`、`indexed`、`indexed-ssbo`、`dynamic` 路径以及 Triangle/Quad/Cube 旋转控制；输出 present FPS、CPU/GPU 平均值与中位数、draw call、状态跳过率和 VS invocation。
- 增加 EmptyWindowDemo 与 Nsight 启动脚本，作为同分辨率、clear、present、VSync 和 debug 条件下的无绘制基线。

### v0.9-color-bloom-closeout（0.9.0，2026-07-15）

#### 色彩空间与 LDR/HDR 输出

- 引入明确的 `TextureColorSpace.LINEAR/SRGB` 契约；颜色纹理使用 `GL_SRGB8` 或 `GL_SRGB8_ALPHA8`，法线、roughness、metallic、遮罩和查找表继续使用 linear 格式。
- texture cache key 现在包含资源路径、flip 和 color space，避免同一资源按不同语义加载时错误复用。
- LDR geometry、最终后处理和 TAA history target 统一使用 `SRGB8_ALPHA8`，由 RenderGraph 根据 attachment 格式负责 framebuffer sRGB 状态。
- NONE、MSAA、FXAA、TAA 的 LDR present 只复制已经编码的字节；无论默认 backbuffer 被驱动报告为 linear 还是 sRGB，都不会漏编码或重复编码。
- HDR/ACES 继续在线性 `RGBA16F` 中渲染和累积，并由 tone-mapping shader 显式 gamma；对应 pass 显式关闭 framebuffer sRGB，避免双重转换。
- MinimalDemo 增加确定性的 sRGB LDR 集成验证，覆盖最终 present 像素，而不仅是纹理格式或非空画面。

#### Bloom 与 RenderGraph 资源

- 增加不可变 `BloomSettings`，支持 threshold、soft-knee、intensity 和 1～8 个 level；默认关闭，不改变旧调用方的 pass 计划和画面。
- RenderGraph 支持相对窗口尺寸 target，并在 resize、奇数尺寸和最小化恢复后按至少 1×1 正确重建。
- Bloom 在线性 HDR scene 上执行高亮提取、逐级 downsample 和 upsample，最终半分辨率结果直接交给 tone mapping 合成，不额外创建全分辨率 HDR combine target。
- 四种 HDR/AA 路径均完成真实 GL 最终像素回归；disabled 路径保持原 ACES 输出，enabled 路径验证亮区扩散且暗部不被错误抬升。
- 增加 pass 级 Bloom GPU 计时和正式五轮性能报告，数据记录在 `docs/performance/post-v0.8-bloom-2026-07-15.md`。

#### 实例批次、同步与异常收尾

- 10 万实例阴影基准达到优化触发条件后，引入 typed single-upload/multi-pass 命令；shadow 与 geometry 复用同一帧 snapshot、同一个 persistent-ring slot 和一次上传。
- GPU fence 只在批次最后一次 draw 后插入；下一帧 ring slot 复用前仍执行完整同步检查。
- `CommandExecutor` 显式跟踪所有已 prepare 但未 finish 的批次；任一 geometry/pass 命令失败时，按逆序完成剩余批次的 fence、snapshot 和 ring 清理。
- 清理阶段的附加异常作为 suppressed exception 保留，原始渲染异常不会被覆盖。
- 增加真实 GL 回归：在 prepared multi-pass 的 geometry 阶段主动失败后，验证 fence 已插入、快照已释放，并且下一帧能够复用 ring 正常绘制。

#### 命令与状态提交

- pending pipeline state 覆盖 blend、depth test、depth mask、cull、viewport、clear color 和 framebuffer sRGB，只在可观察边界 flush 最终状态差异。
- depth clear 前强制提交 `depthMask=true`，避免前序透明或后处理状态阻止深度清除。
- GPU timer query 使用正式 opcode；`custom()` 保持 flush、执行和完整 cache invalidation 的屏障语义。
- 增加状态折叠、draw/pass 顺序、透明物体顺序、depth-mask clear 和真实 GL 状态回归，确认优化不跨越语义边界。

#### 压力测试与运行工具

- 压力入口默认提升到 100 万实例，同时保留 10 万实例的两帧正确性集成，避免把启动检查误当成正式性能结果。
- 统一空窗口、Triangle、Quad、展开 Cube、Indexed Cube、Indexed+SSBO Cube 和 Dynamic Cube 的窗口、分辨率、clear、present、VSync 与 debug 条件。
- 正式基准采用 100 帧预热、1000 帧采样和多轮测试，记录 present FPS、CPU submit、GPU 时间、draw call、状态跳过率及可用时的 VS invocation。
- 详细性能数据记录在 `docs/performance/stress-indexed-ssbo-2026-07-14.md`、`stress-matrix-copy-fix-2026-07-15.md` 和 `stress-one-million-2026-07-15.md`。

#### 发布验证

- Gradle 稳定版本更新为 `0.9.0`，Java Toolchain 保持 21。
- 完整执行 `compileJava demoClasses test localGlVerification --rerun-tasks`，21 个任务全部执行并通过。
- 验收覆盖纯 JVM 测试、真实 GL smoke、LDR/HDR 四种 AA、Bloom、阴影最终像素、resize、Minimal、LearnOpenGL、Async、空窗口以及压力测试入口。
- 验收未发现 GL error、线程悬挂、漏 fence、prepared batch 泄漏、ring 次帧复用失败或 resize 资源错误。
- `git diff --check` 通过；当前能力与限制同步记录在 `docs/planning/capability-matrix.md`。

### v0.10-gpu-auto-exposure（0.10.0-rc.1，2026-07-15）

- 新增 `ExposureMode.MANUAL/AUTO` 和不可变 `AutoExposureSettings`；手动 exposure 继续作为默认值和自动 history 初始值。
- backend 增加 `R16F` 单通道与 `RG32F` 双通道浮点 render target，descriptor 分别使用正确的 `GL_RED`、`GL_RG` 和 `GL_FLOAT` 元数据。
- 新增全 GPU `AutoExposurePass`：按 Rec.709 提取对数亮度，逐级归约到 1×1，再以帧率无关指数公式适应目标曝光。
- reduction target 使用 RG32F 显式携带 `logLuminance sum` 与像素 weight；第一级把 R16F texel 视为 `(value, 1)`，后续级直接累加 RG，彻底移除从尺寸反推权重的逻辑。
- 自动曝光固定 14 级拓扑：前 13 级为 `1/2`～`1/8192` 相对尺寸，末级固定 1×1；resize 只重建 graph target，两个 exposure history 保留，正式支持最大 16384 像素边长。
- 两个持久 1×1 `R16F` history 逐帧 ping-pong；只有整张 RenderGraph 成功执行后才交换，失败帧保留上一张有效 history。
- Tone Mapping 在 AUTO 模式直接采样本帧 exposure texture，生产路径没有 `glReadPixels`、`glGetTexImage`、同步 map 或 `CommandBuffer.custom()`。
- 自动曝光与 Bloom 从同一个 resolve/TAA 后的线性 HDR source 分支，测光不受 Bloom 扩散反向影响；Tone Mapping 同时依赖两条分支。
- `RenderPipeline.execute(RenderDevice, deltaSeconds)` 接入 `FrameClock`，同时限制调试暂停产生的异常大 delta；旧重载保持确定性 1/60 秒。
- Main Demo 增加 `--auto-exposure`、确定性明暗切换集成和模式标题；不为了 UI 显示逐帧回读曝光值。
- RenderGraph 增加窄用途 external-target pass，用正式 framebuffer 命令支持跨帧 history，同时保留 pass 排序和 GPU timer profiling。
- `Scene.setLight()` 只接受保持 `LightType` 与 `castShadows` 的动态更新；结构变化报告 index/字段并要求重新 build pipeline。
- 增加 R16F/RG32F、9×1 完整归约、1280×1 边缘对称、1×1 构建后 resize 到 47×33、灯光结构边界、失败帧 history、四种 AA、Bloom 开关和重复关闭的纯 JVM/真实 GL 回归。
- 1080p/4K 五轮正式基准显示 AUTO GPU median 增量分别为 `0.215 ms` 和 `0.435 ms`；详细数据见 `docs/performance/v0.10-auto-exposure-2026-07-15.md`。
- `clean compileJava demoClasses test localGlVerification --rerun-tasks` 的 23 个任务全部通过，版本进入 `0.10.0-rc.1`。

### v0.11-retained-ui（0.11.0-rc.1，2026-07-16）

#### Tree、layout、控件与输入

- 新增每窗口 `UiSystem/UiDocument/UiNode` retained tree，定义稳定 ID、parent/child invariant、
  dirty propagation、关闭后拒绝使用和单 update-thread 所有权。
- 通过 LWJGL Yoga 实现 row/column、grow/shrink、min/max、percent、padding/margin、gap 和
  intrinsic text measure；Yoga symbol 被架构测试限制在 `subsystems.ui.layout`。
- 增加 typed theme/style、capture-target-bubble event、pointer capture、焦点与 focus scope，
  并提供 Panel、Label、Image、Button、Toggle、Slider、ScrollView、virtualized ListView、
  TextField、Popup 和 Menu。
- GLFW callback 汇总为严格递增的不可变 `WindowInputSnapshot`，明确 logical window、framebuffer、
  content scale、按键边沿、鼠标、滚轮、Unicode commit 和 composition；失焦会合成 release。
- TextField 完成 selection、grapheme deletion、clipboard、undo 和 composition/preedit；Windows
  `Win32TextInputAdapter` 使用 JNA 可链式 WndProc hook，并保持 GLFW char 为唯一 commit 来源。

#### 文本与 GPU 渲染

- 接入 FreeType 2.13.2、HarfBuzz 8.2.0 和 Sans2.004 Noto Sans SC variable TTF；字体来源、
  SHA-256 与 OFL-1.1 完整归档，Demo 与自动测试不依赖开发机字体。
- 新增 Latin/CJK fallback shaping cache、word/CJK/grapheme wrap、cluster-safe ellipsis、DPI ppem、
  glyph rasterization 与有 generation/in-flight guard 的多页 CPU atlas。
- GPU atlas 使用 R8 texture page 和 typed texture-region upload；整批命令成功后才发布 placement，
  失败批次保留并重试，没有 CPU readback 或生产 `CommandBuffer.custom()`。
- UI display list 只合并相邻兼容 primitive，保持透明 paint order；renderer 使用 premultiplied
  alpha、framebuffer sRGB、typed scissor、uint16 EBO、VAO 高水位池和三槽 persistent-mapped ring。
- `GpuFenceTarget.executionFailed` 与命令执行器补齐未到达 fence 的失败通知，确保 draw/upload
  命令中途失败后 ring 和 atlas completion 不停留在 active 状态。

#### RenderGraph、线程与 Demo

- `RenderPipeline.finalPassName()` 成为 UI 与 3D/postprocess 的唯一 composition 锚点；UI 始终在
  最终 backbuffer pass 后绘制，不进入 Bloom、自动曝光、TAA 或 FXAA。
- 新增真实 GL pipeline 矩阵，执行 40 个合法运行项，覆盖 LDR/HDR、四种 AA、Bloom 开关、
  manual/auto exposure 和 UI 开关；每个 UI 组合均产生正式 draw 且无 GL error。
- RenderGraph 可查询 backbuffer pass 并冻结拓扑；每个 pass 基线显式关闭 scissor，避免 UI
  状态泄漏到下一 pass/帧。`StateCache` 增加 scissor 跨命令/跨帧去重。
- snapshot exchange 支持同步双槽和异步三槽 latest-wins。正式异步集成在 12 个 render frame
  发布 36 张 snapshot、丢弃 24 张旧 snapshot，并按 render GL resource → update/native resource
  顺序完成双阶段关闭。
- 新增 `UiDemo`、LearnOpenGlDemo overlay、Nsight 启动脚本以及 deterministic、resize、async、
  text 四条 integration；`localUiVerification` 聚合真实 GL smoke，`localGlVerification` 纳入该任务。
- 修复 ScrollView 内容被 viewport 的 flex shrink 压扁、ListView 只移动 cell 外框却遗漏 Label
  子树，以及 Popup 默认占据左上角的问题；虚拟 cell 现在通过 materialized host 偏移保持完整
  子树坐标，Popup 按 owner 下边缘锚定，UiDemo 启动时不再强制打开菜单。
- 修复组合控件事件只在最深命中节点执行默认行为的问题：默认行为现在沿 bubble 路径执行，
  焦点与 pointer capture 归属于实际请求节点，因此 Button 内文字可点击，ScrollView 和 ListView
  内子节点可响应滚轮，内层消费滚轮后不会再驱动外层滚动。
- 修复全屏 `UiOverlayRoot` 在没有弹窗时仍拦截普通树 hit-test 的问题；overlay 根节点自身不再
  命中，但其 Popup/Menu 子节点仍保持最高命中优先级。UiDemo 标题增加窗口、光标、hit 和 focus
  诊断，便于区分 GLFW 输入、坐标命中和控件焦点。

#### 验证、性能与当前限制

- 默认测试为 349 项，其中无桌面运行通过 289 项并按条件跳过 60 项真实 GL；`glSmoke`
  通过 348 项，仅跳过专用于非 Windows 的 1 项平台测试。`localUiVerification --rerun-tasks`
  的 13 个任务和完整 `localGlVerification --rerun-tasks` 的 25 个任务全部通过。
- 修正高 DPI 隐藏窗口回归：pipeline 像素断言按实际 framebuffer extent 读取中心，不再把
  32×32 logical window 错当为 32×32 framebuffer。
- 修正 Windows monitor content scale 与实际 framebuffer 比例不同导致的 scissor 偏移；GL 裁剪
  现在由 framebuffer/window 尺寸比换算。Demo properties 与 Gradle resource processing 固定 UTF-8，
  文本垂直裁剪增加 1 个逻辑像素的 hinting 保护带，避免字形上下覆盖率边缘被截断。
- 新增 `runUiBenchmarks`，完成 1080p/4K 五轮容量记录。10,000 quads 保持 1 draw，warm atlas
  hit 为 100%，layout/shape/upload 归零；详细数据见 `docs/performance/v0.11-ui-2026-07-16.md`。
- 性能记录同时暴露两个后续优化点：10,000 quads 仍约分配 1.57 MiB/frame，virtual list 的
  相邻 Label 尚未跨节点合并 glyph batch。它们是优化项，不通过跳过 fence 或改变 paint order 掩盖。
- Gradle 版本进入 `0.11.0-rc.1`。Microsoft Pinyin 的候选翻页、DPI、多显示器和 Alt+Tab
  仍按 `docs/guides/windows-ime.md` 在发布稳定版前人工记录。
