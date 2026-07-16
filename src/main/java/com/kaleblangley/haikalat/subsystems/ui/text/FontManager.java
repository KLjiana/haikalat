package com.kaleblangley.haikalat.subsystems.ui.text;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.freetype.FreeType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 线程封闭的 FreeType library、字体注册表和 fallback 所有者。
 *
 * <p>字体只来自应用显式注册的数据；该类不会查询操作系统字体目录。注册表发生结构变化时
 * {@link #generation()} 严格递增，使 shaping/fallback cache 能够拒绝旧结果。</p>
 */
public final class FontManager implements AutoCloseable {
    private final TextThreadOwner threadOwner = new TextThreadOwner();
    private final Map<FontFamilyId, FontFamily> families = new LinkedHashMap<>();
    private final Map<FontFaceId, FontFace> faces = new LinkedHashMap<>();
    private long library;
    private long nextFamilyId = 1L;
    private long nextFaceId = 1L;
    private FontGeneration generation = new FontGeneration(0L);
    private boolean closed;

    /** 初始化一个由当前线程拥有的 FreeType library。 */
    public FontManager() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer result = stack.mallocPointer(1);
            int error = FreeType.FT_Init_FreeType(result);
            if (error != 0) {
                throw new FontNativeException("FT_Init_FreeType", error);
            }
            library = result.get(0);
            if (library == 0L) {
                throw new FontNativeException("FT_Init_FreeType", "returned a null library handle");
            }
        }
    }

    /** 返回字体注册表当前 generation。 */
    public FontGeneration generation() {
        checkUsable("FontManager.generation");
        return generation;
    }

    /** 注册一个显式命名的字体家族。 */
    public FontFamily registerFamily(String name) {
        checkUsable("FontManager.registerFamily");
        String normalizedName = Objects.requireNonNull(name, "name").trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("Font family name must not be blank");
        }
        FontFamilyId id = new FontFamilyId(nextStableId(true));
        FontFamily family = new FontFamily(id, normalizedName);
        families.put(id, family);
        bumpGeneration();
        return family;
    }

    /** 从 ByteBuffer 当前 remaining 区域注册 face；数据会复制到 manager 自己拥有的 direct buffer。 */
    public FontFace registerFace(FontFamily family, ByteBuffer fontData) {
        return registerFace(family, fontData, 0);
    }

    /** 从 ByteBuffer 当前 remaining 区域和指定 collection index 注册 face。 */
    public FontFace registerFace(FontFamily family, ByteBuffer fontData, int faceIndex) {
        checkUsable("FontManager.registerFace");
        FontFamily registeredFamily = requireFamily(family);
        Objects.requireNonNull(fontData, "fontData");
        if (!fontData.hasRemaining()) {
            throw new IllegalArgumentException("Font data must not be empty");
        }
        if (faceIndex < 0) {
            throw new IllegalArgumentException("faceIndex must be non-negative");
        }
        FontFaceId id = new FontFaceId(nextStableId(false));
        FontFace face = FontFace.create(this, registeredFamily, id, library, fontData, faceIndex);
        faces.put(id, face);
        bumpGeneration();
        return face;
    }

    /** 从 byte array 注册 face，输入数组不会被长期引用。 */
    public FontFace registerFace(FontFamily family, byte[] fontData, int faceIndex) {
        Objects.requireNonNull(fontData, "fontData");
        return registerFace(family, ByteBuffer.wrap(fontData), faceIndex);
    }

    /** 从显式路径读取并注册 face。 */
    public FontFace registerFace(FontFamily family, Path path, int faceIndex) throws IOException {
        Objects.requireNonNull(path, "path");
        return registerFace(family, Files.readAllBytes(path), faceIndex);
    }

    /** 返回仍处于打开状态的已注册 face。 */
    public Optional<FontFace> face(FontFaceId id) {
        checkUsable("FontManager.face");
        return Optional.ofNullable(faces.get(Objects.requireNonNull(id, "id")));
    }

    /** 返回指定家族的 face，顺序与注册顺序一致。 */
    public List<FontFace> faces(FontFamily family) {
        checkUsable("FontManager.faces");
        FontFamily registeredFamily = requireFamily(family);
        return faces.values().stream()
                .filter(face -> face.family().equals(registeredFamily))
                .toList();
    }

    /** 创建绑定当前 generation 的显式 fallback chain。 */
    public FontFallbackChain fallbackChain(List<FontFace> orderedFaces) {
        checkUsable("FontManager.fallbackChain");
        Objects.requireNonNull(orderedFaces, "orderedFaces");
        List<FontFaceId> ids = new ArrayList<>(orderedFaces.size());
        for (FontFace face : orderedFaces) {
            requireFace(face);
            ids.add(face.id());
        }
        return new FontFallbackChain(generation, ids);
    }

    /** 创建 fallback chain 的 varargs 便捷入口。 */
    public FontFallbackChain fallbackChain(FontFace first, FontFace... remaining) {
        Objects.requireNonNull(remaining, "remaining");
        List<FontFace> faces = new ArrayList<>(remaining.length + 1);
        faces.add(Objects.requireNonNull(first, "first"));
        for (FontFace face : remaining) {
            faces.add(Objects.requireNonNull(face, "face"));
        }
        return fallbackChain(faces);
    }

    /**
     * 查找能够覆盖完整 grapheme cluster 的首个 face。
     */
    public Optional<FontFace> findCoveringFace(FontFallbackChain chain, String text,
                                                int startUtf16, int endUtf16) {
        checkUsable("FontManager.findCoveringFace");
        List<FontFace> resolved = resolveChain(chain);
        for (FontFace face : resolved) {
            if (face.covers(text, startUtf16, endUtf16)) {
                return Optional.of(face);
            }
        }
        return Optional.empty();
    }

    /**
     * 返回覆盖 cluster 的 face；全部 miss 时稳定返回 chain 首项作为 tofu glyph 来源。
     */
    public FontFace resolveFaceOrTofu(FontFallbackChain chain, String text,
                                      int startUtf16, int endUtf16) {
        checkUsable("FontManager.resolveFaceOrTofu");
        List<FontFace> resolved = resolveChain(chain);
        for (FontFace face : resolved) {
            if (face.covers(text, startUtf16, endUtf16)) {
                return face;
            }
        }
        return resolved.get(0);
    }

    /** 返回 manager 是否已关闭；该只读诊断可从任意线程调用。 */
    public boolean isClosed() {
        return closed;
    }

    /** 幂等关闭 face、FreeType library；face 按注册逆序释放。 */
    @Override
    public void close() {
        threadOwner.check("FontManager.close");
        if (closed) {
            return;
        }
        RuntimeException failure = null;
        List<FontFace> reverseFaces = new ArrayList<>(faces.values());
        for (int index = reverseFaces.size() - 1; index >= 0; index--) {
            try {
                reverseFaces.get(index).closeFromManager();
            } catch (RuntimeException cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        faces.clear();
        families.clear();
        long ownedLibrary = library;
        library = 0L;
        closed = true;
        if (ownedLibrary != 0L) {
            try {
                int error = FreeType.FT_Done_FreeType(ownedLibrary);
                if (error != 0) {
                    failure = appendFailure(failure, new FontNativeException("FT_Done_FreeType", error));
                }
            } catch (RuntimeException cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    void checkUsable(String operation) {
        threadOwner.check(operation);
        if (closed) {
            throw new IllegalStateException(operation + " cannot use closed FontManager");
        }
    }

    FontFace requireFace(FontFace face) {
        checkUsable("FontManager.requireFace");
        Objects.requireNonNull(face, "face");
        if (faces.get(face.id()) != face || face.isClosed()) {
            throw new IllegalArgumentException("Font face is not an open member of this manager");
        }
        return face;
    }

    void closeFace(FontFace face) {
        checkUsable("FontManager.closeFace");
        if (face.isClosed()) {
            return;
        }
        if (faces.get(face.id()) != face) {
            throw new IllegalArgumentException("Font face does not belong to this manager");
        }
        try {
            face.closeFromManager();
        } finally {
            faces.remove(face.id());
            bumpGeneration();
        }
    }

    /** 字体外观配置变化后使尚未创建的 fallback/shaping 配置获得新代次。 */
    void faceConfigurationChanged(FontFace face) {
        requireFace(face);
        bumpGeneration();
    }

    /** 仅供同包字体对象释放 FreeType 返回的 library-owned 数据。 */
    long nativeLibraryHandle() {
        checkUsable("FontManager.nativeLibraryHandle");
        return library;
    }

    private FontFamily requireFamily(FontFamily family) {
        Objects.requireNonNull(family, "family");
        if (families.get(family.id()) != family) {
            throw new IllegalArgumentException("Font family does not belong to this manager");
        }
        return family;
    }

    private List<FontFace> resolveChain(FontFallbackChain chain) {
        Objects.requireNonNull(chain, "chain");
        if (!generation.equals(chain.fontGeneration())) {
            throw new IllegalStateException("Fallback chain belongs to stale font generation "
                    + chain.fontGeneration().value() + "; current generation is " + generation.value());
        }
        List<FontFace> result = new ArrayList<>(chain.faceIds().size());
        for (FontFaceId id : chain.faceIds()) {
            FontFace face = faces.get(id);
            if (face == null || face.isClosed()) {
                throw new IllegalStateException("Fallback chain references unavailable face " + id.value());
            }
            result.add(face);
        }
        return result;
    }

    private long nextStableId(boolean family) {
        long next = family ? nextFamilyId : nextFaceId;
        if (next <= 0L || next == Long.MAX_VALUE) {
            throw new IllegalStateException((family ? "Font family" : "Font face") + " id space exhausted");
        }
        if (family) {
            nextFamilyId = next + 1L;
        } else {
            nextFaceId = next + 1L;
        }
        return next;
    }

    private void bumpGeneration() {
        generation = generation.next();
    }

    private static RuntimeException appendFailure(RuntimeException primary, RuntimeException added) {
        if (primary == null) {
            return added;
        }
        primary.addSuppressed(added);
        return primary;
    }
}
