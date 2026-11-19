# 46 项渲染功能展览馆计划书

日期：2026-11-17。适用分支：`features/46-render-features`。状态：8 翼 / 46 站均有大厅几何，靠近即可就地观察生产效果（第 12 节）。已归档逐站 A/B 截图和确定性集成证据。逐站人工视觉验收仍为 `NOT_EVALUATED`。

## 1. 目标与边界

为当前 46 项实时渲染技术提供一个可交互、可重复观察的统一入口。展馆不是第二套渲染器：每个展位调用现有生产模块和 `RenderPipeline` 消费者，使用真实几何、材质、灯光、深度和时间输入；展馆只负责场景布置、展位选择、参数 preset、说明牌和观察记录。

当前候选矩阵为 46/46 `DONE`，每项已有不同范围的自动化/真实 GL 证据，但这不能推出人工视觉通过。展馆要把“生产接线、自动化证据、硬件条件、当前限制、人工观察”分开显示。性能、显存、多 GPU、人工画质和发布就绪均继续遵循候选报告的 `NOT_EVALUATED` 状态。主依据：[0.28.1 交付矩阵](v0.28.1-delivery-matrix.md)、[交付报告](v0.28.1-delivery-report.md)、[能力矩阵](capability-matrix.md)及[路线图](realtime-rendering-roadmap.md)。

## 2. 展馆交互和渲染原则

- 一个浅色中性地面、清楚的主通道和 8 个展翼；按路线图布置 46 个有编号的地面展台。展翼标牌与展台说明牌用于导航。
- 大厅就地观看 46 个展位。逐站基线 A/B 从 `--station=N` 入口运行，复用同一观察相机与主体几何。镜头、曝光、动画时间、随机种子、分辨率和灯光按该展位 preset 控制。切换时清理历史或按合同执行 camera cut，处理 TAA、SSR、雾及曝光的跨站历史。
- 局部材质、阴影、水、头发、贴花和 VFX 作用于相应生产场景的接收面、遮挡物或主体。屏幕空间、时域、全画面后处理和全局体积效果在靠近展位时使用当前参观相机运行。
- 不同时开启所有功能。互斥、质量拒绝和硬件依赖按生产 policy 原样显示；切换到不支持的模式时展示明确原因和可工作的替代站，不伪造效果。
- 大厅展位有编号、名称和就地说明。单站模式提供 `A/B`、`Reset`、`Next/Previous`、当前配置与拒绝原因、自动证据链接和人工记录状态。完整路线可用固定顺序巡览。
- 运行模式分开：可见窗口用于人工观察；隐藏有限帧集成入口只检查站位登记、配置切换、无 GL 错误、资源关闭及代表像素合同，不把隐藏运行算作人工视觉验收。

## 3. 空间布局与共用资产

展馆按 8 个主题展翼组织，结构遵循[实时渲染技术路线图](realtime-rendering-roadmap.md)：

| 展翼 | 主题 | 站位 |
|---|---|---|
| A | 战斗特效表现 | #1 #2 #3 #5 #7 |
| B | 特效与场景交互 | #4 #6 #9 #10 |
| C | 材质与局部光照 | #8 #16 #17 #18 #19 #31 |
| D | 水体、折射与反射 | #11 #13 #14 #20 #30 #36 |
| E | 镜头与最终画面 | #12 #21 #22 #23 #24 #25 |
| F | 粒子规模与重建 | #15 #26 #27 #28 #34 #35 #41 #42 |
| G | 专项能力 | #29 #32 #33 #38 #40 #43 #46 |
| H | 高成本研究能力 | #37 #39 #44 #45 |

共用场景至少包含：中性地面与墙面、尺度参照物、漫反射/粗糙度/金属度对照板、带细节的斜面/墙角/薄片、可移动遮挡体、角色/发束展示体、有限水池、烟雾/云观察空间及明暗参考卡。资产采用项目自有程序几何或已有授权资产，按 `docs/licenses/assets.tsv` 维护来源；不因展馆重复复制 shader、渲染器或生产 preset。

## 4. 46 个展位清单

“观察主体”是展馆需要构造或复用的画面输入，不是效果正确性的自动判定。特定合同、限制和测试证据仍以对应技术卡及交付矩阵为准。

