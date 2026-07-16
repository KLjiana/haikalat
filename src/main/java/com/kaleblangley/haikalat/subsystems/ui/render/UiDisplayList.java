package com.kaleblangley.haikalat.subsystems.ui.render;

import java.util.Arrays;
import java.util.Objects;

/**
 * 与图形 API 无关、可清空复用的 UI display list。
 *
 * <p>primitive 和 quad 使用结构化数组保存，稳定帧无需为每个 primitive 创建对象。
 * {@link #freeze()} 生成只读的紧凑副本，可安全发布给另一个线程。</p>
 */
public final class UiDisplayList {
    private static final int DEFAULT_PRIMITIVE_CAPACITY = 32;
    private static final int DEFAULT_QUAD_CAPACITY = 64;
    private static final UiPrimitiveKind[] KINDS = UiPrimitiveKind.values();
    private static final UiShaderVariant[] SHADERS = UiShaderVariant.values();
    private static final UiBlendMode[] BLENDS = UiBlendMode.values();

    private byte[] primitiveKinds;
    private byte[] primitiveShaders;
    private byte[] primitiveBlends;
    private int[] primitiveTextures;
    private int[] primitiveSamplers;
    private int[] primitiveFirstQuads;
    private int[] primitiveQuadCounts;
    private double[] primitiveClipX;
    private double[] primitiveClipY;
    private double[] primitiveClipWidth;
    private double[] primitiveClipHeight;

    private double[] quadX;
    private double[] quadY;
    private double[] quadWidth;
    private double[] quadHeight;
    private float[] quadU0;
    private float[] quadV0;
    private float[] quadU1;
    private float[] quadV1;
    private int[] quadColors;

    private int primitiveCount;
    private int quadCount;
    private int clipDepth;
    private boolean glyphRunOpen;
    private int glyphFirstQuad;
    private int glyphTexture;
    private int glyphSampler;
    private UiBlendMode glyphBlend;
    private final boolean frozen;

    /** 使用默认容量创建可复用的 display list。 */
    public UiDisplayList() {
        this(DEFAULT_PRIMITIVE_CAPACITY, DEFAULT_QUAD_CAPACITY);
    }

    /**
     * 创建可复用的 display list。
     *
     * @param primitiveCapacity 初始 primitive 容量
     * @param quadCapacity 初始 quad 容量
     */
    public UiDisplayList(int primitiveCapacity, int quadCapacity) {
        if (primitiveCapacity < 0 || quadCapacity < 0) {
            throw new IllegalArgumentException("display-list capacities must be non-negative");
        }
        frozen = false;
        allocatePrimitives(primitiveCapacity);
        allocateQuads(quadCapacity);
    }

    private UiDisplayList(UiDisplayList source) {
        frozen = true;
        primitiveCount = source.primitiveCount;
        quadCount = source.quadCount;
        primitiveKinds = Arrays.copyOf(source.primitiveKinds, primitiveCount);
        primitiveShaders = Arrays.copyOf(source.primitiveShaders, primitiveCount);
        primitiveBlends = Arrays.copyOf(source.primitiveBlends, primitiveCount);
        primitiveTextures = Arrays.copyOf(source.primitiveTextures, primitiveCount);
        primitiveSamplers = Arrays.copyOf(source.primitiveSamplers, primitiveCount);
        primitiveFirstQuads = Arrays.copyOf(source.primitiveFirstQuads, primitiveCount);
        primitiveQuadCounts = Arrays.copyOf(source.primitiveQuadCounts, primitiveCount);
        primitiveClipX = Arrays.copyOf(source.primitiveClipX, primitiveCount);
        primitiveClipY = Arrays.copyOf(source.primitiveClipY, primitiveCount);
        primitiveClipWidth = Arrays.copyOf(source.primitiveClipWidth, primitiveCount);
        primitiveClipHeight = Arrays.copyOf(source.primitiveClipHeight, primitiveCount);
        quadX = Arrays.copyOf(source.quadX, quadCount);
        quadY = Arrays.copyOf(source.quadY, quadCount);
        quadWidth = Arrays.copyOf(source.quadWidth, quadCount);
        quadHeight = Arrays.copyOf(source.quadHeight, quadCount);
        quadU0 = Arrays.copyOf(source.quadU0, quadCount);
        quadV0 = Arrays.copyOf(source.quadV0, quadCount);
        quadU1 = Arrays.copyOf(source.quadU1, quadCount);
        quadV1 = Arrays.copyOf(source.quadV1, quadCount);
        quadColors = Arrays.copyOf(source.quadColors, quadCount);
    }

