# 公共 API 迁移记录

本文件只记录从稳定 API 基线删除或破坏性替换的类型。删除
`stable-baseline.allowlist` 中的类型时，必须在这里说明替代入口和迁移方法。

v0.14 没有删除 stable 类型。`AssimpModelLoader` 属于未完成的实验入口，不在稳定基线中；
使用者应将源资产离线转换为 glTF，并改用 `GltfAssetLoader` 与 `GltfRuntimeLibrary`。

v0.15 没有删除 stable 类型。advanced `PassProfile` 保留原三参数构造器；新增 GPU 状态、样本帧身份、
年龄和 skipped 计数。旧调用方可继续读取显式传入的 `gpuNanos`，新诊断调用方应先检查
`gpuStatus()==AVAILABLE`。advanced `FrameProfile` 保留双参数构造器，并新增 frame sequence 与
`gpuTotalComplete()`。

v0.16 没有删除 stable 类型。advanced `MeshData` 保持原 record component 和构造签名，新增
`localBounds()`；advanced `Mesh`/`Mesh.Builder` 新增不可变 `Bounds3f` 读取与显式设置入口。
旧 builder 无法从 POSITION semantic 推导范围时会保守返回 unbounded，不会错误裁剪。

v0.17 没有删除 stable 类型或修改 `SceneObject` record component。advanced `SceneObject` 新增
`fixed(Mesh, Material, Matrix4fc[, boolean])`，用于明确表达可缓存的防御性复制固定矩阵；现有构造器
与任意 `ModelUpdater` 保持动态、每帧一次语义。`Transform` 的 revision 与 queue/cache 类型不公开。

v0.17.2 没有删除 stable 类型。advanced backend `GlDebug` 新增 `contextStateEpoch()`，只用于
`GlRenderDevice` 在提交边界同步 native 资源删除代次；普通调用方不应以该值实现资源所有权或跨 context
同步。无 current context 时返回固定标记，正式资源生命周期仍由各 `GlResource.close()` 管理。
