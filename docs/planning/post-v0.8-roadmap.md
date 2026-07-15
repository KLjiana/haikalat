# v0.8 验收结论与后续方向

状态：2026-07-15 完成架构复核和本机真实 GL 验收。

实现进度：2026-07-15 已完成 v0.8 fullscreen 状态加固、里程碑 6 色彩空间契约与五轮正式基准、
里程碑 7 Bloom 及四条 HDR 路径回归。10 万实例基准触发并完成了单次上传、多 pass 复用；结果记录在
`docs/performance/post-v0.8-bloom-2026-07-15.md`。自动曝光与 PBR 仍按本文保持为后续条件方向。

## 里程碑 4、5 验收结论

| 项目 | 结论 | 证据 |
| --- | --- | --- |
| 实例化方向光阴影 | 完成 | 默认关闭、显式 opt-in、独立 depth shader、复用同一批次和帧快照；里程碑 6 又将 shadow/geometry 收敛为单次上传和同一 ring slot，多 pass 完成后才插入 fence |
| HDR / ACES | 完成 | LDR 默认兼容、`RGBA16F` geometry、ACES exposure、`RGBA8` 输出和高亮范围测试已落地 |
| HDR + MSAA | 完成 | multisampled `RGBA16F` renderbuffer 通过独立 pass resolve 到可采样浮点 texture |
| HDR + TAA | 完成 | 在线性 HDR 空间累积，history 使用 `RGBA16F`，resize 后重建并复位有效状态 |
| HDR + FXAA | 完成 | ACES tone mapping 后在显示空间执行 FXAA |
| Demo 与版本 | 完成 | 主 Demo 显式启用 ACES、FXAA 和实例阴影；版本已进入 `0.8.0-SNAPSHOT` |

本机执行结果：

- `compileJava demoClasses test`：通过。
- `localGlVerification --rerun-tasks`：18 个任务全部通过。
- 覆盖真实 GL smoke、主 Demo、resize、Async、空窗口和全部 10 万实例压力入口。
- 未发现 GL error、线程悬挂、ring 复用失败、漏 fence 或 resize 资源错误。

验收期间工作区出现了一项未提交改动：`LearnOpenGlDemo` 创建 `InstancedRenderer` 时的
`castShadows` 从 `true` 改成了 `false`。提交 `9f7eeff` 和独立真实 GL 回归仍证明实例阴影能力完整，
但如果保留该工作区改动，主 Demo 将不再展示实例阴影，也不再满足原规划的 Demo 验收条款。发布前应
恢复显式开启，或增加确定性 Demo 参数并让集成任务明确以开启状态运行。

因此里程碑 4 可以正式关闭；里程碑 5 的功能与原验收项也已完成，但稳定版发布前仍需完成下面的
fullscreen 状态隔离加固。

## v0.8 立即加固

### 1. Fullscreen pass 状态所有权

当前 HDR TAA 和 ToneMapping 写入离屏 target 时，没有显式关闭 blending 和 face culling。如果 geometry
最后一次 draw 是透明材质，或调用方留下了 cull 状态，fullscreen draw 会继承上一个 pass 的状态。
由于相关 target 使用 `noClear()`，这可能把输出与未初始化内容混合；该问题通常不会产生 GL error，
现有“非空像素”测试无法发现。

修正要求：

- FXAA、TAA 和 ToneMapping 在 fullscreen draw 前显式设置 `blend=false`、`depthTest=false`、
  `cullFace=false`，不得依赖前一个 pass 的状态。
- RenderGraph 在任何 depth clear 前显式提交 `depthMask=true`，避免上一帧后处理状态影响下一帧清深度。
- 不引入通用 push/pop GL state；每个 pass 声明自己真正依赖的状态。
- 增加真实 GL 回归：geometry 最后绘制 alpha material，并预先启用 culling；HDR TAA/ToneMapping 的
  最终像素必须与显式干净状态一致，同时下一帧 depth clear 仍然有效。

完成该项并再次通过 `localGlVerification` 后，才允许把 `0.8.0-SNAPSHOT` 提升为稳定发布候选。

### 2. 文档基线修正

- `capability-matrix.md` 仍把 RenderGraph profiling 描述为 `custom()` escape hatch，但生产路径已经使用
  正式 GPU timer opcode；应与 `render-boundaries.md` 和当前代码统一。
- 性能文档必须区分两帧集成任务与正式预热、采样基准，不能使用集成任务输出判断性能回退。

## 推荐主线：色彩正确性到 Bloom

### 里程碑 6：色彩空间契约与性能基线

当前 HDR target 和 tone mapping 已经工作，但普通 PNG/JPG 纹理仍按线性 `RGB/RGBA` 上传，资源系统没有
区分颜色纹理和数据纹理。进入 Bloom 或 PBR 前，应先固定以下契约：

