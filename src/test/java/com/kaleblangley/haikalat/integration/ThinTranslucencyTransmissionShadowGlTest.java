package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
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
import com.kaleblangley.haikalat.subsystems.render3d.PipelineTopology;
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
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ContactShadowSettings;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *
 * <p>Four additional cases pin the review fixes: a world-space cloud shadow and
 * the material-level contact term must attenuate only the thin share; a
 * four-cascade blend band must convert the transmitted world thickness with
 * each sampled cascade's own matrix; and a 3-4 cm caster outside the half-slab
 * bound must not transmit. The four-cascade fixture is used because the
 * existing two-cascade atlas layout writes 256x256 tiles while the forward
 * shader reads a half-width, full-height tile, so a two-cascade fixture cannot
 * sample its own shadow map; that layout mismatch is out of this change's
 * scope.</p>
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

    /**
     * Defect 1, cloud half. The production procedural cloud layer is evaluated
     * at a world position that is under cloud cover; the same slab at a clear
     * world position must stay bit-identical. Before the fix the cloud-on frame
     * measured the same 0.328613 as cloud-off, i.e. the thin share escaped the
     * cloud attenuation entirely.
     */
    @Test
    void cloudShadowAttenuatesTheThinBacklightShare() {
        try (GlfwWindow window = thinWindow()) {
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = loadEnvironment(device);
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = forwardShader();
                 Mesh mesh = thinMesh("thin-transmission-cloud")) {
                environment.intensity(0.0f);
                Material thin = thinMaterial(shader, fallbacks, thinProperties(1.0f));
                try {
                    // Production cloud tuning with the wind pinned to zero so the
                    // fixture is deterministic (the animated path is covered by
                    // CloudShadowsGlTest and the shader contract test).
                    CloudShadowSettings production = new CloudShadowSettings(
                            true, 0.55f, 0.35f, 0.12f, 0.0f, 0.65f);
                    ShadowSettings off = shadowSettings(
                            ContactShadowSettings.disabled(), CloudShadowSettings.disabled());
                    ShadowSettings on = shadowSettings(
                            ContactShadowSettings.disabled(), production);
                    Vector3f overcast = new Vector3f(-4.5f, 2.0f, 0.0f);
                    Vector3f clear = new Vector3f();

                    float[] overcastOff = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, null, null, overcast, 3.0f,
                            off, DirectionalCascadeSettings.disabled(), renderSettings());
                    float[] overcastOn = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, null, null, overcast, 3.0f,
                            on, DirectionalCascadeSettings.disabled(), renderSettings());
                    float[] clearOff = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, null, null, clear, 3.0f,
                            off, DirectionalCascadeSettings.disabled(), renderSettings());
                    float[] clearOn = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, null, null, clear, 3.0f,
                            on, DirectionalCascadeSettings.disabled(), renderSettings());

                    float baseline = centerLuminance(overcastOff);
                    float clouded = centerLuminance(overcastOn);
                    assertTrue(baseline > 0.31f,
                            "the thin self-shadow baseline must stay at the recorded value: "
                                    + baseline);
                    assertTrue(clouded < baseline * 0.75f,
                            "cloud shadow must attenuate the thin backlight share: measured "
                                    + clouded + " vs " + baseline);
                    assertTrue(clouded > 0.05f,
                            "cloud shadow is attenuation, not a full shadow: " + clouded);
                    // A clear world position must take the exact same thin share:
                    // the cloud mask is world-locked, not a global toggle.
                    assertArrayEquals(clearOff, clearOn, 1.0e-4f,
                            "a clear cloud-field position must leave the thin share unchanged");
                    assertTrue(centerLuminance(clearOn) > 0.31f,
                            "the clear control must reproduce the baseline: "
                                    + centerLuminance(clearOn));
                    GlDebug.checkError("thin transmission cloud shadow");
                } finally {
                    thin.close();
                }
            }
        }
    }

    /**
     * Defect 1, contact half. MSAA keeps the screen-space contact pass out of the
     * topology, so only the material-level shadow-map march runs. The 2.2 cm
     * caster sits inside the half-slab tolerance: the transmissive lookup passes
     * it, and only the contact term may darken the thin share. Before the fix
     * contact-on measured the same 0.328613 as contact-off.
     */
    @Test
    void materialContactShadowAttenuatesTheThinBacklightShare() {
        try (GlfwWindow window = thinWindow()) {
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = loadEnvironment(device);
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = forwardShader();
                 Mesh mesh = thinMesh("thin-transmission-contact")) {
                environment.intensity(0.0f);
                Material thin = thinMaterial(shader, fallbacks, thinProperties(1.0f));
                Material opaque = opaqueMaterial(shader, fallbacks);
                try {
                    ContactShadowSettings contact = new ContactShadowSettings(
                            true, 0.1f, 0.05f, 8, 0.8f);
                    RenderSettings settings = renderSettings(AntiAliasingMode.MSAA);
                    assertFalse(PipelineTopology.screenSpaceContactShadowCompositionActive(
                                    settings, contact, true),
                            "MSAA must keep the screen-space contact pass inactive so the "
                                    + "material-level term is the only consumer");
                    assertTrue(PipelineTopology.screenSpaceContactShadowCompositionActive(
                                    renderSettings(AntiAliasingMode.NONE), contact, true),
                            "the non-MSAA topology must contain the screen-space pass, proving "
                                    + "the MSAA fixture isolates the material term");
                    float nearCaster = -0.022f;
                    float[] offWithCaster = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, opaque, nearCaster, new Vector3f(),
                            3.0f, shadowSettings(ContactShadowSettings.disabled(),
                                    CloudShadowSettings.disabled()),
                            DirectionalCascadeSettings.disabled(), settings);
                    float[] onWithCaster = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, opaque, nearCaster, new Vector3f(),
                            3.0f, shadowSettings(contact, CloudShadowSettings.disabled()),
                            DirectionalCascadeSettings.disabled(), settings);
                    float[] offNoCaster = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, null, null, new Vector3f(), 3.0f,
                            shadowSettings(ContactShadowSettings.disabled(),
                                    CloudShadowSettings.disabled()),
                            DirectionalCascadeSettings.disabled(), settings);
                    float[] onNoCaster = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, null, null, new Vector3f(), 3.0f,
                            shadowSettings(contact, CloudShadowSettings.disabled()),
                            DirectionalCascadeSettings.disabled(), settings);

                    float contactOff = centerLuminance(offWithCaster);
                    float contactOn = centerLuminance(onWithCaster);
                    assertEquals(centerLuminance(offNoCaster), contactOff, 1.0e-4f,
                            "the 2.2 cm caster is inside the half-slab tolerance and must not "
                                    + "change the transmissive baseline");
                    assertEquals(centerLuminance(onNoCaster), contactOff, 1.0e-4f,
                            "the material contact term must not self-occlude at these settings");
                    assertTrue(contactOn < contactOff * 0.5f,
                            "the material contact shadow must attenuate the thin backlight "
                                    + "share: measured " + contactOn + " vs " + contactOff);
                    assertTrue(contactOn > 0.01f,
                            "contact strength 0.8 is a partial attenuation, not a full shadow: "
                                    + contactOn);
                    GlDebug.checkError("thin transmission material contact shadow");
                } finally {
                    thin.close();
                    opaque.close();
                }
            }
        }
    }

    /**
     * Defect 2. The receiver sits at 2.25 m, inside cascade 0's blend band for a
     * four-cascade, lambda=0 plan; the 4 cm caster is deeper than the half-slab
     * bound, so both sampled cascades must block it. The pre-fix shader reused
     * cascade 0's normalized tolerance for cascade 1 and leaked 0.161987
     * (about half the 0.328613 baseline) at this exact pixel.
     */
    @Test
    void cascadeBlendBandUsesEachCascadesOwnTolerance() {
        try (GlfwWindow window = thinWindow()) {
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = loadEnvironment(device);
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = forwardShader();
                 Mesh mesh = thinMesh("thin-transmission-cascade")) {
                environment.intensity(0.0f);
                Material thin = thinMaterial(shader, fallbacks, thinProperties(1.0f));
                Material opaque = opaqueMaterial(shader, fallbacks);
                try {
                    // Lambda 0 keeps cascade 0 small and cascade 1 larger, which is
                    // the worst case for reusing the near tolerance; the four-tile
                    // atlas has the same layout the forward shader expects.
                    DirectionalCascadeSettings cascades =
                            new DirectionalCascadeSettings(4, 1024, 0.0f, 0.25f, 10.0f);
                    ShadowSettings shadow = shadowSettings(
                            ContactShadowSettings.disabled(), CloudShadowSettings.disabled());
                    float[] baseline = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, null, null, new Vector3f(), 2.25f,
                            shadow, cascades, renderSettings());
                    float[] inside = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, opaque, -0.015f, new Vector3f(),
                            2.25f, shadow, cascades, renderSettings());
                    float[] beyond = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, opaque, -0.04f, new Vector3f(),
                            2.25f, shadow, cascades, renderSettings());
                    float[] distant = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, opaque, -0.1f, new Vector3f(),
                            2.25f, shadow, cascades, renderSettings());

                    assertTrue(centerLuminance(baseline) > 0.31f,
                            "the blend-band fixture must start from the recorded thin baseline: "
                                    + centerLuminance(baseline));
                    assertTrue(centerLuminance(inside) > 0.31f,
                            "a caster inside the half-slab bound must still transmit: "
                                    + centerLuminance(inside));
                    assertTrue(centerLuminance(beyond) < 0.005f,
                            "a 4 cm caster beyond the half-slab bound must not leak through the "
                                    + "blend band: " + centerLuminance(beyond));
                    assertTrue(centerLuminance(distant) < 0.005f,
                            "a 10 cm caster in the same blend band must fully occlude the thin "
                                    + "share: " + centerLuminance(distant));
                    GlDebug.checkError("thin transmission cascade blend band");
                } finally {
                    thin.close();
                    opaque.close();
                }
            }
        }
    }

    /**
     * Defect 5/6 bound. The NDC projection is mapped to [0, 1] window depth and
     * then halved, so the effective pass-through is half the 5 cm material
     * bound (about 2.5 cm). A 3-4 cm caster must therefore not transmit, while
     * 2.5 cm still does.
     */
    @Test
    void halfSlabBoundBlocksThreeToFourCentimetreCasters() {
        try (GlfwWindow window = thinWindow()) {
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = loadEnvironment(device);
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = forwardShader();
                 Mesh mesh = thinMesh("thin-transmission-half-slab-bound")) {
                environment.intensity(0.0f);
                Material thin = thinMaterial(shader, fallbacks, thinProperties(1.0f));
                Material opaque = opaqueMaterial(shader, fallbacks);
                try {
                    ShadowSettings shadow = shadowSettings(
                            ContactShadowSettings.disabled(), CloudShadowSettings.disabled());
                    float[] boundary = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, opaque, -0.025f, new Vector3f(),
                            3.0f, shadow, DirectionalCascadeSettings.disabled(),
                            renderSettings());
                    float[] three = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, opaque, -0.03f, new Vector3f(),
                            3.0f, shadow, DirectionalCascadeSettings.disabled(),
                            renderSettings());
                    float[] four = renderBacklitFixture(window, device, environment,
                            fallbacks, shader, mesh, thin, opaque, -0.04f, new Vector3f(),
                            3.0f, shadow, DirectionalCascadeSettings.disabled(),
                            renderSettings());
                    assertTrue(centerLuminance(boundary) > 0.31f,
                            "a caster at the 2.5 cm effective bound must still transmit: "
                                    + centerLuminance(boundary));
                    assertTrue(centerLuminance(three) < 0.005f,
                            "a 3 cm caster must not transmit through the half-slab bound: "
                                    + centerLuminance(three));
                    assertTrue(centerLuminance(four) < 0.005f,
                            "a 4 cm caster must not transmit through the half-slab bound: "
                                    + centerLuminance(four));
                    GlDebug.checkError("thin transmission half-slab bound");
                } finally {
                    thin.close();
                    opaque.close();
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
        return execute(window, device, environment, scene, shadowSettings,
                DirectionalCascadeSettings.disabled(), renderSettings());
    }

    private static float[] execute(GlfwWindow window, GlRenderDevice device,
                                   PbrEnvironment environment, Scene scene,
                                   ShadowSettings shadowSettings,
                                   DirectionalCascadeSettings cascades,
                                   RenderSettings renderSettings) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings,
                environment);
        try {
            pipeline.directionalCascades(cascades);
            pipeline.features().submit(pipeline.features().effective().toBuilder()
                    .directionalShadowMap(new DirectionalShadowMap(shadowSettings))
                    .directionalCascades(cascades)
                    .build());
            pipeline.build();
            pipeline.execute(device);
            return pipeline.captureLinearHdrRgbaFloat();
        } finally {
            pipeline.close();
        }
    }

    private static ShadowSettings shadowSettings(ContactShadowSettings contact,
                                                 CloudShadowSettings clouds) {
        return new ShadowSettings(32, 4.0f, 0.1f, 20.0f, 0.0f, contact, clouds);
    }

    /**
     * Two parallel casters model the panel's two faces plus an optional opaque
     * caster on the light side. The light travels +Z, so the camera at +Z sees
     * the front face and the -Z face writes the nearer shadow-map depth. The
     * fixture is translated as a unit when a cloud test needs a specific
     * world-space cloud field position.
     */
    private static float[] renderBacklitFixture(GlfwWindow window, GlRenderDevice device,
                                                PbrEnvironment environment,
                                                PbrFallbackTextures fallbacks,
                                                ShaderProgram shader, Mesh mesh,
                                                Material panel, Material caster, Float casterZ,
                                                Vector3f origin, float cameraZ,
                                                ShadowSettings shadow,
                                                DirectionalCascadeSettings cascades,
                                                RenderSettings renderSettings) {
        Camera camera = new Camera(new Vector3f(origin.x, origin.y, cameraZ));
        Scene scene = new Scene(camera)
                .add(SceneObject.fixed(mesh, panel,
                        new Matrix4f().translate(origin.x, origin.y, origin.z)
                                .scale(3.0f, 3.0f, 1.0f), true))
                .add(SceneObject.fixed(mesh, panel,
                        new Matrix4f().translate(origin.x, origin.y,
                                        origin.z - PANEL_GAP_METRES)
                                .scale(3.0f, 3.0f, 1.0f), true))
                .addLight(SceneLight.shadowedDirectional(new Vector3f(0.0f, 0.0f, 1.0f),
                        new Vector3f(1.0f), LIGHT_INTENSITY));
        if (casterZ != null) {
            scene.add(SceneObject.fixed(mesh, caster,
                    new Matrix4f().translate(origin.x, origin.y, origin.z + casterZ)
                            .scale(3.0f, 3.0f, 1.0f), true));
        }
        return execute(window, device, environment, scene, shadow, cascades, renderSettings);
    }

    private static RenderSettings renderSettings() {
        return renderSettings(AntiAliasingMode.NONE);
    }

    private static RenderSettings renderSettings(AntiAliasingMode antiAliasing) {
        return RenderSettings.builder()
                .antiAliasingMode(antiAliasing)
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
