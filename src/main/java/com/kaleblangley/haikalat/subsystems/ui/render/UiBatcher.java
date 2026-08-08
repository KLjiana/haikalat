package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.UiBatchBreakStats;

import java.util.Arrays;
import java.util.Objects;

/**
 * 按 display list 原顺序生成 UI draw batch。
 *
 * <p>仅相邻且 shader、texture、sampler、逻辑 clip 和 blend key 完全相同的
 * draw primitive 可以合并。clip push/pop 与 paint boundary 即使不改变最终状态也会
 * 终止当前 batch，因而不会跨半透明内容或用户 paint 边界重排。</p>
 */
public final class UiBatcher {
    private static final int DEFAULT_CAPACITY = 16;

    private int[] firstPrimitives = new int[DEFAULT_CAPACITY];
    private int[] primitiveCounts = new int[DEFAULT_CAPACITY];
    private int[] firstQuads = new int[DEFAULT_CAPACITY];
    private int[] quadCounts = new int[DEFAULT_CAPACITY];
    private byte[] shaders = new byte[DEFAULT_CAPACITY];
    private byte[] blends = new byte[DEFAULT_CAPACITY];
    private int[] textures = new int[DEFAULT_CAPACITY];
    private int[] samplers = new int[DEFAULT_CAPACITY];
    private long[] imageIds = filledImageIds(DEFAULT_CAPACITY);
    private boolean[] clipEnabled = new boolean[DEFAULT_CAPACITY];
    private double[] clipX = new double[DEFAULT_CAPACITY];
    private double[] clipY = new double[DEFAULT_CAPACITY];
    private double[] clipWidth = new double[DEFAULT_CAPACITY];
    private double[] clipHeight = new double[DEFAULT_CAPACITY];

    private double[] stackX = new double[8];
    private double[] stackY = new double[8];
    private double[] stackWidth = new double[8];
    private double[] stackHeight = new double[8];

