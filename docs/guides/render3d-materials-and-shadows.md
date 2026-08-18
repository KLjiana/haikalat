# Render3D 材质、透明与阴影指南

本文描述 v0.23 的稳定合同，面向资产制作者和直接构建 `Scene` 的调用方。完整窗口化示例为
`runRender3dV023Demo`，有限帧自动验证为 `runRender3dV023Integration`。

## 四类 forward queue

| queue | 材质来源 | depth write | blend | 顺序 |
| --- | --- | --- | --- | --- |
| OPAQUE | 默认不透明材质 | 开 | 关 | shader/material/mesh 稳定排序 |
| MASKED | 正 alpha cutoff / glTF MASK | 开 | 关 | shader/material/mesh 稳定排序 |
| ALPHA | `BlendMode.ALPHA` / glTF BLEND | 关 | source alpha | camera-space back-to-front，稳定平局 |
| ADDITIVE | `BlendMode.ADDITIVE` | 关 | additive | 保持提交顺序 |

ALPHA 是对象级排序，不会拆分 mesh 三角形。半透明物体内部自相交、相互穿插或相机位于物体内部时，
应把内容拆成独立对象并控制 pivot/bounds；本版本不承诺 OIT、transmission 或折射。

## glTF alpha

- OPAQUE 保持默认不透明路径。
- MASK 在主 pass 和方向光 shadow pass 使用同一个 `alphaCutoff`。贴图 alpha 是数据的一部分，
  不要通过颜色空间转换改变 cutoff 语义。
- BLEND 映射到 ALPHA queue，并沿用 glTF 的 `doubleSided`。不要用 BLEND 表达硬边叶片、栅栏或
  头发卡片；这类内容应使用 MASK，才能获得稳定 depth 和剪影阴影。

非法 alpha mode、非有限 cutoff 或缺少所需纹理输入会在资产准备阶段失败，不会静默降级为 opaque。

## 阴影策略

OPAQUE 和 MASKED 在 `castShadows=true` 时可进入方向光、点光和聚光 caster 路径。ALPHA/ADDITIVE 默认不投射
阴影，即使 `SceneObject` 沿用 `castShadows=true` 默认值也不会被普通 depth shader 当作不透明物体。
v0.23 不提供透明度积分阴影；需要近似时应由宿主建立独立的 opaque/MASK proxy，而不是修改通用
透明 queue 合同。

方向光默认仍为单级 shadow map。高级设置支持 2～4 级 fixed atlas、practical split、
world-texel snapping 和 split blend。窗口 resize 不重建 fixed atlas；atlas size 或 cascade count
变化属于 pipeline topology 变化。PBR 3x3 PCF 的 tap 被限制在当前 tile 内，不会跨 cascade 采样。

`runRender3dV023Demo` 使用 4096x4096 atlas；4 级布局下每个 tile 为 2048x2048。更高的 atlas 会
增加显存和 shadow fill 成本，应按目标硬件、可见距离与 caster 数量测量后选择。

## Fog、MSAA 与资源生命周期

Fog 需要单采样 scene depth。启用 MSAA 时，pipeline 创建显式 depth resolve 节点并校验源/目标的
format、sample 和 extent；关闭 MSAA 时直接复用 geometry depth，不分配 resolve target。窗口 resize
先构建完整 candidate generation，成功后再替换 active generation；构建或 pass 失败不会发布半成品。

## 验证

```powershell
.\gradlew.bat compileJava demoClasses test
.\gradlew.bat runRender3dV023Integration --rerun-tasks
.\gradlew.bat localGlVerification --rerun-tasks
```

隐藏集成入口固定 12 帧，中途从 640x360 resize 到 800x450，并断言四类 queue、MASK caster、
4x depth resolve、4 级 cascade、固定 atlas、缓存和 diagnostics。无桌面环境只运行前三项中的
JVM 编译/测试；GL 入口需要 OpenGL 4.6 Core 与 GLFW 窗口系统。
