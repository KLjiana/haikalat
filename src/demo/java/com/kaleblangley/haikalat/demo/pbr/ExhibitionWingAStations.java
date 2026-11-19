package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.VfxDissolve;
import com.kaleblangley.haikalat.subsystems.vfx.VfxLight;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaskMode;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaterial;
import com.kaleblangley.haikalat.subsystems.vfx.VfxRimLight;
import com.kaleblangley.haikalat.subsystems.vfx.VfxUvFlow;
import com.kaleblangley.haikalat.subsystems.vfx.VfxVisualSet;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;

/**
 * Wing A - combat VFX stations.
 *
 * <p>#1 Fresnel/rim, #2 UV flow, #3 dissolve and #7 dynamic VFX lighting are
 * wired to the production CPU VFX path: the station driver returns a feed that
 * records a real {@link EffectSnapshot} through {@code assets.vfxRenderer()} in
 * the session-owned HDR VFX composite pass.  The station {@link Scene} keeps
 * the shared PBR studio/receiver geometry, so the only A/B difference is the
 * observed feature (zero-intensity rim, frozen flow, disabled dissolve, or no
 * lifecycle light).</p>
 *
 * <p>#5 vertex displacement is intentionally not claimed here: wing B wires it
 * through the per-object {@code VertexDisplacementDrawBinding}.  Returning
 * {@code null} for it lets the wing chain fall through instead of duplicating
 * that station.</p>
 */
final class ExhibitionWingAStations {
    /** Demo-owned deterministic UV sphere with positions, UVs, normals and tangents. */
    private static final MeshData SUBJECT_SPHERE = PbrSphereMesh.create(32, 20);
    /** Unit quad with UVs for the flowing-texture subject. */
    private static final MeshData FLOW_QUAD = BuiltinMeshData.texturedQuad("wing-a-uv-flow-quad");
    /** Real directional-streak mask owned by the production VFX resource tree. */
    private static final AssetRef FLOW_MASK = AssetRef.of(
            "/vfx/masks/kenney/light_masks/transparent/streaks_composed_a_noise.png");
    /** Real circular mask used as the dissolve coverage pattern. */
    private static final AssetRef DISSOLVE_MASK = AssetRef.of(
            "/vfx/masks/kenney/light_masks/transparent/circle_a_streaks.png");

    private static final Vector3f RIM_COLOR = new Vector3f(0.36f, 0.82f, 1.0f);
    private static final Vector3f LIGHT_COLOR = new Vector3f(1.0f, 0.62f, 0.28f);
    private static final Vector3f LIGHT_POSITION = new Vector3f(-0.55f, 1.90f, -0.30f);
    private static final long LIGHT_EFFECT_ID = 7_001L;
    private static final float LIGHT_MAX_INTENSITY = 20.0f;
    private static final float LIGHT_RANGE = 6.0f;
    private static final float LIGHT_CYCLE_SECONDS = 4.0f;
    private static final float LIGHT_RAMP_IN_SECONDS = 0.2f;
    private static final float LIGHT_HOLD_END_SECONDS = 1.8f;
    private static final float LIGHT_RAMP_OUT_END_SECONDS = 2.6f;

    private ExhibitionWingAStations() {
    }

