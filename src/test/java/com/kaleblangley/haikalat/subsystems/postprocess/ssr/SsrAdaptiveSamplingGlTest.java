package com.kaleblangley.haikalat.subsystems.postprocess.ssr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.AdaptiveSamplingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RENDERER;
import static org.lwjgl.opengl.GL11.GL_VENDOR;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;

/**
 * Real-GL acceptance for adaptive sampling bound to the screen-space
 * reflection march (#41 / #20).
 *
 * <p>Every case renders the production {@link SsrPass} variant against an
 * analytic fixture, so the measured budget can be read from the default-off
 * diagnostic alpha ({@code budget / maximumSteps}) without a second target.
 * The cases cover: disabled byte identity, the minimum-sample floor, monotone
 * tile allocation on a normal step, the per-pixel coarse-march residual, a
 * cost-matched fixed-budget oracle comparison against the high-sample fixed
 * path, odd/partial extents and border rays, the degenerate {@code min == max}
 * case, close idempotence, and the colour-chain wiring.</p>
 *
 * <p>This class is guarded by {@code -Dhaikalat.glSmoke=true}; a plain
 * {@code test} run skips it and a skip is not a PASS.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SsrAdaptiveSamplingGlTest {
    private static final int SIZE = 96;
    private static final int ODD_WIDTH = 100;
    private static final int ODD_HEIGHT = 76;
    private static final Vector3f EYE = new Vector3f(0.0f, 1.6f, 4.0f);
    private static final Vector3f CENTRE = new Vector3f(0.0f, 1.0f, -3.0f);
    private static final Vector3f UP = new Vector3f(0.0f, 1.0f, 0.0f);
    private static final float FIELD_OF_VIEW = (float) Math.toRadians(55.0);
    private static final float WALL_Z = -3.0f;
    private static final float WALL_HALF_WIDTH = 1.2f;
    private static final float WALL_HEIGHT = 2.0f;
    private static final float FLOOR_ROUGHNESS = 0.05f;
    private static final float WALL_ROUGHNESS = 0.9f;
    private static final float SKY_ROUGHNESS = 1.0f;
    private static final float ROUGHNESS_CUTOFF = 0.4f;
    private static final Vector3f FLOOR_COLOR = new Vector3f(0.05f, 0.06f, 0.07f);
    private static final Vector3f WALL_COLOR = new Vector3f(32.0f, 0.2f, 0.1f);
    private static final Vector3f SKY_COLOR = new Vector3f(0.01f, 0.02f, 0.03f);
    private static final Vector3f PLANE_COLOR = new Vector3f(0.2f, 0.3f, 0.4f);
    private static final float PLANE_DEPTH = 2.0f;

    private static final int MINIMUM_STEPS = 8;
    private static final int MAXIMUM_STEPS = 32;
    private static final int TILE_SIZE = 8;
    private static final float MINIMUM_ALPHA = (float) MINIMUM_STEPS / MAXIMUM_STEPS;

    @Test
    void disabledAdaptiveRequestKeepsTheFixedShaderByteIdentical() {
        withContext("Adaptive SSR disabled", (device, environment) -> {
            Fixture fixture = floorWallFixture(SIZE, SIZE, environment);
            try (SsrPass baseline = new SsrPass(fixed(48));
                 SsrPass disabled = new SsrPass(fixed(48),
                         AdaptiveSamplingSettings.disabled())) {
                assertFalse(disabled.adaptiveSettings().active());
                float[] expected = render(device, baseline, fixture, false);
                float[] actual = render(device, disabled, fixture, false);
                assertArrayEquals(expected, actual, 0.0f,
                        "a disabled adaptive request must be the exact fixed shader output");
            }
        });
    }

    @Test
    void minimumSampleFloorIsSpentOnAFlatPlane() {
        withContext("Adaptive SSR floor", (device, environment) -> {
            Fixture fixture = planeFixture(SIZE, SIZE, new Vector3f(0.0f, 0.0f, 1.0f),
                    new Vector3f(0.0f, 0.0f, 1.0f), SIZE, environment);
            try (SsrPass adaptive = new SsrPass(fixed(MAXIMUM_STEPS),
                    adaptive(MINIMUM_STEPS, MAXIMUM_STEPS, TILE_SIZE));
                 SsrPass fixedFloor = new SsrPass(fixed(MINIMUM_STEPS))) {
                float[] output = render(device, adaptive, fixture, true);
                for (int index = 0; index < SIZE * SIZE; index++) {
                    assertTrue(fixture.eligible()[index],
                            "the constant plane must be eligible at pixel " + index);
                    assertEquals(MINIMUM_ALPHA, alpha(output, index), 1.0e-6f,
                            "a zero-error pixel must spend only the minimum-sample floor at "
                                    + index);
                }
                assertArrayEquals(rgb(fixture.colour()), rgb(output), 0.0f,
                        "a plane without a screen-space hit must keep the input colour exactly");
                float[] floorOutput = render(device, fixedFloor, fixture, false);
                assertArrayEquals(rgb(output), rgb(floorOutput), 0.0f,
                        "the floor budget must equal the fixed minimum-sample march");
            }
        });
    }

    @Test
    void tileBudgetIsMonotoneInTheMeasuredNormalSpread() {
        withContext("Adaptive SSR tiles", (device, environment) -> {
            Fixture flat = planeFixture(SIZE, SIZE, new Vector3f(0.0f, 0.0f, 1.0f),
                    new Vector3f(0.0f, 0.0f, 1.0f), SIZE, environment);
            Fixture small = planeFixture(SIZE, SIZE, new Vector3f(0.0f, 0.0f, 1.0f),
                    tiltedNormal(35.0f), 44, environment);
            Fixture large = planeFixture(SIZE, SIZE, new Vector3f(0.0f, 0.0f, 1.0f),
                    tiltedNormal(60.0f), 44, environment);
            // Isolate the tile term: the widest accepted residual scales make the
            // per-pixel term small, so the observed allocation is the tile budget.
            AdaptiveSamplingSettings tileOnly = AdaptiveSamplingSettings.builder()
                    .enabled(true)
                    .minimumSteps(MINIMUM_STEPS)
                    .maximumSteps(MAXIMUM_STEPS)
                    .tileSizePixels(TILE_SIZE)
                    .errorThreshold(0.10f)
                    .screenStepScalePixels(64.0f)
                    .depthJumpScale(8.0f)
                    .build();
            try (SsrPass adaptive = new SsrPass(fixed(MAXIMUM_STEPS), tileOnly)) {
                float[] flatOutput = render(device, adaptive, flat, true);
                float[] smallOutput = render(device, adaptive, small, true);
                float[] largeOutput = render(device, adaptive, large, true);
                float flatMax = maximumAlpha(flatOutput, flat);
                float smallMax = maximumAlpha(smallOutput, small);
                float largeMax = maximumAlpha(largeOutput, large);
                assertEquals(MINIMUM_ALPHA, flatMax, 1.0e-6f,
                        "a uniform-normal plane must allocate only the floor");
                assertEquals(15.0f / MAXIMUM_STEPS, smallMax, 1.0e-6f,
                        "a 35-degree normal step must allocate the documented 15 steps");
                assertEquals(1.0f, largeMax, 1.0e-6f,
                        "a 60-degree normal step must allocate the ceiling");
                assertTrue(flatMax < smallMax && smallMax < largeMax,
                        "tile budget must grow monotonically with the measured spread: "
                                + flatMax + " < " + smallMax + " < " + largeMax);
                assertTileUniform(flatOutput, flat);
                assertTileUniform(smallOutput, small);
                // The 60-degree region reflects into the surface and can add a
                // per-pixel residual on top of the tile term, so only the step
                // tile itself is asserted uniform; it is the tile whose measured
                // normal spread reaches the ceiling.
                assertTileAlpha(largeOutput, large, 40, 0, 1.0f);
                for (float alpha : eligibleAlphas(largeOutput, large)) {
                    assertTrue(alpha >= MINIMUM_ALPHA - 1.0e-6f
                                    && alpha <= 1.0f + 1.0e-6f,
                            "every tile budget must stay inside [minimum, maximum]");
                }
            }
        });
    }

    @Test
    void pixelBudgetRisesWithTheMeasuredCoarseMarchResidual() {
        withContext("Adaptive SSR residual", (device, environment) -> {
            Fixture fixture = floorWallFixture(SIZE, SIZE, environment);
            try (SsrPass adaptive = new SsrPass(fixed(MAXIMUM_STEPS),
                    adaptive(MINIMUM_STEPS, MAXIMUM_STEPS, TILE_SIZE))) {
                float[] output = render(device, adaptive, fixture, true);
                int eligible = 0;
                int atFloor = 0;
                int atCeiling = 0;
                for (int index = 0; index < SIZE * SIZE; index++) {
                    if (!fixture.eligible()[index]) continue;
                    eligible++;
                    int budget = budget(output, index, MAXIMUM_STEPS);
                    if (budget == MINIMUM_STEPS) atFloor++;
                    if (budget == MAXIMUM_STEPS) atCeiling++;
                }
                assertTrue(eligible > 400, "the fixture must have a meaningful floor region");
                assertTrue(atFloor >= 20,
                        "converged pixels must keep the floor, got " + atFloor);
                assertTrue(atCeiling >= 20,
                        "coarse-march crossings must earn the ceiling, got " + atCeiling);
                assertTrue(atCeiling < eligible * 3 / 4,
                        "adaptive allocation must be sparse, got " + atCeiling + "/" + eligible);
            }
        });
    }

    @Test
    void fixedBudgetOracleComparisonStaysCloserThanTheFloorAndTheMean() {
        withContext("Adaptive SSR oracle", (device, environment) -> {
            Fixture fixture = floorWallFixture(SIZE, SIZE, environment);
            try (SsrPass oraclePass = new SsrPass(fixed(64));
                 SsrPass floorPass = new SsrPass(fixed(MINIMUM_STEPS));
                 SsrPass adaptive = new SsrPass(fixed(MAXIMUM_STEPS),
                         adaptive(MINIMUM_STEPS, MAXIMUM_STEPS, TILE_SIZE))) {
                float[] oracle = render(device, oraclePass, fixture, false);
                float[] floor = render(device, floorPass, fixture, false);
                float[] adaptiveOutput = render(device, adaptive, fixture, true);
                int meanBudget = meanBudget(adaptiveOutput, fixture, MAXIMUM_STEPS);
                try (SsrPass controlPass = new SsrPass(fixed(meanBudget))) {
                    float[] control = render(device, controlPass, fixture, false);
                    boolean[] reflection = oracleReflectionMask(oracle, fixture);
                    int reflectionPixels = count(reflection);
                    assertTrue(reflectionPixels > 50,
                            "the fixture must contain a meaningful reflection region, got "
                                    + reflectionPixels);

                    double adaptiveError = meanRgbError(adaptiveOutput, oracle, reflection);
                    double floorError = meanRgbError(floor, oracle, reflection);
                    double controlError = meanRgbError(control, oracle, reflection);
                    assertTrue(Double.isFinite(adaptiveError)
                                    && Double.isFinite(floorError)
                                    && Double.isFinite(controlError));
                    assertTrue(adaptiveError <= floorError + 1.0e-6,
                            "extra budget must never be worse than the floor: adaptive "
                                    + adaptiveError + " floor " + floorError);
                    assertTrue(adaptiveError <= controlError * 1.05,
                            "the adaptive result must not lose to the cost-matched fixed "
                                    + "control: adaptive " + adaptiveError + " control "
                                    + controlError);

                    boolean[] hard = new boolean[reflection.length];
                    for (int index = 0; index < hard.length; index++) {
                        hard[index] = reflection[index]
                                && budget(adaptiveOutput, index, MAXIMUM_STEPS)
                                == MAXIMUM_STEPS;
                    }
                    assertTrue(count(hard) >= 10,
                            "the fixture must expose pixels that earn the ceiling, got "
                                    + count(hard));
                    double hardAdaptive = meanRgbError(adaptiveOutput, oracle, hard);
                    double hardFloor = meanRgbError(floor, oracle, hard);
                    double hardControl = meanRgbError(control, oracle, hard);
                    assertTrue(hardAdaptive < hardFloor - 1.0e-4,
                            "the ceiling must beat the floor on the hard region: adaptive "
                                    + hardAdaptive + " floor " + hardFloor);
                    assertTrue(hardAdaptive < hardControl,
                            "the ceiling must beat the cost-matched fixed control on the hard "
                                    + "region: adaptive " + hardAdaptive + " control "
                                    + hardControl);
                    assertFinite(oracle);
                    assertFinite(floor);
                    assertFinite(control);
                    assertFinite(adaptiveOutput);
                }
            }
        });
    }

    @Test
    void edgeAndPartialTilesStayBoundedForAnOddExtent() {
        withContext("Adaptive SSR odd extent", (device, environment) -> {
            Fixture fixture = floorWallFixture(ODD_WIDTH, ODD_HEIGHT, environment);
            try (SsrPass adaptive = new SsrPass(fixed(MAXIMUM_STEPS),
                    adaptive(MINIMUM_STEPS, MAXIMUM_STEPS, TILE_SIZE))) {
                float[] output = render(device, adaptive, fixture, true);
                assertFinite(output);
                int eligible = 0;
                int rightPartialTile = 0;
                int bottomPartialRow = 0;
                for (int y = 0; y < ODD_HEIGHT; y++) {
                    for (int x = 0; x < ODD_WIDTH; x++) {
                        int index = y * ODD_WIDTH + x;
                        if (!fixture.eligible()[index]) continue;
                        eligible++;
                        float alpha = alpha(output, index);
                        assertTrue(alpha >= MINIMUM_ALPHA - 1.0e-6f
                                        && alpha <= 1.0f + 1.0e-6f,
                                "border/partial tile budget out of range at " + x + "," + y
                                        + ": " + alpha);
                        if (x >= ODD_WIDTH - TILE_SIZE) rightPartialTile++;
                        if (y < TILE_SIZE) bottomPartialRow++;
                    }
                }
                assertTrue(eligible > 400);
                assertTrue(rightPartialTile > 20,
                        "the partial right tile column must still be shaded");
                assertTrue(bottomPartialRow > 20,
                        "the partial bottom tile row must still be shaded");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        });
    }

    @Test
    void minimumEqualsMaximumDegeneratesToTheFixedBudget() {
        withContext("Adaptive SSR degenerate", (device, environment) -> {
            Fixture fixture = floorWallFixture(SIZE, SIZE, environment);
            try (SsrPass fixedPass = new SsrPass(fixed(24));
                 SsrPass degenerate = new SsrPass(fixed(24),
                         adaptive(24, 24, TILE_SIZE))) {
                float[] expected = render(device, fixedPass, fixture, false);
                float[] actual = render(device, degenerate, fixture, true);
                assertArrayEquals(rgb(expected), rgb(actual), 0.0f,
                        "min == max must be the exact fixed-budget march");
                for (int index = 0; index < SIZE * SIZE; index++) {
                    if (!fixture.eligible()[index]) continue;
                    assertEquals(1.0f, alpha(actual, index), 1.0e-6f,
                            "the degenerate budget is the ceiling at " + index);
                }
            }
        });
    }

    @Test
    void closeIsIdempotentAndLeavesNoTrackedResources() {
        withContext("Adaptive SSR close", (device, environment) -> {
            Fixture fixture = floorWallFixture(SIZE, SIZE, environment);
            int baseline = GlDebug.resources().liveResources().size();
            SsrPass pass = new SsrPass(fixed(MAXIMUM_STEPS),
                    adaptive(MINIMUM_STEPS, MAXIMUM_STEPS, TILE_SIZE));
            try {
                assertFinite(render(device, pass, fixture, true));
                assertFalse(pass.isClosed());
            } finally {
                pass.close();
            }
            assertTrue(pass.isClosed());
            pass.close();
            pass.close();
            assertEquals(baseline, GlDebug.resources().liveResources().size(),
                    "the adaptive program and quad must be released exactly once");
            assertThrows(GlException.class, () -> render(device, pass, fixture, false),
                    "a closed pass must reject recording");

            Fixture secondExtent = floorWallFixture(61, 97, environment);
            try (SsrPass second = new SsrPass(fixed(MAXIMUM_STEPS),
                    adaptive(MINIMUM_STEPS, MAXIMUM_STEPS, TILE_SIZE))) {
                assertFinite(render(device, second, secondExtent, true));
            }
            assertEquals(baseline, GlDebug.resources().liveResources().size(),
                    "a second generation must not leak resources");
        });
    }

    @Test
    void pipelineKeepsTheSsrChainAndRejectsAdaptiveWithoutItsConsumer() {
        withContext("Adaptive SSR pipeline", (device, environment) -> {
            RenderSettings settings = RenderSettings.builder()
                    .antiAliasingMode(AntiAliasingMode.NONE)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .vsync(false)
                    .build();
            PostProcessSettings enabled = PostProcessSettings.defaults()
                    .withSsr(SsrSettings.defaults())
                    .withAdaptiveSampling(AdaptiveSamplingSettings.defaults());
            try (PostProcessPipeline pipeline = PostProcessPipeline.create(settings, enabled,
                    SIZE, SIZE, null, SSR_SURFACE, null, NO_ENVIRONMENT);
                 RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> {
                        });
                pipeline.addFinalPass(graph);
                pipeline.validateSurfaceInputs();
                assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS,
                                PostProcessTargets.SSR_PASS,
                                PostProcessTargets.TONE_MAPPING_PASS,
                                PostProcessTargets.PRESENT_PASS),
                        graph.description().executionOrder(),
                        "adaptive sampling must not add or reorder a colour-chain pass");
                assertTrue(graph.hasPass(PostProcessTargets.SSR_PASS));
                assertEquals(PostProcessTargets.SSR_COLOR, pipeline.linearHdrTextureName());
            }

            PostProcessSettings disabledAdaptive = PostProcessSettings.defaults()
                    .withSsr(SsrSettings.defaults())
                    .withAdaptiveSampling(AdaptiveSamplingSettings.disabled());
            try (PostProcessPipeline pipeline = PostProcessPipeline.create(settings,
                    disabledAdaptive, SIZE, SIZE, null, SSR_SURFACE, null, NO_ENVIRONMENT);
                 RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> {
                        });
                pipeline.addFinalPass(graph);
                assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS,
                                PostProcessTargets.SSR_PASS,
                                PostProcessTargets.TONE_MAPPING_PASS,
                                PostProcessTargets.PRESENT_PASS),
                        graph.description().executionOrder());
            }

            PostProcessSettings withoutSsr = PostProcessSettings.defaults()
                    .withAdaptiveSampling(AdaptiveSamplingSettings.defaults());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> PostProcessPipeline.create(settings, withoutSsr, SIZE, SIZE,
                            null, NO_SURFACE, null, NO_ENVIRONMENT));
            assertTrue(failure.getMessage().contains("screen-space reflection"),
                    "the rejection must name the missing consumer: " + failure.getMessage());
            assertEquals(GL_NO_ERROR, glGetError());
        });
    }

    // ------------------------------------------------------------------
    // Context, fixture and readback helpers.
    // ------------------------------------------------------------------

    private interface ContextBody {
        void run(GlRenderDevice device, SsrEnvironmentSource environment);
    }

    private static void withContext(String title, ContextBody body) {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title(title).visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            recordDevice();
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device,
                    SsrAdaptiveSamplingGlTest.class,
                    "/environments/pbr/studio-small.hdr",
                    PbrEnvironmentSettings.testQuality());
                 Sampler cubeSampler = Sampler.create(new Sampler.Descriptor(
                         GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                         GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE))) {
                body.run(device, environment(environment, cubeSampler));
            }
        }
    }

    private static SsrEnvironmentSource environment(PbrEnvironment environment,
                                                    Sampler sampler) {
        return new SsrEnvironmentSource() {
            @Override
            public float bindPrefilteredSpecular(CommandBuffer cmd, int unit) {
                cmd.bindTextureCube(unit, environment.prefilteredSpecular(), sampler);
                return environment.prefilteredSpecular().mipLevels() - 1.0f;
            }

            @Override
            public float rotationRadians() {
                return environment.rotationRadians();
            }

            @Override
            public float intensity() {
                return environment.intensity();
            }
        };
    }

    private static SsrSettings fixed(int maxSteps) {
        return SsrSettings.builder()
                .enabled(true)
                .maxDistanceMetres(12.0f)
                .maxSteps(maxSteps)
                .thicknessMetres(1.0f)
                .roughnessCutoff(ROUGHNESS_CUTOFF)
                .minimumConfidence(0.15f)
                .strength(0.9f)
                .edgeFadePixels(8.0f)
                .build();
    }

    private static AdaptiveSamplingSettings adaptive(int minimumSteps, int maximumSteps,
                                                     int tileSizePixels) {
        return AdaptiveSamplingSettings.builder()
                .enabled(true)
                .minimumSteps(minimumSteps)
                .maximumSteps(maximumSteps)
                .tileSizePixels(tileSizePixels)
                .errorThreshold(0.10f)
                .screenStepScalePixels(4.0f)
                .depthJumpScale(1.0f)
                .build();
    }

    private static Vector3f tiltedNormal(float degrees) {
        double radians = Math.toRadians(degrees);
        return new Vector3f((float) Math.sin(radians), 0.0f, (float) Math.cos(radians))
                .normalize();
    }

    /** A camera-facing plane with a vertical normal step at {@code stepColumn}. */
    private static Fixture planeFixture(int width, int height, Vector3f nearNormal,
                                        Vector3f farNormal, int stepColumn,
                                        SsrEnvironmentSource environment) {
        Matrix4f view = new Matrix4f();
        Matrix4f projection = new Matrix4f().perspective(FIELD_OF_VIEW,
                (float) width / height, 0.1f, 100.0f);
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        Matrix4f inverseView = new Matrix4f(view).invert();
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        float deviceDepth = deviceDepth(PLANE_DEPTH, projection);
        float[] colour = new float[width * height * 4];
        float[] depth = new float[width * height];
        float[] normal = new float[width * height * 4];
        byte[] roughness = new byte[width * height];
        byte roughByte = (byte) Math.round(FLOOR_ROUGHNESS * 255.0f);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int offset = index * 4;
                depth[index] = deviceDepth;
                Vector3f surfaceNormal = x < stepColumn ? nearNormal : farNormal;
                float[] oct = octEncode(surfaceNormal);
                normal[offset] = oct[0];
                normal[offset + 1] = oct[1];
                normal[offset + 2] = 0.0f;
                normal[offset + 3] = 1.0f;
                roughness[index] = roughByte;
                colour[offset] = PLANE_COLOR.x;
                colour[offset + 1] = PLANE_COLOR.y;
                colour[offset + 2] = PLANE_COLOR.z;
                colour[offset + 3] = 1.0f;
            }
        }
        boolean[] eligible = eligibility(width, height, depth, normal, roughness,
                inverseProjection, inverseView);
        return new Fixture(width, height, colour, depth, normal, roughness, view,
                viewProjection, inverseView, inverseProjection, environment, eligible);
    }

    /** The analytic floor + narrow emissive wall fixture of the SSR GL test. */
    private static Fixture floorWallFixture(int width, int height,
                                            SsrEnvironmentSource environment) {
        Matrix4f view = new Matrix4f().lookAt(EYE, CENTRE, UP);
        Matrix4f projection = new Matrix4f().perspective(FIELD_OF_VIEW,
                (float) width / height, 0.1f, 100.0f);
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        Matrix4f inverseView = new Matrix4f(view).invert();
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        float[] colour = new float[width * height * 4];
        float[] depth = new float[width * height];
        float[] normal = new float[width * height * 4];
        byte[] roughness = new byte[width * height];
        Arrays.fill(depth, 1.0f);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int offset = index * 4;
                float ndcX = (x + 0.5f) / width * 2.0f - 1.0f;
                float ndcY = (y + 0.5f) / height * 2.0f - 1.0f;
                Vector3f direction = viewRay(ndcX, ndcY, inverseProjection, inverseView);
                float floorT = direction.y < 0.0f ? -EYE.y / direction.y : Float.MAX_VALUE;
                float wallT = direction.z < 0.0f ? (WALL_Z - EYE.z) / direction.z
                        : Float.MAX_VALUE;
                boolean floorHit = floorT > 0.0f && floorT < Float.MAX_VALUE;
                boolean wallCandidate = false;
                if (wallT > 0.0f && wallT < Float.MAX_VALUE) {
                    Vector3f hit = new Vector3f(EYE).fma(wallT, direction);
                    wallCandidate = Math.abs(hit.x) <= WALL_HALF_WIDTH
                            && hit.y >= 0.0f && hit.y <= WALL_HEIGHT;
                }
                Vector3f position;
                Vector3f surfaceNormal;
                float surfaceRoughness;
                Vector3f surfaceColour;
                boolean sky = false;
                if (wallCandidate && (!floorHit || wallT < floorT)) {
                    position = new Vector3f(EYE).fma(wallT, direction);
                    surfaceNormal = new Vector3f(0.0f, 0.0f, 1.0f);
                    surfaceRoughness = WALL_ROUGHNESS;
                    surfaceColour = WALL_COLOR;
                } else if (floorHit) {
                    position = new Vector3f(EYE).fma(floorT, direction);
                    surfaceNormal = new Vector3f(0.0f, 1.0f, 0.0f);
                    surfaceRoughness = FLOOR_ROUGHNESS;
                    surfaceColour = FLOOR_COLOR;
                } else {
                    position = new Vector3f(EYE).fma(1.0f, direction);
                    surfaceNormal = new Vector3f(0.0f, 0.0f, 1.0f);
                    surfaceRoughness = SKY_ROUGHNESS;
                    surfaceColour = SKY_COLOR;
                    sky = true;
                }
                if (!sky) {
                    Vector4f viewPosition = new Vector4f(position, 1.0f).mul(view);
                    Vector4f clip = new Vector4f(viewPosition).mul(projection);
                    depth[index] = clip.z / clip.w * 0.5f + 0.5f;
                }
                float[] oct = octEncode(surfaceNormal);
                normal[offset] = oct[0];
                normal[offset + 1] = oct[1];
                normal[offset + 2] = 0.0f;
                normal[offset + 3] = 1.0f;
                roughness[index] = (byte) Math.round(surfaceRoughness * 255.0f);
                colour[offset] = surfaceColour.x;
                colour[offset + 1] = surfaceColour.y;
                colour[offset + 2] = surfaceColour.z;
                colour[offset + 3] = 1.0f;
            }
        }
        boolean[] eligible = eligibility(width, height, depth, normal, roughness,
                inverseProjection, inverseView);
        return new Fixture(width, height, colour, depth, normal, roughness, view,
                viewProjection, inverseView, inverseProjection, environment, eligible);
    }

    private static boolean[] eligibility(int width, int height, float[] depth,
                                         float[] normal, byte[] roughness,
                                         Matrix4f inverseProjection,
                                         Matrix4f inverseView) {
        boolean[] eligible = new boolean[width * height];
        Vector3f eye = new Vector3f(0.0f, 0.0f, 0.0f).mulPosition(inverseView);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (depth[index] >= 1.0f) continue;
                if (Byte.toUnsignedInt(roughness[index]) / 255.0f > ROUGHNESS_CUTOFF) {
                    continue;
                }
                float ndcX = (x + 0.5f) / width * 2.0f - 1.0f;
                float ndcY = (y + 0.5f) / height * 2.0f - 1.0f;
                Vector4f clip = new Vector4f(ndcX, ndcY, depth[index] * 2.0f - 1.0f, 1.0f)
                        .mul(inverseProjection);
                if (clip.w <= 0.0f) continue;
                clip.div(clip.w);
                Vector3f world = new Vector3f(clip.x, clip.y, clip.z)
                        .mulPosition(inverseView);
                Vector3f surfaceNormal = octDecode(normal[index * 4], normal[index * 4 + 1]);
                Vector3f toCamera = new Vector3f(eye).sub(world);
                if (toCamera.lengthSquared() <= 0.0f) continue;
                float nDotV = surfaceNormal.dot(toCamera.normalize());
                if (nDotV <= 0.05f) continue;
                eligible[index] = true;
            }
        }
        return eligible;
    }

    private static float[] render(GlRenderDevice device, SsrPass pass, Fixture fixture,
                                  boolean exposeBudget) {
        int width = fixture.width();
        int height = fixture.height();
        try (Texture2D colour = floatTexture(width, height, 4, fixture.colour());
             Texture2D depth = floatTexture(width, height, 1, fixture.depth());
             Texture2D normal = floatTexture(width, height, 4, fixture.normal());
             Texture2D roughness = byteTexture(width, height, fixture.roughness());
             Framebuffer target = Framebuffer.mrt(width, height, GL_RGBA32F)) {
            target.bind();
            glViewport(0, 0, width, height);
            CommandBuffer commands = device.createCommandBuffer();
            pass.recordIntoCurrentTarget(commands, colour.id(), depth.id(), normal.id(),
                    roughness.id(), fixture.inverseProjection(), fixture.inverseView(),
                    fixture.view(), fixture.viewProjection(), fixture.environment(),
                    width, height, exposeBudget);
            device.execute(commands);
            target.bind();
            glViewport(0, 0, width, height);
            FloatBuffer pixels = BufferUtils.createFloatBuffer(width * height * 4);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, pixels);
            float[] result = new float[width * height * 4];
            pixels.get(result);
            return result;
        }
    }

    private static Texture2D floatTexture(int width, int height, int channels, float[] data) {
        int format = channels == 1 ? GL_R32F : GL_RGBA32F;
        Texture2D texture = Texture2D.createEmpty(width, height, format);
        ByteBuffer upload = BufferUtils.createByteBuffer(width * height * channels
                * Float.BYTES);
        upload.asFloatBuffer().put(data);
        texture.uploadRegion(0, 0, width, height, upload);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    private static Texture2D byteTexture(int width, int height, byte[] data) {
        Texture2D texture = Texture2D.createEmpty(width, height, GL_R8);
        ByteBuffer upload = BufferUtils.createByteBuffer(width * height);
        upload.put(data);
        upload.flip();
        texture.uploadRegion(0, 0, width, height, upload);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    private static float deviceDepth(float viewDepth, Matrix4f projection) {
        Vector4f clip = new Vector4f(0.0f, 0.0f, -viewDepth, 1.0f).mul(projection);
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static Vector3f viewRay(float ndcX, float ndcY, Matrix4f inverseProjection,
                                    Matrix4f inverseView) {
        Vector4f near = new Vector4f(ndcX, ndcY, -1.0f, 1.0f).mul(inverseProjection);
        near.div(near.w);
        Vector4f far = new Vector4f(ndcX, ndcY, 1.0f, 1.0f).mul(inverseProjection);
        far.div(far.w);
        Vector3f nearWorld = new Vector3f(near.x, near.y, near.z).mulPosition(inverseView);
        Vector3f farWorld = new Vector3f(far.x, far.y, far.z).mulPosition(inverseView);
        return farWorld.sub(nearWorld).normalize();
    }

    /** Mirrors the octahedral encoding written by scene-surface.frag. */
    private static float[] octEncode(Vector3f normal) {
        Vector3f n = new Vector3f(normal).normalize();
        float denominator = Math.abs(n.x) + Math.abs(n.y) + Math.abs(n.z) + 1.0e-8f;
        float x = n.x / denominator;
        float y = n.y / denominator;
        if (n.z < 0.0f) {
            float signX = n.x >= 0.0f ? 1.0f : -1.0f;
            float signY = n.y >= 0.0f ? 1.0f : -1.0f;
            float originalX = x;
            x = (1.0f - Math.abs(y)) * signX;
            y = (1.0f - Math.abs(originalX)) * signY;
        }
        return new float[]{x, y};
    }

    /** Mirrors octDecode() in the SSR shaders. */
    private static Vector3f octDecode(float octX, float octY) {
        Vector3f n = new Vector3f(octX, octY, 1.0f - Math.abs(octX) - Math.abs(octY));
        if (n.z < 0.0f) {
            float originalX = n.x;
            n.x = (1.0f - Math.abs(n.y)) * (n.x >= 0.0f ? 1.0f : -1.0f);
            n.y = (1.0f - Math.abs(originalX)) * (n.y >= 0.0f ? 1.0f : -1.0f);
        }
        return n.normalize();
    }

    private static float alpha(float[] output, int index) {
        return output[index * 4 + 3];
    }

    private static int budget(float[] output, int index, int maximumSteps) {
        return Math.round(alpha(output, index) * maximumSteps);
    }

    private static int meanBudget(float[] output, Fixture fixture, int maximumSteps) {
        long sum = 0;
        int count = 0;
        for (int index = 0; index < fixture.width() * fixture.height(); index++) {
            if (!fixture.eligible()[index]) continue;
            sum += budget(output, index, maximumSteps);
            count++;
        }
        return count == 0 ? MINIMUM_STEPS : (int) Math.round((double) sum / count);
    }

    private static boolean[] oracleReflectionMask(float[] oracle, Fixture fixture) {
        boolean[] mask = new boolean[fixture.width() * fixture.height()];
        for (int index = 0; index < mask.length; index++) {
            if (!fixture.eligible()[index]) continue;
            int offset = index * 4;
            mask[index] = Math.abs(oracle[offset] - fixture.colour()[offset]) > 1.0e-3f
                    || Math.abs(oracle[offset + 1] - fixture.colour()[offset + 1]) > 1.0e-3f
                    || Math.abs(oracle[offset + 2] - fixture.colour()[offset + 2]) > 1.0e-3f;
        }
        return mask;
    }

    private static double meanRgbError(float[] candidate, float[] reference, boolean[] mask) {
        double sum = 0.0;
        int count = 0;
        for (int index = 0; index < mask.length; index++) {
            if (!mask[index]) continue;
            int offset = index * 4;
            for (int channel = 0; channel < 3; channel++) {
                sum += Math.abs(candidate[offset + channel] - reference[offset + channel]);
            }
            count += 3;
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static int count(boolean[] mask) {
        int total = 0;
        for (boolean value : mask) if (value) total++;
        return total;
    }

    private static float maximumAlpha(float[] output, Fixture fixture) {
        float maximum = 0.0f;
        for (float alpha : eligibleAlphas(output, fixture)) {
            maximum = Math.max(maximum, alpha);
        }
        return maximum;
    }

    private static float[] eligibleAlphas(float[] output, Fixture fixture) {
        int width = fixture.width();
        int height = fixture.height();
        float[] alphas = new float[width * height];
        int count = 0;
        for (int index = 0; index < width * height; index++) {
            if (!fixture.eligible()[index]) continue;
            alphas[count++] = alpha(output, index);
        }
        return Arrays.copyOf(alphas, count);
    }

    private static float[] rgb(float[] rgba) {
        float[] rgb = new float[rgba.length / 4 * 3];
        for (int index = 0; index < rgba.length / 4; index++) {
            rgb[index * 3] = rgba[index * 4];
            rgb[index * 3 + 1] = rgba[index * 4 + 1];
            rgb[index * 3 + 2] = rgba[index * 4 + 2];
        }
        return rgb;
    }

    private static void assertTileUniform(float[] output, Fixture fixture) {
        int width = fixture.width();
        int height = fixture.height();
        for (int tileY = 0; tileY < height; tileY += TILE_SIZE) {
            for (int tileX = 0; tileX < width; tileX += TILE_SIZE) {
                Float expected = null;
                for (int y = tileY; y < Math.min(tileY + TILE_SIZE, height); y++) {
                    for (int x = tileX; x < Math.min(tileX + TILE_SIZE, width); x++) {
                        int index = y * width + x;
                        if (!fixture.eligible()[index]) continue;
                        float value = alpha(output, index);
                        if (expected == null) {
                            expected = value;
                        } else {
                            assertEquals(expected, value, 1.0e-6f,
                                    "the tile term must be uniform inside tile "
                                            + tileX + "," + tileY + " at " + x + "," + y);
                        }
                    }
                }
            }
        }
    }

    private static void assertTileAlpha(float[] output, Fixture fixture, int tileX, int tileY,
                                        float expectedAlpha) {
        int width = fixture.width();
        int height = fixture.height();
        int checked = 0;
        for (int y = tileY; y < Math.min(tileY + TILE_SIZE, height); y++) {
            for (int x = tileX; x < Math.min(tileX + TILE_SIZE, width); x++) {
                int index = y * width + x;
                if (!fixture.eligible()[index]) continue;
                assertEquals(expectedAlpha, alpha(output, index), 1.0e-6f,
                        "the step tile must carry the measured ceiling budget at "
                                + x + "," + y);
                checked++;
            }
        }
        assertTrue(checked > 0, "the step tile must contain eligible pixels");
    }

    private static void assertFinite(float[] output) {
        for (float value : output) {
            assertTrue(Float.isFinite(value), "output must be finite, got " + value);
        }
    }

    private static void recordDevice() {
        String vendor = glGetString(GL_VENDOR);
        String renderer = glGetString(GL_RENDERER);
        String version = glGetString(GL_VERSION);
        var snapshot = GlDebug.messages();
        StringBuilder text = new StringBuilder();
        text.append("vendor=").append(vendor).append('\n')
                .append("renderer=").append(renderer).append('\n')
                .append("version=").append(version).append('\n')
                .append("debugMessages=").append(snapshot.messages().size())
                .append(" dropped=").append(snapshot.droppedCount()).append('\n');
        for (var message : snapshot.messages()) {
            text.append("  ").append(message.severity()).append(' ')
                    .append(message.source()).append(' ').append(message.type())
                    .append(" id=").append(message.driverId()).append(' ')
                    .append(message.message()).append('\n');
        }
        try {
            Path directory = Path.of("build", "reports", "gl");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("ssr-adaptive-sampling-device.txt"),
                    text.toString(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot record the adaptive SSR GL device identity", failure);
        }
    }

    private record Fixture(int width, int height, float[] colour, float[] depth,
                           float[] normal, byte[] roughness, Matrix4f view,
                           Matrix4f viewProjection, Matrix4f inverseView,
                           Matrix4f inverseProjection,
                           SsrEnvironmentSource environment, boolean[] eligible) {
        Fixture {
            assertNotNull(colour);
            assertNotNull(depth);
            assertNotNull(normal);
            assertNotNull(roughness);
            assertNotNull(environment);
        }
    }

    private static final SurfaceInputs NO_SURFACE = new SurfaceInputs() {
        @Override
        public Input single(Channel channel) {
            return null;
        }

        @Override
        public void validateDependency(String consumerPass, Input input) {
        }

        @Override
        public int texture(com.kaleblangley.haikalat.core.graph.PassResources resources,
                           Input input) {
            throw new UnsupportedOperationException();
        }
    };

    private static final SurfaceInputs SSR_SURFACE = new SurfaceInputs() {
        @Override
        public Input single(Channel channel) {
            return switch (channel) {
                case DEPTH -> new Input(channel, PostProcessTargets.SCENE_DEPTH,
                        PostProcessTargets.GEOMETRY_PASS);
                case NORMAL -> new Input(channel, PostProcessTargets.SCENE_NORMAL,
                        PostProcessTargets.GEOMETRY_PASS);
                case ROUGHNESS -> new Input(channel, PostProcessTargets.SCENE_ROUGHNESS,
                        PostProcessTargets.GEOMETRY_PASS);
                default -> null;
            };
        }

        @Override
        public void validateDependency(String consumerPass, Input input) {
        }

        @Override
        public int texture(com.kaleblangley.haikalat.core.graph.PassResources resources,
                           Input input) {
            throw new UnsupportedOperationException();
        }
    };

    private static final SsrEnvironmentSource NO_ENVIRONMENT = new SsrEnvironmentSource() {
        @Override
        public float bindPrefilteredSpecular(CommandBuffer cmd, int unit) {
            return -1.0f;
        }

        @Override
        public float rotationRadians() {
            return 0.0f;
        }

        @Override
        public float intensity() {
            return 1.0f;
        }
    };
}
