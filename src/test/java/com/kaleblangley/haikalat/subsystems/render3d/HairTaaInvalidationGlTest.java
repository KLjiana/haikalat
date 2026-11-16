package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandPass;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-GL regression for the hair-strand + TAA history boundary.
 *
 * <p>The hair lock is a dynamic forward overlay that writes no shared-surface
 * velocity and (for {@code SORTED_ALPHA}) no depth. A temporal resolve would
 * therefore accept the previous strand image as valid history after the lock
 * moves, leaving the old hair as a ghost. The pipeline must invalidate the
 * post-process history exactly on a real participation/placement change and
 * must not reset it on stable frames.</p>
 *
 * <p>The fixture is a full-screen receiver with a deterministic
 * {@code gl_FragCoord} checker pattern: without hair the pattern is identical on
 * every static frame, so a fresh TAA resolve is byte-identical to the pre-hair
 * baseline. The dense straight lock covers the probe patch before the move and
 * is shifted far enough to vacate it, so "vacated receiver pixels reproduce the
 * baseline exactly" is only true when the stale history was really dropped.
 * The invalidation reason is also asserted through the production
 * diagnostics.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class HairTaaInvalidationGlTest {
    private static final int HINT_WIDTH = 192;
    private static final int HINT_HEIGHT = 144;
    /** Lock-only move large enough to vacate the probe patch completely. */
    private static final float HAIR_SHIFT_X = 1.0f;
    private static final int PROBE_RADIUS = 2;
    /**
     * Static frames needed for the jitter-shifted history to converge; the
     * 0.9 history weight leaves about 8% of a fresh frame's error after this.
     */
    private static final int CONVERGENCE_FRAMES = 24;

    @Test
    void movingTheHairLockUnderTaaInvalidatesHistoryOnceAndLeavesNoGhost() {
        try (GlfwWindow window = window()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (Fixture fixture = fixture(window)) {
                    RenderPipeline pipeline = fixture.pipeline();
                    pipeline.build();
                    int width = pipeline.graph().width();
                    int height = pipeline.graph().height();
                    // Converge the static TAA history so the pre-hair image is
                    // the stable receiver reference.
                    for (int frame = 0; frame < CONVERGENCE_FRAMES; frame++) {
                        execute(pipeline, device);
                    }
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();
                    var history = pipeline.activeGenerationForTest().postProcess()
                            .taaHistoryForTest();
                    assertNotNull(history, "TAA must own a temporal history");
                    assertTrue(history.valid(), "the static baseline must commit TAA history");
                    assertEquals("", pipeline.activeGenerationForTest().postProcess()
                            .completedTaaInvalidationReason());

                    // Enabling the lock is a participation change: the first
                    // frame must resolve only the current image.
                    pipeline.hairStrandModel(hairModel(0.0f));
                    pipeline.applyHairStrands(hair());
                    assertTrue(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));
                    assertFalse(history.valid(),
                            "enabling hair must invalidate the TAA history");
                    execute(pipeline, device);
                    assertEquals("TAA_HAIR_STRANDS_SETTINGS",
                            pipeline.activeGenerationForTest().postProcess()
                                    .completedTaaInvalidationReason());
                    float[] hairFrame = pipeline.captureLinearHdrRgbaFloat();
                    assertFrameEqualsCurrentScene(pipeline, hairFrame,
                            "enabling hair");
                    assertTrue(different(baseline, hairFrame),
                            "the enabled lock must change forward pixels");
                    int[] probe = hairProbe(baseline, hairFrame, width, height);

                    // Stable hair frames resume history: no repeat invalidation.
                    for (int frame = 0; frame < CONVERGENCE_FRAMES; frame++) {
                        execute(pipeline, device);
                    }
                    assertTrue(history.valid(), "stable hair frames must commit history");
                    assertEquals("", pipeline.activeGenerationForTest().postProcess()
                            .completedTaaInvalidationReason());
                    float[] stableHair = pipeline.captureLinearHdrRgbaFloat();
                    assertTrue(patchMeanAbsDiff(baseline, stableHair, probe, width) > 0.1f,
                            "the probe patch must be covered by the lock before the move");

                    // Moving only the lock must drop the history immediately and
                    // must not leave the previous placement as a ghost.
                    pipeline.hairStrandModel(hairModel(HAIR_SHIFT_X));
                    assertFalse(history.valid(),
                            "moving the lock must invalidate the TAA history");
                    execute(pipeline, device);
                    assertEquals("TAA_HAIR_STRAND_MODEL",
                            pipeline.activeGenerationForTest().postProcess()
                                    .completedTaaInvalidationReason());
                    assertTrue(pipeline.lastRender3dDiagnostics().invalidationReasons()
                                    .contains("TAA_HAIR_STRAND_MODEL"),
                            pipeline.lastRender3dDiagnostics().invalidationReasons().toString());
                    float[] moved = pipeline.captureLinearHdrRgbaFloat();
                    assertFrameEqualsCurrentScene(pipeline, moved, "moving the lock");
                    assertTrue(patchMeanAbsDiff(stableHair, moved, probe, width) > 0.1f,
                            "the probe patch must be vacated by the lock move");

                    // The next unchanged frames accept the new history and must
                    // converge back to the pre-hair receiver, not to a ghost.
                    for (int frame = 0; frame < CONVERGENCE_FRAMES; frame++) {
                        execute(pipeline, device);
                    }
                    assertTrue(history.valid(), "the moved placement must commit history");
                    assertEquals("", pipeline.activeGenerationForTest().postProcess()
                            .completedTaaInvalidationReason());
                    float[] resumed = pipeline.captureLinearHdrRgbaFloat();
                    assertPatchWithin(baseline, resumed, probe, width, 0.05f,
                            "a stable frame after the move must resume history without a ghost");
                    int[] vacated = vacatedPixels(baseline, stableHair, resumed, width);
                    assertTrue(vacated[0] > 50,
                            "the lock move must vacate a measurable region, was " + vacated[0]);
                    assertEquals(0, vacated[1],
                            "every vacated receiver pixel must converge back to the baseline "
                                    + "luminance, but " + vacated[1] + " retained a ghost");

                    // Repeating the identical matrix must not reset anything.
                    pipeline.hairStrandModel(hairModel(HAIR_SHIFT_X));
                    assertTrue(history.valid(),
                            "an identical lock placement must keep the history");
                    execute(pipeline, device);
                    assertEquals("", pipeline.activeGenerationForTest().postProcess()
                            .completedTaaInvalidationReason(),
                            "an identical lock placement must not invalidate history");

                    // The unified feature request reaches the same settings path.
                    assertEquals(pipeline.hairStrands(), pipeline.features().effective()
                            .hairStrands());
                    assertTrue(pipeline.features().effective().hairStrands().active(),
                            "the applied hair snapshot must be observable");
                    pipeline.features().submit(pipeline.features().effective().toBuilder()
                            .hairStrands(HairStrandSettings.disabled()).build());
                    execute(pipeline, device);
                    assertEquals("TAA_HAIR_STRANDS_SETTINGS",
                            pipeline.activeGenerationForTest().postProcess()
                                    .completedTaaInvalidationReason(),
                            "the feature-request frame must invalidate history when hair "
                                    + "participation changes");
                    float[] disabled = pipeline.captureLinearHdrRgbaFloat();
                    assertFalse(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));
                    assertFrameEqualsCurrentScene(pipeline, disabled,
                            "disabling hair through the feature request");
                    for (int frame = 0; frame < CONVERGENCE_FRAMES; frame++) {
                        execute(pipeline, device);
                    }
                    assertPatchWithin(baseline, pipeline.captureLinearHdrRgbaFloat(), probe,
                            width, 0.05f,
                            "disabling the lock must restore the pre-hair receiver");
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "the hair + TAA fixture must release every tracked resource");
            }
            GlDebug.assertNoError("hair + TAA history invalidation");
        }
    }

    private static void execute(RenderPipeline pipeline, GlRenderDevice device) {
        pipeline.execute(device, 1.0f / 60.0f);
        GlDebug.assertNoError("hair + TAA pipeline frame");
    }

    /**
     * Picks the leftmost fully covered near-black patch of the lock with bright
     * receiver cells inside it, so the +X move vacates the whole patch and a
     * retained dark history would be detectable.
     */
    private static int[] hairProbe(float[] baseline, float[] hair, int width, int height) {
        int bestX = -1;
        int bestY = -1;
        for (int y = PROBE_RADIUS; y < height - PROBE_RADIUS; y++) {
            for (int x = PROBE_RADIUS; x < width - PROBE_RADIUS; x++) {
                if (bestX >= 0 && x >= bestX) break;
                if (!patchCovered(baseline, hair, x, y, width)) continue;
                bestX = x;
                bestY = y;
                break;
            }
        }
        assertTrue(bestX >= 0,
                "the hair lock must cover a fully opaque patch with bright receiver cells");
        return new int[]{bestX, bestY};
    }

    private static boolean patchCovered(float[] baseline, float[] hair, int centerX,
                                        int centerY, int width) {
        int bright = 0;
        for (int y = centerY - PROBE_RADIUS; y <= centerY + PROBE_RADIUS; y++) {
            for (int x = centerX - PROBE_RADIUS; x <= centerX + PROBE_RADIUS; x++) {
                int index = (y * width + x) * 4;
                if (luminance(hair, index) > 0.2) return false;
                if (luminance(baseline, index) > 0.35) bright++;
            }
        }
        return bright >= 8;
    }

    private static float patchMeanAbsDiff(float[] left, float[] right, int[] probe,
                                          int width) {
        float total = 0.0f;
        int count = 0;
        for (int y = probe[1] - PROBE_RADIUS; y <= probe[1] + PROBE_RADIUS; y++) {
            for (int x = probe[0] - PROBE_RADIUS; x <= probe[0] + PROBE_RADIUS; x++) {
                int index = (y * width + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    total += Math.abs(left[index + channel] - right[index + channel]);
                    count++;
                }
            }
        }
        return total / count;
    }

    private static void assertPatchWithin(float[] expected, float[] actual, int[] probe,
                                          int width, float tolerance, String message) {
        float difference = patchMeanAbsDiff(expected, actual, probe, width);
        assertTrue(difference <= tolerance, message + ": mean |delta|=" + difference
                + " > " + tolerance);
    }

    /**
     * A frame whose history was invalidated must resolve to exactly the current
     * scene colour: any accepted history contributes bytes here.
     */
    private static void assertFrameEqualsCurrentScene(RenderPipeline pipeline,
                                                      float[] resolved, String label) {
        float[] current = pipeline.graph().readColorAttachmentRgbaFloat(
                PostProcessTargets.SCENE_COLOR);
        assertEquals(current.length, resolved.length, label + ": HDR extent");
        for (int index = 0; index < current.length; index++) {
            assertEquals(Float.floatToIntBits(current[index]),
                    Float.floatToIntBits(resolved[index]),
                    label + ": the fresh resolve must equal the current scene colour at "
                            + (index / 4));
        }
    }

    /**
     * Counts bright receiver pixels that the lock covered before the move and
     * that the settled, converged frame should show again. A ghost keeps such a
     * pixel dark, so only a real history reset can bring it back.
     *
     * @return {@code [vacatedPixels, stillDifferentPixels]}
     */
    private static int[] vacatedPixels(float[] baseline, float[] covered,
                                       float[] settled, int width) {
        int vacated = 0;
        int changed = 0;
        for (int index = 0; index < baseline.length; index += 4) {
            double baselineLuminance = luminance(baseline, index);
            if (baselineLuminance < 0.3) continue;
            if (luminance(covered, index) >= baselineLuminance * 0.5) continue;
            vacated++;
            if (Math.abs(luminance(settled, index) - baselineLuminance) > 0.1) changed++;
        }
        return new int[]{vacated, changed};
    }

    private static boolean different(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("pixel arrays must have the same length");
        }
        for (int index = 0; index < left.length; index += 4) {
            if (!samePixel(left, right, index)) return true;
        }
        return false;
    }

    private static boolean samePixel(float[] left, float[] right, int index) {
        for (int component = 0; component < 4; component++) {
            if (Float.floatToIntBits(left[index + component])
                    != Float.floatToIntBits(right[index + component])) return false;
        }
        return true;
    }

    private static double luminance(float[] pixels, int index) {
        return 0.2126 * pixels[index] + 0.7152 * pixels[index + 1]
                + 0.0722 * pixels[index + 2];
    }

    private static Fixture fixture(GlfwWindow window) {
        ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
        Mesh mesh = Mesh.from(BuiltinMeshData.coloredQuad("hair-taa-pattern-receiver"));
        Material material = Material.builder(shader).build();
        Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 4.0f)));
        // The receiver is larger than the frustum at z=0, so the pattern covers
        // every pixel and no geometry edge can move under TAA jitter.
        scene.add(SceneObject.fixed(mesh, material,
                new Matrix4f().scale(8.0f, 6.0f, 1.0f), false));
        scene.addLight(SceneLight.directional(new Vector3f(-0.2f, -1.0f, -0.35f),
                new Vector3f(1.0f), 1.0f));
        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                RenderSettings.builder()
                        .antiAliasingMode(AntiAliasingMode.TAA)
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
                        : new IllegalStateException("failed to close hair + TAA fixture",
                        closeFailure);
                if (failure == null) return runtime;
                failure.addSuppressed(runtime);
            }
            return failure;
        }
    }

    /**
     * A dense, straight, almost black lock. {@code curl=0} keeps the silhouette
     * deterministic and {@code SORTED_ALPHA} keeps the lock out of the shared
     * depth/velocity surface, which is exactly the ghosting case.
     */
    private static HairStrandSettings hair() {
        return HairStrandSettings.builder()
                .strandCount(64)
                .segmentsPerStrand(8)
                .strandLengthMetres(1.0f)
                .rootSpreadMetres(0.3f)
                .curlMetres(0.0f)
                .strandRadiusMetres(0.01f)
                .flowDirection(new Vector3f(0.0f, -1.0f, 0.0f))
                .widthPixels(4.0f)
                .minimumWidthPixels(2.0f)
                .maximumWidthPixels(8.0f)
                .nearDistanceMetres(1.0f)
                .farDistanceMetres(20.0f)
                .lodMinimumPixels(1.0f)
                .lodWidthScale(1.0f)
                .alpha(1.0f)
                .alphaCutoff(0.35f)
                .transparency(HairStrandSettings.Transparency.SORTED_ALPHA)
                .rootColor(new Vector3f(0.01f, 0.008f, 0.006f))
                .tipColor(new Vector3f(0.01f, 0.008f, 0.006f))
                .diffuseStrength(0.0f)
                .specularStrength(0.0f)
                .colorVariation(0.0f)
                .castsShadow(false)
                .seed(91L)
                .build();
    }

    /** Places the lock in front of the receiver; only the placement changes. */
    private static Matrix4f hairModel(float shiftX) {
        return new Matrix4f().translate(shiftX, 0.5f, 1.6f).scale(0.8f);
    }

    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(HINT_WIDTH, HINT_HEIGHT).visible(false)
                .title("Hair + TAA invalidation").build();
    }

    private static final String VERTEX_SOURCE = """
            #version 460 core
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

    /**
     * Screen-space checker: identical on every static frame and wide enough
     * that the TAA neighbourhood clamp cannot hide a stale dark strand history.
     */
    private static final String FRAGMENT_SOURCE = """
            #version 460 core
            out vec4 FragColor;
            void main() {
                float checker = mod(floor(gl_FragCoord.x * 0.5)
                        + floor(gl_FragCoord.y * 0.5), 2.0);
                FragColor = vec4(mix(vec3(0.06, 0.07, 0.08), vec3(0.85, 0.80, 0.70),
                        checker), 1.0);
            }
            """;
}