| ID | 展示功能 | 展位观察主体与对照 |
|---|---|---|
| 1 | Fresnel / Rim Lighting | 旋转的有法线曲面体；正视/掠射角及效果开关对照 |
| 2 | UV Flow / Distortion | 带方向纹理和图集格线的流动面；冻结时间、零幅度和活动状态对照 |
| 3 | Dissolve / Erosion | 固定种子的网格渐隐；0、中间阈值、1 端点与边缘发光 |
| 4 | Screen-Space Distortion | 前后景文字/网格和冲击环；扭曲前后颜色、遮挡及边缘行为 |
| 5 | Vertex Displacement | 带波形位移的网格；静态/位移、轮廓、阴影和 velocity 输入观察 |
| 6 | Projected Decals | 地面、斜坡、墙角与遮挡板上的同一投射贴花；开关和深度遮挡对照 |
| 7 | VFX Dynamic Lighting | 有生命周期的 VFX 照亮相邻接收球；效果前/活动/回收后 |
| 8 | Thin Translucency | 双面薄片与厚度阶梯；正面光/背光、阴影和厚度对照 |
| 9 | Contact Shadows | 物体贴近地面与离地间隙阶梯；接触阴影开关及屏幕边缘回退 |
| 10 | Cloud Shadows | 大地面和固定方向光下的世界空间云影；时间滚动及相机移动对照 |
| 11 | Screen-Space Water Refraction | 岸边水面、前景杆和背景标尺；折射前后、厚度及前景泄漏拒绝 |
| 12 | Depth of Field | 前/中/远三组标尺；焦点切换与焦内/焦外清晰度对照 |
| 13 | Refraction | 有厚度的玻璃/护盾形体及高对比背景；开关、厚度和边缘回退 |
| 14 | Water Surface Rendering | 有岸边、浸水体和波纹的浅水池；表面/深度/吸收效果对照 |
| 15 | Depth-Based Particle Collision | 粒子落向台阶和薄墙；碰撞前后、暂停/恢复与深度边界 |
| 16 | Clearcoat Shading | 清漆基底球和粗糙度/涂层量阶梯；涂层归零回到基线 |
| 17 | Anisotropic Shading | 带切线方向标记的拉丝金属体；转灯/转物及各向异性关闭 |
| 18 | Local Reflection Probes | 两个可辨识环境反射物与探针影响边界；局部探针/IBL 回退 |
| 19 | PCSS | 有面积感的遮挡体与接收平面；硬/软阴影及 blocker 距离变化 |
| 20 | SSR | 粗糙度阶梯、屏幕内/边缘外反射物；SSR 命中与环境回退 |
| 21 | Motion Blur | 固定背景和已知速度移动主体；静止/运动、方向和相机切换对照 |
| 22 | SMAA | 高频斜线、细栅栏和高对比轮廓；无 AA/SMAA 像素并排对照 |
| 23 | Vignette | 均匀亮度角落/中心标靶；零强度与受控暗角 |
| 24 | Film Grain | 暗/中/亮灰阶卡；同 seed 固定帧与噪声关闭对照 |
| 25 | Chromatic Aberration | 中心十字与边缘白线；零强度、边缘色分离和 UV 边界 |
| 26 | GPU Particle Sorting | 深度交错的彩色半透明粒子层；排序模式与固定参考 |
| 27 | GPU Particle Indirect Rendering | 不同活跃数/寿命的 GPU 粒子发射器；空集、增量和耗尽状态 |
| 28 | Weighted Blended OIT | 多层彩色透明体；排序参考与 OIT 近似、启用限制 |
| 29 | Atmospheric Scattering | 地平线、太阳方向与远近遮挡轮廓；大气开关和参数档 |
| 30 | Planar Reflections | 水池/镜面和已知反射标记；反射开关、更新节流和相机切换 |
| 31 | Parallax Occlusion Mapping | 高度图砖墙和斜视掠射相机；POM/普通贴图及距离变化 |
| 32 | SSS | 皮肤/蜡质目标的受光体；散射开关、光源方向和厚度参考 |
| 33 | Screen-Space SSS | 带散射 mask 的高对比边缘目标；模糊前后、背景串色检查 |
| 34 | Temporal Denoising | 静态细节与缓慢移动目标；历史收敛、运动和 disocclusion |
| 35 | Temporal Upscaling | 内部分辨率细线/棋盘与输出标尺；原生分辨率、空间放大和时域结果 |
| 36 | Caustics | 水面下接收地面及遮挡边界；焦散开关、时间和接收区域 |
| 37 | Stochastic Transparency | 多层透明网格/薄片；静帧收敛、序列稳定性和参考图 |
| 38 | Volumetric Particles | 局部烟团穿过光束与遮挡体；深度、密度、光照和预算档 |
| 39 | SSGI | 颜色鲜明的相邻面与凹角；间接光开关、接触边界和屏幕可见限制 |
| 40 | Volumetric Clouds | 有地平线和方向光的有限云层；云体、云影与曝光对照 |
| 41 | Adaptive Sampling | 固定预算与自适应预算的同一 SSR 场景；样本分布和误差参考 |
| 42 | Variable Rate Shading | 细线、文字、透明与平滑区组合靶；能力检测、速率可视化及 fallback |
| 43 | Volumetric Smoke Simulation | 有界烟雾容器、注入源和固定观察相机；演化阶段与 reset 对照 |
| 44 | DDGI | 具有亮色间接照明和遮挡墙的测试房；探针可视化、漏光边界和关闭对照 |
| 45 | Voxel Cone Tracing | 体素调试视图、接收面与薄墙；VCT/基线及体素尺度边界 |
| 46 | Hair Strand Rendering | 头发卡片与 strand/ribbon 主体、背光轮廓和阴影接收面；静态/运动/TAA 状态 |

