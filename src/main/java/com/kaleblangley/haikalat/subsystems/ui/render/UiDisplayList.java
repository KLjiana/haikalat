package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.animation.UiVisualTransform;

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
    private long[] primitiveImageIds;
    private int[] primitiveFirstQuads;
    private int[] primitiveQuadCounts;
    private double[] primitiveClipX;
    private double[] primitiveClipY;
    private double[] primitiveClipWidth;
    private double[] primitiveClipHeight;
    private byte[] primitiveSdfKinds;
    private float[] primitiveSdfRadii;
    private float[] primitiveSdfParams;
    private float[] primitiveSdfGradientAngles;
    private int[] primitiveSdfFillColors;
    private int[] primitiveSdfBorderColors;
    private int[] primitiveSdfGradientStartColors;
    private int[] primitiveSdfGradientEndColors;
    private UiLayerDescription[] primitiveLayers;

    // Text effect arrays
    private byte[] primitiveTextEffectTypes;
    private int[] primitiveTextEffectColor1;
    private int[] primitiveTextEffectColor2;
    private float[] primitiveTextEffectThickness;
    private float[] primitiveTextEffectOffsetX;
    private float[] primitiveTextEffectOffsetY;
    private float[] primitiveTextEffectBlur;
    private float[] primitiveTextEffectAngle;

    private double[] quadX;
    private double[] quadY;
    private double[] quadWidth;
    private double[] quadHeight;
    private float[] quadU0;
    private float[] quadV0;
    private float[] quadU1;
    private float[] quadV1;
    private int[] quadColors;
    private double[] quadTransformM00;
    private double[] quadTransformM01;
    private double[] quadTransformM10;
    private double[] quadTransformM11;
    private double[] quadTransformX;
    private double[] quadTransformY;

    private int primitiveCount;
    private int quadCount;
    private int clipDepth;
    private int layerDepth;
    private UiVisualTransform.Matrix transform = UiVisualTransform.Matrix.IDENTITY;
    private UiVisualTransform.Matrix[] transformStack = new UiVisualTransform.Matrix[8];
    private int transformDepth;
    private boolean glyphRunOpen;
    private int glyphFirstQuad;
    private int glyphTexture;
    private int glyphSampler;
    private UiBlendMode glyphBlend;
    private byte glyphTextEffectType;
    private int glyphTextEffectColor1;
    private int glyphTextEffectColor2;
    private float glyphTextEffectThickness;
    private float glyphTextEffectOffsetX;
    private float glyphTextEffectOffsetY;
    private float glyphTextEffectBlur;
    private float glyphTextEffectAngle;
    private byte currentTextEffectType;
    private int currentTextEffectColor1;
    private int currentTextEffectColor2;
    private float currentTextEffectThickness;
    private float currentTextEffectOffsetX;
    private float currentTextEffectOffsetY;
    private float currentTextEffectBlur;
    private float currentTextEffectAngle;
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
        this(false, primitiveCapacity, quadCapacity);
    }

    private UiDisplayList(boolean frozen, int primitiveCapacity, int quadCapacity) {
        if (primitiveCapacity < 0 || quadCapacity < 0) {
            throw new IllegalArgumentException("display-list capacities must be non-negative");
        }
        this.frozen = frozen;
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
        primitiveImageIds = Arrays.copyOf(source.primitiveImageIds, primitiveCount);
        primitiveFirstQuads = Arrays.copyOf(source.primitiveFirstQuads, primitiveCount);
        primitiveQuadCounts = Arrays.copyOf(source.primitiveQuadCounts, primitiveCount);
        primitiveClipX = Arrays.copyOf(source.primitiveClipX, primitiveCount);
        primitiveClipY = Arrays.copyOf(source.primitiveClipY, primitiveCount);
        primitiveClipWidth = Arrays.copyOf(source.primitiveClipWidth, primitiveCount);
        primitiveClipHeight = Arrays.copyOf(source.primitiveClipHeight, primitiveCount);
        primitiveSdfKinds = Arrays.copyOf(source.primitiveSdfKinds, primitiveCount);
        primitiveSdfRadii = Arrays.copyOf(source.primitiveSdfRadii, primitiveCount * 4);
        primitiveSdfParams = Arrays.copyOf(source.primitiveSdfParams, primitiveCount * 4);
        primitiveSdfGradientAngles = Arrays.copyOf(source.primitiveSdfGradientAngles, primitiveCount);
        primitiveSdfFillColors = Arrays.copyOf(source.primitiveSdfFillColors, primitiveCount);
        primitiveSdfBorderColors = Arrays.copyOf(source.primitiveSdfBorderColors, primitiveCount);
        primitiveSdfGradientStartColors = Arrays.copyOf(
                source.primitiveSdfGradientStartColors, primitiveCount);
        primitiveSdfGradientEndColors = Arrays.copyOf(
                source.primitiveSdfGradientEndColors, primitiveCount);
        primitiveLayers = Arrays.copyOf(source.primitiveLayers, primitiveCount);

        // Text effect arrays
        primitiveTextEffectTypes = Arrays.copyOf(source.primitiveTextEffectTypes, primitiveCount);
        primitiveTextEffectColor1 = Arrays.copyOf(source.primitiveTextEffectColor1, primitiveCount);
        primitiveTextEffectColor2 = Arrays.copyOf(source.primitiveTextEffectColor2, primitiveCount);
        primitiveTextEffectThickness = Arrays.copyOf(source.primitiveTextEffectThickness, primitiveCount);
        primitiveTextEffectOffsetX = Arrays.copyOf(source.primitiveTextEffectOffsetX, primitiveCount);
        primitiveTextEffectOffsetY = Arrays.copyOf(source.primitiveTextEffectOffsetY, primitiveCount);
        primitiveTextEffectBlur = Arrays.copyOf(source.primitiveTextEffectBlur, primitiveCount);
        primitiveTextEffectAngle = Arrays.copyOf(source.primitiveTextEffectAngle, primitiveCount);

        quadX = Arrays.copyOf(source.quadX, quadCount);
        quadY = Arrays.copyOf(source.quadY, quadCount);
        quadWidth = Arrays.copyOf(source.quadWidth, quadCount);
        quadHeight = Arrays.copyOf(source.quadHeight, quadCount);
        quadU0 = Arrays.copyOf(source.quadU0, quadCount);
        quadV0 = Arrays.copyOf(source.quadV0, quadCount);
        quadU1 = Arrays.copyOf(source.quadU1, quadCount);
        quadV1 = Arrays.copyOf(source.quadV1, quadCount);
        quadColors = Arrays.copyOf(source.quadColors, quadCount);
        quadTransformM00 = Arrays.copyOf(source.quadTransformM00, quadCount);
        quadTransformM01 = Arrays.copyOf(source.quadTransformM01, quadCount);
        quadTransformM10 = Arrays.copyOf(source.quadTransformM10, quadCount);
        quadTransformM11 = Arrays.copyOf(source.quadTransformM11, quadCount);
        quadTransformX = Arrays.copyOf(source.quadTransformX, quadCount);
        quadTransformY = Arrays.copyOf(source.quadTransformY, quadCount);
    }

    /** 清空记录内容并保留高水位容量。 */
    public void clear() {
        ensureMutable();
        primitiveCount = 0;
        quadCount = 0;
        clipDepth = 0;
        layerDepth = 0;
        transform = UiVisualTransform.Matrix.IDENTITY;
        transformDepth = 0;
        glyphRunOpen = false;
        glyphBlend = null;
        resetCurrentTextEffect();
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
        Objects.requireNonNull(bounds, "bounds");
        return addSolidQuad(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                premultipliedRgba8, blendMode);
    }

    /** 以标量直接追加单色四边形，避免热路径创建临时矩形。 */
    public UiDisplayList addSolidQuad(double x, double y, double width, double height,
                                      int premultipliedRgba8, UiBlendMode blendMode) {
        ensureRecordable();
        Objects.requireNonNull(blendMode, "blendMode");
        int firstQuad = appendQuad(x, y, width, height, 0.0f, 0.0f, 1.0f, 1.0f,
                premultipliedRgba8);
        appendPrimitive(UiPrimitiveKind.SOLID_QUAD, UiShaderVariant.SOLID,
                -1, -1, blendMode, firstQuad, 1);
        return this;
    }

    /** Appends one analytic SDF shape while preserving the ordinary quad path. */
    public UiDisplayList addSdfShape(UiScreenRect bounds, UiSdfShape shape,
                                     UiSdfDecoration decoration,
                                     UiBlendMode blendMode) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(decoration, "decoration");
        ensureRecordable();
        Objects.requireNonNull(blendMode, "blendMode");
        UiSdfShape normalized = shape.normalizedFor((float) bounds.width(),
                (float) bounds.height());
        int firstQuad = appendQuad(bounds, UiUvRect.FULL,
                decoration.fill().color().packedPremultipliedRgba8());
        int primitive = appendPrimitive(UiPrimitiveKind.SDF_SHAPE, UiShaderVariant.SDF,
                -1, -1, blendMode, firstQuad, 1);
        primitiveSdfKinds[primitive] = (byte) normalized.kind().ordinal();
        int radiusOffset = primitive * 4;
        primitiveSdfRadii[radiusOffset] = normalized.radiusTopLeft();
        primitiveSdfRadii[radiusOffset + 1] = normalized.radiusTopRight();
        primitiveSdfRadii[radiusOffset + 2] = normalized.radiusBottomRight();
        primitiveSdfRadii[radiusOffset + 3] = normalized.radiusBottomLeft();
        int paramsOffset = primitive * 4;
        primitiveSdfParams[paramsOffset] = normalized.innerRadius();
        primitiveSdfParams[paramsOffset + 1] = normalized.startRadians();
        primitiveSdfParams[paramsOffset + 2] = normalized.endRadians();
        primitiveSdfParams[paramsOffset + 3] = normalized.thickness() > 0.0f
                ? normalized.thickness() : decoration.borderWidth();
        primitiveSdfGradientAngles[primitive] = decoration.gradient() == null
                ? 0.0f : decoration.gradient().angleRadians();
        primitiveSdfFillColors[primitive] = decoration.fill().color().packedPremultipliedRgba8();
        primitiveSdfBorderColors[primitive] = decoration.border().color().packedPremultipliedRgba8();
        primitiveSdfGradientStartColors[primitive] = decoration.gradient() == null
                ? primitiveSdfFillColors[primitive]
                : decoration.gradient().firstColor().packedPremultipliedRgba8();
        primitiveSdfGradientEndColors[primitive] = decoration.gradient() == null
                ? primitiveSdfFillColors[primitive]
                : decoration.gradient().lastColor().packedPremultipliedRgba8();
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
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(uv, "uv");
        return addTexturedQuad(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                uv.u0(), uv.v0(), uv.u1(), uv.v1(), textureId, samplerId,
                premultipliedRgba8, blendMode);
    }

    /** 以标量直接追加纹理四边形，避免热路径创建 bounds/UV 对象。 */
    public UiDisplayList addTexturedQuad(double x, double y, double width, double height,
                                         float u0, float v0, float u1, float v1,
                                         int textureId, int samplerId,
                                         int premultipliedRgba8, UiBlendMode blendMode) {
        ensureRecordable();
        requireResource(textureId, "textureId");
        requireResource(samplerId, "samplerId");
        Objects.requireNonNull(blendMode, "blendMode");
        int firstQuad = appendQuad(x, y, width, height, u0, v0, u1, v1,
                premultipliedRgba8);
        appendPrimitive(UiPrimitiveKind.TEXTURED_QUAD, UiShaderVariant.TEXTURED,
                textureId, samplerId, blendMode, firstQuad, 1);
        return this;
    }

    /**
     * 追加需要在 render-record 边界重新解析的逻辑图片。
     * 快照中的 texture/sampler 只用于布局元数据，渲染时不会直接信任它们。
     */
    public UiDisplayList addLogicalImageQuad(UiScreenRect bounds, UiUvRect uv,
                                             long imageId, int textureId, int samplerId,
                                             int premultipliedRgba8, UiBlendMode blendMode) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(uv, "uv");
        if (imageId < 0L) throw new IllegalArgumentException("imageId must be non-negative");
        ensureRecordable();
        requireResource(textureId, "textureId");
        requireResource(samplerId, "samplerId");
        Objects.requireNonNull(blendMode, "blendMode");
        int firstQuad = appendQuad(bounds, uv, premultipliedRgba8);
        int primitive = appendPrimitive(UiPrimitiveKind.TEXTURED_QUAD, UiShaderVariant.TEXTURED,
                textureId, samplerId, blendMode, firstQuad, 1);
        primitiveImageIds[primitive] = imageId;
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
        copyCurrentTextEffectToGlyphRun();
    }

    /**
     * 向当前 glyph run 追加一个定位 glyph quad。
     *
     * @param bounds glyph 的逻辑坐标范围
     * @param uv atlas UV 范围
     * @param premultipliedRgba8 0xRRGGBBAA 预乘颜色
     */
    public void addGlyph(UiScreenRect bounds, UiUvRect uv, int premultipliedRgba8) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(uv, "uv");
        addGlyph(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                uv.u0(), uv.v0(), uv.u1(), uv.v1(), premultipliedRgba8);
    }

    /** 以标量直接写入当前 glyph run。 */
    public void addGlyph(double x, double y, double width, double height,
                         float u0, float v0, float u1, float v1,
                         int premultipliedRgba8) {
        ensureMutable();
        if (!glyphRunOpen) {
            throw new IllegalStateException("no glyph run is active");
        }
        appendQuad(x, y, width, height, u0, v0, u1, v1, premultipliedRgba8);
    }

    /** 完成当前 glyph run；空 run 不产生 primitive。 */
    public void endGlyphRun() {
        ensureMutable();
        if (!glyphRunOpen) {
            throw new IllegalStateException("no glyph run is active");
        }
        int count = quadCount - glyphFirstQuad;
        if (count != 0) {
            int primitiveIndex = appendPrimitive(UiPrimitiveKind.GLYPH_RUN, UiShaderVariant.GLYPH,
                    glyphTexture, glyphSampler, glyphBlend, glyphFirstQuad, count);
            // Store text effect data
            primitiveTextEffectTypes[primitiveIndex] = glyphTextEffectType;
            primitiveTextEffectColor1[primitiveIndex] = glyphTextEffectColor1;
            primitiveTextEffectColor2[primitiveIndex] = glyphTextEffectColor2;
            primitiveTextEffectThickness[primitiveIndex] = glyphTextEffectThickness;
            primitiveTextEffectOffsetX[primitiveIndex] = glyphTextEffectOffsetX;
            primitiveTextEffectOffsetY[primitiveIndex] = glyphTextEffectOffsetY;
            primitiveTextEffectBlur[primitiveIndex] = glyphTextEffectBlur;
            primitiveTextEffectAngle[primitiveIndex] = glyphTextEffectAngle;
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
     * 为当前 glyph run 设置文本特效。
     * 必须在 {@link #beginGlyphRun} 之后、{@link #endGlyphRun} 之前调用。
     *
     * @param effectType 特效类型 (0-5)
     * @param color1 主颜色 (RGBA8)
     * @param color2 次颜色 (RGBA8, 用于渐变)
     * @param thickness 描边粗细或发光半径
     * @param offsetX 阴影X偏移
     * @param offsetY 阴影Y偏移
     * @param blur 模糊半径
     * @param angle 渐变角度
     */
    /** Records a glyph effect; the gradient angle is expressed in degrees. */
    public void setGlyphTextEffect(byte effectType, int color1, int color2,
                                   float thickness, float offsetX, float offsetY,
                                   float blur, float angleDegrees) {
        ensureMutable();
        if (!glyphRunOpen) {
            throw new IllegalStateException("no glyph run is active");
        }
        glyphTextEffectType = effectType;
        glyphTextEffectColor1 = color1;
        glyphTextEffectColor2 = color2;
        glyphTextEffectThickness = thickness;
        glyphTextEffectOffsetX = offsetX;
        glyphTextEffectOffsetY = offsetY;
        glyphTextEffectBlur = blur;
        glyphTextEffectAngle = angleDegrees;
    }

    /**
     * 为当前活动的glyph run设置文本特效（便捷方法）。
     *
     * @param effect 文本特效
     */
    public void setTextEffect(com.kaleblangley.haikalat.subsystems.ui.text.TextEffect effect) {
        ensureMutable();
        if (effect == null) {
            effect = com.kaleblangley.haikalat.subsystems.ui.text.TextEffect.none();
        }
        currentTextEffectType = (byte) effect.type().ordinal();
        currentTextEffectColor1 = effect.primaryColor().packedPremultipliedRgba8();
        currentTextEffectColor2 = effect.secondaryColor().packedPremultipliedRgba8();
        currentTextEffectThickness = effect.thickness();
        currentTextEffectOffsetX = effect.offsetX();
        currentTextEffectOffsetY = effect.offsetY();
        currentTextEffectBlur = effect.blur();
        currentTextEffectAngle = effect.angleDegrees();
        if (glyphRunOpen) {
            copyCurrentTextEffectToGlyphRun();
        }
    }

    private void copyCurrentTextEffectToGlyphRun() {
        glyphTextEffectType = currentTextEffectType;
        glyphTextEffectColor1 = currentTextEffectColor1;
        glyphTextEffectColor2 = currentTextEffectColor2;
        glyphTextEffectThickness = currentTextEffectThickness;
        glyphTextEffectOffsetX = currentTextEffectOffsetX;
        glyphTextEffectOffsetY = currentTextEffectOffsetY;
        glyphTextEffectBlur = currentTextEffectBlur;
        glyphTextEffectAngle = currentTextEffectAngle;
    }

    private void resetCurrentTextEffect() {
        currentTextEffectType = 0;
        currentTextEffectColor1 = 0;
        currentTextEffectColor2 = 0;
        currentTextEffectThickness = 0.0f;
        currentTextEffectOffsetX = 0.0f;
        currentTextEffectOffsetY = 0.0f;
        currentTextEffectBlur = 0.0f;
        currentTextEffectAngle = 0.0f;
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
        UiScreenRect transformed = transform.transformBounds(
                clip.x(), clip.y(), clip.width(), clip.height());
        int index = appendPrimitive(UiPrimitiveKind.PUSH_CLIP, null,
                -1, -1, null, -1, 0);
        primitiveClipX[index] = transformed.x();
        primitiveClipY[index] = transformed.y();
        primitiveClipWidth[index] = transformed.width();
        primitiveClipHeight[index] = transformed.height();
        clipDepth++;
        return this;
    }

    /**
     * Applies a layout-independent transform to subsequently recorded quads and clips.
     * Calls may be nested; child transforms inherit the complete parent matrix.
     */
    public UiDisplayList pushTransform(UiVisualTransform value, UiScreenRect bounds) {
        ensureRecordable();
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(bounds, "bounds");
        if (transformDepth == transformStack.length) {
            transformStack = Arrays.copyOf(transformStack,
                    Math.max(2, transformStack.length << 1));
        }
        transformStack[transformDepth++] = transform;
        transform = transform.multiply(value.matrix(bounds));
        return this;
    }

    /** Restores the transform active before the matching {@link #pushTransform}. */
    public UiDisplayList popTransform() {
        ensureRecordable();
        if (transformDepth == 0) throw new IllegalStateException("transform stack underflow");
        transform = transformStack[--transformDepth];
        transformStack[transformDepth] = null;
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

    public UiDisplayList beginLayer(UiLayerDescription description) {
        ensureRecordable();
        int primitive = appendPrimitive(UiPrimitiveKind.LAYER_BEGIN, null,
                -1, -1, null, -1, 0);
        primitiveLayers[primitive] = Objects.requireNonNull(description, "description");
        layerDepth++;
        return this;
    }

    public UiDisplayList endLayer() {
        ensureRecordable();
        if (layerDepth == 0) throw new IllegalStateException("layer stack underflow");
        appendPrimitive(UiPrimitiveKind.LAYER_END, null,
                -1, -1, null, -1, 0);
        layerDepth--;
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

    /** 创建只能由 snapshot exchange 覆写、对消费方始终只读的槽 arena。 */
    static UiDisplayList snapshotArena() {
        return new UiDisplayList(true, 0, 0);
    }

    /** 在槽尚未被 acquire 时以数组复制覆写只读 arena，不产生新的 primitive 数组。 */
    void replaceSnapshotFrom(UiDisplayList source) {
        if (!frozen) {
            throw new IllegalStateException("snapshot target must be a frozen slot arena");
        }
        Objects.requireNonNull(source, "source").validateComplete();
        ensurePrimitiveCapacity(source.primitiveCount);
        ensureQuadCapacity(source.quadCount);
        primitiveCount = source.primitiveCount;
        quadCount = source.quadCount;
        copy(source.primitiveKinds, primitiveKinds, primitiveCount);
        copy(source.primitiveShaders, primitiveShaders, primitiveCount);
        copy(source.primitiveBlends, primitiveBlends, primitiveCount);
        copy(source.primitiveTextures, primitiveTextures, primitiveCount);
        copy(source.primitiveSamplers, primitiveSamplers, primitiveCount);
        copy(source.primitiveImageIds, primitiveImageIds, primitiveCount);
        copy(source.primitiveFirstQuads, primitiveFirstQuads, primitiveCount);
        copy(source.primitiveQuadCounts, primitiveQuadCounts, primitiveCount);
        copy(source.primitiveClipX, primitiveClipX, primitiveCount);
        copy(source.primitiveClipY, primitiveClipY, primitiveCount);
        copy(source.primitiveClipWidth, primitiveClipWidth, primitiveCount);
        copy(source.primitiveClipHeight, primitiveClipHeight, primitiveCount);
        copy(source.primitiveSdfKinds, primitiveSdfKinds, primitiveCount);
        copy(source.primitiveSdfRadii, primitiveSdfRadii, primitiveCount * 4);
        copy(source.primitiveSdfParams, primitiveSdfParams, primitiveCount * 4);
        copy(source.primitiveSdfGradientAngles, primitiveSdfGradientAngles, primitiveCount);
        copy(source.primitiveSdfFillColors, primitiveSdfFillColors, primitiveCount);
        copy(source.primitiveSdfBorderColors, primitiveSdfBorderColors, primitiveCount);
        copy(source.primitiveSdfGradientStartColors,
                primitiveSdfGradientStartColors, primitiveCount);
        copy(source.primitiveSdfGradientEndColors,
                primitiveSdfGradientEndColors, primitiveCount);
        copy(source.primitiveLayers, primitiveLayers, primitiveCount);
        copy(source.primitiveTextEffectTypes, primitiveTextEffectTypes, primitiveCount);
        copy(source.primitiveTextEffectColor1, primitiveTextEffectColor1, primitiveCount);
        copy(source.primitiveTextEffectColor2, primitiveTextEffectColor2, primitiveCount);
        copy(source.primitiveTextEffectThickness, primitiveTextEffectThickness, primitiveCount);
        copy(source.primitiveTextEffectOffsetX, primitiveTextEffectOffsetX, primitiveCount);
        copy(source.primitiveTextEffectOffsetY, primitiveTextEffectOffsetY, primitiveCount);
        copy(source.primitiveTextEffectBlur, primitiveTextEffectBlur, primitiveCount);
        copy(source.primitiveTextEffectAngle, primitiveTextEffectAngle, primitiveCount);
        copy(source.quadX, quadX, quadCount);
        copy(source.quadY, quadY, quadCount);
        copy(source.quadWidth, quadWidth, quadCount);
        copy(source.quadHeight, quadHeight, quadCount);
        copy(source.quadU0, quadU0, quadCount);
        copy(source.quadV0, quadV0, quadCount);
        copy(source.quadU1, quadU1, quadCount);
        copy(source.quadV1, quadV1, quadCount);
        copy(source.quadColors, quadColors, quadCount);
        copy(source.quadTransformM00, quadTransformM00, quadCount);
        copy(source.quadTransformM01, quadTransformM01, quadCount);
        copy(source.quadTransformM10, quadTransformM10, quadCount);
        copy(source.quadTransformM11, quadTransformM11, quadCount);
        copy(source.quadTransformX, quadTransformX, quadCount);
        copy(source.quadTransformY, quadTransformY, quadCount);
        clipDepth = 0;
        layerDepth = 0;
        transform = UiVisualTransform.Matrix.IDENTITY;
        transformDepth = 0;
        glyphRunOpen = false;
        glyphBlend = null;
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

    /** 返回逻辑图片 ID；普通纹理 primitive 返回 -1。 */
    public long primitiveImageId(int primitiveIndex) {
        requireDrawPrimitive(primitiveIndex);
        return primitiveImageIds[primitiveIndex];
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

    public UiSdfShape.Kind sdfKind(int primitiveIndex) {
        requireSdfPrimitive(primitiveIndex);
        return UiSdfShape.Kind.values()[primitiveSdfKinds[primitiveIndex]];
    }

    public float sdfRadius(int primitiveIndex, int corner) {
        requireSdfPrimitive(primitiveIndex);
        if (corner < 0 || corner >= 4) throw new IndexOutOfBoundsException("corner: " + corner);
        return primitiveSdfRadii[primitiveIndex * 4 + corner];
    }

    public float sdfParameter(int primitiveIndex, int parameter) {
        requireSdfPrimitive(primitiveIndex);
        if (parameter < 0 || parameter >= 4) {
            throw new IndexOutOfBoundsException("parameter: " + parameter);
        }
        return primitiveSdfParams[primitiveIndex * 4 + parameter];
    }

    public float sdfGradientAngle(int primitiveIndex) {
        requireSdfPrimitive(primitiveIndex);
        return primitiveSdfGradientAngles[primitiveIndex];
    }

    public int sdfFillColor(int primitiveIndex) {
        requireSdfPrimitive(primitiveIndex);
        return primitiveSdfFillColors[primitiveIndex];
    }

    public int sdfBorderColor(int primitiveIndex) {
        requireSdfPrimitive(primitiveIndex);
        return primitiveSdfBorderColors[primitiveIndex];
    }

    public int sdfGradientStartColor(int primitiveIndex) {
        requireSdfPrimitive(primitiveIndex);
        return primitiveSdfGradientStartColors[primitiveIndex];
    }

    public int sdfGradientEndColor(int primitiveIndex) {
        requireSdfPrimitive(primitiveIndex);
        return primitiveSdfGradientEndColors[primitiveIndex];
    }

    public UiLayerDescription primitiveLayer(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        if (primitiveKind(primitiveIndex) != UiPrimitiveKind.LAYER_BEGIN) {
            throw new IllegalArgumentException("primitive is not LAYER_BEGIN");
        }
        return primitiveLayers[primitiveIndex];
    }

    /** 返回文本特效类型 (0=none, 1=outline, 2=shadow, 3=glow, 4=inner_glow, 5=gradient)。 */
    public byte textEffectType(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        return primitiveTextEffectTypes[primitiveIndex];
    }

    /** 返回文本特效主颜色 (RGBA8)。 */
    public int textEffectColor1(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        return primitiveTextEffectColor1[primitiveIndex];
    }

    /** 返回文本特效次颜色 (RGBA8)，用于渐变。 */
    public int textEffectColor2(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        return primitiveTextEffectColor2[primitiveIndex];
    }

    /** 返回文本特效粗细或半径。 */
    public float textEffectThickness(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        return primitiveTextEffectThickness[primitiveIndex];
    }

    /** 返回文本特效X偏移。 */
    public float textEffectOffsetX(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        return primitiveTextEffectOffsetX[primitiveIndex];
    }

    /** 返回文本特效Y偏移。 */
    public float textEffectOffsetY(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        return primitiveTextEffectOffsetY[primitiveIndex];
    }

    /** 返回文本特效模糊半径。 */
    public float textEffectBlur(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        return primitiveTextEffectBlur[primitiveIndex];
    }

    /** 返回文本特效角度（用于渐变）。 */
    public float textEffectAngle(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        return primitiveTextEffectAngle[primitiveIndex];
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

    public UiVisualTransform.Matrix quadTransform(int quadIndex) {
        checkQuadIndex(quadIndex);
        return new UiVisualTransform.Matrix(
                quadTransformM00[quadIndex], quadTransformM01[quadIndex],
                quadTransformM10[quadIndex], quadTransformM11[quadIndex],
                quadTransformX[quadIndex], quadTransformY[quadIndex]);
    }

    double quadTransformM00(int quadIndex) { return quadTransformM00[quadIndex]; }
    double quadTransformM01(int quadIndex) { return quadTransformM01[quadIndex]; }
    double quadTransformM10(int quadIndex) { return quadTransformM10[quadIndex]; }
    double quadTransformM11(int quadIndex) { return quadTransformM11[quadIndex]; }
    double quadTransformX(int quadIndex) { return quadTransformX[quadIndex]; }
    double quadTransformY(int quadIndex) { return quadTransformY[quadIndex]; }

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
        UiDisplayListValidator.validateComplete(glyphRunOpen, clipDepth,
                transformDepth, layerDepth);
    }

    private int appendQuad(UiScreenRect bounds, UiUvRect uv, int color) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(uv, "uv");
        return appendQuad(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                uv.u0(), uv.v0(), uv.u1(), uv.v1(), color);
    }

    private int appendQuad(double x, double y, double width, double height,
                           float u0, float v0, float u1, float v1, int color) {
        ensureQuadCapacity(quadCount + 1);
        int index = quadCount++;
        quadX[index] = x;
        quadY[index] = y;
        quadWidth[index] = width;
        quadHeight[index] = height;
        quadU0[index] = u0;
        quadV0[index] = v0;
        quadU1[index] = u1;
        quadV1[index] = v1;
        quadColors[index] = color;
        quadTransformM00[index] = transform.m00();
        quadTransformM01[index] = transform.m01();
        quadTransformM10[index] = transform.m10();
        quadTransformM11[index] = transform.m11();
        quadTransformX[index] = transform.translateX();
        quadTransformY[index] = transform.translateY();
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
        primitiveImageIds[index] = -1L;
        primitiveFirstQuads[index] = firstQuad;
        primitiveQuadCounts[index] = quadCountValue;
        primitiveSdfKinds[index] = -1;
        return index;
    }

    private void requireSdfPrimitive(int primitiveIndex) {
        requireDrawPrimitive(primitiveIndex);
        if (primitiveKind(primitiveIndex) != UiPrimitiveKind.SDF_SHAPE) {
            throw new IllegalArgumentException("primitive is not SDF_SHAPE");
        }
    }

    private void requireDrawPrimitive(int primitiveIndex) {
        checkPrimitiveIndex(primitiveIndex);
        if (primitiveShaders[primitiveIndex] < 0) {
            throw new IllegalArgumentException("primitive does not produce a draw");
        }
    }

    private void ensureRecordable() {
        UiDisplayListValidator.ensureRecordable(frozen, glyphRunOpen);
    }

    private void ensureMutable() {
        UiDisplayListValidator.ensureMutable(frozen);
    }

    private static void requireResource(int id, String name) {
        UiDisplayListValidator.requireResource(id, name);
    }

    private void checkPrimitiveIndex(int index) {
        UiDisplayListValidator.checkIndex(index, primitiveCount, "primitive");
    }

    private void checkQuadIndex(int index) {
        UiDisplayListValidator.checkIndex(index, quadCount, "quad");
    }

    private void allocatePrimitives(int capacity) {
        primitiveKinds = new byte[capacity];
        primitiveShaders = new byte[capacity];
        primitiveBlends = new byte[capacity];
        primitiveTextures = new int[capacity];
        primitiveSamplers = new int[capacity];
        primitiveImageIds = new long[capacity];
        Arrays.fill(primitiveImageIds, -1L);
        primitiveFirstQuads = new int[capacity];
        primitiveQuadCounts = new int[capacity];
        primitiveClipX = new double[capacity];
        primitiveClipY = new double[capacity];
        primitiveClipWidth = new double[capacity];
        primitiveClipHeight = new double[capacity];
        primitiveSdfKinds = new byte[capacity];
        Arrays.fill(primitiveSdfKinds, (byte) -1);
        primitiveSdfRadii = new float[capacity * 4];
        primitiveSdfParams = new float[capacity * 4];
        primitiveSdfGradientAngles = new float[capacity];
        primitiveSdfFillColors = new int[capacity];
        primitiveSdfBorderColors = new int[capacity];
        primitiveSdfGradientStartColors = new int[capacity];
        primitiveSdfGradientEndColors = new int[capacity];
        primitiveLayers = new UiLayerDescription[capacity];

        // Text effect arrays
        primitiveTextEffectTypes = new byte[capacity];
        primitiveTextEffectColor1 = new int[capacity];
        primitiveTextEffectColor2 = new int[capacity];
        primitiveTextEffectThickness = new float[capacity];
        primitiveTextEffectOffsetX = new float[capacity];
        primitiveTextEffectOffsetY = new float[capacity];
        primitiveTextEffectBlur = new float[capacity];
        primitiveTextEffectAngle = new float[capacity];
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
        quadTransformM00 = new double[capacity];
        quadTransformM01 = new double[capacity];
        quadTransformM10 = new double[capacity];
        quadTransformM11 = new double[capacity];
        quadTransformX = new double[capacity];
        quadTransformY = new double[capacity];
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
        int previousCapacity = primitiveImageIds.length;
        primitiveImageIds = Arrays.copyOf(primitiveImageIds, capacity);
        Arrays.fill(primitiveImageIds, previousCapacity, capacity, -1L);
        primitiveFirstQuads = Arrays.copyOf(primitiveFirstQuads, capacity);
        primitiveQuadCounts = Arrays.copyOf(primitiveQuadCounts, capacity);
        primitiveClipX = Arrays.copyOf(primitiveClipX, capacity);
        primitiveClipY = Arrays.copyOf(primitiveClipY, capacity);
        primitiveClipWidth = Arrays.copyOf(primitiveClipWidth, capacity);
        primitiveClipHeight = Arrays.copyOf(primitiveClipHeight, capacity);
        int previousSdfCapacity = primitiveSdfKinds.length;
        primitiveSdfKinds = Arrays.copyOf(primitiveSdfKinds, capacity);
        Arrays.fill(primitiveSdfKinds, previousSdfCapacity, capacity, (byte) -1);
        primitiveSdfRadii = Arrays.copyOf(primitiveSdfRadii, capacity * 4);
        primitiveSdfParams = Arrays.copyOf(primitiveSdfParams, capacity * 4);
        primitiveSdfGradientAngles = Arrays.copyOf(primitiveSdfGradientAngles, capacity);
        primitiveSdfFillColors = Arrays.copyOf(primitiveSdfFillColors, capacity);
        primitiveSdfBorderColors = Arrays.copyOf(primitiveSdfBorderColors, capacity);
        primitiveSdfGradientStartColors = Arrays.copyOf(
                primitiveSdfGradientStartColors, capacity);
        primitiveSdfGradientEndColors = Arrays.copyOf(
                primitiveSdfGradientEndColors, capacity);
        primitiveLayers = Arrays.copyOf(primitiveLayers, capacity);

        // Text effect arrays
        primitiveTextEffectTypes = Arrays.copyOf(primitiveTextEffectTypes, capacity);
        primitiveTextEffectColor1 = Arrays.copyOf(primitiveTextEffectColor1, capacity);
        primitiveTextEffectColor2 = Arrays.copyOf(primitiveTextEffectColor2, capacity);
        primitiveTextEffectThickness = Arrays.copyOf(primitiveTextEffectThickness, capacity);
        primitiveTextEffectOffsetX = Arrays.copyOf(primitiveTextEffectOffsetX, capacity);
        primitiveTextEffectOffsetY = Arrays.copyOf(primitiveTextEffectOffsetY, capacity);
        primitiveTextEffectBlur = Arrays.copyOf(primitiveTextEffectBlur, capacity);
        primitiveTextEffectAngle = Arrays.copyOf(primitiveTextEffectAngle, capacity);
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
        quadTransformM00 = Arrays.copyOf(quadTransformM00, capacity);
        quadTransformM01 = Arrays.copyOf(quadTransformM01, capacity);
        quadTransformM10 = Arrays.copyOf(quadTransformM10, capacity);
        quadTransformM11 = Arrays.copyOf(quadTransformM11, capacity);
        quadTransformX = Arrays.copyOf(quadTransformX, capacity);
        quadTransformY = Arrays.copyOf(quadTransformY, capacity);
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

    private static void copy(byte[] source, byte[] target, int count) {
        System.arraycopy(source, 0, target, 0, count);
    }

    private static void copy(int[] source, int[] target, int count) {
        System.arraycopy(source, 0, target, 0, count);
    }

    private static void copy(long[] source, long[] target, int count) {
        System.arraycopy(source, 0, target, 0, count);
    }

    private static void copy(float[] source, float[] target, int count) {
        System.arraycopy(source, 0, target, 0, count);
    }

    private static void copy(double[] source, double[] target, int count) {
        System.arraycopy(source, 0, target, 0, count);
    }

    private static void copy(Object[] source, Object[] target, int count) {
        System.arraycopy(source, 0, target, 0, count);
    }
}
