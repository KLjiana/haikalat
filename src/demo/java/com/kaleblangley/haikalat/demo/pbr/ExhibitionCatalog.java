package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.render3d.RenderFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single source of truth for the 46 exhibition stations.
 *
 * <p>Metadata only: no scene, mesh, material, shader or pipeline is created
 * here.  The visible demo and the hidden integration both enumerate this
 * catalogue, so station identity, ordering, descriptions and limits cannot
 * drift between the two paths.</p>
 */
public final class ExhibitionCatalog {
    private static final String POSTPROCESS_EVIDENCE = "docs/planning/v0.28.1-s1-postprocess.md";
    private static final String S6_EVIDENCE = "docs/planning/v0.28.1-s6-interaction-and-quality.md";
    private static final String DELIVERY_MATRIX = "docs/planning/v0.28.1-delivery-matrix.md";
    private static final String TEST_REPORT = "docs/planning/v0.28.1-per-feature-test-report.md";

    private static final List<ExhibitionStation> STATIONS = createStations().stream()
            .sorted(java.util.Comparator.comparingInt(ExhibitionStation::id))
            .toList();
    private static final Map<Integer, ExhibitionStation> BY_ID = index(STATIONS);
    private static final List<ExhibitionStation> UNMODIFIABLE = List.copyOf(STATIONS);

    private ExhibitionCatalog() {
    }

    /** @return the 46 stations in roadmap order; immutable. */
    public static List<ExhibitionStation> stations() {
        return UNMODIFIABLE;
    }

    public static ExhibitionStation station(int id) {
        ExhibitionStation station = BY_ID.get(id);
        if (station == null) {
            throw new IllegalArgumentException("unknown exhibition station: " + id);
        }
        return station;
    }

