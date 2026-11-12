package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiIndirectPass;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiProbeVolume;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandPass;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSceneInput;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

/**
 * Real-GL pipeline path for DDGI (#44), VCT (#45) and hair strands (#46).
 *
 * <p>Each case proves the feature contributes inside its declared region while
 * disabled settings restore the byte-identical baseline, and that the late
 * single-sample composites never write the shared opaque depth. These cases are
 * guarded by {@code haikalat.glSmoke} and are reported as {@code NOT_EVALUATED}
 * until the Orchestrator runs the serialized GPU queue.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class RenderPipelineWiringB3GlTest {
    private static final int SIZE = 32;

    @Test
    void ddgiIndirectContributesAndDisabledRestoresBaselineWithoutDepthWrites() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = pipeline(window, "ddgi-wiring");
                try {
                    pipeline.build();
                    execute(pipeline);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();
                    float[] baselineDepth = readSharedDepth(pipeline);

                    pipeline.applyDdgi(ddgi());
                    assertTrue(pipeline.graph().hasPass(DdgiProbeVolume.PASS_NAME));
                    assertTrue(pipeline.graph().hasPass(DdgiIndirectPass.PASS_NAME));
                    execute(pipeline);
                    float[] indirect = pipeline.captureLinearHdrRgbaFloat();
                    float[] indirectDepth = readSharedDepth(pipeline);

                    assertTrue(different(baseline, indirect),
                            "DDGI indirect light must modify the HDR scene colour");
                    assertArrayBitsEqual(baselineDepth, indirectDepth,
                            "DDGI must not write the shared opaque depth");

                    List<String> order = pipeline.graph().description().executionOrder();
                    assertTrue(order.indexOf(DdgiProbeVolume.PASS_NAME)
                            < order.indexOf(DdgiIndirectPass.PASS_NAME), order.toString());

                    pipeline.applyDdgi(DdgiSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(DdgiProbeVolume.PASS_NAME));
                    execute(pipeline);
                    float[] restored = pipeline.captureLinearHdrRgbaFloat();
                    assertArrayBitsEqual(baseline, restored,
                            "disabling DDGI must restore the byte-identical baseline");

                    // The resize candidate must rebuild the pass-owned indirect target.
                    pipeline.applyDdgi(ddgi());
                    pipeline.resize(SIZE + 8, SIZE + 4);
                    execute(pipeline);
                    assertTrue(pipeline.graph().hasPass(DdgiIndirectPass.PASS_NAME));
                    assertEquals(SIZE + 8, pipeline.graph().width());
                    assertEquals(SIZE + 4, pipeline.graph().height());
                    pipeline.applyDdgi(DdgiSettings.disabled());
                } finally {
                    pipeline.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("DDGI pipeline wiring");
        }
    }

    @Test
    void vctCompositeContributesAndDisabledRestoresBaselineWithoutDepthWrites() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = pipeline(window, "vct-wiring");
                try {
                    pipeline.build();
                    execute(pipeline);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();
                    float[] baselineDepth = readSharedDepth(pipeline);

                    pipeline.applyVct(vct());
                    pipeline.vctSceneInput(vctSceneInput());
                    assertTrue(pipeline.graph().hasPass(RenderPipeline.VCT_VOXELIZE_PASS));
                    assertTrue(pipeline.graph().hasPass(RenderPipeline.VCT_COMPOSITE_PASS));
                    execute(pipeline);
                    float[] composite = pipeline.captureLinearHdrRgbaFloat();
                    float[] compositeDepth = readSharedDepth(pipeline);

                    assertTrue(different(baseline, composite),
                            "voxel cone tracing must modify the HDR scene colour");
                    assertArrayBitsEqual(baselineDepth, compositeDepth,
                            "VCT must not write the shared opaque depth");

                    List<String> order = pipeline.graph().description().executionOrder();
                    assertTrue(order.indexOf(RenderPipeline.VCT_VOXELIZE_PASS)
                            < order.indexOf(RenderPipeline.VCT_COMPOSITE_PASS), order.toString());

                    pipeline.applyVct(VctSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(RenderPipeline.VCT_COMPOSITE_PASS));
                    execute(pipeline);
                    float[] restored = pipeline.captureLinearHdrRgbaFloat();
                    assertArrayBitsEqual(baseline, restored,
                            "disabling VCT must restore the byte-identical baseline");
                } finally {
                    pipeline.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("VCT pipeline wiring");
        }
    }

    @Test
    void hairStrandsContributeInsideTheLockAndDisabledRestoreBaseline() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = pipeline(window, "hair-wiring");
                try {
                    pipeline.build();
                    execute(pipeline);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                    pipeline.applyHairStrands(hair());
                    pipeline.hairStrandModel(new Matrix4f().translation(0.0f, 0.3f, 0.4f)
                            .scale(2.0f));
                    assertTrue(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));
                    execute(pipeline);
                    float[] strands = pipeline.captureLinearHdrRgbaFloat();

                    int changed = countChangedPixels(baseline, strands);
                    assertTrue(changed > 0,
                            "hair strands must contribute inside the lock region");
                    int width = pipeline.graph().width();
                    int height = pipeline.graph().height();
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            if (x >= 2 && x < width - 2 && y >= 2 && y < height - 2) continue;
                            int index = (y * width + x) * 4;
                            assertSamePixel(baseline, strands, index,
                                    "hair must stay inside the declared lock region");
                        }
                    }

                    pipeline.applyHairStrands(HairStrandSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));
                    execute(pipeline);
                    float[] restored = pipeline.captureLinearHdrRgbaFloat();
                    assertArrayBitsEqual(baseline, restored,
                            "disabling hair strands must restore the byte-identical baseline");
                } finally {
                    pipeline.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("hair pipeline wiring");
        }
    }

    private static RenderPipeline pipeline(GlfwWindow window, String name) {
        MeshData data = BuiltinMeshData.coloredCube(name);
        ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
        Mesh mesh = Mesh.from(data);
        Material material = Material.builder(shader).build();
        Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 4.0f)));
        scene.add(SceneObject.fixed(mesh, material, new Matrix4f().scale(2.0f), true));
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.3f, -1.0f, -0.4f),
                new Vector3f(1.0f), 1.0f));
        return new RenderPipeline(window, scene, null, RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build());
    }

    private static void execute(RenderPipeline pipeline) {
        pipeline.execute(new GlRenderDevice(), 1.0f / 60.0f);
        GlDebug.assertNoError("pipeline feature frame");
    }

    private static float[] readSharedDepth(RenderPipeline pipeline) {
        var depth = pipeline.activeGenerationForTest().topology().sceneSurface()
                .single(SceneBufferChannel.DEPTH);
        assertNotNull(depth, "the feature topology must expose the shared single depth");
        int[] texture = new int[1];
        pipeline.graph().addPass("B3DepthProbe")
                .computeOnly()
                .dependsOn(pipeline.finalPassName())
                .execute((resources, commands) ->
                        texture[0] = resources.depthAttachment(depth.name()));
        pipeline.execute(new GlRenderDevice(), 1.0f / 60.0f);
        assertTrue(texture[0] > 0, "the shared depth texture must be readable");
        return readDepth(texture[0], pipeline.graph().width(), pipeline.graph().height());
    }

    private static DdgiSettings ddgi() {
        return DdgiSettings.builder()
                .enabled(true)
                .intensity(2.0f)
                .surfaceAlbedo(0.8f)
                .skyRadiance(0.1f, 0.1f, 0.1f)
                .gridOrigin(-3.0f, -3.0f, -3.0f)
                .spacingMetres(2.0f)
                .probes(2, 2, 2)
                .updateBudget(8)
                .hysteresis(0.0f)
                .maximumRayDistance(8.0f)
                .raySteps(16)
                .depthFaceResolution(8)
                .borderFadeMetres(0.5f)
                .maximumIrradiance(32.0f)
                .build();
    }

    private static VctSettings vct() {
        return VctSettings.enabled(32, 4.0f);
    }

    private static VctSceneInput vctSceneInput() {
        return VctSceneInput.builder(1L)
                .addBox(new Vector3f(-1.5f, -1.5f, -1.5f), new Vector3f(1.5f, 1.5f, 1.5f),
                        2.0f, 1.6f, 1.2f)
                .build();
    }

    private static HairStrandSettings hair() {
        return HairStrandSettings.builder()
                .strandCount(64)
                .segmentsPerStrand(8)
                .strandLengthMetres(1.0f)
                .rootSpreadMetres(0.3f)
                .widthPixels(4.0f)
                .minimumWidthPixels(2.0f)
                .maximumWidthPixels(8.0f)
                .nearDistanceMetres(1.0f)
                .farDistanceMetres(20.0f)
                .transparency(HairStrandSettings.Transparency.SORTED_ALPHA)
                .build();
    }

    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE).visible(false)
                .title("Render3D wiring B3").build();
    }

    private static float[] readDepth(int texture, int width, int height) {
        int framebuffer = org.lwjgl.opengl.GL30.glGenFramebuffers();
        FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height);
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                    GL_TEXTURE_2D, texture, 0);
            org.lwjgl.opengl.GL11.glDrawBuffer(org.lwjgl.opengl.GL11.GL_NONE);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_NONE);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, width, height, GL_DEPTH_COMPONENT,
                    GL_FLOAT, buffer);
            float[] result = new float[width * height];
            buffer.get(result);
            return result;
        } finally {
            MemoryUtil.memFree(buffer);
            org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer);
        }
    }

    private static void assertArrayBitsEqual(float[] expected, float[] actual, String message) {
        assertEquals(expected.length, actual.length, message);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(Float.floatToIntBits(expected[index]),
                    Float.floatToIntBits(actual[index]), message + " at index " + index);
        }
    }

    private static void assertSamePixel(float[] expected, float[] actual, int index,
                                        String message) {
        for (int component = 0; component < 4; component++) {
            assertEquals(Float.floatToIntBits(expected[index + component]),
                    Float.floatToIntBits(actual[index + component]),
                    message + " at pixel index " + (index / 4));
        }
    }

    private static boolean different(float[] left, float[] right) {
        return countChangedPixels(left, right) > 0;
    }

    private static int countChangedPixels(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("pixel arrays must have the same length");
        }
        int changed = 0;
        for (int index = 0; index < left.length; index += 4) {
            if (!samePixel(left, right, index)) changed++;
        }
        return changed;
    }

    private static boolean samePixel(float[] left, float[] right, int index) {
        for (int component = 0; component < 4; component++) {
            if (Float.floatToIntBits(left[index + component])
                    != Float.floatToIntBits(right[index + component])) return false;
        }
        return true;
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
