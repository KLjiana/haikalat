# v0.12 PBR 公共 API 分层

PBR 子系统的 Java 可见性不等于兼容性承诺。跨包协作所需的部分实现类型暂时保持
`public`，实际兼容边界由 [`pbr-public-api.allowlist`](pbr-public-api.allowlist) 分类，
并由 `PbrPublicApiSurfaceTest` 保证分类完整且没有陈旧条目。

- `stable`：环境资源、加载入口与预处理设置，面向普通调用方。
- `advanced`：材质组装和缺省纹理所有权，适合自定义资产管线。
- `internal`：pipeline 内部 binder 与背景 renderer，仅因跨包协作保持可见，不承诺兼容。

新增、删除或移动顶层 `public` PBR 类型时，必须在同一变更中更新 allowlist。Demo
专用 sphere、UI 和 benchmark 类型只允许存在于 demo source set，不能进入主 API。
