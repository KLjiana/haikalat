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
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-GL acceptance for the bounded transmission-aware directional shadow
 * lookup that feeds only the thin backlight term.
 *
 * <p>Fixture geometry. A thin double-sided panel is modelled as two parallel
 * quads separated by {@link #PANEL_GAP_METRES} of world space; both faces are
 * shadow casters. The light sits behind the panel, so the camera sees the
 * backlit side and the light-facing face writes the nearer shadow-map depth.
 * With the pre-fix exact lookup the front face is therefore self-occluded and
 * the thin term disappears; the thickness tolerance must recover it. The
 * fixtures keep the shadow bias at zero and the light perpendicular to the
 * panel so the comparison isolates the depth slab and no slope correction
 * masks it.</p>
 *
 * <p>Three independent controls: the zero-thickness run is the pre-fix
 * baseline; a far opaque caster proves the tolerance cannot leak through
 * distant geometry; and a front-lit run proves the direct term and the
 * zero-control frame are unchanged by the new path.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ThinTranslucencyTransmissionShadowGlTest {
    private static final float LIGHT_INTENSITY = 3.0f;
    private static final int WIDTH = 200;
    private static final int HEIGHT = 140;
    /** Physical gap of the modelled thin panel; below half the 5 cm bound. */
    private static final float PANEL_GAP_METRES = 0.02f;
    /** Light-side distance of the opaque caster used by the leak control. */
    private static final float DISTANT_CASTER_METRES = 0.5f;

    @Test
    void selfShadowedBacklitPanelTransmitsButOpaqueReceiversStayOccluded() {
        try (GlfwWindow window = thinWindow()) {
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = loadEnvironment(device);
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = forwardShader();
                 Mesh mesh = thinMesh("thin-transmission-self-shadow")) {
                environment.intensity(0.0f);
                Material thin = thinMaterial(shader, fallbacks, thinProperties(1.0f));
                Material zeroThickness = thinMaterial(shader, fallbacks, thinProperties(0.0f));
                Material opaque = opaqueMaterial(shader, fallbacks);
                try {
                    Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 3.0f));
                    float[] thinFrame = renderBacklitSlab(window, device, environment, fallbacks,
                            shader, mesh, thin, camera, null);
                    float[] zeroFrame = renderBacklitSlab(window, device, environment, fallbacks,
                            shader, mesh, zeroThickness, camera, null);
                    float thinLuminance = centerLuminance(thinFrame);
                    float zeroLuminance = centerLuminance(zeroFrame);
                    assertTrue(thinLuminance > 0.05f,
                            "the self-shadowed backlit panel must transmit visible energy: "
                                    + thinLuminance);
                    assertTrue(thinLuminance > zeroLuminance + 0.05f,
                            "the thickness tolerance must recover energy over the pre-fix "
                                    + "zero-thickness baseline: " + thinLuminance + " vs "
                                    + zeroLuminance);
                    assertTrue(zeroLuminance < 0.005f,
                            "the zero-thickness fixture must reproduce the pre-fix self-shadow "
                                    + "(near zero): " + zeroLuminance);

                    // Opaque receiver, front-lit by the same shadow-enabled light
                    // with the thin panel as the caster on the light side. The
                    // receiver has no thin controls, so the exact lookup applies
                    // and the panel must fully occlude it; the no-caster run
                    // proves the selected pixel is a visible lit receiver pixel.
                    float[] receiverLit = renderFrontShadowReceiver(window, device, environment,
                            fallbacks, shader, mesh, thin, opaque, false);
                    float[] receiverShadow = renderFrontShadowReceiver(window, device, environment,
                            fallbacks, shader, mesh, thin, opaque, true);
                    int occluded = darkestVisibleReceiverPixel(receiverLit, receiverShadow);
                    assertTrue(occluded >= 0,
                            "the opaque-receiver fixture must contain a visible receiver pixel");
                    assertTrue(luminance(receiverLit, occluded) > 0.1f,
                            "the selected opaque pixel must be a lit receiver pixel without the "
                                    + "caster: " + luminance(receiverLit, occluded));
                    assertTrue(luminance(receiverShadow, occluded) < 0.002f,
                            "an opaque receiver behind the thin panel must stay fully occluded; "
                                    + "the thickness tolerance must not leak below the panel: "
                                    + luminance(receiverShadow, occluded));
                    GlDebug.checkError("thin transmission self-shadow and opaque receiver");
                } finally {
                    thin.close();
                    zeroThickness.close();
                    opaque.close();
                }
            }
        }
    }

    @Test
    void opaqueCasterBeyondTheThicknessStillOccludesTheThinBacklight() {
        try (GlfwWindow window = thinWindow()) {
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = loadEnvironment(device);
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = forwardShader();
                 Mesh mesh = thinMesh("thin-transmission-distant-caster")) {
                environment.intensity(0.0f);
                Material thin = thinMaterial(shader, fallbacks, thinProperties(1.0f));
                Material opaque = opaqueMaterial(shader, fallbacks);
                try {
                    Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 3.0f));
                    float[] unobstructed = renderBacklitSlab(window, device, environment,
                            fallbacks, shader, mesh, thin, camera, null);
                    float[] obstructed = renderBacklitSlab(window, device, environment,
                            fallbacks, shader, mesh, thin, camera, opaque);
                    assertTrue(centerLuminance(unobstructed) > 0.05f,
                            "the thin backlight must transmit without the far caster: "
                                    + centerLuminance(unobstructed));
                    assertTrue(centerLuminance(obstructed) < 0.002f,
                            "an opaque caster " + DISTANT_CASTER_METRES + " m on the light side "
                                    + "must still occlude the thin backlight; the tolerance is "
                                    + "bounded to a 5 cm slab: " + centerLuminance(obstructed));
                    GlDebug.checkError("thin transmission distant opaque caster");
                } finally {
                    thin.close();
                    opaque.close();
                }
            }
        }
    }

    @Test
    void frontLitDirectTermAndZeroThicknessStayAtTheLegacyBaseline() {
        try (GlfwWindow window = thinWindow()) {
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = loadEnvironment(device);
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = forwardShader();
                 Mesh mesh = thinMesh("thin-transmission-front-baseline")) {
                environment.intensity(0.0f);
                Material thin = thinMaterial(shader, fallbacks, thinProperties(1.0f));
                Material zeroControls = thinMaterial(shader, fallbacks, zeroControls());
                Material zeroThickness = thinMaterial(shader, fallbacks, thinProperties(0.0f));
                try {
                    Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 3.0f));
                    float[] thinFrame = renderFrontLitPanel(window, device, environment, fallbacks,
                            shader, mesh, thin, camera);
                    float[] zeroFrame = renderFrontLitPanel(window, device, environment, fallbacks,
                            shader, mesh, zeroControls, camera);
                    // Current-run two-configuration equality: front lighting makes
                    // the thin term exactly zero, so the feature values must take
                    // the same legacy `visibility * base` expression as the
                    // all-zero controls.
                    assertArrayEquals(zeroFrame, thinFrame, 0.0f,
                            "front-lit frames must be bit-identical between the feature values "
                                    + "and the all-zero thin controls");
                    assertTrue(centerLuminance(thinFrame) > 0.1f,
                            "the front-lit baseline must be a visibly lit direct pixel: "
                                    + centerLuminance(thinFrame));
                    // The backlit zero-thickness frame is the recorded pre-fix
                    // behaviour: it must stay dark because the tolerance only
                    // exists for a non-zero slab.
                    float[] zeroThicknessBacklit = renderBacklitSlab(window, device, environment,
                            fallbacks, shader, mesh, zeroThickness, camera, null);
                    assertTrue(centerLuminance(zeroThicknessBacklit) < 0.005f,
                            "zero thickness must keep the exact legacy shadow lookup and stay "
                                    + "self-shadowed: " + centerLuminance(zeroThicknessBacklit));
                    GlDebug.checkError("thin transmission front-lit baseline");
                } finally {
                    thin.close();
                    zeroControls.close();
                    zeroThickness.close();
                }
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

    private static PbrEnvironment loadEnvironment(GlRenderDevice device) {
        return PbrEnvironmentLoader.load(device,
                ThinTranslucencyTransmissionShadowGlTest.class,
                "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
    }

    private static ShaderProgram forwardShader() {
        return ShaderProgram.fromResource(ThinTranslucencyTransmissionShadowGlTest.class,
                "/shaders/render3d/pbr/pbr-forward.vert",
                "/shaders/render3d/pbr/pbr-forward.frag");
    }

    private static Mesh thinMesh(String name) {
        return Mesh.from(TangentGenerator.generate(BuiltinMeshData.texturedQuad(name)).mesh());
    }

    private static Material thinMaterial(ShaderProgram shader, PbrFallbackTextures fallbacks,
                                         PbrMaterialProperties properties) {
        return PbrMaterials.createWithBindings(shader, properties, Map.of(), fallbacks,
                CullMode.NONE, true, false);
    }

    private static Material opaqueMaterial(ShaderProgram shader, PbrFallbackTextures fallbacks) {
        return PbrMaterials.createWithBindings(shader, PbrMaterialProperties.defaults(),
                Map.of(), fallbacks, CullMode.NONE, false, false);
    }

    /**
     * Two parallel casters model the panel's two faces. The light points along
     * +Z, so the camera at +Z sees the front face and the -Z face writes the
     * nearer shadow-map depth. The optional opaque caster sits on the light
     * side and must dominate the shadow map.
     */
    private static float[] renderBacklitSlab(GlfwWindow window, GlRenderDevice device,
                                             PbrEnvironment environment,
                                             PbrFallbackTextures fallbacks,
                                             ShaderProgram shader, Mesh mesh, Material panel,
                                             Camera camera, Material opaqueCaster) {
        Scene scene = new Scene(camera)
                .add(SceneObject.fixed(mesh, panel,
                        new Matrix4f().scale(3.0f, 3.0f, 1.0f), true))
                .add(SceneObject.fixed(mesh, panel,
                        new Matrix4f().translate(0.0f, 0.0f, -PANEL_GAP_METRES)
                                .scale(3.0f, 3.0f, 1.0f), true))
                .addLight(SceneLight.shadowedDirectional(new Vector3f(0.0f, 0.0f, 1.0f),
                        new Vector3f(1.0f), LIGHT_INTENSITY));
        if (opaqueCaster != null) {
            scene.add(SceneObject.fixed(mesh, opaqueCaster,
                    new Matrix4f().translate(0.0f, 0.0f, -DISTANT_CASTER_METRES)
                            .scale(3.0f, 3.0f, 1.0f), true));
        }
        return execute(window, device, environment, scene,
                new ShadowSettings(32, 4.0f, 0.1f, 20.0f, 0.0f));
    }

    /**
     * Front-lit control: the thin panel is the only caster and the camera sees
     * its lit side, so the thin term is exactly zero and no self-shadow exists.
     */
    private static float[] renderFrontLitPanel(GlfwWindow window, GlRenderDevice device,
                                               PbrEnvironment environment,
                                               PbrFallbackTextures fallbacks,
                                               ShaderProgram shader, Mesh mesh, Material panel,
                                               Camera camera) {
        Scene scene = new Scene(camera)
                .add(SceneObject.fixed(mesh, panel,
                        new Matrix4f().scale(3.0f, 3.0f, 1.0f), true))
                .addLight(SceneLight.shadowedDirectional(new Vector3f(0.0f, 0.0f, -1.0f),
                        new Vector3f(1.0f), LIGHT_INTENSITY));
        return execute(window, device, environment, scene,
                new ShadowSettings(32, 4.0f, 0.1f, 20.0f, 0.0f));
    }

    /**
     * Front-lit opaque receiver behind the thin panel caster. The grazing light
     * projection is selected so the umbra is visible beside the caster
     * silhouette; the pixel search below finds it from the images instead of
     * hard-coding a projection.
     */
    private static float[] renderFrontShadowReceiver(GlfwWindow window, GlRenderDevice device,
                                                     PbrEnvironment environment,
                                                     PbrFallbackTextures fallbacks,
                                                     ShaderProgram shader, Mesh mesh,
                                                     Material panel, Material receiver,
                                                     boolean casterPresent) {
        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 3.0f));
        Vector3f lightTravel = new Vector3f(0.7f, 0.2f, -0.68f).normalize();
        Scene scene = new Scene(camera)
                .add(SceneObject.fixed(mesh, receiver,
                        new Matrix4f().scale(3.0f, 3.0f, 1.0f), false))
                .addLight(SceneLight.shadowedDirectional(lightTravel, new Vector3f(1.0f),
                        LIGHT_INTENSITY));
        if (casterPresent) {
            Vector3f towardLight = new Vector3f(lightTravel).negate();
            scene.add(SceneObject.fixed(mesh, panel,
                    new Matrix4f().translate(towardLight.mul(0.8f))
                            .scale(1.6f, 1.6f, 1.0f), true));
        }
        return execute(window, device, environment, scene,
                new ShadowSettings(64, 4.0f, 0.1f, 20.0f, 0.0f));
    }

    private static float[] execute(GlfwWindow window, GlRenderDevice device,
                                   PbrEnvironment environment, Scene scene,
                                   ShadowSettings shadowSettings) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings(),
                environment);
        try {
            pipeline.directionalCascades(DirectionalCascadeSettings.disabled());
            pipeline.features().submit(pipeline.features().effective().toBuilder()
                    .directionalShadowMap(new DirectionalShadowMap(shadowSettings))
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

    /**
     * Darkest visible pixel of the receiver: a pixel lit without the caster and
     * darkest with it. Background pixels are excluded by the lit threshold and
     * the caster itself is identical in both frames.
     */
    private static int darkestVisibleReceiverPixel(float[] lit, float[] shadow) {
        int best = -1;
        float bestLuminance = Float.MAX_VALUE;
        for (int pixel = 0; pixel < lit.length / 4; pixel++) {
            if (luminance(lit, pixel) <= 0.1f) continue;
            float shadowLuminance = luminance(shadow, pixel);
            if (shadowLuminance < bestLuminance) {
                bestLuminance = shadowLuminance;
                best = pixel;
            }
        }
        return best;
    }

    private static float centerLuminance(float[] rgba) {
        return luminance(rgba, (HEIGHT / 2) * WIDTH + WIDTH / 2);
    }

    private static float luminance(float[] rgba, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * rgba[offset] + 0.7152f * rgba[offset + 1]
                + 0.0722f * rgba[offset + 2];
    }

    private static PbrMaterialProperties thinProperties(float thickness) {
        return new PbrMaterialProperties(new Vector4f(1.0f), 0.0f, 1.0f, 1.0f, 1.0f,
                new Vector3f(), Map.of(), thickness, 0.9f, 0.9f);
    }

    private static PbrMaterialProperties zeroControls() {
        return new PbrMaterialProperties(new Vector4f(1.0f), 0.0f, 1.0f, 1.0f, 1.0f,
                new Vector3f(), Map.of(), 0.0f, 0.0f, 0.0f);
    }
}