    static ExhibitionStationBuilder builder(int id) {
        return switch (id) {
            case 1 -> ExhibitionWingAStations::rimLight;
            case 2 -> ExhibitionWingAStations::uvFlow;
            case 3 -> ExhibitionWingAStations::dissolve;
            // #5 belongs to wing B's vertex-displacement wiring.
            case 7 -> ExhibitionWingAStations::vfxDynamicLighting;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ #1 rim

    private static ExhibitionStationView rimLight(ExhibitionStationBuildContext context,
                                                  ExhibitionStation station,
                                                  ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = subjectStage(assets, rimCamera());
        // Dark PBR receiver inside the rotating VFX shell: the shell silhouette
        // reads against it, and its interior base tint is kept very low so the
        // rim term is the visible contribution.
        scene.add(SceneObject.fixed(assets.sphere, assets.darkMaterial,
                new Matrix4f().identity().translation(0.0f, 1.45f, -0.9f).scale(0.88f)));

        VfxMaterial material = VfxMaterial.builder(
                        effect ? "wing-a-rim-effect" : "wing-a-rim-baseline")
                .blendMode(BlendMode.ADDITIVE)
                .emissiveIntensity(0.12f)
                // Baseline uses the same rim factory at intensity 0: identical mesh,
                // pose, blend and feed; only the artistic rim contribution is zero.
                .rimLight(new VfxRimLight(RIM_COLOR, effect ? 6.0f : 0.0f, 2.0f))
                .build();
        MeshStationFeed feed = new RimFeed(assets, material);
        return new ExhibitionStationView(scene, ExhibitionSettings.base(context.renderSettings()),
                new MeshFeedDriver(feed),
                effect ? "rim color (0.36, 0.82, 1.00) / intensity 6.0 / exponent 2.0 / additive"
                        : "");
    }

    /** Rotating non-uniform sphere: silhouette and normals both change over time. */
    private static final class RimFeed extends MeshStationFeed {
        private RimFeed(ExhibitionAssets assets, VfxMaterial material) {
            super(assets, SUBJECT_SPHERE, material,
                    new Vector4f(0.10f, 0.45f, 0.95f, 1.0f), 1L);
        }

        @Override
        protected Matrix4f model(float seconds) {
            return new Matrix4f().identity()
                    .translation(0.0f, 1.45f, -0.9f)
                    .rotateY(seconds * 0.55f)
                    .rotateX(0.16f)
                    .scale(1.30f, 1.42f, 1.04f);
        }
    }

    // --------------------------------------------------------------- #2 UV flow

    private static ExhibitionStationView uvFlow(ExhibitionStationBuildContext context,
                                                ExhibitionStation station,
                                                ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = subjectStage(assets, flowCamera());
        // Neutral PBR board behind the flowing panel gives the alpha streaks a
        // stable receiver and makes the flow direction readable.
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                new Matrix4f().identity().translation(0.0f, 1.55f, -1.80f)
                        .scale(1.55f, 1.12f, 0.06f)));

        // Effect: positive UV velocity plus bounded local noise distortion.
        // Baseline: same texture/mesh/feed with velocity and amplitude frozen at 0.
        VfxUvFlow flow = effect
                ? VfxUvFlow.of(0.18f, 0.05f, 3.4f, 0.22f, 0x5EEDL)
                : VfxUvFlow.of(0.0f, 0.0f, 3.4f, 0.0f, 0x5EEDL);
        VfxMaterial material = VfxMaterial.builder(
                        effect ? "wing-a-uv-flow-effect" : "wing-a-uv-flow-baseline")
                .texture(FLOW_MASK)
                .maskMode(VfxMaskMode.ALPHA)
                .blendMode(BlendMode.ADDITIVE)
                .emissiveIntensity(2.2f)
                .uvFlow(flow)
                .build();
        MeshStationFeed feed = new FlowFeed(assets, material);
        return new ExhibitionStationView(scene, ExhibitionSettings.base(context.renderSettings()),
                new MeshFeedDriver(feed),
                effect ? "uv velocity (0.18, 0.05)/s / noise scale 3.4 / amplitude 0.22 / seed 0x5EED"
                        : "");
    }

    private static final class FlowFeed extends MeshStationFeed {
        private FlowFeed(ExhibitionAssets assets, VfxMaterial material) {
            super(assets, FLOW_QUAD, material,
                    new Vector4f(0.22f, 0.72f, 1.0f, 1.0f), 2L);
        }

        @Override
        protected Matrix4f model(float seconds) {
            return new Matrix4f().identity()
                    .translation(0.0f, 1.55f, -1.45f)
                    .scale(2.7f, 1.85f, 1.0f);
        }

