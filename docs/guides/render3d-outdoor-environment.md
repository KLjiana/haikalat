# Render3D 室外环境与体积阳光指南

v0.24 的室外能力由一个不可变的 `OutdoorEnvironmentSettings` 快照驱动。快照同时描述
风格化天空、主太阳、全局介质和最多 8 个局部雾体；`RenderPipeline` 在帧边界创建或替换
候选代次，失败时保留上一代资源。

## 启动样板

```powershell
.\gradlew.bat runOutdoorEnvironmentDemo --args="--preset=morning_fog --size=1280x720 --csm-atlas=2048"
.\gradlew.bat runOutdoorEnvironmentDemo --args="--preset=clear_day --volume=off --no-vsync"
.\gradlew.bat runOutdoorEnvironmentDemo --args="--preset=golden_hour --aa=taa --bloom --no-vsync"
.\gradlew.bat runOutdoorEnvironmentDemo --args="--hidden --frames=2 --capture=build/reports/outdoor.png --preset=morning_fog"
.\gradlew.bat runOutdoorEnvironmentDemo --args="--hidden --frames=600 --route --preset=morning_fog --capture=build/reports/outdoor-route.png --no-vsync"
.\gradlew.bat runOutdoorEnvironmentReferenceIntegration --rerun-tasks
```

预设是 `morning_fog`、`clear_day` 和 `golden_hour`。样板使用程序化低多边形树干、树冠、
岩石和地面，主太阳使用 4 级 CSM；`--volume=off` 关闭体积专用目标和历史，可作为同场
景基线。

可见 demo 默认在右侧附加 `OutdoorEnvironmentOverlay`。它提供晨雾/晴天/黄昏、Low/
Balanced/High/Reference、太阳密度/距离/方向性、高度雾、局部雾、风、噪声、太阳强度、
独立历史和 volume-off 对比开关；面板下方显示深度、CSM 级联范围、散射/透射率估计、
历史是否被拒绝以及每级 caster 数。面板的 Save/Load 会把 preset、天空/太阳、雾体、
噪声种子、风速和确定性相机路径写入 properties 文件：

```powershell
.\gradlew.bat runOutdoorEnvironmentDemo --args="--config=build/reports/outdoor.properties"
.\gradlew.bat runOutdoorEnvironmentDemo --args="--hidden --frames=8 --save-config=build/reports/outdoor.properties"
.\gradlew.bat runOutdoorEnvironmentDemo --args="--hidden --frames=8 --load-config=build/reports/outdoor.properties --verify"
```

配置加载只在构建前替换不可变快照；失败时不会修改当前 pipeline。`route_enabled` 和
`camera_path` 作为复现元数据保存，实际路线仍可用 `--route` 显式开启。

## API 接入

```java
OutdoorEnvironmentSettings environment = OutdoorEnvironmentSettings.morningFog()
        .withLocalFogVolumes(List.of(
                LocalFogVolume.sphere(new Vector3f(0, 0, -8), 3.5f,
                        0.04f, new Vector3f(0.65f, 0.75f, 0.85f))));

RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, pbrEnvironment)
        .postProcessSettings(PostProcessSettings.defaults())
        .directionalCascades(new DirectionalCascadeSettings(4, 2048, 0.62f, 0.08f))
        .outdoorEnvironment(environment);
pipeline.build();
// 预设切换在帧边界准备候选代次；异常不会替换当前代次。
pipeline.applyOutdoorEnvironment(OutdoorEnvironmentSettings.goldenHour());
```

体积模式要求 HDR、至少一盏投射阴影的方向光和 OPAQUE/MASK 场景。LDR、ALPHA、ADDITIVE、
自定义 HDR VFX 与该模式会在构建/应用时明确拒绝；关闭体积后旧路径保持原合同。普通
`PostProcessSettings.fog` 必须关闭，预设中的 `globalFog` 由体积积分统一处理，避免同一段
视线被重复染雾。

## 视觉复核

隐藏验证和可读的 pass 顺序：

```powershell
.\gradlew.bat runOutdoorEnvironmentIntegration --rerun-tasks
.\gradlew.bat runOutdoorEnvironmentStabilityIntegration --rerun-tasks
.\gradlew.bat localOutdoorVerification --rerun-tasks
```

人工复核时固定曝光，依次观察：

1. 晨雾中近处树干投下的光束应在树冠/墙体处截断，远处由有限距离和全局高度雾平滑接续；
2. 移动镜头或进入球/盒雾体时，体积历史不应出现长条拖影，树干和 MASK 边缘不应明显漏光；
3. 切换晴天、黄昏和关闭体积，天空、太阳颜色、环境强度和阴影方向应成组变化；
4. 启用 MSAA、TAA、FXAA、Bloom 或自动曝光时，UI 不应被雾化，体积仍位于 tone mapping 之前。

建议先用 `--frames=600 --route --inside-volume --no-vsync` 运行 20～30 秒；分别保存三种
预设的 volume on/off 画面，并记录面板中的 `DEPTH`、`CSM`、`SCATTER`、`TRANS`、`HISTORY`
与 caster 诊断，避免只凭固定镜头判断历史稳定性。

## 成本与限制

```powershell
.\gradlew.bat runOutdoorEnvironmentBenchmarks --rerun-tasks
```

benchmark 命令直接给出 1080p/4K 五轮 enabled/disabled 的 `OUTDOOR_BENCHMARK`，包含
预热后的 CPU/GPU 和线程 allocation p50/p95。体积历史是独立的 RGBA16F 散射/透射率与
R16F 深度目标（Balanced 1080p 半分辨率、4K 四分之一分辨率），resize、相机/场景失效和失败帧会清除历史。首版不承诺透明表面体积散射、体积云、
实时 GI 或连续昼夜插值。
