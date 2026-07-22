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
| `GraphPreviewRenderer` | 惰性独占 preview output、临时 resolve、shader/quad/sampler；只借用 graph/PBR source | `GraphPreviewGlTest`、`runPreviewIntegration`、重复 panel 开关/close |

PBR 和 UI 的清理仍保留各自专用事务，因为它们包含 context affinity、history/atlas lease 与
故障注入阶段。`CloseStack` 只在 glTF runtime 中消除已经重复的逆序关闭语义，不提升为公共 API。

preview 关闭或 pipeline close 时先撤销逻辑 output mapping，再逆序关闭 resolve/output/sampler/quad/program。
UI snapshot 只保存 `UiImageId`；即使 snapshot 延迟消费，UiRenderer 也会重新解析 mapping，失效时跳过
该 batch，因此不会持有或使用已释放的 preview texture。被检查的 graph attachment/cubemap 始终由原 owner
管理，preview 不延长其生命周期。

## OpenGL 删除代次与状态缓存

OpenGL 允许删除资源后立即复用相同的数值名称。数值相同不代表对象相同，因此 wrapper 成功执行
`glDelete*` 后必须通过 `GlDebug.closeResource()` 推进当前 context 的 deletion epoch。资源是否开启
diagnostics tracking 不影响 epoch；构造失败但从未对外发布的临时 handle 不需要污染已提交状态。

`GlRenderDevice` 在每个 `CommandBuffer` 提交边界读取一次 epoch。值发生变化时，整份 `StateCache`
失效，然后照常执行命令；同一批命令内部仍保留状态折叠，未发生删除的稳定帧只做一次无锁 O(1) 比较。
这条防线覆盖 VAO、program、texture、sampler、buffer 与 framebuffer，不要求各 wrapper 反向持有 device。

epoch 以当前 `GLCapabilities` identity 为 context key。多 context 的删除不会污染另一个 device；
`GlDebug.releaseCurrentContext()` 必须在销毁 context 前调用以撤销 callback、资源 registry 和 epoch 条目。
没有 current capabilities 时读取返回固定标记，不查询 GLFW、不创建窗口，也不隐式初始化 native 系统。

pipeline/graph owner 在大规模 rebuild 边界仍可显式调用 `RenderDevice.invalidateState()`；这是防御性边界，
不是用来替代 wrapper 的删除通知。禁止缓存带已关闭 native handle 的完整 `CommandBuffer` 跨 generation 重放。
