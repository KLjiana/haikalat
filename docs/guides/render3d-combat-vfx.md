# 战斗 VFX 材质与动态光照（0.28）

2026-11-11 随 0.28.0 实现。本文说明当前可用的 API、作者流程、行为语义与已知限制；版本验收见 [0.28.0 验收](../releases/v0.28.0-review.md)，原始范围见 [实施计划](../planning/v0.28.0-combat-vfx-materials-and-lighting.md)。

## 1. 能力边界

0.28 在已有 `EffectAsset` / `EffectInstance` / `EffectSnapshot` / `VfxRenderer` 链上增加四项可组合能力：

| 能力 | 入口 | 适用对象 | 不包含 |
|---|---|---|---|
| 轮廓光 | `VfxMaterial.rimLight(VfxRimLight)` | 有 NORMAL 的 MeshVfx | 不替换 PBR Fresnel，不给无 NORMAL 网格伪报生效 |
| UV Flow / Distortion | `VfxMaterial.uvFlow(VfxUvFlow)` | 全部 VFX 材质 | 不采样场景颜色，不是屏幕空间扭曲 |
| Dissolve / Erosion | `VfxMaterial.dissolve(VfxDissolve)` | 全部 VFX 材质 | 不推广到普通不透明场景材质 |
| 无阴影点光 | `EffectAsset.light(EffectLight)` + `RenderPipeline.vfxLights(...)` | 效果实例 | 不产生阴影、不默认体积注光、不是逐粒子灯 |

四项都可关闭；关闭时材质默认值与旧行为一致。`VfxMaterial.requiresNormals()` 只表示启用了轮廓光。

屏幕空间冲击波／热浪是独立的场景颜色消费者（路线图 #4，0.28.1 S1），不属于本表的材质 UV Flow；数据、Pass 顺序与限制见[屏幕空间扭曲技术卡](../planning/v0.28.1-s1-screen-distortion.md)。

## 2. 材质参数

```java
VfxMaterial energy = VfxMaterial.builder("energy")
        .texture(AssetRef.of("/vfx/masks/kenney/light_masks/transparent/ring_a_streaks.png"))
        .maskMode(VfxMaskMode.ALPHA)
        .blendMode(BlendMode.ALPHA)
        .emissiveIntensity(2.4f)
        .rimLight(new VfxRimLight(new Vector3f(0.35f, 0.75f, 1.0f), 3.2f, 2.0f))
        .uvFlow(VfxUvFlow.of(0.22f, 0.06f, 5.0f, 0.06f, seed))
        .dissolve(VfxDissolve.explicit(0.12f,
                new Vector3f(0.45f, 0.85f, 1.0f), 5.0f, 6.0f, seed))
        .build();
```

- 轮廓权重为 `pow(1 - clamp(dot(N, V), 0, 1), exponent)`；双面网格在着色器内把背面法线翻向观察者。非均匀/镜像缩放使用逆转置法线矩阵；奇异变换明确跳过该 primitive 的轮廓项，不产生 NaN。
- UV 顺序固定为：局部 UV → 速度 × 显式效果时间 → 线性噪声扭曲 → cell 内 half-texel 内缩/夹取 → Flipbook 当前/下一 cell → 两次采样混合。速度为 UV/秒，shader 不读取墙钟。
- 溶解可见侧为 `noise >= progress`；`progress=0` 恢复完整材质，`progress=1` 完全消失。`edgeWidth=0` 时没有边缘发光且不除零。边缘发光只增加线性 HDR 颜色，不增加 alpha；最终仍受原 mask、alpha/additive 与雾变体预乘约定限制。
- 噪声是 renderer 自有、确定性的 128×128 线性 R8 资源；材质 seed 只偏移采样坐标，不按材质复制纹理。噪声使用 REPEAT 与 base level 采样，避免 dissolve 阈值随 mip 模糊。
- Sprite 纹理的 sampler 合同已冻结：颜色纹理为 `GL_SRGB8_ALPHA8`，mask 为线性 `GL_R8`；两者都是完整 immutable mip 链、`GL_LINEAR_MIPMAP_LINEAR` 缩小、`GL_LINEAR` 放大、`GL_CLAMP_TO_EDGE`、无 anisotropic filtering。sRGB 在线性光中解码/生成 mip；具体数值、UV 与 atlas 边界交互、限制见 [F6 采样器验收卡](../planning/v0.28.1-s2-uv-flow.md)。

## 3. 效果时间、暂停、取消与重放

