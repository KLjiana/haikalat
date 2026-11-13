package com.kaleblangley.haikalat.subsystems.postprocess.ssr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TemporalFramePose;
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

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11.glDrawBuffer;

/**
 * Real-GL regression for the SSR jittered-projection fix.
 *
 * <p>The G-buffer fixture is rasterized with a nonzero TAA-style jittered
 * projection. The depth is therefore only consistent with
 * {@code inverseJitteredProjection}; projecting march samples back with the
 * stable view-projection shifts every tap by the jitter and samples the wrong
 * screen position. The case renders the same fixture three ways:</p>
 *
 * <ol>
 *   <li>through the production {@link PostProcessPipeline} callback, whose
 *       fixed path must invert {@code inverseJitteredProjection} and compose it
 *       with the view, not pass the projection alone;</li>
 *   <li>the jitter-consistent reference: {@link SsrPass} driven directly with
 *       the jittered view-projection (an independent wiring path);</li>
 *   <li>the old stable-projection path: {@link SsrPass} driven directly with
 *       the stable view-projection.</li>
 * </ol>
 *
 * <p>An independent analytic prediction projects each mirrored wall hit with
 * both matrices and reads the wall's world-locked monotone red ramp at the
 * resulting texel. The fixed path must match the jitter-consistent reference,
 * while the stable path must differ and read a brighter texel at the predicted
 * pixels. The ramp makes the sub-pixel address error observable and gives it a
 * known sign without relying on a GPU-produced expected image.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SsrJitterGlTest {
    private static final int SIZE = 96;
    private static final Vector3f EYE = new Vector3f(0.0f, 1.6f, 4.0f);
    private static final Vector3f CENTRE = new Vector3f(0.0f, 1.0f, -3.0f);
    private static final Vector3f UP = new Vector3f(0.0f, 1.0f, 0.0f);
    private static final float FIELD_OF_VIEW = (float) Math.toRadians(55.0);
    private static final float WALL_Z = -3.0f;
    private static final float WALL_HALF_WIDTH = 1.5f;
    private static final float WALL_HEIGHT = 2.0f;
    /**
     * Deliberately larger than the production +/-0.25 px TAA pattern. The defect
     * is an address error, so the fixture only needs a nonzero, measurable
     * sub-pixel jitter; 0.75 px is the same magnitude the temporal-denoise
     * reprojection counterexample uses and stays below a pixel.
     */
    private static final float JITTER_X_PIXELS = 0.75f;
    private static final float JITTER_Y_PIXELS = 0.0f;
    /**
     * Monotone world-space red ramp on the wall. A projected sample shifted to
     * the right therefore always reads a brighter texel, so the jitter-omitting
     * path's error has a predictable sign that does not depend on the march's
     * sub-texel refinement. The ramp is world-locked, like a real material.
     */
    private static final float WALL_RAMP_RED = 600.0f;
    private static final float FLOOR_ROUGHNESS = 0.05f;
    private static final float WALL_ROUGHNESS = 0.9f;
    private static final float SKY_ROUGHNESS = 1.0f;
    private static final Vector3f FLOOR_COLOR = new Vector3f(0.05f, 0.06f, 0.07f);
    private static final Vector3f SKY_COLOR = new Vector3f(0.01f, 0.02f, 0.03f);
    private static final float WALL_GREEN = 0.2f;
    private static final float WALL_BLUE = 0.1f;
    private static final int MINIMUM_PREDICTED = 100;
    private static final int MINIMUM_CHANGED = 100;
    private static final float DIRECTION_EPSILON = 0.05f;

    private static final Matrix4f VIEW = new Matrix4f().lookAt(EYE, CENTRE, UP);
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(FIELD_OF_VIEW,
            1.0f, 0.1f, 100.0f);
    private static final Matrix4f JITTERED_PROJECTION = jittered(PROJECTION);
    private static final Matrix4f STABLE_VIEW_PROJECTION =
            new Matrix4f(PROJECTION).mul(VIEW);
    private static final Matrix4f JITTERED_VIEW_PROJECTION =
            new Matrix4f(JITTERED_PROJECTION).mul(VIEW);
    private static final Matrix4f INVERSE_VIEW = new Matrix4f(VIEW).invert();
    private static final Matrix4f INVERSE_JITTERED_PROJECTION =
            new Matrix4f(JITTERED_PROJECTION).invert();

    private static final SsrSettings SETTINGS = SsrSettings.builder()
            .enabled(true)
            .maxDistanceMetres(12.0f)
            .maxSteps(48)
            .thicknessMetres(1.0f)
            .roughnessCutoff(0.4f)
            .minimumConfidence(0.15f)
            .strength(0.9f)
            .edgeFadePixels(8.0f)
            .build();

    @Test
    void fixedPathMatchesTheJitteredReferenceAndStableProjectionSamplesTheWrongTexels() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("SSR jitter GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr",
                    PbrEnvironmentSettings.testQuality());
                 Sampler cubeSampler = Sampler.create(new Sampler.Descriptor(
                         GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                         GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
                 Fixture fixture = fixture()) {
                SsrEnvironmentSource source = environment(environment, cubeSampler);
                assertTrue(fixture.predicted() >= MINIMUM_PREDICTED,
                        "the fixture must expose a meaningful jitter-sensitive region, got "
                                + fixture.predicted());

                float[] reference = renderDirect(device, fixture, source,
                        JITTERED_VIEW_PROJECTION);
                float[] stable = renderDirect(device, fixture, source,
                        STABLE_VIEW_PROJECTION);
                float[] fixedPath = renderThroughPipeline(device, fixture, source);

                assertMatches(reference, fixedPath,
                        "the production callback must use the jittered view-projection");
                int changed = countDifferent(reference, stable, 1.0e-4f);
                assertTrue(changed >= MINIMUM_CHANGED,
                        "the old stable projection must produce a different sample set, got "
                                + changed + " changed pixels");

                int compared = 0;
                int stableBrighter = 0;
                int stableDarker = 0;
                int significant = 0;
                for (int index = 0; index < SIZE * SIZE; index++) {
                    if (!fixture.jitterSensitive()[index]) continue;
                    int offset = index * 4;
                    compared++;
                    float difference = stable[offset] - reference[offset];
                    if (difference > DIRECTION_EPSILON) stableBrighter++;
                    else if (difference < -DIRECTION_EPSILON) stableDarker++;
                    if (Math.abs(difference) > 10.0f * DIRECTION_EPSILON) significant++;
                }
                assertEquals(fixture.predicted(), compared,
                        "every predicted pixel must be inspected");
                System.out.println("SSR_JITTER predicted=" + fixture.predicted()
                        + " stableChanged=" + changed + " stableBrighter=" + stableBrighter
                        + " stableDarker=" + stableDarker + " significant=" + significant);
                assertTrue(stableBrighter >= compared * 9 / 10,
                        "the stable projection shifts each sample right on the ramp, so it "
                                + "must read brighter: " + stableBrighter + "/" + compared);
                assertTrue(stableDarker <= compared / 50,
                        "the stable projection must not read consistently darker: "
                                + stableDarker + "/" + compared);
                assertTrue(significant >= compared * 4 / 5,
                        "the wrong sample set must change the reflected colour measurably: "
                                + significant + "/" + compared);
            }
            GlDebug.assertNoError("SSR jitter projection");
        }
    }

    private static Matrix4f jittered(Matrix4f projection) {
        // Same clip-space translation as TemporalJitter: NDC shifts by
        // -2 * pixels / extent, so UV shifts by -pixels / extent.
        return new Matrix4f().translation(-2.0f * JITTER_X_PIXELS / SIZE,
                -2.0f * JITTER_Y_PIXELS / SIZE, 0.0f).mul(projection);
    }

    private static float[] renderThroughPipeline(GlRenderDevice device, Fixture fixture,
                                                 SsrEnvironmentSource source) {
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
        PostProcessSettings effects = PostProcessSettings.defaults().withSsr(SETTINGS);
        SurfaceInputs surfaceInputs = surfaceInputs(fixture);
        try (PostProcessPipeline pipeline = PostProcessPipeline.create(settings, effects,
                SIZE, SIZE, null, surfaceInputs, null, source);
             RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
            graph.importExternalColor(PostProcessTargets.SCENE_COLOR,
                    ExternalAttachment.borrowedColor(fixture.colour().id(),
                            RenderFormat.RGBA32F, SIZE, SIZE));
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .computeOnly()
                    .execute((resources, commands) -> {
                    });
            pipeline.addFinalPass(graph);
            int[] ssrTexture = new int[1];
            graph.addPass("SsrJitterCapture")
                    .computeOnly()
                    .dependsOn(PostProcessTargets.SSR_PASS)
                    .execute((resources, commands) ->
                            ssrTexture[0] = resources.colorAttachment(
                                    PostProcessTargets.SSR_COLOR));
            pipeline.beginFrame(new TemporalFramePose(1.0f / 60.0f, SIZE, SIZE,
                    -JITTER_X_PIXELS / SIZE, -JITTER_Y_PIXELS / SIZE,
                    INVERSE_JITTERED_PROJECTION, INVERSE_VIEW,
                    STABLE_VIEW_PROJECTION, null, null));
            graph.execute(device);
            pipeline.frameSucceeded();
            assertTrue(ssrTexture[0] > 0, "the SSR target must be readable");
            return readColorTexture(ssrTexture[0]);
        }
    }

    private static float[] renderDirect(GlRenderDevice device, Fixture fixture,
                                        SsrEnvironmentSource source,
                                        Matrix4f viewProjection) {
        try (Framebuffer target = Framebuffer.mrt(SIZE, SIZE, GL_RGBA16F);
             SsrPass pass = new SsrPass(SETTINGS)) {
            target.bind();
            glViewport(0, 0, SIZE, SIZE);
            CommandBuffer commands = device.createCommandBuffer();
            pass.recordIntoCurrentTarget(commands, fixture.colour().id(),
                    fixture.depth().id(), fixture.normal().id(), fixture.roughness().id(),
                    INVERSE_JITTERED_PROJECTION, INVERSE_VIEW, VIEW, viewProjection,
                    source, SIZE, SIZE);
            device.execute(commands);
            return readColorTexture(target.colorAttachment());
        }
    }

    private static SurfaceInputs surfaceInputs(Fixture fixture) {
        return new SurfaceInputs() {
            @Override
            public Input single(Channel channel) {
                return switch (channel) {
                    case DEPTH -> new Input(channel, "SsrJitterDepth",
                            PostProcessTargets.GEOMETRY_PASS);
                    case NORMAL -> new Input(channel, "SsrJitterNormal",
                            PostProcessTargets.GEOMETRY_PASS);
                    case ROUGHNESS -> new Input(channel, "SsrJitterRoughness",
                            PostProcessTargets.GEOMETRY_PASS);
                    default -> null;
                };
            }

            @Override
            public void validateDependency(String consumerPass, Input input) {
            }

            @Override
            public int texture(PassResources resources, Input input) {
                return switch (input.channel()) {
                    case DEPTH -> fixture.depth().id();
                    case NORMAL -> fixture.normal().id();
                    case ROUGHNESS -> fixture.roughness().id();
                    default -> throw new IllegalArgumentException(
                            "unexpected surface input " + input.channel());
                };
            }
        };
    }

    private static void assertMatches(float[] expected, float[] actual, String message) {
        assertEquals(expected.length, actual.length, message);
        float maximum = 0.0f;
        for (int index = 0; index < expected.length; index++) {
            maximum = Math.max(maximum, Math.abs(expected[index] - actual[index]));
        }
        assertTrue(maximum <= 2.0e-3f,
                message + " (maximum difference " + maximum + ")");
    }

    private static int countDifferent(float[] left, float[] right, float tolerance) {
        assertEquals(left.length, right.length);
        int changed = 0;
        for (int index = 0; index < left.length; index += 4) {
            if (Math.abs(left[index] - right[index]) > tolerance
                    || Math.abs(left[index + 1] - right[index + 1]) > tolerance
                    || Math.abs(left[index + 2] - right[index + 2]) > tolerance) {
                changed++;
            }
        }
        return changed;
    }

    private static float[] readColorTexture(int texture) {
        int framebuffer = glGenFramebuffers();
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture, 0);
            assertEquals(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE,
                    glCheckFramebufferStatus(GL_FRAMEBUFFER));
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
            float[] result = new float[SIZE * SIZE * 4];
            pixels.get(result);
            return result;
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(framebuffer);
        }
    }

    private static Fixture fixture() {
        float[] colour = new float[SIZE * SIZE * 4];
        float[] depth = new float[SIZE * SIZE];
        float[] normal = new float[SIZE * SIZE * 4];
        byte[] roughness = new byte[SIZE * SIZE];
        float[] worldPosition = new float[SIZE * SIZE * 3];
        float[] worldNormal = new float[SIZE * SIZE * 3];
        boolean[] floorPixel = new boolean[SIZE * SIZE];
        java.util.Arrays.fill(depth, 1.0f);

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int index = y * SIZE + x;
                int offset = index * 4;
                Vector3f direction = jitteredViewRay(x, y);
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
                boolean skyPixel = false;
                if (wallCandidate && (!floorHit || wallT < floorT)) {
                    position = new Vector3f(EYE).fma(wallT, direction);
                    surfaceNormal = new Vector3f(0.0f, 0.0f, 1.0f);
                    surfaceRoughness = WALL_ROUGHNESS;
                    surfaceColour = new Vector3f(wallRed(position.x), WALL_GREEN, WALL_BLUE);
                } else if (floorHit) {
                    position = new Vector3f(EYE).fma(floorT, direction);
                    surfaceNormal = new Vector3f(0.0f, 1.0f, 0.0f);
                    surfaceRoughness = FLOOR_ROUGHNESS;
                    surfaceColour = FLOOR_COLOR;
                    floorPixel[index] = true;
                } else {
                    position = new Vector3f(EYE).fma(1.0f, direction);
                    surfaceNormal = new Vector3f(0.0f, 0.0f, 1.0f);
                    surfaceRoughness = SKY_ROUGHNESS;
                    surfaceColour = SKY_COLOR;
                    skyPixel = true;
                }

                worldPosition[index * 3] = position.x;
                worldPosition[index * 3 + 1] = position.y;
                worldPosition[index * 3 + 2] = position.z;
                worldNormal[index * 3] = surfaceNormal.x;
                worldNormal[index * 3 + 1] = surfaceNormal.y;
                worldNormal[index * 3 + 2] = surfaceNormal.z;

                if (!skyPixel) {
                    // Rasterised with the jittered projection, exactly like the depth
                    // the production SSR pass receives under TAA.
                    Vector4f viewPosition = new Vector4f(position, 1.0f).mul(VIEW);
                    Vector4f clip = new Vector4f(viewPosition).mul(JITTERED_PROJECTION);
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

        boolean[] jitterSensitive = new boolean[SIZE * SIZE];
        int predicted = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int index = y * SIZE + x;
                if (!floorPixel[index] || depth[index] >= 1.0f) continue;
                Vector3f position = new Vector3f(worldPosition[index * 3],
                        worldPosition[index * 3 + 1], worldPosition[index * 3 + 2]);
                Vector3f receiverNormal = new Vector3f(worldNormal[index * 3],
                        worldNormal[index * 3 + 1], worldNormal[index * 3 + 2]);
                Vector3f toCamera = new Vector3f(EYE).sub(position).normalize();
                float nDotV = receiverNormal.dot(toCamera);
                if (nDotV <= 0.2f) continue;
                Vector3f reflected = new Vector3f(-toCamera.x, -toCamera.y, -toCamera.z)
                        .fma(2.0f * nDotV, receiverNormal);
                if (reflected.z >= 0.0f) continue;
                float t = (WALL_Z - position.z) / reflected.z;
                if (!(t > 0.0f) || t > 0.7f * SETTINGS.maxDistanceMetres()) continue;
                Vector3f hit = new Vector3f(position).fma(t, reflected);
                if (Math.abs(hit.x) > WALL_HALF_WIDTH - 0.15f
                        || hit.y < 0.15f || hit.y > WALL_HEIGHT - 0.15f) continue;
                float[] jitteredUv = project(hit, JITTERED_VIEW_PROJECTION);
                float[] stableUv = project(hit, STABLE_VIEW_PROJECTION);
                if (jitteredUv == null || stableUv == null) continue;
                float jitteredRed = wallRedAt(colour, jitteredUv);
                float stableRed = wallRedAt(colour, stableUv);
                // The stable projection shifts a sample right on the increasing ramp;
                // only pixels whose texel actually changes can discriminate the paths.
                if (stableRed - jitteredRed < 0.2f) continue;
                predicted++;
                jitterSensitive[index] = true;
            }
        }

        Texture2D colourTexture = floatTexture(4, colour, GL_RGBA32F);
        Texture2D depthTexture = floatTexture(1, depth, GL_R32F);
        Texture2D normalTexture = floatTexture(4, normal, GL_RGBA32F);
        Texture2D roughnessTexture = byteTexture(roughness);
        return new Fixture(colourTexture, depthTexture, normalTexture, roughnessTexture,
                jitterSensitive, predicted);
    }

    private static Vector3f jitteredViewRay(int x, int y) {
        float ndcX = (x + 0.5f) / SIZE * 2.0f - 1.0f;
        float ndcY = (y + 0.5f) / SIZE * 2.0f - 1.0f;
        Vector4f near = new Vector4f(ndcX, ndcY, -1.0f, 1.0f)
                .mul(INVERSE_JITTERED_PROJECTION);
        near.div(near.w);
        Vector4f far = new Vector4f(ndcX, ndcY, 1.0f, 1.0f)
                .mul(INVERSE_JITTERED_PROJECTION);
        far.div(far.w);
        Vector3f nearWorld = new Vector3f(near.x, near.y, near.z).mulPosition(INVERSE_VIEW);
        Vector3f farWorld = new Vector3f(far.x, far.y, far.z).mulPosition(INVERSE_VIEW);
        return farWorld.sub(nearWorld).normalize();
    }

    /** @return normalized UV of a world point, or null behind/outside the raster */
    private static float[] project(Vector3f world, Matrix4f viewProjection) {
        Vector4f clip = new Vector4f(world, 1.0f).mul(viewProjection);
        if (clip.w <= 0.0f) return null;
        float u = clip.x / clip.w * 0.5f + 0.5f;
        float v = clip.y / clip.w * 0.5f + 0.5f;
        if (u < 0.0f || u > 1.0f || v < 0.0f || v > 1.0f) return null;
        return new float[]{u, v};
    }

    private static float wallRed(float worldX) {
        float across = (worldX + WALL_HALF_WIDTH) / (2.0f * WALL_HALF_WIDTH);
        return 1.0f + WALL_RAMP_RED * Math.max(0.0f, Math.min(1.0f, across));
    }

    private static float wallRedAt(float[] colour, float[] uv) {
        int x = Math.min(SIZE - 1, Math.max(0, (int) (uv[0] * SIZE)));
        int y = Math.min(SIZE - 1, Math.max(0, (int) (uv[1] * SIZE)));
        return colour[(y * SIZE + x) * 4];
    }

    private static Texture2D floatTexture(int channels, float[] data, int format) {
        Texture2D texture = Texture2D.createEmpty(SIZE, SIZE, format);
        ByteBuffer upload = BufferUtils.createByteBuffer(
                SIZE * SIZE * channels * Float.BYTES);
        upload.asFloatBuffer().put(data);
        texture.uploadRegion(0, 0, SIZE, SIZE, upload);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    private static Texture2D byteTexture(byte[] data) {
        Texture2D texture = Texture2D.createEmpty(SIZE, SIZE, GL_R8);
        ByteBuffer upload = BufferUtils.createByteBuffer(SIZE * SIZE);
        upload.put(data);
        upload.flip();
        texture.uploadRegion(0, 0, SIZE, SIZE, upload);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
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

    private record Fixture(Texture2D colour, Texture2D depth, Texture2D normal,
                           Texture2D roughness, boolean[] jitterSensitive,
                           int predicted) implements AutoCloseable {
        @Override
        public void close() {
            RuntimeException failure = null;
            for (Texture2D texture : new Texture2D[]{colour, depth, normal, roughness}) {
                try {
                    texture.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
