# 内建 UI 字体清单

## NotoSansSC-VF.ttf

- 用途：v0.11 UI Demo、确定性 Latin/CJK shaping、glyph atlas 与像素回归。
- 上游：`notofonts/noto-cjk` 的 Noto Sans CJK Simplified Chinese variable TTF。
- 参考发布：`Sans2.004`（Noto Sans CJK 2.004）。
- 上游发布页：https://github.com/notofonts/noto-cjk/releases/tag/Sans2.004
- 上游许可证：https://github.com/notofonts/noto-cjk/blob/main/Sans/LICENSE
- 本地资源：`/ui/fonts/NotoSansSC-VF.ttf`
- 文件大小：17,773,244 bytes。
- SHA-256：`763146584CF0710223441356B4395E279021B0806C196614377A7A0174AE074A`。
- 许可证：SIL Open Font License 1.1，副本见 `OFL-1.1.txt`。

该文件是未经裁剪的完整字体，不是依赖当前系统字体目录的运行时引用，因此没有 subset
code-point 清单。若后续为缩小发布包生成子集，必须改用新的字体族名、记录工具版本和完整
命令，并在本文件加入精确 Unicode 范围与新文件哈希。

## unifont-17.0.05.otf

- 用途：UI 字体目录中的可切换等宽覆盖字体，用于缺字诊断与字体管理演示。
- 字体族：Unifont。
- 版本：17.0.05。
- 上游：https://unifoundry.com/unifont/
- 本地资源：`/ui/fonts/unifont-17.0.05.otf`
- 文件大小：5,321,628 bytes。
- SHA-256：`85701AB9B1E251EE16F4DF00B13F22EAC311D72B7DAB427A7D975FE7F5064702`。
- 上游双重许可：SIL Open Font License 1.1，或 GPL-2.0-or-later 加 GNU Font Embedding Exception。
- 本项目选择的发布路径：SIL Open Font License 1.1，许可证副本见 `OFL-1.1.txt`。
- 字体内版权声明：Copyright © 1998–2026 Roman Czyborra、Paul Hardy、Qianqian Fang、Andrew Miller、Johnnie Weaver、David Corbett、Ælla Chiana Moskopp、Rebecca Bettencourt、Ho-Seok Ee 等贡献者。

该资源和 Noto Sans SC 一样由 classpath 字体目录统一管理，不再从项目根目录或系统字体目录读取。
版本、许可选择或二进制内容变化时，必须同步更新本清单和第三方许可归档。

## JetBrainsMono-Regular.ttf

- 用途：ModernUiDemo 的首选等宽字体；Latin、数字和 UI 诊断文本使用该 face，
  缺少的 CJK glyph 继续由 Noto Sans SC / Unifont fallback 提供。
- 字体族：JetBrains Mono。
- 版本：2.304。
- 上游：`JetBrains/JetBrainsMono`，https://github.com/JetBrains/JetBrainsMono
- 导入来源：项目维护者提供的官方 `JetBrainsMono-2.304.zip` 中
  `fonts/ttf/JetBrainsMono-Regular.ttf`，未经修改。
- 本地资源：`/ui/fonts/JetBrainsMono-Regular.ttf`
- 文件大小：273,900 bytes。
- SHA-256：`A0BF60EF0F83C5ED4D7A75D45838548B1F6873372DFAC88F71804491898D138F`。
- 许可证：SIL Open Font License 1.1。
- 上游版权：Copyright 2020 The JetBrains Mono Project Authors。
- 随发行包提供的版权与许可证副本：`JetBrainsMono-OFL.txt`。

三套内建字体的资源路径、字体族和注册/fallback 顺序统一由
`BundledUiFonts` 管理。`FontManager` 继续只负责 native face 和字体族生命周期；
Demo 不再自行读取 classpath 字体文件。
