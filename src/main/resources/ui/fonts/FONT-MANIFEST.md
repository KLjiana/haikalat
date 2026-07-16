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
