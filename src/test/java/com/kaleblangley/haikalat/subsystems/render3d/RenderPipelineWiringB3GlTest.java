package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiIndirectPass;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiProbeVolume;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandPass;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSceneInput;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

/**
 * Real-GL pipeline path for DDGI (#44), VCT (#45) and hair strands (#46).
 *
 * <p>Each case proves the feature contributes inside its declared region while
 * disabled settings restore the byte-identical baseline, and that the late
 * single-sample composites never write the shared opaque depth. These cases are
 * guarded by {@code haikalat.glSmoke} and are reported as {@code NOT_EVALUATED}
 * until the Orchestrator runs the serialized GPU queue.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class RenderPipelineWiringB3GlTest {
    private static final int SIZE = 32;
    private static final int SHADOW_WIDTH = 192;
    private static final int SHADOW_HEIGHT = 144;
    /** Insets the receiver region so black background pixels never count as shadow. */
    private static final int SHADOW_REGION_MARGIN = 8;
    /** Receiver lit luminance is 0.63; this threshold separates it from shadow. */
    private static final float SHADOW_LUMINANCE = 0.3f;
    private static final int MINIMUM_SHADOW_PIXELS = 100;
    private static final int MINIMUM_CHANGED_PIXELS = 150;
    private static final double MINIMUM_CENTROID_SHIFT_PIXELS = 10.0;
    /** Lock-only move large enough to shift the wall shadow by about 35 pixels. */
    private static final float HAIR_SHIFT_X = 1.2f;

    @Test
    void ddgiIndirectContributesAndDisabledRestoresBaselineWithoutDepthWrites() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (var fixture = pipeline(window, "ddgi-wiring")) {
                    RenderPipeline pipeline = fixture.pipeline();
                    pipeline.build();
                    execute(pipeline);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                    // A zero-intensity enabled DDGI generation exposes the same
                    // shared single-sample depth while adding exactly zero indirect
                    // light. Its depth is the reference for the no-write proof.
                    pipeline.applyDdgi(ddgiZero());
                    execute(pipeline);
                    float[] referenceDepth = readSharedDepth(pipeline);

                    pipeline.applyDdgi(ddgi());
                    assertTrue(pipeline.graph().hasPass(DdgiProbeVolume.PASS_NAME));
                    assertTrue(pipeline.graph().hasPass(DdgiIndirectPass.PASS_NAME));
                    execute(pipeline);
                    float[] indirect = pipeline.captureLinearHdrRgbaFloat();
                    float[] indirectDepth = readSharedDepth(pipeline);

                    assertTrue(different(baseline, indirect),
                            "DDGI indirect light must modify the HDR scene colour");
                    assertArrayBitsEqual(referenceDepth, indirectDepth,
                            "DDGI must not write the shared opaque depth");

                    List<String> order = pipeline.graph().description().executionOrder();
                    assertTrue(order.indexOf(DdgiProbeVolume.PASS_NAME)
                            < order.indexOf(DdgiIndirectPass.PASS_NAME), order.toString());

                    pipeline.applyDdgi(DdgiSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(DdgiProbeVolume.PASS_NAME));
                    execute(pipeline);
                    float[] restored = pipeline.captureLinearHdrRgbaFloat();
                    assertArrayBitsEqual(baseline, restored,
                            "disabling DDGI must restore the byte-identical baseline");

                    // The resize candidate must rebuild the pass-owned indirect target.
                    pipeline.applyDdgi(ddgi());
                    pipeline.resize(SIZE + 8, SIZE + 4);
                    assertTrue(pipeline.graph().hasPass(DdgiIndirectPass.PASS_NAME));
                    assertEquals(SIZE + 8, pipeline.graph().width());
                    assertEquals(SIZE + 4, pipeline.graph().height());
                    // execute() targets the window extent, so the graph returns to
                    // SIZE and the resized candidate must remain usable.
                    execute(pipeline);
                    assertTrue(pipeline.graph().hasPass(DdgiIndirectPass.PASS_NAME));
                    pipeline.applyDdgi(DdgiSettings.disabled());
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("DDGI pipeline wiring");
        }
    }

    @Test
    void vctCompositeContributesAndDisabledRestoresBaselineWithoutDepthWrites() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (var fixture = pipeline(window, "vct-wiring")) {
                    RenderPipeline pipeline = fixture.pipeline();
                    pipeline.build();
                    execute(pipeline);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                    // Zero-intensity VCT keeps the same topology and shared depth
                    // but adds exactly zero cone radiance; use its depth as the
                    // reference for the no-write proof.
                    pipeline.applyVct(vctZero());
                    pipeline.vctSceneInput(vctSceneInput());
                    execute(pipeline);
                    float[] referenceDepth = readSharedDepth(pipeline);

                    pipeline.applyVct(vct());
                    pipeline.vctSceneInput(vctSceneInput());
                    assertTrue(pipeline.graph().hasPass(RenderPipeline.VCT_VOXELIZE_PASS));
                    assertTrue(pipeline.graph().hasPass(RenderPipeline.VCT_COMPOSITE_PASS));
                    execute(pipeline);
                    float[] composite = pipeline.captureLinearHdrRgbaFloat();
                    float[] compositeDepth = readSharedDepth(pipeline);

                    assertTrue(different(baseline, composite),
                            "voxel cone tracing must modify the HDR scene colour");
                    assertArrayBitsEqual(referenceDepth, compositeDepth,
                            "VCT must not write the shared opaque depth");

                    List<String> order = pipeline.graph().description().executionOrder();
                    assertTrue(order.indexOf(RenderPipeline.VCT_VOXELIZE_PASS)
                            < order.indexOf(RenderPipeline.VCT_COMPOSITE_PASS), order.toString());

                    pipeline.applyVct(VctSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(RenderPipeline.VCT_COMPOSITE_PASS));
                    execute(pipeline);
                    float[] restored = pipeline.captureLinearHdrRgbaFloat();
                    assertArrayBitsEqual(baseline, restored,
                            "disabling VCT must restore the byte-identical baseline");
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("VCT pipeline wiring");
        }
    }

    @Test
    void hairStrandsContributeInsideTheLockAndDisabledRestoreBaseline() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (var fixture = pipeline(window, "hair-wiring")) {
                    RenderPipeline pipeline = fixture.pipeline();
                    pipeline.build();
                    execute(pipeline);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                    // The lock sits in front of the receiver cube and is scaled to
                    // project well inside the viewport, so every border pixel must
                    // stay at the baseline. Shadow casting is off so this case
                    // measures only the forward strand region; the caster hook is
                    // exercised by the pass-level HairStrandGlTest shadow case.
                    pipeline.applyHairStrands(hairWithoutShadow());
                    pipeline.hairStrandModel(new Matrix4f().translation(0.0f, 0.5f, 1.6f)
                            .scale(0.8f));
                    assertTrue(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));
                    execute(pipeline);
                    float[] strands = pipeline.captureLinearHdrRgbaFloat();

                    int changed = countChangedPixels(baseline, strands);
                    assertTrue(changed > 0,
                            "hair strands must contribute inside the lock region");
                    int width = pipeline.graph().width();
                    int height = pipeline.graph().height();
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            if (x >= 2 && x < width - 2 && y >= 2 && y < height - 2) continue;
                            int index = (y * width + x) * 4;
                            assertSamePixel(baseline, strands, index,
                                    "hair must stay inside the declared lock region");
                        }
                    }

                    pipeline.applyHairStrands(HairStrandSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));
                    execute(pipeline);
                    float[] restored = pipeline.captureLinearHdrRgbaFloat();
                    assertArrayBitsEqual(baseline, restored,
                            "disabling hair strands must restore the byte-identical baseline");
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("hair pipeline wiring");
        }
    }

    /**
     * Regression for the R46/#46 independent-acceptance F5 finding.
     *
     * <p>The hair lock is a dynamic shadow caster outside the
     * scene/light/planner/camera cache key. A static directional tile must not
     * survive a lock move, so the required event sequence is executed on real
     * GL: pre-hair baseline, first miss after the lock exists, static reuse,
     * a lock-only move that forces a redraw, stable reuse, and the disabled
     * baseline again.</p>
     *
     * <p>The receiver is a fixed, non-casting quad that samples the production
     * {@code DirectionalShadowMap} through the same
     * {@code uDirectionalLightSpace}/{@code uHasDirectionalShadow}/{@code uShadowBias}
     * frame uniforms the PBR forward path consumes. The lock itself is staged
     * above the camera frustum (the visible half-height at its plane is about
     * 1.7 world units while the root disc spans y=4.1..4.9), so the only pixels
     * that may change when the model matrix moves are the lock's projected
     * shadow on the receiver.</p>
     */
    @Test
    void movingOnlyTheHairLockInvalidatesTheStaticShadowCacheAndTheShadowFollows() {
        try (var window = window(SHADOW_WIDTH, SHADOW_HEIGHT)) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (var fixture = shadowFixture(window)) {
                    RenderPipeline pipeline = fixture.pipeline();
                    pipeline.build();
                    execute(pipeline);
                    int width = pipeline.graph().width();
                    int height = pipeline.graph().height();
                    float[] preHair = pipeline.captureLinearHdrRgbaFloat();
                    assertEquals(0, countShadowPixels(preHair, width, height),
                            "the receiver must be fully lit before the hair lock exists");

                    // Place the lock with its fixed model matrix, then enable it.
                    // The lock is outside the camera frustum; only its shadow is
                    // visible on the receiver.
                    pipeline.hairStrandModel(hairModel(0.0f));
                    pipeline.applyHairStrands(hairWithShadow());
                    assertTrue(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));

                    // Frame 1: the dynamic caster's first shadow render is a miss
                    // even though the scene/light/camera keys are unchanged.
                    execute(pipeline);
                    Render3dDiagnostics.ShadowSummary first =
                            pipeline.lastRender3dDiagnostics().shadows();
                    assertEquals(1, first.tilesRendered(),
                            "the first hair shadow render must be a cache miss");
                    assertEquals(0, first.tilesReused(),
                            "a miss must not report a reused tile");
                    float[] firstFrame = pipeline.captureLinearHdrRgbaFloat();
                    assertTrue(countShadowPixels(firstFrame, width, height)
                                    >= MINIMUM_SHADOW_PIXELS,
                            "the enabled hair lock must darken receiver pixels: "
                                    + countShadowPixels(firstFrame, width, height));

                    // Frame 2: static cache hit; no shadow redraw and no pixel
                    // change.
                    execute(pipeline);
                    Render3dDiagnostics.ShadowSummary reused =
                            pipeline.lastRender3dDiagnostics().shadows();
                    assertEquals(0, reused.tilesRendered(),
                            "an unchanged static tile must not be redrawn");
                    assertEquals(1, reused.tilesReused(),
                            "the second frame must reuse the cached directional tile");
                    float[] reusedFrame = pipeline.captureLinearHdrRgbaFloat();
                    assertArrayBitsEqual(firstFrame, reusedFrame,
                            "static reuse must not move or restage the shadow");

                    // Move only the hair. The scene/light/camera key is unchanged,
                    // so without ShadowCacheState.invalidateAll() this frame would
                    // remain a hit and the receiver would keep the old shadow.
                    pipeline.hairStrandModel(hairModel(HAIR_SHIFT_X));
                    execute(pipeline);
                    Render3dDiagnostics.ShadowSummary moved =
                            pipeline.lastRender3dDiagnostics().shadows();
                    assertEquals(1, moved.tilesRendered(),
                            "moving the hair lock must invalidate and redraw the tile");
                    assertEquals(0, moved.tilesReused(),
                            "the invalidation frame must not report a reused tile");
                    float[] movedFrame = pipeline.captureLinearHdrRgbaFloat();
                    int changed = countChangedPixelsInRegion(reusedFrame, movedFrame,
                            width, height);
                    assertTrue(changed >= MINIMUM_CHANGED_PIXELS,
                            "the receiver shadow region must change after the lock moves: "
                                    + changed);
                    assertTrue(countShadowPixels(movedFrame, width, height)
                                    >= MINIMUM_SHADOW_PIXELS,
                            "the moved lock must still cast a visible shadow");
                    double before = shadowCentroidX(reusedFrame, width, height);
                    double after = shadowCentroidX(movedFrame, width, height);
                    assertTrue(after - before >= MINIMUM_CENTROID_SHIFT_PIXELS,
                            "the shadow must follow the lock in +X: before=" + before
                                    + " after=" + after);

                    // Frame 4: stable reuse again with no further pixel churn.
                    execute(pipeline);
                    Render3dDiagnostics.ShadowSummary stable =
                            pipeline.lastRender3dDiagnostics().shadows();
                    assertEquals(0, stable.tilesRendered(),
                            "the moved tile must be reused once its key is stable");
                    assertEquals(1, stable.tilesReused(),
                            "frame 4 must reuse the redrawn directional tile");
                    float[] stableFrame = pipeline.captureLinearHdrRgbaFloat();
                    assertArrayBitsEqual(movedFrame, stableFrame,
                            "stable reuse after the move must not change pixels");

                    // Disabling the caster must also invalidate the borrowed
                    // static tile; otherwise the previous lock's shadow survives
                    // with no caster left in the scene.
                    pipeline.applyHairStrands(HairStrandSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(HairStrandPass.PASS_NAME),
                            "disabled hair must remove its forward pass");
                    execute(pipeline);
                    Render3dDiagnostics.ShadowSummary disabled =
                            pipeline.lastRender3dDiagnostics().shadows();
                    assertEquals(1, disabled.tilesRendered(),
                            "disabling the hair caster must invalidate the static tile");
                    float[] restored = pipeline.captureLinearHdrRgbaFloat();
                    assertEquals(0, countShadowPixels(restored, width, height),
                            "the disabled caster must leave no receiver shadow");
                    assertArrayBitsEqual(preHair, restored,
                            "disabling hair must restore the byte-identical pre-hair baseline");
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "the hair shadow fixture must release every tracked resource");
            }
            GlDebug.assertNoError("hair shadow-cache regression");
        }
    }

    private static Fixture pipeline(GlfwWindow window, String name) {
        MeshData data = BuiltinMeshData.coloredCube(name);
        ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
        Mesh mesh = Mesh.from(data);
        Material material = Material.builder(shader).build();
        Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 4.0f)));
        scene.add(SceneObject.fixed(mesh, material, new Matrix4f().scale(2.0f), true));
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.3f, -1.0f, -0.4f),
                new Vector3f(1.0f), 1.0f));
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build());
        return new Fixture(pipeline, material, shader, mesh);
    }

    /** Owns the GL resources used by the scene so resource tracking returns to zero. */
    private record Fixture(RenderPipeline pipeline, Material material, ShaderProgram shader,
                           Mesh mesh) implements AutoCloseable {
        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                pipeline.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            failure = closeOne(material, failure);
            failure = closeOne(shader, failure);
            failure = closeOne(mesh, failure);
            if (failure != null) throw failure;
        }

        private static RuntimeException closeOne(AutoCloseable resource,
                                                 RuntimeException failure) {
            try {
                resource.close();
            } catch (Exception closeFailure) {
                RuntimeException runtime = closeFailure instanceof RuntimeException existing
                        ? existing
                        : new IllegalStateException("failed to close B3 GL fixture", closeFailure);
                if (failure == null) return runtime;
                failure.addSuppressed(runtime);
            }
            return failure;
        }
    }

    private static void execute(RenderPipeline pipeline) {
        pipeline.execute(new GlRenderDevice(), 1.0f / 60.0f);
        GlDebug.assertNoError("pipeline feature frame");
    }

    private static float[] readSharedDepth(RenderPipeline pipeline) {
        var depth = pipeline.activeGenerationForTest().topology().sceneSurface()
                .single(SceneBufferChannel.DEPTH);
        assertNotNull(depth, "the feature topology must expose the shared single depth");
        int[] texture = new int[1];
        pipeline.graph().addPass("B3DepthProbe")
                .computeOnly()
                .dependsOn(pipeline.finalPassName())
                .execute((resources, commands) ->
                        texture[0] = resources.depthAttachment(depth.name()));
        pipeline.execute(new GlRenderDevice(), 1.0f / 60.0f);
        assertTrue(texture[0] > 0, "the shared depth texture must be readable");
        return readDepth(texture[0], pipeline.graph().width(), pipeline.graph().height());
    }

    private static DdgiSettings ddgi() {
        return DdgiSettings.builder()
                .enabled(true)
                .intensity(2.0f)
                .surfaceAlbedo(0.8f)
                .skyRadiance(0.1f, 0.1f, 0.1f)
                .gridOrigin(-3.0f, -3.0f, -3.0f)
                .spacingMetres(3.0f)
                .probes(2, 2, 2)
                .updateBudget(8)
                .hysteresis(0.0f)
                .maximumRayDistance(8.0f)
                .raySteps(16)
                .depthFaceResolution(8)
                .borderFadeMetres(0.5f)
                .maximumIrradiance(32.0f)
                .build();
    }

    /** Same active topology as {@link #ddgi()} but adds exactly zero light. */
    private static DdgiSettings ddgiZero() {
        return ddgi().toBuilder().intensity(0.0f).build();
    }

    private static VctSettings vct() {
        return VctSettings.enabled(32, 4.0f);
    }

    /** Same active topology as {@link #vct()} but adds exactly zero cone radiance. */
    private static VctSettings vctZero() {
        return vct().withIntensities(0.0f, 0.0f);
    }

    private static VctSceneInput vctSceneInput() {
        return VctSceneInput.builder(1L)
                .addBox(new Vector3f(-1.5f, -1.5f, -1.5f), new Vector3f(1.5f, 1.5f, 1.5f),
                        2.0f, 1.6f, 1.2f)
                .build();
    }

    private static HairStrandSettings hairWithoutShadow() {
        return HairStrandSettings.builder()
                .strandCount(64)
                .segmentsPerStrand(8)
                .strandLengthMetres(1.0f)
                .rootSpreadMetres(0.3f)
                .widthPixels(4.0f)
                .minimumWidthPixels(2.0f)
                .maximumWidthPixels(8.0f)
                .nearDistanceMetres(1.0f)
                .farDistanceMetres(20.0f)
                .transparency(HairStrandSettings.Transparency.SORTED_ALPHA)
                .castsShadow(false)
                .build();
    }

    /**
     * Receiver/light scene for the shadow-cache regression. The receiver is a
     * fixed wall that does not cast, so the only depth ever written into the
     * directional tile comes from the hair caster hook.
     */
    private static Fixture shadowFixture(GlfwWindow window) {
        ShaderProgram shader = ShaderProgram.fromSources(SHADOW_VERTEX_SOURCE,
                SHADOW_FRAGMENT_SOURCE);
        Mesh mesh = Mesh.from(BuiltinMeshData.coloredQuad("hair-shadow-receiver"));
        Material material = Material.builder(shader).build();
        Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 6.0f)));
        scene.add(SceneObject.fixed(mesh, material,
                new Matrix4f().scale(8.0f, 5.6f, 1.0f), false));
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(0.0f, -1.0f, -0.45f),
                new Vector3f(1.0f), 1.0f));
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build())
                .localShadows(LocalShadowPipelineSettings.balanced());
        return new Fixture(pipeline, material, shader, mesh);
    }

    /** A shadow-casting, high-detail horizontal lock that is wider than a texel. */
    private static HairStrandSettings hairWithShadow() {
        return HairStrandSettings.builder()
                .strandCount(64)
                .segmentsPerStrand(8)
                .strandLengthMetres(2.0f)
                .rootSpreadMetres(0.4f)
                .curlMetres(0.0f)
                .strandRadiusMetres(0.03f)
                .flowDirection(new Vector3f(1.0f, 0.0f, 0.0f))
                .widthPixels(6.0f)
                .minimumWidthPixels(2.0f)
                .maximumWidthPixels(10.0f)
                .nearDistanceMetres(20.0f)
                .farDistanceMetres(40.0f)
                .lodMinimumPixels(1.0f)
                .lodWidthScale(1.0f)
                .alpha(1.0f)
                .alphaCutoff(0.35f)
                .transparency(HairStrandSettings.Transparency.CUTOUT)
                .rootColor(new Vector3f(1.0f, 1.0f, 1.0f))
                .tipColor(new Vector3f(1.0f, 1.0f, 1.0f))
                .diffuseStrength(1.0f)
                .specularStrength(0.0f)
                .colorVariation(0.0f)
                .castsShadow(true)
                .seed(4242L)
                .build();
    }

    /**
     * Places the local x=[0,2] lock in front of the receiver wall and above the
     * camera frustum, so only its shadow can reach the visible receiver.
     */
    private static Matrix4f hairModel(float shiftX) {
        return new Matrix4f().translate(-1.0f + shiftX, 4.5f, 2.0f);
    }

    private static GlfwWindow window() {
        return window(SIZE, SIZE);
    }

    private static GlfwWindow window(int width, int height) {
        return new GlfwWindow.Builder().dimensions(width, height).visible(false)
                .title("Render3D wiring B3").build();
    }

    private static float[] readDepth(int texture, int width, int height) {
        int framebuffer = org.lwjgl.opengl.GL30.glGenFramebuffers();
        FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height);
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                    GL_TEXTURE_2D, texture, 0);
            org.lwjgl.opengl.GL11.glDrawBuffer(org.lwjgl.opengl.GL11.GL_NONE);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_NONE);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, width, height, GL_DEPTH_COMPONENT,
                    GL_FLOAT, buffer);
            float[] result = new float[width * height];
            buffer.get(result);
            return result;
        } finally {
            MemoryUtil.memFree(buffer);
            org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer);
        }
    }

    private static void assertArrayBitsEqual(float[] expected, float[] actual, String message) {
        assertEquals(expected.length, actual.length, message);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(Float.floatToIntBits(expected[index]),
                    Float.floatToIntBits(actual[index]), message + " at index " + index);
        }
    }

    private static void assertSamePixel(float[] expected, float[] actual, int index,
                                        String message) {
        for (int component = 0; component < 4; component++) {
            assertEquals(Float.floatToIntBits(expected[index + component]),
                    Float.floatToIntBits(actual[index + component]),
                    message + " at pixel index " + (index / 4));
        }
    }

    /**
     * Counts shadowed receiver pixels. The inset keeps black background pixels
     * out even when a future fixture no longer fills the viewport.
     */
    private static int countShadowPixels(float[] pixels, int width, int height) {
        int count = 0;
        for (int y = SHADOW_REGION_MARGIN; y < height - SHADOW_REGION_MARGIN; y++) {
            for (int x = SHADOW_REGION_MARGIN; x < width - SHADOW_REGION_MARGIN; x++) {
                if (luminance(pixels, (y * width + x) * 4) < SHADOW_LUMINANCE) count++;
            }
        }
        return count;
    }

    /** Mean screen x of the receiver-shadow pixels; NaN when none are visible. */
    private static double shadowCentroidX(float[] pixels, int width, int height) {
        double sum = 0.0;
        int count = 0;
        for (int y = SHADOW_REGION_MARGIN; y < height - SHADOW_REGION_MARGIN; y++) {
            for (int x = SHADOW_REGION_MARGIN; x < width - SHADOW_REGION_MARGIN; x++) {
                if (luminance(pixels, (y * width + x) * 4) < SHADOW_LUMINANCE) {
                    sum += x;
                    count++;
                }
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static int countChangedPixelsInRegion(float[] left, float[] right,
                                                  int width, int height) {
        int changed = 0;
        for (int y = SHADOW_REGION_MARGIN; y < height - SHADOW_REGION_MARGIN; y++) {
            for (int x = SHADOW_REGION_MARGIN; x < width - SHADOW_REGION_MARGIN; x++) {
                if (!samePixel(left, right, (y * width + x) * 4)) changed++;
            }
        }
        return changed;
    }

    private static double luminance(float[] pixels, int index) {
        return 0.2126 * pixels[index] + 0.7152 * pixels[index + 1]
                + 0.0722 * pixels[index + 2];
    }

    private static boolean different(float[] left, float[] right) {
        return countChangedPixels(left, right) > 0;
    }

    private static int countChangedPixels(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("pixel arrays must have the same length");
        }
        int changed = 0;
        for (int index = 0; index < left.length; index += 4) {
            if (!samePixel(left, right, index)) changed++;
        }
        return changed;
    }

    private static boolean samePixel(float[] left, float[] right, int index) {
        for (int component = 0; component < 4; component++) {
            if (Float.floatToIntBits(left[index + component])
                    != Float.floatToIntBits(right[index + component])) return false;
        }
        return true;
    }

    /**
     * Test-owned receiver shader. It consumes the production directional
     * shadow frame uniforms exactly like the PBR forward material, but reduces
     * the visible result to "lit vs shadowed" so a shadow move is a
     * deterministic luminance change instead of a lighting-model difference.
     */
    private static final String SHADOW_VERTEX_SOURCE = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            out vec3 vWorldPosition;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                vWorldPosition = world.xyz;
                gl_Position = uProjection * uView * world;
            }
            """;

    private static final String SHADOW_FRAGMENT_SOURCE = """
            #version 330 core
            in vec3 vWorldPosition;
            layout (location = 0) out vec4 FragColor;
            uniform sampler2D uShadowMap;
            uniform int uHasDirectionalShadow;
            uniform mat4 uDirectionalLightSpace;
            uniform float uShadowBias;
            void main() {
                vec3 lit = vec3(0.8, 0.6, 0.4);
                vec3 shadowed = vec3(0.16, 0.12, 0.08);
                float visibility = 1.0;
                if (uHasDirectionalShadow != 0) {
                    vec4 lightClip = uDirectionalLightSpace * vec4(vWorldPosition, 1.0);
                    vec3 projected = lightClip.xyz / lightClip.w * 0.5 + 0.5;
                    if (all(greaterThan(projected, vec3(0.0)))
                            && all(lessThan(projected, vec3(1.0)))) {
                        vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
                        float occluded = 0.0;
                        for (int x = -1; x <= 1; ++x) {
                            for (int y = -1; y <= 1; ++y) {
                                float stored = texture(uShadowMap,
                                        projected.xy + vec2(x, y) * texel).r;
                                occluded += projected.z - uShadowBias > stored ? 1.0 : 0.0;
                            }
                        }
                        visibility = 1.0 - occluded / 9.0;
                    }
                }
                FragColor = vec4(mix(shadowed, lit, visibility), 1.0);
            }
            """;

    private static final String VERTEX_SOURCE = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            void main() {
                gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0);
            }
            """;

    private static final String FRAGMENT_SOURCE = """
            #version 330 core
            out vec4 FragColor;
            void main() {
                FragColor = vec4(0.8, 0.6, 0.4, 1.0);
            }
            """;
}