```java
EffectInstance effect = asset.instantiate(seed);
effect.update(delta, origin);
effect.pause();          // 冻结效果时钟；快照可重复绘制，不重新采样随机数
effect.resume();
effect.cancel();         // 立即移除 primitive 与灯；不关闭共享材质/纹理
effect.replay();         // 用原定义与 seed 重建运行状态
effect.dissolve(0.75f);  // 显式阶段进度，仅 EXPLICIT 材质消费
EffectSnapshot snapshot = effect.snapshot(cameraPosition);
```

- `emitting(false)` 仍是“停止生成新 primitive”，已有内容继续老化；它不是暂停。
- `CombatVfxController` 的阶段时钟和 `EffectInstance` 共享同一暂停条件：`instance.paused()` 时 `update` 直接返回，不推进 phase、不计算 dissolve、不触发 lightEnd cancel 或 replay；恢复后才继续推进。
- `EffectSnapshot` 新增 `effectTimeSeconds`、`dissolveProgress` 与 `lights`，旧构造器保留并填入零值/空灯。`MeshInstance` 新增 `lifetimeProgress`，旧构造器保留。
- 可重复性承诺限定为相同定义、seed、事件与更新序列；不承诺不同 dt 划分的逐位一致积分。
- 同一 `EffectAsset` 的多个实例有独立 `effectId`、时钟、seed 状态和灯；共享材质不进入实例缓存键。

## 4. 效果灯与帧冻结

```java
EffectAsset asset = EffectAsset.builder("impact")
        .meshes(meshVfx)
        .light(new EffectLight(1.2f, 3.0f, new Vector3f(0.0f, 0.45f, 0.0f),
                new Vector3f(0.35f, 0.7f, 1.0f), 14.0f, 7.0f, 10,
                intensityOverLife, null))
        .build();

pipeline.maxVfxLights(4);
pipeline.vfxLights(snapshot.lights()); // 每帧提交完整不可变输入
pipeline.execute(device, delta);
VfxLightDiagnostics lights = pipeline.vfxLightDiagnostics();
```

- `VfxLight` 的长期身份是 `(effectId, lightIndex)`，由 `LightIdAllocator` 映射为与 Scene 灯不冲突的正数 stable ID；不使用数组索引、帧号或负区间。
- 灯在 `RenderFrameContext` 冻结前与普通灯合并。视觉录制与表面光照消费同一帧输入；后到输入下一帧生效。
- `EffectLight.intensity`/`range` 是基础值；可选 `intensityOverLife`/`rangeOverLife` 是归一化倍率，最终值为 `base × curve`。因此把作者强度设为 0 会得到 0，设为 30 会在曲线峰值得到 30，而不是被固定曲线覆盖为 1。
- 选择顺序为显式 priority 降序，再按 effect identity 稳定排序。容量为 `min(maxVfxLights, maxLocalLights - 场景 local 灯数)`；场景自身超限仍由 `FrameLightTable` 明确报错，不会裁掉用户灯。
- 特效灯固定 `castShadows=false`、`LightVolumeHints.DISABLED`，不创建阴影槽位。位置/颜色/强度/范围/生灭变化不重建 RenderGraph；只有容量规格变化走候选事务。
- `hdrVfx=false` 时视觉录制与关联灯同时停止，诊断原因 `hdr-vfx-disabled`；直接光关闭时灯仍在输入诊断中，原因 `direct-lighting-disabled`，但不贡献表面光照。容量裁减原因 `capacity-culled`。
- `Render3dDiagnostics.vfxLights()` 与 `RenderPipeline.vfxLightDiagnostics()` 提供 requested/selected/culled/capacity/reason/inputRevision 的有界观察；不保留逐帧灯列表。

## 5. 资源准备与所有权

- `VfxRenderer.prepare(snapshot)` 在发布前加载缺失纹理；失败只关闭本次候选纹理，旧缓存与旧配置保持可用。缓存最多 32 个键，按 LRU 淘汰未被当前候选引用的条目；被活动实例引用的纹理不会因一次候选准备被提前释放。
- 作者路径的顺序为：构建 CPU 候选 → 校验定义/资源引用 → owner 线程 `prepare` → 帧边界替换实例/材质 → 安全退役旧资源。标量编辑不触发解码/上传。
- `VfxRenderer` 拥有 shader、网格缓存、颜色/噪声纹理；调用方拥有 `EffectAsset`/`EffectInstance` 与 `VfxRenderer` 生命周期。取消实例不释放共享资产，pipeline 不关闭调用方 renderer。
- 发布后退役异常保留已发布的活动配置并报告；单帧 GPU 失败不等于像素已回滚，配置事务与帧成功分别记录。

## 6. Showcase 作者流程

`HaikalatShowcaseDemo` 固定相机、受光 PBR 地面/背板与法线网格 VFX，按“蓄力 → 释放 → 命中 → 消退”驱动：

