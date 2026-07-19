# 公共 API 分类合同

状态：v0.17 当前有效合同。

main source set 的每个 public 顶层类型必须恰好出现在一个域清单中。每行格式为
`<stable|advanced|internal> <fully-qualified-class-name>`。

- `stable`：应用推荐依赖的兼容入口；删除需要迁移记录。
- `advanced`：诊断、扩展或低层控制入口；次版本可收紧，但必须更新 changelog。
- `internal`：因跨包实现暂时 public，不构成兼容承诺，只允许本域或 Demo 证明调用。

清单按 `backend`、`core`、`runtime`、`render3d`、`postprocess`、`ui`、`windowing`
七个真实代码域拆分。路线图初稿遗漏了 backend；v0.14 实施时补入，避免 backend public
类型逃出穷尽校验。`util` 当前归入 core 域。

`PublicApiCatalog` 与 `ArchitectureBoundaryTest` 校验未分类、重复、陈旧、错域、stable
第三方签名泄漏和 internal 越域调用。反射检查覆盖导入后的简单类型，不依赖源码中出现完整包名。
稳定基线见 `stable-baseline.allowlist`，迁移记录见 `migrations.md`。

成员级新增 API 如果承担诊断合同，还必须在对应的成员合同文档中记录统计边界。v0.17 新增的
`CommandBuffer` 录制计数见 [command-buffer-diagnostics.md](command-buffer-diagnostics.md)。
