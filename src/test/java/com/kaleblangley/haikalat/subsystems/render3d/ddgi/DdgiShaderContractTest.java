package com.kaleblangley.haikalat.subsystems.render3d.ddgi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM packaging and shader-ABI contract for the DDGI programs.
 *
 * <p>This cannot compile GLSL, so it pins the resource packaging, the single
 * shared-atlas injection, the uniform names the Java passes upload and the
 * atlas layout arithmetic. The guarded real-GL class proves the actual
 * compilation and the pixel behaviour.</p>
 */
class DdgiShaderContractTest {
    private static final String RELIGHT = "ddgi-relight.frag";
    private static final String INDIRECT = "ddgi-indirect.frag";

    @Test
    void shaderResourcesArePackaged() {
        for (String name : List.of("ddgi-common.glsl", "ddgi-fullscreen.vert", RELIGHT, INDIRECT)) {
            assertNotNull(DdgiShaderSource.class.getResource(DdgiShaderSource.ROOT + name),
                    "DDGI shader resource must be packaged: " + name);
        }
        assertTrue(DdgiProbeVolume.class.getResource(
                        "/shaders/shadows/directional-depth.vert") != null,
                "probe depth capture reuses the production shadow depth vertex stage");
        assertTrue(DdgiProbeVolume.class.getResource(
                        "/shaders/shadows/masked-directional-depth.frag") != null,
                "probe depth capture reuses the production masked shadow variant");
    }

    @Test
    void relightAndIndirectShareExactlyOneAtlasContract() {
        String relight = DdgiShaderSource.fragmentSource(RELIGHT);
        String indirect = DdgiShaderSource.fragmentSource(INDIRECT);
        assertTrue(relight.startsWith("#version 460 core\n"));
        assertTrue(indirect.startsWith("#version 460 core\n"));
        assertEquals(1, versionDirectives(relight),
                "the injected shared block must not add a second #version directive");
        assertEquals(1, versionDirectives(indirect));
        assertEquals(1, occurrences(relight, "// BEGIN DDGI SHARED ATLAS CONTRACT"));
        assertEquals(1, occurrences(indirect, "// BEGIN DDGI SHARED ATLAS CONTRACT"));
        assertEquals(sharedBlock(relight), sharedBlock(indirect),
                "both DDGI fragment stages must use the same injected atlas/weighting block");
        assertTrue(sharedBlock(relight).contains("vec3 ddgiSampleIrradiance("));
        assertTrue(sharedBlock(relight).contains("float ddgiProbeVisibility("));
        assertTrue(sharedBlock(relight).contains("int ddgiFaceIndex("));
    }

    @Test
    void relightDeclaresTheUniformsTheVolumeUploads() {
        String source = DdgiShaderSource.fragmentSource(RELIGHT);
        for (String uniform : List.of(
                "uniform sampler2D uProbeDepthAtlas;",
                "uniform sampler2D uIrradianceAtlas;",
                "uniform int uProbesX;",
                "uniform int uProbesY;",
                "uniform int uProbesZ;",
                "uniform vec3 uGridOrigin;",
                "uniform float uSpacing;",
                "uniform float uBorderFadeMetres;",
                "uniform int uDepthFaceResolution;",
                "uniform float uDepthNear;",
                "uniform float uDepthFar;",
                "uniform int uIrradianceFaceResolution;",
                "uniform int uProbeIndex;",
                "uniform int uFaceIndex;",
                "uniform vec2 uTileOrigin;",
                "uniform int uRaySteps;",
                "uniform float uMaximumRayDistance;",
                "uniform float uHysteresis;",
                "uniform vec3 uSkyRadiance;",
                "uniform vec3 uLightDirection;",
                "uniform vec3 uLightRadiance;",
                "uniform float uSurfaceAlbedo;",
                "uniform float uMaximumIrradiance;")) {
            assertTrue(source.contains(uniform), "relight shader must declare " + uniform);
        }
        assertTrue(source.contains("texelFetch(uProbeDepthAtlas"),
                "world-space ray visibility must read the rasterized probe depth atlas");
        assertTrue(source.contains("ddgiHitNormal("),
                "the relight stage must derive a surface normal from probe depth neighbours");
        assertTrue(source.contains("mix(incident, previous, clamp(uHysteresis, 0.0, 1.0))"),
                "the temporal blend must use the configured history weight");
        assertTrue(source.contains("clamp(blended, vec3(0.0), vec3(uMaximumIrradiance))"),
                "stored radiance must be clamped to the configured maximum");
    }

    @Test
    void indirectDeclaresTheUniformsThePassUploads() {
        String source = DdgiShaderSource.fragmentSource(INDIRECT);
        for (String uniform : List.of(
                "uniform sampler2D uSceneDepth;",
                "uniform sampler2D uSceneNormal;",
                "uniform mat4 uInverseProjection;",
                "uniform mat4 uInverseView;",
                "uniform vec2 uViewportSize;",
                "uniform float uIntensity;",
                "uniform float uSurfaceAlbedo;",
                "uniform sampler2D uProbeDepthAtlas;",
                "uniform sampler2D uIrradianceAtlas;",
                "uniform vec3 uGridOrigin;",
                "uniform float uSpacing;",
                "uniform int uDepthFaceResolution;",
                "uniform int uIrradianceFaceResolution;")) {
            assertTrue(source.contains(uniform), "indirect shader must declare " + uniform);
        }
        assertTrue(source.contains("ddgiSampleIrradiance(worldPosition.xyz, normal)"),
                "the indirect pass must query the shared probe weighting");
        assertTrue(source.contains("depth < 0.999999"),
                "background pixels must not receive indirect light");
    }

