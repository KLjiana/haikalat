package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT1;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL11.glReadBuffer;

/**
 * Real-GL acceptance for the weighted blended OIT pass.
 *
 * <p>Runs the production {@link WeightedOitPass} against a synthetic HDR target
 * and compares float readback with independent double-precision references:
 * the documented weighted model, the exact back-to-front alpha composite, and
 * the two-layer approximation bound derived on the S3 #28 technical card. The
 * cases cover the required matrix: known two-layer resolve, draw-order
 * independence, revealage/transmittance in empty and opaque regions,
 * disabled/unresolved target preservation, and resize/close lifecycle with
 * tracked-resource release.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class WeightedOitPassGlTest {
    private static final int SIZE = 64;
    private static final float FOV_Y_RADIANS = (float) Math.toRadians(45.0);
    private static final float BACKGROUND_RED = 0.05f;
    private static final float BACKGROUND_GREEN = 0.10f;
    private static final float BACKGROUND_BLUE = 0.15f;
    private static final Vector3f BACKGROUND =
            new Vector3f(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE);
    /** Distinct target clear used by the "caller target is untouched" cases. */
    private static final Vector3f SENTINEL = new Vector3f(0.25f, 0.50f, 0.75f);

    @Test
    void twoLayerResolveMatchesTheWeightedModelAndTheSortedAlphaReference() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Matrix4f view = new Matrix4f();
            Matrix4f projection = projection();
            WeightedOitSettings settings = unweightedSettings(8);

            OitSurface front = screenQuad(3.0f, 0.90f, 0.15f, 0.05f, 0.10f);
            OitSurface far = screenQuad(9.0f, 0.05f, 0.15f, 0.90f, 0.20f);
            List<OitSurface> surfaces = List.of(front, far);

            try (WeightedOitPass pass = new WeightedOitPass(SIZE, SIZE, settings);
                 Framebuffer target = hdrTarget()) {
                FloatBuffer resolved = render(device, pass, target, surfaces, view, projection,
                        settings, true);
                int center = pixel(SIZE / 2, SIZE / 2);
                Vector3f weightedModel = weightedModel(settings, surfaces, BACKGROUND);
                Vector3f sorted = sortedReference(surfaces, BACKGROUND);

                assertPixel(weightedModel, resolved, center, 2.0e-3f,
                        "resolved weighted model");
                float approximationBound = twoLayerErrorBound(front, far);
                assertPixel(sorted, resolved, center, approximationBound + 2.0e-3f,
                        "sorted alpha reference within the documented WBOIT bound");

                // With one shared colour the weighted average is exact, so the
                // sorted reference must agree without relying on the error bound.
                OitSurface sharedNear = screenQuad(2.0f, 0.30f, 0.60f, 0.90f, 0.35f);
                OitSurface sharedFar = screenQuad(8.0f, 0.30f, 0.60f, 0.90f, 0.55f);
                List<OitSurface> sharedColour = List.of(sharedNear, sharedFar);
                FloatBuffer sharedPixels = render(device, pass, target, sharedColour, view,
                        projection, settings, true);
                assertPixel(sortedReference(sharedColour, BACKGROUND), sharedPixels, center,
                        2.0e-3f, "equal-colour two-layer sorted reference is exact");

                // The depth-weight branch must agree with the independent
                // reference that applies w(z) per layer.
                WeightedOitSettings depthWeighted = WeightedOitSettings.builder()
                        .enabled(true).depthWeighting(true).depthScale(10.0f)
                        .referenceDepth(20.0f).minimumWeight(1.0e-4f)
                        .maximumWeight(300.0f).alphaThreshold(0.0f)
                        .maximumSurfaces(8).build();
                FloatBuffer weightedPixels = render(device, pass, target, surfaces, view,
                        projection, depthWeighted, true);
                assertPixel(weightedModel(depthWeighted, surfaces, BACKGROUND),
                        weightedPixels, center, 2.0e-3f, "depth-weighted resolve");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void swappingTheDrawOrderDoesNotChangeTheResolvedPixel() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Matrix4f view = new Matrix4f();
            Matrix4f projection = projection();
            WeightedOitSettings settings = unweightedSettings(8);
            OitSurface first = screenQuad(2.5f, 0.85f, 0.20f, 0.10f, 0.30f);
            OitSurface second = screenQuad(7.5f, 0.10f, 0.45f, 0.80f, 0.40f);

            try (WeightedOitPass pass = new WeightedOitPass(SIZE, SIZE, settings);
                 Framebuffer target = hdrTarget()) {
                int center = pixel(SIZE / 2, SIZE / 2);
                FloatBuffer forward = render(device, pass, target, List.of(first, second),
                        view, projection, settings, true);
                FloatBuffer reversed = render(device, pass, target, List.of(second, first),
                        view, projection, settings, true);
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(forward.get(center * 4 + channel),
                            reversed.get(center * 4 + channel), 1.0e-3f,
                            "order-independent accumulation channel " + channel);
                }
                assertPixel(weightedModel(settings, List.of(first, second), BACKGROUND),
                        forward, center, 2.0e-3f, "order-independent weighted model");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void revealageIsOneWithoutSurfacesZeroForOpaqueSurfacesAndAProductOtherwise() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Matrix4f view = new Matrix4f();
            Matrix4f projection = projection();
            WeightedOitSettings settings = unweightedSettings(8);
            int center = pixel(SIZE / 2, SIZE / 2);

            try (WeightedOitPass pass = new WeightedOitPass(SIZE, SIZE, settings);
                 Framebuffer target = hdrTarget()) {
                FloatBuffer empty = render(device, pass, target, List.of(), view, projection,
                        settings, true);
                assertPixel(BACKGROUND, empty, center, 1.0e-4f,
                        "an empty collection must leave the target unchanged");
                FloatBuffer emptyAccumulation = readAttachment(device, pass.framebufferId(),
                        GL_COLOR_ATTACHMENT0, 4);
                FloatBuffer emptyWeight = readAttachment(device, pass.framebufferId(),
                        GL_COLOR_ATTACHMENT1, 1);
                assertEquals(0.0f, emptyAccumulation.get(center * 4), 1.0e-6f,
                        "empty accumulation rgb");
                assertEquals(0.0f, emptyAccumulation.get(center * 4 + 1), 1.0e-6f);
                assertEquals(0.0f, emptyAccumulation.get(center * 4 + 2), 1.0e-6f);
                assertEquals(1.0f, emptyAccumulation.get(center * 4 + 3), 1.0e-6f,
                        "empty revealage/transmittance must be one");
                assertEquals(0.0f, emptyWeight.get(center), 1.0e-6f, "empty weight sum");

                OitSurface opaque = screenQuad(4.0f, 0.20f, 0.60f, 0.90f, 1.0f);
                FloatBuffer opaquePixels = render(device, pass, target, List.of(opaque), view,
                        projection, settings, true);
                FloatBuffer opaqueAccumulation = readAttachment(device, pass.framebufferId(),
                        GL_COLOR_ATTACHMENT0, 4);
                FloatBuffer opaqueWeight = readAttachment(device, pass.framebufferId(),
                        GL_COLOR_ATTACHMENT1, 1);
                assertEquals(0.0f, opaqueAccumulation.get(center * 4 + 3), 1.0e-6f,
                        "an opaque layer must drive revealage to zero");
                assertEquals(1.0f, opaqueWeight.get(center), 1.0e-6f,
                        "an opaque layer with unit weight sums to one");
                assertPixel(new Vector3f(0.20f, 0.60f, 0.90f), opaquePixels, center, 2.0e-3f,
                        "an opaque layer must replace the background");

                OitSurface halfA = screenQuad(3.0f, 0.9f, 0.1f, 0.1f, 0.5f);
                OitSurface halfB = screenQuad(8.0f, 0.1f, 0.1f, 0.9f, 0.5f);
                FloatBuffer layered = render(device, pass, target, List.of(halfA, halfB),
                        view, projection, settings, true);
                FloatBuffer layeredAccumulation = readAttachment(device, pass.framebufferId(),
                        GL_COLOR_ATTACHMENT0, 4);
                FloatBuffer layeredWeight = readAttachment(device, pass.framebufferId(),
                        GL_COLOR_ATTACHMENT1, 1);
                assertEquals(0.25f, layeredAccumulation.get(center * 4 + 3), 2.0e-3f,
                        "two half layers must multiply transmittance, not sum it");
                assertEquals(1.0f, layeredWeight.get(center), 2.0e-3f,
                        "unit weights must sum two half alphas to one");
                assertPixel(weightedModel(settings, List.of(halfA, halfB), BACKGROUND),
                        layered, center, 2.0e-3f, "two-layer weighted resolve");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void recordingWithoutResolveOrWithDisabledSettingsLeavesTheTargetUntouched() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Matrix4f view = new Matrix4f();
            Matrix4f projection = projection();
            WeightedOitSettings settings = unweightedSettings(8);
            int center = pixel(SIZE / 2, SIZE / 2);

            try (WeightedOitPass pass = new WeightedOitPass(SIZE, SIZE, settings);
                 Framebuffer target = hdrTarget()) {
                device.execute(device.createCommandBuffer()
                        .bindFramebuffer(target).viewport(0, 0, SIZE, SIZE)
                        .clearColor(SENTINEL.x, SENTINEL.y, SENTINEL.z, 1.0f)
                        .clear(true, false));
                FloatBuffer sentinel = readColor(target);
                assertPixel(SENTINEL, sentinel, center, 1.0e-6f, "test sentinel");

                CommandBuffer recordOnly = device.createCommandBuffer();
                OitSurface surface = screenQuad(3.0f, 1.0f, 0.0f, 0.0f, 0.5f);
                pass.record(recordOnly, List.of(surface), view, projection, settings);
                device.execute(recordOnly);
                assertPixel(SENTINEL, readColor(target), center,
                        1.0e-6f, "record without resolve must not modify the caller target");

                // Resolving in a later graph pass (a different CommandBuffer) is
                // the supported wiring contract as long as accumulation ran first.
                // One layer has a closed-form result: the weight cancels in the
                // normalisation, so the average is the layer colour and the
                // coverage is alpha; the composite is over the *sentinel* target,
                // not over BACKGROUND (this case never re-clears to BACKGROUND).
                CommandBuffer resolveLater = device.createCommandBuffer();
                resolveLater.bindFramebuffer(target).viewport(0, 0, SIZE, SIZE);
                pass.resolve(resolveLater, settings);
                device.execute(resolveLater);
                float alpha = surface.alpha();
                Vector3f singleLayer = new Vector3f(
                        alpha * surface.red() + (1.0f - alpha) * SENTINEL.x,
                        alpha * surface.green() + (1.0f - alpha) * SENTINEL.y,
                        alpha * surface.blue() + (1.0f - alpha) * SENTINEL.z);
                assertEquals(singleLayer, weightedModel(settings, List.of(surface), SENTINEL),
                        "the analytic single-layer result must agree with the weighted model");
                assertPixel(singleLayer, readColor(target), center, 2.0e-3f,
                        "cross-pass single-layer resolve over the sentinel target");

                device.execute(device.createCommandBuffer()
                        .bindFramebuffer(target).viewport(0, 0, SIZE, SIZE)
                        .clearColor(SENTINEL.x, SENTINEL.y, SENTINEL.z, 1.0f)
                        .clear(true, false));
                CommandBuffer disabledFrame = device.createCommandBuffer();
                pass.record(disabledFrame, List.of(surface), view, projection,
                        WeightedOitSettings.disabled());
                pass.resolve(disabledFrame, WeightedOitSettings.disabled());
                device.execute(disabledFrame);
                assertPixel(SENTINEL, readColor(target), center,
                        1.0e-6f, "disabled settings must record zero work");

                assertThrows(GlException.class,
                        () -> pass.resolve(device.createCommandBuffer(), settings),
                        "a resolve without a preceding record must be rejected");
                assertThrows(IllegalArgumentException.class,
                        () -> new WeightedOitPass(SIZE, SIZE, WeightedOitSettings.disabled()),
                        "disabled settings must not create a pass or attachments");

                pass.close();
                assertPixel(SENTINEL, readColor(target), center,
                        1.0e-6f, "closing the pass must not modify the caller target");
                pass.close();
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void resizeAndCloseReleaseEveryTrackedOwnedResource() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Matrix4f view = new Matrix4f();
            Matrix4f projection = projection();
            WeightedOitSettings settings = unweightedSettings(8);
            int center = pixel(SIZE / 2, SIZE / 2);
            OitSurface surface = screenQuad(4.0f, 0.30f, 0.50f, 0.70f, 0.60f);
            List<OitSurface> surfaces = List.of(surface);

            try (Framebuffer target = hdrTarget()) {
                GlDebug.resourceTracking(true);
                try {
                    WeightedOitPass pass = new WeightedOitPass(SIZE, SIZE, settings);
                    try {
                        assertTrue(pass.accumulationTextureId() != pass.weightTextureId());
                        assertTrue(pass.framebufferId() > 0);
                        assertTrue(GlDebug.resources().liveResources().size() > 0,
                                "the pass must report its owned resources while tracked");

                        FloatBuffer before = render(device, pass, target, surfaces, view,
                                projection, settings, true);
                        assertPixel(weightedModel(settings, surfaces, BACKGROUND), before,
                                center, 2.0e-3f, "render before resize");

                        pass.resize(32, 24);
                        assertEquals(32, pass.width());
                        assertEquals(24, pass.height());
                        assertThrows(IllegalArgumentException.class, () -> pass.resize(0, 8));
                        FloatBuffer after = render(device, pass, target, surfaces, view,
                                projection, settings, true);
                        assertPixel(weightedModel(settings, surfaces, BACKGROUND), after,
                                center, 3.0e-3f, "render after resize");

                        pass.resize(32, 24);
                        assertEquals(32, pass.width(), "same-extent resize must be a no-op");
                    } finally {
                        pass.close();
                        pass.close();
                    }
                    assertTrue(GlDebug.resources().liveResources().isEmpty(),
                            "closing the pass must release every tracked resource: "
                                    + GlDebug.resources().liveResources());
                    assertEquals(GlDebug.resources().createdCount(),
                            GlDebug.resources().closedCount());
                } finally {
                    GlDebug.resourceTracking(false);
                }
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void extremeBrightLayerIsClampedAndStaysFinite() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Matrix4f view = new Matrix4f();
            Matrix4f projection = projection();
            WeightedOitSettings settings = WeightedOitSettings.builder()
                    .enabled(true).depthWeighting(true).depthScale(10.0f)
                    .referenceDepth(20.0f).minimumWeight(1.0e-2f)
                    .maximumWeight(300.0f).alphaThreshold(0.0f)
                    .maximumSurfaces(8).build();
            // Reviewer counterexample: 7000 * 9.975 = 69825 > half-float max.
            OitSurface surface = screenQuad(1.0f, 7000.0f, 7000.0f, 7000.0f, 1.0f);

            try (WeightedOitPass pass = new WeightedOitPass(SIZE, SIZE, settings);
                 Framebuffer target = hdrTarget()) {
                FloatBuffer resolved = render(device, pass, target, List.of(surface), view,
                        projection, settings, true);
                assertAllFiniteAndBounded(resolved, 65504.0f);

                float weight = (float) referenceWeight(settings, 1.0);
                float expectedAverage = 30_000.0f / weight;
                int center = pixel(SIZE / 2, SIZE / 2);
                assertEquals(expectedAverage, resolved.get(center * 4), 2.5f,
                        "the per-layer contribution cap must feed the resolve, not a fallback");
                assertEquals(0.0f, resolved.get(center * 4 + 1), 1.0e-6f);
                assertEquals(0.0f, resolved.get(center * 4 + 2), 1.0e-6f);

                FloatBuffer accumulation = readAttachment(device, pass.framebufferId(),
                        GL_COLOR_ATTACHMENT0, 4);
                FloatBuffer weightSum = readAttachment(device, pass.framebufferId(),
                        GL_COLOR_ATTACHMENT1, 1);
                assertAllFiniteAndBounded(accumulation, 30_000.0f);
                assertAllFiniteAndBounded(weightSum, 30_000.0f);
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void overflowingOpaqueStackFallsBackToTheUnchangedTarget() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Matrix4f view = new Matrix4f();
            Matrix4f projection = projection();
            WeightedOitSettings settings = WeightedOitSettings.builder()
                    .enabled(true).depthWeighting(true).depthScale(1000.0f)
                    .referenceDepth(1_000_000.0f).minimumWeight(1.0e-4f)
                    .maximumWeight(1000.0f).alphaThreshold(0.0f)
                    .maximumSurfaces(128).build();
            List<OitSurface> stack = new ArrayList<>();
            // 100 layers x alpha 1 x weight ~= 1000 -> 100000 per attachment,
            // far beyond the half-float maximum; the resolve must reject it.
            for (int index = 0; index < 100; index++) {
                stack.add(screenQuad(1.0f, 1.0f, 1.0f, 1.0f, 1.0f));
            }

            try (WeightedOitPass pass = new WeightedOitPass(SIZE, SIZE, settings);
                 Framebuffer target = hdrTarget()) {
                FloatBuffer resolved = render(device, pass, target, stack, view, projection,
                        settings, true);
                assertAllFiniteAndBounded(resolved, 65504.0f);
                assertPixel(BACKGROUND, resolved, pixel(SIZE / 2, SIZE / 2), 1.0e-4f,
                        "overflowed accumulation must fall back to the unchanged target");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    private static void assertAllFiniteAndBounded(FloatBuffer pixels, float maximum) {
        for (int index = 0; index < pixels.capacity(); index++) {
            float value = pixels.get(index);
            assertTrue(Float.isFinite(value),
                    "readback[" + index + "] must be finite but was " + value);
            assertTrue(value >= 0.0f && value <= maximum,
                    "readback[" + index + "] out of [" + 0 + ", " + maximum + "]: " + value);
        }
    }

    private static Matrix4f projection() {
        return new Matrix4f().perspective(FOV_Y_RADIANS, 1.0f, 0.1f, 100.0f);
    }

    private static WeightedOitSettings unweightedSettings(int maximumSurfaces) {
        return WeightedOitSettings.builder()
                .enabled(true).depthWeighting(false)
                .depthScale(10.0f).referenceDepth(20.0f)
                .minimumWeight(1.0e-4f).maximumWeight(300.0f)
                .alphaThreshold(0.0f).maximumSurfaces(maximumSurfaces).build();
    }

    /**
     * Screen-covering world-space quad at {@code viewDepth} metres. The view is
     * the identity, so the view-space depth equals {@code -centerZ}.
     */
    private static OitSurface screenQuad(float viewDepth, float red, float green, float blue,
                                         float alpha) {
        float halfExtent = viewDepth * (float) Math.tan(FOV_Y_RADIANS * 0.5) * 2.0f;
        return new OitSurface(0.0f, 0.0f, -viewDepth,
                halfExtent, 0.0f, 0.0f,
                0.0f, halfExtent, 0.0f,
                red, green, blue, alpha);
    }

    private static FloatBuffer render(GlRenderDevice device, WeightedOitPass pass,
                                      Framebuffer target, List<OitSurface> surfaces,
                                      Matrix4f view, Matrix4f projection,
                                      WeightedOitSettings settings, boolean resolve) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target).viewport(0, 0, SIZE, SIZE)
                .clearColor(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE, 1.0f)
                .clear(true, false);
        pass.record(commands, surfaces, view, projection, settings);
        if (resolve) {
            commands.bindFramebuffer(target).viewport(0, 0, SIZE, SIZE);
            pass.resolve(commands, settings);
        }
        device.execute(commands);
        device.invalidateState();
        return readColor(target);
    }

    private static FloatBuffer readColor(Framebuffer target) {
        target.bind();
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
        return pixels;
    }

    private static FloatBuffer readAttachment(GlRenderDevice device, int framebufferId,
                                              int attachment, int components) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebufferId);
        glReadBuffer(attachment);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * components);
        glReadPixels(0, 0, SIZE, SIZE, components == 4 ? GL_RGBA : GL_RED, GL_FLOAT, pixels);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        device.invalidateState();
        return pixels;
    }

    /**
     * Independent double-precision weighted model: normalised weighted colour
     * over the exact transmittance {@code prod(1 - alpha)}.
     */
    private static Vector3f weightedModel(WeightedOitSettings settings,
                                          List<OitSurface> surfaces, Vector3f background) {
        double transmittance = 1.0;
        double weightSum = 0.0;
        double[] colour = new double[3];
        for (OitSurface surface : surfaces) {
            double alpha = surface.alpha();
            if (alpha <= settings.alphaThreshold()) {
                continue;
            }
            double weight = referenceWeight(settings, -surface.centerZ());
            transmittance *= 1.0 - alpha;
            weightSum += alpha * weight;
            colour[0] += surface.red() * alpha * weight;
            colour[1] += surface.green() * alpha * weight;
            colour[2] += surface.blue() * alpha * weight;
        }
        double coverage = 1.0 - transmittance;
        if (weightSum <= 1.0e-12) {
            return new Vector3f(background);
        }
        return new Vector3f(
                (float) (colour[0] / weightSum * coverage + background.x * transmittance),
                (float) (colour[1] / weightSum * coverage + background.y * transmittance),
                (float) (colour[2] / weightSum * coverage + background.z * transmittance));
    }

    private static double referenceWeight(WeightedOitSettings settings, double viewDepth) {
        if (!settings.depthWeighting()) {
            return 1.0;
        }
        double normalized = Math.abs(viewDepth) / settings.referenceDepth();
        double raw = settings.depthScale() / (1.0 + normalized * normalized);
        return Math.min(settings.maximumWeight(), Math.max(settings.minimumWeight(), raw));
    }

    /** Exact back-to-front alpha composite of the same surfaces. */
    private static Vector3f sortedReference(List<OitSurface> surfaces, Vector3f background) {
        List<OitSurface> sorted = new ArrayList<>(surfaces);
        sorted.sort(Comparator.comparingDouble((OitSurface surface) -> -surface.centerZ())
                .reversed());
        double[] colour = {background.x, background.y, background.z};
        for (OitSurface surface : sorted) {
            double alpha = surface.alpha();
            colour[0] = surface.red() * alpha + colour[0] * (1.0 - alpha);
            colour[1] = surface.green() * alpha + colour[1] * (1.0 - alpha);
            colour[2] = surface.blue() * alpha + colour[2] * (1.0 - alpha);
        }
        return new Vector3f((float) colour[0], (float) colour[1], (float) colour[2]);
    }

    /**
     * Max-channel bound for the two-layer WBOIT approximation error:
     * {@code |WBOIT - exact| = aNear^2 * aFar / (aNear + aFar) * |cNear - cFar|}.
     */
    private static float twoLayerErrorBound(OitSurface near, OitSurface far) {
        double aNear = near.alpha();
        double aFar = far.alpha();
        double coefficient = aNear * aNear * aFar / (aNear + aFar);
        double maxColourDifference = Math.max(
                Math.abs(near.red() - far.red()),
                Math.max(Math.abs(near.green() - far.green()),
                        Math.abs(near.blue() - far.blue())));
        return (float) (coefficient * maxColourDifference);
    }

    private static void assertPixel(Vector3f expected, FloatBuffer pixels, int index,
                                    float tolerance, String message) {
        assertEquals(expected.x, pixels.get(index * 4), tolerance, message + " red");
        assertEquals(expected.y, pixels.get(index * 4 + 1), tolerance, message + " green");
        assertEquals(expected.z, pixels.get(index * 4 + 2), tolerance, message + " blue");
    }

    private static int pixel(int x, int y) {
        return y * SIZE + x;
    }

    private static Framebuffer hdrTarget() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(SIZE, SIZE)
                .colorTexture(RenderFormat.RGBA16F)
                .build());
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Weighted OIT GL test").visible(false).build();
    }
}
