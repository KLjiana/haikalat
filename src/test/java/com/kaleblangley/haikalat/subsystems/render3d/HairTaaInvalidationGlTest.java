package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandPass;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-GL regression for the hair-strand + TAA history boundary.
 *
 * <p>The hair lock is a dynamic forward overlay. The forward pass writes no
 * shared-surface velocity (the dedicated velocity pass is a separate later
 * pass) and, for {@code SORTED_ALPHA}, no depth. Without an explicit reset a
 * temporal resolve would therefore accept the previous strand image as valid
 * history after the lock moves, leaving the old hair as a ghost. The pipeline
 * must invalidate the post-process history exactly on a real
 * participation/placement change and must not reset it on stable frames; that
 * policy is unchanged by the velocity channel, whose own boundary is pinned by
 * the second case below.</p>
 *
 * <p>The fixture is a full-screen receiver with a deterministic
 * {@code gl_FragCoord} checker pattern: without hair the pattern is identical on
 * every static frame, so a fresh TAA resolve is byte-identical to the pre-hair
 * baseline. The dense straight lock covers the probe patch before the move and
 * is shifted far enough to vacate it, so "vacated receiver pixels reproduce the
 * baseline exactly" is only true when the stale history was really dropped.
 * The invalidation reason is also asserted through the production
 * diagnostics.</p>
 *
 * <p>The velocity case uses the same fixture and reads
 * {@code graph().readColorAttachmentRgbaFloat(SCENE_VELOCITY)}: a static lock
 * and receiver must leave the attachment at exactly zero, a lock move must
 * write the previous-to-current vector on the new placement, receiver pixels
 * outside both placements must keep the surface velocity, and the same
 * {@code HAIR_STRAND_MODEL} reset must still fire. It uses {@code CUTOUT} so
 * the velocity pass's LEQUAL comparison is exercised against hair depth the
 * forward pass itself wrote.</p>
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
    /** Scene camera position of the shared fixture; the receiver plane is z=0. */
    private static final double CAMERA_EYE_Z = 4.0;
    /**
     * A lock whose strands point almost straight at the camera. The ribbon is
     * still several pixels wide and visible, but its camera-facing plane normal
     * is nearly perpendicular to the view direction (nDotV well under SSR's
     * 0.05 backface gate).
     */
    private static final Vector3f VIEW_ALIGNED_FLOW =
            new Vector3f(0.0f, -0.3569f, -0.9341f);
    private static final Matrix4f VIEW_ALIGNED_MODEL =
            new Matrix4f().translate(0.0f, -0.6f, 2.6f);

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

    /**
     * The velocity channel boundary: hair + TAA must borrow {@code sceneVelocity}
     * and publish zero while the lock is static, a real previous-to-current
     * vector on a lock move, and must not invent motion on receiver pixels the
     * lock never covers. The existing {@code HAIR_STRAND_MODEL} history reset is
     * retained and still fires on the same move.
     */
    @Test
    void hairVelocityIsZeroWhenStaticNonZeroOnAMoveAndTheModelResetStillFires() {
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
                    for (int frame = 0; frame < CONVERGENCE_FRAMES; frame++) {
                        execute(pipeline, device);
                    }
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                    pipeline.hairStrandModel(hairModel(0.0f));
                    // CUTOUT writes the shared raster depth, so this case also
                    // proves the velocity pass's LEQUAL comparison can match the
                    // forward pass's own depth instead of requiring GL_LESS.
                    pipeline.applyHairStrands(hair(HairStrandSettings.Transparency.CUTOUT));
                    assertTrue(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));
                    assertTrue(pipeline.graph().hasPass(HairStrandPass.VELOCITY_PASS_NAME),
                            "hair + TAA must declare the borrowed velocity pass");
                    var order = pipeline.graph().description().executionOrder();
                    assertTrue(order.indexOf(HairStrandPass.PASS_NAME)
                                    < order.indexOf(HairStrandPass.VELOCITY_PASS_NAME),
                            "the velocity draw must follow the forward transparent half: "
                                    + order);
                    assertTrue(order.indexOf(HairStrandPass.VELOCITY_PASS_NAME)
                                    < order.indexOf(PostProcessTargets.TAA_PASS),
                            "the velocity draw must complete before TAA samples sceneVelocity: "
                                    + order);
                    // The first hair frame has no committed previous state and
                    // must not write a fabricated zero; later frames commit.
                    for (int frame = 0; frame < CONVERGENCE_FRAMES; frame++) {
                        execute(pipeline, device);
                    }
                    float[] stableHair = pipeline.captureLinearHdrRgbaFloat();
                    float[] staticVelocity = pipeline.graph().readColorAttachmentRgbaFloat(
                            PostProcessTargets.SCENE_VELOCITY);
                    boolean[] staticMask = hairMask(baseline, stableHair);                    assertTrue(countMask(staticMask) > 200,
                            "the lock must cover a measurable region, was "
                                    + countMask(staticMask));
                    for (int pixel = 0; pixel < width * height; pixel++) {
                        assertEquals(0.0f, Math.abs(staticVelocity[pixel * 4]), 0.0f,
                                "a static lock and receiver must write zero u at " + pixel);
                        assertEquals(0.0f, Math.abs(staticVelocity[pixel * 4 + 1]), 0.0f,
                                "a static lock and receiver must write zero v at " + pixel);
                    }

                    // The model reset policy is unchanged and still fires.
                    pipeline.hairStrandModel(hairModel(HAIR_SHIFT_X));
                    assertFalse(pipeline.activeGenerationForTest().postProcess()
                            .taaHistoryForTest().valid(),
                            "moving the lock must still invalidate the TAA history");
                    execute(pipeline, device);
                    assertEquals("TAA_HAIR_STRAND_MODEL",
                            pipeline.activeGenerationForTest().postProcess()
                                    .completedTaaInvalidationReason());
                    assertTrue(pipeline.lastRender3dDiagnostics().invalidationReasons()
                                    .contains("TAA_HAIR_STRAND_MODEL"),
                            pipeline.lastRender3dDiagnostics().invalidationReasons().toString());

                    float[] moved = pipeline.captureLinearHdrRgbaFloat();
                    float[] movedVelocity = pipeline.graph().readColorAttachmentRgbaFloat(
                            PostProcessTargets.SCENE_VELOCITY);
                    boolean[] movedMask = hairMask(baseline, moved);
                    assertTrue(countMask(movedMask) > 200,
                            "the moved lock must cover a measurable region, was "
                                    + countMask(movedMask));
                    int nonzeroOnMovedHair = 0;
                    int nonzeroOutsideLocks = 0;
                    StringBuilder outsideSamples = new StringBuilder();
                    for (int pixel = 0; pixel < width * height; pixel++) {
                        float u = movedVelocity[pixel * 4];
                        float v = movedVelocity[pixel * 4 + 1];
                        boolean nonzero = Math.abs(u) > 1.0e-3f || Math.abs(v) > 1.0e-3f;
                        if (!nonzero) continue;
                        if (movedMask[pixel]) nonzeroOnMovedHair++;
                        if (!staticMask[pixel] && !movedMask[pixel]) {
                            nonzeroOutsideLocks++;
                            if (outsideSamples.length() < 1200) {
                                outsideSamples.append('[').append(pixel % width).append(',')
                                        .append(pixel / width).append("] u=").append(u)
                                        .append(" v=").append(v)
                                        .append(" base=").append(luminance(baseline, pixel * 4))
                                        .append(" static=").append(luminance(stableHair, pixel * 4))
                                        .append(" moved=").append(luminance(moved, pixel * 4))
                                        .append(' ');
                            }
                        }
                    }
                    assertTrue(nonzeroOnMovedHair > 100,
                            "the lock move must write real motion on the new placement, was "
                                    + nonzeroOnMovedHair);
                    assertEquals(0, nonzeroOutsideLocks,
                            "receiver pixels outside both placements must keep the "
                                    + "surface velocity, but " + nonzeroOutsideLocks
                                    + " invented motion: " + outsideSamples);
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "the hair velocity + TAA fixture must release every tracked resource");
            }
            GlDebug.assertNoError("hair velocity + TAA");
        }
    }

    /**
     * The normal channel boundary: hair + a real normal consumer (screen-space
     * subsurface diffusion requests the shared normal) must declare the borrowed
     * normal pass between the forward hair pass and TAA, alias {@code
     * sceneNormal} at the surface producer's slot, write the analytic ribbon
     * normal on hair pixels, leave receiver pixels byte-identical, and feed a
     * normal whose decoded {@code nDotV} flips SSR's documented backface gate
     * while the receiver normal stays eligible.
     */
    @Test
    void hairNormalFeedsTheSharedSurfaceAndChangesTheSsrBackfaceGate() {
        try (GlfwWindow window = window()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (Fixture fixture = fixture(window, normalConsumingEffects())) {
                    RenderPipeline pipeline = fixture.pipeline();
                    pipeline.build();
                    int width = pipeline.graph().width();
                    int height = pipeline.graph().height();
                    for (int frame = 0; frame < 4; frame++) {
                        execute(pipeline, device);
                    }
                    float[] baseline = normals(pipeline);
                    assertTrue(pipeline.graph().hasPass(PostProcessTargets.SCENE_SURFACE_PASS));
                    assertFalse(pipeline.graph().hasPass(HairStrandPass.NORMAL_PASS_NAME),
                            "hair disabled must not declare a normal pass");

                    pipeline.hairStrandModel(VIEW_ALIGNED_MODEL);
                    pipeline.applyHairStrands(viewAlignedHair());
                    assertTrue(pipeline.graph().hasPass(HairStrandPass.NORMAL_PASS_NAME),
                            "hair + a normal consumer must declare the borrowed normal pass");
                    var layout = pipeline.activeGenerationForTest().topology().sceneSurface();
                    var normalAttachment = layout.raw(SceneBufferChannel.NORMAL);
                    assertNotNull(normalAttachment);
                    assertEquals(0, normalAttachment.colorSlot(),
                            "NORMAL is the first shared surface colour slot in this fixture");
                    assertTrue(pipeline.graph().matchesColorStorage(
                                    HairStrandPass.NORMAL_PASS_NAME,
                                    PostProcessTargets.SCENE_SURFACE_PASS,
                                    List.of(normalAttachment.colorSlot())),
                            "the normal pass must borrow sceneNormal instead of owning it");
                    var order = pipeline.graph().description().executionOrder();
                    assertTrue(order.indexOf(HairStrandPass.PASS_NAME)
                                    < order.indexOf(HairStrandPass.NORMAL_PASS_NAME),
                            "the normal draw must follow the forward hair pass: " + order);
                    assertTrue(order.indexOf(HairStrandPass.NORMAL_PASS_NAME)
                                    < order.indexOf(PostProcessTargets.TAA_PASS),
                            "the normal draw must complete before TAA/post sample sceneNormal: "
                                    + order);

                    execute(pipeline, device);
                    float[] hairNormals = normals(pipeline);
                    double[] expected = viewAlignedAnalyticNormal();
                    int hairPixels = 0;
                    int receiverPixels = 0;
                    int ineligibleHair = 0;
                    int firstHair = -1;
                    double receiverNDotV = Double.NaN;
                    for (int pixel = 0; pixel < width * height; pixel++) {
                        boolean changed = Float.floatToIntBits(baseline[pixel * 4])
                                != Float.floatToIntBits(hairNormals[pixel * 4])
                                || Float.floatToIntBits(baseline[pixel * 4 + 1])
                                != Float.floatToIntBits(hairNormals[pixel * 4 + 1]);
                        if (changed) {
                            if (firstHair < 0) {
                                firstHair = pixel;
                            }
                            hairPixels++;
                            double[] decoded = assertNormalEncoded(hairNormals, pixel, expected,
                                    "hair pixel " + pixel);
                            if (ssrNDotV(decoded) <= 0.05) {
                                ineligibleHair++;
                            }
                        } else {
                            receiverPixels++;
                            double[] decoded = octDecode(baseline[pixel * 4],
                                    baseline[pixel * 4 + 1]);
                            if (Double.isNaN(receiverNDotV)) {
                                receiverNDotV = ssrNDotV(decoded);
                            }
                            assertTrue(ssrNDotV(decoded) > 0.05,
                                    "a receiver pixel must stay SSR-eligible at " + pixel
                                            + " normal=" + java.util.Arrays.toString(decoded));
                        }
                    }
                    double[] firstHairNormal = octDecode(hairNormals[firstHair * 4],
                            hairNormals[firstHair * 4 + 1]);
                    System.out.println("[hair-normal] pipeline hairPixels=" + hairPixels
                            + " ineligibleHair=" + ineligibleHair + " receiverPixels="
                            + receiverPixels + " sampleEncoded=("
                            + hairNormals[firstHair * 4] + ", "
                            + hairNormals[firstHair * 4 + 1] + ") sampleNormal="
                            + java.util.Arrays.toString(firstHairNormal)
                            + " hairNDotV=" + ssrNDotV(firstHairNormal)
                            + " receiverNDotV=" + receiverNDotV
                            + " expected=" + java.util.Arrays.toString(expected));
                    assertTrue(hairPixels > 15,
                            "the view-aligned lock must cover a measurable region, was "
                                    + hairPixels);
                    assertEquals(hairPixels, ineligibleHair,
                            "every covered hair pixel must fall under SSR's 0.05 backface gate");
                    assertTrue(receiverPixels > width * height / 2,
                            "receiver pixels outside the lock must remain untouched, were "
                                    + receiverPixels);
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "the hair normal fixture must release every tracked resource");
            }
            GlDebug.assertNoError("hair normal channel");
        }
    }

    /**
     * Hair silhouette mask: a receiver pixel the lock darkened to at most 80%
     * of its baseline luminance. The root of a {@code SORTED_ALPHA} strand
     * blends at about 55% alpha, so a strict 50% threshold would miss the root
     * region and report its real velocity as invented receiver motion.
     */
    private static boolean[] hairMask(float[] baseline, float[] hair) {
        boolean[] mask = new boolean[baseline.length / 4];
        for (int pixel = 0; pixel < mask.length; pixel++) {
            double baselineLuminance = luminance(baseline, pixel * 4);
            double hairLuminance = luminance(hair, pixel * 4);
            mask[pixel] = baselineLuminance > 0.02
                    && hairLuminance < baselineLuminance * 0.8;
        }
        return mask;
    }

    private static int countMask(boolean[] mask) {
        int count = 0;
        for (boolean value : mask) {
            if (value) count++;
        }
        return count;
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

    /** Forces the shared NORMAL channel with the non-PBR normal consumer. */
    private static PostProcessSettings normalConsumingEffects() {
        return PostProcessSettings.builder()
                .subsurfaceScattering(SubsurfaceScatteringSettings.ofSkin())
                .build();
    }

    private static float[] normals(RenderPipeline pipeline) {
        return pipeline.graph().readColorAttachmentRgbaFloat(PostProcessTargets.SCENE_NORMAL);
    }

    /** Dense near-view-aligned lock; every strand lies on the same world line. */
    private static HairStrandSettings viewAlignedHair() {
        return HairStrandSettings.builder()
                .strandCount(16)
                .segmentsPerStrand(8)
                .strandLengthMetres(2.0f)
                .rootSpreadMetres(0.0f)
                .curlMetres(0.0f)
                .strandRadiusMetres(0.01f)
                .flowDirection(new Vector3f(VIEW_ALIGNED_FLOW))
                .widthPixels(16.0f)
                .minimumWidthPixels(8.0f)
                .maximumWidthPixels(24.0f)
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

    /**
     * Independent double reference for the near-view-aligned ribbon plane: the
     * normal is the component of {@code eye - p0} perpendicular to the strand
     * tangent. The fixture camera is at (0, 0, 4) and the lock's local origin is
     * transformed only by the known translation.
     */
    private static double[] viewAlignedAnalyticNormal() {
        double tangentX = VIEW_ALIGNED_FLOW.x;
        double tangentY = VIEW_ALIGNED_FLOW.y;
        double tangentZ = VIEW_ALIGNED_FLOW.z;
        double tangentLength = Math.sqrt(tangentX * tangentX + tangentY * tangentY
                + tangentZ * tangentZ);
        tangentX /= tangentLength;
        tangentY /= tangentLength;
        tangentZ /= tangentLength;
        double dx = -VIEW_ALIGNED_MODEL.m30();
        double dy = -VIEW_ALIGNED_MODEL.m31();
        double dz = CAMERA_EYE_Z - VIEW_ALIGNED_MODEL.m32();
        double tangentDot = tangentX * dx + tangentY * dy + tangentZ * dz;
        double nx = dx - tangentX * tangentDot;
        double ny = dy - tangentY * tangentDot;
        double nz = dz - tangentZ * tangentDot;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return new double[]{nx / length, ny / length, nz / length};
    }

    /**
     * The SSR fragment gate ({@code roughness > cutoff || nDotV <= 0.05}) uses
     * {@code nDotV = clamp(dot(normal, normalize(cameraPosition - worldPosition)), 0, 1)}.
     * This fixture's receiver depth plane is z=0 and the camera sits at (0, 0, 4),
     * so the reconstructed view direction is +Z: the decoded z component is the
     * gate value. Hair is SORTED_ALPHA, which intentionally does not write depth,
     * so the receiver depth behind the lock is exactly what SSR would read.
     */
    private static double ssrNDotV(double[] normal) {
        return Math.max(0.0, Math.min(1.0, normal[2]));
    }

    private static double[] assertNormalEncoded(float[] pixels, int pixel, double[] expected,
                                                String label) {
        double[] encoded = octEncode(expected[0], expected[1], expected[2]);
        assertEquals(encoded[0], pixels[pixel * 4], 2.0e-3, label + ": encoded u");
        assertEquals(encoded[1], pixels[pixel * 4 + 1], 2.0e-3, label + ": encoded v");
        double[] decoded = octDecode(pixels[pixel * 4], pixels[pixel * 4 + 1]);
        assertEquals(expected[0], decoded[0], 5.0e-3, label + ": decoded x");
        assertEquals(expected[1], decoded[1], 5.0e-3, label + ": decoded y");
        assertEquals(expected[2], decoded[2], 5.0e-3, label + ": decoded z");
        return decoded;
    }

    /** Mirrors {@code octEncode} in scene-surface.frag/hair-normal.frag in double. */
    private static double[] octEncode(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        x /= length;
        y /= length;
        z /= length;
        double denominator = Math.abs(x) + Math.abs(y) + Math.abs(z) + 1.0e-8;
        double ox = x / denominator;
        double oy = y / denominator;
        if (z < 0.0) {
            double previousX = ox;
            ox = (1.0 - Math.abs(oy)) * Math.signum(previousX + 1.0e-8);
            oy = (1.0 - Math.abs(previousX)) * Math.signum(oy + 1.0e-8);
        }
        return new double[]{ox, oy};
    }

    /** Mirrors {@code octDecode} in ssr.frag/ssgi.frag in double. */
    private static double[] octDecode(double ox, double oy) {
        double x = ox;
        double y = oy;
        double z = 1.0 - Math.abs(ox) - Math.abs(oy);
        if (z < 0.0) {
            double previousX = x;
            double previousY = y;
            x = (1.0 - Math.abs(previousY)) * (previousX >= 0.0 ? 1.0 : -1.0);
            y = (1.0 - Math.abs(previousX)) * (previousY >= 0.0 ? 1.0 : -1.0);
        }
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / length, y / length, z / length};
    }

    private static Fixture fixture(GlfwWindow window) {
        return fixture(window, PostProcessSettings.defaults());
    }

    private static Fixture fixture(GlfwWindow window, PostProcessSettings effects) {
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
                        .build())
                .postProcessSettings(effects);
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
        return hair(HairStrandSettings.Transparency.SORTED_ALPHA);
    }

    private static HairStrandSettings hair(HairStrandSettings.Transparency transparency) {
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
                .transparency(transparency)
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
