# 调试与诊断指南

主 Demo 默认使用 `BASIC` 诊断。运行可见 Demo 时按 `F2` 打开/关闭诊断面板；打开时自动释放 GLFW
相机捕获并进入 UI 输入；面板可见期间 `F1` 不会切回 CAMERA，关闭面板后 `F1` 才恢复普通 HUD
控件与相机模式切换。

面板包含 Overview、Passes、Graph、Resources 和 Messages 五页。`Live` 返回实时数据，`Freeze` 固定
同一 epoch 的历史/资源/消息，`Clear` 开启新 epoch，`Filter` 在资源或消息页循环常用过滤器，
`Export` 导出当前 frozen capture。pass 的 pending、skipped、age 会原样显示。各页的明细行可以点击
选择；按住 `Ctrl` 点击可逐项增减，`Shift` 点击可连续选择，`Ctrl+A` 选择当前页全部可见行，
`Ctrl+C` 将所选行按显示顺序复制到剪贴板。

## 命令行

```powershell
./gradlew.bat runLearnOpenGlDemo -PdemoDiagnostics=detailed -PdemoDiagnosticsPanel=true
./gradlew.bat runDiagnosticsIntegration
./gradlew.bat runDiagnosticsResizeIntegration
./gradlew.bat runDiagnosticsFailureIntegration
./gradlew.bat localDiagnosticsVerification
./gradlew.bat runDiagnosticsBenchmarks
```

主类参数：

```text
--diagnostics=off|basic|detailed
--diagnostics-panel
--diagnostics-export=<path>
```

指定 export 不会隐式提升诊断级别；需要完整 graph/resource/message 时必须同时指定 DETAILED。自动任务分别验证 12 帧 capture、960×540 resize 后的 managed/fixed
target、真实 pass callback 失败、active GPU query 之后的命令失败、后续帧恢复、资源正常关闭和
JSON schema。`runDiagnosticsFailureIntegration` 不再把成功帧人工重标记为失败。

Demo 自动导出路径被限制在 `build/diagnostics/`，并拒绝目录穿越和符号链接路径；默认 JSON 省略
OpenGL native id，只有直接调用 exporter 的显式 debug 选项才会包含。导出前会验证有限数值、计数、
frame/pass/graph/resource/message 一致性，并记录引擎版本、构建修订和 OpenGL vendor/renderer/version。
这些文件是临时验证产物，不应提交。比较性能时使用同一窗口、分辨率、
VSync、debug callback、warmup 和采样帧，并优先比较帧时间和 paired round，而不是只看 FPS。

当前不提供 RenderGraph 编辑、texture 像素预览、远程 telemetry、shader 热重载或外部 GPU debugger
控制。Nsight/RenderDoc 可以直接识别 `RenderGraph/<pass>` debug group 和已有 GL object label。

## Scene visibility

主 Demo 的 Overview 和 schema-v1 `scene.visibility` section 显示普通 renderer 的候选、有限/
unbounded、forward 可见/裁剪、shadow 候选/可见/裁剪、opaque/additive/alpha draw、相邻 shader/
material/mesh/blend/mirrored 变化，以及 model、bounds、frustum、sort 和总 queue build 时间。
v0.17 还显示 static/dynamic、model/bounds hit/miss、forward/shadow reuse/rebuild、command record、
recorded command、primitive matrix snapshot 与 object payload。它们都是冻结后的计数值，不暴露 live
SceneFrame、矩阵 arena 或 renderer 引用。
必须满足 `visible + culled == candidates`；shadow 使用自己的候选等式。BASIC 保留计数和总时间，
DETAILED 才保留各阶段时间与 key-change 明细；两种层级都不会导出逐对象矩阵或 renderer 列表。
SceneFrame 构建失败时该帧 visibility 为 unavailable，不复用上一成功帧的值。
