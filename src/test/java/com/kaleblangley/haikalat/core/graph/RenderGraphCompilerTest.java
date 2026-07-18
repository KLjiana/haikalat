package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.GlException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RenderGraphCompilerTest {
    @Test
    void compilesDependenciesInStableDeclarationOrder() {
        CompiledRenderGraph graph = RenderGraphCompiler.compile(List.of(
                pass("geometry"),
                pass("shadow"),
                pass("lighting", "geometry", "shadow"),
                pass("overlay", "geometry")));

        assertEquals(List.of("geometry", "shadow", "lighting", "overlay"), graph.passNames());
        assertThrows(UnsupportedOperationException.class, () -> graph.passNames().add("late"));
    }

    @Test
    void rejectsMissingAndDuplicateDependenciesAtCompileTime() {
        GlException missing = assertThrows(GlException.class, () -> RenderGraphCompiler.compile(List.of(
                pass("lighting", "geometry"))));
        assertEquals("RenderGraph pass lighting depends on missing pass geometry", missing.getMessage());

        GlException duplicate = assertThrows(GlException.class, () -> RenderGraphCompiler.compile(List.of(
                pass("geometry"), pass("lighting", "geometry", "geometry"))));
        assertEquals("RenderGraph pass lighting declares duplicate dependency geometry", duplicate.getMessage());
    }

    @Test
    void rejectsDuplicatePassNamesAndCycles() {
        assertThrows(GlException.class, () -> RenderGraphCompiler.compile(List.of(
                pass("geometry"), pass("geometry"))));
        assertThrows(GlException.class, () -> RenderGraphCompiler.compile(List.of(
                pass("a", "b"), pass("b", "a"))));
    }

    private static RenderGraphCompiler.PassSpec pass(String name, String... dependencies) {
        return new RenderGraphCompiler.PassSpec(name, List.of(dependencies));
    }
}