        @Override
        protected float effectTime(float seconds) {
            // The atlas sampler clamps to the cell. Sweep back before the
            // offset reaches the texture's transparent border.
            return 2f - Math.abs(seconds % 4f - 2f);
        }
    }

    // ---------------------------------------------------------------- #3 dissolve

    private static ExhibitionStationView dissolve(ExhibitionStationBuildContext context,
                                                  ExhibitionStation station,
                                                  ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = subjectStage(assets, dissolveCamera());
        // PBR receiver behind the shell; dissolved fragments reveal it.
        scene.add(SceneObject.fixed(assets.sphere, assets.neutralMaterial,
                new Matrix4f().identity().translation(0.0f, 1.45f, -1.0f).scale(0.92f)));

        VfxMaterial.Builder builder = VfxMaterial.builder(
                        effect ? "wing-a-dissolve-effect" : "wing-a-dissolve-baseline")
                .texture(DISSOLVE_MASK)
                .maskMode(VfxMaskMode.ALPHA)
                .blendMode(BlendMode.ALPHA)
                .emissiveIntensity(1.8f);
        if (effect) {
            builder.dissolve(VfxDissolve.explicit(0.20f,
                    new Vector3f(1.0f, 0.48f, 0.10f), 6.0f, 4.5f, 0x51A7L));
        }
        MeshStationFeed feed = new DissolveFeed(assets, builder.build(), effect);
        return new ExhibitionStationView(scene, ExhibitionSettings.base(context.renderSettings()),
                new MeshFeedDriver(feed),
                effect ? "explicit dissolve 0.35..1 over 3.6 s / edge width 0.20 / "
                        + "edge color (1.00, 0.48, 0.10) x 6.0 / noise scale 4.5 / seed 0x51A7"
                        : "");
    }

    private static final class DissolveFeed extends MeshStationFeed {
        private static final float CYCLE_SECONDS = 5.0f;
        private static final float SWEEP_SECONDS = 3.6f;
        private final boolean dissolving;

        private DissolveFeed(ExhibitionAssets assets, VfxMaterial material, boolean dissolving) {
            super(assets, SUBJECT_SPHERE, material,
                    new Vector4f(0.30f, 0.85f, 1.0f, 0.92f), 3L);
            this.dissolving = dissolving;
        }

        @Override
        protected Matrix4f model(float seconds) {
            return new Matrix4f().identity()
                    .translation(0.0f, 1.45f, -1.0f)
                    .rotateY(seconds * 0.20f)
                    .scale(1.18f);
        }

        @Override
        protected float dissolveProgress(float seconds) {
            if (!dissolving) {
                return 0.0f;
            }
            // Start already partially eroded so the A/B phase switch is
            // observable immediately; the sweep still reaches the endpoint.
            float phase = seconds % CYCLE_SECONDS;
            return Math.min(1.0f, 0.35f + 0.65f * phase / SWEEP_SECONDS);
        }
    }

    // ---------------------------------------------------- #7 dynamic VFX lighting

    private static ExhibitionStationView vfxDynamicLighting(ExhibitionStationBuildContext context,
                                                            ExhibitionStation station,
                                                            ExhibitionPhase phase) {
        return vfxDynamicLighting(context, phase, new Matrix4f());
    }

    static ExhibitionStationView hallLight(ExhibitionStationBuildContext context, Matrix4f placement) {
        return vfxDynamicLighting(context, ExhibitionPhase.EFFECT, placement);
    }

    private static ExhibitionStationView vfxDynamicLighting(ExhibitionStationBuildContext context,
                                                            ExhibitionPhase phase, Matrix4f placement) {
        ExhibitionAssets assets = context.assets();
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = lifecycleLightStage(assets);
        LifecycleLightDriver driver = new LifecycleLightDriver(assets, effect, placement);
        return new ExhibitionStationView(scene, ExhibitionSettings.base(context.renderSettings()),
                driver,
                effect ? "point light effectId 7001 / intensity 0..20 / range 6.0 / "
                        + "priority 10 / 4.0 s lifecycle" : "");
    }

