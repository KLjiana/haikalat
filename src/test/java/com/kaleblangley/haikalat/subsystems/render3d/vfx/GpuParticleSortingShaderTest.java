package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the sorted-particle shader contracts that no JVM-only assertion can
 * observe at runtime: the GPU-side counter read, the sentinel padding rule, the
 * order-preserving float key, the one-pair-per-invocation bitonic step, the
 * empty/single skip guard and the sorted prefix consumed by the draw.
 *
 * <p>The disabled path is guarded separately: it must keep compiling the
 * untouched #27 draw shader, which has no sort binding at all.</p>
 */
class GpuParticleSortingShaderTest {
    private static final String SORT_KEY =
            "/shaders/render3d/vfx/gpu-particle-sort-key.comp";
    private static final String SORT_STEP =
            "/shaders/render3d/vfx/gpu-particle-sort-step.comp";
    private static final String SORTED_VERTEX =
            "/shaders/render3d/vfx/gpu-particle-sorted.vert";
    private static final String INDIRECT_VERTEX =
            "/shaders/render3d/vfx/gpu-particle-indirect.vert";

    @Test
    void preparationPassDerivesTheKeyFromTheViewAndPadsWithDirectionAwareSentinels()
            throws IOException {
        String source = resource(SORT_KEY);

        assertTrue(source.contains("layout (std430, binding = 1) readonly buffer CounterBuffer"));
        assertTrue(source.contains("layout (std430, binding = 3) buffer SortKeyBuffer"));
        assertTrue(source.contains("layout (std430, binding = 4) buffer SortedIndexBuffer"));
        assertTrue(source.contains("uint count = min(counters.activeCount, capacity);"),
                "the active count must be clamped and never read on the CPU");
        assertTrue(source.contains("uint sorted = min(count, uint(uMaximumSortedParticles));"),
                "the sort bound must cap the sorted prefix");
        assertTrue(source.contains("uint sentinel = MAXIMUM_KEY;"),
                "padding must sort after every valid key in either direction");
        assertTrue(source.contains("sortKeys[position] = uvec2(sentinel, 0u);"));
        assertTrue(source.contains("sortedIndices[position] = SENTINEL_INDEX;"));
        assertTrue(source.contains("uint primary = orderableKey(metric);"));
        assertTrue(source.contains("primary = ~primary;"),
                "far-to-near must invert the order-preserving key, not the compare");
        assertTrue(source.contains("uSortKey == 0 ? -viewPosition.z : length(viewPosition.xyz)"));
        assertTrue(source.contains("uint tie = uStableTies != 0 ? position : 0u;"),
                "the stable policy must carry the active-list position as tie key");
        assertTrue(source.contains("uniform mat4 uView;"));
        assertFalse(source.contains("atomicAdd"),
                "the preparation pass must not append to the active set");
        assertFalse(source.contains("counters.activeCount ="),
                "the sort must not rewrite the count that owns the indirect command");
    }

    @Test
    void bitonicStepOwnsOnePairAndSkipsEmptyOrSingleSetsOnTheGpu() throws IOException {
        String source = resource(SORT_STEP);

        assertTrue(source.contains("uint pairs = uint(uSortCapacity) >> 1;"));
        assertTrue(source.contains("if (invocation >= pairs)"));
        assertTrue(source.contains("if (min(counters.activeCount, uint(uMaximumSortedParticles)) <= 1u)"),
                "empty and single-element sets must not touch scratch memory");
        assertTrue(source.contains("uint low = (invocation / distance) * (distance << 1) + (invocation % distance);"));
        assertTrue(source.contains("uint high = low + distance;"));
        assertTrue(source.contains("bool ascending = (low & blockSize) == 0u;"));
        assertTrue(source.contains("bool exchange = ascending ? keyLess(highKey, lowKey) : keyLess(lowKey, highKey);"));
        assertTrue(source.contains("sortKeys[low] = highKey;"));
        assertTrue(source.contains("sortKeys[high] = lowKey;"));
        assertTrue(source.contains("sortedIndices[low] = sortedIndices[high];"));
        assertTrue(source.contains("bool keyLess(uvec2 left, uvec2 right)"));
        assertFalse(source.contains("barrier()"),
                "steps depend on global order, so the owner records a storage barrier between them");
        assertFalse(source.contains("atomicAdd"));
    }

    @Test
    void sortedDrawConsumesTheSortedPrefixAndFallsBackToTheActiveList() throws IOException {
        String source = resource(SORTED_VERTEX);

        assertTrue(source.contains("layout (std430, binding = 1) readonly buffer CounterBuffer"));
        assertTrue(source.contains("layout (std430, binding = 3) readonly buffer SortedIndexBuffer"));
        assertTrue(source.contains("uint sorted = min(count, uint(uMaximumSortedParticles));"));
        assertTrue(source.contains("uint index = vertex < sorted ? sortedIndices[vertex] : activeIndices[vertex];"),
                "a partial sort must fall back to the published active order");
        assertTrue(source.contains("if (index >= uint(uCapacity))"),
                "sentinel and invalid indices must stay off-screen");
        assertTrue(source.contains("uViewProjection * vec4(particle.positionLife.xyz, 1.0)"));
        assertTrue(source.contains("uColor.a * alpha"));
        assertFalse(source.contains("atomicAdd"));
        assertFalse(source.contains("sortKeys"),
                "the draw must consume indices only, not the sort scratch");
    }

    @Test
    void disabledDrawPathStillUsesTheUntouchedActiveIndexShader() throws IOException {
        String source = resource(INDIRECT_VERTEX);

        assertTrue(source.contains("uint index = activeIndices[vertex];"),
                "the disabled path must keep reading the published active list directly");
        assertFalse(source.contains("sortedIndices"),
                "a disabled owner must not need sort scratch in the legacy draw shader");
        assertFalse(source.contains("counters"),
                "the legacy draw shader must not depend on the sort counter binding");
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = GpuParticleSortingShaderTest.class.getResourceAsStream(path)) {
            assertTrue(stream != null, "missing shader resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