## 5. Demo 结构与实现分批

新增一个统一的 `RenderFeatureExhibitionDemo` 可见窗口入口，放在 `src/demo/java/com/kaleblangley/haikalat/demo/pbr/` 或职责更合适的 render3d demo 包。应先核对是否可扩展 `HaikalatShowcaseDemo`；该类职责是既有同帧综合验收，展馆的交互/巡览/46 个站位状态明显超出其范围，因此只复用场景构造器与资产，不把 46 个功能塞入一个巨大 `main`。

代码边界：

- 展位目录是纯数据，记录编号、生产配置 preset、几何布置、参考/效果阶段、前置能力和证据链接；不拥有 GL 对象。
- 展馆场景/交互留在 demo 层；原始 GL 调用继续走 backend/既有渲染模块，生产 shader 与 RenderPipeline 功能不复制到 Demo。
- 一个站位激活一套受控配置。普通设置通过 feature-control 的 frame-boundary 提交；需要改变 RenderGraph topology 的项走现有 generation 事务。站位切换必须清空或重置其不兼容历史。
- 公共生产 API 只有在展馆暴露出真实缺口且完成技术卡审查后才修改；单纯演示编排不扩大生产 API。
- 站位说明以当前候选矩阵的限制字段为源，不能把测试 fixture 的测试专用输入当作面向用户的正式能力。

分批交付：

1. **地面与巡览骨架**：统一地面、8 翼/46 编号站位、相机导航、标签、A/B/Reset、固定相机和运行摘要；先接已存在的 PBR、基础阴影、AO、TAA、透明和 VFX 现成场景，确定资源生命周期。
2. **局部表面展翼 A-C**：材质、位移、贴花、阴影、头发、VFX 与光照；要求效果确实落在站位几何上，并为 #1–10、#16–19、#31、#46 提供可辨识的对照。
3. **水与反射展翼 D**：水面、折射、平面反射、SSR、焦散和水下体积；以水池为共用主体，按照路线图互斥/顺序合同逐个切换，不让 pass 顺序错误被展馆遮掩。
4. **全帧与时域展翼 E/F**：DOF、模糊、AA、色差、颗粒、降噪、升采样、采样率和透明模式；每次仅一个明确配置，加入固定种子/动画脚本及历史 reset。
5. **体积与研究展翼 G/H**：大气、SSS、体积粒子/云/烟、SSGI、DDGI、VCT；高成本站位按硬件/质量档显示预算，慢速单站初始化，不阻塞轻量站位。
6. **人工视觉验收**：每个站位记录候选 HEAD、设备/驱动、分辨率、AA/质量档、baseline/effect 截图、观察人、结果（PASS/FAIL/NOT_EVALUATED）和缺陷链接。出现问题回到对应生产模块修复，再重新验证其直接消费者。

## 6. 验收标准

- 目录完整且唯一覆盖 1–46；每站都能打开、回到基线、切到效果、重置历史并显示当前状态。
- 自动集成至少巡览所有 station IDs，验证无空配置、非法组合无静默降级、切换后首帧行为明确、无 GL debug error、资源关闭归零。按[有侧重验收规范](../guides/focused-acceptance-policy.md)选取，不运行 46 份重复渲染器或全组合笛卡尔积。
- 对每项复用原有定向 JVM/GL 证据；展馆接线本身的消费者改变时，补真实 GL 像素/状态合同，不仅验证目录存在。
- 人工逐站查看 A/B，并按限制清单检查轮廓、边界、遮挡、时间稳定和关闭还原。图片归档按 station ID 命名；自动像素证明与人工判断分栏。
- RTX 3050 目标设备可以运行支持的主路线。VRS 在不支持 `GL_NV_shading_rate_image` 的设备上明确显示 fallback；低分辨率链对未迁移特性按当前合同 fail-closed。性能、显存、长测、多 GPU 和发布就绪单独立项，不从展馆可运行推出 PASS。

