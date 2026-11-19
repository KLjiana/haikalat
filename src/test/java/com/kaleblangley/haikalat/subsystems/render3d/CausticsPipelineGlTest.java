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
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
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

    @Test
    void directionalAtlasRejectsCausticsUnderAnOccluderButKeepsLitReceivers() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (Mesh quad = Mesh.from(BuiltinMeshData.coloredQuad("caustics-shadow-quad"));
                 ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
                 Material material = Material.builder(shader).build()) {
                Camera camera = new Camera(new Vector3f(0, 3, 8), new Vector3f(0, 1, 0), -90, -26);
                Scene scene = new Scene(camera);
                scene.addLight(SceneLight.shadowedDirectional(new Vector3f(1, -1, 0), new Vector3f(1), 1));
                float horizontal = (float) (-Math.PI / 2);
                scene.add(SceneObject.fixed(quad, material,
                        new Matrix4f().translate(0, -1, 0).rotateX(horizontal).scale(12), false));
                scene.add(SceneObject.fixed(quad, material,
                        new Matrix4f().translate(0, 1, 0).rotateX(horizontal).scale(1.5f)));
                var caustics = CausticsSettings.builder().enabled(true).waterPlane(0)
                        .waterRegion(-8, -8, 8, 8).receiverHeight(-4, -0.5f).edgeFadeDistance(0)
                        .animationSpeed(0).intensity(3).build();
                var water = WaterSurfaceSettings.builder().enabled(true).bounds(-8, -8, 8, 8)
                        .level(0).waveAmplitude(0).build();
                RenderPipeline pipeline = new RenderPipeline(window, scene, null, hdrSettings())
                        .waterSurface(water).directionalCascades(new DirectionalCascadeSettings(
                                4, 512, 0.6f, 0.08f, 30)).localShadows(LocalShadowPipelineSettings.balanced());
                try {
                    var device = new GlRenderDevice();
                    pipeline.build();
                    var controls = pipeline.features();
                    var baseline = controls.requested();
                    float[][] pixels = new float[4][];
                    for (int i = 0; i < pixels.length; i++) {
                        controls.submit(baseline.toBuilder().directionalShadows((i & 2) != 0)
                                .caustics((i & 1) != 0 ? caustics : CausticsSettings.disabled()).build());
                        pipeline.execute(device, 0);
                        pixels[i] = pipeline.captureLinearHdrRgbaFloat();
                    }
                    var description = pipeline.graph().description().passes().stream()
                            .filter(pass -> pass.name().equals(CausticsPass.PASS_NAME)).findFirst().orElseThrow();
                    assertTrue(description.directDependencies().contains(DirectionalShadowMap.PASS_NAME));
                    Matrix4f pv = CameraProjection.stable(camera, window.width(), window.height(), new Matrix4f())
                            .mul(camera.getViewMatrix());
                    int[] viewport = {0, 0, window.width(), window.height()};
                    // At y=-1 the light has moved 2m in +X from the caster at y=1.
                    Vector3f under = pv.project(2, -1, 0, viewport, new Vector3f());
                    Vector3f open = pv.project(-2, -1, 0, viewport, new Vector3f());
                    float underUnshadowed = peakGain(pixels[0], pixels[1], window.width(), under);
                    float underShadowed = peakGain(pixels[2], pixels[3], window.width(), under);
                    float openUnshadowed = peakGain(pixels[0], pixels[1], window.width(), open);
                    float openShadowed = peakGain(pixels[2], pixels[3], window.width(), open);
                    assertTrue(underUnshadowed > 0.02f && underShadowed < 0.002f,
                            "blocked caustics: " + underUnshadowed + " -> " + underShadowed);
                    assertTrue(openUnshadowed > 0.02f && Math.abs(openShadowed - openUnshadowed) < 0.002f,
                            "unblocked caustics: " + openUnshadowed + " -> " + openShadowed);
                } finally {
                    pipeline.close();
                }
            }
            GlDebug.assertNoError("caustics directional shadow composition");
        }
    }

    private static float peakGain(float[] off, float[] on, int width, Vector3f projected) {
        float gain = 0;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int i = 4 * (((int) projected.y + dy) * width + (int) projected.x + dx) + 1;
            gain = Math.max(gain, on[i] - off[i]);
        }
        return gain;
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