    /** 清空记录内容并保留高水位容量。 */
    public void clear() {
        ensureMutable();
        primitiveCount = 0;
        quadCount = 0;
        clipDepth = 0;
        glyphRunOpen = false;
        glyphBlend = null;
    }

    /**
     * 追加单色四边形。
     *
     * @param bounds 逻辑坐标范围
     * @param premultipliedRgba8 0xRRGGBBAA 预乘颜色
     * @param blendMode 混合模式
     * @return 当前 display list，便于链式记录
     */
    public UiDisplayList addSolidQuad(UiScreenRect bounds, int premultipliedRgba8,
                                      UiBlendMode blendMode) {
        ensureRecordable();
        Objects.requireNonNull(blendMode, "blendMode");
        int firstQuad = appendQuad(bounds, UiUvRect.FULL, premultipliedRgba8);
        appendPrimitive(UiPrimitiveKind.SOLID_QUAD, UiShaderVariant.SOLID,
                -1, -1, blendMode, firstQuad, 1);
        return this;
    }

    /**
     * 追加纹理四边形。
     *
     * @param bounds 逻辑坐标范围
     * @param uv UV 范围
     * @param textureId 稳定纹理资源 ID
     * @param samplerId 稳定 sampler 资源 ID
     * @param premultipliedRgba8 0xRRGGBBAA 预乘调制色
     * @param blendMode 混合模式
     * @return 当前 display list
     */
    public UiDisplayList addTexturedQuad(UiScreenRect bounds, UiUvRect uv,
                                         int textureId, int samplerId,
                                         int premultipliedRgba8, UiBlendMode blendMode) {
        ensureRecordable();
        requireResource(textureId, "textureId");
        requireResource(samplerId, "samplerId");
        Objects.requireNonNull(blendMode, "blendMode");
        int firstQuad = appendQuad(bounds, uv, premultipliedRgba8);
        appendPrimitive(UiPrimitiveKind.TEXTURED_QUAD, UiShaderVariant.TEXTURED,
                textureId, samplerId, blendMode, firstQuad, 1);
        return this;
    }

    /**
     * 开始记录一段共用 atlas、sampler 和混合状态的 glyph。
     * 开始后只允许调用 {@link #addGlyph(UiScreenRect, UiUvRect, int)}、
     * {@link #endGlyphRun()} 或 {@link #abortGlyphRun()}。
     *
     * @param atlasPage atlas 页资源 ID
     * @param samplerId sampler 资源 ID
     * @param blendMode 混合模式
     */
    public void beginGlyphRun(int atlasPage, int samplerId, UiBlendMode blendMode) {
        ensureRecordable();
        requireResource(atlasPage, "atlasPage");
        requireResource(samplerId, "samplerId");
        Objects.requireNonNull(blendMode, "blendMode");
        glyphRunOpen = true;
        glyphFirstQuad = quadCount;
        glyphTexture = atlasPage;
        glyphSampler = samplerId;
        glyphBlend = blendMode;
    }

    /**
     * 向当前 glyph run 追加一个定位 glyph quad。
     *
     * @param bounds glyph 的逻辑坐标范围
     * @param uv atlas UV 范围
     * @param premultipliedRgba8 0xRRGGBBAA 预乘颜色
     */
    public void addGlyph(UiScreenRect bounds, UiUvRect uv, int premultipliedRgba8) {
        ensureMutable();
        if (!glyphRunOpen) {
            throw new IllegalStateException("no glyph run is active");
        }
        appendQuad(bounds, uv, premultipliedRgba8);
    }

    /** 完成当前 glyph run；空 run 不产生 primitive。 */
    public void endGlyphRun() {
        ensureMutable();
        if (!glyphRunOpen) {
            throw new IllegalStateException("no glyph run is active");
        }
        int count = quadCount - glyphFirstQuad;
        if (count != 0) {
            appendPrimitive(UiPrimitiveKind.GLYPH_RUN, UiShaderVariant.GLYPH,
                    glyphTexture, glyphSampler, glyphBlend, glyphFirstQuad, count);
        }
        glyphRunOpen = false;
        glyphBlend = null;
    }

