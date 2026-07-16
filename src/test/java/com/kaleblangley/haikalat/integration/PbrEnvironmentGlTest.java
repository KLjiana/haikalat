package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureCube;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.FloatBuffer;
import java.nio.ByteBuffer;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL45.glGetTextureImage;

/** 覆盖 HDR float decode、layered cube image、mip 与 BRDF LUT 的真实 GL 闭环。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class PbrEnvironmentGlTest {
    @Test
    void testQualityEnvironmentPreprocessesOnGpuAndOwnsResources() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            PbrEnvironment environment = PbrEnvironmentLoader.load(
                    getClass(), "/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
            TextureCube radiance = environment.radiance();
            TextureCube irradiance = environment.irradiance();
            TextureCube prefiltered = environment.prefilteredSpecular();
            Texture2D lut = environment.brdfLut();
            assertFiniteNonZeroCube(radiance, 0);
            assertFiniteNonZeroCube(irradiance, 0);
            assertFiniteNonZeroCube(prefiltered, prefiltered.mipLevels() - 1);
            assertFiniteNonZeroLut(lut);
            GlDebug.checkError("PBR environment preprocessing");

            environment.close();
            assertTrue(radiance.isClosed() && irradiance.isClosed()
                    && prefiltered.isClosed() && lut.isClosed());
            assertDoesNotThrow(environment::close);
            assertThrows(RuntimeException.class, environment::radiance);
        }
    }

    @Test
    void pbrForwardPathProducesObjectPixelsThroughHdrAndAces() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(
                    getClass(), "/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/com/kaleblangley/haikalat/subsystems/render3d/pbr/shaders/pbr_forward.vert",
                         "/com/kaleblangley/haikalat/subsystems/render3d/pbr/shaders/pbr_forward.frag");
                 Mesh mesh = Mesh.from(pbrTriangle())) {
                Material material = PbrMaterials.create(shader,
                        new PbrMaterialProperties(new Vector4f(0.9f, 0.12f, 0.04f, 1.0f),
                                0.0f, 0.55f, 1.0f, 1.0f, new Vector3f(), java.util.Map.of()),
                        java.util.Map.of(), fallbacks);
                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 3.0f)))
                        .add(new SceneObject(mesh, material, (out, frame) -> out.identity(), false))
                        .addLight(SceneLight.directional(new Vector3f(0.0f, 0.0f, -1.0f),
                                new Vector3f(1.0f), 3.0f));
                RenderSettings settings = RenderSettings.builder()
                        .toneMappingMode(ToneMappingMode.ACES)
                        .bloomSettings(BloomSettings.disabled())
                        .build();
                RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment);
                try {
                    pipeline.build();
                    int radianceId = environment.radiance().id();
                    int irradianceId = environment.irradiance().id();
                    pipeline.resize(47, 33);
                    assertTrue(radianceId == environment.radiance().id()
                            && irradianceId == environment.irradiance().id(),
                            "resize must not rebuild fixed-size environment resources");
                    pipeline.resize(window.width(), window.height());
                    pipeline.execute(new GlRenderDevice());
                    ByteBuffer center = readPixel(16, 16);
                    ByteBuffer corner = readPixel(1, 1);
                    int centerEnergy = Byte.toUnsignedInt(center.get(0))
                            + Byte.toUnsignedInt(center.get(1)) + Byte.toUnsignedInt(center.get(2));
                    int cornerEnergy = Byte.toUnsignedInt(corner.get(0))
                            + Byte.toUnsignedInt(corner.get(1)) + Byte.toUnsignedInt(corner.get(2));
                    assertTrue(centerEnergy > 20, "PBR object must produce a visible pixel");
                    assertTrue(Math.abs(centerEnergy - cornerEnergy) > 5,
                            "object pixel must differ from the environment background");
                    GlDebug.checkError("PBR forward HDR/ACES pixel smoke");
                } finally {
                    pipeline.close();
                    material.close();
                }
            }
        }
    }

    @Test
    void stateCacheDistinguishesTextureTargetAndSkipsRepeatedCubeBind() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (Texture2D texture2D = Texture2D.createEmpty(1, 1,
                    org.lwjgl.opengl.GL11.GL_RGBA8);
                 TextureCube cube = TextureCube.create(2, 1, RenderFormat.RGBA16F)) {
                GlRenderDevice device = new GlRenderDevice();
                device.execute(device.createCommandBuffer()
                        .bindTexture(2, texture2D)
                        .bindTextureCube(2, cube)
                        .bindTextureCube(2, cube)
                        .bindTexture(2, texture2D));
                var statistics = device.stateStatistics();
                assertTrue(statistics.appliedChanges() >= 4,
                        "2D/cube target transitions must issue real binds");
                assertTrue(statistics.avoidedChanges() >= 2,
                        "repeated cube and sampler bindings should be skipped");
                GlDebug.checkError("2D/cube state cache transitions");
            }
        }
    }

    private static MeshData pbrTriangle() {
        VertexLayout layout = VertexLayout.interleaved(12 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).semantic(VertexSemantic.POSITION).offsetBytes(0).build(),
                VertexAttribute.builder().index(1).size(2).semantic(VertexSemantic.TEXCOORD_0).offsetBytes(12).build(),
                VertexAttribute.builder().index(2).size(3).semantic(VertexSemantic.NORMAL).offsetBytes(20).build(),
                VertexAttribute.builder().index(3).size(4).semantic(VertexSemantic.TANGENT).offsetBytes(32).build());
        return MeshData.of("pbr-smoke", new float[]{
                -1.2f, -1.0f, 0.0f, 0.0f, 0.0f, 0, 0, 1, 1, 0, 0, 1,
                 1.2f, -1.0f, 0.0f, 1.0f, 0.0f, 0, 0, 1, 1, 0, 0, 1,
                 0.0f,  1.2f, 0.0f, 0.5f, 1.0f, 0, 0, 1, 1, 0, 0, 1
        }, layout);
    }

    private static ByteBuffer readPixel(int x, int y) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        org.lwjgl.opengl.GL11.glReadPixels(x, y, 1, 1,
                org.lwjgl.opengl.GL11.GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        return pixel;
    }

    private static void assertFiniteNonZeroCube(TextureCube cube, int mip) {
        int size = Math.max(1, cube.size() >> mip);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(size * size * 6 * 4);
        glGetTextureImage(cube.id(), mip, GL_RGBA, GL_FLOAT, pixels);
        assertFiniteNonZero(pixels);
    }

    private static void assertFiniteNonZeroLut(Texture2D texture) {
        FloatBuffer pixels = BufferUtils.createFloatBuffer(texture.width() * texture.height() * 2);
        glGetTextureImage(texture.id(), 0, GL_RG, GL_FLOAT, pixels);
        assertFiniteNonZero(pixels);
    }

    private static void assertFiniteNonZero(FloatBuffer values) {
        boolean nonZero = false;
        for (int i = 0; i < values.capacity(); i++) {
            float value = values.get(i);
            assertTrue(Float.isFinite(value), "precomputed texture contains non-finite data");
            nonZero |= Math.abs(value) > 1.0e-6f;
        }
        assertTrue(nonZero, "precomputed texture must contain visible energy");
    }
}
