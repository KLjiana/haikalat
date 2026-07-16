package com.kaleblangley.haikalat.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.freetype.FreeType;
import org.lwjgl.util.harfbuzz.HarfBuzz;
import org.lwjgl.util.yoga.Yoga;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 验证 UI native 依赖能够加载、报告基线版本，并按所有权逆序释放。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class NativeUiDependencyGlTest {
    private static final String EXPECTED_LWJGL_VERSION = "3.3.3";
    private static final String EXPECTED_FREETYPE_VERSION = "2.13.2";
    private static final String EXPECTED_HARFBUZZ_VERSION = "8.2.0";

    @Test
    void yogaCreatesNodeAndReleasesNodeBeforeConfig() {
        long config = 0L;
        long node = 0L;
        AssertionError primaryFailure = null;
        try {
            assertEquals(EXPECTED_LWJGL_VERSION, Yoga.class.getPackage().getSpecificationVersion(),
                    "Yoga binding specification version does not match the LWJGL baseline");
            config = Yoga.YGConfigNew();
            assertNotEquals(0L, config, "Yoga YGConfigNew returned a null native handle");
            node = Yoga.YGNodeNewWithConfig(config);
            assertNotEquals(0L, node, "Yoga YGNodeNewWithConfig returned a null native handle");
        } catch (Throwable failure) {
            primaryFailure = nativeFailure("Yoga", "create/version", failure);
            throw primaryFailure;
        } finally {
            try {
                if (node != 0L) Yoga.YGNodeFree(node);
                if (config != 0L) Yoga.YGConfigFree(config);
            } catch (Throwable cleanupFailure) {
                throwCleanupFailure(primaryFailure, "Yoga", "YGNodeFree -> YGConfigFree", cleanupFailure);
            }
        }
    }

    @Test
    void freeTypeCreatesLibraryReportsVersionAndDestroysLibrary() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer libraryPointer = stack.mallocPointer(1);
            int initError = FreeType.FT_Init_FreeType(libraryPointer);
            assertEquals(0, initError, () -> "FreeType FT_Init_FreeType failed with error " + initError);

            long library = libraryPointer.get(0);
            assertNotEquals(0L, library, "FreeType initialized successfully but returned a null library handle");
            AssertionError primaryFailure = null;
            try {
                IntBuffer major = stack.mallocInt(1);
                IntBuffer minor = stack.mallocInt(1);
                IntBuffer patch = stack.mallocInt(1);
                FreeType.FT_Library_Version(library, major, minor, patch);
                String actualVersion = major.get(0) + "." + minor.get(0) + "." + patch.get(0);
                assertEquals(EXPECTED_FREETYPE_VERSION, actualVersion,
                        "FreeType native version does not match the LWJGL 3.3.3 baseline");
            } catch (Throwable failure) {
                primaryFailure = nativeFailure("FreeType", "version query", failure);
                throw primaryFailure;
            } finally {
                int closeError = FreeType.FT_Done_FreeType(library);
                if (closeError != 0) {
                    throwCleanupFailure(primaryFailure, "FreeType", "FT_Done_FreeType",
                            new AssertionError("FreeType FT_Done_FreeType failed with error " + closeError));
                }
            }
        }
    }

    @Test
    void harfBuzzCreatesBufferReportsVersionAndDestroysBuffer() {
        long buffer = 0L;
        AssertionError primaryFailure = null;
        try {
            String actualVersion = HarfBuzz.hb_version_string();
            assertEquals(EXPECTED_HARFBUZZ_VERSION, actualVersion,
                    "HarfBuzz native version does not match the LWJGL 3.3.3 baseline");
            buffer = HarfBuzz.hb_buffer_create();
            assertNotEquals(0L, buffer, "HarfBuzz hb_buffer_create returned a null native handle");
        } catch (Throwable failure) {
            primaryFailure = nativeFailure("HarfBuzz", "create/version", failure);
            throw primaryFailure;
        } finally {
            try {
                if (buffer != 0L) HarfBuzz.hb_buffer_destroy(buffer);
            } catch (Throwable cleanupFailure) {
                throwCleanupFailure(primaryFailure, "HarfBuzz", "hb_buffer_destroy", cleanupFailure);
            }
        }
    }

    private static AssertionError nativeFailure(String component, String operation, Throwable cause) {
        return new AssertionError(component + " native smoke failed during " + operation, cause);
    }

    private static void throwCleanupFailure(
            AssertionError primaryFailure,
            String component,
            String closeOrder,
            Throwable cause
    ) {
        AssertionError cleanupFailure = new AssertionError(
                component + " native cleanup failed during " + closeOrder, cause);
        if (primaryFailure != null) {
            primaryFailure.addSuppressed(cleanupFailure);
            return;
        }
        throw cleanupFailure;
    }
}
