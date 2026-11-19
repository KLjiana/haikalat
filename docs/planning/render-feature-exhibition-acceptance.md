# 46 项渲染功能展览馆人工视觉验收记录

本文件是[展馆计划书](render-feature-exhibition-plan.md)第 6 批次（人工视觉验收）的登记入口。
自动化证据（hidden GL 集成、逐站 A/B 像素差）见计划书第 8、10–12 节。逐站人工判断记录在本节下方。

## 当前状态

- 候选分支：`features/46-render-features`
- 自动证据：46/46 站接线、hidden 集成 PASS、92 张 A/B 截图归档（`index.csv` 全部 `NOT_EVALUATED`）
- 人工结论：**NOT_EVALUATED**（尚未由观察人逐站查看并登记）
- 性能、显存、多 GPU、长测、发布就绪：`NOT_EVALUATED`

## 复现命令

```powershell
# 博物馆大厅（WASD 无碰撞飞行，空格上升、Shift 下降；可穿过展台进入 #11 水池，在池水范围内观察水下；鼠标环顾，Tab 俯视，走近激活展项）
.\gradlew.bat runRenderFeatureExhibitionDemo

# 入口视图（可见窗口，人工操作 A/B/Reset/Next/Prev；--station=N 直接进入单站）
.\gradlew.bat runRenderFeatureExhibitionDemo --args="--station=8"

# 固定顺序隐藏巡览（不是人工验收）
.\gradlew.bat runRenderFeatureExhibitionIntegration

# 逐站 A/B 截图归档 + index.csv（human_result 列待人工填写）
.\gradlew.bat runRenderFeatureExhibitionCapture
```

归档位置：`build/exhibition/stations/stationNN-A.png`、`stationNN-B.png`、`index.csv`；
单站入口视图：`build/exhibition/entry.png`。地面展馆全景：`build/exhibition/floor-hall.png`。

## 运行身份（每次人工验收填写）

| 字段 | 值 |
|---|---|
| 候选 HEAD | 待填 |
| 设备 / 驱动 | 待填 |
| 分辨率 | 待填 |
| AA / 质量档 | 待填（默认 AA=NONE、ACES、手动曝光） |
| 观察人 | 待填 |
| 日期 | 待填 |
| 截图目录 | `build/exhibition/stations/` |

## 逐站登记

操作：复制 `build/exhibition/stations/index.csv` 到 `build/exhibition/stations/acceptance.csv`，
逐行填写 `human_result`（`PASS` / `FAIL` / `NOT_EVALUATED`）并追加
`observer,device,driver,resolution,aa,defect_link` 列；观察到缺陷时回到对应生产模块修复，
再重跑该站的 hidden 集成与该特性的直接消费者测试。

分翼范围（站号即 `index.csv` 的 `station` 列）：

| 展翼 | 主题 | 站位 | 当前人工结果 |
|---|---|---|---|
| A | 战斗特效表现 | #1 #2 #3 #5 #7 | NOT_EVALUATED |
| B | 特效与场景交互 | #4 #6 #9 #10 | NOT_EVALUATED |
| C | 材质与局部光照 | #8 #16 #17 #18 #19 #31 | NOT_EVALUATED |
| D | 水体、折射与反射 | #11 #13 #14 #20 #30 #36 | NOT_EVALUATED |
| E | 镜头与最终画面 | #12 #21 #22 #23 #24 #25 | NOT_EVALUATED |
| F | 粒子规模与重建 | #15 #26 #27 #28 #34 #35 #41 #42 | NOT_EVALUATED |
| G | 专项能力 | #29 #32 #33 #38 #40 #43 #46 | NOT_EVALUATED |
| H | 高成本研究能力 | #37 #39 #44 #45 | NOT_EVALUATED |

## 逐站检查清单

每个站位的 A/B 对照都必须检查（参照计划书第 6 节与各技术卡限制字段）：

- 轮廓与边界：效果是否只作用于站位主体/接收面，边缘回退是否与文档一致；
- 遮挡与深度：贴花/接触阴影/水/反射/头发等是否被正确遮挡或拒绝；
- 时间稳定：TAA/SSR/降噪/烟雾/粒子/体积云等在连续帧中是否收敛、无拖影或历史污染；
- 关闭还原：A→B→A 后基线是否回到初始观感（reset 后无残留灯、雾、曝光或历史）；
- 拒绝与 fallback：VRS、MSAA 拒绝、低分辨率链 fail-closed 等是否显示明确原因而不是伪造效果；
- UI/合成：说明牌、A/B/Reset/Next/Prev 与画面后处理顺序符合合同。

## 结果模板（追加到 `acceptance.csv`）

```csv
station,wing,name_en,phase,png,human_result,observer,device,driver,resolution,aa,defect_link
1,A,Fresnel / Rim Lighting,A,station01-A.png,PASS,,
```
