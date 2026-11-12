package com.kaleblangley.haikalat.subsystems.render3d.vct;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level contract of the VCT shaders and recorder.
 *
 * <p>The shaders are not compiled here (that needs a GL context); the checks
 * lock the documented cone footprint, conservative shell rasterization, mip
 * coverage rule, no-op/disable boundary and the absence of any hardware
 * ray-tracing path. Real compilation and pixel behaviour belong to
 * {@code VctGlTest}.</p>
 */
class VctShaderTest {
    private static final String CLEAR = "/shaders/render3d/vct/vct-clear.comp";
    private static final String VOXELIZE = "/shaders/render3d/vct/vct-voxelize.comp";
    private static final String MIP = "/shaders/render3d/vct/vct-mip.comp";
    private static final String TRACE = "/shaders/render3d/vct/vct-trace.comp";
    private static final String COMPOSITE_VERTEX = "/shaders/render3d/vct/vct-composite.vert";
    private static final String COMPOSITE_FRAGMENT = "/shaders/render3d/vct/vct-composite.frag";
    private static final Path PASS_SOURCE = Path.of("src", "main", "java", "com",
            "kaleblangley", "haikalat", "subsystems", "render3d", "vct", "VctPass.java");

    @Test
    void allShadersLoadAndDeclareTheExpectedStageVersion() {
        for (String path : new String[]{CLEAR, VOXELIZE, MIP, TRACE, COMPOSITE_FRAGMENT}) {
            String source = VctPass.shaderSource(path);
            assertTrue(source.startsWith("#version 460 core"), path + " must declare GLSL 4.60");
        }
        String vertex = VctPass.shaderSource(COMPOSITE_VERTEX);
        assertTrue(vertex.startsWith("#version 460 core"));
        assertTrue(vertex.contains("layout(location = 0) in vec2 aPosition"));
        assertTrue(vertex.contains("layout(location = 1) in vec2 aTexCoord"));
    }

    @Test
    void voxelizerIsAConservativeBoundedShellWithExplicitDilation() {
        String source = VctPass.shaderSource(VOXELIZE);
        assertTrue(source.contains("layout(local_size_x = 64) in"));
        assertTrue(source.contains("readonly buffer TriangleData"));
        assertTrue(source.contains("imageStore(uDestination"));
        assertTrue(source.contains("planeTolerance = uVoxelSize * (0.75 + float(uDilation))"));
        assertTrue(source.contains("insideConservative"));
        assertTrue(source.contains("min(high, regionHigh)"));
        assertTrue(source.contains("uOccupancy * normalOccupancy.w"));
        assertFalse(source.contains("rayQuery"));
        assertFalse(source.contains("GL_NV_ray_tracing"));
        assertFalse(source.contains("NV_ray_tracing"));
    }

    @Test
    void mipChainAveragesRadianceByCoverageAndKeepsMaximumCoverage() {
        String source = VctPass.shaderSource(MIP);
        assertTrue(source.contains("readonly uniform image3D uSource"));
        assertTrue(source.contains("writeonly uniform image3D uDestination"));
        assertTrue(source.contains("weightedRadiance += value.rgb * value.a"));
        assertTrue(source.contains("weight += value.a"));
        assertTrue(source.contains("coverage = max(coverage, value.a)"));
        assertTrue(source.contains("weightedRadiance / weight"));
        assertTrue(source.contains("vec4(radiance, coverage)"));
    }

    @Test
    void coneTraceUsesTheDocumentedFootprintAndOpacityRule() {
        String trace = VctPass.shaderSource(TRACE);
        assertTrue(trace.contains("textureLod(uVoxels, uvw, lod)"));
        assertTrue(trace.contains("log2(max(2.0 * radius / voxelSize, 1.0))"));
        assertTrue(trace.contains("transmittance *= 1.0 - coverage"));
        assertTrue(trace.contains("uOpacityThreshold"));
        assertTrue(trace.contains("for (int cone = 0; cone < uDiffuseCones; ++cone)"));
        assertTrue(trace.contains("float cosTheta = sqrt(fraction)"));
        assertTrue(trace.contains("roughness * roughness * uSpecularApertureScale"));
        assertTrue(trace.contains("uResults[probe * 2 + 0] = vec4(diffuse, diffuseTransmittance)"));
        assertTrue(trace.contains("uResults[probe * 2 + 1] = vec4(specular, specularTransmittance)"));

        String composite = VctPass.shaderSource(COMPOSITE_FRAGMENT);
        assertTrue(composite.contains("textureLod(uVoxels, uvw, lod)"));
        assertTrue(composite.contains("log2(max(2.0 * radius / voxelSize, 1.0))"));
        assertTrue(composite.contains("transmittance *= 1.0 - coverage"));
        assertTrue(composite.contains("uInverseProjection"));
        assertTrue(composite.contains("uInverseView"));
        assertTrue(composite.contains("depth >= 0.999999"));
        assertTrue(composite.contains("uSurfaceRoughness * uSurfaceRoughness"));
        assertTrue(composite.contains("fragColor = vec4(indirect, 0.0)"));
    }