    /** PBR receivers the effect light actually illuminates, plus studio scale props. */
    private static Scene lifecycleLightStage(ExhibitionAssets assets) {
        Scene scene = ExhibitionSceneKit.studio(assets,
                ExhibitionSceneKit.camera(0.0f, 1.70f, 4.8f, -90.0f, -7.0f));
        ExhibitionSceneKit.addScaleProps(scene, assets);
        scene.add(SceneObject.fixed(assets.sphere, assets.neutralMaterial,
                new Matrix4f().identity().translation(0.0f, 1.20f, -1.70f).scale(0.78f)));
        scene.add(SceneObject.fixed(assets.cube, assets.accentMaterial,
                new Matrix4f().identity().translation(1.45f, 0.55f, -1.35f)
                        .scale(0.50f, 0.55f, 0.50f)));
        return scene;
    }

    /**
     * Publishes one stable-identity effect light through the production bridge.
     *
     * <p>The light ramps in, holds, ramps out and is then reclaimed (empty list)
     * on a fixed 4 s schedule.  The same immutable list is both the snapshot
     * light input and the {@code pipeline.vfxLights(...)} bridge input, so the
     * HDR VFX recording and the surface lighting consume one revision.  The
     * baseline driver publishes only empty lists and no feed, so no light can
     * survive an A/B switch.</p>
     */
    private static final class LifecycleLightDriver implements ExhibitionStationDriver {
        private final ExhibitionAssets assets;
        private final boolean emitting;
        private final Vector3f lightPosition;
        private final float lightRange;
        private float elapsedSeconds;
        private EffectSnapshot frameSnapshot;

        private LifecycleLightDriver(ExhibitionAssets assets, boolean emitting, Matrix4f placement) {
            this.assets = assets;
            this.emitting = emitting;
            lightPosition = placement.transformPosition(new Vector3f(LIGHT_POSITION));
            lightRange = LIGHT_RANGE * placement.m00();
            this.frameSnapshot = snapshot(0.0f, List.of());
        }

        @Override
        public void onSelect(RenderPipeline pipeline, ExhibitionPhase phase) {
            elapsedSeconds = 0.0f;
            frameSnapshot = snapshot(0.0f, List.of());
            // Clear any light published by the previous station/phase first.
            pipeline.vfxLights(List.of());
        }

        @Override
        public void onFrame(RenderPipeline pipeline, int frameIndex, float deltaSeconds,
                            ExhibitionPhase phase) {
            elapsedSeconds += deltaSeconds;
            List<VfxLight> lights = emitting ? lightsAt(elapsedSeconds) : List.of();
            pipeline.vfxLights(lights);
            frameSnapshot = snapshot(elapsedSeconds, lights);
        }

        @Override
        public ExhibitionVfxFeed vfxFeed() {
            return emitting ? this::record : null;
        }

        private void record(CommandBuffer commands, Matrix4f projection, Matrix4f view,
                            int sceneDepthTexture, int width, int height, int frameIndex,
                            float deltaSeconds) {
            assets.vfxRenderer().record(commands, frameSnapshot, projection, view,
                    sceneDepthTexture, width, height);
        }

        private List<VfxLight> lightsAt(float seconds) {
            float phase = seconds % LIGHT_CYCLE_SECONDS;
            float intensity;
            if (phase < LIGHT_RAMP_IN_SECONDS) {
                intensity = LIGHT_MAX_INTENSITY * phase / LIGHT_RAMP_IN_SECONDS;
            } else if (phase < LIGHT_HOLD_END_SECONDS) {
                intensity = LIGHT_MAX_INTENSITY;
            } else if (phase < LIGHT_RAMP_OUT_END_SECONDS) {
                intensity = LIGHT_MAX_INTENSITY
                        * (1.0f - (phase - LIGHT_HOLD_END_SECONDS)
                        / (LIGHT_RAMP_OUT_END_SECONDS - LIGHT_HOLD_END_SECONDS));
            } else {
                // Reclaimed: the lifecycle ends with no residual light.
                return List.of();
            }
            return List.of(new VfxLight(LIGHT_EFFECT_ID, 0, lightPosition, LIGHT_COLOR,
                    intensity, lightRange, 10));
        }