    /**
     * 生成不可变 batch 结果。
     *
     * @param displayList 完整且 clip 平衡的 display list
     * @return 保持 paint 顺序的 batch
     */
    public Result batch(UiDisplayList displayList) {
        Objects.requireNonNull(displayList, "displayList").validateComplete();
        int batchCount = 0;
        int clipDepth = 0;
        boolean mayMergePrevious = false;
        long orderBarriers = 0;
        long shaderChanges = 0;
        long textureChanges = 0;
        long samplerChanges = 0;
        long blendChanges = 0;
        long clipChanges = 0;

        for (int primitive = 0; primitive < displayList.primitiveCount(); primitive++) {
            UiPrimitiveKind kind = displayList.primitiveKind(primitive);
            if (kind == UiPrimitiveKind.PUSH_CLIP) {
                ensureStackCapacity(clipDepth + 1);
                double x = displayList.primitiveClipX(primitive);
                double y = displayList.primitiveClipY(primitive);
                double width = displayList.primitiveClipWidth(primitive);
                double height = displayList.primitiveClipHeight(primitive);
                if (clipDepth != 0) {
                    int parent = clipDepth - 1;
                    double left = Math.max(stackX[parent], x);
                    double top = Math.max(stackY[parent], y);
                    double right = Math.min(stackX[parent] + stackWidth[parent], x + width);
                    double bottom = Math.min(stackY[parent] + stackHeight[parent], y + height);
                    x = left;
                    y = top;
                    width = Math.max(0.0, right - left);
                    height = Math.max(0.0, bottom - top);
                }
                stackX[clipDepth] = x;
                stackY[clipDepth] = y;
                stackWidth[clipDepth] = width;
                stackHeight[clipDepth] = height;
                clipDepth++;
                mayMergePrevious = false;
                continue;
            }
            if (kind == UiPrimitiveKind.POP_CLIP) {
                if (clipDepth == 0) {
                    throw new IllegalStateException("clip stack underflow while batching");
                }
                clipDepth--;
                mayMergePrevious = false;
                continue;
            }
            if (kind == UiPrimitiveKind.PAINT_BOUNDARY
                    || kind == UiPrimitiveKind.LAYER_BEGIN
                    || kind == UiPrimitiveKind.LAYER_END) {
                mayMergePrevious = false;
                continue;
            }

            UiShaderVariant shader = displayList.primitiveShader(primitive);
            UiBlendMode blend = displayList.primitiveBlend(primitive);
            int texture = displayList.primitiveTexture(primitive);
            int sampler = displayList.primitiveSampler(primitive);
            long imageId = displayList.primitiveImageId(primitive);
            boolean hasClip = clipDepth != 0;
            int activeClip = clipDepth - 1;
            boolean compatible = mayMergePrevious && batchCount != 0
                    && keyEquals(displayList, batchCount - 1, primitive, shader, texture,
                    sampler, imageId, blend,
                    hasClip, activeClip)
                    && (shader != UiShaderVariant.SDF
                    || displayList.primitiveKind(primitive) != UiPrimitiveKind.SDF_SHAPE
                    || firstPrimitives[batchCount - 1] == primitive);
            if (compatible) {
                primitiveCounts[batchCount - 1]++;
                quadCounts[batchCount - 1] += displayList.primitiveQuadCount(primitive);
            } else {
                if (batchCount != 0) {
                    if (!mayMergePrevious) orderBarriers++;
                    else if (shaders[batchCount - 1] != (byte) shader.ordinal()) shaderChanges++;
                    else if (textures[batchCount - 1] != texture
                            || imageIds[batchCount - 1] != imageId) textureChanges++;
                    else if (samplers[batchCount - 1] != sampler) samplerChanges++;
                    else if (blends[batchCount - 1] != (byte) blend.ordinal()) blendChanges++;
                    else if (shader == UiShaderVariant.GLYPH
                            && !sameTextEffect(displayList, firstPrimitives[batchCount - 1], primitive)) {
                        shaderChanges++;
                    }
                    else clipChanges++;
                }
                ensureBatchCapacity(batchCount + 1);
                firstPrimitives[batchCount] = primitive;
                primitiveCounts[batchCount] = 1;
                firstQuads[batchCount] = displayList.primitiveFirstQuad(primitive);
                quadCounts[batchCount] = displayList.primitiveQuadCount(primitive);
                shaders[batchCount] = (byte) shader.ordinal();
                blends[batchCount] = (byte) blend.ordinal();
                textures[batchCount] = texture;
                samplers[batchCount] = sampler;
                imageIds[batchCount] = imageId;
                clipEnabled[batchCount] = hasClip;
                if (hasClip) {
                    clipX[batchCount] = stackX[activeClip];
                    clipY[batchCount] = stackY[activeClip];
                    clipWidth[batchCount] = stackWidth[activeClip];
                    clipHeight[batchCount] = stackHeight[activeClip];
                }
                batchCount++;
            }
            mayMergePrevious = true;
        }

        if (clipDepth != 0) {
            throw new IllegalStateException("clip stack is not balanced after batching");
        }
        UiBatchBreakStats breakStats = new UiBatchBreakStats(orderBarriers, shaderChanges,
                textureChanges, samplerChanges, blendChanges, clipChanges);
        return batchCount == 0 ? Result.EMPTY : new Result(this, batchCount, breakStats);
    }

    private boolean keyEquals(UiDisplayList displayList, int batch, int primitive,
                              UiShaderVariant shader, int texture, int sampler, long imageId,
                              UiBlendMode blend, boolean hasClip, int activeClip) {
        if (shaders[batch] != (byte) shader.ordinal()
                || textures[batch] != texture
                || samplers[batch] != sampler
                || imageIds[batch] != imageId
                || blends[batch] != (byte) blend.ordinal()
                || clipEnabled[batch] != hasClip) {
            return false;
        }
        if (shader == UiShaderVariant.GLYPH
                && !sameTextEffect(displayList, firstPrimitives[batch], primitive)) {
            return false;
        }
        return !hasClip
                || equal(clipX[batch], stackX[activeClip])
                && equal(clipY[batch], stackY[activeClip])
                && equal(clipWidth[batch], stackWidth[activeClip])
                && equal(clipHeight[batch], stackHeight[activeClip]);
    }

    private static boolean equal(double first, double second) {
        return Double.doubleToLongBits(first) == Double.doubleToLongBits(second);
    }

