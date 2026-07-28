package com.kaleblangley.haikalat.subsystems.ui.vfx;

import com.kaleblangley.haikalat.subsystems.ui.render.UiBlendMode;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;

import java.util.Objects;

/** Immutable definition for a bounded, deterministic screen-space UI effect. */
public final class UiEffectDefinition {
    private final Type type;
    private final float durationSeconds;
    private final float particleLifetimeSeconds;
    private final long seed;
    private final AnchorMode anchorMode;
    private final UiScreenRect localBounds;
    private final ClipPolicy clipPolicy;
    private final UiBlendMode blendMode;
    private final UiColor startColor;
    private final UiColor endColor;
    private final int maximumParticles;
    private final ReducedMotionFallback reducedMotionFallback;

    private UiEffectDefinition(Builder builder) {
        type = Objects.requireNonNull(builder.type, "type");
        durationSeconds = positiveFinite(builder.durationSeconds, "durationSeconds");
        particleLifetimeSeconds = positiveFinite(
                Math.min(builder.particleLifetimeSeconds, durationSeconds),
                "particleLifetimeSeconds");
        seed = builder.seed;
        anchorMode = Objects.requireNonNull(builder.anchorMode, "anchorMode");
        localBounds = Objects.requireNonNull(builder.localBounds, "localBounds");
        clipPolicy = Objects.requireNonNull(builder.clipPolicy, "clipPolicy");
        blendMode = Objects.requireNonNull(builder.blendMode, "blendMode");
        startColor = Objects.requireNonNull(builder.startColor, "startColor");
        endColor = Objects.requireNonNull(builder.endColor, "endColor");
        maximumParticles = builder.maximumParticles;
        reducedMotionFallback = Objects.requireNonNull(
                builder.reducedMotionFallback, "reducedMotionFallback");
        if (maximumParticles < 0 || maximumParticles > 4096) {
            throw new IllegalArgumentException("maximumParticles must be within [0, 4096]");
        }
        if (particleType(type) && maximumParticles == 0) {
            throw new IllegalArgumentException(type + " requires a positive particle capacity");
        }
    }

    public static Builder builder(Type type) { return new Builder(type); }
    public Type type() { return type; }
    public float durationSeconds() { return durationSeconds; }
    public float particleLifetimeSeconds() { return particleLifetimeSeconds; }
    public long seed() { return seed; }
    public AnchorMode anchorMode() { return anchorMode; }
    public UiScreenRect localBounds() { return localBounds; }
    public ClipPolicy clipPolicy() { return clipPolicy; }
    public UiBlendMode blendMode() { return blendMode; }
    public UiColor startColor() { return startColor; }
    public UiColor endColor() { return endColor; }
    public int maximumParticles() { return maximumParticles; }
    public ReducedMotionFallback reducedMotionFallback() { return reducedMotionFallback; }

    public enum Type { SHIMMER, RIPPLE, DISSOLVE, SPARK, CONFETTI, TRAIL, SCANLINE, GLITCH }
    public enum AnchorMode { NODE_BOUNDS, CONTENT_BOUNDS, EDGE, CORNER, POINTER, EXPLICIT_POINT, PATH_PROGRESS }
    public enum ClipPolicy { INHERIT, NODE_BOUNDS, NONE }
    public enum ReducedMotionFallback { NONE, COLOR_FEEDBACK, OPACITY_FEEDBACK }

    public static final class Builder {
        private final Type type;
        private float durationSeconds = 0.6f;
        private float particleLifetimeSeconds = 0.6f;
        private long seed = 1L;
        private AnchorMode anchorMode = AnchorMode.NODE_BOUNDS;
        private UiScreenRect localBounds = UiScreenRect.EMPTY;
        private ClipPolicy clipPolicy = ClipPolicy.INHERIT;
        private UiBlendMode blendMode = UiBlendMode.PREMULTIPLIED_ALPHA;
        private UiColor startColor = UiColor.WHITE;
        private UiColor endColor = UiColor.TRANSPARENT;
        private int maximumParticles;
        private ReducedMotionFallback reducedMotionFallback =
                ReducedMotionFallback.COLOR_FEEDBACK;

        private Builder(Type type) {
            this.type = Objects.requireNonNull(type, "type");
            maximumParticles = switch (type) {
                case SPARK -> 24;
                case CONFETTI -> 96;
                case DISSOLVE -> 128;
                case TRAIL -> 32;
                default -> 0;
            };
        }

        public Builder duration(float value) { durationSeconds = value; return this; }
        public Builder particleLifetime(float value) { particleLifetimeSeconds = value; return this; }
        public Builder seed(long value) { seed = value; return this; }
        public Builder anchor(AnchorMode value) { anchorMode = value; return this; }
        public Builder localBounds(UiScreenRect value) { localBounds = value; return this; }
        public Builder clip(ClipPolicy value) { clipPolicy = value; return this; }
        public Builder blend(UiBlendMode value) { blendMode = value; return this; }
        public Builder colors(UiColor start, UiColor end) {
            startColor = start;
            endColor = end;
            return this;
        }
        public Builder maximumParticles(int value) { maximumParticles = value; return this; }
        public Builder reducedMotion(ReducedMotionFallback value) {
            reducedMotionFallback = value;
            return this;
        }
        public UiEffectDefinition build() { return new UiEffectDefinition(this); }
    }

    private static boolean particleType(Type type) {
        return type == Type.SPARK || type == Type.CONFETTI
                || type == Type.DISSOLVE || type == Type.TRAIL;
    }

    private static float positiveFinite(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
        return value;
    }
}
