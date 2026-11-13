package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiProbeVolume;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

/**
 * Real-GL regression for the DDGI failed-submission recovery fix.
 *
 * <p>{@code DdgiProbeVolume.recordUpdate} publishes the tentative
 * {@code initialized}/schedule state while recording. When the graph's device
 * submission fails, {@code RenderPipeline} now calls
 * {@link DdgiProbeVolume#invalidatePendingUpdate()}; the recovery frame must
 * therefore replay the first-use depth-atlas clear. This case makes that
 * difference observable: the atlas is pre-filled with a sentinel, probe 0 is
 * scheduled on the failed frame and probe 1 on the recovery frame, so the
 * unscheduled probe tile is only 1.0 (the documented "no surface" depth) when
 * the recovery frame really cleared the whole atlas.</p>
 *
 * <p>The one-shot fault is the test-only {@code haikalat.test.failSubmissionAfterPass}
 * property handled by {@code RenderGraph}: it throws after all pass callbacks
 * returned and before {@code device.execute}, so no recorded command reaches
 * the GPU. Production never sets it.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class DdgiRecoveryGlTest {
    private static final int SIZE = 32;
    /** Distinct from both the cleared depth 1.0 and any rasterized surface depth. */
    private static final float SENTINEL_DEPTH = 0.25f;
    private static final String SUBMISSION_FAULT =
            "haikalat.test.failSubmissionAfterPass";

    @Test
    void failedFirstSubmissionReinitializesTheDepthAtlasOnTheRecoveryFrame() {
        try (GlfwWindow window = window()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (Fixture fixture = fixture(window)) {
                    RenderPipeline pipeline = fixture.pipeline();
                    pipeline.build();
                    DdgiProbeVolume volume =
                            pipeline.activeGenerationForTest().ddgiVolume();
                    assertNotNull(volume, "the active generation must own the probe volume");
                    assertEquals(2, volume.probeCount());
                    assertEquals(1, volume.settings().updateBudget());

                    // The production path assumes the GL default clear depth. Restore
                    // it immediately after pre-filling so the recovery clear is not
                    // accidentally the sentinel.
                    glClearDepth(SENTINEL_DEPTH);
                    try {
                        volume.probeDepthTarget().bind().clear(false, true);
                    } finally {
                        glClearDepth(1.0f);
                        glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    }
                    assertEquals(SENTINEL_DEPTH, probeCenterDepth(volume, 0, 3), 1.0e-6f,
                            "the fixture must pre-fill the unscheduled probe tile with the sentinel");
                    assertEquals(SENTINEL_DEPTH, probeCenterDepth(volume, 1, 3), 1.0e-6f);

                    String previous = System.getProperty(SUBMISSION_FAULT);
                    IllegalStateException failure;
                    try {
                        System.setProperty(SUBMISSION_FAULT, DdgiProbeVolume.PASS_NAME);
                        failure = assertThrows(IllegalStateException.class,
                                () -> execute(pipeline),
                                "the injected submission fault must fail the frame");
                    } finally {
                        restoreProperty(SUBMISSION_FAULT, previous);
                    }
                    assertTrue(failure.getMessage().contains(
                                    "injected graph submission failure after recording "
                                            + DdgiProbeVolume.PASS_NAME),
                            failure.getMessage());
                    assertNull(System.getProperty(SUBMISSION_FAULT),
                            "the one-shot fault must clear its property");
                    assertEquals(0, volume.updatedProbeCount(),
                            "a failed submission must invalidate the tentative schedule");
                    assertEquals(SENTINEL_DEPTH, probeCenterDepth(volume, 0, 3), 1.0e-6f,
                            "a failed submission must execute none of the recorded clears");

                    execute(pipeline);

                    assertEquals(1, volume.updatedProbeCount(),
                            "the recovery frame must schedule exactly one probe");
                    int scheduled = volume.updatedProbeIndex(0);
                    int unscheduled = 1 - scheduled;
                    assertEquals(1, scheduled,
                            "the failed frame already advanced the round-robin cursor to probe 0");
                    assertEquals(1.0f, probeCenterDepth(volume, unscheduled, 3), 0.0f,
                            "the recovery frame must re-clear the whole atlas, so an "
                                    + "unscheduled probe is 1.0 rather than the sentinel");
                    assertEquals(1.0f, probeCenterDepth(volume, unscheduled, 4), 0.0f);
                    assertEquals(1.0f, probeCenterDepth(volume, unscheduled, 5), 0.0f);
                    int visibleFace = scheduled == 0 ? 4 : 5;
                    assertTrue(probeCenterDepth(volume, scheduled, visibleFace) < 1.0f,
                            "the recovery frame must rasterize the scheduled probe's cube");
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "the recovery fixture must release every tracked resource");
            }
            GlDebug.assertNoError("DDGI failed-submission recovery");
        }
    }

    private static void execute(RenderPipeline pipeline) {
        pipeline.execute(new GlRenderDevice(), 1.0f / 60.0f);
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) System.clearProperty(name);
        else System.setProperty(name, previous);
    }

    private static float probeCenterDepth(DdgiProbeVolume volume, int probe, int face) {
        int resolution = volume.settings().depthFaceResolution();
        int x = (probe * DdgiSettings.FACE_COUNT + face) * resolution + resolution / 2;
        int y = resolution / 2;
        Framebuffer target = volume.probeDepthTarget();
        target.bind();
        FloatBuffer depth = BufferUtils.createFloatBuffer(1);
        glReadPixels(x, y, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return depth.get(0);
    }

    private static Fixture fixture(GlfwWindow window) {
        ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
        Mesh mesh = Mesh.from(BuiltinMeshData.coloredCube("ddgi-recovery-cube"));
        Material material = Material.builder(shader).build();
        Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 4.0f)));
        scene.add(SceneObject.fixed(mesh, material, new Matrix4f().scale(2.0f), true));
        scene.addLight(SceneLight.shadowedDirectional(
                new Vector3f(-0.3f, -1.0f, -0.4f), new Vector3f(1.0f), 1.0f));
        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                RenderSettings.builder()
                        .antiAliasingMode(AntiAliasingMode.NONE)
                        .toneMappingMode(ToneMappingMode.ACES)
                        .vsync(false)
                        .build())
                .ddgi(ddgi());
        return new Fixture(pipeline, material, shader, mesh);
    }

    private static DdgiSettings ddgi() {
        return DdgiSettings.builder()
                .enabled(true)
                .intensity(1.0f)
                .surfaceAlbedo(0.8f)
                .skyRadiance(0.1f, 0.1f, 0.1f)
                // Probe 0 is at (0.5, 0.0, -1.5), probe 1 at (0.5, 0.0, 1.5);
                // their +/-Z faces hit the unit cube's z = -/+1 faces.
                .gridOrigin(-2.0f, -1.5f, -3.0f)
                .spacingMetres(3.0f)
                .probes(1, 1, 2)
                .updateBudget(1)
                .hysteresis(0.0f)
                .maximumRayDistance(8.0f)
                .raySteps(8)
                .depthFaceResolution(4)
                .borderFadeMetres(0.5f)
                .maximumIrradiance(32.0f)
                .build();
    }

    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE).visible(false)
                .title("DDGI recovery GL test").build();
    }

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
            for (AutoCloseable resource : new AutoCloseable[]{material, shader, mesh}) {
                try {
                    resource.close();
                } catch (Exception closeFailure) {
                    RuntimeException runtime = closeFailure instanceof RuntimeException existing
                            ? existing
                            : new IllegalStateException("failed to close DDGI recovery fixture",
                                    closeFailure);
                    if (failure == null) failure = runtime;
                    else failure.addSuppressed(runtime);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static final String VERTEX_SOURCE = """
            #version 330 core
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

    private static final String FRAGMENT_SOURCE = """
            #version 330 core
            out vec4 FragColor;
            void main() {
                FragColor = vec4(0.8, 0.6, 0.4, 1.0);
            }
            """;
}