        private static EffectSnapshot snapshot(float timeSeconds, List<VfxLight> lights) {
            return new EffectSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(),
                    VfxVisualSet.legacy(), timeSeconds, 0.0f, lights);
        }
    }

    // ------------------------------------------------------------- shared wiring

    private static Scene subjectStage(ExhibitionAssets assets, Camera camera) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        ExhibitionSceneKit.addScaleProps(scene, assets);
        return scene;
    }

    private static Camera rimCamera() {
        return ExhibitionSceneKit.camera(0.0f, 1.50f, 4.6f, -90.0f, -4.0f);
    }

    private static Camera flowCamera() {
        return ExhibitionSceneKit.camera(0.0f, 1.55f, 4.2f, -90.0f, -4.0f);
    }

    private static Camera dissolveCamera() {
        return ExhibitionSceneKit.camera(0.0f, 1.50f, 4.6f, -90.0f, -4.0f);
    }

    /** Resets the deterministic station clock and clears stray effect lights on entry. */
    private static final class MeshFeedDriver implements ExhibitionStationDriver {
        private final MeshStationFeed feed;

        private MeshFeedDriver(MeshStationFeed feed) {
            this.feed = feed;
        }

        @Override
        public void onSelect(RenderPipeline pipeline, ExhibitionPhase phase) {
            feed.resetClock();
            pipeline.vfxLights(List.of());
        }

        @Override
        public ExhibitionVfxFeed vfxFeed() {
            return feed;
        }
    }

    /**
     * Records one mesh primitive with the station material into the production
     * HDR VFX composite pass.  Subclasses supply the frame pose and optional
     * dissolve progress; time is the deterministic accumulated station time.
     */
    private abstract static class MeshStationFeed implements ExhibitionVfxFeed {
        private final ExhibitionAssets assets;
        private final MeshData mesh;
        private final Vector4f color;
        private final long sequence;
        private final VfxVisualSet visuals;
        private float elapsedSeconds;

        private MeshStationFeed(ExhibitionAssets assets, MeshData mesh, VfxMaterial material,
                                Vector4f color, long sequence) {
            this.assets = assets;
            this.mesh = mesh;
            this.color = color;
            this.sequence = sequence;
            this.visuals = new VfxVisualSet(VfxMaterial.legacyAlpha(),
                    VfxMaterial.legacyAlpha(), VfxMaterial.legacyAlpha(), material);
        }

        final void resetClock() {
            elapsedSeconds = 0.0f;
        }

        @Override
        public final void record(CommandBuffer commands, Matrix4f projection, Matrix4f view,
                                 int sceneDepthTexture, int width, int height, int frameIndex,
                                 float deltaSeconds) {
            elapsedSeconds += deltaSeconds;
            Matrix4f model = model(elapsedSeconds);
            Vector3f center = view.transformPosition(model.getTranslation(new Vector3f()));
            EffectSnapshot.MeshInstance primitive = new EffectSnapshot.MeshInstance(sequence,
                    mesh, model, color, center.lengthSquared(), 1.0f);
            EffectSnapshot snapshot = new EffectSnapshot(List.of(), List.of(), List.of(),
                    List.of(primitive), List.of(primitive), visuals, effectTime(elapsedSeconds),
                    dissolveProgress(elapsedSeconds), List.of());
            assets.vfxRenderer().record(commands, snapshot, projection, view,
                    sceneDepthTexture, width, height);
        }

        protected abstract Matrix4f model(float seconds);

        protected float effectTime(float seconds) {
            return seconds;
        }

        protected float dissolveProgress(float seconds) {
            return 0.0f;
        }
    }
}