## 7. 首批执行清单

- [x] 建立 `RenderFeatureExhibitionDemo` 交互壳与统一场景资源 owner。
- [x] 提供 8 翼和 46 站目录，逐项链接生产实现、当前 GL 证明、限制和人工结果。
- [x] 接入共享 A/B/Reset/Next/Previous 控件和 deterministic hidden integration。
- [x] 按 6 个实现批次逐项补齐真实展位配置及几何，不用标题或纯色占位冒充效果（46/46 站已接真实生产配置）。
- [x] 完成全馆入口视图及每站 A/B 截图归档（`build/exhibition/entry.png`、`build/exhibition/stations/` 与 `index.csv`）。
- [ ] 在归档截图上逐站登记人工视觉验收结果（观察人、设备/驱动、PASS/FAIL/NOT_EVALUATED、缺陷链接）；当前 `index.csv` 全部为 `NOT_EVALUATED`。

## 8. 首次交付记录（2026-11-17）

入口与边界：

- 可见/隐藏入口 `src/demo/java/com/kaleblangley/haikalat/demo/pbr/RenderFeatureExhibitionDemo.java`；
  `ExhibitionSession` 持有唯一 window-scoped pipeline、共享 `ExhibitionAssets` 和逐相位 view 缓存。
  站位切换在帧边界执行 `replaceScene` + `features().submit(完整快照)` + `resetTemporalHistory`；
  场景派生消费者（如折射材质）使用 `ExhibitionStationView.pipelineRebuild` 重建 generation；
  atmosphere / VFX light / VCT input 等快照外状态在每次切换时显式清空。
- 目录是纯数据 `ExhibitionCatalog`（1–46 唯一、8 翼映射、名称/观察主体/限制/证据/设备门控/交付批次），
  不拥有 GL 对象；场景接线在 `ExhibitionWingAStations` … `ExhibitionWingHStations` 八个文件中。
- 隐藏集成 `src/test/java/com/kaleblangley/haikalat/demo/pbr/RenderFeatureExhibitionGlTest.java`
  复用同一 session/catalog：逐站选择、A/B 切换、reset、GL error/资源关闭检查与代表性像素差合同；
  它不是人工视觉验收。
- 本阶段的可见入口进入**可漫游博物馆大厅**（`ExhibitionMuseum`）：启动时按 46 站真实效果相位逐站渲染并
  烘焙 46 张屏幕纹理（逆 ACES 补偿，避免二次色调映射），在一面 8 列 × 6 行画框墙上同时列出全部类型；
  `WASD`/`Space`/`Shift`/鼠标自由移动，走近画框按 `E` 进入该站实时交互场景，站内 `ESC` 返回大厅，
  `H` 说明牌开关、`M` 指针；站位说明牌按「观察主体 / 限制 / 门控 / 证据 / 生产状态」分类排版，
  博物馆墙本身就是目录（原 46 站文字列表已移除）。隐藏巡览/截图任务仍按逐站确定性入口运行，不受大厅模式影响。
  大厅首帧前需要约 9 秒逐站烘焙（46 站、702 帧），控制台打印 `museum: baked …` 进度。

当前覆盖（自动证据）：

| 项目 | 范围 | 结果 |
|---|---|---|
| 目录完整性 | 1–46 唯一、8 翼覆盖、卡片字段齐全 | PASS |
| 展位接线 | 46/46 站全部接真实生产配置 | PASS |
| hidden GL 集成 | `.\gradlew.bat glSmoke --tests "*RenderFeatureExhibitionGlTest"` | PASS，wired=46 pending=0，0 GL error，session 关闭后无 live GL 资源 |
| 固定顺序巡览 | `.\gradlew.bat runRenderFeatureExhibitionIntegration`（`--hidden --tour --size=640x360 --verify`） | PASS，46 站/184 帧，无 FAILED 请求 |
| 逐站截图归档 | `.\gradlew.bat runRenderFeatureExhibitionCapture`（`--hidden --size=960x540 --capture-dir=build/exhibition/stations`） | PASS，92 张 A/B PNG + `index.csv`（`human_result=NOT_EVALUATED`） |
| 编译 | `.\gradlew.bat compileDemoJava` | PASS |

