# 多光源体积雾

当前开发实现包含三维介质、多光源与分配阴影、透明合成、独立体积历史，以及四场景 Demo 和 schema 2 预设。旧正式二维 Outdoor 体积路径已迁移；保留独立参考与仍有用途的天空设置。

已采用 Spot 面板保守剔除，受影响 GL/独立参考与新临时包短启动通过。构建版本仍为 0.24.3，0.25.0 尚未发布；新候选正式性能未执行，父候选预算失败和 CPU 可比性问题保留。最新身份、复用范围与未完成项见 [v2 验收汇总](../releases/v0.25.0-focused-acceptance-v2-report.md) 和 [采用报告](../performance/v0.25.0-panel-cone-cull.md)。

## 配置

`RenderPipeline.volumetricFog(settings)` 在 build 前配置，`applyVolumetricFog(settings)` 在帧边界更新。
同一快照也包含在 `VisualSettings.volumetricFog()` 中，可以随曝光、IBL、天空和 AA 一起原子更新。
构建失败保留旧设置和资源；只改密度、g 或雾体参数不需要更换资源形状。

```java
var fog = new VolumetricFogSettings(true, 64,
        VolumetricFogSettings.Quality.BALANCED,
        new FogMediumSettings(0.004f, new Vector3f(0.8f), new Vector3f(), 0, 0.12f),
        List.of(LocalFogVolume.box(new Vector3f(0, 1, -6), new Vector3f(3, 2, 4),
                0.015f, new Vector3f(0.8f))),
        0.25f, true, 0.9f, 1337, new Vector3f());
pipeline.volumetricFog(fog);
scene.setLightVolumeHints(index, new LightVolumeHints(1, true, true));
```

extinction 是每世界单位的消光系数；albedo 在 [0,1]；emission 是独立的单位长度发射源。
球 radius 使用 extent.x，盒 extent 为半尺寸。最多 8 个局部体积，系数相加。每灯 hints 不改变表面灯强度或阴影预算；
`temporalAccumulation=false` 用于火把闪烁、技能光和快速移动光束，变化时也拒绝其旧影响域。

先固定曝光和 IBL，关闭 Bloom，检查无雾画面；随后依次增加低密度高度雾、局部体积、选中灯的贡献/g 和阴影。
生产旧 Outdoor 体积链已移除，天空设置仅含 sky；体积雾与独立 analytic FogPass 互斥。NONE/FXAA/TAA 和 MSAA 2×/4× 均有合成专项；
默认档的固定帧图像矩阵已通过；实际事件和美术另行验收。

## 四场景入口

```powershell
.\gradlew.bat runRender3dVolumetricFogDemo --args='--scene=town_night'
.\gradlew.bat runRender3dVolumetricFogDemo --args='--hidden --scene=tavern_threshold --frames=421 --size=1280x720'
```

场景 ID 为 `forest_morning`、`town_night`、`tavern_threshold`、`medium_lab`，复用森林和夜镇场景工厂。
正式 profile 位于 `config/volumetric-fog/profiles/`，镜头/固定帧位于 `config/volumetric-fog/captures/`。
小镇保持 128 个表面局部灯，默认 16 个体积灯；`--all-128` 独立标记压力输入。
酒馆在 120 帧亮灯、180 帧关灯、240 帧移动雾体；预设包含事件前后抓图。

支持 `--fog-off`、`--local-volume-off`、`--local-shadow-off`、`--history-off`、`--aa=TAA`、`--quality=LOW`、
`--orthographic`、`--camera-path=overview`、`--capture-final`、`--profile=...`、`--capture-spec=...`。
`--local-shadow-off` 只关闭 hints 的阴影采样，保留表面阴影预算，便于配对。
截图保留线性 HDR、当前配置、相机矩阵、阴影分配和 pass sample identity；截图运行不作为性能证据。

面板编辑 desired profile，显示 dirty 状态；Apply 成功后发布，失败保留旧帧。Save 使用原子文件替换，Load 严格校验。
面板在正式输出后绘制，UI 不参与雾。隐藏 UI 检查可用 `--hidden --overlay --capture-final`。

## schema 2 与诊断

schema 2 保存 `volume.quality/distance/history/wind`、`medium.extinction/albedo/emission`、
`local.N.extinction/albedo/emission/falloff` 和 `light.N.scene_index/intensity/shadow/history`。
light index 是确定性场景工厂中的顺序，不是 transient GPU index。schema 1、无版本旧配置、旧 density/color 字段明确拒绝。
当前美术预设已显式重新填写物理系数；旧 HDR 参考保留原文件，不能把换格式视为视觉等价。