- 新增 `TextureColorSpace { LINEAR, SRGB }`；保留旧 `Texture2D.fromResource(...)`，默认
  `LINEAR` 以维持兼容，并增加显式 color-space 重载。
- `SRGB` 的三、四通道图片分别使用 `GL_SRGB8`、`GL_SRGB8_ALPHA8`，由采样硬件自动解码到线性空间；
  法线、roughness、metallic、遮罩和查找表必须使用 `LINEAR`。
- 场景 manifest 增加 `texture.<name>.colorSpace=linear|srgb`，默认 `linear`；主 Demo 的 wall/face
  颜色纹理显式标记为 `srgb`。
- texture cache 的键必须包含资源路径、flip 和 color space，允许同一文件以不同语义加载而不错误复用。
- 最终输出继续由 ACES shader 显式 gamma 编码，保持 `GL_FRAMEBUFFER_SRGB` 关闭，避免双重编码。
- 增加纯 JVM descriptor/manifest/cache 测试和真实 GL 采样测试，证明 sRGB 中间值被解码而 linear
  数据保持原值。

同时建立两组正式基准：

1. 相同场景下 LDR 与 ACES 的 NONE/MSAA/FXAA/TAA CPU、GPU pass 成本。
2. 10 万动态实例在 shadow disabled/enabled 下的 CPU、GPU、上传字节和 ring wait 成本。

基准继续使用现有五轮、预热 100 帧、采样 1000 帧的方法。只有实例阴影双提交在至少三轮中稳定增加
`0.5 ms` CPU median，或产生可测 ring wait，才启动单次上传、多 pass 复用协议；否则保持现状。

### 里程碑 7：Bloom

色彩空间契约稳定后，在 tone mapping 之前增加可选 Bloom：

- RenderGraph 增加与窗口相关的相对尺寸 target，例如 1/2、1/4、1/8；相对尺寸与 `fixedSize(...)`
  互斥，resize 后按 `max(1, round(windowSize * scale))` 重建。
- 新增不可变 `BloomSettings`，包含 enabled、threshold、soft-knee、intensity 和 max-levels；默认关闭，
  不改变旧调用方 pass 计划与画面。
- 从线性 HDR scene 提取高亮，执行逐级 downsample 和 upsample；最终 Bloom texture 直接交给
  ToneMapping 组合，避免额外创建一个全分辨率 HDR combine target。
- MSAA 使用 resolve 后的 HDR texture，TAA 使用累积后的 HDR texture，FXAA 仍位于 tone mapping 之后。
- Bloom pass 只通过 RenderGraph 声明资源，不自行创建、resize 或释放 framebuffer。

验收要求：

- disabled 路径与当前 ACES 输出逐像素保持一致。
- enabled 后高亮区域向周围扩散，暗部不会被 threshold 错误提亮。
- 所有相对尺寸 target 在 resize、最小化恢复和奇数尺寸下正确重建，最小尺寸不低于 1×1。
- NONE/MSAA/FXAA/TAA 四条 HDR 路径均有真实 GL 最终像素验证。
- 记录各 Bloom level 的 GPU 时间；性能作为报告项，不挂入跨机器 CI 的硬阈值。

## 条件方向

### 自动曝光

Bloom 稳定后再评估自动曝光。实现前必须先解决帧 delta 输入、`R16F` luminance target、GPU reduction
和跨帧 1×1 exposure history；不得通过每帧 CPU readback 实现。自动曝光应作为独立设置，手动 exposure
继续保留并作为默认路径。

### PBR

当前架构能够继续扩展，但还不应立即启动完整 PBR。开始 PBR 前至少需要：

- 已验证的 linear/sRGB 纹理语义。
- cubemap/environment/IBL 资源生命周期。
- material 的 albedo、normal、metallic、roughness、AO 语义和 tangent 数据路径。
- Bloom/HDR 输出链在多材质场景中的稳定基线。

满足这些门槛后，PBR 应从单一 metallic-roughness forward shader 和一个自有测试资产开始，不同时引入
deferred、ECS、材质图或跨后端抽象。

### 性能与第二后端

- 单次实例上传、多 pass draw 继续由里程碑 6 基准触发，不因抽象看起来更漂亮而提前重构。
- GPU culling、indirect draw 和 bindless texture 继续要求真实多 mesh/material 压力数据。
- Vulkan 或第二后端仍不进入近期计划；当前 RenderDevice 边界应继续由架构测试守卫。

## 推荐顺序

1. 修复 fullscreen pass 状态所有权并关闭 v0.8 加固项。
2. 完成色彩空间契约和 HDR/实例阴影正式基准。
3. 实施 Bloom，作为 `v0.9` 的主功能。
4. 根据数据选择自动曝光或实例单次上传优化。
5. 完成纹理语义与环境贴图准备后，再启动最小 PBR 闭环。
