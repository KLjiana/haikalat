package com.kaleblangley.haikalat.subsystems.postprocess.sss;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;

/**
 * Real-GL acceptance for #33, the bounded screen-space subsurface diffusion.
 *
 * <p>Case 1 renders an analytic synthetic surface: a black gap next to a
 * diffuse patch, a specular highlight, a depth discontinuity, a normal flip
 * and a non-participating region. The test derives the expected behaviour
 * independently of the shader: diffusion must brighten the near gap, reject the
 * bright far/normal layers (a black control render must produce the same gap
 * pixels), leave the highlight exactly untouched, copy non-participating pixels
 * bit-exactly and keep the changed region inside the fixed radius bound. A
 * second render with a smaller material radius must change fewer/less.</p>
 *
 * <p>Case 2 checks the colour-chain contract: disabled keeps the exact baseline
 * order, enabled inserts one RGBA16F pass before SSR/tone mapping, a missing
 * surface channel is rejected, HDR is required and resize/close leave no
 * resources behind.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SubsurfaceScatteringPassGlTest {
    private static final int SIZE = 96;
    private static final float FIELD_OF_VIEW = (float) Math.toRadians(55.0);
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(FIELD_OF_VIEW,
            1.0f, 0.1f, 100.0f);
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f(PROJECTION).invert();
    private static final Matrix4f INVERSE_VIEW = new Matrix4f();

    private static final SubsurfaceScatteringSettings SETTINGS =
            SubsurfaceScatteringSettings.builder()
                    .profile(SubsurfaceScatteringSettings.Profile.SKIN)
                    .diffusionRadiusMetres(1.0f)
                    .maxDistanceMetres(20.0f)
                    .build();

    @Test
    void boundedDiffusionRejectsDepthAndNormalBoundariesAndProtectsHighlights() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Subsurface Pass GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            Fixture main = fixture(0.35f, true, true);
            float[] output = render(device, main, SETTINGS);
            assertFinite(output);
            assertBoundedDiffusion(main, output);

            Fixture darkFar = fixture(0.35f, false, true);
            float[] darkFarOutput = render(device, darkFar, SETTINGS);
            int gap = pixel(50, 20);
            assertEquals(darkFarOutput[gap * 4], output[gap * 4], 1.0e-3f,
                    "the far bright layer must not leak across the depth step");
            assertEquals(darkFarOutput[gap * 4 + 1], output[gap * 4 + 1], 1.0e-3f);

            Fixture darkNormal = fixture(0.35f, true, false);
            float[] darkNormalOutput = render(device, darkNormal, SETTINGS);
            int normalGap = pixel(40, 56);
            assertEquals(darkNormalOutput[normalGap * 4], output[normalGap * 4], 1.0e-3f,
                    "the flipped-normal layer must not leak across the boundary");
            assertEquals(darkNormalOutput[normalGap * 4 + 1], output[normalGap * 4 + 1], 1.0e-3f);

            Fixture smallRadius = fixture(0.12f, true, true);
            float[] smallOutput = render(device, smallRadius, SETTINGS);
            assertTrue(output[gap * 4] > smallOutput[gap * 4] * 1.5f,
                    "the larger material radius must spread more light into the gap");
            assertTrue(changedPixels(output, main) >= changedPixels(smallOutput, smallRadius),
                    "the larger radius must affect at least as many pixels");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void equalLuminanceSpecularShareStaysUndiffusedWhileDiffuseShareDiffuses() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Subsurface Diffuse Share GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            Fixture fixture = equalLuminanceFixture();
            float[] output = render(device, fixture, SETTINGS);
            assertFinite(output);

            // Both patches have exactly the same colour and therefore the same
            // luminance; only the alpha diffuse share differs. A luminance-band
            // heuristic cannot distinguish them.
            int specularEdge = pixel(31, 24);
            int diffuseEdge = pixel(58, 24);
            assertEquals(fixture.colour()[specularEdge * 4], output[specularEdge * 4],
                    0.0f, "an alpha-0 specular pixel must stay byte-exact");
            assertEquals(fixture.colour()[specularEdge * 4 + 1],
                    output[specularEdge * 4 + 1], 0.0f);
            assertTrue(output[diffuseEdge * 4] < fixture.colour()[diffuseEdge * 4] * 0.95f,
                    "an alpha-1 diffuse pixel must participate in the gather: "
                            + output[diffuseEdge * 4] + " vs "
                            + fixture.colour()[diffuseEdge * 4]);

            // The near gap participates in the mask. The diffuse patch must
            // spread light into it; the equally bright specular patch must not.
            int diffuseHalo = pixel(57, 24);
            int specularHalo = pixel(32, 24);
            assertTrue(output[diffuseHalo * 4] > 0.01f,
                    "the diffuse patch must scatter into the participating gap");
            assertEquals(0.0f, output[specularHalo * 4], 0.0f,
                    "the specular patch must not produce a diffusion halo");

            // The bright specular patch is not itself diffused by its
            // neighbours either: it has no diffuse albedo to scatter.
            assertEquals(fixture.colour()[pixel(20, 24) * 4], output[pixel(20, 24) * 4],
                    0.0f, "the specular patch interior must be copied exactly");
            assertTrue(output[pixel(70, 24) * 4] < fixture.colour()[pixel(70, 24) * 4] * 0.95f,
                    "the diffuse patch interior must still diffuse from its dark gap");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    /**
     * Two isolated patches with identical colour/luminance: the left one is
     * marked fully specular (alpha 0) and the right one fully diffuse
     * (alpha 1). Both sit on a participating black gap with the same near
     * depth and normal, so only the alpha contract can tell them apart.
     */
    private static Fixture equalLuminanceFixture() {
        float[] colour = new float[SIZE * SIZE * 4];
        float[] depth = new float[SIZE * SIZE];
        float[] normal = new float[SIZE * SIZE * 4];
        float[] mask = new float[SIZE * SIZE * 4];
        float nearDepth = deviceDepth(-4.0f);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int index = pixel(x, y);
                int offset = index * 4;
                boolean body = y >= 8 && y < 48;
                boolean specular = body && x >= 16 && x < 32;
                boolean diffuse = body && x >= 58 && x < 74;
                if (specular || diffuse) {
                    colour[offset] = 0.60f;
                    colour[offset + 1] = 0.30f;
                    colour[offset + 2] = 0.15f;
                }
                colour[offset + 3] = specular ? 0.0f : 1.0f;
                depth[index] = nearDepth;
                normal[offset] = 0.0f;
                normal[offset + 1] = 0.0f;
                normal[offset + 2] = 0.0f;
                normal[offset + 3] = 1.0f;
                boolean participating = x >= 8 && x < 82;
                mask[offset] = participating ? 1.0f : 0.0f;
                mask[offset + 1] = participating ? 0.35f : 0.0f;
                mask[offset + 2] = participating ? 0.05f : 0.0f;
                mask[offset + 3] = participating ? 1.0f : 0.0f;
            }
        }
        return new Fixture(colour, depth, normal, mask);
    }

    @Test
    void pipelineInsertsOnePassAndDisabledResizeCloseStayClean() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Subsurface Pipeline GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            RenderSettings hdr = RenderSettings.builder()
                    .antiAliasingMode(AntiAliasingMode.NONE)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .vsync(false)
                    .build();
            int baseline = GlDebug.resources().liveResources().size();

            try (PostProcessPipeline pipeline = PostProcessPipeline.create(hdr,
                    PostProcessSettings.defaults(), SIZE, SIZE, null, NO_SURFACE, null, null);
                 RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> {
                        });
                pipeline.addFinalPass(graph);
                assertFalse(graph.hasPass(PostProcessTargets.SUBSURFACE_SCATTERING_PASS));
                assertEquals(PostProcessPipeline.passNamesFor(AntiAliasingMode.NONE,
                        ToneMappingMode.ACES), graph.description().executionOrder(),
                        "a disabled subsurface request must keep the exact baseline chain");
            }

            try (PostProcessPipeline pipeline = PostProcessPipeline.create(hdr,
                    PostProcessSettings.defaults().withSubsurfaceScattering(SETTINGS),
                    SIZE, SIZE, null, SSS_SURFACE, null, null);
                 RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> {
                        });
                pipeline.addFinalPass(graph);
                pipeline.validateSurfaceInputs();
                assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS,
                                PostProcessTargets.SUBSURFACE_SCATTERING_PASS,
                                PostProcessTargets.TONE_MAPPING_PASS,
                                PostProcessTargets.PRESENT_PASS),
                        graph.description().executionOrder(),
                        "subsurface diffusion must run on the linear HDR colour before tone mapping");
                assertEquals(PostProcessTargets.SUBSURFACE_SCATTERING_COLOR,
                        pipeline.linearHdrTextureName(),
                        "the HDR capture must see the diffused colour");
                assertTrue(graph.description().passes().stream()
                                .filter(pass -> pass.name()
                                        .equals(PostProcessTargets.SUBSURFACE_SCATTERING_PASS))
                                .findFirst().orElseThrow()
                                .directDependencies().contains(PostProcessTargets.GEOMETRY_PASS),
                        "the diffusion pass must depend on the surface producer");

                pipeline.resize(SIZE + 16, SIZE + 16);
                pipeline.prepareResize(SIZE + 16, SIZE + 16).close();
            }

            assertThrows(IllegalArgumentException.class,
                    () -> PostProcessPipeline.create(RenderSettings.builder()
                                    .antiAliasingMode(AntiAliasingMode.NONE)
                                    .toneMappingMode(ToneMappingMode.NONE)
                                    .vsync(false).build(),
                            PostProcessSettings.defaults().withSubsurfaceScattering(SETTINGS),
                            SIZE, SIZE, null, SSS_SURFACE, null, null),
                    "screen-space subsurface scattering requires HDR tone mapping");

            try (PostProcessPipeline pipeline = PostProcessPipeline.create(hdr,
                    PostProcessSettings.defaults().withSubsurfaceScattering(SETTINGS),
                    SIZE, SIZE, null, NO_SURFACE, null, null);
                 RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> {
                        });
                assertThrows(IllegalStateException.class, () -> pipeline.addFinalPass(graph),
                        "a missing SUBSURFACE surface input must be rejected");
            }

            assertEquals(baseline, GlDebug.resources().liveResources().size(),
                    "pipeline close must release the diffusion shader and quad");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static void assertBoundedDiffusion(Fixture fixture, float[] output) {
        int gap = pixel(50, 20);
        assertTrue(output[gap * 4] > 0.02f,
                "the near gap must receive diffusion from the scattering patch");
        assertTrue(output[gap * 4] < 2.0f,
                "the gap must not receive the bright far layer across the depth step");

        int highlight = pixel(40, 48);
        assertEquals(fixture.colour()[highlight * 4], output[highlight * 4], 1.0e-3f,
                "the specular centre must keep its original value");
        assertEquals(fixture.colour()[highlight * 4 + 1], output[highlight * 4 + 1], 1.0e-3f);
        assertTrue(output[(highlight + 2) * 4] < fixture.colour()[highlight * 4] * 0.2f,
                "the highlight must not smear a large halo into its neighbour");

        int nonParticipating = pixel(90, 20);
        assertEquals(fixture.colour()[nonParticipating * 4],
                output[nonParticipating * 4], 0.0f,
                "a zero-mask pixel must be copied exactly");
        assertEquals(fixture.colour()[nonParticipating * 4 + 1],
                output[nonParticipating * 4 + 1], 0.0f);

        assertTrue(changedPixels(output, fixture) > 0,
                "the fixture must produce a visible diffusion change");
    }

    private static int changedPixels(float[] output, Fixture fixture) {
        int changed = 0;
        for (int index = 0; index < SIZE * SIZE; index++) {
            for (int channel = 0; channel < 3; channel++) {
                if (Math.abs(output[index * 4 + channel]
                        - fixture.colour()[index * 4 + channel]) > 1.0e-3f) {
                    changed++;
                    break;
                }
            }
        }
        return changed;
    }

    private static void assertFinite(float[] image) {
        for (float value : image) {
            assertTrue(Float.isFinite(value), "diffusion output must be finite");
        }
    }

    private static int pixel(int x, int y) {
        return y * SIZE + x;
    }

    private static float[] render(GlRenderDevice device, Fixture fixture,
                                  SubsurfaceScatteringSettings settings) {
        try (Texture2D colour = floatTexture(4, fixture.colour());
             Texture2D depth = floatTexture(1, fixture.depth());
             Texture2D normal = floatTexture(4, fixture.normal());
             Texture2D mask = floatTexture(4, fixture.mask());
             Framebuffer target = Framebuffer.mrt(SIZE, SIZE, GL_RGBA32F);
             SubsurfaceScatteringPass pass = new SubsurfaceScatteringPass(settings)) {
            target.bind();
            glViewport(0, 0, SIZE, SIZE);
            CommandBuffer commands = device.createCommandBuffer();
            pass.recordIntoCurrentTarget(commands, colour.id(), depth.id(),
                    normal.id(), mask.id(), INVERSE_PROJECTION, INVERSE_VIEW,
                    SIZE, SIZE);
            device.execute(commands);
            target.bind();
            glViewport(0, 0, SIZE, SIZE);
            FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
            float[] result = new float[SIZE * SIZE * 4];
            pixels.get(result);
            return result;
        }
    }

    private static Texture2D floatTexture(int channels, float[] data) {
        int format = channels == 1 ? GL_R32F : GL_RGBA32F;
        Texture2D texture = Texture2D.createEmpty(SIZE, SIZE, format);
        ByteBuffer upload = BufferUtils.createByteBuffer(
                SIZE * SIZE * channels * Float.BYTES);
        upload.asFloatBuffer().put(data);
        texture.uploadRegion(0, 0, SIZE, SIZE, upload);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    /**
     * Synthetic scene: a near patch (x 30..44) with a 16.0 highlight, a black
     * near gap (x 44..52), a far bright layer (x >= 52), a normal-flipped bright
     * strip (y >= 60 still near depth) and a non-participating bright strip
     * (x >= 80). The fixture can darken either boundary control independently.
     */
    private static Fixture fixture(float radiusMetres, boolean brightFar, boolean brightNormal) {
        float[] colour = new float[SIZE * SIZE * 4];
        float[] depth = new float[SIZE * SIZE];
        float[] normal = new float[SIZE * SIZE * 4];
        float[] mask = new float[SIZE * SIZE * 4];
        float nearDepth = deviceDepth(-4.0f);
        float farDepth = deviceDepth(-8.0f);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int index = pixel(x, y);
                int offset = index * 4;
                float red = 0.0f;
                float green = 0.0f;
                float blue = 0.0f;
                if (x >= 30 && x < 44) {
                    red = 0.60f;
                    green = 0.30f;
                    blue = 0.15f;
                }
                boolean highlight = x == 40 && y == 48;
                if (highlight) red = green = blue = 16.0f;
                boolean far = x >= 52;
                if (far && brightFar) {
                    // Below the highlight-protection window, so a failed depth
                    // test would leak this value instead of being suppressed.
                    red = 1.5f;
                    green = 0.5f;
                    blue = 0.25f;
                }
                if (!far && y >= 60 && brightNormal) {
                    red = 1.5f;
                    green = 0.5f;
                    blue = 0.25f;
                }
                if (x >= 80) {
                    red = 8.0f;
                    green = 2.0f;
                    blue = 1.0f;
                }
                colour[offset] = red;
                colour[offset + 1] = green;
                colour[offset + 2] = blue;
                // The fixture's bright centre is a specular highlight: under the
                // S7 contract it carries alpha 0 and must never be diffused or
                // spread into its neighbours. Every diffuse pixel is alpha 1.
                colour[offset + 3] = highlight ? 0.0f : 1.0f;
                depth[index] = far ? farDepth : nearDepth;
                boolean flipped = !far && y >= 60;
                normal[offset] = 0.0f;
                normal[offset + 1] = flipped ? 1.0f : 0.0f;
                normal[offset + 2] = 0.0f;
                normal[offset + 3] = 1.0f;
                boolean participating = x < 80;
                mask[offset] = participating ? 1.0f : 0.0f;
                mask[offset + 1] = participating ? radiusMetres : 0.0f;
                mask[offset + 2] = participating ? 0.05f : 0.0f;
                mask[offset + 3] = participating ? 1.0f : 0.0f;
            }
        }
        return new Fixture(colour, depth, normal, mask);
    }

    private static float deviceDepth(float viewZ) {
        org.joml.Vector4f clip = new org.joml.Vector4f(0.0f, 0.0f, viewZ, 1.0f)
                .mul(PROJECTION);
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private record Fixture(float[] colour, float[] depth, float[] normal, float[] mask) {
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

    private static final SurfaceInputs SSS_SURFACE = new SurfaceInputs() {
        @Override
        public Input single(Channel channel) {
            return switch (channel) {
                case DEPTH -> new Input(channel, PostProcessTargets.SCENE_DEPTH,
                        PostProcessTargets.GEOMETRY_PASS);
                case NORMAL -> new Input(channel, PostProcessTargets.SCENE_NORMAL,
                        PostProcessTargets.GEOMETRY_PASS);
                case SUBSURFACE -> new Input(channel, PostProcessTargets.SCENE_SUBSURFACE,
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
}
