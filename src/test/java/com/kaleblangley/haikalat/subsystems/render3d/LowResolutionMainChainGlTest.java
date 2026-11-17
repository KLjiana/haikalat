package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
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
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
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
import org.lwjgl.opengl.GL;

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
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

/**
 * Real-GL acceptance for the reduced internal scene resolution skeleton.
 *
 * <p>The scene surface/geometry passes render at the rounded scene extent while
 * the post-process chain, the spatial scene→chain magnifier and present stay at
 * the target extent.  Scale 1.0 must remain a byte-identical no-op and every
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
                                    PostProcessTargets.SCENE_UPSCALE_PASS),
                            "renderScale 1.0 must not insert the spatial magnifier");
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
                                    PostProcessTargets.SCENE_UPSCALE_PASS),
                            "the rebuilt full-resolution generation must not add the magnifier");
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
    void halfScaleRendersTheSceneAtRAndMagnifiesFlatInteriorsToTheTarget() {
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
                    RenderGraph.Description description = reducedPipeline.graph().description();
                    RenderGraph.PassDescription upscale = pass(description,
                            PostProcessTargets.SCENE_UPSCALE_PASS);
                    assertNotNull(upscale, "renderScale 0.5 must insert one spatial magnifier");
                    assertEquals(targetWidth, upscale.width());
                    assertEquals(targetHeight, upscale.height());
                    RenderGraph.PassDescription geometry = pass(description,
                            PostProcessTargets.GEOMETRY_PASS);
                    assertNotNull(geometry);
                    assertEquals(sceneWidth, geometry.width(),
                            "the geometry pass must rasterise at the scene extent");
                    assertEquals(sceneHeight, geometry.height());
                    float[] reduced = reducedPipeline.captureLinearHdrRgbaFloat();
                    assertEquals(targetWidth * targetHeight * 4, reduced.length,
                            "the magnified output must have the target extent");
                    for (float value : reduced) {
                        assertTrue(Float.isFinite(value),
                                "the magnified output must stay finite");
                    }
                    assertFlatInteriorsMatch(window, fixture, device, reduced);
                } finally {
                    reducedPipeline.close();
                }
            }
            GlDebug.assertNoError("low-resolution half-scale frame");
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the half-scale case must release every tracked GL resource");
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
                        RenderGraph.PassDescription upscale = pass(description,
                                PostProcessTargets.SCENE_UPSCALE_PASS);
                        assertNotNull(upscale);
                        assertEquals(resizedWidth, upscale.width());
                        assertEquals(resizedHeight, upscale.height());
                        RenderGraph.PassDescription geometry = pass(description,
                                PostProcessTargets.GEOMETRY_PASS);
                        assertNotNull(geometry);
                        assertEquals(sceneWidth, geometry.width(),
                                "the replacement generation must move the scene extent with the target");
                        assertEquals(sceneHeight, geometry.height());
                        assertEquals(resizedWidth * resizedHeight * 4,
                                pipeline.captureLinearHdrRgbaFloat().length);
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

    private static void assertFlatInteriorsMatch(GlfwWindow window, Fixture fixture,
                                                 GlRenderDevice device, float[] reduced) {
        int targetWidth = window.width();
        int targetHeight = window.height();
        float[] full;
        RenderPipeline reference = buildPipeline(window, fixture.scene(), 1.0f,
                PostProcessSettings.defaults());
        try {
            reference.execute(device, 1.0f / 60.0f);
            full = reference.captureLinearHdrRgbaFloat();
        } finally {
            reference.close();
        }
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
            shader = ShaderProgram.fromSources(FULLSCREEN_VERTEX_SOURCE,
                    FLAT_INTERIOR_FRAGMENT_SOURCE);
            material = Material.builder(shader).build();
            scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 5.0f)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
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
