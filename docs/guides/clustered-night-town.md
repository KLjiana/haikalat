# Clustered 夜间小镇 Demo

小镇使用程序化低多边形模型：两排八栋木构店铺、双坡屋顶、烟囱、带窗框/百叶的门窗、条纹雨棚、吊牌、钟楼、石板街、灯笼路灯、喷泉、长椅和树木。模型没有外部贴图或下载资产。

## 运行

```powershell
.\gradlew.bat runRender3dClusteredDemo --args="--scene=town --aa=TAA"
```

默认静态相机展示全景。`F` 切换 STATIC / STREET / FREE；FREE 模式使用 WASD 和鼠标。`O` 暂停相机、`L` 暂停灯光动画、`C` 保存截图，`H` 显示帮助。

```powershell
.\gradlew.bat runRender3dClusteredDemo --args="--scene=town --aa=TAA --camera-path=street"
```

## 建模约定

- `ClusteredTownModel` 负责小镇几何，尺寸使用完整世界尺寸，避免单位方块的半尺寸/全尺寸混淆。地面直接在 XZ 平面建模。
- 细节按材质烘焙为 18 个静态 mesh，整套模型由原有 Bundle 关闭。该小型展示场景采用材质级合批；大世界应按街区分块，以保留空间剔除粒度。
- 主立面的窗灯、路灯、店铺与灯笼位置由同一建筑变换生成，避免灯藏入墙体。背街窗为自发光装饰，不额外增加点灯。
- 保留 128 个局部光源：48 窗灯、16 路灯 Spot、16 店铺 Point、16 灯笼 Point、32 喷泉弱光。4 Spot + 2 Point 请求局部阴影，仍使用原有预算。
- 采用冷色夜空/月光和暖色窗灯，固定曝光；喷泉弱蓝光替代原来遮盖地面层次的大范围彩色技能灯。
- 小镇替换不改变 LAB/STRESS 的模型或 benchmark 分布。旧 town 截图属于旧场景，不能作新模型的像素基线。

## 本次验证

- `runRender3dClusteredTownIntegration`：通过，包括 resize。
- `runRender3dClusteredQuality`：通过，两个 480 帧 TAA 序列及其 clustered integration 前置检查。
- 全景和街景实际 GL 截图人工检查；128 局部灯，诊断无 cluster overflow，7 个阴影灯被选中（含方向光）。
- 本次没有重新执行完整性能矩阵，不将画面调整宣称为性能达标。

截图目录：`build/reports/town-remodel/`；质量报告：`build/reports/clustered-quality/b544fcda-457b-48c3-b320-1754e0cd85f8/metrics.json`。
