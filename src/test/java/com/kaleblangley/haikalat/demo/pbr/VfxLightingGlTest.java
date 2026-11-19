package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.vfx.VfxLight;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadPixels;

/**
 * Real PBR readback for the v0.28 effect-light bridge.
 *
 * <p>A temporary effect light must produce the same lit receiver as a manual
 * Scene point light with identical values, and both HDR VFX and direct-lighting
 * gates must return the receiver to the unlit baseline without dropping the
 * user Scene lights.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VfxLightingGlTest {
    private static final Vector3f LIGHT_POSITION = new Vector3f(0.0f, 0.0f, 2.2f);
    private static final Vector3f LIGHT_COLOR = new Vector3f(1.0f, 0.25f, 0.1f);
    private static final float LIGHT_INTENSITY = 12.0f;
    private static final float LIGHT_RANGE = 9.0f;

    @Test
    void effectLightMatchesManualPointLightAndHonorsBothGates() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(64, 64)
                .title("VFX Light PBR Integration").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            byte[] baseline = render(window, false, false, false, false);
            byte[] manual = render(window, true, false, false, false);
            byte[] effect = render(window, false, true, false, false);
            byte[] hdrGated = render(window, false, true, true, false);
            byte[] directGated = render(window, false, true, false, true);
            byte[] directOffBaseline = render(window, false, false, false, true);

            assertTrue(difference(baseline, manual) > 6,
                    "manual point light must brighten the PBR receiver baseline="
                            + java.util.Arrays.toString(baseline) + " manual="
                            + java.util.Arrays.toString(manual));
            assertTrue(difference(manual, effect) <= 3,
                    "effect light must match the manual point light on the receiver manual="
                            + java.util.Arrays.toString(manual) + " effect="
                            + java.util.Arrays.toString(effect));
            assertTrue(difference(baseline, hdrGated) <= 2,
                    "HDR VFX gate must stop the associated effect light baseline="
                            + java.util.Arrays.toString(baseline) + " gated="
                            + java.util.Arrays.toString(hdrGated));
            assertTrue(difference(directOffBaseline, directGated) <= 2,
                    "direct-lighting gate must keep the light input but stop surface contribution baseline="
                            + java.util.Arrays.toString(directOffBaseline) + " gated="
                            + java.util.Arrays.toString(directGated));
            GlDebug.checkError("effectLightMatchesManualPointLightAndHonorsBothGates");
        }
    }

    private static byte[] render(GlfwWindow window, boolean manualLight, boolean effectLight,
                                 boolean hdrVfxOff, boolean directLightingOff) {
        RenderSettings settings = RenderSettings.builder()
                .vsync(false)
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .build();
        try (FrameDriver driver = new FrameDriver(settings);
             PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(),
                     VfxLightingGlTest.class, "/environments/pbr/studio-small.hdr",
                     PbrEnvironmentSettings.quality("test"));
             PbrFallbackTextures fallbacks = new PbrFallbackTextures();
             ShaderProgram pbrShader = ShaderProgram.fromResource(VfxLightingGlTest.class,
                     "/shaders/render3d/pbr/pbr-forward.vert",
                     "/shaders/render3d/pbr/pbr-forward.frag")) {
            Mesh sphere = Mesh.from(PbrSphereMesh.create(24, 16));
            Material material = PbrMaterials.create(pbrShader,
                    new PbrMaterialProperties(new Vector4f(0.8f, 0.55f, 0.3f, 1.0f),
                            0.0f, 0.45f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                    Map.of(), fallbacks);
            Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 4.0f)));
            scene.add(SceneObject.fixed(sphere, material,
                    new org.joml.Matrix4f().scale(1.6f)));
            scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.4f, -1.0f, -0.5f),
                    new Vector3f(1.0f, 0.95f, 0.9f), 0.35f));
            if (manualLight) {
                scene.addLight(SceneLight.point(LIGHT_POSITION, LIGHT_COLOR,
                        LIGHT_INTENSITY, LIGHT_RANGE));
            }
            RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment)
                    .maxVfxLights(2);
            if (effectLight) {
                pipeline.vfxLights(List.of(new VfxLight(1L, 0,
                        LIGHT_POSITION, LIGHT_COLOR, LIGHT_INTENSITY, LIGHT_RANGE, 5)));
            }
            if (hdrVfxOff || directLightingOff) {
                RenderFeatureSettings.Builder builder = RenderFeatureSettings.defaults().toBuilder()
                        .render(settings);
                if (hdrVfxOff) builder.hdrVfx(false);
                if (directLightingOff) builder.directLighting(false);
                pipeline.features().submit(builder.build());
            }
            try {
                pipeline.build();
                pipeline.execute(driver.device());
                if (effectLight && hdrVfxOff) {
                    assertEquals("hdr-vfx-disabled", pipeline.vfxLightDiagnostics().reason());
                }
                if (effectLight && directLightingOff) {
                    assertEquals("direct-lighting-disabled", pipeline.vfxLightDiagnostics().reason());
                    assertEquals(1, pipeline.vfxLightDiagnostics().selected());
                }
                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                glReadPixels(window.width() / 2, window.height() / 2, 1, 1,
                        GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                byte[] result = new byte[4];
                pixel.get(result);
                if (effectLight && !hdrVfxOff && !directLightingOff) {
                    long builds = pipeline.lastRender3dDiagnostics().caches().generationBuilds();
                    pipeline.vfxLights(List.of(new VfxLight(1L, 0,
                            new Vector3f(LIGHT_POSITION).add(0.2f, 0.0f, 0.0f),
                            LIGHT_COLOR, LIGHT_INTENSITY, LIGHT_RANGE, 5)));
                    pipeline.execute(driver.device());
                    assertEquals(builds,
                            pipeline.lastRender3dDiagnostics().caches().generationBuilds(),
                            "moving an effect light must not rebuild the RenderGraph");
                    assertFalse(pipeline.lastRender3dDiagnostics().invalidationReasons()
                                    .contains("LIGHTING"),
                            "moving an effect light must not invalidate scene lighting history");
                    pipeline.maxVfxLights(1);
                    pipeline.execute(driver.device());
                    assertEquals(builds,
                            pipeline.lastRender3dDiagnostics().caches().generationBuilds(),
                            "changing the effect-light selection cap must not rebuild the RenderGraph");
                }
                return result;
            } finally {
                pipeline.close();
            }
        }
    }

    private static int difference(byte[] first, byte[] second) {
        int total = 0;
        for (int index = 0; index < Math.min(first.length, second.length); index++) {
            total += Math.abs(Byte.toUnsignedInt(first[index]) - Byte.toUnsignedInt(second[index]));
        }
        return total;
    }
}
