# 公共 API 分类合同

状态：v0.23 M7 当前有效合同。

v0.20 的域统计和包边界快照见
[v0.20 API 与包边界清单](../v0.20-api-boundary-inventory.md)。

main source set 的每个 public 顶层类型必须恰好出现在一个域清单中。每行格式为
`<stable|advanced|internal> <fully-qualified-class-name>`。

- `stable`：应用推荐依赖的兼容入口；删除需要迁移记录。
- `advanced`：诊断、扩展或低层控制入口；次版本可收紧，但必须更新 changelog。
- `internal`：因跨包实现暂时 public，不构成兼容承诺，只允许本域或 Demo 证明调用。

清单按 `animation`、`backend`、`core`、`runtime`、`render3d`、`postprocess`、`resources`、
`ui`、`text`、`vfx`、`windowing` 十一个真实代码域拆分。路线图初稿遗漏了 backend；v0.14 实施时补入，避免
backend public 类型逃出穷尽校验。`animation` 在引擎演进阶段作为无 GL 调用的兄弟
subsystem 独立管理；`resources` 保存宿主无关的资源身份、代次和 CPU 解码协议，两者均由
架构测试禁止引用 backend 或直接调用 OpenGL；`vfx` 同样只保存效果资产、实例模拟和帧快照，
具体 GPU 适配位于 render3d 域；`util` 当前归入 core 域。

Milestone 4 的 `AnimationMixer/BoneMask/RootMotionDelta/TwoBoneIkSolver`、
`GpuParticleExperiment`、`VolumetricLightPass` 与 `UiAnimationDiagnostics` 均分类为 `advanced`。
其中动画和 UI 诊断保持 GL-free；GPU 粒子与体积光是经过真实像素、性能和稳定性验证的受控实验，
不构成完整编辑型 VFX/volume API 的 stable 承诺。详细限制见
`docs/guides/milestone4-api-performance-limitations.md`。

`PublicApiCatalog` 与 `ArchitectureBoundaryTest` 校验未分类、重复、陈旧、错域、stable
第三方签名泄漏和 internal 越域调用。反射检查覆盖导入后的简单类型，不依赖源码中出现完整包名。
稳定基线见 `stable-baseline.allowlist`，迁移记录见 `migrations.md`。

成员级新增 API 如果承担诊断合同，还必须在对应的成员合同文档中记录统计边界。v0.17 新增的
`CommandBuffer` 录制计数见 [command-buffer-diagnostics.md](command-buffer-diagnostics.md)。
v0.23 新增的 optional cascade settings 与 bounded Render3D diagnostics 均分类为
`advanced`，合同见 [M6 CSM](../v0.23-render3d-m6-cascaded-shadows.md) 与
[M7 可见性诊断](../v0.23-render3d-m7-visibility-diagnostics.md)。
