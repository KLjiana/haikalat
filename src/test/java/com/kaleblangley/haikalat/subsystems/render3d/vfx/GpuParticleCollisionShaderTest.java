package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the collision shader contract and the unchanged #27 update program.
 *
 * <p>The disabled path must keep using the byte-identical
 * {@code gpu-particle-indirect.comp} source, so its accepted SHA-256 is pinned
 * here. The collision variant must keep the emission/active-set ABI, sample only
 * the bound single-sample opaque depth texture, bound its search and keep the
 * documented restitution/friction response. These are the contracts a JVM test
 * can observe without a GL context.</p>
 */
class GpuParticleCollisionShaderTest {
    private static final String INDIRECT = "/shaders/render3d/vfx/gpu-particle-indirect.comp";
    private static final String COLLISION = "/shaders/render3d/vfx/gpu-particle-collision.comp";
    /**
     * SHA-256 of the #27 update shader as accepted in
     * {@code docs/licenses/assets.tsv}; the disabled collision path links this
     * exact resource and must never silently change it.
     */
    private static final String INDIRECT_SHA256 =
            "bb49e7a9d0e9f1efd21f3a0e76a1c3a206c6a489dfae93a830ca33e8b8c35a3f";

    @Test
    void disabledPathKeepsTheAcceptedIndirectUpdateSource() throws IOException {
        assertEquals(INDIRECT_SHA256, sha256(bytes(INDIRECT)));
    }

    @Test
    void collisionProgramSamplesTheBoundDepthAndKeepsTheParticleUpdateAbi()
            throws IOException {
        String source = resource(COLLISION);

        assertTrue(source.startsWith("#version 460 core"));
        assertTrue(source.contains("layout (local_size_x = 64) in;"));
        assertTrue(source.contains("layout (std430, binding = 0) buffer ParticleBuffer"));
        assertTrue(source.contains("layout (std430, binding = 1) buffer CounterBuffer"));
        assertTrue(source.contains("layout (std430, binding = 2) buffer ActiveIndexBuffer"));

        assertTrue(source.contains("uniform sampler2D uSceneDepth;"),
                "the collision input is a single-sample depth texture");
        assertTrue(source.contains("texelFetch(uSceneDepth"),
                "depth must be point sampled; filtering would invent surfaces");
        assertTrue(source.contains("uDepthSampling == 1"),
                "the conservative 2x2 mode must be an explicit shader branch");
        assertFalse(source.contains("sampler2DMS"),
                "raw multisampled depth is not sampled by the compute path");
        assertFalse(source.contains("texture("),
                "depth must use texelFetch, not a filtered texture lookup");

        for (String uniform : List.of("uViewProjection", "uInverseViewProjection",
                "uCameraPosition", "uViewportSize", "uCollisionThickness", "uRestitution",
                "uFriction", "uMaximumSearchSteps", "uSearchRadiusPixels", "uDepthSampling")) {
            assertTrue(source.contains(" " + uniform + ";"),
                    "missing collision uniform " + uniform);
        }

        assertTrue(source.contains("if (!(clip.w > 1.0e-6))"),
                "particles at or behind the eye must keep free flight");
        assertTrue(source.contains("any(lessThan(uv, vec2(0.0)))"),
                "particles outside the depth viewport must keep free flight");
        assertTrue(source.contains("if (!(depth < 1.0))"),
                "clear or far-plane depth must not produce a surface");
        assertTrue(source.contains("penetration < 0.0 && -penetration <= uCollisionThickness"),
                "only a bounded behind-surface band may collide");
        assertTrue(source.contains("tangential * (1.0 - uFriction)"),
                "tangential friction must be an explicit scale");
        assertTrue(source.contains("- bestNormal * (uRestitution * normalVelocity)"),
                "the reflected normal velocity must use restitution");

        int integration = source.indexOf("particle.positionLife.w -= uDeltaSeconds;");
        int collision = source.indexOf("collide(particle);");
        int append = source.indexOf("uint slot = atomicAdd(counters.activeCount, 1u);");
        assertTrue(integration >= 0, "the update integration must be present");
        assertTrue(integration < collision,
                "collision must run after the lifetime/integration step");
        assertTrue(collision < append,
                "the active append must observe the resolved position");
        assertEquals(1, occurrences(source, "collide(particle);"),
                "collision must be resolved exactly once per live particle");

        assertFalse(source.contains("gl_VertexID"),
                "the update stage must not depend on a draw vertex index");
        assertFalse(source.contains("imageStore"),
                "the collision path must not write an unrelated image");
        assertEquals(1, occurrences(source, "atomicAdd(counters.activeCount, 1u)"),
                "the collision source must keep exactly one active-set append");
    }

    @Test
    void collisionProgramKeepsTheEmissionAndIntegrationRulesOfTheIndirectProgram()
            throws IOException {
        String indirect = resource(INDIRECT);
        String collision = resource(COLLISION);
        List<String> sharedRules = List.of(
                "particle.velocitySeed.y -= uGravity * uDeltaSeconds;",
                "particle.positionLife.xyz += particle.velocitySeed.xyz * uDeltaSeconds;",
                "particle.positionLife.w -= uDeltaSeconds;",
                "if (particle.positionLife.w <= 0.0 && emissionOffset < uEmitCount) {",
                "uint slot = atomicAdd(counters.activeCount, 1u);",
                "if (slot < uint(uCapacity)) {",
                "activeIndices[slot] = index;",
                "particle.positionLife = vec4(mix(uSpawnMin, uSpawnMax, vec3(x, y, z)), "
                        + "uLifetimeSeconds);",
                "return float(state) / 4294967295.0;");
        for (String rule : sharedRules) {
            assertTrue(indirect.contains(rule),
                    "the accepted #27 update lost a rule: " + rule);
            assertTrue(collision.contains(rule),
                    "the collision variant diverged from #27: " + rule);
        }
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = source.indexOf(needle);
        while (index >= 0) {
            count++;
            index = source.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String resource(String path) throws IOException {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String path) throws IOException {
        try (InputStream stream = GpuParticleCollisionShaderTest.class
                .getResourceAsStream(path)) {
            assertTrue(stream != null, "missing shader resource " + path);
            return stream.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
    }
}
