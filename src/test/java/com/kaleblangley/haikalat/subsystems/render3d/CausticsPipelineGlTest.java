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
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsPass;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-GL production path for bounded water caustics.
 *
 * <p>The pass is inserted after the opaque scene and before the water consumer.
 * Enabling it must change the water-region pixels, disabling it must restore the
 * byte-identical baseline, and the graph pass must not attach the shared depth.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class CausticsPipelineGlTest {
    private static final int SIZE = 64;
    private static final String VERTEX = """
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
    private static final String FRAGMENT = """
            #version 330 core
            out vec4 FragColor;
            void main() {
                FragColor = vec4(0.12, 0.18, 0.24, 1.0);
            }
            """;

    @Test
    void enabledCausticsChangeTheWaterRegionAndDisabledRestoresTheBaseline() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                Mesh mesh = Mesh.from(BuiltinMeshData.coloredQuad("caustics-wiring-floor"));
                ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
                Material material = Material.builder(shader).build();
                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 3.0f, 8.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -20.0f));
                scene.addLight(SceneLight.directional(new Vector3f(0.15f, -1.0f, 0.25f),
                        new Vector3f(1.0f), 1.0f));
                scene.add(new SceneObject(mesh, material, (model, frame) ->
                        model.identity().translate(0.0f, -2.0f, 0.0f)
                                .rotateX((float) -Math.PI * 0.5f).scale(8.0f)));
                WaterSurfaceSettings water = WaterSurfaceSettings.builder().enabled(true)
                        .level(0.0f).bounds(-20.0f, -20.0f, 20.0f, 20.0f)
                        // Freeze the wave animation: the byte-identical disabled
                        // comparison renders a later frame, so an animated surface
                        // would legitimately change between the two readbacks.
                        .waveSpeed(0.0f).waveAmplitude(0.0f).build();
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        hdrSettings()).waterSurface(water);
                try {
                    pipeline.build();
                    assertFalse(pipeline.graph().hasPass(CausticsPass.PASS_NAME));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                    CausticsSettings active = CausticsSettings.builder().enabled(true)
                            .waterPlane(0.0f).waterRegion(-20.0f, -20.0f, 20.0f, 20.0f)
                            .receiverHeight(-8.0f, -0.5f).intensity(3.0f)
                            .edgeFadeDistance(0.0f).build();
                    pipeline.applyCaustics(active);
                    assertTrue(pipeline.graph().hasPass(CausticsPass.PASS_NAME));
                    var causticsPass = pipeline.graph().description().passes().stream()
                            .filter(pass -> pass.name().equals(CausticsPass.PASS_NAME))
                            .findFirst().orElseThrow();
                    assertFalse(causticsPass.depthAttachment() != null,
                            "the caustics composite must not attach or write the shared depth");
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    float[] lit = pipeline.captureLinearHdrRgbaFloat();
                    assertTrue(brighter(baseline, lit),
                            "caustics must add a visible linear contribution in the water region");

                    pipeline.applyCaustics(CausticsSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(CausticsPass.PASS_NAME));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    assertArrayEquals(baseline, pipeline.captureLinearHdrRgbaFloat(),
                            "disabling caustics must restore the byte-identical baseline");
                } finally {
                    pipeline.close();
                    material.close();
                    shader.close();
                    mesh.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("caustics pipeline composition");
        }
    }

    private static RenderSettings hdrSettings() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE).visible(false)
                .title("Caustics wiring").build();
    }

    private static boolean brighter(float[] baseline, float[] lit) {
        if (baseline.length != lit.length) return false;
        double before = 0.0;
        double after = 0.0;
        for (int index = 0; index < baseline.length; index++) {
            before += baseline[index];
            after += lit[index];
        }
        return after > before + 1.0e-4;
    }
}
