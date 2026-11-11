package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.AnisotropicSettings;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
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
import com.kaleblangley.haikalat.subsystems.render3d.pbr.AnisotropicReferenceMath;
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
import org.lwjgl.opengl.GL;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-consumer acceptance for the bounded anisotropic PBR highlight.
 *
 * <p>The valid-tangent fixture is one camera-facing textured quad with the
 * shared tangent generator and an axis-aligned transform. For that mesh the
 * UV-derived frame is tangent {@code +X}, bitangent {@code +Y} and handedness
 * {@code +1}, so the CPU oracle can build the same tangent frame without
 * reading production state. The base normal scale is zero, so the shaded
 * normal stays geometric.</p>
 *
 * <p>Every variant renders through the same pipeline, camera, geometry and
 * material template; only {@link MaterialInstance} uniforms switch the
 * isotropic baseline, zero strength, two anisotropy rotations and the
 * bounded-IBL variants. Direct lighting is compared per pixel with the
 * independent double-precision Burley oracle in
 * {@link AnisotropicReferenceMath}: the anisotropic GGX distribution and
 * Smith visibility are written in the test and never call production
 * helpers. Zero strength and the re-disabled default are compared with the
 * original frame, so the isotropic fallback is verified byte for byte.</p>
 *
 * <p>The final fixture uses a canonical-layout mesh whose TANGENT is zero and
 * disables the normal map, which makes {@code vTangent} non-finite while the
 * pipeline geometry contract stays satisfied. (A fully missing TANGENT is
 * rejected by {@code RenderPipeline.build()} for PBR model materials, as
 * covered by {@code PbrEnvironmentGlTest}.) The shader must take its
 * deterministic normal-derived fallback frame (also verified against the CPU
 * oracle), stay finite, and remain bounded; a NaN rotation override must fail
 * closed to the isotropic path. The environment term is only checked for
 * boundedness, visible change and rotation sensitivity: the documented IBL
 * lookup is an approximation, not an exact anisotropic prefilter.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class AnisotropicGlTest {
    private static final int WIDTH = 200;
    private static final int HEIGHT = 140;
    private static final float CAMERA_Z = 3.4f;
    private static final float QUAD_HALF = 1.0f;
    private static final Vector3f CAMERA_POSITION = new Vector3f(0.0f, 0.0f, CAMERA_Z);
    /**
     * Chosen so the isotropic specular mirror point ({@code ~(-0.68, -0.34)} on
     * the z = 0 plane) stays inside the quad. With the earlier
     * {@code (-0.35, -0.55, -1)} direction the mirror point was outside the
     * quad, the positive specular difference for rotation zero was empty, and
     * the rotation-orientation moment check had no participating pixels.
     */
    private static final Vector3f LIGHT_TRAVEL_DIRECTION =
            new Vector3f(-0.2f, -0.1f, -1.0f);
    private static final Vector3f LIGHT_COLOR = new Vector3f(1.0f, 0.97f, 0.92f);
    private static final float LIGHT_INTENSITY = 2.5f;
    private static final Vector4f BASE_COLOR = new Vector4f(0.55f, 0.50f, 0.45f, 1.0f);
    /** Black base colour leaves only the F0 = 0.04 specular IBL lobe. */
    private static final Vector4f BLACK_BASE_COLOR = new Vector4f(0.0f, 0.0f, 0.0f, 1.0f);
    private static final float BASE_METALLIC = 0.0f;
    private static final float BASE_ROUGHNESS = 0.45f;
    /** Keeps the shaded normal geometric; the fixture only varies anisotropy. */
    private static final float BASE_NORMAL_SCALE = 0.0f;
    private static final float STRENGTH = 0.8f;
    private static final float TOLERANCE_ABSOLUTE = 0.0025f;
    private static final float TOLERANCE_RELATIVE = 0.0025f;
    private static final float MAXIMUM_BOUNDED_LUMINANCE = 1000.0f;
    private static final float[] REFERENCE_NORMAL = {0.0f, 0.0f, 1.0f};
    private static final float[] REFERENCE_TANGENT = {1.0f, 0.0f, 0.0f};

    @Test
    void anisotropicDirectMatchesTheOracleAndRotationMovesTheHighlight() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(WIDTH, HEIGHT)
                .title("Anisotropic GL Test")
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
                         BuiltinMeshData.texturedQuad("anisotropic-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                PbrMaterialProperties properties = new PbrMaterialProperties(BASE_COLOR,
                        BASE_METALLIC, BASE_ROUGHNESS, BASE_NORMAL_SCALE, 1.0f,
                        new Vector3f(), Map.of());
                Material material = PbrMaterials.createWithAnisotropy(shader, properties,
                        Map.of(), fallbacks, AnisotropicSettings.disabled());
                try {
                    MaterialInstance instance = material.createInstance();
                    Camera camera = new Camera(CAMERA_POSITION,
                            new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
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
                        pipeline.features().submit(directSettings(
                                pipeline.features().effective()));
                        pipeline.build();
                        Matrix4f inverse = inverseViewProjection(camera);

                        float[] disabled = render(pipeline, device);
                        assertDirectReference("disabled default", disabled, inverse,
                                0.0f, 0.0f, false);

                        float[] zeroStrength = anisotropicFrame(pipeline, device, instance,
                                0.0f, 0.0f);
                        assertArrayEquals(disabled, zeroStrength, 1.0e-6f,
                                "enabled anisotropy with zero strength must keep the exact "
                                        + "isotropic image");

                        float[] stretched = anisotropicFrame(pipeline, device, instance,
                                STRENGTH, 0.0f);
                        assertDirectReference("anisotropy on the tangent axis", stretched,
                                inverse, STRENGTH, 0.0f, true);
                        assertDiffers("anisotropy vs isotropic", stretched, disabled,
                                0.01f, 64);

                        float[] quarterTurn = anisotropicFrame(pipeline, device, instance,
                                STRENGTH, (float) (Math.PI / 2.0));
                        assertDirectReference("anisotropy rotated a quarter turn",
                                quarterTurn, inverse, STRENGTH,
                                (float) (Math.PI / 2.0), true);
                        assertDiffers("anisotropy rotation", quarterTurn, stretched,
                                0.01f, 64);
                        assertHighlightAlignment(stretched, quarterTurn, disabled);

                        // The disabled default must be restored exactly after overrides.
                        instance.clearOverrides();
                        assertArrayEquals(disabled, render(pipeline, device), 0.0f,
                                "clearing the anisotropy overrides must restore the previous image");

                        assertIblIsBoundedAndRotationSensitive(pipeline, device, instance,
                                inverse);
                        GlDebug.checkError("anisotropic acceptance");
                    } finally {
                        pipeline.close();
                    }
                    assertDegenerateTangentFallsBackBoundedly(window, device, environment,
                            camera, instance);
                } finally {
                    material.close();
                }
            }
        }
    }

    /** Renders one direct-lit variant through MaterialInstance overrides. */
    private static float[] anisotropicFrame(RenderPipeline pipeline, GlRenderDevice device,
                                            MaterialInstance instance, float strength,
                                            float rotation) {
        instance.clearOverrides();
        instance.setInt("uAnisotropicEnabled", 1)
                .setFloat("uAnisotropicStrength", strength)
                .setFloat("uAnisotropicRotation", rotation);
        return render(pipeline, device);
    }

    /**
     * Environment-only phase with a black base colour, so the remaining term is
     * the F0 = 0.04 prefiltered specular IBL. The implementation is a
     * documented bounded approximation; it is only required to be finite,
     * visibly different from the isotropic lookup, rotation-sensitive and
     * bounded. Zero strength must still reproduce the isotropic frame.
     */
    private static void assertIblIsBoundedAndRotationSensitive(RenderPipeline pipeline,
                                                               GlRenderDevice device,
                                                               MaterialInstance instance,
                                                               Matrix4f inverse) {
        pipeline.features().submit(iblSettings(pipeline.features().effective()));
        instance.clearOverrides();
        instance.setVec4("uBaseColorFactor", BLACK_BASE_COLOR);
        float[] isotropic = render(pipeline, device);
        assertFiniteBounded("isotropic IBL", isotropic);
        assertTrue(maxQuadLuminance(isotropic, inverse) > 1.0e-3f,
                "the black-base specular IBL fixture must contain real environment radiance");

        instance.setInt("uAnisotropicEnabled", 1)
                .setFloat("uAnisotropicStrength", 0.0f)
                .setFloat("uAnisotropicRotation", 0.0f);
        assertArrayEquals(isotropic, render(pipeline, device), 1.0e-6f,
                "zero strength must preserve the isotropic IBL sample sequence");

        instance.clearOverrides();
        instance.setVec4("uBaseColorFactor", BLACK_BASE_COLOR)
                .setInt("uAnisotropicEnabled", 1)
                .setFloat("uAnisotropicStrength", STRENGTH)
                .setFloat("uAnisotropicRotation", 0.0f);
        float[] stretched = render(pipeline, device);
        assertFiniteBounded("anisotropic IBL", stretched);
        assertDiffers("anisotropic IBL lookup", stretched, isotropic, 0.001f, 16);

        instance.setFloat("uAnisotropicRotation", (float) (Math.PI / 2.0));
        float[] quarterTurn = render(pipeline, device);
        assertFiniteBounded("rotated anisotropic IBL", quarterTurn);
        assertDiffers("anisotropic IBL rotation", quarterTurn, stretched, 0.001f, 16);
        instance.clearOverrides();
    }

    /**
     * The zero-TANGENT fixture makes {@code vTangent} non-finite; with the base
     * normal map disabled the shader must take its deterministic fallback
     * frame. That frame is matched per pixel against the oracle, and a NaN
     * rotation override must fail closed to the isotropic path without NaN in
     * the captured image.
     */
    private static void assertDegenerateTangentFallsBackBoundedly(
            GlfwWindow window, GlRenderDevice device, PbrEnvironment environment,
            Camera camera, MaterialInstance instance) {
        try (Mesh degenerate = Mesh.from(degenerateTangentQuad())) {
            Scene scene = new Scene(camera);
            scene.add(MeshRenderer.of(degenerate, instance,
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
                pipeline.features().submit(directSettings(pipeline.features().effective()));
                pipeline.build();
                Matrix4f inverse = inverseViewProjection(camera);

                instance.clearOverrides();
                instance.setInt("uEnableNormalMap", 0)
                        .setInt("uAnisotropicEnabled", 0)
                        .setFloat("uAnisotropicStrength", STRENGTH)
                        .setFloat("uAnisotropicRotation", 0.0f);
                float[] isotropic = render(pipeline, device);
                assertFiniteBounded("degenerate-tangent isotropic", isotropic);

                instance.setInt("uAnisotropicEnabled", 1);
                float[] fallback = render(pipeline, device);
                assertFiniteBounded("degenerate-tangent fallback frame", fallback);
                assertDiffers("degenerate tangent fallback", fallback, isotropic,
                        0.01f, 32);
                assertFallbackDirectReference("degenerate-tangent fallback frame", fallback,
                        inverse);

                // A non-finite rotation is rejected by AnisotropicSettings, but a
                // raw MaterialInstance override can still inject it; the shader
                // must fail closed instead of producing NaN.
                instance.setFloat("uAnisotropicRotation", Float.NaN);
                float[] nonFiniteRotation = render(pipeline, device);
                assertFiniteBounded("non-finite rotation fallback", nonFiniteRotation);
                assertArrayEquals(isotropic, nonFiniteRotation, 1.0e-6f,
                        "a non-finite rotation must degrade to the isotropic path");
                instance.clearOverrides();
                GlDebug.checkError("anisotropic degenerate-tangent acceptance");
            } finally {
                pipeline.close();
            }
        }
    }

    private static RenderFeatureSettings directSettings(RenderFeatureSettings base) {
        return base.toBuilder()
                .imageBasedLighting(false)
                .directLighting(true)
                .directionalShadows(false)
                .pointShadows(false)
                .spotShadows(false)
                .directionalCascades(DirectionalCascadeSettings.disabled())
                .localShadows(LocalShadowPipelineSettings.legacyDefaults())
                .build();
    }

    private static RenderFeatureSettings iblSettings(RenderFeatureSettings base) {
        return base.toBuilder()
                .imageBasedLighting(true)
                .directLighting(false)
                .directionalShadows(false)
                .pointShadows(false)
                .spotShadows(false)
                .directionalCascades(DirectionalCascadeSettings.disabled())
                .localShadows(LocalShadowPipelineSettings.legacyDefaults())
                .build();
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
     * Per-pixel direct-light comparison with the independent double precision
     * oracle. Only pixels whose camera ray crosses the known quad plane are
     * compared.
     */
    private static void assertDirectReference(String label, float[] image, Matrix4f inverse,
                                               float strength, float rotation,
                                               boolean anisotropic) {
        double[] normal = toDouble(REFERENCE_NORMAL);
        double[] tangent = toDouble(REFERENCE_TANGENT);
        double[] light = toDouble(new Vector3f(LIGHT_TRAVEL_DIRECTION).normalize().negate());
        double[] radiance = toDouble(new Vector3f(LIGHT_COLOR).mul(LIGHT_INTENSITY));
        double[] baseColor = {BASE_COLOR.x, BASE_COLOR.y, BASE_COLOR.z};
        double[] frame = AnisotropicReferenceMath.frame(normal, tangent, 1.0, rotation);
        int compared = 0;
        int failures = 0;
        float worst = 0.0f;
        String worstPixel = "";
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                Vector3f world = quadWorldPosition(inverse, x, y);
                if (world == null) continue;
                double[] view = toDouble(new Vector3f(CAMERA_POSITION).sub(world).normalize());
                double[] expected;
                if (anisotropic) {
                    expected = AnisotropicReferenceMath.anisotropicDirectBrdf(normal, view,
                            light, radiance, baseColor, BASE_METALLIC, BASE_ROUGHNESS,
                            strength, slice(frame, 0), slice(frame, 3));
                } else {
                    expected = isotropicReference(normal, view, light, radiance, baseColor);
                }
                int offset = (y * WIDTH + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    float actual = image[offset + channel];
                    float reference = (float) expected[channel];
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
                + "anisotropic oracle, worst " + worst + " at " + worstPixel);
    }

    /** Same comparison for the zero-tangent mesh (normal-derived fallback frame). */
    private static void assertFallbackDirectReference(String label, float[] image,
                                                       Matrix4f inverse) {
        double[] normal = toDouble(REFERENCE_NORMAL);
        double[] light = toDouble(new Vector3f(LIGHT_TRAVEL_DIRECTION).normalize().negate());
        double[] radiance = toDouble(new Vector3f(LIGHT_COLOR).mul(LIGHT_INTENSITY));
        double[] baseColor = {BASE_COLOR.x, BASE_COLOR.y, BASE_COLOR.z};
        double[] frame = AnisotropicReferenceMath.frame(normal, null, 1.0, 0.0);
        int compared = 0;
        int failures = 0;
        float worst = 0.0f;
        String worstPixel = "";
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                Vector3f world = quadWorldPosition(inverse, x, y);
                if (world == null) continue;
                double[] view = toDouble(new Vector3f(CAMERA_POSITION).sub(world).normalize());
                double[] expected = AnisotropicReferenceMath.anisotropicDirectBrdf(normal,
                        view, light, radiance, baseColor, BASE_METALLIC, BASE_ROUGHNESS,
                        STRENGTH, slice(frame, 0), slice(frame, 3));
                int offset = (y * WIDTH + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    float actual = image[offset + channel];
                    float reference = (float) expected[channel];
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
        assertTrue(compared > WIDTH * HEIGHT / 5,
                label + ": the fallback fixture must cover a usable pixel region");
        assertEquals(0, failures, label + ": the normal-derived fallback frame differs from "
                + "the oracle, worst " + worst + " at " + worstPixel);
    }

    /**
     * Isotropic direct reference matching the shader's original pre-anisotropy
     * path (GGX + Schlick-GGX Smith + Schlick Fresnel).
     */
    private static double[] isotropicReference(double[] normal, double[] view,
                                               double[] light, double[] radiance,
                                               double[] baseColor) {
        double[] n = normalize(normal);
        double[] v = normalize(view);
        double[] l = normalize(light);
        double nDotL = Math.max(dot(n, l), 0.0);
        if (nDotL <= 0.0) return new double[3];
        double[] half = normalize(add(v, l));
        double hDotV = Math.max(dot(half, v), 0.0);
        double roughness = BASE_ROUGHNESS;
        double a = roughness * roughness;
        double a2 = a * a;
        double nDotH = Math.max(dot(n, half), 0.0);
        double denominator = nDotH * nDotH * (a2 - 1.0) + 1.0;
        double distribution = a2 / Math.max(Math.PI * denominator * denominator, 1.0e-6);
        double k = (roughness + 1.0) * (roughness + 1.0) * 0.125;
        double nDotV = Math.max(dot(n, v), 0.0);
        double gv = nDotV / Math.max(nDotV * (1.0 - k) + k, 1.0e-6);
        double gl = nDotL / Math.max(nDotL * (1.0 - k) + k, 1.0e-6);
        double specularDenominator = Math.max(4.0 * nDotV * nDotL, 1.0e-5);
        double[] result = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            double f0 = AnisotropicReferenceMath.DIELECTRIC_F0
                    + (baseColor[channel] - AnisotropicReferenceMath.DIELECTRIC_F0)
                    * BASE_METALLIC;
            double fresnel = AnisotropicReferenceMath.fresnelSchlick(hDotV, f0);
            double specular = distribution * gv * gl * fresnel / specularDenominator;
            double diffuse = (1.0 - fresnel) * (1.0 - BASE_METALLIC)
                    * baseColor[channel] / Math.PI;
            result[channel] = (diffuse + specular) * radiance[channel] * nDotL;
        }
        return result;
    }

    /**
     * The anisotropic difference image isolates the specular lobe from the
     * rotation-independent diffuse term. A tangent-axis stretch must widen the
     * highlight along X; a quarter turn must widen it along Y.
     */
    private static void assertHighlightAlignment(float[] stretched, float[] quarterTurn,
                                                float[] baseline) {
        double[] tangentAxis = specularMoments(stretched, baseline);
        double[] bitangentAxis = specularMoments(quarterTurn, baseline);
        assertTrue(tangentAxis[2] > 16.0,
                "the tangent-axis highlight must contain enough participating pixels: "
                        + (int) tangentAxis[2]);
        assertTrue(bitangentAxis[2] > 16.0,
                "the rotated highlight must contain enough participating pixels: "
                        + (int) bitangentAxis[2]);
        assertTrue(tangentAxis[0] > tangentAxis[1] * 1.15,
                "rotation zero must stretch the highlight along the tangent axis: mxx="
                        + tangentAxis[0] + " myy=" + tangentAxis[1]
                        + " pixels=" + (int) tangentAxis[2]);
        assertTrue(bitangentAxis[1] > bitangentAxis[0] * 1.15,
                "a quarter turn must stretch the highlight along the bitangent axis: mxx="
                        + bitangentAxis[0] + " myy=" + bitangentAxis[1]
                        + " pixels=" + (int) bitangentAxis[2]);
    }

    /**
     * Returns {@code {mxx, myy, participantCount}} of the positive specular
     * difference. Both moments are normalized by the luminance weight sum, so
     * the orientation check is independent of exposure and absolute energy; the
     * count reports how many pixels carry a positive difference above the
     * fixed 1e-4 luminance floor and is therefore also scale independent.
     */
    private static double[] specularMoments(float[] image, float[] baseline) {
        double weightSum = 0.0;
        double centroidX = 0.0;
        double centroidY = 0.0;
        int participantCount = 0;
        double[] weights = new double[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int offset = (y * WIDTH + x) * 4;
                double luminance = 0.2126 * (image[offset] - baseline[offset])
                        + 0.7152 * (image[offset + 1] - baseline[offset + 1])
                        + 0.0722 * (image[offset + 2] - baseline[offset + 2]);
                if (luminance <= 1.0e-4 || !Double.isFinite(luminance)) continue;
                weights[y * WIDTH + x] = luminance;
                weightSum += luminance;
                centroidX += luminance * x;
                centroidY += luminance * y;
                participantCount++;
            }
        }
        if (weightSum <= 0.0) return new double[]{0.0, 0.0, 0.0};
        centroidX /= weightSum;
        centroidY /= weightSum;
        double mxx = 0.0;
        double myy = 0.0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                double weight = weights[y * WIDTH + x];
                if (weight == 0.0) continue;
                double dx = x - centroidX;
                double dy = y - centroidY;
                mxx += weight * dx * dx;
                myy += weight * dy * dy;
            }
        }
        return new double[]{mxx / weightSum, myy / weightSum, participantCount};
    }

    private static void assertFiniteBounded(String label, float[] image) {
        float maximum = 0.0f;
        for (int pixel = 0; pixel < image.length / 4; pixel++) {
            float luminance = luminance(image, pixel);
            assertTrue(Float.isFinite(luminance),
                    label + ": pixel " + pixel + " produced a non-finite value");
            maximum = Math.max(maximum, luminance);
        }
        assertTrue(maximum < MAXIMUM_BOUNDED_LUMINANCE,
                label + ": luminance " + maximum + " exceeded the bounded fixture limit");
    }

    private static void assertDiffers(String label, float[] image, float[] other,
                                      float threshold, int minimumPixels) {
        int changed = 0;
        for (int pixel = 0; pixel < image.length / 4; pixel++) {
            int offset = pixel * 4;
            float delta = Math.abs(image[offset] - other[offset])
                    + Math.abs(image[offset + 1] - other[offset + 1])
                    + Math.abs(image[offset + 2] - other[offset + 2]);
            if (delta > threshold) changed++;
        }
        assertTrue(changed >= minimumPixels, label + ": only " + changed
                + " pixels changed; the anisotropic term must be visible");
    }

    private static float maxQuadLuminance(float[] image, Matrix4f inverse) {
        float maximum = 0.0f;
        int compared = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (quadWorldPosition(inverse, x, y) == null) continue;
                maximum = Math.max(maximum, luminance(image, y * WIDTH + x));
                compared++;
            }
        }
        assertTrue(compared > WIDTH * HEIGHT / 5,
                "the quad must cover a usable pixel region");
        return maximum;
    }

    private static float luminance(float[] image, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * image[offset] + 0.7152f * image[offset + 1]
                + 0.0722f * image[offset + 2];
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

    /**
     * Canonical PBR vertex layout with a zero TANGENT. The layout contract
     * passes {@code RenderPipeline.build()}, while {@code pbr-forward.vert}
     * normalizes the zero tangent to a non-finite {@code vTangent}; the
     * fragment shader's fallback checks are therefore exercised on the real
     * consumer path.
     */
    private static MeshData degenerateTangentQuad() {
        float[] vertices = {
                -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                0.5f, -0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                0.5f, 0.5f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                0.5f, 0.5f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                -0.5f, 0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f
        };
        return MeshData.of("anisotropic-degenerate-tangent", vertices,
                TangentGenerator.pbrLayout());
    }

    private static double[] toDouble(float[] values) {
        double[] result = new double[values.length];
        for (int index = 0; index < values.length; index++) result[index] = values[index];
        return result;
    }

    private static double[] toDouble(Vector3f value) {
        return new double[]{value.x, value.y, value.z};
    }

    private static double[] slice(double[] values, int offset) {
        return new double[]{values[offset], values[offset + 1], values[offset + 2]};
    }

    private static double[] normalize(double[] value) {
        double length = Math.sqrt(dot(value, value));
        return new double[]{value[0] / length, value[1] / length, value[2] / length};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