    /** 异常路径中放弃当前 glyph run，并回收尚未发布的 quad。 */
    public void abortGlyphRun() {
        ensureMutable();
        if (!glyphRunOpen) {
            return;
        }
        quadCount = glyphFirstQuad;
        glyphRunOpen = false;
        glyphBlend = null;
    }

    /**
     * 压入逻辑裁剪。batching 时先与父裁剪求交，不在此处进行 DPI 取整。
     *
     * @param clip 逻辑裁剪
     * @return 当前 display list
     */
    public UiDisplayList pushClip(UiScreenRect clip) {
        ensureRecordable();
        Objects.requireNonNull(clip, "clip");
        int index = appendPrimitive(UiPrimitiveKind.PUSH_CLIP, null,
                -1, -1, null, -1, 0);
        primitiveClipX[index] = clip.x();
        primitiveClipY[index] = clip.y();
        primitiveClipWidth[index] = clip.width();
        primitiveClipHeight[index] = clip.height();
        clipDepth++;
        return this;
    }

    /**
     * 弹出最近压入的逻辑裁剪。
     *
     * @return 当前 display list
     */
    public UiDisplayList popClip() {
        ensureRecordable();
        if (clipDepth == 0) {
            throw new IllegalStateException("clip stack underflow");
        }
        appendPrimitive(UiPrimitiveKind.POP_CLIP, null,
                -1, -1, null, -1, 0);
        clipDepth--;
        return this;
    }

    /**
     * 追加诊断边框。线宽和具体光栅化方式由 debug shader 解释。
     *
     * @param bounds 逻辑坐标范围
     * @param premultipliedRgba8 0xRRGGBBAA 预乘颜色
     * @return 当前 display list
     */
    public UiDisplayList addDebugOutline(UiScreenRect bounds, int premultipliedRgba8) {
        ensureRecordable();
        int firstQuad = appendQuad(bounds, UiUvRect.FULL, premultipliedRgba8);
        appendPrimitive(UiPrimitiveKind.DEBUG_OUTLINE, UiShaderVariant.DEBUG_OUTLINE,
                -1, -1, UiBlendMode.PREMULTIPLIED_ALPHA, firstQuad, 1);
        return this;
    }

    /**
     * 追加不可跨越的 paint 顺序屏障。该标记不产生顶点和 draw。
     *
     * @return 当前 display list
     */
    public UiDisplayList paintBoundary() {
        ensureRecordable();
        appendPrimitive(UiPrimitiveKind.PAINT_BOUNDARY, null,
                -1, -1, null, -1, 0);
        return this;
    }

    /**
     * 生成紧凑只读副本。若当前对象已经只读，则直接返回自身。
     *
     * @return 可跨线程发布的只读 display list
     */
    public UiDisplayList freeze() {
        if (frozen) {
            return this;
        }
        validateComplete();
        return new UiDisplayList(this);
    }

    /** 返回对象是否为只读快照。 */
    public boolean isFrozen() {
        return frozen;
    }

    /** 返回 primitive 数量，包含 clip 和 paint boundary 指令。 */
    public int primitiveCount() {
        return primitiveCount;
    }

    /** 返回实际 quad 数量；一个 glyph run 可以包含多个 quad。 */
    public int quadCount() {
        return quadCount;
    }

    /** 返回指定 primitive 的种类。 */
    public UiPrimitiveKind primitiveKind(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        return KINDS[primitiveKinds[primitiveIndex]];
    }

    /** 返回 draw primitive 的 shader 变体。 */
    public UiShaderVariant primitiveShader(int primitiveIndex) {
        requireDrawPrimitive(primitiveIndex);
        return SHADERS[primitiveShaders[primitiveIndex]];
    }

    /** 返回 draw primitive 的纹理资源 ID；纯色和 debug primitive 返回 -1。 */
    public int primitiveTexture(int primitiveIndex) {
        requireDrawPrimitive(primitiveIndex);
        return primitiveTextures[primitiveIndex];
    }

    /** 返回 draw primitive 的 sampler 资源 ID；不采样纹理时返回 -1。 */
    public int primitiveSampler(int primitiveIndex) {
        requireDrawPrimitive(primitiveIndex);
        return primitiveSamplers[primitiveIndex];
    }

    /** 返回 draw primitive 的混合模式。 */
    public UiBlendMode primitiveBlend(int primitiveIndex) {
        requireDrawPrimitive(primitiveIndex);
        return BLENDS[primitiveBlends[primitiveIndex]];
    }