逐站 A/B 像素证据（`changedPixels` ≥ 0.0005 且无生产拒绝）已由集成测试输出并归档到
`build/test-results/glSmoke/TEST-com.kaleblangley.haikalat.demo.pbr.RenderFeatureExhibitionGlTest.xml`；
入口视图 `build/exhibition/entry.png`（含说明牌与 A/B/Reset/Next/Prev 控件），逐站 A/B 归档在
`build/exhibition/stations/`（`stationNN-A.png` / `stationNN-B.png` 与 `index.csv`）。

实现备注（两处曾暴露真实缺口的站位）：

1. **#31 Parallax Occlusion Mapping**：`createWithParallax` 的 supplied-binding 路径在
   BASE_COLOR 角色上会让整个显示帧变黑（基线正常、无 GL error），因此站位按
   `ParallaxGlTest` 已验证的 HEIGHT + EMISSIVE 纹理组合接入：真实 POM UV 位移作用于砖纹图案，
   基线为同一图案/几何的普通 PBR 材质，A/B 像素差已由集成测试证明。
2. **#35 Temporal Upscaling**：reduced-resolution（renderScale 0.5）内部重建链与
   全分辨率 HDR VFX/decal recorder 组合时呈现黑帧；session 为 renderScale<1 的相位构建
   bare pipeline（该站无 VFX/decal 消费者），A/B 与呈现均通过集成测试。

仍待交付：

1. 人工视觉验收：逐站 A/B 截图已归档且 `index.csv` 行齐全，但观察人、设备/驱动、分辨率、
   PASS/FAIL/NOT_EVALUATED 与缺陷链接尚未登记；登记流程与模板见
   [人工视觉验收记录](render-feature-exhibition-acceptance.md)。性能、显存、多 GPU、长测与发布就绪继续 `NOT_EVALUATED`。
2. 历史小缺口：#43 `session.reset()` 未重置 `SmokeTimeline`，已于 2026-11-18 修复（见第 9 节）；
   #18 反射探针只覆盖 specular 局部校正（生产限制）；#5 顶点位移物理上位于翼 A 目录但接在
   `ExhibitionWingBStations`（A 文件对 id 5 返回 null，registry 顺序不影响行为）。


## 9. 展馆美化与修复（2026-11-18）

候选：`features/46-render-features`，基于 `c471464636263d9a0acd6de8ef700730524e686b` 的未提交工作树；保留原有改动。

- 大厅采用蓝灰墙面、浅色地面和青色 HUD 强调色。修正单位 quad 与 cube 半尺寸混用造成的墙面、地板、天花板覆盖不足，修正侧墙朝向；标题移到画框上方，标签扩至画框全宽，去掉重复编号。本阶段布局为 12 列 × 4 行（第 8 节的 8 × 6 是旧记录）。
- HUD 的装饰只作用于卡片和按钮，布局容器保持透明；按钮支持 hover/pressed 反馈。非当前模式面板使用 COLLAPSED，不再占布局空间；H 只切换当前大厅栏或站位卡。站位卡最大宽度随窗口收缩，内容可在 M 指针模式下滚轮阅读。
- #43 使用已有 `pipelineRebuild` 路径重新创建模拟资源和固定步长时间线，使 Reset 和重访回到初始状态。不扩大生产 API；代价是烟雾站相位切换/重置需要一次管线重建，未作性能结论。
- 扩展原有 GL 集成用例：先确认烟雾经过 30 帧产生变化，再 Reset，对比第一帧像素（每像素允许一个显示码值误差）。该回归需要真实 GPU 模拟和读回，单纯历史 reset 状态检查无法覆盖。

本次验证：

| 验证 | 结果与证据 |
|---|---|
| `./gradlew.bat compileDemoJava` | 新执行通过 |
| `./gradlew.bat glSmoke --tests "*RenderFeatureExhibitionGlTest"` | 新执行 3 个用例，0 skipped / failures / errors，含 46 站 A/B 和烟雾 Reset；XML 归档于 `build/exhibition/polish-evidence/` |
| 可见大厅 / 站位截图 | `runRenderFeatureExhibitionDemo --args="--hall --size=1280x720 --frames=90 --capture=build/exhibition/polish-hall.png --capture-frame=90"`；站位使用 `--station=43` 与 `polish-card.png`，两者正常结束、failed=0，已查看截图 |
| 设备 | NVIDIA GeForce RTX 3050 Laptop GPU，OpenGL 4.6，驱动 576.88 |

