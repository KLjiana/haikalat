package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctBox;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctPass;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSceneInput;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-GL regression for the VCT failed-submission recovery fix.
 *
 * <p>{@code VctPass.recordVoxelize} marks the volume valid and consumes the
 * staged dirty boxes while recording. If that submission fails, the next frame
 * with the same scene revision and an empty dirty list used to report
 * {@code noOp = true} even though no voxel command reached the GPU.
 * {@code RenderPipeline} now calls {@link VctPass#invalidatePendingUpdate()},
 * so the recovery frame must force a full rebuild. The assertions separate the
 * three states: first frame full update, failed partial update (dirty boxes
 * consumed, volume invalidated), recovery frame full update with
 * {@code dispatches > 0}.</p>
 *
 * <p>The fault is the test-only {@code haikalat.test.failSubmissionAfterPass}
 * property handled by {@code RenderGraph}; it fires once after all pass
 * callbacks recorded and before {@code device.execute}. Production never sets
 * it.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VctRecoveryGlTest {
    private static final int SIZE = 32;
    private static final long SCENE_REVISION = 17L;
    private static final String SUBMISSION_FAULT =
            "haikalat.test.failSubmissionAfterPass";

    @Test
    void failedPartialVoxelSubmissionForcesAFullRebuildOnTheSameRevision() {
        try (GlfwWindow window = window()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (Fixture fixture = fixture(window)) {
                    RenderPipeline pipeline = fixture.pipeline();
                    pipeline.build();
                    VctPass pass = pipeline.activeGenerationForTest().vct();
                    assertNotNull(pass, "the active generation must own the VCT pass");
                    assertTrue(pass.active());
                    pipeline.vctSceneInput(sceneInput());
                    pipeline.vctSurface(0.35f, new Vector3f(0.8f, 0.8f, 0.8f));

                    // Frame 1: no dirty boxes and a new revision => forced full update.
                    execute(pipeline);
                    VctPass.UpdateStats first = pass.lastUpdateStats();
                    assertTrue(pass.volumeValid(), "the first successful frame must publish the volume");
                    assertTrue(first.full(), "the first update must be a full rebuild: " + first);
                    assertTrue(first.dispatches() > 0, "the full rebuild must record dispatches: " + first);
                    assertFalse(first.noOp(), "the first update must not be a no-op: " + first);

                    // Frame 2: an explicit dirty region => partial update. The fault
                    // fires after recording, so the dirty boxes are already consumed
                    // but no dispatch reached the GPU.
                    pipeline.vctDirtyBoxes(List.of(
                            VctBox.of(-1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)));
                    String previous = System.getProperty(SUBMISSION_FAULT);
                    IllegalStateException failure;
                    try {
                        System.setProperty(SUBMISSION_FAULT,
                                RenderPipeline.VCT_VOXELIZE_PASS);
                        failure = assertThrows(IllegalStateException.class,
                                () -> execute(pipeline),
                                "the injected submission fault must fail the frame");
                    } finally {
                        restoreProperty(SUBMISSION_FAULT, previous);
                    }
                    assertTrue(failure.getMessage().contains(
                                    "injected graph submission failure after recording "
                                            + RenderPipeline.VCT_VOXELIZE_PASS),
                            failure.getMessage());
                    assertNull(System.getProperty(SUBMISSION_FAULT),
                            "the one-shot fault must clear its property");
                    assertEquals(List.of(), pipeline.vctDirtyBoxes(),
                            "recording the failed partial update must have consumed the dirty boxes");
                    assertFalse(pass.volumeValid(),
                            "the failed submission must invalidate the tentative volume");
                    assertEquals(VctPass.UpdateStats.NONE, pass.lastUpdateStats(),
                            "the failed submission must discard the tentative update stats");

                    // Frame 3: same revision and no dirty boxes. Only the invalidation
                    // can force the full rebuild; without it this is a no-op.
                    execute(pipeline);
                    VctPass.UpdateStats recovery = pass.lastUpdateStats();
                    assertTrue(pass.volumeValid(),
                            "the recovery frame must publish the rebuilt volume");
                    assertTrue(recovery.full(),
                            "the recovery frame must rebuild the whole volume: " + recovery);
                    assertTrue(recovery.dispatches() > 0,
                            "the recovery frame must record dispatches: " + recovery);
                    assertFalse(recovery.noOp(),
                            "the recovery frame must not be a no-op: " + recovery);
                    assertTrue(recovery.regions() >= 1,
                            "a full rebuild must cover at least one region: " + recovery);
                    assertEquals(SCENE_REVISION, pipeline.vctSceneInput().revision(),
                            "the recovery frame must keep the same scene revision");
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "the recovery fixture must release every tracked resource");
            }
            GlDebug.assertNoError("VCT failed-submission recovery");
        }
    }

    private static void execute(RenderPipeline pipeline) {
        pipeline.execute(new GlRenderDevice(), 1.0f / 60.0f);
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) System.clearProperty(name);
        else System.setProperty(name, previous);
    }

    private static VctSceneInput sceneInput() {
        return VctSceneInput.builder(SCENE_REVISION)
                .addBox(new Vector3f(-1.5f, -1.5f, -1.5f), new Vector3f(1.5f, 1.5f, 1.5f),
                        2.0f, 1.6f, 1.2f)
                .build();
    }

    private static Fixture fixture(GlfwWindow window) {
        ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
        Mesh mesh = Mesh.from(BuiltinMeshData.coloredCube("vct-recovery-cube"));
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
                .vct(VctSettings.enabled(32, 4.0f));
        return new Fixture(pipeline, material, shader, mesh);
    }

    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE).visible(false)
                .title("VCT recovery GL test").build();
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
                            : new IllegalStateException("failed to close VCT recovery fixture",
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