    @Test
    void recorderOrdersClearRasterizeAndMipBarriersWithoutCustomEscape() throws IOException {
        String pass = Files.readString(PASS_SOURCE);
        assertTrue(pass.contains("recordClear(cmd, active, region)"));
        assertTrue(pass.contains("recordVoxelizeRegion(cmd, active, region, triangles)"));
        assertTrue(pass.contains("recordMipChain(cmd, active, plan)"));
        assertTrue(pass.contains("GL_SHADER_IMAGE_ACCESS_BARRIER_BIT"));
        assertTrue(pass.contains("GL_TEXTURE_FETCH_BARRIER_BIT"));
        assertTrue(pass.contains("GL_SHADER_STORAGE_BARRIER_BIT"));
        assertTrue(pass.contains("bindImageTexture(VOXEL_IMAGE_UNIT, active.volume.texture, 0"));
        assertTrue(pass.contains("bindImageTexture(IMAGE_UNIT_SOURCE, active.volume.texture, level - 1"));
        assertTrue(pass.contains("bindImageTexture(IMAGE_UNIT_DESTINATION, active.volume.texture, level"));
        assertFalse(pass.contains(".custom("));
        assertFalse(pass.contains("glDispatchCompute"));
    }

    @Test
    void everyUploadedUniformIsDeclaredAndReferencedInItsShader() throws IOException {
        String pass = Files.readString(PASS_SOURCE);
        Map<String, String> programs = Map.of(
                "clear", CLEAR,
                "voxelize", VOXELIZE,
                "mip", MIP,
                "trace", TRACE,
                "composite", COMPOSITE_FRAGMENT);
        for (Map.Entry<String, String> program : programs.entrySet()) {
            String shader = VctPass.shaderSource(program.getValue());
            Set<String> declared = declaredUniforms(shader);
            Set<String> uploaded = uploadedUniforms(pass, program.getKey());
            assertEquals(declared, uploaded, "uploaded uniforms must match the "
                    + program.getValue() + " declaration set");
            for (String uniform : declared) {
                long references = Pattern.compile("\\b" + Pattern.quote(uniform) + "\\b")
                        .matcher(shader).results().count();
                assertTrue(references >= 2, uniform + " is declared in " + program.getValue()
                        + " but never referenced; GLSL would optimize it out and the uniform "
                        + " upload would fail at runtime");
            }
        }
    }

    private static Set<String> declaredUniforms(String source) {
        Matcher matcher = Pattern.compile(
                "(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?"
                        + "(?:readonly\\s+|writeonly\\s+|coherent\\s+|volatile\\s+)*"
                        + "uniform\\s+(\\w+)\\s+(u\\w+)\\s*;")
                .matcher(source);
        Set<String> names = new java.util.HashSet<>();
        while (matcher.find()) {
            // Image uniforms are bound with image units, not uploaded by name.
            if (!matcher.group(1).startsWith("image")) names.add(matcher.group(2));
        }
        return names;
    }

    private static Set<String> uploadedUniforms(String passSource, String programVariable) {
        Matcher matcher = Pattern.compile(
                "setUniform(?:Int|Float|Vec2|Vec3|Vec4|Mat3|Mat4)\\(\\s*"
                        + Pattern.quote(programVariable) + "\\s*,\\s*\"(u\\w+)\"")
                .matcher(passSource);
        Set<String> names = new java.util.HashSet<>();
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    @Test
    void disabledPathIsAZeroCommandNoOpBeforeAnyBorrowedInputValidation() throws IOException {
        String pass = Files.readString(PASS_SOURCE);
        int disabledCheck = pass.indexOf("if (runtime == null) return;");
        int borrowedTarget = pass.indexOf("Objects.requireNonNull(colorTarget", disabledCheck);
        assertTrue(disabledCheck > 0, "the disabled early-out must exist");
        assertTrue(borrowedTarget > disabledCheck,
                "disabled composite must return before touching borrowed targets");
        assertTrue(pass.contains("if (runtime == null) {\n            lastUpdateStats = UpdateStats.NONE;\n            return lastUpdateStats;\n        }"));
    }
}