`volumetricFogDiagnostics()` 返回上一完成帧的尺寸、字节数、历史状态、脏域和失效原因，不同步 GPU。
`--diagnostics` 显式启用可选 GPU 计数；`captureVolumetricCounters()` 只在计数开启的完成帧可用，
通过 staging 读回候选访问/overflow/无阴影槽、历史拒绝及数值限幅。正常性能运行关闭此选项。
`captureVolumetricSlice(field,layer)` 是显式同步导出，只在有效完成帧可用。
介质、q、S/T、reject 和 reactive-prefix 分别输出 2D PNG 与 `.rgba.f32`；后者为 big-endian width/height/layer 三个 int，
随后是行序 RGBA float。PNG 为诊断显示变换，数值比较应使用原始 float。
prefix 的物理边界字段为 Nz+1，实际 storage 为 Nz+9，额外层保存 opaque depth、near mask 与灯索引；实际卷和历史均由 PipelineGeneration 独立拥有，双视图不共享历史。

4K balanced 含 emission 的保守 3D/参数资源为 108,151,568 bytes；正常单采样额外颜色/Reactive 后为 182,801,168 bytes。
HDRVFX 单采样 soft depth 另计 4 bytes/pixel；超出 192 MiB 的组合在候选阶段拒绝。HIGH 不保证适用于 4K。
这些是 storage 预算。实测结果与限制见阶段报告，不能用预算替代性能通过。

## 测量入口

```powershell
.\gradlew.bat localRender3dV0250FoundationVerification
.\gradlew.bat runRender3dVolumetricFogQuality
.\gradlew.bat runRender3dVolumetricFogQualityTemporal
.\gradlew.bat localRender3dV0250Verification
.\gradlew.bat runRender3dVolumetricFogBenchmarks
.\gradlew.bat runRender3dVolumetricFogCpuAttribution --args='--only=budget-128-1080p --cpu-isolation'
```

质量任务在原生 1280×720 渲染上读取预先固定的 stride=4 像素，使用整个 320×180 ROI。
参考独立评估介质、射线和 FP32 积分，遍历完整灯表；256/512 不收敛才继续 1024/2048。
同一物理点的全表 source 对照是额外诊断，不能替代独立图像参考。
图像任务目前比较 opaque 线性 HDR；酒馆最终透明/VFX 美术和事件序列仍须单独验收。
`--candidate-history=off` 生成无体积历史候选；`--scene=town_night --frame=60 --quality=HIGH`
是明确标记的局部探针，不计为完整默认质量矩阵。

性能任务使用原生 1080p/4K、3 轮交替 A/B、每轮 120 warmup+300 measured，保存逐帧所有必需 pass。
完整 GPU 先同帧求和再按 round/index 做 on-off 配对；CPU 计 pipeline.execute 全墙钟，所有显式等待另报。
`--only=...` 为单配置测量；缩短采样或 CPU isolation 均明确标为非正式。
所有输出位于 `build/reports/render3d-v0250/<fingerprint>/<runId>/`，包括配置、原始样本、HDR/误差图和失败原因。

时间序列任务读取最终 TAA 后、曝光/Bloom 前的线性 HDR，包含材质透明与酒馆 HDRVFX。
从 120 帧冻结世界、镜头和 VFX；148～179 共 32 帧评估整个原 ROI 的平均亮度标准差/无历史参考均值，
2%/暗区绝对阈值沿用 `quality.properties`，逐像素极值另存。180 帧仅关闭选中灯的体积 hints，
表面灯保持原亮度；第 2 帧绝对 RGB 灯雾差能量须 ≤关灯前的 5%。

受控基线使用 v2：在 coarse GPU source 和不透明/ALPHA/HDR VFX 的 native 近灯查询中一致去掉选中灯的体积 radiance；场景、表面照明和 CPU 历史策略与候选相同。
有 TAA 时逐帧重放候选的原生 R8 reactive；全部成功帧的脏域/相位/时间与读回 R8 均严格比对。
这测量灯雾历史，不能代替实际灯闪烁时背景变化、移动风场或最终美术验收。无可见灯雾增量会明确失败，不能除零登记通过。
`--scene=medium_lab` 为局部诊断；默认完整四场景×三种历史组合。连续原生读回缓冲在每次抓帧后释放；
该任务限制 heap=1 GiB/direct command staging=256 MiB，全部同步读回均不构成性能证据。

已知关灯时，受影响的可见雾区在该帧产生强 reactive，避免弱灯的旧颜色停留在最终 TAA 历史；
该响应下一稳定帧清零，其他区域继续使用现有权重。当前受控四场景恢复均通过，实际灯事件与移动风场仍须单独验收。
