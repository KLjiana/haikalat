package com.kaleblangley.haikalat.subsystems.render3d.hair;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandPass.HairStrandLight;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

/**
 * Real-GL acceptance for the bounded hair-strand feature (#46).
 *
 * <p>Seven discriminating cases cover the required matrix without claiming
 * card geometry: bounded screen-space ribbon width, distance LOD switching,
 * Kajiya-Kay tangent highlight versus the parallel-tangent negative control,
 * back-to-front alpha ordering against a single-layer-derived reference,
 * directional shadow casting through the existing {@link DirectionalShadowMap}
 * depth target and receiving through the pass's shadow sampler, disabled
 * byte-identical no-op, and candidate-first resize plus idempotent close.
 * The test is guarded so plain {@code test} skips it; {@code glSmoke} runs it.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class HairStrandGlTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final float FOV_Y = (float) Math.toRadians(55.0);
    private static final Vector3f CAMERA_TARGET = new Vector3f(0.0f, 0.0f, 0.0f);

    @Test
    void strandRibbonWidthIsBoundedInPixelsAndNotACard() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (Framebuffer target = colorTarget()) {
                HairStrandSettings thin = singleStrand(2.0f, 1.5f, 12.0f).build();
                HairStrandSettings thick = singleStrand(8.0f, 1.5f, 12.0f).build();
                ExternalCamera camera = camera(new Vector3f(0.5f, 0.0f, 2.5f),
                        new Vector3f(0.5f, 0.0f, 0.0f));
                try (HairStrandPass thinPass = new HairStrandPass(thin, WIDTH, HEIGHT);
                     HairStrandPass thickPass = new HairStrandPass(thick, WIDTH, HEIGHT)) {
                    byte[] thinPixels = render(device, target, thinPass, thin, camera,
                            new Matrix4f().translate(-0.5f, 0.0f, 0.0f), whiteAmbient(), 0,
                            null, 0.0f);
                    byte[] thickPixels = render(device, target, thickPass, thick, camera,
                            new Matrix4f().translate(-0.5f, 0.0f, 0.0f), whiteAmbient(), 0,
                            null, 0.0f);

                    int thinCovered = coveredPixels(thinPixels, 24);
                    int thickCovered = coveredPixels(thickPixels, 24);
                    assertTrue(thinCovered > 60,
                            "a single strand must cover a real ribbon, covered=" + thinCovered);
                    assertTrue(thickCovered > thinCovered * 2,
                            "a wider pixel width must cover more pixels: thin=" + thinCovered
                                    + " thick=" + thickCovered);
                    assertTrue(maxVerticalRun(thickPixels, 24) <= (int) thick.maximumWidthPixels() + 3,
                            "the ribbon must stay inside the maximum pixel width; max run="
                                    + maxVerticalRun(thickPixels, 24));
                    assertTrue(maxVerticalRun(thinPixels, 24) <= (int) thin.maximumWidthPixels() + 3);
                    int columns = coveredColumns(thinPixels, 24);
                    assertTrue(columns >= 25,
                            "the strand must span a curved path, covered columns=" + columns);
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    @Test
    void lodSwitchesFromAllStrandsToAQuarterAtDistance() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            HairStrandSettings settings = HairStrandSettings.builder()
                    .strandCount(16).segmentsPerStrand(8).strandLengthMetres(1.0f)
                    .rootSpreadMetres(0.15f).curlMetres(0.05f)
                    .flowDirection(new Vector3f(1.0f, 0.0f, 0.0f))
                    .widthPixels(5.0f).minimumWidthPixels(1.5f).maximumWidthPixels(12.0f)
                    .nearDistanceMetres(4.0f).farDistanceMetres(9.0f)
                    .lodMinimumPixels(30.0f).lodWidthScale(0.5f)
                    .alpha(1.0f).alphaCutoff(0.35f)
                    .transparency(HairStrandSettings.Transparency.CUTOUT)
                    .rootColor(new Vector3f(1.0f, 1.0f, 1.0f))
                    .tipColor(new Vector3f(1.0f, 1.0f, 1.0f))
                    .diffuseStrength(0.0f).specularStrength(0.0f)
                    .colorVariation(0.0f).seed(77L).build();
            try (Framebuffer target = colorTarget();
                 HairStrandPass pass = new HairStrandPass(settings, WIDTH, HEIGHT)) {
                ExternalCamera near = camera(new Vector3f(0.5f, 0.0f, 1.8f),
                        new Vector3f(0.5f, 0.0f, 0.0f));
                byte[] nearPixels = render(device, target, pass, settings, near,
                        new Matrix4f().translate(-0.5f, 0.0f, 0.0f), whiteAmbient(), 0,
                        null, 0.0f);
                assertEquals(HairStrandSettings.Lod.HIGH, pass.activeLod());
                assertEquals(16, pass.activeStrandCount());

                ExternalCamera far = camera(new Vector3f(0.5f, 0.0f, 12.0f),
                        new Vector3f(0.5f, 0.0f, 0.0f));
                byte[] farPixels = render(device, target, pass, settings, far,
                        new Matrix4f().translate(-0.5f, 0.0f, 0.0f), whiteAmbient(), 0,
                        null, 0.0f);
                assertEquals(HairStrandSettings.Lod.LOW, pass.activeLod());
                assertEquals(4, pass.activeStrandCount());
                assertTrue(pass.activeWidthScale() < 0.75f,
                        "the far level must narrow the requested width");

                int nearCovered = coveredPixels(nearPixels, 24);
                int farCovered = coveredPixels(farPixels, 24);
                assertTrue(nearCovered > farCovered * 2,
                        "the far LOD must draw substantially less coverage: near="
                                + nearCovered + " far=" + farCovered);
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void tangentHighlightBeatsTheParallelTangentNegativeControl() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Vector3f viewDirection = new Vector3f(0.0f, 0.0f, 1.0f);
            Vector3f lightDirection = new Vector3f(1.0f, 1.0f, 1.0f).normalize();
            Vector3f half = new Vector3f(lightDirection).add(viewDirection).normalize();
            Vector3f positive = new Vector3f(half).cross(0.0f, 1.0f, 0.0f).normalize();
            Vector3f negative = new Vector3f(half);
            HairStrandSettings.Builder base = HairStrandSettings.builder()
                    .strandCount(1).segmentsPerStrand(8).strandLengthMetres(1.0f)
                    .rootSpreadMetres(0.0f).curlMetres(0.0f)
                    .widthPixels(8.0f).minimumWidthPixels(2.0f).maximumWidthPixels(12.0f)
                    .nearDistanceMetres(20.0f).farDistanceMetres(40.0f)
                    .lodMinimumPixels(1.0f).lodWidthScale(1.0f)
                    .alpha(1.0f).alphaCutoff(0.35f)
                    .transparency(HairStrandSettings.Transparency.CUTOUT)
                    .rootColor(new Vector3f(1.0f, 1.0f, 1.0f))
                    .tipColor(new Vector3f(1.0f, 1.0f, 1.0f))
                    .diffuseStrength(0.0f).specularStrength(2.5f)
                    .primaryExponent(48.0f).secondaryExponent(128.0f)
                    .secondaryWeight(0.5f).colorVariation(0.0f).seed(91L);
            HairStrandSettings highlightSettings = base.flowDirection(positive).build();
            HairStrandSettings negativeSettings = base.flowDirection(negative).build();
            ExternalCamera camera = camera(new Vector3f(0.0f, 0.0f, 3.0f), CAMERA_TARGET);
            HairStrandLight light = new HairStrandLight(
                    new Vector3f(-1.0f, -1.0f, -1.0f).normalize(),
                    new Vector3f(1.0f, 1.0f, 1.0f), 1.0f, new Vector3f());
            try (Framebuffer target = colorTarget();
                 HairStrandPass highlightPass = new HairStrandPass(highlightSettings, WIDTH, HEIGHT);
                 HairStrandPass negativePass = new HairStrandPass(negativeSettings, WIDTH, HEIGHT)) {
                byte[] highlightPixels = render(device, target, highlightPass,
                        highlightSettings, camera, centeredModel(positive), light, 0, null, 0.0f);
                byte[] negativePixels = render(device, target, negativePass,
                        negativeSettings, camera, centeredModel(negative), light, 0, null, 0.0f);
                int highlightCount = coveredPixels(highlightPixels, 100);
                int negativeMaximum = maximumRgbChannel(negativePixels);
                assertTrue(highlightCount > 30,
                        "perpendicular tangent must produce a visible highlight, count="
                                + highlightCount);
                assertTrue(negativeMaximum < 12,
                        "parallel tangent must be the geometric negative control, max RGB="
                                + negativeMaximum);
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void sortedAlphaComposesInBackToFrontOrderAndSortsStrandsPerDraw() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            HairStrandSettings red = blendLock(new Vector3f(1.0f, 0.0f, 0.0f)).build();
            HairStrandSettings blue = blendLock(new Vector3f(0.0f, 0.0f, 1.0f)).build();
            HairStrandSettings sorted = HairStrandSettings.builder()
                    .strandCount(8).segmentsPerStrand(6).strandLengthMetres(0.8f)
                    .rootSpreadMetres(0.2f).curlMetres(0.0f)
                    .flowDirection(new Vector3f(1.0f, 0.0f, 0.0f))
                    .widthPixels(7.0f).minimumWidthPixels(2.0f).maximumWidthPixels(12.0f)
                    .nearDistanceMetres(20.0f).farDistanceMetres(40.0f)
                    .lodMinimumPixels(1.0f).lodWidthScale(1.0f)
                    .alpha(0.5f).alphaCutoff(0.0f)
                    .transparency(HairStrandSettings.Transparency.SORTED_ALPHA)
                    .rootColor(new Vector3f(1.0f, 1.0f, 1.0f))
                    .tipColor(new Vector3f(1.0f, 1.0f, 1.0f))
                    .diffuseStrength(0.0f).specularStrength(0.0f)
                    .colorVariation(0.0f).seed(31L).build();
            ExternalCamera camera = camera(new Vector3f(0.0f, 0.0f, 3.0f), CAMERA_TARGET);
            HairStrandLight ambient = whiteAmbient();
            Matrix4f farModel = new Matrix4f().translate(-0.4f, 0.0f, -1.0f);
            Matrix4f nearModel = new Matrix4f().translate(-0.4f, 0.0f, 0.0f);
            try (Framebuffer target = colorTarget();
                 HairStrandPass redPass = new HairStrandPass(red, WIDTH, HEIGHT);
                 HairStrandPass bluePass = new HairStrandPass(blue, WIDTH, HEIGHT);
                 HairStrandPass sortedPass = new HairStrandPass(sorted, WIDTH, HEIGHT)) {
                byte[] farOnly = render(device, target, redPass, red, camera, farModel,
                        ambient, 0, null, 0.0f);
                byte[] nearOnly = render(device, target, bluePass, blue, camera, nearModel,
                        ambient, 0, null, 0.0f);
                byte[] farThenNear = renderTwo(device, target,
                        redPass, red, farModel, bluePass, blue, nearModel, camera, ambient);
                byte[] nearThenFar = renderTwo(device, target,
                        bluePass, blue, nearModel, redPass, red, farModel, camera, ambient);

                int overlap = firstOverlap(nearOnly, farOnly);
                assertTrue(overlap >= 0, "the two locks must overlap");
                int nearBlue = unsigned(nearOnly, overlap * 4 + 2);
                int farRed = unsigned(farOnly, overlap * 4);
                int composedRed = unsigned(farThenNear, overlap * 4);
                int reversedRed = unsigned(nearThenFar, overlap * 4);
                float nearAlpha = nearBlue / 255.0f;
                assertEquals(farRed * (1.0f - nearAlpha), composedRed, 4.0f,
                        "far->near over-composite must follow SRC_ALPHA/ONE_MINUS_SRC_ALPHA");
                assertTrue(reversedRed > composedRed + 8,
                        "near->far must differ from far->near when alpha < 1: reversed="
                                + reversedRed + " composed=" + composedRed);

                render(device, target, sortedPass, sorted, camera,
                        new Matrix4f().translate(-0.4f, 0.0f, 0.0f), ambient, 0, null, 0.0f);
                assertTrue(sortedPass.lastDrawOrderBackToFront(),
                        "the pass must record the generated strands far to near");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void hairCastsIntoTheExistingDirectionalShadowMapAndReceivesIt() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            DirectionalShadowMap map = new DirectionalShadowMap(
                    ShadowSettings.directionalDefaults());
            SceneLight light = SceneLight.directional(new Vector3f(0.0f, -1.0f, 0.0f),
                    new Vector3f(1.0f, 1.0f, 1.0f), 2.0f);
            Matrix4f lightSpace = map.lightSpaceMatrix(light, new Vector3f());
            HairStrandSettings settings = HairStrandSettings.builder()
                    .strandCount(12).segmentsPerStrand(6).strandLengthMetres(1.5f)
                    .rootSpreadMetres(0.25f).curlMetres(0.0f).strandRadiusMetres(0.03f)
                    .flowDirection(new Vector3f(1.0f, 0.0f, 0.0f))
                    .widthPixels(8.0f).minimumWidthPixels(2.0f).maximumWidthPixels(12.0f)
                    .nearDistanceMetres(20.0f).farDistanceMetres(40.0f)
                    .lodMinimumPixels(1.0f).lodWidthScale(1.0f)
                    .alpha(1.0f).alphaCutoff(0.35f)
                    .transparency(HairStrandSettings.Transparency.CUTOUT)
                    .rootColor(new Vector3f(1.0f, 1.0f, 1.0f))
                    .tipColor(new Vector3f(1.0f, 1.0f, 1.0f))
                    .diffuseStrength(1.0f).specularStrength(0.0f)
                    .colorVariation(0.0f).castsShadow(true).seed(4242L).build();
            HairStrandLight shading = new HairStrandLight(new Vector3f(0.0f, -1.0f, 0.0f),
                    new Vector3f(1.0f, 1.0f, 1.0f), 2.0f,
                    new Vector3f(0.15f, 0.15f, 0.15f));
            ExternalCamera camera = camera(new Vector3f(0.5f, 0.0f, 4.0f),
                    new Vector3f(0.5f, 0.0f, 0.0f));
            Matrix4f blocker = new Matrix4f().translate(-0.75f, 1.2f, 0.0f);
            Matrix4f receiver = new Matrix4f().translate(-0.75f, 0.0f, 0.0f);
            try (Framebuffer shadowFramebuffer = Framebuffer.fromDescriptor(map.descriptor());
                 Framebuffer target = colorTarget();
                 HairStrandPass pass = new HairStrandPass(settings, WIDTH, HEIGHT)) {
                CommandBuffer shadowCommands = device.createCommandBuffer();
                shadowCommands.bindFramebuffer(shadowFramebuffer)
                        .viewport(0, 0, map.settings().resolution(), map.settings().resolution())
                        .depthMask(true)
                        .clear(false, true);
                pass.recordShadow(shadowCommands, blocker, lightSpace, settings,
                        new Vector3f(0.0f, -1.0f, 0.0f));
                device.execute(shadowCommands);
                device.invalidateState();
                assertEquals(1, shadowFramebuffer.depthAttachment() > 0 ? 1 : 0);
                assertTrue(castDepthPixels(shadowFramebuffer) > 20,
                        "the hair caster must write depth into the existing shadow map");

                byte[] unshadowed = render(device, target, pass, settings, camera, receiver,
                        shading, 0, null, 0.002f);
                byte[] shadowed = render(device, target, pass, settings, camera, receiver,
                        shading, shadowFramebuffer.depthAttachment(), lightSpace, 0.002f);
                int receiverPixels = receiverMask(unshadowed);
                assertTrue(receiverPixels > 40,
                        "the receiver fixture must cover real pixels, count=" + receiverPixels);
                float unshadowedMean = maskedMean(unshadowed, unshadowed, 24);
                float shadowedMean = maskedMean(shadowed, unshadowed, 24);
                assertTrue(unshadowedMean > 0.35f,
                        "the unshadowed receiver must be lit, mean=" + unshadowedMean);
                assertTrue(shadowedMean < unshadowedMean * 0.85f,
                        "the receiver must darken inside the hair shadow: unshadowed="
                                + unshadowedMean + " shadowed=" + shadowedMean);
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void disabledRecordIsByteIdenticalAndCloseReleasesEveryOwnedResource() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            HairStrandSettings settings = singleStrand(5.0f, 1.5f, 12.0f).build();
            ExternalCamera camera = camera(new Vector3f(0.5f, 0.0f, 2.5f),
                    new Vector3f(0.5f, 0.0f, 0.0f));
            try (Framebuffer target = colorTarget()) {
                CommandBuffer baselineCommands = device.createCommandBuffer();
                baselineCommands.bindFramebuffer(target).viewport(0, 0, WIDTH, HEIGHT)
                        .clearColor(0.1f, 0.2f, 0.3f, 1.0f).clear(true, false);
                device.execute(baselineCommands);
                device.invalidateState();
                byte[] baseline = read(target);

                GlDebug.resourceTracking(true);
                try {
                    HairStrandPass pass = new HairStrandPass(settings, WIDTH, HEIGHT);
                    CommandBuffer disabledCommands = device.createCommandBuffer();
                    pass.record(disabledCommands, camera, new Matrix4f(),
                            HairStrandSettings.disabled(), whiteAmbient(), 0, null, 0.0f);
                    pass.recordShadow(disabledCommands, new Matrix4f(), new Matrix4f(),
                            HairStrandSettings.disabled(), new Vector3f(0.0f, -1.0f, 0.0f));
                    device.execute(disabledCommands);
                    device.invalidateState();
                    assertArrayEquals(baseline, read(target),
                            "a disabled record must not enqueue any command or touch the target");

                    assertTrue(GlDebug.resources().liveResources().size() > 0,
                            "the active pass must own tracked resources");
                    pass.close();
                    pass.close();
                    assertTrue(GlDebug.resources().liveResources().isEmpty(),
                            "idempotent close must release every pass-owned resource: "
                                    + GlDebug.resources().liveResources());
                    assertEquals(GlDebug.resources().createdCount(),
                            GlDebug.resources().closedCount());
                } finally {
                    GlDebug.resourceTracking(false);
                }
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void resizePublishesOnlyAValidatedExtentAndCloseIsTransactional() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            HairStrandSettings settings = singleStrand(5.0f, 1.5f, 12.0f).build();
            GlDebug.resourceTracking(true);
            try {
                HairStrandPass pass = new HairStrandPass(settings, WIDTH, HEIGHT);
                try {
                    assertEquals(WIDTH, pass.width());
                    assertEquals(HEIGHT, pass.height());
                    pass.resize(96, 72);
                    assertEquals(96, pass.width());
                    assertEquals(72, pass.height());
                    assertThrows(IllegalArgumentException.class, () -> pass.resize(0, 72));
                    assertThrows(IllegalArgumentException.class,
                            () -> pass.resize(96, HairStrandPass.MAXIMUM_EXTENT + 1));
                    assertEquals(96, pass.width(),
                            "a rejected candidate must not replace the active extent");
                    assertEquals(72, pass.height());
                    pass.resize(96, 72);
                    assertEquals(96, pass.width(), "same-extent resize must be a no-op");
                } finally {
                    pass.close();
                    pass.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
                assertEquals(GlDebug.resources().createdCount(),
                        GlDebug.resources().closedCount());
            } finally {
                GlDebug.resourceTracking(false);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static HairStrandSettings.Builder singleStrand(float width, float minimum,
                                                          float maximum) {
        return HairStrandSettings.builder()
                .strandCount(1).segmentsPerStrand(12)
                .strandLengthMetres(1.0f).rootSpreadMetres(0.0f).curlMetres(0.15f)
                .flowDirection(new Vector3f(1.0f, 0.0f, 0.0f))
                .widthPixels(width).minimumWidthPixels(minimum).maximumWidthPixels(maximum)
                .nearDistanceMetres(20.0f).farDistanceMetres(40.0f)
                .lodMinimumPixels(1.0f).lodWidthScale(1.0f)
                .alpha(1.0f).alphaCutoff(0.35f)
                .transparency(HairStrandSettings.Transparency.CUTOUT)
                .rootColor(new Vector3f(1.0f, 1.0f, 1.0f))
                .tipColor(new Vector3f(1.0f, 1.0f, 1.0f))
                .diffuseStrength(1.0f).specularStrength(0.0f)
                .colorVariation(0.0f).castsShadow(false).seed(11L);
    }

    private static HairStrandSettings.Builder blendLock(Vector3f color) {
        return HairStrandSettings.builder()
                .strandCount(1).segmentsPerStrand(10).strandLengthMetres(0.8f)
                .rootSpreadMetres(0.0f).curlMetres(0.0f)
                .flowDirection(new Vector3f(1.0f, 0.0f, 0.0f))
                .widthPixels(12.0f).minimumWidthPixels(4.0f).maximumWidthPixels(16.0f)
                .nearDistanceMetres(20.0f).farDistanceMetres(40.0f)
                .lodMinimumPixels(1.0f).lodWidthScale(1.0f)
                .alpha(0.5f).alphaCutoff(0.0f)
                .transparency(HairStrandSettings.Transparency.SORTED_ALPHA)
                .rootColor(color).tipColor(color)
                .diffuseStrength(0.0f).specularStrength(0.0f)
                .colorVariation(0.0f).castsShadow(false).seed(19L);
    }

    private static HairStrandLight whiteAmbient() {
        return new HairStrandLight(new Vector3f(0.0f, -1.0f, 0.0f),
                new Vector3f(1.0f, 1.0f, 1.0f), 0.0f, new Vector3f(1.0f, 1.0f, 1.0f));
    }

    private static Matrix4f centeredModel(Vector3f flow) {
        return new Matrix4f().translate(-flow.x * 0.5f, -flow.y * 0.5f, -flow.z * 0.5f);
    }

    private static ExternalCamera camera(Vector3f eye, Vector3f target) {
        Matrix4f view = new Matrix4f().lookAt(eye, target,
                Math.abs(eye.x - target.x) < 1.0e-4f ? new Vector3f(0.0f, 1.0f, 0.0f)
                        : new Vector3f(0.0f, 1.0f, 0.0f));
        Matrix4f projection = new Matrix4f().perspective(FOV_Y, WIDTH / (float) HEIGHT,
                0.1f, 100.0f);
        return ExternalCamera.of(view, projection, eye, 0.0f);
    }

    private static byte[] render(GlRenderDevice device, Framebuffer target,
                                 HairStrandPass pass, HairStrandSettings settings,
                                 ExternalCamera camera, Matrix4f model, HairStrandLight light,
                                 int shadowTexture, Matrix4f lightSpace, float shadowBias) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target).viewport(0, 0, WIDTH, HEIGHT)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f).clear(true, false);
        pass.record(commands, camera, model, settings, light, shadowTexture, lightSpace,
                shadowBias);
        device.execute(commands);
        device.invalidateState();
        return read(target);
    }

    private static byte[] renderTwo(GlRenderDevice device, Framebuffer target,
                                    HairStrandPass firstPass, HairStrandSettings firstSettings,
                                    Matrix4f firstModel, HairStrandPass secondPass,
                                    HairStrandSettings secondSettings, Matrix4f secondModel,
                                    ExternalCamera camera, HairStrandLight light) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target).viewport(0, 0, WIDTH, HEIGHT)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f).clear(true, false);
        firstPass.record(commands, camera, firstModel, firstSettings, light, 0, null, 0.0f);
        secondPass.record(commands, camera, secondModel, secondSettings, light, 0, null, 0.0f);
        device.execute(commands);
        device.invalidateState();
        return read(target);
    }

    private static Framebuffer colorTarget() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(WIDTH, HEIGHT)
                .colorTexture(RenderFormat.RGBA8).build());
    }

    private static byte[] read(Framebuffer target) {
        target.bind();
        ByteBuffer buffer = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        byte[] pixels = new byte[WIDTH * HEIGHT * 4];
        buffer.get(pixels);
        return pixels;
    }

    private static int castDepthPixels(Framebuffer shadowFramebuffer) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, shadowFramebuffer.id());
        glReadBuffer(org.lwjgl.opengl.GL11.GL_NONE);
        FloatBuffer depth = BufferUtils.createFloatBuffer(
                shadowFramebuffer.width() * shadowFramebuffer.height());
        glReadPixels(0, 0, shadowFramebuffer.width(), shadowFramebuffer.height(),
                GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        int written = 0;
        for (int index = 0; index < depth.capacity(); index++) {
            if (depth.get(index) < 0.999f && depth.get(index) > 0.0f) {
                written++;
            }
        }
        return written;
    }

    private static int coveredPixels(byte[] pixels, int threshold) {
        int count = 0;
        for (int index = 0; index < WIDTH * HEIGHT; index++) {
            int luminance = unsigned(pixels, index * 4 + 1);
            if (luminance > threshold) {
                count++;
            }
        }
        return count;
    }

    private static int coveredColumns(byte[] pixels, int threshold) {
        int columns = 0;
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                if (unsigned(pixels, ((y * WIDTH) + x) * 4 + 1) > threshold) {
                    columns++;
                    break;
                }
            }
        }
        return columns;
    }

    private static int maxVerticalRun(byte[] pixels, int threshold) {
        int maximum = 0;
        for (int x = 0; x < WIDTH; x++) {
            int run = 0;
            for (int y = 0; y < HEIGHT; y++) {
                if (unsigned(pixels, ((y * WIDTH) + x) * 4 + 1) > threshold) {
                    run++;
                    maximum = Math.max(maximum, run);
                } else {
                    run = 0;
                }
            }
        }
        return maximum;
    }

    /**
     * Maximum RGB channel over the frame. The alpha channel is the strand
     * coverage (the cutout profile writes up to 255 even when the shaded colour
     * is black), so it must not participate in a highlight/negative-control
     * brightness assertion.
     */
    private static int maximumRgbChannel(byte[] pixels) {
        int maximum = 0;
        for (int index = 0; index < WIDTH * HEIGHT; index++) {
            int offset = index * 4;
            maximum = Math.max(maximum, unsigned(pixels, offset));
            maximum = Math.max(maximum, unsigned(pixels, offset + 1));
            maximum = Math.max(maximum, unsigned(pixels, offset + 2));
        }
        return maximum;
    }

    private static int receiverMask(byte[] maskPixels) {
        int count = 0;
        for (int index = 0; index < WIDTH * HEIGHT; index++) {
            if (luminance(maskPixels, index) > 24) {
                count++;
            }
        }
        return count;
    }

    private static float maskedMean(byte[] pixels, byte[] maskPixels, int threshold) {
        double sum = 0.0;
        int count = 0;
        for (int index = 0; index < WIDTH * HEIGHT; index++) {
            if (luminance(maskPixels, index) > threshold) {
                sum += luminance(pixels, index);
                count++;
            }
        }
        return count == 0 ? 0.0f : (float) (sum / count / 255.0);
    }

    private static double luminance(byte[] pixels, int index) {
        int offset = index * 4;
        return 0.2126 * unsigned(pixels, offset) + 0.7152 * unsigned(pixels, offset + 1)
                + 0.0722 * unsigned(pixels, offset + 2);
    }

    private static int firstOverlap(byte[] nearOnly, byte[] farOnly) {
        for (int index = 0; index < WIDTH * HEIGHT; index++) {
            if (unsigned(nearOnly, index * 4 + 2) > 20
                    && unsigned(farOnly, index * 4) > 20) {
                return index;
            }
        }
        return -1;
    }

    private static int unsigned(byte[] pixels, int index) {
        return pixels[index] & 0xFF;
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(WIDTH, HEIGHT)
                .title("Hair Strand GL Test").visible(false).build();
    }
}
