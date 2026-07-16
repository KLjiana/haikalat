package com.kaleblangley.haikalat.integration;

import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;
import org.lwjgl.util.harfbuzz.HarfBuzz;
import org.lwjgl.util.harfbuzz.hb_glyph_info_t;
import org.lwjgl.util.yoga.YGMeasureFuncI;
import org.lwjgl.util.yoga.Yoga;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 验证 UI native binding 在普通无桌面测试类路径中可见，但不初始化其 native library。 */
class NativeUiDependencyVisibilityTest {
    private static final String EXPECTED_LWJGL_VERSION = "3.3.3";

    @Test
    void requiredBindingsAndEntryPointsAreVisibleWithoutNativeInitialization() throws ReflectiveOperationException {
        ClassLoader loader = NativeUiDependencyVisibilityTest.class.getClassLoader();

        assertAll(
                () -> assertSame(Yoga.class, Class.forName(Yoga.class.getName(), false, loader)),
                () -> assertSame(FreeType.class, Class.forName(FreeType.class.getName(), false, loader)),
                () -> assertSame(HarfBuzz.class, Class.forName(HarfBuzz.class.getName(), false, loader)),
                () -> assertEquals(EXPECTED_LWJGL_VERSION, Yoga.class.getPackage().getSpecificationVersion()),
                () -> assertEquals(EXPECTED_LWJGL_VERSION, FreeType.class.getPackage().getSpecificationVersion()),
                () -> assertEquals(EXPECTED_LWJGL_VERSION, HarfBuzz.class.getPackage().getSpecificationVersion()),
                () -> assertEquals(long.class, Yoga.class.getMethod("YGNodeNew").getReturnType()),
                () -> assertEquals(int.class,
                        FreeType.class.getMethod("FT_Init_FreeType", PointerBuffer.class).getReturnType()),
                () -> assertEquals(long.class, HarfBuzz.class.getMethod("hb_buffer_create").getReturnType()),
                () -> assertEquals("org.lwjgl.util.yoga", YGMeasureFuncI.class.getPackageName()),
                () -> assertEquals("org.lwjgl.util.freetype", FT_Face.class.getPackageName()),
                () -> assertEquals("org.lwjgl.util.harfbuzz", hb_glyph_info_t.class.getPackageName())
        );
    }
}