    @Test
    void atlasLayoutMatchesTheBoundedJavaSettings() {
        String shared = DdgiShaderSource.commonSource();
        assertEquals(6, DdgiSettings.FACE_COUNT);
        assertEquals(3, DdgiSettings.IRRADIANCE_TILE_COLUMNS);
        assertEquals(2, DdgiSettings.IRRADIANCE_TILE_ROWS);
        assertEquals(4, DdgiSettings.IRRADIANCE_FACE_RESOLUTION);
        assertEquals(12, DdgiSettings.IRRADIANCE_TILE_WIDTH);
        assertEquals(8, DdgiSettings.IRRADIANCE_TILE_HEIGHT);
        assertTrue(shared.contains("(probe * 6 + face) * uDepthFaceResolution"),
                "the probe depth atlas layout must be probe * 6 * resolution");
        assertTrue(shared.contains("probe * tileWidth + faceX * uIrradianceFaceResolution"),
                "the irradiance atlas layout must be probe * tileWidth + face column");
        assertTrue(shared.contains("int tileWidth = 3 * uIrradianceFaceResolution;"));
        assertTrue(shared.contains("int faceX = face % 3;"));
        assertTrue(shared.contains("int faceY = face / 3;"));
        assertTrue(shared.contains("return direction.x >= 0.0 ? 0 : 1;"),
                "face order must start with +X then -X");
        assertTrue(shared.contains("return direction.y >= 0.0 ? 2 : 3;"));
        assertTrue(shared.contains("return direction.z >= 0.0 ? 4 : 5;"));
    }

    @Test
    void shaderUsesTheDocumentedBoundedWeightingAndNoHardwareRayTracing() {
        String shared = DdgiShaderSource.commonSource();
        assertTrue(shared.contains("max(dot(normalize(toProbe + vec3(1.0e-5)), normal), 0.0)"),
                "the normal-side probe gate must be explicit");
        assertTrue(shared.contains("* normalWeight;"),
                "the normalizer must keep the unoccluded trilinear weight");
        assertTrue(shared.contains("sum += ddgiSampleProbeRadiance(probe, normal) * weight * visibility;"));
        assertFalse(shared.contains("0.05 + normalWeight"),
                "probes behind the surface must not receive a fixed 5% leak bias");
        assertTrue(shared.contains("float tolerance = max(uSpacing * 0.05, distance * 0.02);"));
        assertTrue(shared.contains("return difference <= tolerance ? 1.0 : 0.0;"),
                "visibility must be a hard bounded gate");
        assertTrue(shared.contains("return border * sum / weightSum;"));
        assertTrue(shared.contains("clamp(nearest / fade, 0.0, 1.0);"));

        for (String forbidden : List.of("rayQuery", "traceRay", "rayPayload",
                "GL_EXT_ray", "GL_NV_ray", "VK_KHR_ray", "RTX")) {
            assertFalse(shared.toUpperCase().contains(forbidden.toUpperCase()),
                    "DDGI must not assume hardware ray tracing: " + forbidden);
            assertFalse(DdgiShaderSource.fragmentSource(RELIGHT).toUpperCase()
                            .contains(forbidden.toUpperCase()),
                    "DDGI must not assume hardware ray tracing: " + forbidden);
        }
    }

    @Test
    void javaUploadsOnlyNamesTheShaderContractDeclares() {
        String relight = DdgiShaderSource.fragmentSource(RELIGHT);
        String indirect = DdgiShaderSource.fragmentSource(INDIRECT);
        for (String name : List.of("uProbeDepthAtlas", "uIrradianceAtlas", "uProbesX",
                "uProbesY", "uProbesZ", "uGridOrigin", "uSpacing", "uBorderFadeMetres",
                "uDepthFaceResolution", "uDepthNear", "uDepthFar", "uIrradianceFaceResolution",
                "uProbeIndex", "uFaceIndex", "uTileOrigin", "uRaySteps",
                "uMaximumRayDistance", "uHysteresis", "uSkyRadiance", "uLightDirection",
                "uLightRadiance", "uSurfaceAlbedo", "uMaximumIrradiance")) {
            assertTrue(relight.contains(name), "relight must declare uploaded uniform " + name);
        }
        for (String name : List.of("uSceneDepth", "uSceneNormal", "uInverseProjection",
                "uInverseView", "uViewportSize", "uIntensity", "uSurfaceAlbedo")) {
            assertTrue(indirect.contains(name), "indirect must declare uploaded uniform " + name);
        }
    }

    private static String sharedBlock(String source) {
        int begin = source.indexOf("// BEGIN DDGI SHARED ATLAS CONTRACT");
        int end = source.indexOf("// END DDGI SHARED ATLAS CONTRACT");
        assertTrue(begin >= 0 && end > begin, "missing shared atlas markers");
        return source.substring(begin, end);
    }

    private static int versionDirectives(String source) {
        int count = 0;
        for (String line : source.split("\n", -1)) {
            if (line.startsWith("#version")) count++;
        }
        return count;
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