    private static boolean sameTextEffect(UiDisplayList displayList, int first, int second) {
        return displayList.textEffectType(first) == displayList.textEffectType(second)
                && displayList.textEffectColor1(first) == displayList.textEffectColor1(second)
                && displayList.textEffectColor2(first) == displayList.textEffectColor2(second)
                && Float.compare(displayList.textEffectThickness(first),
                        displayList.textEffectThickness(second)) == 0
                && Float.compare(displayList.textEffectOffsetX(first),
                        displayList.textEffectOffsetX(second)) == 0
                && Float.compare(displayList.textEffectOffsetY(first),
                        displayList.textEffectOffsetY(second)) == 0
                && Float.compare(displayList.textEffectBlur(first),
                        displayList.textEffectBlur(second)) == 0
                && Float.compare(displayList.textEffectAngle(first),
                        displayList.textEffectAngle(second)) == 0;
    }

    private void ensureBatchCapacity(int required) {
        if (required <= firstPrimitives.length) {
            return;
        }
        int capacity = Math.max(required, firstPrimitives.length + (firstPrimitives.length >> 1));
        firstPrimitives = Arrays.copyOf(firstPrimitives, capacity);
        primitiveCounts = Arrays.copyOf(primitiveCounts, capacity);
        firstQuads = Arrays.copyOf(firstQuads, capacity);
        quadCounts = Arrays.copyOf(quadCounts, capacity);
        shaders = Arrays.copyOf(shaders, capacity);
        blends = Arrays.copyOf(blends, capacity);
        textures = Arrays.copyOf(textures, capacity);
        samplers = Arrays.copyOf(samplers, capacity);
        int previous = imageIds.length;
        imageIds = Arrays.copyOf(imageIds, capacity);
        Arrays.fill(imageIds, previous, capacity, -1L);
        clipEnabled = Arrays.copyOf(clipEnabled, capacity);
        clipX = Arrays.copyOf(clipX, capacity);
        clipY = Arrays.copyOf(clipY, capacity);
        clipWidth = Arrays.copyOf(clipWidth, capacity);
        clipHeight = Arrays.copyOf(clipHeight, capacity);
    }

    private void ensureStackCapacity(int required) {
        if (required <= stackX.length) {
            return;
        }
        int capacity = Math.max(required, stackX.length << 1);
        stackX = Arrays.copyOf(stackX, capacity);
        stackY = Arrays.copyOf(stackY, capacity);
        stackWidth = Arrays.copyOf(stackWidth, capacity);
        stackHeight = Arrays.copyOf(stackHeight, capacity);
    }

    /** 不可变的结构化 batch 结果。 */
    public static final class Result {
        private static final UiShaderVariant[] SHADER_VALUES = UiShaderVariant.values();
        private static final UiBlendMode[] BLEND_VALUES = UiBlendMode.values();
        private static final Result EMPTY = new Result();

        private final int[] firstPrimitives;
        private final int[] primitiveCounts;
        private final int[] firstQuads;
        private final int[] quadCounts;
        private final byte[] shaders;
        private final byte[] blends;
        private final int[] textures;
        private final int[] samplers;
        private final long[] imageIds;
        private final boolean[] clipEnabled;
        private final double[] clipX;
        private final double[] clipY;
        private final double[] clipWidth;
        private final double[] clipHeight;
        private final UiBatchBreakStats breakStatistics;

        private Result() {
            firstPrimitives = new int[0];
            primitiveCounts = new int[0];
            firstQuads = new int[0];
            quadCounts = new int[0];
            shaders = new byte[0];
            blends = new byte[0];
            textures = new int[0];
            samplers = new int[0];
            imageIds = new long[0];
            clipEnabled = new boolean[0];
            clipX = new double[0];
            clipY = new double[0];
            clipWidth = new double[0];
            clipHeight = new double[0];
            breakStatistics = UiBatchBreakStats.EMPTY;
        }