    /** 返回 draw primitive 在 quad arena 中的起始位置。 */
    public int primitiveFirstQuad(int primitiveIndex) {
        requireDrawPrimitive(primitiveIndex);
        return primitiveFirstQuads[primitiveIndex];
    }

    /** 返回 draw primitive 的 quad 数量。 */
    public int primitiveQuadCount(int primitiveIndex) {
        requireDrawPrimitive(primitiveIndex);
        return primitiveQuadCounts[primitiveIndex];
    }

    /** 返回 PUSH_CLIP primitive 保存的逻辑裁剪。 */
    public UiScreenRect primitiveClip(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        if (primitiveKind(primitiveIndex) != UiPrimitiveKind.PUSH_CLIP) {
            throw new IllegalArgumentException("primitive is not PUSH_CLIP");
        }
        return new UiScreenRect(primitiveClipX[primitiveIndex], primitiveClipY[primitiveIndex],
                primitiveClipWidth[primitiveIndex], primitiveClipHeight[primitiveIndex]);
    }

    /** 返回 quad 的逻辑 X。 */
    public double quadX(int quadIndex) {
        checkQuadIndex(quadIndex);
        return quadX[quadIndex];
    }

    /** 返回 quad 的逻辑 Y。 */
    public double quadY(int quadIndex) {
        checkQuadIndex(quadIndex);
        return quadY[quadIndex];
    }

    /** 返回 quad 的逻辑宽度。 */
    public double quadWidth(int quadIndex) {
        checkQuadIndex(quadIndex);
        return quadWidth[quadIndex];
    }

    /** 返回 quad 的逻辑高度。 */
    public double quadHeight(int quadIndex) {
        checkQuadIndex(quadIndex);
        return quadHeight[quadIndex];
    }

    /** 返回 quad 左上角 U。 */
    public float quadU0(int quadIndex) {
        checkQuadIndex(quadIndex);
        return quadU0[quadIndex];
    }

    /** 返回 quad 左上角 V。 */
    public float quadV0(int quadIndex) {
        checkQuadIndex(quadIndex);
        return quadV0[quadIndex];
    }

    /** 返回 quad 右下角 U。 */
    public float quadU1(int quadIndex) {
        checkQuadIndex(quadIndex);
        return quadU1[quadIndex];
    }

    /** 返回 quad 右下角 V。 */
    public float quadV1(int quadIndex) {
        checkQuadIndex(quadIndex);
        return quadV1[quadIndex];
    }

    /** 返回 quad 的 0xRRGGBBAA 预乘颜色。 */
    public int quadColor(int quadIndex) {
        checkQuadIndex(quadIndex);
        return quadColors[quadIndex];
    }

    double primitiveClipX(int primitiveIndex) {
        return primitiveClipX[primitiveIndex];
    }

    double primitiveClipY(int primitiveIndex) {
        return primitiveClipY[primitiveIndex];
    }

    double primitiveClipWidth(int primitiveIndex) {
        return primitiveClipWidth[primitiveIndex];
    }

    double primitiveClipHeight(int primitiveIndex) {
        return primitiveClipHeight[primitiveIndex];
    }

    void validateComplete() {
        if (glyphRunOpen) {
            throw new IllegalStateException("glyph run is still active");
        }
        if (clipDepth != 0) {
            throw new IllegalStateException("clip stack is not balanced: depth=" + clipDepth);
        }
    }

