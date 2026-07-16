# v0.11 UI 公共 API 分层

v0.11 RC 的 Java 可见性不是兼容性承诺。UI 实现存在跨子包协作，因此部分实现类型暂时仍为
`public`；兼容边界由 [`ui-public-api.allowlist`](ui-public-api.allowlist) 明确分类，并由
`UiPublicApiSurfaceTest` 强制保持穷尽且互斥。

## 分类语义

- `stable`：面向普通调用方的首选入口，包括 `UiSystem`、`UiConfig`、`UiDocument`、
  `UiNode`、widgets、焦点管理、布局矩形和主题/样式值。正式 `0.11.0` 后变更需考虑源码与行为兼容。
- `advanced`：事件监听、自定义测量、字体/图像注册相关 handle、文本 shaping/layout 与诊断值。
  可以使用，但调用方应预期次版本仍可能收紧契约。
- `internal`：renderer、batch/display-list/snapshot、GPU glyph atlas、Yoga adapter、atlas
  allocation/upload 和 cache 协议。公开可见只为项目内跨包实现服务，不承诺兼容，Demo 不应直接依赖。

`WindowInputSnapshot` 和平台文本输入 adapter 位于 windowing subsystem，不属于本 UI 目录的
allowlist；它们继续作为 UI 的稳定输入边界单独维护。

## 修改规则

新增或移动顶层 `public` UI 类型时，必须在同一提交中选择唯一分类。架构测试会同时拒绝未分类
类型、重复分类和已经不存在的陈旧条目。把 internal 提升为 advanced/stable 需要补充公开用例、
Javadoc、生命周期约束和至少一个非实现包调用测试。

## 大类职责审计

| 类型 | 当前职责 | 结论与下一提取点 |
| --- | --- | --- |
| `FontFace`（818 行） | FreeType face 与字体字节所有权、variation、metrics、glyph load/raster | 保持一个真实 owner；后续可提取 `FreeTypeFaceHandle` 管理 native address/close，但必须先有失败构造和重复关闭测试，不能只包一层转发。 |
| `UiGlyphAtlasGpu`（583 行） | atlas page 纹理所有权、dirty upload、generation lease、上传结果 | 与 slot-owned primitive arena 一起设计 `AtlasUploadTransaction`；事务必须保留异常收尾和 generation 一致性。 |
| `YogaLayoutEngine`（571 行） | Yoga node 映射、style 翻译、measure callback、布局回写 | 优先提取 native node ownership/registry；style 翻译仍留在 engine，避免把每个 Yoga 调用拆成薄包装。 |
| `UiSystem`（565 行） | 公共 facade、update 阶段编排、IME 协调、snapshot 发布、renderer 生命周期 | 最优先候选是 text-input coordinator，其边界已由 synthetic IME soak 证明；frame snapshot builder 在 primitive arena 方案确定后再提取。 |
| `UiDisplayList`（审计时 559 行） | primitive 存储、相邻 batch、文本 caret 信息、snapshot 复制 | 已接入 slot-owned primitive arena；下一步只在 allocation profile 证明有收益时提取 primitive writer，不拆薄包装。 |
| `TextField`（449 行） | value/selection、composition、undo/redo、键盘编辑和水平视口 | 仅在编辑行为测试完整后提取纯 JVM text-edit model；节点事件与绘制状态仍归 widget。 |

该审计优先围绕 ownership、事务和协调边界，不以缩短文件为目标。
