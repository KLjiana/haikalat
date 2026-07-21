package com.kaleblangley.haikalat.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.lwjgl.opengl.GL11.GL_INVALID_VALUE;
import static org.lwjgl.opengl.GL43.GL_BUFFER;
import static org.lwjgl.opengl.GL43.GL_DEBUG_SEVERITY_HIGH;
import static org.lwjgl.opengl.GL43.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43.GL_DEBUG_TYPE_ERROR;

class GlDebugTest {
    @Test
    void errorNameMapsKnownErrors() {
        assertEquals("GL_INVALID_VALUE", GlDebug.errorName(GL_INVALID_VALUE));
        assertEquals("GL_ERROR_UNKNOWN", GlDebug.errorName(-1));
    }

    @Test
    void debugNamesMapKnownConstants() {
        assertEquals("API", GlDebug.sourceName(GL_DEBUG_SOURCE_API));
        assertEquals("ERROR", GlDebug.typeName(GL_DEBUG_TYPE_ERROR));
        assertEquals("HIGH", GlDebug.severityName(GL_DEBUG_SEVERITY_HIGH));
        assertEquals("BUFFER", GlDebug.objectIdentifierName(GL_BUFFER));
    }

    @Test
    void debugCallsAreNoopsWithoutCurrentContext() {
        assertFalse(GlDebug.hasCurrentContext());
        assertEquals(GlDebug.ContextInfo.UNAVAILABLE, GlDebug.contextInfo());
        assertFalse(GlDebug.enableDebugCallback());
        assertDoesNotThrow(() -> GlDebug.checkError("unit"));
        assertDoesNotThrow(() -> GlDebug.assertNoError("unit"));
        assertDoesNotThrow(() -> GlDebug.labelObject(GL_BUFFER, 1, "buffer"));
    }
}
