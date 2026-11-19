package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrTextureBinding;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadPixels;

/**
 * 鐪熷疄 GL 瀵圭収锛氬悓涓€鍙岄潰钖勯潰鐗囧湪鑳屽厜/姝ｉ潰鍏変笅鐨勪腑蹇冨儚绱狅紝浠ュ強鎺у埗鍊间负闆舵椂鐨勫熀绾裤€? *
 * <p>鑳屽厜浣跨敤涓?{@code pbr-forward.frag} 鐩稿悓鐨勪笘鐣岀┖闂寸伅鏂瑰悜绾﹀畾锛涙闈㈠厜鐢ㄦ潵璇佹槑
 * 闆跺€艰矾寰勬病鏈夋敼鍙樺師鏈?PBR 琛ㄩ潰缁撴灉銆傝鐢ㄤ緥榛樿涓嶆墽琛岋紝闇€鏄惧紡 GL 鍏ュ彛鍜屾闈?OpenGL 4.6銆?/p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ThinTranslucencyGlTest {
    private static final float LIGHT_INTENSITY = 3.0f;
    private static final int WIDTH = 200;
    private static final int HEIGHT = 140;

    @Test
    void backlitThinSurfaceContrastsWithFrontLightAndZeroControlsStayBaseline() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(200, 140)
                .title("GL Smoke Test")
                .visible(false)
                .build()) {
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

    @Test
    void maskCutoutBackFaceAndSideLightKeepThinTransmissionBounded() {
        try (GlfwWindow window = thinWindow()) {
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 Mesh mesh = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("thin-translucency-boundaries")).mesh())) {
                environment.intensity(0.0f);
                Camera frontCamera = new Camera(new Vector3f(0.0f, 0.0f, 3.0f));
                Camera backCamera = new Camera(new Vector3f(0.0f, 0.0f, -3.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f), 90.0f, 0.0f);
                List<Vector3f> maskProbes = List.of(new Vector3f(-0.75f, 0.0f, 0.0f),
                        new Vector3f(0.75f, 0.0f, 0.0f), new Vector3f(2.2f, 0.0f, 0.0f));

                // A two-texel base-colour map: the left texel is below the MASK
                // cutoff, the right one is opaque.
                byte[] texels = {
                        (byte) 255, (byte) 255, (byte) 255, (byte) 51,
                        (byte) 255, (byte) 255, (byte) 255, (byte) 255};
                try (Texture2D baseColor = Texture2D.fromRgba8(2, 1, texels,
                        TextureColorSpace.SRGB);
                     Sampler sampler = Sampler.nearestRepeat()) {
                    Map<PbrTextureRole, PbrTextureBinding> bindings = Map.of(
                            PbrTextureRole.BASE_COLOR,
                            new PbrTextureBinding(baseColor, sampler));
                    Material masked = PbrMaterials.createWithBindings(shader,
                            thinProperties(0.0f), bindings, fallbacks, CullMode.NONE, true,
                            false, 0.5f, BlendMode.OPAQUE);
                    Material unmasked = PbrMaterials.createWithBindings(shader,
                            thinProperties(0.0f), bindings, fallbacks, CullMode.NONE, true,
                            false, 0.0f, BlendMode.OPAQUE);
                    try {
                        int[] maskedEnergy = renderProbeEnergies(window, device, environment,
                                fallbacks, shader, mesh, frontCamera,
                                new Vector3f(0.0f, 0.0f, 1.0f), masked, 3.0f, maskProbes);
                        int[] unmaskedEnergy = renderProbeEnergies(window, device, environment,
                                fallbacks, shader, mesh, frontCamera,
                                new Vector3f(0.0f, 0.0f, 1.0f), unmasked, 3.0f, maskProbes);
                        assertTrue(maskedEnergy[1] > 30,
                                "the opaque right texel must keep the backlit transmission: "
                                        + maskedEnergy[1]);
                        assertEquals(maskedEnergy[2], maskedEnergy[0], 2,
                                "the alpha-cut left texel must be discarded to the background");
                        assertTrue(unmaskedEnergy[0] > maskedEnergy[0] + 30,
                                "without the MASK cutoff the cut-out texel must be shaded and "
                                        + "transmit: " + unmaskedEnergy[0] + " vs "
                                        + maskedEnergy[0]);
                    } finally {
                        masked.close();
                        unmasked.close();
                    }
                }

                Material zero = PbrMaterials.createWithBindings(shader,
                        PbrMaterialProperties.defaults(), Map.of(), fallbacks,
                        CullMode.NONE, true, false);
                Material thin = PbrMaterials.createWithBindings(shader, thinProperties(0.0f),
                        Map.of(), fallbacks, CullMode.NONE, true, false);
                Material sideThin = PbrMaterials.createWithBindings(shader,
                        thinProperties(0.0f), Map.of(), fallbacks, CullMode.NONE, true,
                        false);
                Material sideZero = PbrMaterials.createWithBindings(shader,
                        PbrMaterialProperties.defaults(), Map.of(), fallbacks,
                        CullMode.NONE, true, false);
                try {
                    List<Vector3f> center = List.of(new Vector3f());
                    int[] backZero = renderProbeEnergies(window, device, environment,
                            fallbacks, shader, mesh, backCamera,
                            new Vector3f(0.0f, 0.0f, -1.0f), zero, 3.0f, center);
                    int[] backThin = renderProbeEnergies(window, device, environment,
                            fallbacks, shader, mesh, backCamera,
                            new Vector3f(0.0f, 0.0f, -1.0f), thin, 3.0f, center);
                    assertTrue(backThin[0] > backZero[0] + 30,
                            "a back-face camera view must keep the thin transmission: "
                                    + backThin[0] + " vs " + backZero[0]);
                    int[] frontZero = renderProbeEnergies(window, device, environment,
                            fallbacks, shader, mesh, frontCamera,
                            new Vector3f(0.0f, 0.0f, -1.0f), zero, 3.0f, center);
                    int[] frontThin = renderProbeEnergies(window, device, environment,
                            fallbacks, shader, mesh, frontCamera,
                            new Vector3f(0.0f, 0.0f, -1.0f), thin, 3.0f, center);
                    assertEquals(frontZero[0], frontThin[0], 2,
                            "the front-lit surface path must stay unchanged by the thin controls");
                    int[] sideThinEnergy = renderProbeEnergies(window, device, environment,
                            fallbacks, shader, mesh, frontCamera,
                            new Vector3f(1.0f, 0.0f, 0.0f), sideThin, 3.0f, center);
                    int[] sideZeroEnergy = renderProbeEnergies(window, device, environment,
                            fallbacks, shader, mesh, frontCamera,
                            new Vector3f(1.0f, 0.0f, 0.0f), sideZero, 3.0f, center);
                    assertEquals(sideZeroEnergy[0], sideThinEnergy[0], 2,
                            "a side-lit face has back <= 0; the documented rejection must "
                                    + "return exactly zero transmission");
                    assertEquals(0, sideThinEnergy[0], 2,
                            "the side-lit baseline must be black for the rejection to be "
                                    + "observable");
                } finally {
                    zero.close();
                    thin.close();
                    sideThin.close();
                    sideZero.close();
                }
                GlDebug.checkError("thin translucency mask/back-face boundaries");
            }
        }
    }

    @Test
    void thicknessChangesAbsorptionAndLobeInTheAssertedDirection() {
        try (GlfwWindow window = thinWindow()) {
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 Mesh mesh = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("thin-translucency-thickness")).mesh())) {
                environment.intensity(0.0f);
                Material thinZero = PbrMaterials.createWithBindings(shader,
                        thinProperties(0.0f), Map.of(), fallbacks, CullMode.NONE, true,
                        false);
                Material thinOne = PbrMaterials.createWithBindings(shader,
                        thinProperties(1.0f), Map.of(), fallbacks, CullMode.NONE, true,
                        false);
                try {
                    Camera onAxis = new Camera(new Vector3f(0.0f, 0.0f, 3.0f));
                    // front = normalize(-eye) = (-0.496, 0, -0.868) for this eye.
                    Camera oblique = new Camera(new Vector3f(1.6f, 0.0f, 2.8f),
                            new Vector3f(0.0f, 1.0f, 0.0f), -119.7f, 0.0f);
                    Vector3f backLight = new Vector3f(0.0f, 0.0f, 1.0f);
                    float[] onZero = renderLinearHdr(window, device, environment, fallbacks,
                            shader, mesh, onAxis, backLight, thinZero, 3.0f);
                    float[] onOne = renderLinearHdr(window, device, environment, fallbacks,
                            shader, mesh, onAxis, backLight, thinOne, 3.0f);
                    float[] obliqueZero = renderLinearHdr(window, device, environment,
                            fallbacks, shader, mesh, oblique, backLight, thinZero, 3.0f);
                    float[] obliqueOne = renderLinearHdr(window, device, environment, fallbacks,
                            shader, mesh, oblique, backLight, thinOne, 3.0f);
                    double onRatio = centerLuminance(onOne) / centerLuminance(onZero);
                    double obliqueRatio = centerLuminance(obliqueOne)
                            / centerLuminance(obliqueZero);
                    assertTrue(onRatio > 0.0 && onRatio < 0.5,
                            "thickness 1 must absorb most of the on-axis transmission: "
                                    + onRatio);
                    assertTrue(obliqueRatio < onRatio * 0.9,
                            "the tightened forward lobe must attenuate the oblique view more "
                                    + "than absorption alone: " + obliqueRatio + " vs "
                                    + onRatio);
                } finally {
                    thinZero.close();
                    thinOne.close();
                }
                GlDebug.checkError("thin translucency thickness boundary");
            }
        }
    }

    @Test
    void cascadeShadowAttenuatesTheThinReceiverExactlyOnce() {
        try (GlfwWindow window = thinWindow()) {
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 Mesh mesh = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("thin-translucency-shadow")).mesh())) {
                environment.intensity(0.0f);
                Material thin = PbrMaterials.createWithBindings(shader, thinProperties(0.0f),
                        Map.of(), fallbacks, CullMode.NONE, true, false);
                Material zero = PbrMaterials.createWithBindings(shader,
                        PbrMaterialProperties.defaults(), Map.of(), fallbacks,
                        CullMode.NONE, true, false);
                Material caster = PbrMaterials.create(shader, PbrMaterialProperties.defaults(),
                        Map.of(), fallbacks);
                try {
                    // Two symmetric shadow setups measure the same visibility
                    // profile: a front light (travel -Z) with the caster at
                    // +Z gives the direct-light ratio, and a back light (travel
                    // +Z) with the caster at -Z gives the thin ratio. The
                    // receiver is unrotated in both, so the thin term is
                    // exercised in the same geometry.
                    float[] zeroLit = renderShadowHdr(window, device, environment, fallbacks,
                            shader, mesh, zero, caster, false, false);
                    float[] zeroShadow = renderShadowHdr(window, device, environment, fallbacks,
                            shader, mesh, zero, caster, false, true);
                    float[] thinLit = renderShadowHdr(window, device, environment, fallbacks,
                            shader, mesh, thin, caster, true, false);
                    float[] thinShadow = renderShadowHdr(window, device, environment, fallbacks,
                            shader, mesh, thin, caster, true, true);
                    int penumbra = findPenumbra(zeroLit, zeroShadow);
                    assertTrue(penumbra >= 0,
                            "the shadow fixture must contain a partial-visibility pixel");
                    double visibility = luminance(zeroShadow, penumbra)
                            / luminance(zeroLit, penumbra);
                    double thinRatio = luminance(thinShadow, penumbra)
                            / luminance(thinLit, penumbra);
                    assertTrue(visibility > 0.15 && visibility < 0.9,
                            "the selected pixel must carry a fractional cascade visibility: "
                                    + visibility);
                    assertTrue(luminance(thinShadow, penumbra)
                                    < luminance(thinLit, penumbra),
                            "the cascade shadow must not disappear from the thin receiver");
                    assertEquals(visibility, thinRatio, 0.1,
                            "the thin receiver must be attenuated by the same single visibility "
                                    + "factor as the direct-light receiver, not twice or not at all");
                } finally {
                    thin.close();
                    zero.close();
                    caster.close();
                }
                GlDebug.checkError("thin translucency shadow consistency");
            }
        }
    }

    private static GlfwWindow thinWindow() {
        GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(WIDTH, HEIGHT)
                .title("GL Smoke Test")
                .visible(false)
                .build();
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        return window;
    }

    private static int[] renderProbeEnergies(GlfwWindow window, GlRenderDevice device,
                                             PbrEnvironment environment,
                                             PbrFallbackTextures fallbacks,
                                             ShaderProgram shader, Mesh mesh, Camera camera,
                                             Vector3f lightDirection, Material material,
                                             float scale, List<Vector3f> probes) {
        Scene scene = new Scene(camera)
                .add(SceneObject.fixed(mesh, material,
                        new Matrix4f().scale(scale, scale, 1.0f), false))
                .addLight(SceneLight.directional(lightDirection, new Vector3f(1.0f),
                        LIGHT_INTENSITY));
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings(),
                environment);
        try {
            pipeline.build();
            pipeline.execute(device);
            int[] result = new int[probes.size()];
            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            for (int index = 0; index < probes.size(); index++) {
                int[] point = project(camera, probes.get(index));
                pixel.clear();
                glReadPixels(point[0], point[1], 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                result[index] = Byte.toUnsignedInt(pixel.get(0))
                        + Byte.toUnsignedInt(pixel.get(1))
                        + Byte.toUnsignedInt(pixel.get(2));
            }
            return result;
        } finally {
            pipeline.close();
        }
    }

    private static float[] renderLinearHdr(GlfwWindow window, GlRenderDevice device,
                                           PbrEnvironment environment,
                                           PbrFallbackTextures fallbacks,
                                           ShaderProgram shader, Mesh mesh, Camera camera,
                                           Vector3f lightDirection, Material material,
                                           float scale) {
        Scene scene = new Scene(camera)
                .add(SceneObject.fixed(mesh, material,
                        new Matrix4f().scale(scale, scale, 1.0f), false))
                .addLight(SceneLight.directional(lightDirection, new Vector3f(1.0f),
                        LIGHT_INTENSITY));
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings(),
                environment);
        try {
            pipeline.build();
            pipeline.execute(device);
            return pipeline.captureLinearHdrRgbaFloat();
        } finally {
            pipeline.close();
        }
    }

    private static float[] renderShadowHdr(GlfwWindow window, GlRenderDevice device,
                                           PbrEnvironment environment,
                                           PbrFallbackTextures fallbacks,
                                           ShaderProgram shader, Mesh mesh, Material receiver,
                                           Material caster, boolean lightFromBehind,
                                           boolean casterPresent) {
        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 3.0f));
        Vector3f lightDirection = lightFromBehind
                ? new Vector3f(0.2f, -0.3f, 0.95f)
                : new Vector3f(-0.2f, 0.3f, -0.95f);
        Vector3f towardLight = new Vector3f(lightDirection).normalize().negate();
        Scene scene = new Scene(camera)
                .add(SceneObject.fixed(mesh, receiver,
                        new Matrix4f().scale(3.0f, 3.0f, 1.0f), false))
                .addLight(SceneLight.shadowedDirectional(lightDirection,
                        new Vector3f(1.0f), LIGHT_INTENSITY));
        if (casterPresent) {
            Vector3f casterCenter = new Vector3f(towardLight).mul(0.8f);
            scene.add(SceneObject.fixed(mesh, caster,
                    new Matrix4f().translate(casterCenter).scale(1.6f, 1.6f, 1.0f),
                    true));
        }
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings(),
                environment);
        try {
            pipeline.directionalCascades(DirectionalCascadeSettings.disabled());
            pipeline.features().submit(pipeline.features().effective().toBuilder()
                    .directionalShadowMap(new DirectionalShadowMap(
                            new ShadowSettings(32, 4.0f, 0.1f, 20.0f, 0.01f)))
                    .build());
            pipeline.build();
            pipeline.execute(device);
            return pipeline.captureLinearHdrRgbaFloat();
        } finally {
            pipeline.close();
        }
    }

    private static RenderSettings renderSettings() {
        return RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES)
                .bloomSettings(BloomSettings.disabled())
                .vsync(false)
                .build();
    }

    private static int[] project(Camera camera, Vector3f world) {
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Matrix4f projection = CameraProjection.stable(camera, WIDTH, HEIGHT, new Matrix4f());
        Vector4f clip = new Matrix4f(projection).mul(view)
                .transform(new Vector4f(world, 1.0f));
        float inverseW = 1.0f / clip.w;
        int x = Math.round((clip.x * inverseW * 0.5f + 0.5f) * WIDTH);
        int y = Math.round((clip.y * inverseW * 0.5f + 0.5f) * HEIGHT);
        return new int[]{Math.max(0, Math.min(WIDTH - 1, x)),
                Math.max(0, Math.min(HEIGHT - 1, y))};
    }

    private static float centerLuminance(float[] rgba) {
        return luminance(rgba, (HEIGHT / 2) * WIDTH + WIDTH / 2);
    }

    private static float luminance(float[] rgba, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * rgba[offset] + 0.7152f * rgba[offset + 1]
                + 0.0722f * rgba[offset + 2];
    }

    private static int findPenumbra(float[] lit, float[] shadow) {
        int best = -1;
        double bestDistance = 0.5;
        for (int pixel = 0; pixel < lit.length / 4; pixel++) {
            float litLuminance = luminance(lit, pixel);
            if (litLuminance <= 1.0e-5f) {
                continue;
            }
            double ratio = luminance(shadow, pixel) / litLuminance;
            double distance = Math.abs(ratio - 0.5);
            if (ratio > 0.15 && ratio < 0.9 && distance < bestDistance) {
                bestDistance = distance;
                best = pixel;
            }
        }
        return best;
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
            glReadPixels(window.width() / 2, window.height() / 2, 1, 1,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            return Byte.toUnsignedInt(pixel.get(0))
                    + Byte.toUnsignedInt(pixel.get(1))
                    + Byte.toUnsignedInt(pixel.get(2));
        } finally {
            pipeline.close();
            material.close();
        }
    }

    private static PbrMaterialProperties thinProperties() {
        return thinProperties(0.0f);
    }

    private static PbrMaterialProperties thinProperties(float thickness) {
        return new PbrMaterialProperties(new Vector4f(1.0f), 0.0f, 1.0f, 1.0f, 1.0f,
                new Vector3f(), Map.of(), thickness, 0.9f, 0.9f);
    }
}
