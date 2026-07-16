# PBR Demo assets

`studio-small.hdr` 是项目自有的确定性 4×2 Radiance RGBE 测试环境，由简单常量色块组成，
用于 PBR Demo、自动化预计算和像素回归。它随 Haikalat 项目采用相同许可发布。

Demo 中暂时复用仓库已有的 `wall.png` 与 `awesomeface.png` 来覆盖五种纹理角色；
角色仍按各自要求以 sRGB 或 linear storage 独立加载，缺省路径则使用引擎的 1×1 fallback。