日志：`build/exhibition/polish-gl.log`、`polish-hall.log`、`polish-card.log`。截图检查只覆盖大厅与代表说明牌；未进行全部 46 站人工动态视觉验收、完整键鼠漫游、性能或发布矩阵。原始 UI/场景源文件副本保留在 `build/exhibition/polish-before/`。


## 10. 同帧地面展馆记录（2026-11-18）

这一版默认进入全馆俯视，Tab 切换总览与步行，E 进入单站。当前操作见第 12 节。

- #1、#2、#3、#5、#8、#16、#17、#31 的主体网格、材质或 VFX 直接在大厅空间绘制，可绕行观察。
- 其余 38 站各自保留生产场景、相机、驱动和渲染历史，每个大厅帧顺序绘制全部展项并合成到各自的地面展台。展台视窗为 256 × 144。全屏效果只覆盖本站视窗。#35 沿用生产低分辨率平台输出，在大厅绘制前通过 GPU 缩放复制到展台。显示纹理的颜色还原和缩略过滤在 GPU 上执行。
- 大厅退出特殊渲染配置的单站时恢复完整管线。回归覆盖 #35 → 大厅 → #6 的贴花输出；另核对 46 站点选、38 个视窗逐站非空、固定大厅相机下的 UV 流动，以及同一时段 #21 与 #43 的视窗像素变化。
- RTX 3050 Laptop GPU / 576.88 上定向 `glSmoke --tests "*RenderFeatureExhibitionGlTest"` 新执行 4 项，零失败、错误和跳过。首次测试的日志门控记录三条 NVIDIA 片元程序状态特化提示，独立可见窗口记录四条。展馆测试按该型号、消息类型、文本和四条上限登记。报告在 `build/test-results/glSmoke/`，可见大厅截图在 `build/exhibition/floor-hall.png`。
- 单站人工判断继续登记在[人工视觉验收记录](render-feature-exhibition-acceptance.md)。帧率、显存预算、多 GPU 和长时间运行仍待单独测量。

## 11. 步行大厅与视窗刷新记录（2026-11-18）

默认从主通道入口开始，WASD 在地面行走，鼠标环顾，Tab 俯视全馆，再按一次回到原步行位置。展位沿两侧八个带标牌的展翼排列，横向中心间距由 3.45 增至 5.8，前后排中心间距由 3.1 增至 6。主通道、横向过道和展台之间均可通行。走近并看向展台即显示展项名和观察重点，单站 A/B 仍可通过 `--station=N` 启动。

- 13 个展项在共享场景直接展示效果。继原有八项之后，#6 贴花、#7 VFX 动态灯、#13 玻璃折射、#32 前向次表面散射、#46 发丝也使用大厅里的模型和生产渲染路径。
- 另有 20 站并排放置实体模型与效果视窗，包括四个水池及其接收体、景深标尺、透明层、反射道具与 GI 房间。模型用来观察几何、材质和结构，视窗使用该站的独立渲染设置来显示效果。大气、体积云、体积烟雾等其余 13 站使用独立视窗。
- 33 个独立视窗在开馆后逐帧生成首张真实效果画面，每帧至多初始化一站。带模型的视窗复用同一个展位场景的主体几何。
- 大厅每帧最多刷新两个独立视窗。优先轮换视野内 12 单位范围中最近的四个视窗，每站刷新上限约 30 Hz。远处视窗和俯视全馆使用已有画面。再次靠近时从保留的渲染历史继续运行。
- 扩展现有 GL 回归，覆盖步行与俯视位置还原、展台阻挡、46 站说明识别、33 个视窗画面填充、远处缓存稳定和 #21 与 #43 走近后恢复动画。回归还核对共享场景的贴花、动态灯、玻璃、发丝和水池几何，以及 #35 与大厅之间的管线切换。保留原有逐站 A/B 和资源释放断言。

`glSmoke --tests '*RenderFeatureExhibitionGlTest'` 新执行 4 项，零失败、错误和跳过。RTX 3050 Laptop GPU / 576.88 上，1280 × 720 隐藏窗口在 #14 展台定点采集 180 帧，预热后帧均值由原布局 21.37 ms 降到 3.36 ms。测量包括渲染提交和批末 GPU 等待，未做换帧或逐站性能验收。日志位于 `build/exhibition/walk-models-gl.log`、`walk-probe.log` 和 `walk-probe-final.log`。当次定点截图为 `walk-overview.png`、`walk-glass-model.png` 和 `walk-water-model.png`。