- 面板提供 rim 强度/指数、UV 速度/幅度、溶解进度/边缘、灯强度/范围/优先级、Replay/Cancel/Pause/Apply/A-B/Save/Load、HDR VFX/直接光/管线暂停门。
- 草稿编辑不直接覆盖生效配置；`Apply` 构建完整候选，失败保留旧效果并显示原因。候选准备阶段的临时 mesh 会在发布前清除，首个 update 才在效果 origin 生成唯一网格。
- 消退阶段从 fade 开始停止发射；到达 lightEnd 时清除全部 primitive 与灯，循环在 lightEnd+2s 后重放。
- `A/B` 使用相同相机、seed、资源与时间规则，只关闭四项新技术，便于固定条件对照。
- A/B 不重建实例：保留同一 `EffectInstance`、效果时钟与 seed，只替换快照的 `VfxVisualSet` 并清空关联灯；因此切换不会把采样时间归零，也不会恢复默认 seed/timing。
- 配置保存到 `build/vfx-authoring/showcase-combat-vfx.properties`，使用独立 schema 1（`CombatVfxProfileCodec`），严格拒绝缺字段、未知键、未知版本、非有限数、越界值；原子保存不会用损坏内容覆盖有效文件。
- 网格/纹理通过 demo 的 `CombatVfxCatalog` 逻辑 ID 解析；不序列化任意 Java 对象、GL ID、完整 Scene 或 callback。

## 7. 验证入口

```powershell
.\gradlew.bat test --tests "*VfxMaterialTest" --tests "*EffectInstanceTest" --tests "*VfxRendererMathTest" --tests "*VfxLightBridgeTest" --tests "*CombatVfxProfileCodecTest" --tests "*CombatVfxControllerTest" --tests "*VfxSamplerReferenceTest"
.\gradlew.bat glSmoke --tests "*VfxRendererGlTest" --tests "*VfxTextureCacheGlTest" --tests "*VfxSamplerContractGlTest"
.\gradlew.bat runShowcaseIntegration
```

`VfxRendererGlTest` 覆盖轮廓 HDR 增量、固定时间 UV Flow 采样变化、溶解端点/边缘与纹理准备事务；`VfxTextureCacheGlTest` 覆盖导入格式与缓存事务；`VfxSamplerReferenceTest` 从 fixture 独立推导 sRGB/mip/bilinear 数值，`VfxSamplerContractGlTest` 在生产纹理对象上冻结过滤、wrap、mip 链、sRGB 解码、放大双折中点、缩小 mip 平均与 Flipbook cell 边界；`VfxLightBridgeTest` 覆盖 ID/容量/门控/冻结；`CombatVfxProfileCodecTest` 覆盖 schema 与坏输入。像素通过不等于人工视觉通过；正式性能、Minecraft 客户端与完整 TAA/雾矩阵仍需单独记录。

## 8. 已知限制

- 轮廓只对带 NORMAL 的 MeshVfx 生效；旧 quad/Ribbon/Decal 继续走旧路径。
- 首版噪声为 renderer 自有程序化纹理，不是可替换的外部噪声资产；不支持任意噪声资源热替换。
- 非 padded Flipbook 图集固定使用 LOD0 采样，避免 mip 选择把相邻 cell 平均进来；代价是缩小粒子不参与 mip 过滤，高细节/抗锯齿不保证。真实 GL 证据证明 cell 选择、half-texel inset 与缩小 sprite 都不串 cell；同一 12×4 图集的 3×1 mip 确实跨过 cell 边界，因此 LOD0 是必要条件而不是像素巧合。
- 非 Flipbook 但手工设置 `uvRegion` 的子区域没有 LOD0 保护：clamp-to-edge 只保护整张纹理的外边界，高缩小率选到粗 mip 时可能跨过子区域边界。需要缩小抗锯齿时应使用 padded atlas、保持放大或改用 Flipbook；详见 [F6 采样器验收卡](../planning/v0.28.1-s2-uv-flow.md)。
- `LIFECYCLE` 溶解进度由 particle 的归一化年龄和 mesh 的 `lifetimeProgress` 提供；Ribbon/Decal 当前没有年龄字段，使用 0，需要时间驱动时应配置 `EXPLICIT` 并由效果阶段显式设置进度。
- 溶解是 VFX 材质效果，不是场景材质通用溶解；没有逐 primitive 的独立噪声相位。
- 特效灯只有无阴影点光；不支持聚光、投影、逐粒子灯或空间参数混合。
- `RenderPipeline` 不自动暂停外部 CPU 模拟；工具暂停联动属于 demo 更新驱动的明确行为。
- 当前未新增自动 diff 选测；新必需用例已加入 `focused-verification.gradle` 的显式 JVM/GL 列表，但聚焦通过仍不是发布就绪。
