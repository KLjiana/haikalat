# UI SDF 基础（0.19.1）

UI 的普通 quad、图片和 glyph 路径保持不变。带有非零 `ComputedStyle.radius` 的 Panel、
Button、TextField 等控件，会在 display list 中记录 `SDF_SHAPE`，由 `UiRenderer` 的独立
SDF shader 变体绘制。

当前基础 API 位于 `subsystems.ui.render`：

- `UiSdfShape`：rounded rect、pill、ellipse/circle、ring 和 progress arc 参数；
- `UiSdfPaint`、`UiGradientStop`、`UiGradient`：填充、边框和线性渐变；
- `UiSdfDecoration`：填充、边框宽度、渐变和未来 compositor shadow 描述；
- `UiDisplayList.addSdfShape(...)`：只记录逻辑数据，不持有 OpenGL 对象。

SDF 的 radius 会在 CPU 侧限制到图元半尺寸，shader 侧使用 `fwidth` 做像素级 coverage。
渐变颜色使用现有 premultiplied-alpha 约定。当前 shadow 仅记录描述，实际 blur/shadow
属于后续 compositor 版本；多层 rounded clip 也仍走原有 scissor/fallback 路径。

验证：

```powershell
.\gradlew.bat test --tests com.kaleblangley.haikalat.subsystems.ui.render.UiSdfPrimitiveTest
.\gradlew.bat runUiSdfIntegration
```
