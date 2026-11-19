package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.RenderGraphTestAccess;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.upscale.TemporalUpscalingInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.upscale.TemporalUpscalingPass;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pipeline.PipelineGeneration;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecalSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBlitFramebuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;

/**
 * Real-GL acceptance for the reduced internal scene resolution + temporal
 * reconstruction boundary.
 *
 * <p>The scene surface/geometry passes render at the rounded scene extent R
 * while the internal temporal upscaler and every post-process pass stay at the
 * target extent O.  The upscaler consumes the generation-owned R colour and
 * single-sample R depth; no caller {@code TemporalUpscalingSource} is
 * registered.  Scale 1.0 must remain a byte-identical no-op and every
 * unsupported composition must still fail closed before a generation exists.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class LowResolutionMainChainGlTest {
    private static final int REQUESTED_WIDTH = 96;
    private static final int REQUESTED_HEIGHT = 72;
    private static final float REDUCED_SCALE = 0.5f;

    private static final String FULLSCREEN_VERTEX_SOURCE = """
            #version 460 core
            layout (location = 0) in vec3 aPos;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            out vec2 vUv;
            void main() {
                vec4 clip = uProjection * uView * uModel * vec4(aPos, 1.0);
                vUv = clip.xy / clip.w * 0.5 + 0.5;
                gl_Position = clip;
            }
            """;

    /**
     * Two flat regions with one axis-aligned transition.  Bilinear magnification
     * of a flat region is exact, so the interior comparison needs no edge
     * tolerance; the transition band is excluded on purpose.
     */
    private static final String FLAT_INTERIOR_FRAGMENT_SOURCE = """
            #version 460 core
            in vec2 vUv;
            out vec4 FragColor;
            void main() {
                vec2 delta = abs(vUv - vec2(0.5));
                bool inside = delta.x < 0.3 && delta.y < 0.3;
                FragColor = inside ? vec4(0.8, 0.3, 0.6, 1.0)
                                   : vec4(0.12, 0.2, 0.35, 1.0);
            }
            """;

    /**
     * World-anchored variant: the reduced raster content is a function of the
     * fragment's world position, so a jittered projection visibly moves the
     * boundaries relative to the screen.  The independent reference below
     * recomputes the same world position from the jittered ray in double.
     */
    private static final String WORLD_ANCHORED_VERTEX_SOURCE = """
            #version 460 core
            layout (location = 0) in vec3 aPos;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            out vec3 vWorld;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                vWorld = world.xyz;
                gl_Position = uProjection * uView * world;
            }
            """;

    /**
     * Sharp world-space cell grid.  Every sampled reduced pixel is exactly one
     * of two constants, so a wrong jitter sign or magnitude flips a whole
     * column/row of pixels rather than hiding inside filtering noise.  Several
     * cell boundaries make at least one flip certain for a sub-pixel phase step.
     */
    private static final String WORLD_STEP_FRAGMENT_SOURCE = """
            #version 460 core
            in vec3 vWorld;
            out vec4 FragColor;
            void main() {
                bool inside = fract(vWorld.x / 1.7) < 0.5
                        && fract(vWorld.y / 1.3) < 0.5;
                FragColor = inside ? vec4(0.75, 0.125, 0.5, 1.0)
                                   : vec4(0.0625, 0.8125, 0.25, 1.0);
            }
            """;

    private static final float[] WORLD_STEP_INSIDE = {0.75f, 0.125f, 0.5f};
    private static final float[] WORLD_STEP_OUTSIDE = {0.0625f, 0.8125f, 0.25f};

    /**
     * Independent copy of the documented reduced-raster pattern in reduced
     * pixels.  Deliberately not read from {@code TemporalJitter}: the phase
     * reference must fail if the production sequence changes sign, magnitude or
     * ordering.
     */
    private static final double[][] REDUCED_JITTER_PIXELS = {
            {-0.25, -0.25},
            {0.25, -0.25},
            {-0.25, 0.25},
            {0.25, 0.25}
    };
    private static final double WORLD_CELL_X = 1.7;
    private static final double WORLD_CELL_Y = 1.3;

    @Test
    void scaleOneFrameStaysByteIdenticalAfterAReducedGeneration() {
        try (GlfwWindow window = window("Low-Resolution Identity")) {
            GlRenderDevice device = new GlRenderDevice();
            try (Fixture fixture = new Fixture()) {
                Scene scene = fixture.scene();
                float[] first;
                RenderPipeline firstPipeline = buildPipeline(window, scene, 1.0f,
                        PostProcessSettings.defaults());
                try {
                    assertFalse(firstPipeline.graph().hasPass(
                                    PostProcessTargets.TEMPORAL_UPSCALING_PASS),
                            "renderScale 1.0 must not insert the temporal reconstruction pass");
                    assertNull(firstPipeline.temporalUpscalingSource());
                    firstPipeline.execute(device, 1.0f / 60.0f);
                    first = firstPipeline.captureLinearHdrRgbaFloat();
                    assertEquals(window.width() * window.height() * 4, first.length);
                } finally {
                    firstPipeline.close();
                }

                RenderPipeline reducedPipeline = buildPipeline(window, scene, REDUCED_SCALE,
                        PostProcessSettings.defaults());
                try {
                    reducedPipeline.execute(device, 1.0f / 60.0f);
                    assertNotNull(reducedPipeline.captureLinearHdrRgbaFloat());
                } finally {
                    reducedPipeline.close();
                }

                RenderPipeline rebuiltPipeline = buildPipeline(window, scene, 1.0f,
                        PostProcessSettings.defaults());
                try {
                    rebuiltPipeline.execute(device, 1.0f / 60.0f);
                    assertArrayEquals(first, rebuiltPipeline.captureLinearHdrRgbaFloat(),
                            "a renderScale 1.0 frame after a 0.5 generation must be byte-identical");
                    assertFalse(rebuiltPipeline.graph().hasPass(
                                    PostProcessTargets.TEMPORAL_UPSCALING_PASS),
                            "the rebuilt full-resolution generation must not add a reconstruction pass");
                } finally {
                    rebuiltPipeline.close();
                }
            }
            GlDebug.assertNoError("low-resolution scale-one identity");
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the identity case must release every tracked GL resource");
        }
    }

    @Test
    void halfScaleReconstructsAtTheTargetAndMatchesTheSpatialAndIndependentUpscaler() {
        // This case pins the jitter-0 contract: the disable switch must make the
        // internal resolve reproduce the independent pass and the spatial
        // magnifier.  The nonzero-jitter behavior is covered by the dedicated
        // jitter cases below.
        System.setProperty(RenderPipeline.DISABLE_REDUCED_SCENE_JITTER_PROPERTY, "true");
        try (GlfwWindow window = window("Low-Resolution Half Scale")) {
            GlRenderDevice device = new GlRenderDevice();
            int targetWidth = window.width();
            int targetHeight = window.height();
            int sceneWidth = scaledExtent(targetWidth, REDUCED_SCALE);
            int sceneHeight = scaledExtent(targetHeight, REDUCED_SCALE);
            try (Fixture fixture = new Fixture()) {
                RenderPipeline reducedPipeline = buildPipeline(window, fixture.scene(),
                        REDUCED_SCALE, PostProcessSettings.defaults());
                try {
                    reducedPipeline.execute(device, 1.0f / 60.0f);
                    assertEquals(window.width(), targetWidth);
                    RenderGraph.Description description = reducedPipeline.graph().description();
                    RenderGraph.PassDescription reconstruction = pass(description,
                            PostProcessTargets.TEMPORAL_UPSCALING_PASS);
                    assertNotNull(reconstruction,
                            "renderScale 0.5 must insert the internal temporal reconstruction pass");
                    assertEquals(RenderGraph.TargetKind.EXTERNAL, reconstruction.targetKind(),
                            "the reconstruction pass owns its O-sized history target");
                    assertTrue(reconstruction.borrowedExternalStorage().isEmpty(),
                            "the internal source must not be imported as caller storage");
                    assertTrue(reconstruction.directDependencies().contains(
                                    PostProcessTargets.GEOMETRY_PASS),
                            "the reconstruction pass must depend on the scene colour producer");
                    RenderGraph.PassDescription geometry = pass(description,
                            PostProcessTargets.GEOMETRY_PASS);
                    assertNotNull(geometry);
                    assertEquals(sceneWidth, geometry.width(),
                            "the geometry pass must rasterise at the scene extent");
                    assertEquals(sceneHeight, geometry.height());
                    assertNull(reducedPipeline.temporalUpscalingSource(),
                            "no caller TemporalUpscalingSource may be registered on the internal path");
                    assertTrue(temporalHistoryValid(reducedPipeline),
                            "the pipeline must commit the internal upscaling history");

                    float[] reduced = reducedPipeline.captureLinearHdrRgbaFloat();
                    assertEquals(targetWidth * targetHeight * 4, reduced.length,
                            "the reconstructed output must have the target extent");
                    for (float value : reduced) {
                        assertTrue(Float.isFinite(value),
                                "the reconstructed output must stay finite");
                    }

                    int sceneColor = RenderGraphTestAccess.textureId(reducedPipeline.graph(),
                            PostProcessTargets.SCENE_COLOR);
                    int sceneDepth = RenderGraphTestAccess.textureId(reducedPipeline.graph(),
                            PostProcessTargets.SCENE_DEPTH);
                    assertTrue(sceneColor > 0, "the scene colour attachment must exist at R");
                    assertTrue(sceneDepth > 0,
                            "the reduced scene must expose its single-sample depth as a texture");

                    float[] independent = independentUpscale(device, fixture.scene().camera(),
                            sceneColor, sceneDepth, sceneWidth, sceneHeight,
                            targetWidth, targetHeight);
                    assertRgbaClose(independent, reduced, 2.0e-3f,
                            "the pipeline must match the independently driven TemporalUpscalingPass");

                    float[] spatial = spatialMagnifier(sceneColor, sceneWidth, sceneHeight,
                            targetWidth, targetHeight);
                    assertRgbaClose(spatial, reduced, 4.0e-3f,
                            "at jitter 0 the temporal reconstruction must reproduce the spatial "
                                    + "magnifier geometry");
                    assertRgbaClose(spatial, independent, 4.0e-3f,
                            "the independent pass must also match the spatial magnifier at jitter 0");
                    assertFlatInteriorsMatch(window, fixture, device, reduced);
                } finally {
                    reducedPipeline.close();
                }
            }
            GlDebug.assertNoError("low-resolution half-scale temporal reconstruction");
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the half-scale case must release every tracked GL resource");
        } finally {
            System.clearProperty(RenderPipeline.DISABLE_REDUCED_SCENE_JITTER_PROPERTY);
        }
    }

    @Test
    void surfacePrepassUsesTheSceneExtentWhileTheChainStaysAtTheTarget() {
        try (GlfwWindow window = window("Low-Resolution Surface Extent")) {
            GlRenderDevice device = new GlRenderDevice();
            int targetWidth = window.width();
            int targetHeight = window.height();
            int sceneWidth = scaledExtent(targetWidth, REDUCED_SCALE);
            int sceneHeight = scaledExtent(targetHeight, REDUCED_SCALE);
            // Analytic fog is the allowed composition that requests the shared
            // single-sample scene depth, so the surface prepass exists.
            PostProcessSettings fog = PostProcessSettings.builder()
                    .fog(FogSettings.builder().enabled(true).build()).build();
            try (Fixture fixture = new Fixture()) {
                RenderPipeline pipeline = buildPipeline(window, fixture.scene(),
                        REDUCED_SCALE, fog);
                try {
                    pipeline.execute(device, 1.0f / 60.0f);
                    RenderGraph.Description description = pipeline.graph().description();
                    RenderGraph.PassDescription surface = pass(description,
                            PostProcessTargets.SCENE_SURFACE_PASS);
                    assertNotNull(surface, "fog must declare the shared surface prepass");
                    assertEquals(sceneWidth, surface.width(),
                            "the surface prepass must render at the scene extent");
                    assertEquals(sceneHeight, surface.height());
                    assertNotNull(surface.depthAttachment());
                    RenderGraph.PassDescription reconstruction = pass(description,
                            PostProcessTargets.TEMPORAL_UPSCALING_PASS);
                    assertNotNull(reconstruction);
                    assertTrue(reconstruction.directDependencies().contains(
                                    PostProcessTargets.SCENE_SURFACE_PASS),
                            "the reconstruction must sample the shared surface depth producer");
                    RenderGraph.PassDescription fogPass = pass(description,
                            PostProcessTargets.FOG_PASS);
                    assertNotNull(fogPass);
                    assertEquals(targetWidth, fogPass.width(),
                            "the post-process chain must stay at the target extent");
                    assertEquals(targetHeight, fogPass.height());
                    float[] pixels = pipeline.captureLinearHdrRgbaFloat();
                    assertEquals(targetWidth * targetHeight * 4, pixels.length);
                    for (float value : pixels) {
                        assertTrue(Float.isFinite(value));
                    }
                } finally {
                    pipeline.close();
                }
            }
            GlDebug.assertNoError("low-resolution surface prepass");
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the surface-extent case must release every tracked GL resource");
        }
    }

    @Test
    void targetResizeAtReducedScaleRebuildsExactlyOneGeneration() {
        try (GlfwWindow window = window("Low-Resolution Resize")) {
            GlRenderDevice device = new GlRenderDevice();
            int resizedWidth = 80;
            int resizedHeight = 60;
            int sceneWidth = scaledExtent(resizedWidth, REDUCED_SCALE);
            int sceneHeight = scaledExtent(resizedHeight, REDUCED_SCALE);
            try (Fixture fixture = new Fixture()) {
                RenderPipeline pipeline = buildPipeline(window, fixture.scene(),
                        REDUCED_SCALE, PostProcessSettings.defaults());
                try {
                    pipeline.execute(device, 1.0f / 60.0f);
                    long buildsBefore = pipeline.lastRender3dDiagnostics()
                            .caches().generationBuilds();
                    try (Framebuffer target = Framebuffer.colorOnly(resizedWidth, resizedHeight)) {
                        PresentationTarget presentation = PresentationTarget.borrowed(
                                target.id(),
                                ExternalAttachment.borrowedColor(target.colorAttachment(0),
                                        RenderFormat.RGBA8, resizedWidth, resizedHeight),
                                null, resizedWidth, resizedHeight, 1L);
                        Camera camera = fixture.scene().camera();
                        Matrix4f projection = CameraProjection.stable(camera, resizedWidth,
                                resizedHeight, new Matrix4f());
                        ExternalCamera external = ExternalCamera.of(
                                camera.getViewMatrix(new Matrix4f()), projection,
                                camera.position(), 0.0f);
                        pipeline.execute(device, external, presentation, 1.0f / 60.0f);
                        long buildsAfter = pipeline.lastRender3dDiagnostics()
                                .caches().generationBuilds();
                        assertEquals(buildsBefore + 1, buildsAfter,
                                "a target resize at a reduced scale must rebuild exactly one generation");
                        assertEquals(resizedWidth, pipeline.graph().width());
                        assertEquals(resizedHeight, pipeline.graph().height());
                        RenderGraph.Description description = pipeline.graph().description();
                        RenderGraph.PassDescription reconstruction = pass(description,
                                PostProcessTargets.TEMPORAL_UPSCALING_PASS);
                        assertNotNull(reconstruction,
                                "the replacement generation must own a fresh reconstruction pass");
                        assertEquals(RenderGraph.TargetKind.EXTERNAL,
                                reconstruction.targetKind());
                        RenderGraph.PassDescription geometry = pass(description,
                                PostProcessTargets.GEOMETRY_PASS);
                        assertNotNull(geometry);
                        assertEquals(sceneWidth, geometry.width(),
                                "the replacement generation must move the scene extent with the target");
                        assertEquals(sceneHeight, geometry.height());
                        assertNull(pipeline.temporalUpscalingSource());
                        assertTrue(temporalHistoryValid(pipeline),
                                "the resized generation must commit its own O-sized history");
                        assertEquals(resizedWidth * resizedHeight * 4,
                                pipeline.captureLinearHdrRgbaFloat().length,
                                "the history/output extent must follow the resized target");
                    }
                } finally {
                    pipeline.close();
                }
            }
            GlDebug.assertNoError("low-resolution target resize");
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the resize case must release every tracked GL resource");
        }
    }

    @Test
    void repeatedReducedFramesStayFiniteAndLeakFree() {
        // Static-frame stability is the jitter-0 contract; with the sequence
        // enabled consecutive frames deliberately differ at sub-pixel edges (the
        // convergence/observability case covers that path).
        System.setProperty(RenderPipeline.DISABLE_REDUCED_SCENE_JITTER_PROPERTY, "true");
        try (GlfwWindow window = window("Low-Resolution Stability")) {
            GlRenderDevice device = new GlRenderDevice();
            int targetWidth = window.width();
            int targetHeight = window.height();
            try (Fixture fixture = new Fixture()) {
                RenderPipeline pipeline = buildPipeline(window, fixture.scene(),
                        REDUCED_SCALE, PostProcessSettings.defaults());
                try {
                    float[] previous = null;
                    for (int frame = 0; frame < 3; frame++) {
                        pipeline.execute(device, 1.0f / 60.0f);
                        assertTrue(temporalHistoryValid(pipeline),
                                "frame " + frame + " must commit the internal history");
                        float[] current = pipeline.captureLinearHdrRgbaFloat();
                        assertEquals(targetWidth * targetHeight * 4, current.length);
                        for (float value : current) {
                            assertTrue(Float.isFinite(value),
                                    "frame " + frame + " output must stay finite");
                        }
                        if (previous != null) {
                            assertRgbaClose(previous, current, 1.0e-3f,
                                    "a static scene must stay stable across reconstructed frames");
                        }
                        previous = current;
                    }
                } finally {
                    pipeline.close();
                }
            }
            GlDebug.assertNoError("low-resolution multi-frame stability");
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the stability case must release every tracked GL resource");
        } finally {
            System.clearProperty(RenderPipeline.DISABLE_REDUCED_SCENE_JITTER_PROPERTY);
        }
    }

    /**
     * Requirement (a): the internal reduced raster is jittered with a
     * deterministic nonzero sequence, consecutive frames differ before the
     * history converges, and the converged output matches the scale-1 reference
     * on flat interiors within 1e-3 (the same tolerance the jitter-0 slice used).
     */
    @Test
    void nonzeroReducedJitterConvergesToTheFullResolutionReferenceAndIsObservable() {
        try (GlfwWindow window = window("Low-Resolution Jitter Convergence")) {
            GlRenderDevice device = new GlRenderDevice();
            try (Fixture fixture = new Fixture()) {
                RenderPipeline pipeline = buildPipeline(window, fixture.scene(), REDUCED_SCALE,
                        PostProcessSettings.defaults());
                try {
                    int width = window.width();
                    int height = window.height();
                    float[] full = fullResolutionReference(window, fixture, device);
                    float[] first = null;
                    float[] second = null;
                    float[] last = null;
                    for (int frame = 0; frame < 12; frame++) {
                        pipeline.execute(device, 1.0f / 60.0f);
                        assertTrue(temporalHistoryValid(pipeline),
                                "frame " + frame + " must commit the internal history");
                        float[] output = pipeline.captureLinearHdrRgbaFloat();
                        assertEquals(width * height * 4, output.length);
                        assertFinite(output, "jittered frame " + frame);
                        if (frame == 0) first = output;
                        if (frame == 1) second = output;
                        last = output;
                    }
                    float difference = maxChannelDifference(first, second);
                    assertTrue(difference > 1.0e-3f,
                            "the nonzero jitter must change the frame before convergence, max "
                                    + "channel difference " + difference);
                    assertFlatInteriorsMatch(full, last, width, height);
                    double firstBand = transitionBandMeanAbsoluteError(first, full, width, height);
                    double convergedBand = transitionBandMeanAbsoluteError(last, full, width,
                            height);
                    System.out.println("[lowres-jitter] frame0-vs-frame1 max-channel-diff="
                            + difference + " first transition-band MAE=" + firstBand
                            + " frame12 transition-band MAE=" + convergedBand);
                    assertTrue(convergedBand < 0.05,
                            "the converged jittered reconstruction must stay close to the "
                                    + "full-resolution reference at the transition band, got "
                                    + convergedBand);
                    assertTrue(convergedBand < firstBand * 1.25,
                            "history accumulation must stay bounded relative to the first "
                                    + "frame at the transition band: " + convergedBand
                                    + " vs first " + firstBand);
                } finally {
                    pipeline.close();
                }
            }
            GlDebug.assertNoError("low-resolution jitter convergence");
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the jitter convergence case must release every tracked GL resource");
        }
    }

    /**
     * Requirement (b): for every phase of the known four-phase sequence, the
     * reduced raster read back from the generation must equal an independent
     * double-precision ray/projection reference.  The world-anchored cell grid
     * makes a wrong jitter sign or magnitude flip whole columns of reduced
     * pixels.
     */
    @Test
    void jitteredReducedRasterMatchesAnIndependentRayReferenceAtKnownPhases() {
        try (GlfwWindow window = window("Low-Resolution Jitter Phase Reference")) {
            GlRenderDevice device = new GlRenderDevice();
            try (Fixture fixture = Fixture.worldAnchored()) {
                RenderPipeline pipeline = buildPipeline(window, fixture.scene(), REDUCED_SCALE,
                        PostProcessSettings.defaults());
                try {
                    int outputWidth = window.width();
                    int outputHeight = window.height();
                    int reducedWidth = scaledExtent(outputWidth, REDUCED_SCALE);
                    int reducedHeight = scaledExtent(outputHeight, REDUCED_SCALE);
                    float[][] rasters = new float[REDUCED_JITTER_PIXELS.length][];
                    for (int phase = 0; phase < REDUCED_JITTER_PIXELS.length; phase++) {
                        pipeline.execute(device, 1.0f / 60.0f);
                        assertEquals((phase + 1) % REDUCED_JITTER_PIXELS.length,
                                currentReducedJitterPhase(pipeline),
                                "the sequence must advance once per successful frame");
                        int sceneColor = RenderGraphTestAccess.textureId(pipeline.graph(),
                                PostProcessTargets.SCENE_COLOR);
                        assertTrue(sceneColor > 0,
                                "the reduced scene colour attachment must exist");
                        rasters[phase] = readColorTexture(sceneColor, reducedWidth, reducedHeight);
                        assertWorldStepReference(fixture.scene().camera(), outputWidth,
                                outputHeight, reducedWidth, reducedHeight, phase, rasters[phase]);
                    }
                    for (int phase = 1; phase < rasters.length; phase++) {
                        assertTrue(maxChannelDifference(rasters[0], rasters[phase]) > 1.0e-3f,
                                "phase " + phase + " must be observably different from phase 0");
                    }
                    assertEquals(0, currentReducedJitterPhase(pipeline),
                            "four successful frames must wrap the sequence back to phase 0");
                    pipeline.execute(device, 1.0f / 60.0f);
                    assertEquals(1, currentReducedJitterPhase(pipeline),
                            "the wrapped frame uses phase 0 and advances to phase 1");
                    int wrappedColor = RenderGraphTestAccess.textureId(pipeline.graph(),
                            PostProcessTargets.SCENE_COLOR);
                    float[] wrapped = readColorTexture(wrappedColor, reducedWidth, reducedHeight);
                    assertEquals(0.0f, maxChannelDifference(rasters[0], wrapped),
                            "the wrapped phase-0 raster must be deterministic");
                } finally {
                    pipeline.close();
                }
            }
            GlDebug.assertNoError("low-resolution jitter phase reference");
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the jitter phase case must release every tracked GL resource");
        }
    }

    /**
     * Requirement (c): the test-only disable switch reproduces the jitter-0
     * output exactly.  The pipeline frame is compared bit for bit with an
     * independently driven {@link TemporalUpscalingPass} on the same reduced
     * colour/depth attachments, and the enabled first frame must differ.
     */
    @Test
    void disablingTheReducedJitterSequenceRestoresTheJitterZeroOutput() {
        try (GlfwWindow window = window("Low-Resolution Jitter Disable")) {
            GlRenderDevice device = new GlRenderDevice();
            try (Fixture fixture = new Fixture()) {
                float[] jitteredFirst;
                RenderPipeline jittered = buildPipeline(window, fixture.scene(), REDUCED_SCALE,
                        PostProcessSettings.defaults());
                try {
                    jittered.execute(device, 1.0f / 60.0f);
                    jitteredFirst = jittered.captureLinearHdrRgbaFloat();
                } finally {
                    jittered.close();
                }

                float[] disabledFirst;
                float[] independent;
                System.setProperty(RenderPipeline.DISABLE_REDUCED_SCENE_JITTER_PROPERTY, "true");
                try {
                    RenderPipeline disabled = buildPipeline(window, fixture.scene(),
                            REDUCED_SCALE, PostProcessSettings.defaults());
                    try {
                        disabled.execute(device, 1.0f / 60.0f);
                        disabledFirst = disabled.captureLinearHdrRgbaFloat();
                        int sceneWidth = scaledExtent(window.width(), REDUCED_SCALE);
                        int sceneHeight = scaledExtent(window.height(), REDUCED_SCALE);
                        int sceneColor = RenderGraphTestAccess.textureId(disabled.graph(),
                                PostProcessTargets.SCENE_COLOR);
                        int sceneDepth = RenderGraphTestAccess.textureId(disabled.graph(),
                                PostProcessTargets.SCENE_DEPTH);
                        independent = independentUpscale(device, fixture.scene().camera(),
                                sceneColor, sceneDepth, sceneWidth, sceneHeight,
                                window.width(), window.height());
                    } finally {
                        disabled.close();
                    }
                } finally {
                    System.clearProperty(
                            RenderPipeline.DISABLE_REDUCED_SCENE_JITTER_PROPERTY);
                }
                assertArrayEquals(independent, disabledFirst, 0.0f,
                        "with the sequence disabled the internal resolve must reproduce the "
                                + "jitter-0 output bit for bit");
                assertTrue(maxChannelDifference(jitteredFirst, disabledFirst) > 1.0e-3f,
                        "the enabled sequence must change the first reconstructed frame");
            }
            GlDebug.assertNoError("low-resolution jitter disable");
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the jitter disable case must release every tracked GL resource");
        }
    }

    /**
     * Requirement (d): a camera cut invalidates the reduced reconstruction
     * history and restarts its sequence, and an explicit history reset does the
     * same, so the world-anchored step never retains the previous view.  The
     * assertion counts pixels that keep the wrong side of the step after
     * excluding a 3-output-pixel band around the geometric boundary.
     */
    @Test
    void cameraCutAndHistoryResetDoNotLeaveAPersistentGhost() {
        try (GlfwWindow window = window("Low-Resolution Jitter Camera Cut")) {
            GlRenderDevice device = new GlRenderDevice();
            try (Fixture fixture = Fixture.worldAnchored()) {
                RenderPipeline pipeline = buildPipeline(window, fixture.scene(), REDUCED_SCALE,
                        PostProcessSettings.defaults());
                try {
                    int width = window.width();
                    int height = window.height();
                    Camera camera = fixture.scene().camera();
                    for (int frame = 0; frame < 5; frame++) {
                        pipeline.execute(device, 1.0f / 60.0f);
                    }
                    assertEquals(1, currentReducedJitterPhase(pipeline));
                    float[] beforeCut = pipeline.captureLinearHdrRgbaFloat();
                    assertEquals(0, misclassifiedPixels(camera, width, height, beforeCut, 3),
                            "the settled pre-cut view must show the current step");

                    // 5 m jump: above the documented camera-cut distance.
                    camera.setPosition(new Vector3f(5.0f, 0.0f, 5.0f));
                    pipeline.execute(device, 1.0f / 60.0f);
                    float[] afterCut = pipeline.captureLinearHdrRgbaFloat();
                    assertEquals(1, currentReducedJitterPhase(pipeline),
                            "the cut frame must be rasterised with phase 0 and advance to phase 1");
                    assertEquals(0, misclassifiedPixels(camera, width, height, afterCut, 3),
                            "a camera cut must not keep history from the previous view");
                    assertTrue(maxChannelDifference(beforeCut, afterCut) > 0.05f,
                            "the camera cut must change the rendered view");

                    for (int frame = 0; frame < 4; frame++) {
                        pipeline.execute(device, 1.0f / 60.0f);
                    }
                    float[] settled = pipeline.captureLinearHdrRgbaFloat();
                    assertEquals(0, misclassifiedPixels(camera, width, height, settled, 3),
                            "the post-cut view must stay ghost-free");

                    // Small move (below the cut threshold) plus an explicit reset.
                    camera.setPosition(new Vector3f(5.0f, 0.3f, 5.0f));
                    for (int frame = 0; frame < 3; frame++) {
                        pipeline.execute(device, 1.0f / 60.0f);
                    }
                    pipeline.resetTemporalHistory();
                    assertEquals(0, currentReducedJitterPhase(pipeline),
                            "an explicit history reset must restart the sequence at phase 0");
                    pipeline.execute(device, 1.0f / 60.0f);
                    float[] afterReset = pipeline.captureLinearHdrRgbaFloat();
                    assertEquals(0, misclassifiedPixels(camera, width, height, afterReset, 3),
                            "an explicit history reset must not keep a ghost of the old view");
                    assertEquals(1, currentReducedJitterPhase(pipeline),
                            "the frame after an explicit reset uses phase 0 and advances to 1");
                } finally {
                    pipeline.close();
                }
            }
            GlDebug.assertNoError("low-resolution jitter camera cut");
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the camera-cut case must release every tracked GL resource");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectionCases")
    void unsupportedCompositionsThrowBeforeAnyGeneration(RejectionCase testCase) {
        try (GlfwWindow window = window("Low-Resolution Rejection " + testCase.code())) {
            try (Fixture fixture = new Fixture()) {
                RenderPipeline pipeline = new RenderPipeline(window, fixture.scene(), null,
                        testCase.render())
                        .postProcessSettings(testCase.effects());
                try {
                    testCase.configure().accept(pipeline);
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                            pipeline::build,
                            "renderScale 0.5 must reject " + testCase.code()
                                    + " before a generation");
                    assertTrue(failure.getMessage().contains(testCase.code()),
                            () -> "expected reason '" + testCase.code() + "' but got: "
                                    + failure.getMessage());
                    assertNull(pipeline.graph(),
                            "the rejection must happen before any candidate graph exists");
                } finally {
                    pipeline.close();
                }
            }
            GlDebug.assertNoError("low-resolution rejection " + testCase.code());
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "rejection case " + testCase.code() + " must not lease a GL resource");
        }
    }

    /** One fail-closed composition; the code is the documented rejection reason. */
    record RejectionCase(String code, RenderSettings render, PostProcessSettings effects,
                         Consumer<RenderPipeline> configure) {
        @Override
        public String toString() {
            return code;
        }
    }

    static Stream<RejectionCase> rejectionCases() {
        PostProcessSettings none = PostProcessSettings.defaults();
        return Stream.of(
                new RejectionCase("msaa",
                        settings(REDUCED_SCALE, AntiAliasingMode.MSAA), none, pipeline -> {
                }),
                new RejectionCase("taa",
                        settings(REDUCED_SCALE, AntiAliasingMode.TAA), none, pipeline -> {
                }),
                new RejectionCase("ssr", settings(REDUCED_SCALE, AntiAliasingMode.NONE),
                        PostProcessSettings.builder().ssr(SsrSettings.defaults()).build(),
                        pipeline -> {
                        }),
                new RejectionCase("ssgi", settings(REDUCED_SCALE, AntiAliasingMode.NONE),
                        PostProcessSettings.builder().ssgi(SsgiSettings.defaults()).build(),
                        pipeline -> {
                        }),
                new RejectionCase("gtao", settings(REDUCED_SCALE, AntiAliasingMode.NONE),
                        PostProcessSettings.builder()
                                .gtao(GtaoSettings.defaults().withEnabled(true)).build(),
                        pipeline -> {
                        }),
                new RejectionCase("bloom", hdrBuilder(REDUCED_SCALE)
                        .bloomSettings(BloomSettings.builder().enabled(true).maxLevels(3).build())
                        .build(), none, pipeline -> {
                        }),
                new RejectionCase("auto-exposure", hdrBuilder(REDUCED_SCALE)
                        .exposureMode(ExposureMode.AUTO).build(), none, pipeline -> {
                        }),
                new RejectionCase("projected-decals",
                        settings(REDUCED_SCALE, AntiAliasingMode.NONE), none,
                        pipeline -> pipeline.applyProjectedDecals(new ProjectedDecalSettings(
                                true, 0.6f, 0.02f, 0.5f, false, true, 8))),
                new RejectionCase("water-surface",
                        settings(REDUCED_SCALE, AntiAliasingMode.NONE), none,
                        pipeline -> pipeline.waterSurface(
                                WaterSurfaceSettings.builder().enabled(true).build())),
                new RejectionCase("hair-strands",
                        settings(REDUCED_SCALE, AntiAliasingMode.NONE), none,
                        pipeline -> pipeline.hairStrands(
                                HairStrandSettings.disabled().withEnabled(true))),
                new RejectionCase("weighted-oit",
                        settings(REDUCED_SCALE, AntiAliasingMode.NONE), none,
                        pipeline -> pipeline.weightedOit(WeightedOitSettings.defaults())));
    }

    /**
     * Drives a standalone {@link TemporalUpscalingPass} over the generation's
     * R colour/depth attachments with exactly the internal settings and a
     * jitter-zero pose.
     *
     * <p>The stable projection is built at the <b>output</b> extent because the
     * pipeline's frozen frame camera carries the output-aspect projection and
     * the reduced passes reuse it unchanged; the reduced extent only changes the
     * texel grid, not the frustum.</p>
     */
    private static float[] independentUpscale(GlRenderDevice device, Camera camera,
                                              int colorTexture, int depthTexture,
                                              int inputWidth, int inputHeight,
                                              int outputWidth, int outputHeight) {
        TemporalUpscalingSettings upscaling =
                TemporalUpscalingSettings.internalLowResolution(REDUCED_SCALE);
        TemporalUpscalingPass pass = new TemporalUpscalingPass(upscaling, outputWidth,
                outputHeight);
        try {
            Matrix4f projection = CameraProjection.stable(camera, outputWidth, outputHeight,
                    new Matrix4f());
            Matrix4f view = camera.getViewMatrix(new Matrix4f());
            Matrix4f stableViewProjection = new Matrix4f(projection).mul(view);
            pass.beginFrame();
            var commands = device.createCommandBuffer();
            pass.record(commands, new TemporalUpscalingInputs(
                    colorTexture, depthTexture, 0, 0, 0, 0, inputWidth, inputHeight,
                    0.0f, 0.0f, settings(REDUCED_SCALE, AntiAliasingMode.NONE).exposure(),
                    new Matrix4f(projection).invert(),
                    new Matrix4f(view).invert(),
                    stableViewProjection));
            device.execute(commands);
            pass.frameSucceeded();
            return readFloat(pass.readOutputFramebuffer());
        } finally {
            pass.close();
        }
    }

    /**
     * Reproduces the removed spatial magnifier independently: a GL_LINEAR
     * colour blit from the R scene colour attachment into an O RGBA16F target.
     */
    private static float[] spatialMagnifier(int sourceColor, int sourceWidth, int sourceHeight,
                                            int targetWidth, int targetHeight) {
        try (Framebuffer target = Framebuffer.fromDescriptor(FramebufferDescriptor
                .builder(targetWidth, targetHeight)
                .colorTexture(RenderFormat.RGBA16F)
                .build())) {
            int readFramebuffer = glGenFramebuffers();
            try {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
                glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_TEXTURE_2D, sourceColor, 0);
                glReadBuffer(GL_COLOR_ATTACHMENT0);
                assertEquals(GL_FRAMEBUFFER_COMPLETE,
                        glCheckFramebufferStatus(GL_READ_FRAMEBUFFER),
                        "the scene colour attachment must be blit-complete");
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.id());
                glBlitFramebuffer(0, 0, sourceWidth, sourceHeight,
                        0, 0, targetWidth, targetHeight,
                        GL_COLOR_BUFFER_BIT, GL_LINEAR);
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
            } finally {
                glDeleteFramebuffers(readFramebuffer);
            }
            return readFloat(target);
        }
    }

    private static void assertFlatInteriorsMatch(GlfwWindow window, Fixture fixture,
                                                 GlRenderDevice device, float[] reduced) {
        assertFlatInteriorsMatch(fullResolutionReference(window, fixture, device), reduced,
                window.width(), window.height());
    }

    private static float[] fullResolutionReference(GlfwWindow window, Fixture fixture,
                                                   GlRenderDevice device) {
        RenderPipeline reference = buildPipeline(window, fixture.scene(), 1.0f,
                PostProcessSettings.defaults());
        try {
            reference.execute(device, 1.0f / 60.0f);
            return reference.captureLinearHdrRgbaFloat();
        } finally {
            reference.close();
        }
    }

    private static void assertFlatInteriorsMatch(float[] full, float[] reduced,
                                                 int targetWidth, int targetHeight) {
        int inside = 0;
        int outside = 0;
        for (int y = 0; y < targetHeight; y++) {
            float v = (y + 0.5f) / targetHeight;
            for (int x = 0; x < targetWidth; x++) {
                float u = (x + 0.5f) / targetWidth;
                float dx = Math.abs(u - 0.5f);
                float dy = Math.abs(v - 0.5f);
                // The shader transition sits at 0.3; keep a >1 scene texel
                // margin on both sides so only flat regions are compared.
                boolean flatInside = dx < 0.2f && dy < 0.2f;
                boolean flatOutside = dx > 0.42f || dy > 0.42f;
                if (!flatInside && !flatOutside) continue;
                int index = (y * targetWidth + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(full[index + channel], reduced[index + channel], 1.0e-3f,
                            "flat " + (flatInside ? "interior" : "exterior")
                                    + " pixel (" + x + "," + y + ") channel " + channel
                                    + " must match the full-resolution reference");
                }
                if (flatInside) inside++;
                else outside++;
            }
        }
        assertTrue(inside > 100, "the fixture must expose a large flat interior region");
        assertTrue(outside > 100, "the fixture must expose a large flat exterior region");
    }

    private static void assertRgbaClose(float[] expected, float[] actual, float tolerance,
                                        String message) {
        assertEquals(expected.length, actual.length, message + " length");
        for (int index = 0; index < expected.length; index++) {
            if (index % 4 == 3) continue;
            assertEquals(expected[index], actual[index], tolerance,
                    message + " at component " + index);
        }
    }

    private static float[] readFloat(Framebuffer framebuffer) {
        int width = framebuffer.width();
        int height = framebuffer.height();
        framebuffer.bind();
        glViewport(0, 0, width, height);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, pixels);
        float[] result = new float[width * height * 4];
        pixels.get(result);
        return result;
    }

    private static int currentReducedJitterPhase(RenderPipeline pipeline) {        PipelineGeneration generation = pipeline.activeGenerationForTest();
        return generation == null ? -1 : generation.postProcess().internalRasterJitterPhase();
    }

    /**
     * Reads one RGBA16F attachment through a temporary read framebuffer at the
     * given extent.  Row 0 is the OpenGL bottom row, matching the shader's
     * {@code ndcY = -1} convention used by the ray reference.
     */
    private static float[] readColorTexture(int texture, int width, int height) {
        int framebuffer = glGenFramebuffers();
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                    texture, 0);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE,
                    glCheckFramebufferStatus(GL_READ_FRAMEBUFFER),
                    "the reduced scene colour attachment must be read-complete");
            FloatBuffer pixels = BufferUtils.createFloatBuffer(width * height * 4);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, pixels);
            float[] result = new float[width * height * 4];
            pixels.get(result);
            return result;
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(framebuffer);
        }
    }

    private static float maxChannelDifference(float[] first, float[] second) {
        assertEquals(first.length, second.length, "image extents must agree");
        float maximum = 0.0f;
        for (int index = 0; index < first.length; index++) {
            if (index % 4 == 3) continue;
            maximum = Math.max(maximum, Math.abs(first[index] - second[index]));
        }
        return maximum;
    }

    private static void assertFinite(float[] pixels, String message) {
        for (float value : pixels) {
            assertTrue(Float.isFinite(value), message + " must stay finite");
        }
    }

    /** MAE of the pixels whose pattern distance lies in the 0.3 transition band. */
    private static double transitionBandMeanAbsoluteError(float[] actual, float[] reference,
                                                          int width, int height) {
        double sum = 0.0;
        int count = 0;
        for (int y = 0; y < height; y++) {
            float v = (y + 0.5f) / height;
            for (int x = 0; x < width; x++) {
                float u = (x + 0.5f) / width;
                float distance = Math.max(Math.abs(u - 0.5f), Math.abs(v - 0.5f));
                if (distance < 0.26f || distance > 0.34f) continue;
                int offset = (y * width + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    sum += Math.abs(actual[offset + channel] - reference[offset + channel]);
                }
                count += 3;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    /**
     * Double-precision world position at the {@code z = 0} plane for one
     * reduced-raster pixel centre under the documented jitter.
     *
     * <p>The fixture camera never rotates, so its view is a translation; the
     * projection is the output-aspect perspective the frozen frame camera
     * carries.  The stable NDC of the ray through the pixel is
     * {@code pixelNdc + delta} with {@code delta = 2 * jitterPixels / extent},
     * because the jittered projection shifts NDC by {@code -delta}.</p>
     */
    private static double[] worldAtPlane(Camera camera, int outputWidth, int outputHeight,
                                         int reducedWidth, int reducedHeight,
                                         double[] jitterPixels, int pixelX, int pixelY) {
        double f = 1.0 / Math.tan(Math.toRadians(camera.zoom()) * 0.5);
        double aspect = outputWidth / (double) outputHeight;
        double m00 = f / aspect;
        double m11 = f;
        Vector3f position = camera.position();
        double ndcX = (pixelX + 0.5) / reducedWidth * 2.0 - 1.0;
        double ndcY = (pixelY + 0.5) / reducedHeight * 2.0 - 1.0;
        double stableNdcX = ndcX + 2.0 * jitterPixels[0] / reducedWidth;
        double stableNdcY = ndcY + 2.0 * jitterPixels[1] / reducedHeight;
        // The plane sits at view depth = camera z (camera looks down -Z).
        double viewDepth = position.z;
        return new double[]{
                position.x + stableNdcX * viewDepth / m00,
                position.y + stableNdcY * viewDepth / m11
        };
    }

    private static boolean worldStepInside(double worldX, double worldY) {
        return cellFraction(worldX, WORLD_CELL_X) < 0.5
                && cellFraction(worldY, WORLD_CELL_Y) < 0.5;
    }

    private static double cellFraction(double value, double period) {
        double scaled = value / period;
        return scaled - Math.floor(scaled);
    }

    /** @return true when the world point is within double noise of a cell edge */
    private static boolean nearCellBoundary(double x, double y) {
        return nearCellEdge(cellFraction(x, WORLD_CELL_X), WORLD_CELL_X)
                || nearCellEdge(cellFraction(y, WORLD_CELL_Y), WORLD_CELL_Y);
    }

    private static boolean nearCellEdge(double fraction, double period) {
        double toHalf = Math.abs(fraction - 0.5) * period;
        double toZero = Math.min(fraction, 1.0 - fraction) * period;
        return Math.min(toHalf, toZero) < 1.0e-4;
    }

    private static void assertWorldStepReference(Camera camera, int outputWidth,
                                                 int outputHeight, int reducedWidth,
                                                 int reducedHeight, int phase, float[] raster) {
        double[] jitter = REDUCED_JITTER_PIXELS[phase];
        int compared = 0;
        for (int y = 0; y < reducedHeight; y++) {
            for (int x = 0; x < reducedWidth; x++) {
                double[] world = worldAtPlane(camera, outputWidth, outputHeight,
                        reducedWidth, reducedHeight, jitter, x, y);
                // A pixel whose ray lands within float noise of a cell edge could
                // classify either way; exclude only that degenerate sliver.
                if (nearCellBoundary(world[0], world[1])) {
                    continue;
                }
                float[] expected = worldStepInside(world[0], world[1])
                        ? WORLD_STEP_INSIDE : WORLD_STEP_OUTSIDE;
                int offset = (y * reducedWidth + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(expected[channel], raster[offset + channel], 1.0e-6f,
                            "phase " + phase + " reduced pixel (" + x + "," + y
                                    + ") channel " + channel);
                }
                compared++;
            }
        }
        assertTrue(compared > reducedWidth * reducedHeight / 2,
                "the independent ray reference must cover most reduced pixels");
    }

    /**
     * Stable (no jitter) classification of an output pixel by tracing its
     * output-ray to the plane; {@code null} for pixels outside the image.
     */
    private static Boolean stableWorldStepInside(Camera camera, int outputWidth,
                                                 int outputHeight, int x, int y) {
        if (x < 0 || y < 0 || x >= outputWidth || y >= outputHeight) return null;
        double[] world = stableWorldPixel(camera, outputWidth, outputHeight, x, y);
        return worldStepInside(world[0], world[1]);
    }

    private static double[] stableWorldPixel(Camera camera, int outputWidth, int outputHeight,
                                             int x, int y) {
        double f = 1.0 / Math.tan(Math.toRadians(camera.zoom()) * 0.5);
        double m00 = f / (outputWidth / (double) outputHeight);
        double m11 = f;
        Vector3f position = camera.position();
        double ndcX = (x + 0.5) / outputWidth * 2.0 - 1.0;
        double ndcY = (y + 0.5) / outputHeight * 2.0 - 1.0;
        return new double[]{
                position.x + ndcX * position.z / m00,
                position.y + ndcY * position.z / m11
        };
    }

    /**
     * Counts output pixels that keep the wrong side of the world step while
     * staying at least {@code marginPixels} away from the geometric boundary in
     * every direction (including the reduced-texel bilinear footprint).
     */
    private static int misclassifiedPixels(Camera camera, int outputWidth, int outputHeight,
                                           float[] output, int marginPixels) {
        int misclassified = 0;
        for (int y = 0; y < outputHeight; y++) {
            for (int x = 0; x < outputWidth; x++) {
                Boolean inside = stableWorldStepInside(camera, outputWidth, outputHeight, x, y);
                if (inside == null) continue;
                boolean confident = true;
                for (int dx = -marginPixels; dx <= marginPixels && confident; dx++) {
                    for (int dy = -marginPixels; dy <= marginPixels && confident; dy++) {
                        Boolean neighbour = stableWorldStepInside(camera, outputWidth,
                                outputHeight, x + dx, y + dy);
                        if (neighbour == null || !neighbour.equals(inside)) confident = false;
                    }
                }
                if (!confident) continue;
                float[] expected = inside ? WORLD_STEP_INSIDE : WORLD_STEP_OUTSIDE;
                int offset = (y * outputWidth + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    // The reconstructed value is RGBA16F history blended in
                    // float, so a flat cell may differ by one half-float ULP
                    // (~4.9e-4).  A retained ghost is ~0.69, far above this.
                    if (Math.abs(output[offset + channel] - expected[channel]) > 1.5e-3f) {
                        if (misclassified < 5) {
                            double[] ray = stableWorldPixel(camera, outputWidth, outputHeight,
                                    x, y);
                            System.out.println("[jitter-debug] misclassified (" + x + "," + y
                                    + ") expectedInside=" + inside + " expected="
                                    + expected[0] + "," + expected[1] + "," + expected[2]
                                    + " actual=" + output[offset] + "," + output[offset + 1]
                                    + "," + output[offset + 2] + " world=" + ray[0] + ","
                                    + ray[1]);
                        }
                        misclassified++;
                        break;
                    }
                }
            }
        }
        return misclassified;
    }

    private static boolean temporalHistoryValid(RenderPipeline pipeline) {
        PipelineGeneration generation = pipeline.activeGenerationForTest();
        return generation != null
                && generation.postProcess().temporalUpscalingHistoryValid();
    }

    private static RenderPipeline buildPipeline(GlfwWindow window, Scene scene, float scale,
                                                PostProcessSettings effects) {
        return buildPipeline(window, scene, settings(scale, AntiAliasingMode.NONE), effects);
    }

    private static RenderPipeline buildPipeline(GlfwWindow window, Scene scene,
                                                RenderSettings render,
                                                PostProcessSettings effects) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, render)
                .postProcessSettings(effects);
        pipeline.build();
        return pipeline;
    }

    private static RenderSettings settings(float scale, AntiAliasingMode mode) {
        return hdrBuilder(scale).antiAliasingMode(mode).build();
    }

    private static RenderSettings.Builder hdrBuilder(float scale) {
        return RenderSettings.builder()
                .vsync(false)
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .renderScale(scale);
    }

    private static RenderGraph.PassDescription pass(RenderGraph.Description description,
                                                    String name) {
        return description.passes().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst().orElse(null);
    }

    private static int scaledExtent(int extent, float scale) {
        return Math.max(1, Math.round(extent * scale));
    }

    private static GlfwWindow window(String title) {
        GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(REQUESTED_WIDTH, REQUESTED_HEIGHT).title(title).visible(false).build();
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        window.setVsync(false);
        return window;
    }

    /** Fullscreen raster fixture with a flat interior and a flat exterior. */
    private static final class Fixture implements AutoCloseable {
        private final Mesh mesh;
        private final ShaderProgram shader;
        private final Material material;
        private final Scene scene;

        private Fixture() {
            this(FULLSCREEN_VERTEX_SOURCE, FLAT_INTERIOR_FRAGMENT_SOURCE);
        }

        private Fixture(String vertexSource, String fragmentSource) {
            VertexAttribute position = VertexAttribute.builder()
                    .index(0).size(3).offsetBytes(0L)
                    .semantic(VertexSemantic.POSITION).build();
            mesh = Mesh.builder()
                    .vertices(new float[]{
                            -20.0f, -20.0f, 0.0f,
                            20.0f, -20.0f, 0.0f,
                            -20.0f, 20.0f, 0.0f,
                            20.0f, -20.0f, 0.0f,
                            20.0f, 20.0f, 0.0f,
                            -20.0f, 20.0f, 0.0f
                    }, 3 * Float.BYTES, position)
                    .build();
            shader = ShaderProgram.fromSources(vertexSource, fragmentSource);
            material = Material.builder(shader).build();
            scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 5.0f)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
        }

        private static Fixture worldAnchored() {
            return new Fixture(WORLD_ANCHORED_VERTEX_SOURCE, WORLD_STEP_FRAGMENT_SOURCE);
        }

        private Scene scene() {
            return scene;
        }

        @Override
        public void close() {
            List<AutoCloseable> owners = List.of(material, shader, mesh);
            RuntimeException failure = null;
            for (AutoCloseable owner : owners) {
                try {
                    owner.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                } catch (Exception closeFailure) {
                    IllegalStateException wrapped = new IllegalStateException(
                            "fixture close failed", closeFailure);
                    if (failure == null) failure = wrapped;
                    else failure.addSuppressed(wrapped);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
