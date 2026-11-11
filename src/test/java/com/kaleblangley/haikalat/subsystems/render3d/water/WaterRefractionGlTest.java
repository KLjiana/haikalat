package com.kaleblangley.haikalat.subsystems.render3d.water;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalFrameState;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_ALWAYS;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;

/**
 * Real-GL acceptance for the screen-space water refraction path.
 *
 * <p>Drives the production {@link WaterSurfacePass} directly with its companion
 * background-copy contract: a known procedural gradient and an explicitly
 * written depth buffer stand in for the opaque scene, and the shifted lookup is
 * compared with the independent double reference in
 * {@link WaterRefractionReferenceMath}.  Cases cover the IOR/thickness offset,
 * the byte-identical disabled baseline, foreground/thin-water/off-screen
 * fallbacks, finite screen edges and time-varying normals.  The class is
 * guarded by {@code -Dhaikalat.glSmoke=true}; it is not executed by ordinary
 * JVM test runs.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class WaterRefractionGlTest {
    private static final int SIZE = 128;
    private static final float FIELD_OF_VIEW = 60.0f;
    private static final float NEAR_PLANE = CameraProjection.NEAR_PLANE;
    private static final float FAR_PLANE = CameraProjection.FAR_PLANE;
    private static final Vector3f CAMERA = new Vector3f(0.0f, 4.0f, 0.0f);
    private static final Vector3f TARGET = new Vector3f(0.0f, 0.0f, -10.0f);
    private static final Matrix4f PROJECTION = new Matrix4f()
            .perspective((float) Math.toRadians(FIELD_OF_VIEW), 1.0f, NEAR_PLANE, FAR_PLANE);
    private static final Matrix4f VIEW = new Matrix4f()
            .lookAt(CAMERA, TARGET, new Vector3f(0.0f, 1.0f, 0.0f));
    private static final double OFFSET_PIXEL_X = 40;
    private static final double OFFSET_PIXEL_Y = 50;
    private static final int THIN_PIXEL_X = SIZE / 2;
    private static final int THIN_PIXEL_Y = SIZE / 2;
    private static final double EDGE_PIXEL_X = 20;
    private static final double EDGE_PIXEL_Y = 5;

    @Test
    void refractionOffsetMatchesTheIndependentReference() {
        try (Fixture fixture = new Fixture()) {
            float deepDepth = fixture.depthForCentreSceneY(-1.0f);
            fixture.renderSource(0, deepDepth, deepDepth, deepDepth, 0.0f);
            fixture.copyBackground();
            float[] sourceColour = fixture.readColour(fixture.source);
            float[] sourceDepth = fixture.readDepth(fixture.source);

            WaterSurfaceSettings water = water(WATER_AMPLITUDE, WAVE_DIRECTION);
            WaterRefractionSettings refraction = refraction(
                    WaterRefractionSettings.OffscreenFallback.UNREFRACTED, 0.20f);
            fixture.renderWater(water, refraction, 0.0f);
            float[] actual = fixture.readColour(fixture.target);

            WaterRefractionReferenceMath.Evaluation evaluation =
                    WaterRefractionReferenceMath.evaluate(water, refraction, PROJECTION, VIEW,
                            CAMERA, SIZE, SIZE, sourceDepth, (int) OFFSET_PIXEL_X,
                            (int) OFFSET_PIXEL_Y, FAR_PLANE, 0.0);
            assertTrue(evaluation.water(), "the fixture pixel must be covered by water");
            assertEquals(WaterRefractionReferenceMath.FallbackReason.NONE,
                    evaluation.fallback(), "the offset fixture must stay inside the snapshot");
            double offsetPixelsX = evaluation.offset().x() * SIZE;
            double offsetPixelsY = evaluation.offset().y() * SIZE;
            assertTrue(Math.abs(offsetPixelsX) > 1.0,
                    "the fixture must shift the lookup horizontally, got " + offsetPixelsX);
            assertTrue(offsetPixelsY < -8.0,
                    "the fixture must shift the lookup downward, got " + offsetPixelsY);
            assertTrue(evaluation.offset().length() <= refraction.maximumScreenOffset() + 1.0e-9,
                    "the reference offset must respect the configured UV bound");

            Vector3f expected = WaterRefractionReferenceMath.composite(water, refraction,
                    evaluation, CAMERA, sourceColour, SIZE, SIZE);
            assertPixel(expected, actual, (int) OFFSET_PIXEL_X, (int) OFFSET_PIXEL_Y, 3.0e-3f,
                    "refracted background");
            Vector3f unrefracted = fixture.unrefractedComposite(water, sourceColour,
                    evaluation, 0.0);
            assertTrue(maxChannelDifference(expected, unrefracted) > 8.0e-3f,
                    "the fixture gradient must make the screen-space shift observable");
            GlDebug.checkError("water refraction offset");
        }
    }

    @Test
    void disabledRefractionMatchesTheWaterSurfaceBaselineByteForByte() {
        try (Fixture fixture = new Fixture()) {
            float deepDepth = fixture.depthForCentreSceneY(-1.0f);
            fixture.renderSource(0, deepDepth, deepDepth, deepDepth, 0.0f);
            float[] baseline = fixture.readBaselineColour(water(WATER_AMPLITUDE, WAVE_DIRECTION),
                    0.0f);
            fixture.renderWaterLegacy(water(WATER_AMPLITUDE, WAVE_DIRECTION), 0.0f);
            float[] legacy = fixture.readColour(fixture.target);
            fixture.copyBackground();
            fixture.renderWater(water(WATER_AMPLITUDE, WAVE_DIRECTION),
                    WaterRefractionSettings.disabled(), 0.0f);
            float[] disabled = fixture.readColour(fixture.target);

            assertArrayEqualsExact(baseline, legacy,
                    "the pre-refraction pass and the baseline fixture must agree exactly");
            assertArrayEqualsExact(baseline, disabled,
                    "WaterRefractionSettings.disabled() must reproduce the #14 baseline exactly");
            GlDebug.checkError("water refraction disabled baseline");
        }
    }

    @Test
    void foregroundThinWaterAndOffscreenUseTheConfiguredFallback() {
        try (Fixture fixture = new Fixture()) {
            WaterSurfaceSettings flat = water(0.0f, 0.0f);
            WaterRefractionSettings unrefracted = refraction(
                    WaterRefractionSettings.OffscreenFallback.UNREFRACTED, 0.20f);
            float deepDepth = fixture.depthForCentreSceneY(-1.0f);

            // Foreground: the shifted pixel contains a surface above the water plane.
            fixture.renderSource(1, deepDepth, 0.90f, deepDepth, 45.0f);
            fixture.copyBackground();
            float[] foregroundColour = fixture.readColour(fixture.source);
            float[] foregroundDepth = fixture.readDepth(fixture.source);
            fixture.renderWater(flat, unrefracted, 0.0f);
            float[] foregroundActual = fixture.readColour(fixture.target);
            WaterRefractionReferenceMath.Evaluation foreground =
                    WaterRefractionReferenceMath.evaluate(flat, unrefracted, PROJECTION, VIEW,
                            CAMERA, SIZE, SIZE, foregroundDepth, (int) OFFSET_PIXEL_X,
                            (int) OFFSET_PIXEL_Y, FAR_PLANE, 0.0);
            assertTrue(foreground.water());
            assertEquals(WaterRefractionReferenceMath.FallbackReason.FOREGROUND,
                    foreground.fallback(), "a nearer shifted surface must reject refraction");
            Vector3f foregroundExpected = WaterRefractionReferenceMath.composite(flat,
                    unrefracted, foreground, CAMERA, foregroundColour, SIZE, SIZE);
            assertPixel(foregroundExpected, foregroundActual, (int) OFFSET_PIXEL_X,
                    (int) OFFSET_PIXEL_Y, 3.0e-3f, "foreground fallback");
            Vector3f foregroundWrong = fixture.compositeAt(flat, foregroundColour, foreground,
                    foreground.offset().shiftedX(), foreground.offset().shiftedY(), 0.0);
            assertTrue(maxChannelDifference(foregroundExpected, foregroundWrong) > 1.0e-2f,
                    "the foreground fixture must make the rejected lookup observable");

            // Thin water: the offset collapses with the vertical thickness.
            float thinDepth = fixture.depthForCentreSceneY(-0.02f);
            fixture.renderSource(0, thinDepth, thinDepth, thinDepth, 0.0f);
            fixture.copyBackground();
            float[] thinColour = fixture.readColour(fixture.source);
            float[] thinDepthBuffer = fixture.readDepth(fixture.source);
            fixture.renderWater(flat, unrefracted, 0.0f);
            float[] thinActual = fixture.readColour(fixture.target);
            WaterRefractionReferenceMath.Evaluation thin =
                    WaterRefractionReferenceMath.evaluate(flat, unrefracted, PROJECTION, VIEW,
                            CAMERA, SIZE, SIZE, thinDepthBuffer, THIN_PIXEL_X,
                            THIN_PIXEL_Y, FAR_PLANE, 0.0);
            assertTrue(thin.water());
            assertEquals(WaterRefractionReferenceMath.FallbackReason.NONE, thin.fallback());
            assertTrue(thin.offset().length() * SIZE < 0.5,
                    "a two-centimetre water column must not translate the lookup");
            Vector3f thinExpected = WaterRefractionReferenceMath.composite(flat, unrefracted,
                    thin, CAMERA, thinColour, SIZE, SIZE);
            assertPixel(thinExpected, thinActual, THIN_PIXEL_X, THIN_PIXEL_Y,
                    3.0e-3f, "thin-water transition");
            Vector3f thinUnrefracted = fixture.unrefractedComposite(flat, thinColour, thin, 0.0);
            assertTrue(maxChannelDifference(thinExpected, thinUnrefracted) < 1.5e-3f,
                    "thin water must stay visually close to the unrefracted background");

            // Off-screen: the shifted lookup leaves the bottom of the frame.
            fixture.renderSource(0, deepDepth, deepDepth, deepDepth, 0.0f);
            fixture.copyBackground();
            float[] edgeColour = fixture.readColour(fixture.source);
            float[] edgeDepth = fixture.readDepth(fixture.source);
            WaterSurfaceSettings transparent = water(0.0f, 0.0f, 0.0f);
            fixture.renderWater(transparent, unrefracted, 0.0f);
            float[] edgeActual = fixture.readColour(fixture.target);
            WaterRefractionReferenceMath.Evaluation offscreen =
                    WaterRefractionReferenceMath.evaluate(transparent, unrefracted, PROJECTION,
                            VIEW, CAMERA, SIZE, SIZE, edgeDepth, (int) EDGE_PIXEL_X,
                            (int) EDGE_PIXEL_Y, FAR_PLANE, 0.0);
            assertTrue(offscreen.water());
            assertEquals(WaterRefractionReferenceMath.FallbackReason.OUTSIDE_SCREEN,
                    offscreen.fallback(), "the bottom-edge lookup must leave the frame");
            Vector3f offscreenExpected = WaterRefractionReferenceMath.composite(transparent,
                    unrefracted, offscreen, CAMERA, edgeColour, SIZE, SIZE);
            assertPixel(offscreenExpected, edgeActual, (int) EDGE_PIXEL_X, (int) EDGE_PIXEL_Y,
                    3.0e-3f, "off-screen UNREFRACTED fallback");
            Vector3f wrongEdge = fixture.compositeAt(transparent, edgeColour, offscreen,
                    offscreen.offset().shiftedX(),
                    Math.max(0.0, Math.min(1.0, offscreen.offset().shiftedY())), 0.0);
            assertTrue(maxChannelDifference(offscreenExpected, wrongEdge) > 1.0e-2f,
                    "the fallback must not sample the clamped screen edge");

            WaterRefractionSettings tinted = WaterRefractionSettings.builder()
                    .enabled(true).ior(1.333f).strength(1.0f)
                    .maximumScreenOffset(0.20f).thicknessScale(1.0f)
                    .foregroundRejectionBias(0.05f)
                    .offscreenFallback(WaterRefractionSettings.OffscreenFallback.TINT)
                    .offscreenFallbackColor(0.9f, 0.1f, 0.7f).build();
            fixture.renderWater(transparent, tinted, 0.0f);
            float[] tintedActual = fixture.readColour(fixture.target);
            Vector3f tintedExpected = WaterRefractionReferenceMath.composite(transparent,
                    tinted, offscreen, CAMERA, edgeColour, SIZE, SIZE);
            assertPixel(tintedExpected, tintedActual, (int) EDGE_PIXEL_X, (int) EDGE_PIXEL_Y,
                    3.0e-3f, "off-screen TINT fallback");
            assertTrue(maxChannelDifference(tintedExpected, offscreenExpected) > 0.1f,
                    "the two fallback modes must be observably different");
            GlDebug.checkError("water refraction fallbacks");
        }
    }

    @Test
    void screenEdgesStayBoundedAndFinite() {
        try (Fixture fixture = new Fixture()) {
            float deepDepth = fixture.depthForCentreSceneY(-1.0f);
            fixture.renderSource(0, deepDepth, deepDepth, deepDepth, 0.0f);
            fixture.copyBackground();
            float[] sourceColour = fixture.readColour(fixture.source);
            float[] sourceDepth = fixture.readDepth(fixture.source);
            WaterSurfaceSettings water = water(WATER_AMPLITUDE, WAVE_DIRECTION);
            WaterRefractionSettings refraction = refraction(
                    WaterRefractionSettings.OffscreenFallback.UNREFRACTED, 0.20f);
            fixture.renderWater(water, refraction, 0.0f);
            float[] actual = fixture.readColour(fixture.target);

            for (int index = 0; index < actual.length; index++) {
                assertTrue(Float.isFinite(actual[index]),
                        "refracted water must stay finite at component " + index);
                assertTrue(Math.abs(actual[index]) < 100.0f,
                        "refracted water must stay bounded at component " + index);
            }
            List<int[]> boundary = List.of(new int[]{0, 0}, new int[]{0, 63},
                    new int[]{0, 126}, new int[]{63, 0}, new int[]{63, 127},
                    new int[]{126, 0}, new int[]{126, 63}, new int[]{126, 127});
            int compared = 0;
            for (int[] pixel : boundary) {
                WaterRefractionReferenceMath.Evaluation evaluation =
                        WaterRefractionReferenceMath.evaluate(water, refraction, PROJECTION,
                                VIEW, CAMERA, SIZE, SIZE, sourceDepth, pixel[0], pixel[1],
                                FAR_PLANE, 0.0);
                if (!evaluation.water() || evaluation.thickness() < 0.05) continue;
                assertTrue(evaluation.offset().length() <= refraction.maximumScreenOffset()
                                + 1.0e-9,
                        "boundary offset must stay within the configured UV bound");
                if (evaluation.fallback() == WaterRefractionReferenceMath.FallbackReason.NONE) {
                    assertTrue(evaluation.offset().shiftedX() >= 0.0
                                    && evaluation.offset().shiftedX() <= 1.0
                                    && evaluation.offset().shiftedY() >= 0.0
                                    && evaluation.offset().shiftedY() <= 1.0,
                            "a non-fallback lookup must stay inside the frame");
                }
                Vector3f expected = WaterRefractionReferenceMath.composite(water, refraction,
                        evaluation, CAMERA, sourceColour, SIZE, SIZE);
                assertPixel(expected, actual, pixel[0], pixel[1], 5.0e-3f,
                        "screen-edge pixel " + pixel[0] + "," + pixel[1]);
                compared++;
            }
            assertTrue(compared >= 3, "the fixture must cover several water boundary pixels");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void timeVaryingNormalsKeepTheOffsetBounded() {
        try (Fixture fixture = new Fixture()) {
            float deepDepth = fixture.depthForCentreSceneY(-1.0f);
            fixture.renderSource(0, deepDepth, deepDepth, deepDepth, 0.0f);
            fixture.copyBackground();
            float[] sourceColour = fixture.readColour(fixture.source);
            float[] sourceDepth = fixture.readDepth(fixture.source);
            WaterSurfaceSettings water = water(WATER_AMPLITUDE, WAVE_DIRECTION);
            WaterRefractionSettings refraction = refraction(
                    WaterRefractionSettings.OffscreenFallback.UNREFRACTED, 0.20f);

            fixture.renderWater(water, refraction, 0.0f);
            float[] first = fixture.readColour(fixture.target);
            WaterRefractionReferenceMath.Evaluation firstEvaluation =
                    WaterRefractionReferenceMath.evaluate(water, refraction, PROJECTION, VIEW,
                            CAMERA, SIZE, SIZE, sourceDepth, (int) OFFSET_PIXEL_X,
                            (int) OFFSET_PIXEL_Y, FAR_PLANE, 0.0);
            Vector3f firstExpected = WaterRefractionReferenceMath.composite(water, refraction,
                    firstEvaluation, CAMERA, sourceColour, SIZE, SIZE);
            assertPixel(firstExpected, first, (int) OFFSET_PIXEL_X, (int) OFFSET_PIXEL_Y,
                    3.0e-3f, "refraction at t=0");

            fixture.renderWater(water, refraction, 0.9f);
            float[] later = fixture.readColour(fixture.target);
            WaterRefractionReferenceMath.Evaluation laterEvaluation =
                    WaterRefractionReferenceMath.evaluate(water, refraction, PROJECTION, VIEW,
                            CAMERA, SIZE, SIZE, sourceDepth, (int) OFFSET_PIXEL_X,
                            (int) OFFSET_PIXEL_Y, FAR_PLANE, 0.9);
            Vector3f laterExpected = WaterRefractionReferenceMath.composite(water, refraction,
                    laterEvaluation, CAMERA, sourceColour, SIZE, SIZE);
            assertPixel(laterExpected, later, (int) OFFSET_PIXEL_X, (int) OFFSET_PIXEL_Y,
                    3.0e-3f, "refraction at t=0.9");

            assertTrue(maxChannelDifference(firstExpected, laterExpected) > 1.0e-3f,
                    "moving wave normals must change the refracted lookup");
            assertTrue(firstEvaluation.offset().length() <= refraction.maximumScreenOffset()
                            + 1.0e-9,
                    "the t=0 offset must stay within the configured UV bound");
            assertTrue(laterEvaluation.offset().length() <= refraction.maximumScreenOffset()
                            + 1.0e-9,
                    "the t=0.9 offset must stay within the configured UV bound");
            for (float value : later) {
                assertTrue(Float.isFinite(value), "time-varying refraction must stay finite");
            }
            GlDebug.checkError("water refraction time variation");
        }
    }

    private static final float WATER_AMPLITUDE = 0.15f;
    private static final float WAVE_DIRECTION = 0.7f;

    private static WaterSurfaceSettings water(float amplitude, float direction) {
        return water(amplitude, direction, 0.35f);
    }

    private static WaterSurfaceSettings water(float amplitude, float direction,
                                              float absorptionDensity) {
        return WaterSurfaceSettings.builder()
                .enabled(true).level(0.0f).bounds(-20.0f, -20.0f, 20.0f, 20.0f)
                .waveWavelength(3.0f).waveSpeed(0.6f).waveAmplitude(amplitude)
                .waveDirectionRadians(direction)
                .waterColor(0.05f, 0.2f, 0.25f)
                .absorptionDensity(absorptionDensity)
                .shoreFadeDistance(0.0f)
                .reflectionFallback(0.4f, 0.5f, 0.6f)
                .build();
    }

    private static WaterRefractionSettings refraction(
            WaterRefractionSettings.OffscreenFallback fallback, float maximumOffset) {
        return WaterRefractionSettings.builder()
                .enabled(true).ior(1.333f).strength(1.0f)
                .maximumScreenOffset(maximumOffset).thicknessScale(1.0f)
                .foregroundRejectionBias(0.05f).offscreenFallback(fallback)
                .offscreenFallbackColor(0.9f, 0.1f, 0.7f).build();
    }

    private static TemporalFrameState.FrameParameters frameParameters() {
        Vector3f forward = new Vector3f(TARGET).sub(CAMERA).normalize();
        Matrix4f viewProjection = new Matrix4f(PROJECTION).mul(VIEW);
        return new TemporalFrameState.FrameParameters(0L, SIZE, SIZE, NEAR_PLANE, FAR_PLANE,
                CAMERA.x, CAMERA.y, CAMERA.z, forward.x, forward.y, forward.z, 0.0f, 0.0f,
                PROJECTION, PROJECTION, new Matrix4f(PROJECTION).invert(), VIEW,
                viewProjection, new Matrix4f(viewProjection).invert(), 0L);
    }

    private static void assertPixel(Vector3f expected, float[] actual, int x, int y,
                                    float tolerance, String message) {
        int index = (y * SIZE + x) * 4;
        assertEquals(expected.x, actual[index], tolerance, message + " red");
        assertEquals(expected.y, actual[index + 1], tolerance, message + " green");
        assertEquals(expected.z, actual[index + 2], tolerance, message + " blue");
    }

    private static float maxChannelDifference(Vector3f left, Vector3f right) {
        return Math.max(Math.abs(left.x - right.x),
                Math.max(Math.abs(left.y - right.y), Math.abs(left.z - right.z)));
    }

    private static void assertArrayEqualsExact(float[] expected, float[] actual, String message) {
        assertEquals(expected.length, actual.length, message + " length");
        for (int index = 0; index < expected.length; index++) {
            if (Float.floatToIntBits(expected[index]) != Float.floatToIntBits(actual[index])) {
                assertEquals(expected[index], actual[index], 0.0f,
                        message + " at component " + index);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private static final String SOURCE_VERTEX = """
                #version 460 core
                layout(location = 0) in vec3 aPosition;
                void main() {
                    gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                }
                """;
        private static final String SOURCE_FRAGMENT = """
                #version 460 core
                layout(location = 0) out vec4 fragColor;
                uniform int uDepthMode;
                uniform float uUniformDepth;
                uniform float uNearDepth;
                uniform float uFarDepth;
                uniform float uSplitRow;
                uniform vec2 uExtent;
                void main() {
                    vec2 uv = gl_FragCoord.xy / uExtent;
                    vec3 colour = vec3(0.1 + 0.8 * uv.x, 0.1 + 0.8 * uv.y, 0.2);
                    float depth = uUniformDepth;
                    if (uDepthMode == 1) {
                        depth = gl_FragCoord.y < uSplitRow ? uNearDepth : uFarDepth;
                    }
                    gl_FragDepth = depth;
                    fragColor = vec4(colour, 1.0);
                }
                """;

        final GlfwWindow window;
        final GlRenderDevice device = new GlRenderDevice();
        final Framebuffer source;
        final Framebuffer backgroundCopy;
        final Framebuffer target;
        final ShaderProgram sourceShader;
        final ShaderProgram baselineShader;
        final Mesh fullscreenQuad;
        final Mesh waterQuad;
        final WaterSurfacePass pass;

        Fixture() {
            window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                    .title("Water refraction GL test").visible(false).build();
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            source = Framebuffer.fromDescriptor(FramebufferDescriptor.builder(SIZE, SIZE)
                    .colorTexture(RenderFormat.RGBA16F)
                    .depthTexture()
                    .build());
            backgroundCopy = Framebuffer.fromDescriptor(
                    FramebufferDescriptor.builder(SIZE, SIZE)
                            .colorTexture(RenderFormat.RGBA16F)
                            .build());
            target = Framebuffer.fromDescriptor(FramebufferDescriptor.builder(SIZE, SIZE)
                    .colorTexture(RenderFormat.RGBA16F)
                    .build());
            sourceShader = ShaderProgram.fromSources(SOURCE_VERTEX, SOURCE_FRAGMENT);
            baselineShader = ShaderProgram.fromResource(WaterRefractionGlTest.class,
                    "/shaders/render3d/water/water-surface.vert",
                    "/shaders/waterrefr/water-surface-baseline.frag");
            VertexAttribute position = VertexAttribute.builder()
                    .index(0).size(3).offsetBytes(0L)
                    .semantic(VertexSemantic.POSITION).build();
            fullscreenQuad = Mesh.builder()
                    .vertices(new float[]{
                            -1.0f, -1.0f, 0.0f,
                            1.0f, -1.0f, 0.0f,
                            1.0f, 1.0f, 0.0f,
                            -1.0f, 1.0f, 0.0f
                    }, 3 * Float.BYTES, position)
                    .indices(new int[]{0, 1, 2, 0, 2, 3})
                    .build();
            waterQuad = Mesh.builder()
                    .vertices(new float[]{
                            -0.5f, 0.0f, -0.5f,
                            -0.5f, 0.0f, 0.5f,
                            0.5f, 0.0f, 0.5f,
                            0.5f, 0.0f, -0.5f
                    }, 3 * Float.BYTES, position)
                    .indices(new int[]{0, 1, 2, 0, 2, 3})
                    .build();
            pass = new WaterSurfacePass(1, true);
        }

        void renderSource(int depthMode, float uniformDepth, float nearDepth, float farDepth,
                          float splitRow) {
            source.bind();
            glViewport(0, 0, SIZE, SIZE);
            glDepthMask(true);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClearDepth(1.0);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            var commands = device.createCommandBuffer();
            commands.bindFramebuffer(source)
                    .viewport(0, 0, SIZE, SIZE)
                    .bindShader(sourceShader)
                    .setUniformInt(sourceShader, "uDepthMode", depthMode)
                    .setUniformFloat(sourceShader, "uUniformDepth", uniformDepth)
                    .setUniformFloat(sourceShader, "uNearDepth", nearDepth)
                    .setUniformFloat(sourceShader, "uFarDepth", farDepth)
                    .setUniformFloat(sourceShader, "uSplitRow", splitRow)
                    .setUniformVec2(sourceShader, "uExtent", SIZE, SIZE)
                    .enableDepthTest(true)
                    .depthFunc(GL_ALWAYS)
                    .depthMask(true)
                    .enableBlend(false)
                    .enableFramebufferSrgb(false)
                    .bindMesh(fullscreenQuad)
                    .drawMesh(fullscreenQuad);
            device.execute(commands);
        }

        void copyBackground() {
            var commands = device.createCommandBuffer();
            WaterSurfacePass.recordBackgroundCopy(commands, source, backgroundCopy);
            device.execute(commands);
        }

        void renderWater(WaterSurfaceSettings water, WaterRefractionSettings refraction,
                         float timeSeconds) {
            var commands = device.createCommandBuffer();
            pass.record(commands, source, backgroundCopy, target, source.depthAttachment(),
                    frameParameters(), water, refraction, null, 0.0f, 0.0f, timeSeconds);
            device.execute(commands);
        }

        void renderWaterLegacy(WaterSurfaceSettings water, float timeSeconds) {
            var commands = device.createCommandBuffer();
            pass.record(commands, source, target, source.depthAttachment(), frameParameters(),
                    water, null, 0.0f, 0.0f, timeSeconds);
            device.execute(commands);
        }

        float[] readBaselineColour(WaterSurfaceSettings water, float timeSeconds) {
            var commands = device.createCommandBuffer();
            commands.blitColor(source, target)
                    .bindFramebuffer(target)
                    .viewport(0, 0, SIZE, SIZE)
                    .bindShader(baselineShader);
            Matrix4f model = new Matrix4f().identity()
                    .translate((water.minX() + water.maxX()) * 0.5f, water.level(),
                            (water.minZ() + water.maxZ()) * 0.5f)
                    .scale(water.width(), 1.0f, water.depth());
            commands.setUniformMat4(baselineShader, "uProjection", PROJECTION)
                    .setUniformMat4(baselineShader, "uView", VIEW)
                    .setUniformMat4(baselineShader, "uModel", model)
                    .setUniformMat4(baselineShader, "uInverseProjection",
                            new Matrix4f(PROJECTION).invert())
                    .setUniformMat4(baselineShader, "uInverseView", new Matrix4f(VIEW).invert())
                    .setUniformVec3(baselineShader, "uCameraPosition", CAMERA)
                    .setUniformFloat(baselineShader, "uFarPlane", FAR_PLANE)
                    .setUniformVec3(baselineShader, "uWaterColor", water.waterColor())
                    .setUniformVec3(baselineShader, "uReflectionFallback",
                            water.reflectionFallback())
                    .setUniformFloat(baselineShader, "uAbsorptionDensity",
                            water.absorptionDensity())
                    .setUniformFloat(baselineShader, "uShoreFadeDistance",
                            water.shoreFadeDistance())
                    .setUniformFloat(baselineShader, "uWaterLevel", water.level())
                    .setUniformFloat(baselineShader, "uWaveWavelength", water.waveWavelength())
                    .setUniformFloat(baselineShader, "uWaveSpeed", water.waveSpeed())
                    .setUniformFloat(baselineShader, "uWaveAmplitude", water.waveAmplitude())
                    .setUniformFloat(baselineShader, "uWaveDirection",
                            water.waveDirectionRadians())
                    .setUniformFloat(baselineShader, "uTimeSeconds", timeSeconds)
                    .setUniformInt(baselineShader, "uDebugMode", 0)
                    .setUniformInt(baselineShader, "uSceneDepth", 0)
                    .setUniformInt(baselineShader, "uHasEnvironment", 0)
                    .bindTexture(0, source.depthAttachment())
                    .enableDepthTest(false)
                    .depthMask(false)
                    .enableCullFace(false)
                    .enableFramebufferSrgb(false)
                    .enableBlend(true)
                    .blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
                    .bindMesh(waterQuad)
                    .drawMesh(waterQuad)
                    .enableBlend(false)
                    .depthMask(true)
                    .enableDepthTest(true);
            device.execute(commands);
            return readColour(target);
        }

        float[] readColour(Framebuffer framebuffer) {
            framebuffer.bind();
            FloatBuffer buffer = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, buffer);
            float[] pixels = new float[SIZE * SIZE * 4];
            buffer.get(pixels);
            return pixels;
        }

        float[] readDepth(Framebuffer framebuffer) {
            framebuffer.bind();
            FloatBuffer buffer = BufferUtils.createFloatBuffer(SIZE * SIZE);
            glReadPixels(0, 0, SIZE, SIZE, GL_DEPTH_COMPONENT, GL_FLOAT, buffer);
            float[] pixels = new float[SIZE * SIZE];
            buffer.get(pixels);
            return pixels;
        }

        float depthForCentreSceneY(float sceneY) {
            Vector3d surface = WaterRefractionReferenceMath.waterWorld(PROJECTION, VIEW, SIZE,
                    SIZE, SIZE / 2, SIZE / 2, 0.0);
            Vector3d direction = new Vector3d(surface)
                    .sub(CAMERA.x, CAMERA.y, CAMERA.z).normalize();
            double distance = (sceneY - CAMERA.y) / direction.y;
            Vector3d scene = new Vector3d(CAMERA.x, CAMERA.y, CAMERA.z)
                    .fma(distance, direction);
            double viewX = VIEW.m00() * scene.x + VIEW.m10() * scene.y
                    + VIEW.m20() * scene.z + VIEW.m30();
            double viewY = VIEW.m01() * scene.x + VIEW.m11() * scene.y
                    + VIEW.m21() * scene.z + VIEW.m31();
            double viewZ = VIEW.m02() * scene.x + VIEW.m12() * scene.y
                    + VIEW.m22() * scene.z + VIEW.m32();
            double depth = WaterRefractionReferenceMath.deviceDepth(PROJECTION,
                    viewX, viewY, viewZ);
            if (!Double.isFinite(depth) || depth <= 0.0 || depth >= 1.0) {
                throw new IllegalStateException(
                        "fixture depth must be inside the depth range, got " + depth);
            }
            return (float) depth;
        }

        Vector3f unrefractedComposite(WaterSurfaceSettings water, float[] sourceColour,
                                      WaterRefractionReferenceMath.Evaluation evaluation,
                                      double timeSeconds) {
            return compositeAt(water, sourceColour, evaluation, evaluation.centreX(),
                    evaluation.centreY(), timeSeconds);
        }

        Vector3f compositeAt(WaterSurfaceSettings water, float[] sourceColour,
                             WaterRefractionReferenceMath.Evaluation evaluation,
                             double u, double v, double timeSeconds) {
            Vector3f background = WaterRefractionReferenceMath.sampleBilinear(sourceColour,
                    SIZE, SIZE, u, v);
            WaterRefractionReferenceMath.Shade shade = WaterRefractionReferenceMath.shade(water,
                    new Vector3f(evaluation.world()), new Vector3f(evaluation.normal()),
                    CAMERA, evaluation.thickness());
            return background.lerp(shade.colour(), (float) shade.alpha());
        }

        @Override
        public void close() {
            try {
                pass.close();
            } finally {
                try {
                    waterQuad.close();
                    fullscreenQuad.close();
                    baselineShader.close();
                    sourceShader.close();
                } finally {
                    try {
                        target.close();
                        backgroundCopy.close();
                        source.close();
                    } finally {
                        window.close();
                    }
                }
            }
        }
    }
}
