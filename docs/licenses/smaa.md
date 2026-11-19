# SMAA 1x reference licence and provenance

状态：2026-11-11。用于 Haikalat `AntiAliasingMode.SMAA` 的首版 SMAA 1x 实现。

## 上游

- 项目：SMAA (Subpixel Morphological Antialiasing)，作者 Jorge Jimenez 等。
- 仓库：https://github.com/iryoku/smaa
- 使用提交：`71c806a838bdd7d517df19192a20f0c61b3ca29d`
- 许可：MIT（全文见下）。
- 参考文件：
  - `SMAA.hlsl`（SMAA 1x edge detection / blending weight / neighborhood blending 及 HIGH preset 常数）
  - `Scripts/AreaTex.py`（160x560 RG8 area texture 生成器）
  - `Scripts/SearchTex.py`（64x16 R8 search texture 生成器）
  - `LICENSE.txt`

## 本项目中的派生形式

- `src/main/java/com/kaleblangley/haikalat/subsystems/postprocess/smaa/SmaaLookupTables.java`
  是 `AreaTex.py` / `SearchTex.py` 的逐行 Java 移植，仅移除了图片 I/O；生成数学、偏移表、
  pattern 表与 8-bit 截断保持不变。生成在 JVM 内完成，不提交二进制查找纹理。
- `src/main/resources/shaders/postprocess/smaa-edge-detect.frag`、
  `smaa-blend-weights.frag`、`smaa-neighborhood-blend.frag` 是 `SMAA.hlsl` 的 GLSL 330
  移植（SMAA 1x / HIGH preset：threshold 0.1、16 线性搜索步、8 对角搜索步、25% 圆角），
  并按本仓库颜色链补充 uniform 与注释。
- 没有复制上游的 demo、模型或 DDS 纹理。

## 生成的查找表指纹

| payload | extent | format | SHA-256 |
|---|---|---|---|
| area | 160x560 | RG8 | `35065cef2a02cabcad711d6bf430239ae64e27d71c4e4fa06f29cce2c992f0d2` |
| search | 64x16 | R8 | `3694eae5e9d44b8ebb4415a13f8c7b94dc08a2fc86658434d771c4610fe5744d` |

指纹由上游 Python 生成器在 CPython + Pillow 下独立执行得到；JVM 测试
`SmaaLookupTablesTest` 校验相同指纹与固定采样值。查找纹理按线性过滤 + clamp-to-edge
采样，与参考实现的 sampler 要求一致。

## MIT License

Copyright (C) 2013 Jorge Jimenez (jorge@iryoku.com)
Copyright (C) 2013 Jose I. Echevarria (joseignacioechevarria@gmail.com)
Copyright (C) 2013 Belen Masia (bmasia@unizar.es)
Copyright (C) 2013 Fernando Navarro (fernandn@microsoft.com)
Copyright (C) 2013 Diego Gutierrez (diegog@unizar.es)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software. As clarification, there is no
requirement that the copyright notice and permission be included in binary
distributions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
