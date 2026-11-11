package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the GPU-particle shader contracts that no JVM-only assertion can
 * observe at runtime: binding points, the atomic active append bound to
 * capacity, the indirect command alias, the replay clear branch and the
 * active-index indirection in the draw stage.
 */
class GpuParticleIndirectShaderTest {
    private static final String UPDATE = "/shaders/render3d/vfx/gpu-particle-indirect.comp";
    private static final String RESET = "/shaders/render3d/vfx/gpu-particle-reset.comp";
    private static final String VERTEX = "/shaders/render3d/vfx/gpu-particle-indirect.vert";
    private static final String GEOMETRY = "/shaders/render3d/vfx/gpu-particle-indirect.geom";
    private static final String FRAGMENT = "/shaders/render3d/vfx/gpu-particle-indirect.frag";

    @Test
    void updatePassAppendsTheBoundedActiveSetAndAliasesTheIndirectCommand() throws IOException {
        String source = resource(UPDATE);

        assertTrue(source.contains("layout (std430, binding = 0) buffer ParticleBuffer"));
        assertTrue(source.contains("layout (std430, binding = 1) buffer CounterBuffer"));
        assertTrue(source.contains("layout (std430, binding = 2) buffer ActiveIndexBuffer"));

        assertTrue(source.contains("uint slot = atomicAdd(counters.activeCount, 1u);"));
        assertTrue(source.contains("if (slot < uint(uCapacity))"));
        assertTrue(source.contains("activeIndices[slot] = index;"),
                "the draw list must contain storage indices, not draw-order vertices");

        assertTrue(source.contains("if (uClearParticles != 0)"),
                "replay must clear slots without reading stale storage");
        assertTrue(source.contains("particle.positionLife.w -= uDeltaSeconds;"));
        assertTrue(source.contains("if (particle.positionLife.w > 0.0)"),
                "only positive remaining lifetimes may enter the active set");
        assertTrue(source.contains("particle.velocitySeed.y -= uGravity * uDeltaSeconds;"));
        assertTrue(source.contains("if (emissionOffset < 0)"));
        assertTrue(source.contains("emissionOffset += uCapacity;"),
                "the emission window must wrap across the ring end");
        assertFalse(source.contains("gl_VertexID"));
    }

    @Test
    void resetPassReArmsTheIndirectCommandBeforeTheUpdate() throws IOException {
        String source = resource(RESET);

        assertTrue(source.contains("layout (std430, binding = 1) buffer CounterBuffer"));
        assertTrue(source.contains("counters.activeCount = 0u;"));
        assertTrue(source.contains("counters.instanceCount = 1u;"));
        assertTrue(source.contains("counters.firstVertex = 0u;"));
        assertTrue(source.contains("counters.baseInstance = 0u;"));
    }

    @Test
    void drawStagesConsumeTheActiveListAndCullInvalidOrBehindCameraEntries() throws IOException {
        String vertex = resource(VERTEX);
        assertTrue(vertex.contains("uint index = activeIndices[vertex];"));
        assertTrue(vertex.contains("if (vertex >= uint(uCapacity))"));
        assertTrue(vertex.contains("if (index >= uint(uCapacity))"));
        assertTrue(vertex.contains("uViewProjection * vec4(particle.positionLife.xyz, 1.0)"));
        assertTrue(vertex.contains("uColor.a * alpha"));
        assertFalse(vertex.contains("atomicAdd"),
                "the draw stage must be read-only over particle storage");

        String geometry = resource(GEOMETRY);
        assertTrue(geometry.contains("if (gl_in[0].gl_Position.w <= 0.0)"));
        assertTrue(geometry.contains("uniform mat4 uViewProjection;"));
        assertTrue(geometry.contains("uParticleSize * uViewProjection[1][1]"),
                "the clip offset must use the projection scale, not center.w");
        assertFalse(geometry.contains("uParticleSize * center.w"),
                "multiplying the clip offset by center.w cancels in NDC and turns "
                        + "the size into a constant screen fraction");
        assertTrue(geometry.contains("layout (triangle_strip, max_vertices = 4) out;"));

        String fragment = resource(FRAGMENT);
        assertTrue(fragment.contains("if (radiusSquared > 1.0)"));
        assertTrue(fragment.contains("discard;"));
        assertTrue(fragment.contains("vec4(gColor.rgb * falloff * gColor.a, gColor.a * falloff)"));
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = GpuParticleIndirectShaderTest.class.getResourceAsStream(path)) {
            assertTrue(stream != null, "missing shader resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