    private int appendQuad(UiScreenRect bounds, UiUvRect uv, int color) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(uv, "uv");
        ensureQuadCapacity(quadCount + 1);
        int index = quadCount++;
        quadX[index] = bounds.x();
        quadY[index] = bounds.y();
        quadWidth[index] = bounds.width();
        quadHeight[index] = bounds.height();
        quadU0[index] = uv.u0();
        quadV0[index] = uv.v0();
        quadU1[index] = uv.u1();
        quadV1[index] = uv.v1();
        quadColors[index] = color;
        return index;
    }

    private int appendPrimitive(UiPrimitiveKind kind, UiShaderVariant shader,
                                int texture, int sampler, UiBlendMode blend,
                                int firstQuad, int quadCountValue) {
        ensurePrimitiveCapacity(primitiveCount + 1);
        int index = primitiveCount++;
        primitiveKinds[index] = (byte) Objects.requireNonNull(kind, "kind").ordinal();
        primitiveShaders[index] = shader == null ? -1 : (byte) shader.ordinal();
        primitiveBlends[index] = blend == null ? -1 : (byte) blend.ordinal();
        primitiveTextures[index] = texture;
        primitiveSamplers[index] = sampler;
        primitiveFirstQuads[index] = firstQuad;
        primitiveQuadCounts[index] = quadCountValue;
        return index;
    }

    private void requireDrawPrimitive(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        if (primitiveShaders[primitiveIndex] < 0) {
            throw new IllegalArgumentException("primitive does not produce a draw");
        }
    }

    private void ensureRecordable() {
        ensureMutable();
        if (glyphRunOpen) {
            throw new IllegalStateException("finish or abort the active glyph run first");
        }
    }

    private void ensureMutable() {
        if (frozen) {
            throw new IllegalStateException("frozen UI display list is immutable");
        }
    }

    private static void requireResource(int id, String name) {
        if (id < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private void checkPrimitiveIndex(int index) {
        if (index < 0 || index >= primitiveCount) {
            throw new IndexOutOfBoundsException("primitive index: " + index);
        }
    }

    private void checkQuadIndex(int index) {
        if (index < 0 || index >= quadCount) {
            throw new IndexOutOfBoundsException("quad index: " + index);
        }
    }

    private void allocatePrimitives(int capacity) {
        primitiveKinds = new byte[capacity];
        primitiveShaders = new byte[capacity];
        primitiveBlends = new byte[capacity];
        primitiveTextures = new int[capacity];
        primitiveSamplers = new int[capacity];
        primitiveFirstQuads = new int[capacity];
        primitiveQuadCounts = new int[capacity];
        primitiveClipX = new double[capacity];
        primitiveClipY = new double[capacity];
        primitiveClipWidth = new double[capacity];
        primitiveClipHeight = new double[capacity];
    }

    private void allocateQuads(int capacity) {
        quadX = new double[capacity];
        quadY = new double[capacity];
        quadWidth = new double[capacity];
        quadHeight = new double[capacity];
        quadU0 = new float[capacity];
        quadV0 = new float[capacity];
        quadU1 = new float[capacity];
        quadV1 = new float[capacity];
        quadColors = new int[capacity];
    }

    private void ensurePrimitiveCapacity(int required) {
        if (required <= primitiveKinds.length) {
            return;
        }
        int capacity = grownCapacity(primitiveKinds.length, required);
        primitiveKinds = Arrays.copyOf(primitiveKinds, capacity);
        primitiveShaders = Arrays.copyOf(primitiveShaders, capacity);
        primitiveBlends = Arrays.copyOf(primitiveBlends, capacity);
        primitiveTextures = Arrays.copyOf(primitiveTextures, capacity);
        primitiveSamplers = Arrays.copyOf(primitiveSamplers, capacity);
        primitiveFirstQuads = Arrays.copyOf(primitiveFirstQuads, capacity);
        primitiveQuadCounts = Arrays.copyOf(primitiveQuadCounts, capacity);
        primitiveClipX = Arrays.copyOf(primitiveClipX, capacity);
        primitiveClipY = Arrays.copyOf(primitiveClipY, capacity);
        primitiveClipWidth = Arrays.copyOf(primitiveClipWidth, capacity);
        primitiveClipHeight = Arrays.copyOf(primitiveClipHeight, capacity);
    }

    private void ensureQuadCapacity(int required) {
        if (required <= quadX.length) {
            return;
        }
        int capacity = grownCapacity(quadX.length, required);
        quadX = Arrays.copyOf(quadX, capacity);
        quadY = Arrays.copyOf(quadY, capacity);
        quadWidth = Arrays.copyOf(quadWidth, capacity);
        quadHeight = Arrays.copyOf(quadHeight, capacity);
        quadU0 = Arrays.copyOf(quadU0, capacity);
        quadV0 = Arrays.copyOf(quadV0, capacity);
        quadU1 = Arrays.copyOf(quadU1, capacity);
        quadV1 = Arrays.copyOf(quadV1, capacity);
        quadColors = Arrays.copyOf(quadColors, capacity);
    }

    private static int grownCapacity(int current, int required) {
        if (required < 0) {
            throw new OutOfMemoryError("display-list capacity overflow");
        }
        int grown = current < 2 ? 2 : current + (current >> 1);
        if (grown < required) {
            grown = required;
        }
        if (grown < 0) {
            grown = Integer.MAX_VALUE - 8;
        }
        return grown;
    }
}
