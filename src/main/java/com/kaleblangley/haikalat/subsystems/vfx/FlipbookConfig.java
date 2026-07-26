package com.kaleblangley.haikalat.subsystems.vfx;

/**
 * 规则网格序列帧配置。帧索引按实例时间推进；随机起始帧由 EffectInstance seed 确定。
 */
public record FlipbookConfig(
        int columns,
        int rows,
        int frameCount,
        float framesPerSecond,
        boolean randomStartFrame,
        boolean interpolateFrames
) {
    public FlipbookConfig {
        if (columns <= 0) throw new IllegalArgumentException("columns must be positive");
        if (rows <= 0) throw new IllegalArgumentException("rows must be positive");
        int capacity = Math.multiplyExact(columns, rows);
        if (frameCount <= 0 || frameCount > capacity) {
            throw new IllegalArgumentException("frameCount must be in [1, columns * rows]");
        }
        ParticleEmitter.requireFinitePositive(framesPerSecond, "framesPerSecond");
    }

    /** 返回指定帧在完整 atlas 中的 UV 区域，帧顺序从左到右、从上到下。 */
    public VfxUvRegion frameRegion(int frame) {
        if (frame < 0 || frame >= frameCount) {
            throw new IllegalArgumentException("frame must be in [0, frameCount)");
        }
        float width = 1.0f / columns;
        float height = 1.0f / rows;
        int column = frame % columns;
        int row = frame / columns;
        float minimumU = column * width;
        float minimumV = row * height;
        return new VfxUvRegion(minimumU, minimumV, minimumU + width, minimumV + height);
    }

    public VfxUvRegion frameRegion(int frame, VfxUvRegion atlasRegion) {
        VfxUvRegion local = frameRegion(frame);
        float atlasWidth = atlasRegion.maximumU() - atlasRegion.minimumU();
        float atlasHeight = atlasRegion.maximumV() - atlasRegion.minimumV();
        return new VfxUvRegion(
                atlasRegion.minimumU() + local.minimumU() * atlasWidth,
                atlasRegion.minimumV() + local.minimumV() * atlasHeight,
                atlasRegion.minimumU() + local.maximumU() * atlasWidth,
                atlasRegion.minimumV() + local.maximumV() * atlasHeight);
    }

    public FrameSample sample(float ageSeconds, int startFrame) {
        if (!Float.isFinite(ageSeconds) || ageSeconds < 0.0f) {
            throw new IllegalArgumentException("ageSeconds must be finite and non-negative");
        }
        if (startFrame < 0 || startFrame >= frameCount) {
            throw new IllegalArgumentException("startFrame must be in [0, frameCount)");
        }
        float framePosition = startFrame + ageSeconds * framesPerSecond;
        int wholeFrame = (int) Math.floor(framePosition);
        int current = Math.floorMod(wholeFrame, frameCount);
        int next = (current + 1) % frameCount;
        float blend = interpolateFrames ? framePosition - (float) Math.floor(framePosition) : 0.0f;
        return new FrameSample(current, next, blend);
    }

    public record FrameSample(int currentFrame, int nextFrame, float blend) {
    }
}
