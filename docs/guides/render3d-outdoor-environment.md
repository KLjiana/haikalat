# Render3D 天空与室外环境

v0.25 的 `OutdoorEnvironmentSettings(enabled, preset, sky)` 只描述天空与太阳外观。正式体积雾由独立的 `VolumetricFogSettings` 驱动，所有灯使用 `LightVolumeHints` 描述体积贡献。

天空预设保留 `morningFog()`、`clearDay()`、`goldenHour()` 与 `withSky()`；旧太阳射线步进、全局/局部雾、噪声和风不再属于天空设置。
构建前调用 `pipeline.outdoorEnvironment(sky).volumetricFog(fog)`；构建后使用 `applyOutdoorEnvironment` 或 `applyVolumetricFog`。
`applyVisualSettings` 可原子应用天空、物理雾、曝光、IBL、Bloom 和 AA；候选分配失败保留旧代次和借用 IBL。

```powershell
.\gradlew.bat runRender3dVolumetricFogDemo --args="--scene=forest_morning"
.\gradlew.bat runRender3dVolumetricFogDemo --args="--scene=forest_morning --fog-off"
.\gradlew.bat runRender3dVolumetricFogDemo --args="--scene=forest_morning --quality=low --history-off"
```

详见 [体积雾指南](render3d-volumetric-fog.md)、[M7 迁移结果](../releases/v0.25.0-m7-migration-report.md)。
旧 `runOutdoorEnvironment*`、旧面板及 96-step production selector 已退役；原单太阳实现通过 [归档参考与独立复现脚本](outdoor-reference.md) 运行。
天空 IBL 继续从同一渐变生成并排除太阳圆盘；环境由调用方拥有，管线借用。独立 analytic 后处理仍可用于其他场景，与正式物理体积雾互斥。