## 12. 全馆实体展位（2026-11-18）

删除所有展台画面视窗和对应的离屏刷新、拷贝及材质合成。46 个展位从原生产接线场景复用真实主体、接收面和观察道具。WASD 步行、鼠标环顾，Tab 继续在参观和俯视之间切换。展位牌仍用于标识和导览。

- 常驻展项在共享大厅中运行。需要独立渲染设置的展项在迎面走近后自动启用，离开区域后恢复默认大厅。进出展位有不同距离阈值，原地转头仍能观察已经启用的天空或体积效果。进入俯视立即恢复默认管线设置。
- 环境与访客相机按同一个变换表达在该展位的坐标系，复用原预设的局部体积、光照、反射、透明度及历史。访客的步行位置和视线连续。全屏效果覆盖当前访客画面。玻璃、水面和 SSR 等互斥路径按当前展位顺序运行。
- 每帧只有主窗口的生产管线运行。普通进入和离开复用管线资源，折射、低分辨率链和烟雾模拟继续遵循对应预设的重建条件。独立 `--station=N` 及隐藏 A/B 截图入口仍用于原合同。
- 重写明示视窗刷新的大厅回归。覆盖 46 站实体几何、全部需就地启用的预设、视点和相机连续性、互斥管线切换、贴花和动态灯，保留逐站 A/B 像素与资源释放检查。

1280 × 720 隐藏大厅入口完成 90 帧，`stations=46 wired=46 failed=0 rendered=true`。RTX 3050 Laptop GPU / 576.88 定点采样的 180 帧均值为入口 5.84 ms、水池 3.07 ms。#14、#13、#43、#21 的进入首帧分别为 21.57、34.58、40.83、34.80 ms。测量包含 CPU 提交和批末 GPU 等待，范围为上述定点与切换。日志见 `build/exhibition/model-only-gl.log` 与 `model-only-probe.log`。水池斜视、烟雾近景和全馆俯视留在 `model-only-water-side.png`、`model-only-station-43.png` 和 `model-only-overview.png`。

## 13. 漫游相机与展项修复（2026-11-18）

- 靠近展位时将相机裁剪距离与大厅几何一起转换到展位单位。`Camera.setClipPlanes` 设置有限且满足 `0 < near < far` 的裁剪距离。变化计入相机修订，帧快照保留裁剪距离并供可见性、光栅和深度重建消费。默认相机仍用 0.1 / 100。HDR VFX 与 GPU 粒子接收相同的稳定投影。
- #38 从固定观察角扩展到漫游。体积粒子先用齐次裁剪平面排除完全离屏的包围盒，再处理可能可见且跨越眼平面的保守全屏边界。`VolumetricParticlePass.exceedsCoverage` 复用生产检查，为大厅逐帧预判覆盖率。真正超过 0.5 时，大厅暂停该粒子效果并提示，覆盖率恢复后重新启用。生产 `record` 的超限拒绝保持有效。
- #2 将 UV 位移限制在往返周期内，避免长期累计贴到图集透明边缘。#6 使用已有带透明通道的 Kenney Light Masks。#8 靠近时启用原展位背光，#31 减小视差高度。GI 展台的说明牌移到房间外，#45 大厅模式减弱高光和间接照明强度。
- 回归按 #4 启用前后同机位读回远墙标牌，在 #38 转头一周并走近、后退，检查离屏无误报、超限暂停和恢复。生产体积粒子的离屏、覆盖上限拒绝、真实深度遮挡和资源释放用例继续执行。原始崩溃日志和修复前后定点截图位于 `build/exhibition/walk-regression/`。
- 两次定向 `glSmoke` 新执行 91 项，零失败、错误和跳过。另用 1280 × 720 隐藏窗口在同一机位确认远墙恢复，完成 #38 转头一周，确认 #2 持续运行 12 秒后仍有可见流动。日志为 `final-gl.log` 和 `camera-consumers-gl.log`。
- #44 同机位关闭 DDGI 后，墙面、挡板和展品上的分块消失。说明牌移位已完成，探针遮挡的分块仍存在。完整人工漫游和 1440p 性能尚未复测。
- #32 同机位关掉散射时，球体背光处的暖色减弱。单独关掉方向光阴影时，下缘的三角碎边消失。大厅改用三件同形样品与相同设置的背光，依次为关闭、厚度参数 30 mm、厚度参数 8 mm。样品使用更细的网格来减轻自阴影锯齿，独立单站继续提供原有 A/B。对照截图和 GL 记录位于 `build/exhibition/sss-probe/`。
- #39 用橙、蓝发光面板和中性接收面演示色彩反弹。半分辨率追踪后按深度和法线滤波、重建。RTX 3050 Laptop GPU / 576.88 的 2560 × 1440 固定大厅机位，24 帧预热后采样 45 帧。旧展项中位数 25.17 ms，新展项 9.20 ms，重复采样 9.04 ms。在同一个新版场景仅换回旧全分辨率参数时中位数 24.08 ms。这是关闭垂直同步的隐藏窗口 GPU 帧计时。82 项定向 GL/JVM 用例全部执行，零失败、错误和跳过。日志与同机位开关截图位于 `build/exhibition/ssgi-probe/`。

