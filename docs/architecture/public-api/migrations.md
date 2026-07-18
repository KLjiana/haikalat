# 公共 API 迁移记录

本文件只记录从稳定 API 基线删除或破坏性替换的类型。删除
`stable-baseline.allowlist` 中的类型时，必须在这里说明替代入口和迁移方法。

v0.14 没有删除 stable 类型。`AssimpModelLoader` 属于未完成的实验入口，不在稳定基线中；
使用者应将源资产离线转换为 glTF，并改用 `GltfAssetLoader` 与 `GltfRuntimeLibrary`。
