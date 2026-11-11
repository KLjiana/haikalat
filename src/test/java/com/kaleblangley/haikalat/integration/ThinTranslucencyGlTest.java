package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.Map;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadPixels;

/**
 * 真实 GL 对照：同一双面薄面片在背光/正面光下的中心像素，以及控制值为零时的基线。
 *
 * <p>背光使用与 {@code pbr-forward.frag} 相同的世界空间灯方向约定；正面光用来证明
 * 零值路径没有改变原有 PBR 表面结果。该用例默认不执行，需显式 GL 入口和桌面 OpenGL 4.6。</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ThinTranslucencyGlTest {
    private static final float LIGHT_INTENSITY = 3.0f;

    @Test
    void backlitThinSurfaceContrastsWithFrontLightAndZeroControlsStayBaseline() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 Mesh mesh = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("thin-translucency-quad")).mesh())) {
                // Direct-light contrast only: isolate the new term from ambient IBL.
                environment.intensity(0.0f);
                int backZero = renderCenterEnergy(window, device, environment, fallbacks, shader,
                        mesh, new Vector3f(0.0f, 0.0f, 1.0f), PbrMaterialProperties.defaults());
                int backThin = renderCenterEnergy(window, device, environment, fallbacks, shader,
                        mesh, new Vector3f(0.0f, 0.0f, 1.0f), thinProperties());
                int frontZero = renderCenterEnergy(window, device, environment, fallbacks, shader,
                        mesh, new Vector3f(0.0f, 0.0f, -1.0f), PbrMaterialProperties.defaults());
                int frontThin = renderCenterEnergy(window, device, environment, fallbacks, shader,
                        mesh, new Vector3f(0.0f, 0.0f, -1.0f), thinProperties());

                assertTrue(backThin > backZero + 30,
                        "backlit thin surface must transmit visible energy over the zero baseline: "
                                + backThin + " vs " + backZero);
                assertTrue(frontZero > backZero + 5,
                        "front lighting must illuminate the surface more than the backlit baseline: "
                                + frontZero + " vs " + backZero);
                assertEquals(frontZero, frontThin, 2,
                        "non-zero thin controls must not change the front-lit surface result");
                GlDebug.checkError("thin translucency back/front light contrast");
            }
        }
    }

    private static int renderCenterEnergy(GlfwWindow window, GlRenderDevice device,
                                          PbrEnvironment environment,
                                          PbrFallbackTextures fallbacks, ShaderProgram shader,
                                          Mesh mesh, Vector3f lightDirection,
                                          PbrMaterialProperties properties) {
        Material material = PbrMaterials.createWithBindings(shader, properties, Map.of(), fallbacks,
                CullMode.NONE, true, false);
        Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 3.0f)))
                .add(SceneObject.fixed(mesh, material, new Matrix4f(), false))
                .addLight(SceneLight.directional(lightDirection,
                        new Vector3f(1.0f), LIGHT_INTENSITY));
        RenderSettings settings = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES)
                .bloomSettings(BloomSettings.disabled())
                .vsync(false)
                .build();
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment);
        try {
            pipeline.build();
            pipeline.execute(device);
            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            return Byte.toUnsignedInt(pixel.get(0))
                    + Byte.toUnsignedInt(pixel.get(1))
                    + Byte.toUnsignedInt(pixel.get(2));
        } finally {
            pipeline.close();
            material.close();
        }
    }

    private static PbrMaterialProperties thinProperties() {
        return new PbrMaterialProperties(new Vector4f(1.0f), 0.0f, 1.0f, 1.0f, 1.0f,
                new Vector3f(), Map.of(), 0.0f, 0.9f, 0.9f);
    }
}