        private Result(UiBatcher source, int count, UiBatchBreakStats breakStatistics) {
            firstPrimitives = Arrays.copyOf(source.firstPrimitives, count);
            primitiveCounts = Arrays.copyOf(source.primitiveCounts, count);
            firstQuads = Arrays.copyOf(source.firstQuads, count);
            quadCounts = Arrays.copyOf(source.quadCounts, count);
            shaders = Arrays.copyOf(source.shaders, count);
            blends = Arrays.copyOf(source.blends, count);
            textures = Arrays.copyOf(source.textures, count);
            samplers = Arrays.copyOf(source.samplers, count);
            imageIds = Arrays.copyOf(source.imageIds, count);
            clipEnabled = Arrays.copyOf(source.clipEnabled, count);
            clipX = Arrays.copyOf(source.clipX, count);
            clipY = Arrays.copyOf(source.clipY, count);
            clipWidth = Arrays.copyOf(source.clipWidth, count);
            clipHeight = Arrays.copyOf(source.clipHeight, count);
            this.breakStatistics = Objects.requireNonNull(breakStatistics, "breakStatistics");
        }

        /** 返回 batch 数量。 */
        public int size() {
            return firstPrimitives.length;
        }

        /** 返回形成新 batch 的互斥原因统计；首个 batch 不计为 break。 */
        public UiBatchBreakStats breakStatistics() {
            return breakStatistics;
        }

        /** 返回 batch 首个 display-list primitive。 */
        public int firstPrimitive(int batchIndex) {
            checkIndex(batchIndex);
            return firstPrimitives[batchIndex];
        }

        /** 返回 batch 合并的相邻 primitive 数量。 */
        public int primitiveCount(int batchIndex) {
            checkIndex(batchIndex);
            return primitiveCounts[batchIndex];
        }

        /** 返回 batch 首个 quad arena 索引。 */
        public int firstQuad(int batchIndex) {
            checkIndex(batchIndex);
            return firstQuads[batchIndex];
        }

        /** 返回 batch 的 quad 数量。 */
        public int quadCount(int batchIndex) {
            checkIndex(batchIndex);
            return quadCounts[batchIndex];
        }

        /** 返回 shader 变体。 */
        public UiShaderVariant shader(int batchIndex) {
            checkIndex(batchIndex);
            return SHADER_VALUES[shaders[batchIndex]];
        }

        /** 返回纹理或 atlas 页 ID；不采样时为 -1。 */
        public int texture(int batchIndex) {
            checkIndex(batchIndex);
            return textures[batchIndex];
        }

        /** 返回 sampler ID；不采样时为 -1。 */
        public int sampler(int batchIndex) {
            checkIndex(batchIndex);
            return samplers[batchIndex];
        }

        /** 返回需要在 render-record 阶段重新解析的逻辑图片 ID；普通纹理为 -1。 */
        public long imageId(int batchIndex) {
            checkIndex(batchIndex);
            return imageIds[batchIndex];
        }

        /** 返回混合模式。 */
        public UiBlendMode blend(int batchIndex) {
            checkIndex(batchIndex);
            return BLEND_VALUES[blends[batchIndex]];
        }

        /** 返回 batch 是否启用裁剪。 */
        public boolean hasClip(int batchIndex) {
            checkIndex(batchIndex);
            return clipEnabled[batchIndex];
        }

        /** 返回逻辑裁剪；未启用裁剪时抛出异常。 */
        public UiScreenRect clip(int batchIndex) {
            checkIndex(batchIndex);
            if (!clipEnabled[batchIndex]) {
                throw new IllegalStateException("batch has no clip");
            }
            return new UiScreenRect(clipX[batchIndex], clipY[batchIndex],
                    clipWidth[batchIndex], clipHeight[batchIndex]);
        }

        /** 把 batch 的逻辑裁剪转换为 framebuffer scissor。 */
        public GlScissorRect glScissor(int batchIndex, double scaleX, double scaleY,
                                       int framebufferWidth, int framebufferHeight) {
            return clip(batchIndex).toGlScissor(scaleX, scaleY,
                    framebufferWidth, framebufferHeight);
        }

        private void checkIndex(int index) {
            if (index < 0 || index >= firstPrimitives.length) {
                throw new IndexOutOfBoundsException("batch index: " + index);
            }
        }
    }

    private static long[] filledImageIds(int capacity) {
        long[] values = new long[capacity];
        Arrays.fill(values, -1L);
        return values;
    }
}
