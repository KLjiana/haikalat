package com.kaleblangley.haikalat.subsystems.text;

import org.lwjgl.CLongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_Glyph_Metrics;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FT_MM_Var;
import org.lwjgl.util.freetype.FT_Size_Metrics;
import org.lwjgl.util.freetype.FT_Var_Axis;
import org.lwjgl.util.freetype.FreeType;
import org.lwjgl.util.harfbuzz.HarfBuzz;
import org.lwjgl.util.harfbuzz.hb_font_extents_t;
import org.lwjgl.util.harfbuzz.hb_font_get_font_extents_func_t;
import org.lwjgl.util.harfbuzz.hb_font_get_glyph_advance_func_t;
import org.lwjgl.util.harfbuzz.hb_font_get_glyph_extents_func_t;
import org.lwjgl.util.harfbuzz.hb_font_get_nominal_glyph_func_t;
import org.lwjgl.util.harfbuzz.hb_glyph_extents_t;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * 线程封闭的 FT face 与其 HB font 所有者。
 *
 * <p>LWJGL 3.3.3 的预编译 HarfBuzz native 不保证导出可选 {@code hb-ft} 符号，因此这里使用
 * 等价的 HB blob/face/font 所有权链，并用线程封闭的 font funcs 从同一 FT face 提供 cmap 和度量。
 * 关闭顺序为 HB font → font funcs/callback → HB face → HB blob → FT face → backing direct buffer。</p>
 */
public final class FontFace implements AutoCloseable {
    private final FontManager manager;
    private final FontFamily family;
    private final FontFaceId id;
    private final String nativeFamilyName;
    private final String styleName;
    private final long glyphCount;
    private final int unitsPerEm;
    private final BitSet checkedCoverage = new BitSet(Character.MAX_CODE_POINT + 1);
    private final BitSet presentCoverage = new BitSet(Character.MAX_CODE_POINT + 1);
    private ByteBuffer backingBuffer;
    private FT_Face ftFace;
    private long hbBlob;
    private long hbFace;
    private long hbFont;
    private NativeFontFunctions nativeFontFunctions;
    private int currentPpem;
    private boolean variationConfigurationLocked;
    private boolean closed;

    private FontFace(FontManager manager, FontFamily family, FontFaceId id,
                     ByteBuffer backingBuffer, FT_Face ftFace,
                     long hbBlob, long hbFace, long hbFont,
                     NativeFontFunctions nativeFontFunctions) {
        this.manager = manager;
        this.family = family;
        this.id = id;
        this.backingBuffer = backingBuffer;
        this.ftFace = ftFace;
        this.hbBlob = hbBlob;
        this.hbFace = hbFace;
        this.hbFont = hbFont;
        this.nativeFontFunctions = nativeFontFunctions;
        String faceFamily = ftFace.family_nameString();
        String faceStyle = ftFace.style_nameString();
        this.nativeFamilyName = faceFamily == null || faceFamily.isBlank() ? family.name() : faceFamily;
        this.styleName = faceStyle == null || faceStyle.isBlank() ? "Regular" : faceStyle;
        this.glyphCount = ftFace.num_glyphs();
        this.unitsPerEm = Short.toUnsignedInt(ftFace.units_per_EM());
    }