    public static Optional<ExhibitionStation> find(int id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static List<ExhibitionStation> wing(ExhibitionWing wing) {
        return STATIONS.stream().filter(station -> station.wing() == wing).toList();
    }

    /** Fixed tour order: the roadmap order 1..46. */
    public static ExhibitionStation next(int id) {
        return STATIONS.get(id % STATIONS.size());
    }

    /**
     * Frames the deterministic integration renders per phase before comparing
     * A/B.  Time-animated stations need enough fixed steps for the effect to
     * become observable; still-life stations stay cheap.
     */
    public static int settleFrames(int id) {
        return switch (id) {
            case 3, 7, 21, 37 -> 24;
            case 44 -> 36;
            case 43 -> 45;
            case 15 -> 90;
            default -> 3;
        };
    }

    public static ExhibitionStation previous(int id) {
        return STATIONS.get((id - 2 + STATIONS.size()) % STATIONS.size());
    }

    /** @return every station id exactly once; used by the hidden integration guard. */
    public static List<Integer> validate() {
        List<String> problems = new ArrayList<>();
        for (int expected = 1; expected <= 46; expected++) {
            ExhibitionStation station = BY_ID.get(expected);
            if (station == null) {
                problems.add("missing station " + expected);
                continue;
            }
            if (station.id() != expected) {
                problems.add("station " + expected + " carries id " + station.id());
            }
            if (station.wing() == null) {
                problems.add("station " + expected + " has no wing");
            }
        }
        List<Integer> declared = new ArrayList<>();
        for (ExhibitionWing wing : ExhibitionWing.values()) {
            for (int id : wing.stationIds()) {
                declared.add(id);
            }
        }
        if (declared.size() != 46 || !declared.containsAll(stations().stream()
                .map(ExhibitionStation::id).toList())) {
            problems.add("wing membership does not cover the catalogue");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("exhibition catalogue invalid: " + problems);
        }
        return STATIONS.stream().map(ExhibitionStation::id).toList();
    }

    private static Map<Integer, ExhibitionStation> index(List<ExhibitionStation> stations) {
        Map<Integer, ExhibitionStation> map = new LinkedHashMap<>();
        for (ExhibitionStation station : stations) {
            ExhibitionStation previous = map.put(station.id(), station);
            if (previous != null) {
                throw new IllegalStateException("duplicate station id " + station.id());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static List<ExhibitionStation> createStations() {
        List<ExhibitionStation> stations = new ArrayList<>(46);
        // Wing A - 战斗特效表现
        stations.add(station(1, ExhibitionWing.A, "网格轮廓光", "Fresnel / Rim Lighting",
                null, "旋转的有法线曲面体；正视/掠射角与效果开关对照",
                List.of("艺术化轮廓项，不是物理 Fresnel BRDF", "只支持网格 VFX，广告牌不算；缺法线时拒绝"),
                List.of("docs/planning/v0.28.0-combat-vfx-materials-and-lighting.md",
                        "VfxRendererGlTest", "VfxMaterialTest"),
                "需要有效法线的 VFX 网格材质", ExhibitionBatch.ONE));
        stations.add(station(2, ExhibitionWing.A, "UV 流动与扭曲", "UV Flow / Distortion",
                null, "带方向纹理和图集格线的流动面；冻结时间、零幅度和活动状态对照",
                List.of("Flipbook 固定 LOD0，缩小无 mip 抗锯齿", "无 NEAREST/REPEAT/aniso/LOD bias"),
                List.of("docs/planning/v0.28.1-s2-uv-flow.md", "VfxSamplerContractGlTest", "VfxMaterialTest"),
                "需要 VFX 材质与纹理 owner", ExhibitionBatch.ONE));
        stations.add(station(3, ExhibitionWing.A, "溶解与侵蚀", "Dissolve / Erosion",
                null, "固定种子的网格渐隐；0、中间阈值、1 端点与边缘发光",
                List.of("首版仅 VFX 材质，未推广到不透明场景材质", "0/1 端点精确；无自造 velocity"),
                List.of("docs/planning/v0.28.0-combat-vfx-materials-and-lighting.md",
                        "docs/releases/v0.28.0-review.md", "VfxMaterialTest"),
                "需要 VFX 材质与稳定种子", ExhibitionBatch.ONE));
        stations.add(station(5, ExhibitionWing.A, "顶点位移", "Vertex Displacement",
                null, "带波形位移的网格；静态/位移、轮廓、阴影和 velocity 输入观察",
                List.of("仅 local sinusoidal 一个模型", "透明/instanced/VFX 自定义 shader 不自动继承到 shadow/depth/velocity"),
                List.of("docs/planning/v0.28.1-s2-vertex-displacement.md",
                        "VertexDisplacementGlTest", "VertexDisplacementSettingsTest"),
                "逐 draw binding，需要 TANGENT 或法线输入", ExhibitionBatch.TWO));
        stations.add(station(7, ExhibitionWing.A, "VFX 动态光照", "VFX Dynamic Lighting",
                null, "有生命周期的 VFX 照亮相邻接收球；效果前/活动/回收后",
                List.of("点光、无阴影；按优先级确定性裁减", "不做每粒子一盏灯；停止/取消不得残留灯"),
                List.of("docs/planning/v0.28.0-combat-vfx-materials-and-lighting.md",
                        "VfxLightingGlTest", "VfxLightDiagnostics"),
                "需要 HDR VFX 与直接光照", ExhibitionBatch.ONE));

        // Wing B - 特效与场景交互
        stations.add(station(4, ExhibitionWing.B, "屏幕空间扭曲", "Screen-Space Distortion",
                null, "背景网格随冲击波和热浪弯曲，蓝色标记与前景立柱保持清晰",
                List.of("需要显式 source 注入，无 pipeline.vfxDistortions", "透明遮挡物不写入深度"),
                List.of("docs/planning/v0.28.1-s1-screen-distortion.md",
                        "ScreenDistortionPassGlTest", "ScreenDistortionSettingsTest"),
                "需要 HDR + HDR VFX 合成", ExhibitionBatch.TWO));
        stations.add(station(6, ExhibitionWing.B, "投射贴花", "Projected Decals",
                RenderFeature.PROJECTED_DECALS, "地面、斜坡、墙角与遮挡板上的同一投射贴花；开关和深度遮挡对照",
                List.of("仅不透明接收面；无逐贴花 velocity", "无法线/粗糙度/高度写入与逐贴花 mask"),
                List.of("docs/planning/v0.28.1-s1-projected-decals.md",
                        "ProjectedDecalPipelineGlTest", "ProjectedDecalGlTest"),
                "需要 HDR + decal source + 单采样深度；MSAA 用 depth resolve", ExhibitionBatch.TWO));
        stations.add(station(9, ExhibitionWing.B, "接触阴影", "Contact Shadows",
                null, "物体贴近地面与离地间隙阶梯；接触阴影开关及屏幕边缘回退",
                List.of("仅主方向光；混合光像素整体衰减", "MSAA 拒绝；TAA+resize 未组合验证"),
                List.of("docs/planning/v0.28.1-s1-contact-shadows.md",
                        "ScreenSpaceContactShadowCompositionGlTest", "ContactShadowsGlTest"),
                "需要 HDR、单采样深度、存在方向光", ExhibitionBatch.TWO));
        stations.add(station(10, ExhibitionWing.B, "云影", "Cloud Shadows",
                null, "大地面和固定方向光下的世界空间云影；时间滚动及相机移动对照",
                List.of("程序化噪声，不是体积云/云图纹理", "只调指定方向光，不压暗 emissive/IBL"),
                List.of("docs/planning/v0.28.1-s1-cloud-shadows.md", "CloudShadowsGlTest"),
                "需要方向光", ExhibitionBatch.TWO));

        // Wing C - 材质与局部光照
        stations.add(station(8, ExhibitionWing.C, "薄表面透光", "Thin Translucency",
                null, "双面薄片与厚度阶梯；正面光/背光、阴影和厚度对照",
                List.of("无透射阴影贴图，薄叶自遮挡仍是普通阴影", "无厚度贴图/SS 折射/glTF transmission；点光聚光无透射"),
                List.of("docs/planning/v0.28.1-s1-thin-translucency.md",
                        "ThinTranslucencyTransmissionShadowGlTest", "ThinTranslucencyGlTest"),
                "需要双面 MASK 材质与方向光", ExhibitionBatch.TWO));
        stations.add(station(16, ExhibitionWing.C, "清漆层", "Clearcoat Shading",
                null, "清漆基底球和粗糙度/涂层量阶梯；涂层归零回到基线",
                List.of("单层均匀权重，无 clearcoat 贴图", "不参与阴影/GTAO/velocity/透明排序"),
                List.of("docs/planning/v0.28.1-s2-clearcoat.md", "ClearcoatGlTest"),
                "需要 PBR 环境与 TANGENT（可选清漆法线）", ExhibitionBatch.TWO));
        stations.add(station(17, ExhibitionWing.C, "各向异性高光", "Anisotropic Shading",
                null, "带切线方向标记的拉丝金属体；转灯/转物及各向异性关闭",
                List.of("无方向/强度贴图与逐像素旋转", "缺 TANGENT 时装配期仍要求 TANGENT"),
                List.of("docs/planning/v0.28.1-s2-anisotropic.md", "AnisotropicGlTest"),
                "需要 TANGENT 与 PBR 环境", ExhibitionBatch.TWO));
        stations.add(station(18, ExhibitionWing.C, "局部反射探针", "Local Reflection Probes",
                null, "两个可辨识环境反射物与探针影响边界；局部探针/IBL 回退",
                List.of("静态预计算、无实时捕获；每 receiver 最多 2 探针", "无探针遮挡/盒内视差；set 不可变需重载"),
                List.of("docs/planning/v0.28.1-s2-reflection-probes.md", "ReflectionProbeGlTest"),
                "需要预计算 cubemap 资源", ExhibitionBatch.TWO));
        stations.add(station(19, ExhibitionWing.C, "软阴影", "PCSS",
                null, "有面积感的遮挡体与接收平面；硬/软阴影及 blocker 距离变化",
                List.of("只支持一个主方向光，假定正交级联", "跨 tile 采样被 clamp；透明 caster 不覆盖"),
                List.of("docs/planning/v0.28.1-s2-pcss.md", "PcssGlTest"),
                "需要方向光级联阴影", ExhibitionBatch.TWO));
        stations.add(station(31, ExhibitionWing.C, "视差遮蔽贴图", "Parallax Occlusion Mapping",
                null, "高度图砖墙和斜视掠射相机；POM/普通贴图及距离变化",
                List.of("仅 UV 位移近似：不改顶点/深度/shadow/velocity/轮廓", "只限近景切线空间材质"),
                List.of("docs/planning/v0.28.1-s3-pom.md", "ParallaxGlTest"),
                "需要高度纹理与 TANGENT 近景材质", ExhibitionBatch.TWO));

        // Wing D - 水体、折射与反射
        stations.add(station(11, ExhibitionWing.D, "水面屏幕空间折射", "Screen-Space Water Refraction",
                RenderFeature.WATER_REFRACTION, "岸边水面、前景杆和背景标尺；折射前后、厚度及前景泄漏拒绝",
                List.of("屏幕空间近似，无真实水下体积/吸收分层", "前景拒绝用不透明深度，透明 VFX 不被识别"),
                List.of("docs/planning/v0.28.1-s3-water-refraction.md", "WaterRefractionGlTest"),
                "需要 active 有限水面 + HDR + 共享光栅深度", ExhibitionBatch.THREE));
        stations.add(station(13, ExhibitionWing.D, "折射", "Refraction",
                null, "有厚度的玻璃/护盾形体及高对比背景；开关、厚度和边缘回退",
                List.of("单层，玻璃间不互相折射；无深度前景拒绝", "与有限水面互斥"),
                List.of("docs/planning/v0.28.1-s3-refraction.md", "RefractionGlTest"),
                "需要场景颜色快照与不透明背景", ExhibitionBatch.THREE));
        stations.add(station(14, ExhibitionWing.D, "有限水面", "Water Surface Rendering",
                RenderFeature.WATER_SURFACE, "有岸边、浸水体和波纹的浅水池；表面/深度/吸收效果对照",
                List.of("平面矩形、波法线解析（无顶点位移）", "反射仅 IBL/回退色；透明 VFX 不进深度"),
                List.of("docs/planning/v0.28.1-s3-water-surface.md",
                        "WaterSurfacePipelineGlTest", "UnderwaterVolumeGlTest"),
                "需要 HDR 与 HDR 颜色拷贝", ExhibitionBatch.THREE));
        stations.add(station(20, ExhibitionWing.D, "屏幕空间反射", "SSR",
                null, "粗糙度阶梯、屏幕内/边缘外反射物；SSR 命中与环境回退",
                List.of("纯空间路径，无 jitter/时域，需 #34 降噪", "粗糙度仅标量 F；离屏/薄遮挡缺失"),
                List.of("docs/planning/v0.28.1-s3-ssr.md", "SsrPassGlTest", "SsrJitterGlTest"),
                "需要 HDR + roughness 通道", ExhibitionBatch.THREE));
        stations.add(station(30, ExhibitionWing.D, "平面反射", "Planar Reflections",
                RenderFeature.PLANAR_REFLECTIONS, "水池/镜面和已知反射标记；反射开关、更新节流和相机切换",
                List.of("单平面、非递归；不含透明排序/MSAA/OIT", "额外场景绘制成本无 GPU 测量；节流可能显示旧反射"),
                List.of("docs/planning/v0.28.1-s3-planar-reflections.md",
                        "PlanarReflectionPipelineGlTest", "PlanarReflectionPassGlTest"),
                "需要 HDR + 有效反射平面", ExhibitionBatch.THREE));
        stations.add(station(36, ExhibitionWing.D, "焦散", "Caustics",
                RenderFeature.CAUSTICS, "水面下接收地面及遮挡边界；焦散开关、时间和接收区域",
                List.of("艺术近似（解析正弦干涉），非物理焦散", "无水下体积/色散；单水体+单主光"),
                List.of("docs/planning/v0.28.1-s3-caustics.md", "CausticsPipelineGlTest"),
                "需要 HDR、非 MSAA、有限水面、朝下主方向光", ExhibitionBatch.THREE));

        // Wing E - 镜头与最终画面
        stations.add(station(12, ExhibitionWing.E, "景深", "Depth of Field",
                null, "前/中/远三组标尺；焦点切换与焦内/焦外清晰度对照",
                List.of("传感器固定 36 mm，不由 FOV 反推；CoC ≤16 px", "透明/VFX 只取背后不透明深度"),
                List.of("docs/planning/v0.28.1-s3-depth-of-field.md", "DepthOfFieldPassGlTest"),
                "需要 HDR + 共享深度", ExhibitionBatch.FOUR));
        stations.add(station(21, ExhibitionWing.E, "运动模糊", "Motion Blur",
                null, "固定背景和已知速度移动主体；静止/运动、方向和相机切换对照",
                List.of("单帧后向 gather，静止背景无 trail", "无 velocity dilation/逐透明 velocity；无深度感知跨表面拒绝"),
                List.of("docs/planning/v0.28.1-s3-motion-blur.md", "MotionBlurPassGlTest"),
                "需要 HDR + velocity 通道", ExhibitionBatch.FOUR));
        stations.add(station(22, ExhibitionWing.E, "SMAA 抗锯齿", "SMAA",
                RenderFeature.ANTI_ALIASING, "高频斜线、细栅栏和高对比轮廓；无 AA/SMAA 像素并排对照",
                List.of("只实现 SMAA 1x、固定 HIGH preset", "仅 HDR tone-mapped display-referred；不与 MSAA/TAA 共存"),
                List.of("docs/planning/v0.28.1-s2-smaa.md", "SmaaPassGlTest"),
                "需要 HDR + ACES 显示引用输出", ExhibitionBatch.FOUR));
        stations.add(station(23, ExhibitionWing.E, "暗角", "Vignette",
                null, "均匀亮度角落/中心标靶；零强度与受控暗角",
                List.of("仅 HDR/ACES 路径；无光学暗角/镜头呼吸", "不影响 UI"),
                List.of(POSTPROCESS_EVIDENCE, "VignettePassGlTest"),
                "需要 HDR/ACES", ExhibitionBatch.FOUR));
        stations.add(station(24, ExhibitionWing.E, "胶片颗粒", "Film Grain",
                null, "暗/中/亮灰阶卡；同 seed 固定帧与噪声关闭对照",
                List.of("非物理胶片模型；无时域抖动/重投影", "owner 重建后帧计数归零"),
                List.of(POSTPROCESS_EVIDENCE, "VignettePassGlTest"),
                "需要 HDR + 固定 seed", ExhibitionBatch.FOUR));
        stations.add(station(25, ExhibitionWing.E, "色差", "Chromatic Aberration",
                null, "中心十字与边缘白线；零强度、边缘色分离和 UV 边界",
                List.of("只有 R/B 径向分离；强参数可见采样带", "不作用于 UI"),
                List.of(POSTPROCESS_EVIDENCE, "VignettePassGlTest"),
                "需要 HDR", ExhibitionBatch.FOUR));

        // Wing F - 粒子规模与重建
        stations.add(station(15, ExhibitionWing.F, "基于深度的粒子碰撞", "Depth-Based Particle Collision",
                null, "粒子落向台阶和薄墙；碰撞前后、暂停/恢复与深度边界",
                List.of("只见当前相机可见不透明深度", "离屏/遮挡/透明/粒子自身深度不参与；MSAA 需先 resolve"),
                List.of("docs/planning/v0.28.1-s3-particle-collision.md", "GpuParticleCollisionGlTest"),
                "需要不透明单采样深度", ExhibitionBatch.FOUR));
        stations.add(station(26, ExhibitionWing.F, "GPU 粒子排序", "GPU Particle Sorting",
                null, "深度交错的彩色半透明粒子层；排序模式与固定参考",
                List.of("只排序同一 owner 活跃集合", "不解决跨 owner/透明网格交错"),
                List.of("docs/planning/v0.28.1-s3-particle-sorting.md", "GpuParticleSortingGlTest"),
                "需要 HDR VFX 与 GPU 粒子", ExhibitionBatch.FOUR));
        stations.add(station(27, ExhibitionWing.F, "GPU 粒子间接渲染", "GPU Particle Indirect Rendering",
                null, "不同活跃数/寿命的 GPU 粒子发射器；空集、增量和耗尽状态",
                List.of("无读取活跃数 API；本版不做排序", "无透明正确性：不写深度、不 OIT、不与透明网格交错"),
                List.of("docs/planning/v0.28.1-s2-particle-indirect.md", "GpuParticleVfxGlTest"),
                "需要 HDR VFX", ExhibitionBatch.FOUR));
        stations.add(station(28, ExhibitionWing.F, "加权混合 OIT", "Weighted Blended OIT",
                RenderFeature.WEIGHTED_OIT, "多层彩色透明体；排序参考与 OIT 近似、启用限制",
                List.of("非精确排序；不做场景深度遮挡", "只接受世界空间平行四边形；half-float 上限"),
                List.of("docs/planning/v0.28.1-s3-oit.md", "WeightedOitPipelineGlTest"),
                "需要 HDR、非 MSAA/TAA、声明 surface set", ExhibitionBatch.FOUR));
        stations.add(station(34, ExhibitionWing.F, "时域降噪", "Temporal Denoising",
                null, "静态细节与缓慢移动目标；历史收敛、运动和 disocclusion",
                List.of("只绑定 SSR 一个信号", "无光追真值参考；无逐像素方差预算"),
                List.of("docs/planning/v0.28.1-s4-temporal-denoise.md", "TemporalDenoisePassGlTest"),
                "需要 SSR 已启用", ExhibitionBatch.FOUR));
        stations.add(station(35, ExhibitionWing.F, "时域升采样", "Temporal Upscaling",
                RenderFeature.TEMPORAL_UPSCALING, "内部分辨率细线/棋盘与输出标尺；原生分辨率、空间放大和时域结果",
                List.of("时域重建近似，非超采样控制器；无 ML/DLSS/FSR/帧生成", "内部 source 无 velocity/previous-depth；性能与画质 NOT_EVALUATED"),
                List.of("docs/planning/v0.28.1-s4-temporal-upscaling.md", "LowResolutionMainChainGlTest"),
                "renderScale 0.25..1.0；低分辨率链下大量特性 fail-closed", ExhibitionBatch.FOUR));
        stations.add(station(41, ExhibitionWing.F, "自适应采样", "Adaptive Sampling",
                RenderFeature.ADAPTIVE_SAMPLING, "固定预算与自适应预算的同一 SSR 场景；样本分布和误差参考",
                List.of("只绑定 SSR 一个采样器", "成本匹配仅指 march 采样数；性能 NOT_EVALUATED"),
                List.of("docs/planning/v0.28.1-s4-adaptive-sampling.md",
                        "docs/planning/v0.28.1-s5-adaptive-sampling.md", "SsrAdaptiveSamplingGlTest"),
                "需要 SSR + HDR", ExhibitionBatch.FOUR));
        stations.add(station(42, ExhibitionWing.F, "可变速率着色", "Variable Rate Shading",
                RenderFeature.VARIABLE_RATE_SHADING, "细线、文字、透明与平滑区组合靶；能力检测、速率可视化及 fallback",
                List.of("仅 NV image 路径；无扩展即 fail-closed 回退", "消费者只是 SSS 全屏 pass，不覆盖几何细线/文字/透明/UI"),
                List.of("docs/planning/v0.28.1-s4-vrs.md", "VariableRateShadingGlTest"),
                "需要 GL_NV_shading_rate_image；EXT 只报告不实现", ExhibitionBatch.FOUR));

        // Wing G - 专项能力
        stations.add(station(29, ExhibitionWing.G, "大气散射", "Atmospheric Scattering",
                null, "地平线、太阳方向与远近遮挡轮廓；大气开关和参数档",
                List.of("只有单次散射；固定高度 +Y 向上观测", "sky-view 32x32 低通；太阳盘非物理；不生成 IBL"),
                List.of("docs/planning/v0.28.1-s2-atmosphere.md", "AtmospherePipelineGlTest"),
                "需要 HDR；每帧重建两个 LUT，帧成本高", ExhibitionBatch.FIVE));
        stations.add(station(32, ExhibitionWing.G, "次表面散射", "Subsurface Scattering (forward)",
                null, "比较关闭散射与不同厚度样品，观察背光处的暖色。",
                List.of("仅解析 profile，无真实体积/多次散射", "#32/#33 不能当两个独立功能累计"),
                List.of("docs/planning/v0.28.1-s4-sss.md", "SubsurfaceScatteringForwardGlTest"),
                "需要 PBR 材质与背光", ExhibitionBatch.FIVE));
        stations.add(station(33, ExhibitionWing.G, "屏幕空间 SSS", "Screen-Space SSS",
                null, "带散射 mask 的高对比边缘目标；模糊前后、背景串色检查",
                List.of("只扩散同屏已着色 HDR 颜色，离屏直接拒绝", "16-tap 大半径稀疏；半径上限 64 px"),
                List.of("docs/planning/v0.28.1-s4-sss.md", "SubsurfaceScatteringPassGlTest"),
                "需要 HDR + 屏幕空间散射 mask", ExhibitionBatch.FIVE));
        stations.add(station(38, ExhibitionWing.G, "体积粒子", "Volumetric Particles",
                RenderFeature.VOLUMETRIC_PARTICLES, "局部烟团穿过光束与遮挡体；深度、密度、光照和预算档",
                List.of("单个解析椭球，不是烟雾模拟：无注入/消散/平流", "单方向常量光、无阴影；不能与雾复用积分"),
                List.of("docs/planning/v0.28.1-s4-volumetric-particles.md",
                        "VolumetricParticlePipelineGlTest", "VolumetricParticleGlTest"),
                "需要体积雾启用且非 MSAA", ExhibitionBatch.FIVE));
        stations.add(station(40, ExhibitionWing.G, "体积云", "Volumetric Clouds",
                RenderFeature.VOLUMETRIC_CLOUDS, "同一片天空下对照云团、天空补光、远景渐隐和云影",
                List.of("云体是有限高度带；渐隐不是物理大气耦合", "单方向光步进自遮蔽；地面云影与可见云不是同一投影"),
                List.of("docs/planning/v0.28.1-s4-clouds.md", "VolumetricCloudPipelineGlTest"),
                "需要 HDR、稳定单采样；coverageField 须与 #10 一致", ExhibitionBatch.FIVE));
        stations.add(station(43, ExhibitionWing.G, "体积烟雾模拟", "Volumetric Smoke Simulation",
                RenderFeature.VOLUMETRIC_SMOKE, "有界烟雾容器、注入源和固定观察相机；演化阶段与 reset 对照",
                List.of("不是完整流体；不是 #38 或雾的替代", "单方向常量光 HG 单次散射；AABB 边界"),
                List.of("docs/planning/v0.28.1-s5-smoke.md", "SmokePipelineGlTest"),
                "需要 HDR、稳定单采样；sim+render 两侧都 active", ExhibitionBatch.FIVE));
        stations.add(station(46, ExhibitionWing.G, "头发丝渲染", "Hair Strand Rendering",
                RenderFeature.HAIR_STRANDS, "头发卡片与 strand/ribbon 主体、背光轮廓和阴影接收面；静态/运动/TAA 状态",
                List.of("无动力学/风/碰撞/自阴影/透射/多次散射", "SORTED_ALPHA 按 strand 质心排序；MSAA 拒绝"),
                List.of("docs/planning/v0.28.1-s4-hair.md", "HairStrandGlTest", "HairTaaInvalidationGlTest"),
                "需要 HDR + 光栅深度 + 方向光；MSAA 拒绝", ExhibitionBatch.TWO));

        // Wing H - 高成本研究能力
        stations.add(station(37, ExhibitionWing.H, "随机透明", "Stochastic Transparency",
                RenderFeature.STOCHASTIC_TRANSPARENCY, "多层透明网格/薄片；静帧收敛、序列稳定性和参考图",
                List.of("有噪声与约 40+ 帧时域滞后；非精确排序", "不做不透明遮挡；单采样"),
                List.of("docs/planning/v0.28.1-s4-stochastic-transparency.md",
                        "StochasticTransparencyPipelineGlTest"),
                "需要 HDR、稳定单采样（MSAA/TAA 拒绝）", ExhibitionBatch.FIVE));
        stations.add(station(39, ExhibitionWing.H, "屏幕空间全局光照", "SSGI",
                RenderFeature.SSGI, "观察两侧白板、球和地面上的色彩反弹。侧转移开发光板，观察反弹光变化。",
                List.of("有界空间近似，无多次弹射/离屏重建", "薄几何会被步长跳过；与 DDGI/VCT 互斥"),
                List.of("docs/planning/v0.28.1-s5-ssgi.md", "SsgiPassGlTest", "SsgiTemporalGlTest"),
                "需要 HDR；advanced/default-off", ExhibitionBatch.FIVE));
        stations.add(station(44, ExhibitionWing.H, "动态漫反射 GI 探针", "DDGI",
                RenderFeature.DDGI, "青色小球标出探针中心。比较左侧覆盖区与右侧网格外的白板，背后的主光留在视野外。",
                List.of("探针只见有限距离内表面；每 texel 单射线", "只消费主方向光与场景级 albedo；MSAA/TAA 不支持"),
                List.of("docs/planning/v0.28.1-s5-ddgi.md",
                        "DdgiProbePassGlTest", "RenderPipelineWiringB3GlTest"),
                "需要 HDR、单采样、方向光", ExhibitionBatch.FIVE));
        stations.add(station(45, ExhibitionWing.H, "体素锥追踪", "Voxel Cone Tracing",
                RenderFeature.VOXEL_CONE_TRACING, "两侧发光源相同，右边有遮挡墙。比较白板和地面的反弹，前排方块标出体素尺度。",
                List.of("三角场景快照由调用方提供；shell 体素化", "<1 体素墙/斜向 cone 仍漏光；与 DDGI 互斥"),
                List.of("docs/planning/v0.28.1-s5-vct.md", "VctGlTest", "RenderPipelineWiringB3GlTest"),
                "需要 HDR、单采样、共享深度与场景三角输入", ExhibitionBatch.FIVE));

        return stations;
    }

    private static ExhibitionStation station(int id, ExhibitionWing wing, String nameZh, String nameEn,
                                             RenderFeature feature, String subject,
                                             List<String> limitations, List<String> evidence,
                                             String gate, ExhibitionBatch batch) {
        return new ExhibitionStation(id, wing, nameZh, nameEn, feature, subject,
                limitations, evidence, gate, batch);
    }
}
