package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeature;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureActivity;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureReason;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecal;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecalPass;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecalSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

/**
 * Real-GL production contract for the projected-decal pipeline stage.
 *
 * <p>Proves that an enabled batch is recorded once through the production
 * {@link RenderPipeline} after the opaque geometry pass and before the
 * transparent consumers: the decal lands on the floor at the independently
 * projected centre pixel, its texture orientation follows the authored
 * {@code up}/{@code normal} basis, a particle-free disabled request is
 * byte-identical to the no-decal baseline, MSAA resolves the geometry depth
 * into a single-sample copy, a foreground caster never receives the decal, and
 * the water consumer composites over the decal instead of replacing it. The
 * empty case asserts no draw and an unchanged frame, and a requested batch
 * without a registered renderer/source creates no pass and reports
 * UNAVAILABLE.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ProjectedDecalPipelineGlTest {
    private static final int REQUESTED_SIZE = 64;
    /**
     * Test-only 8x8 texture: constant sRGB RGB (240,160,64) with per-column
     * alpha {@code [0, 0, 64, 128, 192, 255, 255, 255]}. The alpha columns make
     * the authored decal-basis orientation observable without a second asset.
     */
    private static final AssetRef DECAL_TEXTURE =
            AssetRef.of("/vfx/test/decal-alpha-columns-8x8.png");
    private static final Vector4f DECAL_COLOR = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final Vector3f DECAL_NORMAL = new Vector3f(0.0f, 1.0f, 0.0f);
    /** Local up points at +Z, so the authored right vector is world -X. */
    private static final Vector3f DECAL_UP = new Vector3f(0.0f, 0.0f, 1.0f);
    private static final float[] FLOOR_COLOR = {0.2f, 0.4f, 0.6f};

    @Test
    void enabledBatchLandsOnTheFloorAtTheExpectedCentreAndOrientation() {
        try (Fixture fixture = new Fixture(AntiAliasingMode.NONE, 1,
                WaterSurfaceSettings.disabled(), solidSettings(), ProjectedDecalPipelineGlTest::oneDecal,
                false, true)) {
            int width = fixture.width;
            int height = fixture.height;
            int[] centre = fixture.project(new Vector3f());
            int[] opaqueX = fixture.project(new Vector3f(-0.8f, 0.0f, 0.0f));
            int[] maskedX = fixture.project(new Vector3f(0.8f, 0.0f, 0.0f));
            int[] outside = fixture.project(new Vector3f(0.0f, 0.0f, 1.6f));

            assertTrue(fixture.pipeline.graph().hasPass(ProjectedDecalPass.PASS_NAME));
            assertTrue(fixture.pipeline.graph().hasPass(ProjectedDecalPass.DEPTH_RESOLVE_PASS));
            var decalPass = pass(fixture.pipeline, ProjectedDecalPass.PASS_NAME);
            assertNull(decalPass.depthAttachment(),
                    "the decal draw must not attach or write the shared scene depth");
            var resolvePass = pass(fixture.pipeline, ProjectedDecalPass.DEPTH_RESOLVE_PASS);
            assertNotNull(resolvePass.depthAttachment(),
                    "the stage must own the single-sample depth texture it samples");
            assertEquals(1, resolvePass.samples());

            fixture.frame();
            float[] enabled = fixture.capture();
            assertEquals(1, fixture.pipeline.projectedDecalStatistics().drawn());
            assertEquals(1, fixture.pipeline.projectedDecalStatistics().drawCalls());
            assertEquals(GL_NO_ERROR, glGetError());

            fixture.pipeline.applyProjectedDecals(ProjectedDecalSettings.disabled());
            assertFalse(fixture.pipeline.graph().hasPass(ProjectedDecalPass.PASS_NAME),
                    "a disabled batch must remove the stage from the graph");
            fixture.frame();
            float[] baseline = fixture.capture();
            assertEquals(0, fixture.pipeline.projectedDecalStatistics().drawCalls(),
                    "a disabled stage must report no draw");
            assertEquals(GL_NO_ERROR, glGetError());

            assertTrue(delta(enabled, baseline, centre, width) > 0.2f,
                    "the decal must cover the projected centre pixel");
            // Independent oracle: coverage is 1 (alphaMask=false), tint is
            // white and emissive is 1, so the pixel is the texture's linear RGB.
            float[] expected = linearTexel(240, 160, 64);
            for (int channel = 0; channel < 3; channel++) {
                assertEquals(expected[channel],
                        enabled[(centre[1] * width + centre[0]) * 4 + channel], 0.02f,
                        "the centre pixel must be the sampled decal colour (channel "
                                + channel + ")");
                assertEquals(FLOOR_COLOR[channel],
                        baseline[(centre[1] * width + centre[0]) * 4 + channel], 0.02f,
                        "the baseline centre pixel must be the floor (channel " + channel + ")");
            }
            assertEquals(0.0f, delta(enabled, baseline, outside, width), 0.0f,
                    "the decal must not leak outside its receiver rectangle");

            // Orientation: with up=+Z the authored right axis is -X, so the
            // fully opaque last alpha column sits at -X and the zero-alpha
            // first column at +X.
            fixture.pipeline.applyProjectedDecals(maskedSettings());
            assertTrue(fixture.pipeline.graph().hasPass(ProjectedDecalPass.PASS_NAME));
            fixture.frame();
            float[] oriented = fixture.capture();
            assertTrue(delta(oriented, baseline, opaqueX, width) > 0.2f,
                    "the fully opaque texture column must follow the authored -X axis");
            assertEquals(0.0f, delta(oriented, baseline, maskedX, width), 0.0f,
                    "the zero-alpha texture column must follow the authored +X axis");
            assertEquals(1, fixture.pipeline.projectedDecalStatistics().drawn());
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void disabledSettingsRestoreTheByteIdenticalNoDecalFrame() {
        try (Fixture fixture = new Fixture(AntiAliasingMode.NONE, 1,
                WaterSurfaceSettings.disabled(), ProjectedDecalSettings.disabled(),
                ProjectedDecalPipelineGlTest::oneDecal, false, true)) {
            assertFalse(fixture.pipeline.graph().hasPass(ProjectedDecalPass.PASS_NAME),
                    "disabled settings must not create the decal stage even with a source");
            fixture.frame();
            float[] disabledBaseline = fixture.capture();

            fixture.pipeline.applyProjectedDecals(solidSettings());
            assertTrue(fixture.pipeline.graph().hasPass(ProjectedDecalPass.PASS_NAME));
            fixture.frame();
            float[] enabled = fixture.capture();
            assertFalse(java.util.Arrays.equals(disabledBaseline, enabled),
                    "the enabled batch must change the frame");

            fixture.pipeline.applyProjectedDecals(ProjectedDecalSettings.disabled());
            fixture.frame();
            float[] restored = fixture.capture();
            org.junit.jupiter.api.Assertions.assertArrayEquals(disabledBaseline, restored,
                    "disabling the batch must restore the byte-identical no-decal frame");
            assertEquals(0, fixture.pipeline.projectedDecalStatistics().drawCalls());
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void msaaResolvesGeometryDepthIntoTheSingleSampleDecalCopy() {
        try (Fixture fixture = new Fixture(AntiAliasingMode.MSAA, 4,
                WaterSurfaceSettings.disabled(), solidSettings(),
                ProjectedDecalPipelineGlTest::oneDecal, false, true)) {
            int width = fixture.width;
            int height = fixture.height;
            int[] centre = fixture.project(new Vector3f());
            int[] outside = fixture.project(new Vector3f(0.0f, 0.0f, 1.6f));
            assertEquals(4, fixture.pipeline.graph().description().passes().stream()
                    .filter(candidate -> candidate.name().equals(ProjectedDecalPass.PASS_NAME))
                    .findFirst().orElseThrow().samples(),
                    "the decal colour target must keep the topology sample count");
            assertEquals(1, pass(fixture.pipeline, ProjectedDecalPass.DEPTH_RESOLVE_PASS).samples(),
                    "the sampled depth copy must stay single-sample");
            assertNull(pass(fixture.pipeline, ProjectedDecalPass.PASS_NAME).depthAttachment(),
                    "an MSAA decal pass must not attach a multisampled depth texture");
            assertTrue(pass(fixture.pipeline, ProjectedDecalPass.PASS_NAME)
                            .directDependencies().contains(ProjectedDecalPass.DEPTH_RESOLVE_PASS),
                    "the decal pass must consume the resolved depth copy");
            var resolve = fixture.pipeline.graph().description().passes().stream()
                    .filter(candidate -> candidate.name().equals(
                            com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets
                                    .HDR_RESOLVE_PASS))
                    .findFirst().orElseThrow();
            assertTrue(resolve.directDependencies().contains(
                            com.kaleblangley.haikalat.subsystems.render3d.pipeline
                                    .ForwardPassBuilder.TRANSPARENT_PASS),
                    "the MSAA HDR resolve must consume the transparent forward half, deps="
                            + resolve.directDependencies());
            assertTrue(pass(fixture.pipeline,
                            com.kaleblangley.haikalat.subsystems.render3d.pipeline
                                    .ForwardPassBuilder.TRANSPARENT_PASS)
                            .directDependencies().contains(ProjectedDecalPass.PASS_NAME),
                    "the transparent forward half must composite over the decal batch");

            fixture.frame();
            float[] enabled = fixture.capture();
            assertEquals(1, fixture.pipeline.projectedDecalStatistics().drawn());
            assertEquals(GL_NO_ERROR, glGetError());

            fixture.pipeline.applyProjectedDecals(ProjectedDecalSettings.disabled());
            fixture.frame();
            float[] baseline = fixture.capture();
            for (int channel = 0; channel < 3; channel++) {
                assertEquals(FLOOR_COLOR[channel],
                        baseline[(centre[1] * width + centre[0]) * 4 + channel], 0.02f,
                        "the MSAA baseline must show the floor receiver (channel "
                                + channel + ")");
            }
            assertTrue(delta(enabled, baseline, centre, width) > 0.2f,
                    "the MSAA decal must land on the expected centre pixel, enabled="
                            + enabled[(centre[1] * width + centre[0]) * 4]
                            + " baseline=" + baseline[(centre[1] * width + centre[0]) * 4]);
            assertEquals(0.0f, delta(enabled, baseline, outside, width), 0.0f,
                    "the MSAA decal must not leak outside its rectangle");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void opaqueCasterOccludesTheDecalAndWaterCompositesOverIt() {
        WaterSurfaceSettings water = WaterSurfaceSettings.builder()
                .enabled(true).level(0.25f).bounds(-6.0f, -6.0f, 6.0f, 6.0f)
                .waveWavelength(8.0f).waveSpeed(0.6f).waveAmplitude(0.0f)
                .waveDirectionRadians(0.0f)
                .waterColor(0.05f, 0.35f, 0.55f)
                .absorptionDensity(0.05f).shoreFadeDistance(0.0f)
                .reflectionFallback(0.35f, 0.5f, 0.65f)
                .build();
        try (Fixture fixture = new Fixture(AntiAliasingMode.NONE, 1,
                WaterSurfaceSettings.disabled(), ProjectedDecalSettings.disabled(),
                ProjectedDecalPipelineGlTest::oneDecal, true, true)) {
            int width = fixture.width;
            int[] centre = fixture.project(new Vector3f());
            int[] occluded = fixture.project(new Vector3f(0.7f, 0.5f, 0.0f));

            fixture.frame();
            float[] baseline = fixture.capture();
            assertEquals(0.30f,
                    baseline[(occluded[1] * width + occluded[0]) * 4], 0.02f,
                    "the foreground caster must cover its probe pixel");

            fixture.pipeline.applyProjectedDecals(solidSettings());
            fixture.frame();
            float[] decalOnly = fixture.capture();
            assertEquals(1, fixture.pipeline.projectedDecalStatistics().drawn());
            assertTrue(delta(decalOnly, baseline, centre, width) > 0.2f,
                    "the decal must be visible before the transparent consumers");
            assertEquals(0.0f, delta(decalOnly, baseline, occluded, width), 0.0f,
                    "an opaque caster in front of the receiver volume must not receive the decal");
            assertEquals(GL_NO_ERROR, glGetError());

            fixture.pipeline.applyWaterSurface(water);
            fixture.frame();
            float[] decalThenWater = fixture.capture();
            assertEquals(1, fixture.pipeline.projectedDecalStatistics().drawn());
            fixture.pipeline.applyProjectedDecals(ProjectedDecalSettings.disabled());
            fixture.frame();
            float[] waterOnly = fixture.capture();
            assertEquals(0, fixture.pipeline.projectedDecalStatistics().drawCalls());

            assertTrue(delta(decalThenWater, decalOnly, centre, width) > 1.0e-3f,
                    "the water consumer must be recorded after the decal and composite over it");
            assertTrue(delta(decalThenWater, waterOnly, centre, width) > 1.0e-3f,
                    "the decal must remain part of the scene colour the water sees through");
            assertEquals(0.0f, delta(decalThenWater, waterOnly, occluded, width), 0.0f,
                    "the water consumer must stay behind the foreground caster");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void emptySourceRecordsNoDrawAndUnregisteredSourceCreatesNoPass() {
        try (Fixture fixture = new Fixture(AntiAliasingMode.NONE, 1,
                WaterSurfaceSettings.disabled(), solidSettings(), List::of, false, true)) {
            assertTrue(fixture.pipeline.graph().hasPass(ProjectedDecalPass.PASS_NAME),
                    "the stage is a topology component of the registered batch");
            fixture.frame();
            float[] emptyFrame = fixture.capture();
            assertEquals(0, fixture.pipeline.projectedDecalStatistics().submitted());
            assertEquals(0, fixture.pipeline.projectedDecalStatistics().drawn());
            assertEquals(0, fixture.pipeline.projectedDecalStatistics().drawCalls());
            assertEquals(GL_NO_ERROR, glGetError());

            fixture.pipeline.applyProjectedDecals(ProjectedDecalSettings.disabled());
            fixture.frame();
            org.junit.jupiter.api.Assertions.assertArrayEquals(emptyFrame, fixture.capture(),
                    "an empty batch must record no draw and leave the frame unchanged");
            assertEquals(GL_NO_ERROR, glGetError());
        }

        // A requested batch without a registered renderer/source fails closed:
        // the policy reports UNAVAILABLE and the graph declares no decal pass.
        try (Fixture fixture = new Fixture(AntiAliasingMode.NONE, 1,
                WaterSurfaceSettings.disabled(), solidSettings(), null, false, false)) {
            assertFalse(fixture.pipeline.hasProjectedDecalRecorder());
            assertFalse(fixture.pipeline.graph().hasPass(ProjectedDecalPass.PASS_NAME),
                    "an unregistered source must not create the decal stage");
            RenderFeatureActivity activity = fixture.pipeline.features().activities().stream()
                    .filter(candidate -> candidate.feature() == RenderFeature.PROJECTED_DECALS)
                    .findFirst().orElseThrow();
            assertFalse(activity.active());
            assertEquals(RenderFeatureReason.UNAVAILABLE, activity.reason());
            assertTrue(activity.detail().contains("registered projected-decal renderer/source"),
                    activity.detail());
            fixture.frame();
            assertEquals(0, fixture.pipeline.projectedDecalStatistics().drawCalls());
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    /**
     * The batch uses a deliberately roomy front tolerance. An MSAA resolve
     * picks one geometry sample per pixel while the decal shader reconstructs
     * along the pixel-centre ray, so the reconstructed receiver can sit a few
     * centimetres in front of the authored plane on a floor viewed at an
     * angle. {@code surfaceOffset} is the documented world-unit knob for that
     * bounded reconstruction error.
     */
    /**
     * The sorted transparent forward half must composite over the decal instead
     * of being overwritten by it: an opaque-alpha overlay in front of the
     * receiver hides the decal exactly, while the same fixture without the
     * overlay shows the decal delta.
     */
    @Test
    void transparentOverlayCompositesOverTheDecalInsteadOfBeingOverwritten() {
        try (Fixture fixture = new Fixture(AntiAliasingMode.NONE, 1,
                WaterSurfaceSettings.disabled(), solidSettings(),
                ProjectedDecalPipelineGlTest::oneDecal, false, true, true)) {
            int[] centre = fixture.project(new Vector3f(0.0f, 0.0f, 0.0f));
            var transparent = pass(fixture.pipeline,
                    com.kaleblangley.haikalat.subsystems.render3d.pipeline
                            .ForwardPassBuilder.TRANSPARENT_PASS);
            assertTrue(transparent.directDependencies().contains(ProjectedDecalPass.PASS_NAME),
                    "the transparent forward half must composite over the decal batch, deps="
                            + transparent.directDependencies());
            fixture.frame();
            float[] enabled = fixture.capture();
            assertEquals(1, fixture.pipeline.projectedDecalStatistics().drawn());
            int index = (centre[1] * fixture.width + centre[0]) * 4;
            assertEquals(0.05f, enabled[index], 0.02f, "the overlay red hides the decal");
            assertEquals(0.90f, enabled[index + 1], 0.02f, "the overlay green hides the decal");
            assertEquals(0.15f, enabled[index + 2], 0.02f, "the overlay blue hides the decal");
            fixture.pipeline.applyProjectedDecals(ProjectedDecalSettings.disabled());
            fixture.frame();
            float[] baseline = fixture.capture();
            assertEquals(0.0f, delta(enabled, baseline, centre, fixture.width), 0.0f,
                    "disabling the decal must not change an overlay-covered pixel");
            assertEquals(GL_NO_ERROR, glGetError());
        }
        try (Fixture fixture = new Fixture(AntiAliasingMode.NONE, 1,
                WaterSurfaceSettings.disabled(), solidSettings(),
                ProjectedDecalPipelineGlTest::oneDecal, false, true)) {
            int[] centre = fixture.project(new Vector3f(0.0f, 0.0f, 0.0f));
            fixture.frame();
            float[] enabled = fixture.capture();
            fixture.pipeline.applyProjectedDecals(ProjectedDecalSettings.disabled());
            fixture.frame();
            float[] baseline = fixture.capture();
            assertTrue(delta(enabled, baseline, centre, fixture.width) > 0.2f,
                    "the control decal must change the uncovered floor pixel");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static ProjectedDecalSettings solidSettings() {
        return new ProjectedDecalSettings(true, 0.6f, 0.12f, 0.5f, false, false, 8);
    }

    private static ProjectedDecalSettings maskedSettings() {
        return new ProjectedDecalSettings(true, 0.6f, 0.12f, 0.5f, false, true, 8);
    }

    private static List<ProjectedDecal> oneDecal() {
        return List.of(new ProjectedDecal(1L, DECAL_TEXTURE, new Vector3f(),
                DECAL_NORMAL, DECAL_UP, new Vector2f(2.0f, 2.0f), DECAL_COLOR, 1.0f));
    }

    private static float[] linearTexel(int red, int green, int blue) {
        return new float[]{(float) srgbToLinear(red / 255.0),
                (float) srgbToLinear(green / 255.0), (float) srgbToLinear(blue / 255.0)};
    }

    private static double srgbToLinear(double encoded) {
        return encoded <= 0.04045
                ? encoded / 12.92
                : Math.pow((encoded + 0.055) / 1.055, 2.4);
    }

    private static float delta(float[] left, float[] right, int[] pixel, int width) {
        int index = (pixel[1] * width + pixel[0]) * 4;
        float sum = 0.0f;
        for (int channel = 0; channel < 3; channel++) {
            sum += Math.abs(left[index + channel] - right[index + channel]);
        }
        return sum;
    }

    private static com.kaleblangley.haikalat.core.graph.RenderGraph.PassDescription pass(
            RenderPipeline pipeline, String name) {
        return pipeline.graph().description().passes().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static final class Fixture implements AutoCloseable {
        private static final String GROUND_VERTEX = """
                #version 460 core
                layout(location = 0) in vec3 aPosition;
                layout(std140) uniform CameraBlock {
                    mat4 uProjection;
                    mat4 uView;
                };
                uniform mat4 uModel;
                void main() {
                    gl_Position = uProjection * uView * uModel * vec4(aPosition, 1.0);
                }
                """;
        private static final String GROUND_FRAGMENT = """
                #version 460 core
                layout(location = 0) out vec4 fragColor;
                uniform vec3 uGroundColor;
                void main() {
                    fragColor = vec4(uGroundColor, 1.0);
                }
                """;
        /** Opaque-alpha overlay drawn in the sorted transparent stream. */
        private static final String OVERLAY_FRAGMENT = """
                #version 460 core
                layout(location = 0) out vec4 fragColor;
                uniform vec4 uOverlayColor;
                void main() {
                    fragColor = uOverlayColor;
                }
                """;

        final GlfwWindow window;
        final int width;
        final int height;
        final GlRenderDevice device = new GlRenderDevice();
        final ExternalCamera camera;
        final ShaderProgram groundShader;
        final Mesh quad;
        final Material floorMaterial;
        final Material casterMaterial;
        final Mesh casterMesh;
        final ShaderProgram overlayShader;
        final Material overlayMaterial;
        final Mesh overlayMesh;
        final VfxRenderer renderer;
        final RenderPipeline pipeline;

        Fixture(AntiAliasingMode antiAliasing, int samples, WaterSurfaceSettings water,
                ProjectedDecalSettings decals, Supplier<List<ProjectedDecal>> source,
                boolean occluder, boolean registerSource) {
            this(antiAliasing, samples, water, decals, source, occluder, registerSource, false);
        }

        Fixture(AntiAliasingMode antiAliasing, int samples, WaterSurfaceSettings water,
                ProjectedDecalSettings decals, Supplier<List<ProjectedDecal>> source,
                boolean occluder, boolean registerSource, boolean transparentOverlay) {
            window = new GlfwWindow.Builder().dimensions(REQUESTED_SIZE, REQUESTED_SIZE)
                    .title("Projected decal pipeline test").visible(false).build();
            width = window.width();
            height = window.height();
            window.bindContext();
            org.lwjgl.opengl.GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(false);
            Matrix4f view = new Matrix4f().lookAt(new Vector3f(0.0f, 3.2f, 4.6f),
                    new Vector3f(), new Vector3f(0.0f, 1.0f, 0.0f));
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(55.0),
                    width / (float) height, 0.1f, 50.0f);
            camera = ExternalCamera.of(view, projection, new Vector3f(0.0f, 3.2f, 4.6f), 0.0f);
            groundShader = ShaderProgram.fromSources(GROUND_VERTEX, GROUND_FRAGMENT);
            VertexAttribute position = VertexAttribute.builder()
                    .index(0).size(3).offsetBytes(0L)
                    .semantic(VertexSemantic.POSITION).build();
            float half = 20.0f;
            float[] vertices = {
                    -half, 0.0f, -half,
                    -half, 0.0f, half,
                    half, 0.0f, half,
                    half, 0.0f, -half
            };
            quad = Mesh.builder()
                    .vertices(vertices, 3 * Float.BYTES, position)
                    .indices(new int[]{0, 1, 2, 0, 2, 3})
                    .build();
            floorMaterial = Material.builder(groundShader)
                    .setVec3("uGroundColor", new Vector3f(FLOOR_COLOR))
                    .build();
            Scene scene = new Scene(camera);
            scene.add(SceneObject.fixed(quad, floorMaterial, new Matrix4f()));
            if (occluder) {
                casterMaterial = Material.builder(groundShader)
                        .setVec3("uGroundColor", new Vector3f(0.30f, 0.35f, 0.28f))
                        .build();
                casterMesh = Mesh.builder()
                        .vertices(new float[]{
                                -0.5f, 0.0f, -0.5f,
                                -0.5f, 0.0f, 0.5f,
                                0.5f, 0.0f, 0.5f,
                                0.5f, 0.0f, -0.5f
                        }, 3 * Float.BYTES, position)
                        .indices(new int[]{0, 1, 2, 0, 2, 3})
                        .build();
                scene.add(SceneObject.fixed(casterMesh, casterMaterial,
                        new Matrix4f().translate(0.7f, 0.5f, 0.0f)));
            } else {
                casterMaterial = null;
                casterMesh = null;
            }
            if (transparentOverlay) {
                overlayShader = ShaderProgram.fromSources(GROUND_VERTEX, OVERLAY_FRAGMENT);
                overlayMesh = Mesh.builder()
                        .vertices(new float[]{
                                -1.5f, 0.0f, -1.5f,
                                -1.5f, 0.0f, 1.5f,
                                1.5f, 0.0f, 1.5f,
                                1.5f, 0.0f, -1.5f
                        }, 3 * Float.BYTES, position)
                        .indices(new int[]{0, 1, 2, 0, 2, 3})
                        .build();
                overlayMaterial = Material.builder(overlayShader)
                        .blendMode(com.kaleblangley.haikalat.core.BlendMode.ALPHA)
                        .setVec4("uOverlayColor", new Vector4f(0.05f, 0.9f, 0.15f, 1.0f))
                        .build();
                // Sorted-transparent overlay between the camera and the decal
                // receiver: a decal recorded after the transparent stream would
                // replace the overlay pixel.
                scene.add(SceneObject.fixed(overlayMesh, overlayMaterial,
                        new Matrix4f().translate(0.0f, 0.5f, 0.0f)));
            } else {
                overlayShader = null;
                overlayMesh = null;
                overlayMaterial = null;
            }
            renderer = new VfxRenderer();
            RenderSettings.Builder settings = RenderSettings.builder()
                    .vsync(false)
                    .antiAliasingMode(antiAliasing)
                    .msaaSamples(samples)
                    .toneMappingMode(ToneMappingMode.ACES);
            pipeline = new RenderPipeline(window, scene, null, settings.build());
            if (water.enabled()) pipeline.waterSurface(water);
            if (registerSource) {
                pipeline.decals(renderer, source);
            }
            pipeline.applyProjectedDecals(decals);
            pipeline.build();
        }

        void frame() {
            pipeline.execute(device, 1.0f / 60.0f);
        }

        float[] capture() {
            return pipeline.captureLinearHdrRgbaFloat();
        }

        int[] project(Vector3f world) {
            Vector4f clip = new Vector4f(world, 1.0f);
            new Matrix4f(camera.projection()).mul(camera.view()).transform(clip);
            float inverseW = 1.0f / clip.w;
            int x = Math.round((clip.x * inverseW * 0.5f + 0.5f) * width);
            int y = Math.round((clip.y * inverseW * 0.5f + 0.5f) * height);
            return new int[]{Math.max(0, Math.min(width - 1, x)),
                    Math.max(0, Math.min(height - 1, y))};
        }

        @Override
        public void close() {
            try {
                pipeline.close();
            } finally {
                renderer.close();
                if (casterMaterial != null) casterMaterial.close();
                if (casterMesh != null) casterMesh.close();
                if (overlayMaterial != null) overlayMaterial.close();
                if (overlayMesh != null) overlayMesh.close();
                if (overlayShader != null) overlayShader.close();
                floorMaterial.close();
                quad.close();
                groundShader.close();
                window.close();
            }
        }
    }
}