    static FontFace create(FontManager manager, FontFamily family, FontFaceId id,
                           long library, ByteBuffer source, int faceIndex) {
        ByteBuffer backing = MemoryUtil.memAlloc(source.remaining());
        FT_Face face = null;
        long hbBlob = 0L;
        long hbFace = 0L;
        long hbFont = 0L;
        NativeFontFunctions nativeFunctions = null;
        RuntimeException failure = null;
        try {
            backing.put(source.duplicate()).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer result = stack.mallocPointer(1);
                int error = FreeType.FT_New_Memory_Face(library, backing, faceIndex, result);
                if (error != 0) {
                    throw new FontNativeException("FT_New_Memory_Face", error);
                }
                long faceAddress = result.get(0);
                if (faceAddress == 0L) {
                    throw new FontNativeException("FT_New_Memory_Face", "returned a null face handle");
                }
                face = FT_Face.create(faceAddress);
            }
            hbBlob = HarfBuzz.hb_blob_create(backing, HarfBuzz.HB_MEMORY_MODE_READONLY, 0L, null);
            if (hbBlob == 0L) {
                throw new FontNativeException("hb_blob_create", "returned a null blob handle");
            }
            hbFace = HarfBuzz.hb_face_create(hbBlob, faceIndex);
            if (hbFace == 0L) {
                throw new FontNativeException("hb_face_create", "returned a null face handle");
            }
            hbFont = HarfBuzz.hb_font_create(hbFace);
            if (hbFont == 0L) {
                throw new FontNativeException("hb_font_create", "returned a null font handle");
            }
            nativeFunctions = NativeFontFunctions.create(face);
            nativeFunctions.install(hbFont);
            int upem = HarfBuzz.hb_face_get_upem(hbFace);
            if (upem > 0) {
                HarfBuzz.hb_font_set_scale(hbFont, upem, upem);
            }
            return new FontFace(manager, family, id, backing, face,
                    hbBlob, hbFace, hbFont, nativeFunctions);
        } catch (RuntimeException | Error creationFailure) {
            failure = creationFailure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("Font face creation failed", creationFailure);
            if (hbFont != 0L) {
                try {
                    HarfBuzz.hb_font_destroy(hbFont);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (nativeFunctions != null) {
                try {
                    nativeFunctions.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (hbFace != 0L) {
                try {
                    HarfBuzz.hb_face_destroy(hbFace);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (hbBlob != 0L) {
                try {
                    HarfBuzz.hb_blob_destroy(hbBlob);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (face != null) {
                try {
                    int error = FreeType.FT_Done_Face(face);
                    if (error != 0) {
                        failure.addSuppressed(new FontNativeException("FT_Done_Face", error));
                    }
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                MemoryUtil.memFree(backing);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public FontFaceId id() {
        return id;
    }

    public FontFamily family() {
        return family;
    }

    /** 返回字体文件内声明的 family name。 */
    public String nativeFamilyName() {
        return nativeFamilyName;
    }

    public String styleName() {
        return styleName;
    }

    public long glyphCount() {
        return glyphCount;
    }

    public int unitsPerEm() {
        return unitsPerEm;
    }

    /** 返回字体声明的可变轴；普通静态字体返回空列表。 */
    public List<FontVariationAxis> variationAxes() {
        checkUsable("FontFace.variationAxes");
        if (!FreeType.FT_HAS_MULTIPLE_MASTERS(ftFace)) {
            return List.of();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer result = stack.mallocPointer(1);
            int error = FreeType.FT_Get_MM_Var(ftFace, result);
            if (error != 0) {
                throw new FontNativeException("FT_Get_MM_Var", error);
            }
            FT_MM_Var variables = FT_MM_Var.create(result.get(0));
            try {
                FT_Var_Axis.Buffer axes = variables.axis();
                ArrayList<FontVariationAxis> values = new ArrayList<>(variables.num_axis());
                for (int index = 0; index < variables.num_axis(); index++) {
                    FT_Var_Axis axis = axes.get(index);
                    values.add(new FontVariationAxis(axisTag(axis.tag()),
                            fromFixed(axis.minimum()), fromFixed(axis.def()),
                            fromFixed(axis.maximum())));
                }
                return List.copyOf(values);
            } finally {
                doneVariationMetadata(variables);
            }
        }
    }

    /**
     * 在首次 shaping、度量或光栅化前设置一个可变字体设计坐标。
     *
     * <p>轴坐标会改变 glyph 度量和 atlas 内容，因此字体开始参与文本流水线后禁止再修改。
     * 不存在的轴和越界坐标会抛出 {@link IllegalArgumentException}。</p>
     *
     * @param tag 四字符 OpenType 轴标签，例如 {@code wght}
     * @param value 轴设计坐标
     * @return 当前字体，便于注册后连续配置
     */
    public FontFace variationCoordinate(String tag, float value) {
        checkUsable("FontFace.variationCoordinate");
        long requestedTag = axisTag(Objects.requireNonNull(tag, "tag"));
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("variation coordinate must be finite");
        }
        if (variationConfigurationLocked) {
            throw new IllegalStateException(
                    "variation coordinates must be configured before shaping, measuring or rasterizing");
        }
        if (!FreeType.FT_HAS_MULTIPLE_MASTERS(ftFace)) {
            throw new IllegalArgumentException("font has no variation axis " + tag);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer result = stack.mallocPointer(1);
            int error = FreeType.FT_Get_MM_Var(ftFace, result);
            if (error != 0) {
                throw new FontNativeException("FT_Get_MM_Var", error);
            }
            FT_MM_Var variables = FT_MM_Var.create(result.get(0));
            try {
                int axisCount = variables.num_axis();
                CLongBuffer coordinates = stack.mallocCLong(axisCount);
                error = FreeType.FT_Get_Var_Design_Coordinates(ftFace, coordinates);
                if (error != 0) {
                    throw new FontNativeException("FT_Get_Var_Design_Coordinates", error);
                }

                int requestedIndex = -1;
                long requestedFixed = toFixed(value);
                FT_Var_Axis.Buffer axes = variables.axis();
                for (int index = 0; index < axisCount; index++) {
                    FT_Var_Axis axis = axes.get(index);
                    if ((axis.tag() & 0xffff_ffffL) == requestedTag) {
                        if (requestedFixed < axis.minimum() || requestedFixed > axis.maximum()) {
                            throw new IllegalArgumentException("variation coordinate " + tag + '=' + value
                                    + " is outside [" + fromFixed(axis.minimum()) + ", "
                                    + fromFixed(axis.maximum()) + ']');
                        }
                        requestedIndex = index;
                        break;
                    }
                }
                if (requestedIndex < 0) {
                    throw new IllegalArgumentException("font has no variation axis " + tag);
                }
                if (coordinates.get(requestedIndex) == requestedFixed) {
                    return this;
                }
                coordinates.put(requestedIndex, requestedFixed);
                error = FreeType.FT_Set_Var_Design_Coordinates(ftFace, coordinates);
                if (error != 0) {
                    throw new FontNativeException("FT_Set_Var_Design_Coordinates", error);
                }
                currentPpem = 0;
                manager.faceConfigurationChanged(this);
                return this;
            } finally {
                doneVariationMetadata(variables);
            }
        }
    }

    /** 查询 Unicode scalar 是否具有非 .notdef cmap glyph，并缓存结果。 */
    public boolean supportsCodePoint(int codePoint) {
        checkUsable("FontFace.supportsCodePoint");
        requireUnicodeScalar(codePoint);
        if (!checkedCoverage.get(codePoint)) {
            boolean present = FreeType.FT_Get_Char_Index(ftFace, codePoint) != 0;
            checkedCoverage.set(codePoint);
            if (present) {
                presentCoverage.set(codePoint);
            }
        }
        return presentCoverage.get(codePoint);
    }

    /** 返回 Unicode scalar 对应 glyph id；缺字返回 0（明确 tofu）。 */
    public int glyphIndex(int codePoint) {
        checkUsable("FontFace.glyphIndex");
        requireUnicodeScalar(codePoint);
        return FreeType.FT_Get_Char_Index(ftFace, codePoint);
    }

    /** 判断该 face 是否覆盖完整 UTF-16 cluster。 */
    public boolean covers(String text, int startUtf16, int endUtf16) {
        checkUsable("FontFace.covers");
        Objects.requireNonNull(text, "text");
        requireRange(text, startUtf16, endUtf16);
        for (int offset = startUtf16; offset < endUtf16; ) {
            int codePoint = text.codePointAt(offset);
            if (!isDefaultIgnorableForCoverage(codePoint) && !supportsCodePoint(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    /** 返回指定 ppem 下的 font metrics。 */
    public FontMetrics metrics(int ppem) {
        variationConfigurationLocked = true;
        setPixelSize(ppem);
        FT_Size_Metrics metrics = ftFace.size().metrics();
        float ascent = Math.max(0.0f, metrics.ascender() / 64.0f);
        float descent = Math.max(0.0f, -metrics.descender() / 64.0f);
        float lineHeight = Math.max(0.0f, metrics.height() / 64.0f);
        if (lineHeight == 0.0f) {
            lineHeight = Math.max(1.0f, ascent + descent);
        }
        float maximumAdvance = Math.max(0.0f, metrics.max_advance() / 64.0f);
        return new FontMetrics(ppem, ascent, descent, lineHeight, maximumAdvance);
    }

    /** 光栅化 glyph 为紧密 R8 coverage。 */
    public GlyphBitmap rasterize(GlyphKey key) {
        checkUsable("FontFace.rasterize");
        variationConfigurationLocked = true;
        Objects.requireNonNull(key, "key");
        if (!id.equals(key.faceId())) {
            throw new IllegalArgumentException("Glyph key belongs to a different font face");
        }
        setPixelSize(key.ppem());
        int loadFlags = loadFlags(key);
        int error = FreeType.FT_Load_Glyph(ftFace, key.glyphId(), loadFlags);
        if (error != 0) {
            throw new FontNativeException("FT_Load_Glyph", error);
        }
        FT_GlyphSlot slot = ftFace.glyph();
        int renderMode = key.rasterMode() == GlyphRasterMode.MONOCHROME
                ? FreeType.FT_RENDER_MODE_MONO
                : key.hinting() == GlyphHinting.LIGHT
                ? FreeType.FT_RENDER_MODE_LIGHT
                : FreeType.FT_RENDER_MODE_NORMAL;
        error = FreeType.FT_Render_Glyph(slot, renderMode);
        if (error != 0) {
            throw new FontNativeException("FT_Render_Glyph", error);
        }
        FT_Bitmap bitmap = slot.bitmap();
        int width = bitmap.width();
        int height = bitmap.rows();
        byte[] coverage = copyCoverage(bitmap, width, height);
        return new GlyphBitmap(key, width, height, slot.bitmap_left(), slot.bitmap_top(),
                slot.advance().x() / 64.0f, slot.advance().y() / 64.0f, coverage);
    }

    /** 返回 face 是否已关闭；该诊断不调用 native 对象。 */
    public boolean isClosed() {
        return closed;
    }

    /** 由 manager 移除并按正确顺序释放；重复调用幂等。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        manager.closeFace(this);
    }

    long harfBuzzFontHandle() {
        checkUsable("FontFace.harfBuzzFontHandle");
        variationConfigurationLocked = true;
        return hbFont;
    }

    private void doneVariationMetadata(FT_MM_Var variables) {
        int error = FreeType.FT_Done_MM_Var(manager.nativeLibraryHandle(), variables);
        if (error != 0) {
            throw new FontNativeException("FT_Done_MM_Var", error);
        }
    }

    private static String axisTag(long tag) {
        return new String(new char[]{
                (char) ((tag >>> 24) & 0xff),
                (char) ((tag >>> 16) & 0xff),
                (char) ((tag >>> 8) & 0xff),
                (char) (tag & 0xff)});
    }

    private static long axisTag(String tag) {
        if (tag.length() != 4 || tag.chars().anyMatch(value -> value < 0x20 || value > 0x7e)) {
            throw new IllegalArgumentException("variation axis tag must contain four ASCII characters");
        }
        return ((long) tag.charAt(0) << 24)
                | ((long) tag.charAt(1) << 16)
                | ((long) tag.charAt(2) << 8)
                | tag.charAt(3);
    }

    private static float fromFixed(long value) {
        return value / 65536.0f;
    }

    private static long toFixed(float value) {
        double fixed = Math.rint(value * 65536.0);
        if (fixed < Long.MIN_VALUE || fixed > Long.MAX_VALUE) {
            throw new IllegalArgumentException("variation coordinate is outside 16.16 range");
        }
        return (long) fixed;
    }

    void setPixelSize(int ppem) {
        checkUsable("FontFace.setPixelSize");
        if (ppem <= 0) {
            throw new IllegalArgumentException("ppem must be positive");
        }
        if (currentPpem == ppem) {
            return;
        }
        int error = FreeType.FT_Set_Pixel_Sizes(ftFace, 0, ppem);
        if (error != 0) {
            throw new FontNativeException("FT_Set_Pixel_Sizes", error);
        }
        int hbScale = Math.multiplyExact(ppem, 64);
        HarfBuzz.hb_font_set_scale(hbFont, hbScale, hbScale);
        HarfBuzz.hb_font_set_ppem(hbFont, ppem, ppem);
        currentPpem = ppem;
    }

    void beginNativeShape() {
        checkUsable("FontFace.beginNativeShape");
        nativeFontFunctions.clearFailure();
    }

    void finishNativeShape() {
        checkUsable("FontFace.finishNativeShape");
        nativeFontFunctions.throwIfFailed();
    }

    void closeFromManager() {
        manager.checkUsable("FontFace.close");
        if (closed) {
            return;
        }
        RuntimeException failure = null;
        long ownedHbFont = hbFont;
        hbFont = 0L;
        if (ownedHbFont != 0L) {
            try {
                HarfBuzz.hb_font_destroy(ownedHbFont);
            } catch (RuntimeException cleanupFailure) {
                failure = cleanupFailure;
            }
        }
        NativeFontFunctions ownedFunctions = nativeFontFunctions;
        nativeFontFunctions = null;
        if (ownedFunctions != null) {
            try {
                ownedFunctions.close();
            } catch (RuntimeException cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        long ownedHbFace = hbFace;
        hbFace = 0L;
        if (ownedHbFace != 0L) {
            try {
                HarfBuzz.hb_face_destroy(ownedHbFace);
            } catch (RuntimeException cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        long ownedHbBlob = hbBlob;
        hbBlob = 0L;
        if (ownedHbBlob != 0L) {
            try {
                HarfBuzz.hb_blob_destroy(ownedHbBlob);
            } catch (RuntimeException cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        FT_Face ownedFace = ftFace;
        ftFace = null;
        if (ownedFace != null) {
            try {
                int error = FreeType.FT_Done_Face(ownedFace);
                if (error != 0) {
                    failure = appendFailure(failure, new FontNativeException("FT_Done_Face", error));
                }
            } catch (RuntimeException cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        ByteBuffer ownedBacking = backingBuffer;
        backingBuffer = null;
        if (ownedBacking != null) {
            try {
                MemoryUtil.memFree(ownedBacking);
            } catch (RuntimeException cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        closed = true;
        if (failure != null) {
            throw failure;
        }
    }

    private void checkUsable(String operation) {
        manager.checkUsable(operation);
        if (closed) {
            throw new IllegalStateException(operation + " cannot use closed FontFace " + id.value());
        }
    }

    private static int loadFlags(GlyphKey key) {
        int flags = FreeType.FT_LOAD_DEFAULT;
        flags |= switch (key.hinting()) {
            case NONE -> FreeType.FT_LOAD_NO_HINTING;
            case NORMAL -> FreeType.FT_FT_LOAD_TARGET_NORMAL;
            case LIGHT -> FreeType.FT_FT_LOAD_TARGET_LIGHT;
            case MONOCHROME -> FreeType.FT_FT_LOAD_TARGET_MONO | FreeType.FT_LOAD_MONOCHROME;
        };
        if (key.rasterMode() == GlyphRasterMode.MONOCHROME) {
            flags |= FreeType.FT_FT_LOAD_TARGET_MONO | FreeType.FT_LOAD_MONOCHROME;
        }
        return flags;
    }

    private static RuntimeException appendFailure(RuntimeException primary, RuntimeException added) {
        if (primary == null) {
            return added;
        }
        primary.addSuppressed(added);
        return primary;
    }

    /**
     * HarfBuzz core font funcs 到当前 FT face 的桥接。callback 永远捕获异常，shape 返回 Java 后再抛出。
     */
    private static final class NativeFontFunctions implements AutoCloseable {
        private final FT_Face face;
        private hb_font_get_nominal_glyph_func_t nominalGlyph;
        private hb_font_get_glyph_advance_func_t horizontalAdvance;
        private hb_font_get_font_extents_func_t horizontalExtents;
        private hb_font_get_glyph_extents_func_t glyphExtents;
        private long functions;
        private RuntimeException callbackFailure;
        private boolean closed;

        private NativeFontFunctions(FT_Face face, long functions) {
            this.face = face;
            this.functions = functions;
        }

        static NativeFontFunctions create(FT_Face face) {
            long functions = HarfBuzz.hb_font_funcs_create();
            if (functions == 0L) {
                throw new FontNativeException("hb_font_funcs_create", "returned a null handle");
            }
            NativeFontFunctions result = null;
            try {
                result = new NativeFontFunctions(face, functions);
                result.nominalGlyph = hb_font_get_nominal_glyph_func_t.create(result::nominalGlyph);
                result.horizontalAdvance = hb_font_get_glyph_advance_func_t.create(result::horizontalAdvance);
                result.horizontalExtents = hb_font_get_font_extents_func_t.create(result::horizontalExtents);
                result.glyphExtents = hb_font_get_glyph_extents_func_t.create(result::glyphExtents);
                HarfBuzz.hb_font_funcs_set_nominal_glyph_func(functions, result.nominalGlyph, 0L, null);
                HarfBuzz.hb_font_funcs_set_glyph_h_advance_func(
                        functions, result.horizontalAdvance.address(), 0L, null);
                HarfBuzz.hb_font_funcs_set_font_h_extents_func(
                        functions, result.horizontalExtents.address(), 0L, null);
                HarfBuzz.hb_font_funcs_set_glyph_extents_func(functions, result.glyphExtents, 0L, null);
                HarfBuzz.hb_font_funcs_make_immutable(functions);
                return result;
            } catch (RuntimeException | Error failure) {
                if (result != null) {
                    try {
                        result.close();
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                } else {
                    HarfBuzz.hb_font_funcs_destroy(functions);
                }
                throw failure;
            }
        }

        void install(long hbFont) {
            HarfBuzz.hb_font_set_funcs(hbFont, functions, 0L, null);
        }

        void clearFailure() {
            callbackFailure = null;
        }

        void throwIfFailed() {
            RuntimeException failure = callbackFailure;
            callbackFailure = null;
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            RuntimeException failure = null;
            long ownedFunctions = functions;
            functions = 0L;
            if (ownedFunctions != 0L) {
                try {
                    HarfBuzz.hb_font_funcs_destroy(ownedFunctions);
                } catch (RuntimeException cleanupFailure) {
                    failure = cleanupFailure;
                }
            }
            if (nominalGlyph != null) {
                try {
                    nominalGlyph.free();
                } catch (RuntimeException cleanupFailure) {
                    failure = appendFailure(failure, cleanupFailure);
                }
                nominalGlyph = null;
            }
            if (horizontalAdvance != null) {
                try {
                    horizontalAdvance.free();
                } catch (RuntimeException cleanupFailure) {
                    failure = appendFailure(failure, cleanupFailure);
                }
                horizontalAdvance = null;
            }
            if (horizontalExtents != null) {
                try {
                    horizontalExtents.free();
                } catch (RuntimeException cleanupFailure) {
                    failure = appendFailure(failure, cleanupFailure);
                }
                horizontalExtents = null;
            }
            if (glyphExtents != null) {
                try {
                    glyphExtents.free();
                } catch (RuntimeException cleanupFailure) {
                    failure = appendFailure(failure, cleanupFailure);
                }
                glyphExtents = null;
            }
            closed = true;
            if (failure != null) {
                throw failure;
            }
        }

        private int nominalGlyph(long font, long fontData, int unicode,
                                 long glyphAddress, long userData) {
            try {
                int glyph = FreeType.FT_Get_Char_Index(face, Integer.toUnsignedLong(unicode));
                MemoryUtil.memPutInt(glyphAddress, glyph);
                return glyph == 0 ? 0 : 1;
            } catch (Throwable failure) {
                recordFailure("nominal glyph", failure);
                return 0;
            }
        }

        private int horizontalAdvance(long font, long fontData, int glyph, long userData) {
            try {
                int error = FreeType.FT_Load_Glyph(face, glyph, FreeType.FT_LOAD_DEFAULT);
                if (error != 0) {
                    throw new FontNativeException("FT_Load_Glyph(hb advance)", error);
                }
                return Math.toIntExact(face.glyph().advance().x());
            } catch (Throwable failure) {
                recordFailure("horizontal advance", failure);
                return 0;
            }
        }

        private int horizontalExtents(long font, long fontData, long extentsAddress, long userData) {
            try {
                FT_Size_Metrics metrics = face.size().metrics();
                int ascent = Math.toIntExact(metrics.ascender());
                int descent = Math.toIntExact(metrics.descender());
                int lineGap = Math.max(0, Math.toIntExact(metrics.height()) - (ascent - descent));
                hb_font_extents_t.nascender(extentsAddress, ascent);
                hb_font_extents_t.ndescender(extentsAddress, descent);
                hb_font_extents_t.nline_gap(extentsAddress, lineGap);
                return 1;
            } catch (Throwable failure) {
                recordFailure("horizontal extents", failure);
                return 0;
            }
        }

        private int glyphExtents(long font, long fontData, int glyph,
                                 long extentsAddress, long userData) {
            try {
                int error = FreeType.FT_Load_Glyph(face, glyph, FreeType.FT_LOAD_DEFAULT);
                if (error != 0) {
                    throw new FontNativeException("FT_Load_Glyph(hb extents)", error);
                }
                FT_Glyph_Metrics metrics = face.glyph().metrics();
                hb_glyph_extents_t.nx_bearing(extentsAddress, Math.toIntExact(metrics.horiBearingX()));
                hb_glyph_extents_t.ny_bearing(extentsAddress, Math.toIntExact(metrics.horiBearingY()));
                hb_glyph_extents_t.nwidth(extentsAddress, Math.toIntExact(metrics.width()));
                hb_glyph_extents_t.nheight(extentsAddress, Math.toIntExact(-metrics.height()));
                return 1;
            } catch (Throwable failure) {
                recordFailure("glyph extents", failure);
                return 0;
            }
        }

        private void recordFailure(String operation, Throwable failure) {
            if (callbackFailure != null) {
                return;
            }
            callbackFailure = failure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("HarfBuzz callback failed during " + operation, failure);
        }
    }

    private static byte[] copyCoverage(FT_Bitmap bitmap, int width, int height) {
        if (width == 0 || height == 0) {
            return new byte[0];
        }
        int pitch = bitmap.pitch();
        int stride = Math.abs(pitch);
        if (stride == 0) {
            throw new FontNativeException("FT_Bitmap", "non-empty bitmap has zero pitch");
        }
        ByteBuffer source = bitmap.buffer(Math.multiplyExact(stride, height));
        byte[] result = new byte[Math.multiplyExact(width, height)];
        int pixelMode = Byte.toUnsignedInt(bitmap.pixel_mode());
        int grayscaleLevels = Short.toUnsignedInt(bitmap.num_grays());
        for (int row = 0; row < height; row++) {
            int sourceRow = pitch >= 0 ? row : height - 1 - row;
            int sourceOffset = sourceRow * stride;
            int targetOffset = row * width;
            for (int column = 0; column < width; column++) {
                result[targetOffset + column] = switch (pixelMode) {
                    case FreeType.FT_PIXEL_MODE_GRAY -> scaleGray(
                            Byte.toUnsignedInt(source.get(sourceOffset + column)), grayscaleLevels);
                    case FreeType.FT_PIXEL_MODE_MONO -> (byte) (((source.get(sourceOffset + column / 8)
                            >> (7 - column % 8)) & 1) == 0 ? 0 : 0xFF);
                    case FreeType.FT_PIXEL_MODE_GRAY2 -> (byte) ((((source.get(sourceOffset + column / 4)
                            >> (6 - 2 * (column % 4))) & 0x3) * 255) / 3);
                    case FreeType.FT_PIXEL_MODE_GRAY4 -> (byte) ((((source.get(sourceOffset + column / 2)
                            >> (4 - 4 * (column % 2))) & 0xF) * 255) / 15);
                    default -> throw new FontNativeException("FT_Bitmap",
                            "unsupported pixel mode " + pixelMode);
                };
            }
        }
        return result;
    }

    private static byte scaleGray(int value, int levels) {
        if (levels <= 1 || levels == 256) {
            return (byte) value;
        }
        return (byte) Math.round(value * 255.0f / (levels - 1));
    }

    private static boolean isDefaultIgnorableForCoverage(int codePoint) {
        return codePoint == 0x200C || codePoint == 0x200D
                || codePoint >= 0xFE00 && codePoint <= 0xFE0F
                || codePoint >= 0xE0100 && codePoint <= 0xE01EF
                || Character.isISOControl(codePoint);
    }

    private static void requireUnicodeScalar(int codePoint) {
        if (!Character.isValidCodePoint(codePoint)
                || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
            throw new IllegalArgumentException("Not a Unicode scalar value: " + codePoint);
        }
    }

    private static void requireRange(String text, int start, int end) {
        if (start < 0 || end < start || end > text.length()) {
            throw new IndexOutOfBoundsException("Invalid UTF-16 range [" + start + ", " + end + ")");
        }
        if (!isCodePointBoundary(text, start) || !isCodePointBoundary(text, end)) {
            throw new IllegalArgumentException("Coverage range splits a supplementary code point");
        }
    }

    private static boolean isCodePointBoundary(String text, int offset) {
        return offset == 0 || offset == text.length()
                || !Character.isHighSurrogate(text.charAt(offset - 1))
                || !Character.isLowSurrogate(text.charAt(offset));
    }
}
