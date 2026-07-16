# v0.11 UI Native 第三方依赖基线

状态：v0.11 RC 依赖与字体资产归档。此文件记录已引入的 native binding、上游许可证和确定性字体。

## 已引入依赖

| Gradle 坐标 | 固定版本 | 实际 native 基线 | 许可证 | 用途 |
| --- | --- | --- | --- | --- |
| `org.lwjgl:lwjgl-yoga` | `3.3.3` BOM | 由 LWJGL 3.3.3 构建固定 | LWJGL BSD-3-Clause；Yoga MIT | Flexbox 布局适配器 |
| `org.lwjgl:lwjgl-freetype` | `3.3.3` BOM | FreeType 2.13.2 | LWJGL BSD-3-Clause；FreeType License 或 GPL-2.0，项目选择 FreeType License 路径 | 字体加载与 glyph 光栅化 |
| `org.lwjgl:lwjgl-harfbuzz` | `3.3.3` BOM | HarfBuzz 8.2.0 | LWJGL BSD-3-Clause；HarfBuzz MIT-style | Unicode shaping |
| `net.java.dev.jna:jna` | `5.18.1` | JNA 5.18.1 bundled dispatcher | Apache-2.0 或 LGPL-2.1-or-later，项目采用 Apache-2.0 路径 | Java/native ABI bridge |
| `net.java.dev.jna:jna-platform` | `5.18.1` | JNA Platform 5.18.1 | Apache-2.0 或 LGPL-2.1-or-later，项目采用 Apache-2.0 路径 | Win32 User32/Kernel32 类型映射 |

三个模块沿用根构建的 `detectLwjglNatives()` 和统一的
`implementation + runtimeOnly <native classifier>` 解析方式。当前 Windows x64 已有 native create/version/destroy smoke；
Linux、macOS、ARM 和 Windows x86 必须在对应目标平台完成 classifier resolution 与 native smoke 后，才能声明平台支持。

Windows IME 里程碑将 JNA/JNA Platform 固定为 5.18.1。`imm32.dll` 使用项目内最小
`StdCallLibrary` 接口声明，不引入自制 JNI 二进制；JNA 类型只允许出现在
`subsystems.windowing.input.win32`。发布归档需包含所选 Apache-2.0 路径的 JNA 版权和许可证文本。

## 确定性字体资产

仓库内建 `src/main/resources/ui/fonts/NotoSansSC-VF.ttf`，来自官方
`notofonts/noto-cjk` 的 Sans2.004 Noto Sans CJK Simplified Chinese variable TTF：

- 文件大小：17,773,244 bytes；
- SHA-256：`763146584CF0710223441356B4395E279021B0806C196614377A7A0174AE074A`；
- 许可证：SIL Open Font License 1.1；
- 完整来源、版本和 hash：`src/main/resources/ui/fonts/FONT-MANIFEST.md`；
- 完整许可证副本：`src/main/resources/ui/fonts/OFL-1.1.txt`。

该字体未经裁剪，UiDemo、纯 JVM FreeType/HarfBuzz 测试和真实 GL glyph pixel proof
使用同一 classpath 资源，不依赖开发机字体目录或在线服务。若后续生成子集，必须更新 manifest，
保存工具版本、命令、Unicode 范围和新 hash，并重新检查 OFL Reserved Font Name 条款。
