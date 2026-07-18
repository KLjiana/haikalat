package com.kaleblangley.haikalat.demo;

import org.junit.jupiter.api.Test;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsLevel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearnOpenGlDemoOptionsTest {
    @Test
    void interactiveRunUsesBasicDiagnosticsByDefault() {
        LearnOpenGlDemo.DemoOptions options = assertDoesNotThrow(
                () -> LearnOpenGlDemo.DemoOptions.parse(new String[0]));

        assertEquals(-1, options.maxFrames());
        assertEquals(DiagnosticsLevel.BASIC, options.diagnosticsLevel());
    }

    @Test
    void artificialFailureInjectionIsNotAUserFacingDemoOption() {
        assertThrows(IllegalArgumentException.class,
                () -> LearnOpenGlDemo.DemoOptions.parse(
                        new String[]{"--diagnostics-fail-frame=4"}));
    }

    @Test
    void diagnosticsExportIsConfinedToBuildDiagnostics() {
        LearnOpenGlDemo.DemoOptions options = LearnOpenGlDemo.DemoOptions.parse(
                new String[]{"--diagnostics-export=build/diagnostics/manual.json"});

        assertTrue(options.diagnosticsExport().isAbsolute());
        assertEquals(DiagnosticsLevel.BASIC, options.diagnosticsLevel());
        assertTrue(options.diagnosticsExport().startsWith(
                java.nio.file.Path.of("build", "diagnostics").toAbsolutePath().normalize()));
        assertThrows(IllegalArgumentException.class,
                () -> LearnOpenGlDemo.DemoOptions.parse(
                        new String[]{"--diagnostics-export=diagnostics.json"}));
        assertThrows(IllegalArgumentException.class,
                () -> LearnOpenGlDemo.DemoOptions.parse(
                        new String[]{"--diagnostics-export=build/diagnostics/../../outside.json"}));
    }
}
