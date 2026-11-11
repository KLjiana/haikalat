package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.ClearcoatSettings;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureStatus;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.Transform;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrTextureBinding;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Real-consumer acceptance for the bounded PBR clearcoat layer.
 *
 * <p>The fixture is one camera-facing PBR quad that shares the builtin textured
 * quad and an axis-aligned transform.  For that mesh the UV-derived frame is
 * exactly tangent {@code +X}, bitangent {@code +Y} and handedness {@code +1}
 * (the two triangles give {@code e1 = +X, e2 = +X + Y} in UV space), so the
 * fixture's tangent space equals world space and the CPU reference can decode
 * the coat normal without reading production state.  The base normal scale is
 * zero so the base macro normal stays geometric.</p>
 *
 * <p>All variants render through the same pipeline, camera, geometry and
 * material template; {@link MaterialInstance} uniform and texture overrides
 * switch between the clearcoat-off baseline, two coat roughness values, the
 * flat fallback coat normal, a constant tilted coat normal map at three
 * strengths and the IBL reference material.  Because the frame is always the
 * same single draw, pixels correspond exactly across frames.</p>
 *
 * <p>Direct lighting is validated against an independent CPU implementation of
 * the layered BRDF written in this test: base GGX/Smith/Schlick attenuated by
 * {@code 1 - F_coat}, plus the coat lobe with its own roughness and
 * {@code F0 = 0.04}.  IBL is validated without an analytic environment: the
 * expected image is composed from two separately rendered reference materials,
 * a black-base PBR material whose only remaining term is the dielectric
 * specular IBL lobe at the coat roughness, and the clearcoat-disabled base
 * image.  A correct implementation must satisfy
 * {@code composite = base * (1 - F(n.v)) + specularReference} in linear HDR.
 * The disabled default and the exact restore after re-disabling are compared
 * byte for byte.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ClearcoatGlTest {
    private static final int WIDTH = 200;
    private static final int HEIGHT = 140;
    private static final float CAMERA_Z = 3.4f;
    private static final float QUAD_HALF = 1.0f;
    private static final Vector3f CAMERA_POSITION = new Vector3f(0.0f, 0.0f, CAMERA_Z);
    private static final Vector3f LIGHT_TRAVEL_DIRECTION = new Vector3f(-0.35f, -0.55f, -1.0f);
    private static final Vector3f LIGHT_COLOR = new Vector3f(1.0f, 0.97f, 0.92f);
    private static final float LIGHT_INTENSITY = 2.5f;
    private static final Vector4f BASE_COLOR = new Vector4f(0.62f, 0.55f, 0.48f, 1.0f);
    private static final float BASE_METALLIC = 0.0f;
    private static final float BASE_ROUGHNESS = 0.55f;
    /** The fixture disables the base normal map so the base normal stays geometric. */
    private static final float BASE_NORMAL_SCALE = 0.0f;
    private static final float COAT_ROUGHNESS_SMOOTH = 0.18f;
    private static final float COAT_ROUGHNESS_ROUGH = 0.7f;
    private static final float COAT_STRENGTH_PARTIAL = 0.35f;
    /** Constant tangent-space clearcoat normal texel (200, 160, 230). */
    private static final int TILT_R = 200;
    private static final int TILT_G = 160;
    private static final int TILT_B = 230;
    private static final float PI = (float) Math.PI;
    private static final float TOLERANCE_ABSOLUTE = 0.002f;
    private static final float TOLERANCE_RELATIVE = 0.002f;

    @Test
    void clearcoatMatchesIndependentDirectAndIblReferencesAndRestoresExactly() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(WIDTH, HEIGHT)
                .title("Clearcoat GL Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("clearcoat-quad")).mesh());
                 Sampler coatSampler = Sampler.create(new Sampler.Descriptor(
                         GL_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE,
                         GL_CLAMP_TO_EDGE));
                 Texture2D tiltedNormal = tiltedCoatNormalTexture();
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                PbrMaterialProperties properties = new PbrMaterialProperties(BASE_COLOR,
                        BASE_METALLIC, BASE_ROUGHNESS, BASE_NORMAL_SCALE, 1.0f,
                        new Vector3f(), Map.of());
                Material material = PbrMaterials.createWithClearcoat(shader, properties,
                        Map.of(), fallbacks, ClearcoatSettings.disabled(), null);
                try {
                    MaterialInstance instance = material.createInstance();
                    PbrTextureBinding tiltedBinding = new PbrTextureBinding(tiltedNormal,
                            coatSampler);
                    Camera camera = new Camera(CAMERA_POSITION, new Vector3f(0.0f, 1.0f, 0.0f),
                            -90.0f, 0.0f);
                    Scene scene = new Scene(camera);
                    scene.add(MeshRenderer.of(quad, instance,
                            Transform.at(0.0f, 0.0f, 0.0f)
                                    .scale(QUAD_HALF * 2.0f, QUAD_HALF * 2.0f, 1.0f)));
                    scene.addLight(SceneLight.directional(LIGHT_TRAVEL_DIRECTION,
                            LIGHT_COLOR, LIGHT_INTENSITY));
                    RenderSettings renderSettings = RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build();
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            renderSettings, environment);
                    try {
                        RenderFeatureSettings direct = pipeline.features().effective().toBuilder()
                                .imageBasedLighting(false)
                                .directLighting(true)
                                .directionalShadows(false)
                                .pointShadows(false)
                                .spotShadows(false)
                                .directionalCascades(DirectionalCascadeSettings.disabled())
                                .localShadows(LocalShadowPipelineSettings.legacyDefaults())
                                .build();
                        pipeline.features().submit(direct);
                        pipeline.build();

                        Matrix4f inverse = inverseViewProjection(camera);
                        float[] disabled = render(pipeline, device);
                        assertDirectReference("disabled default", disabled, inverse,
                                false, COAT_ROUGHNESS_SMOOTH, 0.0f, false);

                        float[] smoothFlat = coatFrame(pipeline, device, instance, null,
                                COAT_ROUGHNESS_SMOOTH, 1.0f);
                        assertDirectReference("smooth coat, flat fallback normal", smoothFlat,
                                inverse, false, COAT_ROUGHNESS_SMOOTH, 1.0f, true);
                        assertDiffers("smooth coat vs disabled", smoothFlat, disabled, 0.02f);

                        float[] roughFlat = coatFrame(pipeline, device, instance, null,
                                COAT_ROUGHNESS_ROUGH, 1.0f);
                        assertDirectReference("rough coat, flat fallback normal", roughFlat,
                                inverse, false, COAT_ROUGHNESS_ROUGH, 1.0f, true);
                        assertDiffers("roughness variation", roughFlat, smoothFlat, 0.02f);

                        float[] tilted = coatFrame(pipeline, device, instance, tiltedBinding,
                                COAT_ROUGHNESS_SMOOTH, 1.0f);
                        assertDirectReference("tilted coat normal", tilted, inverse, true,
                                COAT_ROUGHNESS_SMOOTH, 1.0f, true);
                        assertDiffers("tilted coat normal vs flat", tilted, smoothFlat, 0.01f);

                        float[] partial = coatFrame(pipeline, device, instance, tiltedBinding,
                                COAT_ROUGHNESS_SMOOTH, COAT_STRENGTH_PARTIAL);
                        assertDirectReference("partial coat normal strength", partial, inverse,
                                true, COAT_ROUGHNESS_SMOOTH, COAT_STRENGTH_PARTIAL, true);
                        assertDiffers("coat normal strength", partial, tilted, 0.01f);

                        float[] zeroStrength = coatFrame(pipeline, device, instance, tiltedBinding,
                                COAT_ROUGHNESS_SMOOTH, 0.0f);
                        assertDirectReference("zero coat normal strength", zeroStrength, inverse,
                                true, COAT_ROUGHNESS_SMOOTH, 0.0f, true);

                        // Disabled default and exact restore: no coat term may survive.
                        instance.clearOverrides();
                        assertArrayEquals(disabled, render(pipeline, device), 0.0f,
                                "disabling the clearcoat must restore the exact previous image");

                        assertIblSplit(pipeline, device, instance, inverse);

                        GlDebug.checkError("clearcoat acceptance");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    material.close();
                    tiltedNormal.close();
                    coatSampler.close();
                }
            }
        }
    }

    /** Renders one direct-lit clearcoat variant through instance overrides. */
    private static float[] coatFrame(RenderPipeline pipeline, GlRenderDevice device,
                                     MaterialInstance instance, PbrTextureBinding tiltedNormal,
                                     float coatRoughness, float strength) {
        instance.clearOverrides();
        if (tiltedNormal != null) {
            instance.texture(PbrMaterials.CLEARCOAT_NORMAL_UNIT, "uClearcoatNormalMap",
                    tiltedNormal.texture(), tiltedNormal.sampler());
        }
        instance.setInt("uClearcoatEnabled", 1)
                .setFloat("uClearcoatRoughness", coatRoughness)
                .setFloat("uClearcoatNormalStrength", strength);
        return render(pipeline, device);
    }

    /**
     * IBL-only phase: no direct light, so the captured images contain the
     * environment terms only.  For each coat roughness the composite must equal
     * the attenuated base image plus the independently rendered dielectric
     * specular reference at that roughness.
     */
    private static void assertIblSplit(RenderPipeline pipeline, GlRenderDevice device,
                                       MaterialInstance instance, Matrix4f inverse) {
        RenderFeatureSettings ibl = pipeline.features().effective().toBuilder()
                .directLighting(false)
                .imageBasedLighting(true)
                .directionalShadows(false)
                .pointShadows(false)
                .spotShadows(false)
                .directionalCascades(DirectionalCascadeSettings.disabled())
                .localShadows(LocalShadowPipelineSettings.legacyDefaults())
                .build();
        pipeline.features().submit(ibl);

        instance.clearOverrides();
        float[] base = render(pipeline, device);
        assertTrue(maxLuminance(base) > 1.0e-3f,
                "the IBL base frame must contain real environment radiance");

        for (float coatRoughness : new float[]{COAT_ROUGHNESS_SMOOTH, COAT_ROUGHNESS_ROUGH}) {
            instance.clearOverrides();
            instance.setVec4("uBaseColorFactor", new Vector4f(0.0f, 0.0f, 0.0f, 1.0f))
                    .setFloat("uRoughnessFactor", coatRoughness);
            float[] reference = render(pipeline, device);
            assertTrue(maxLuminance(reference) > 1.0e-3f,
                    "the black-base specular reference must contain real environment radiance");
            assertTrue(difference(reference, base) > 1.0e-3f,
                    "the specular reference must not equal the clearcoat-disabled base frame");

            instance.clearOverrides();
            instance.setInt("uClearcoatEnabled", 1)
                    .setFloat("uClearcoatRoughness", coatRoughness)
                    .setFloat("uClearcoatNormalStrength", 0.0f);
            float[] composite = render(pipeline, device);
            assertDiffers("IBL coat over base at roughness " + coatRoughness, composite, base,
                    0.001f);

            assertIblComposite("IBL coat roughness " + coatRoughness, composite, base,
                    reference, inverse, coatRoughness);
        }
        instance.clearOverrides();
    }

    private static float maxLuminance(float[] image) {
        float maximum = 0.0f;
        for (int pixel = 0; pixel < image.length / 4; pixel++) {
            maximum = Math.max(maximum, luminance(image, pixel));
        }
        return maximum;
    }

    private static float difference(float[] image, float[] other) {
        float maximum = 0.0f;
        for (int index = 0; index < image.length; index++) {
            maximum = Math.max(maximum, Math.abs(image[index] - other[index]));
        }
        return maximum;
    }

    private static float luminance(float[] image, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * image[offset] + 0.7152f * image[offset + 1]
                + 0.0722f * image[offset + 2];
    }

    private static float[] render(RenderPipeline pipeline, GlRenderDevice device) {
        pipeline.execute(device);
        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                "the submitted feature request must publish before readback");
        float[] image = pipeline.captureLinearHdrRgbaFloat();
        assertEquals(WIDTH * HEIGHT * 4, image.length,
                "the fixture expects a " + WIDTH + "x" + HEIGHT + " framebuffer");
        return image;
    }

    /**
     * Compares one direct-lit frame with the independent CPU layered BRDF over
     * every pixel that shows the known quad plane.
     */
    private static void assertDirectReference(String label, float[] image, Matrix4f inverse,
                                               boolean tiltedNormalMap, float coatRoughness,
                                               float strength, boolean coatEnabled) {
        int compared = 0;
        int failures = 0;
        float worst = 0.0f;
        String worstPixel = "";
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                Vector3f world = quadWorldPosition(inverse, x, y);
                if (world == null) continue;
                Vector3f expected = directReference(world, tiltedNormalMap, strength,
                        coatRoughness, coatEnabled);
                int offset = (y * WIDTH + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    float actual = image[offset + channel];
                    float reference = channelValue(expected, channel);
                    float tolerance = TOLERANCE_ABSOLUTE
                            + TOLERANCE_RELATIVE * Math.abs(reference);
                    float delta = Math.abs(actual - reference);
                    if (delta > tolerance) failures++;
                    if (delta > worst) {
                        worst = delta;
                        worstPixel = "(" + x + "," + y + ") expected=" + reference
                                + " actual=" + actual;
                    }
                }
                compared++;
            }
        }
        if (failures > 0) {
            System.out.println("DIAG " + label + " failures=" + failures + " worst=" + worst
                    + " at " + worstPixel);
        }
        assertTrue(compared > WIDTH * HEIGHT / 5,
                label + ": the fixture must cover a large part of the frame, got " + compared);
        assertEquals(0, failures, label + ": one or more channels differ from the independent "
                + "reference, worst " + worst + " at " + worstPixel);
    }

    /**
     * Expected direct-light output of the layered fixture material, evaluated
     * with an independent CPU implementation of the shader's documented BRDF
     * model.
     */
    private static Vector3f directReference(Vector3f world, boolean tiltedNormalMap,
                                            float strength, float coatRoughness,
                                            boolean coatEnabled) {
        Vector3f n = new Vector3f(0.0f, 0.0f, 1.0f);
        Vector3f v = new Vector3f(CAMERA_POSITION).sub(world).normalize();
        Vector3f l = new Vector3f(LIGHT_TRAVEL_DIRECTION).normalize().negate();
        Vector3f radiance = new Vector3f(LIGHT_COLOR).mul(LIGHT_INTENSITY);
        Vector3f baseColor = new Vector3f(BASE_COLOR.x, BASE_COLOR.y, BASE_COLOR.z);
        Vector3f base = directBrdf(n, v, l, radiance, baseColor, BASE_METALLIC,
                BASE_ROUGHNESS, ClearcoatSettings.F0);
        if (!coatEnabled) return base;
        Vector3f coatNormal = coatNormal(tiltedNormalMap, strength);
        Vector3f h = new Vector3f(v).add(l).normalize();
        float coatFresnel = clearcoatFresnel(Math.max(h.dot(v), 0.0f));
        Vector3f coat = clearcoatDirect(coatNormal, v, l, coatRoughness).mul(radiance);
        return base.mul(1.0f - coatFresnel).add(coat);
    }

    /**
     * Decodes the constant clearcoat normal texel and rotates it from the
     * fixture's tangent space, which equals world space for the known quad
     * frame.  Without an instance texture override the flat 128,128,255 PBR
     * fallback is sampled.
     */
    private static Vector3f coatNormal(boolean tilted, float strength) {
        float x = decodeTexel(tilted ? TILT_R : 128);
        float y = decodeTexel(tilted ? TILT_G : 128);
        float z = decodeTexel(tilted ? TILT_B : 255);
        return new Vector3f(x * strength, y * strength, z).normalize();
    }

    private static float decodeTexel(int value) {
        return value / 255.0f * 2.0f - 1.0f;
    }

    private static Vector3f directBrdf(Vector3f n, Vector3f v, Vector3f l, Vector3f radiance,
                                       Vector3f baseColor, float metallic, float roughness,
                                       float f0) {
        float nDotL = Math.max(n.dot(l), 0.0f);
        if (nDotL <= 0.0f) return new Vector3f();
        Vector3f h = new Vector3f(v).add(l).normalize();
        float f = fresnelSchlick(Math.max(h.dot(v), 0.0f), f0);
        float d = distributionGgx(n.dot(h), roughness);
        float g = geometrySmith(n, v, l, roughness);
        float specular = d * g * f / Math.max(4.0f * Math.max(n.dot(v), 0.0f) * nDotL, 1.0e-5f);
        Vector3f diffuse = new Vector3f(baseColor).mul((1.0f - f) * (1.0f - metallic) / PI);
        return diffuse.add(new Vector3f(specular)).mul(radiance).mul(nDotL);
    }

    private static Vector3f clearcoatDirect(Vector3f cn, Vector3f v, Vector3f l,
                                            float roughness) {
        float nDotL = Math.max(cn.dot(l), 0.0f);
        if (nDotL <= 0.0f) return new Vector3f();
        Vector3f h = new Vector3f(v).add(l).normalize();
        float f = clearcoatFresnel(Math.max(h.dot(v), 0.0f));
        float d = distributionGgx(cn.dot(h), roughness);
        float g = geometrySmith(cn, v, l, roughness);
        float specular = d * g * f
                / Math.max(4.0f * Math.max(cn.dot(v), 0.0f) * nDotL, 1.0e-5f);
        return new Vector3f(specular * nDotL);
    }

    private static float fresnelSchlick(float cosTheta, float f0) {
        return f0 + (1.0f - f0) * (float) Math.pow(1.0f - clamp01(cosTheta), 5.0);
    }

    private static float clearcoatFresnel(float cosTheta) {
        return fresnelSchlick(cosTheta, ClearcoatSettings.F0);
    }

    /** Independent copy of the documented IBL coat Fresnel (roughness-aware Schlick). */
    private static float clearcoatFresnelRoughness(float cosTheta, float roughness) {
        float f0 = ClearcoatSettings.F0;
        return f0 + (Math.max(1.0f - roughness, f0) - f0)
                * (float) Math.pow(1.0f - clamp01(cosTheta), 5.0);
    }

    private static float distributionGgx(float nDotH, float roughness) {
        float safeRoughness = Math.max(ClearcoatSettings.MINIMUM_ROUGHNESS,
                clamp01(roughness));
        float a = safeRoughness * safeRoughness;
        float a2 = a * a;
        float nh = clamp01(nDotH);
        float denominator = nh * nh * (a2 - 1.0f) + 1.0f;
        return a2 / Math.max(PI * denominator * denominator, 1.0e-6f);
    }

    private static float geometrySmith(Vector3f n, Vector3f v, Vector3f l, float roughness) {
        return geometrySchlickGgx(Math.max(n.dot(v), 0.0f), roughness)
                * geometrySchlickGgx(Math.max(n.dot(l), 0.0f), roughness);
    }

    private static float geometrySchlickGgx(float nDotV, float roughness) {
        float r = Math.max(ClearcoatSettings.MINIMUM_ROUGHNESS, clamp01(roughness)) + 1.0f;
        float k = r * r * 0.125f;
        return nDotV / Math.max(nDotV * (1.0f - k) + k, 1.0e-6f);
    }

    /**
     * IBL consistency: the composite must equal the attenuated base plus the
     * independently rendered dielectric specular reference.  The base image is
     * the clearcoat-disabled frame, the reference image is a black-base
     * material whose remaining term is exactly the coat IBL lobe, and the
     * interface Fresnel comes from the analytic camera/normal geometry.
     */
    private static void assertIblComposite(String label, float[] composite, float[] base,
                                           float[] reference, Matrix4f inverse,
                                           float coatRoughness) {
        int compared = 0;
        int failures = 0;
        float worst = 0.0f;
        String worstPixel = "";
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                Vector3f world = quadWorldPosition(inverse, x, y);
                if (world == null) continue;
                float nDotV = CAMERA_Z / (float) Math.sqrt(
                        CAMERA_POSITION.distanceSquared(world));
                float coatFresnel = clearcoatFresnelRoughness(nDotV, coatRoughness);
                int offset = (y * WIDTH + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    float expected = base[offset + channel] * (1.0f - coatFresnel)
                            + reference[offset + channel];
                    float actual = composite[offset + channel];
                    float tolerance = 0.0005f + 0.005f * Math.max(1.0f, Math.abs(expected));
                    float delta = Math.abs(actual - expected);
                    if (delta > tolerance) failures++;
                    if (delta > worst) {
                        worst = delta;
                        worstPixel = "(" + x + "," + y + ") expected=" + expected
                                + " actual=" + actual + " base=" + base[offset + channel]
                                + " reference=" + reference[offset + channel];
                    }
                }
                compared++;
            }
        }
        assertTrue(compared > WIDTH * HEIGHT / 5,
                label + ": the quad must cover a reusable pixel region, got " + compared);
        assertEquals(0, failures, label + ": the composite differs from base*(1-F) + "
                + "specular reference, worst " + worst + " at " + worstPixel);
    }

    private static void assertDiffers(String label, float[] enabled, float[] baseline,
                                      float threshold) {
        int changed = 0;
        for (int pixel = 0; pixel < enabled.length / 4; pixel++) {
            int offset = pixel * 4;
            float delta = Math.abs(enabled[offset] - baseline[offset])
                    + Math.abs(enabled[offset + 1] - baseline[offset + 1])
                    + Math.abs(enabled[offset + 2] - baseline[offset + 2]);
            if (delta > threshold) changed++;
        }
        assertTrue(changed > 64, label + ": only " + changed
                + " pixels changed; the coat term must be visible");
    }

    private static Matrix4f inverseViewProjection(Camera camera) {
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Matrix4f projection = CameraProjection.stable(camera, WIDTH, HEIGHT, new Matrix4f());
        return new Matrix4f(projection).mul(view).invert();
    }

    /**
     * World point where a pixel ray crosses the {@code z = 0} quad plane, or
     * {@code null} when it misses the known quad.
     */
    private static Vector3f quadWorldPosition(Matrix4f inverseViewProjection, int x, int y) {
        float ndcX = (x + 0.5f) / WIDTH * 2.0f - 1.0f;
        float ndcY = (y + 0.5f) / HEIGHT * 2.0f - 1.0f;
        Vector4f near = new Vector4f(ndcX, ndcY, -1.0f, 1.0f);
        Vector4f far = new Vector4f(ndcX, ndcY, 1.0f, 1.0f);
        inverseViewProjection.transform(near);
        inverseViewProjection.transform(far);
        if (Math.abs(near.w) < 1.0e-6f || Math.abs(far.w) < 1.0e-6f) return null;
        near.div(near.w);
        far.div(far.w);
        Vector3f origin = new Vector3f(near.x, near.y, near.z);
        Vector3f direction = new Vector3f(far.x - near.x, far.y - near.y, far.z - near.z);
        if (Math.abs(direction.z) < 1.0e-6f) return null;
        float t = -origin.z / direction.z;
        if (t <= 0.0f) return null;
        Vector3f world = new Vector3f(origin).fma(t, direction);
        if (Math.abs(world.x) > QUAD_HALF - 1.0e-4f
                || Math.abs(world.y) > QUAD_HALF - 1.0e-4f) return null;
        return world;
    }

    private static Texture2D tiltedCoatNormalTexture() {
        Texture2D texture = Texture2D.createEmpty(1, 1, GL_RGBA8);
        try {
            ByteBuffer data = ByteBuffer.allocateDirect(4)
                    .put((byte) TILT_R).put((byte) TILT_G).put((byte) TILT_B).put((byte) 255)
                    .flip();
            texture.uploadRegion(0, 0, 1, 1, data);
            return texture;
        } catch (RuntimeException failure) {
            texture.close();
            throw failure;
        }
    }

    private static float channelValue(Vector3f value, int channel) {
        return switch (channel) {
            case 0 -> value.x;
            case 1 -> value.y;
            default -> value.z;
        };
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
