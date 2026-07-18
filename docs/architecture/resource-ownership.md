# GPU 与 native 资源所有权合同

状态：v0.14 当前有效合同。

## 基本规则

- 创建资源的 owner 负责关闭；借用引用不得关闭。
- `close()` 必须幂等，失败时继续释放其余资源，并把后续失败记录为 suppressed exception。
- 部分构造使用局部回滚栈，成功后显式移交所有权；回滚栈不承担调度、引用计数或全局注册。
- OpenGL owner 的创建、使用和关闭必须位于拥有当前 context 的 render thread。
- 关闭后的公开操作必须 fail-fast；只读诊断方法可明确例外。
- 共享 library 不得在 active lease 尚存时关闭。

## 公开 owner 审计

| owner | 所有权与线程约束 | 关闭/失败证明 |
| --- | --- | --- |
| backend `GlResource` 实现与 `RenderTargetManager` | 独占 GL handle；当前 context | backend resource 与真实 GL 测试 |
| `UploadSystem`、`GlRenderThread`、`FrameDriver` | 独占 worker、队列或 frame 生命周期 | upload/runtime shutdown 测试 |
| `GlfwWindow`、`TextInputAdapter` | 独占 GLFW window/native hook | window、synthetic IME、native soak |
| `RenderGraph`、`InstancedMeshBatch` | 独占 graph target 或实例 buffer | graph/mesh close 与 GL 回归 |
| `TextureAssetCache`、`HotReloadableShader` | cache 内资源为 owned，返回值为 borrowed | asset/hot-reload 测试 |
| `GltfRuntimeLibrary` | 独占 shader、fallback texture、sampler；scene asset 持 lease | `CloseStackTest`、active asset 与 upload fault tests |
| `GltfSceneAsset` | 独占 mesh/material/texture/sampler，借用 runtime library | 四阶段 upload fault、重复 close、lease tests |
| PBR owner | 独占 environment、fallback、binder/background renderer | environment fault injection 与 PBR GL tests |
| `UiSystem`、`UiDocument` | system 独占 native/render/layout 服务；document 为逻辑 owner | UI integration、async、soak |
| UI font/atlas/render owner | 独占 FreeType/HarfBuzz handle、atlas page、GPU upload ring | shaping/atlas/renderer close tests |

PBR 和 UI 的清理仍保留各自专用事务，因为它们包含 context affinity、history/atlas lease 与
故障注入阶段。`CloseStack` 只在 glTF runtime 中消除已经重复的逆序关闭语义，不提升为公共 API。