## 14. H 翼间接光展台重绘

- H39 用两组开放折角把橙、蓝发光板和中性接收球、白板、地面配对。正面观察两色反弹，绕到斜侧可让其中一块发光板大部分离开画面，露出屏幕采样的限制。
- H44 在同一背侧主光下放置两块白板。左侧位于有限探针网格内，右侧在范围外。青色透明小球标出配置中实际的探针中心。透明标记不遮挡探针深度。DDGI 间接光按共享表面的有符号八面体格式解码法线。独立 GL 夹具使用同一共享格式，展馆读回对照网格内外白板。
- H45 左右使用相同发光块和接收板，右侧在光路中插入遮挡墙。展台几何与 VCT 三角形由同一组参数登记。前排方块标出 0.15 m 的体素尺度。合成在体素盒外直接返回零增量，回归覆盖光锥起点仍可伸进体积的临界接收面。
- 走近三个展位后按 B，可在同一视角和几何上切换间接光。离开展台或切到俯视时清除对照状态。固定机位的开关和斜侧截图位于 `build/exhibition/gi-redesign/`。
- 48 项定向 GL/JVM 用例实际执行，零跳过、失败和错误。覆盖真实大厅、三站在原地关闭及恢复、DDGI 范围内外的白板读回和 VCT 体积边界。`assetManifestGuard` 验证着色器清单与指纹。截图为 RTX 3050 Laptop GPU / 576.88 隐藏窗口，未进行完整的手动漫游或重绘后的性能测量。

H39 随后调整为 2.8 m / 8 步、0.37 m 命中厚度和 1.0 强度。正面同机位对照各自的关闭基线，左白板的红通道平均增量由 68.4 增至 94.3，右白板的蓝通道由 66.0 增至 91.6（8 位显示值，固定区域）。2560 × 1440 的 45 帧样本在原参数为 8.79、8.90 ms，调整后为 8.86、8.75 ms（GPU 帧时间中位数，两轮采样）。侧视时移出画面的蓝板仍明显失去反弹贡献，白板近看仍有采样斑纹。14 项定向 GL 回归全部执行且通过，证据在 `build/exhibition/ssgi-detail/`。

H39 半分辨率滤波加宽，并根据相邻深度补偿同一接收面的局部坡度。正面白板固定区域中，扣除关闭 SSGI 的基线后，两色的高频显示残差（与 8 px 高斯平滑值的平均绝对差）从约 6.63 降至 4.31 和 4.20，原通道平均反弹增量变化小于 1 点。1440p 定点 GPU 帧时间中位数由 8.79–8.86 ms 增至 9.73–9.74 ms。74 项定向验证零失败和跳过，新增真实 GL 用例核对同斜面滤波、跨深度断层拒绝与中心深度保留。首轮、最终截图和日志位于 `build/exhibition/ssgi-natural/`。

H44 斜侧近看时，单个探针深度 texel 将同一接收面分成明暗块。间接光合成现在用接收面切平面预测相邻四条射线应到达的深度，再插值可见性比较值。完全遮挡和网格外的接收点保持零贡献。1440p 同机位关闭及修正前后截图位于 `build/exhibition/ddgi-seams/`，站位未改。隐藏窗口的固定近景 45 帧 GPU 时间中位数由 11.33 ms 增至 12.23 ms。48 项定向验证零失败、错误和跳过，包括低分辨率探针下的掠射接收面、完全遮挡、网格外读回，以及大厅三站的切换恢复。`assetManifestGuard` 通过。
