package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable description of one explicitly requested UI compositor layer. */
public final class UiLayerDescription {
    private final String id;
    private final String parentId;
    private final UiScreenRect bounds;
    private final float framebufferScale;
    private final float opacity;
    private final UiBlendMode blendMode;
    private final MaskMode maskMode;
    private final Set<Effect> effects;
    private final boolean backdropRequired;
    private final int zOrder;
    private final CachePolicy cachePolicy;
    private final long dirtyRevision;
    private final float blurRadius;
    private final int blurDownsample;
    private final UiColor tint;
    private final float brightness;
    private final float contrast;
    private final float saturation;

    private UiLayerDescription(Builder builder) {
        id = requireId(builder.id, "id");
        parentId = builder.parentId == null || builder.parentId.isBlank()
                ? "" : requireId(builder.parentId, "parentId");
        if (id.equals(parentId)) throw new IllegalArgumentException("layer cannot parent itself");
        bounds = Objects.requireNonNull(builder.bounds, "bounds");
        if (bounds.isEmpty()) throw new IllegalArgumentException("layer bounds must not be empty");
        framebufferScale = builder.framebufferScale;
        opacity = builder.opacity;
        blendMode = Objects.requireNonNull(builder.blendMode, "blendMode");
        maskMode = Objects.requireNonNull(builder.maskMode, "maskMode");
        effects = Set.copyOf(builder.effects);
        backdropRequired = builder.backdropRequired;
        zOrder = builder.zOrder;
        cachePolicy = Objects.requireNonNull(builder.cachePolicy, "cachePolicy");
        dirtyRevision = builder.dirtyRevision;
        blurRadius = clamp(builder.blurRadius, 0.0f, 64.0f, "blurRadius");
        blurDownsample = requireDownsample(builder.blurDownsample);
        tint = Objects.requireNonNull(builder.tint, "tint");
        brightness = requireFinite(builder.brightness, "brightness");
        contrast = requireFinite(builder.contrast, "contrast");
        saturation = requireFinite(builder.saturation, "saturation");
        if (!Float.isFinite(framebufferScale) || framebufferScale <= 0.0f
                || framebufferScale > 1.0f) {
            throw new IllegalArgumentException("framebufferScale must be within (0, 1]");
        }
        if (!Float.isFinite(opacity) || opacity < 0.0f || opacity > 1.0f) {
            throw new IllegalArgumentException("opacity must be within [0, 1]");
        }
        if (dirtyRevision < 0L) throw new IllegalArgumentException("dirtyRevision must be non-negative");
        if (backdropRequired && !effects.contains(Effect.BACKDROP_BLUR)) {
            throw new IllegalArgumentException("backdropRequired needs BACKDROP_BLUR");
        }
    }

    public static Builder builder(String id, UiScreenRect bounds) {
        return new Builder(id, bounds);
    }

    public String id() { return id; }
    public String parentId() { return parentId; }
    public UiScreenRect bounds() { return bounds; }
    public float framebufferScale() { return framebufferScale; }
    public float opacity() { return opacity; }
    public UiBlendMode blendMode() { return blendMode; }
    public MaskMode maskMode() { return maskMode; }
    public Set<Effect> effects() { return effects; }
    public boolean backdropRequired() { return backdropRequired; }
    public int zOrder() { return zOrder; }
    public CachePolicy cachePolicy() { return cachePolicy; }
    public long dirtyRevision() { return dirtyRevision; }
    public float blurRadius() { return blurRadius; }
    public int blurDownsample() { return blurDownsample; }
    public UiColor tint() { return tint; }
    public float brightness() { return brightness; }
    public float contrast() { return contrast; }
    public float saturation() { return saturation; }

    public long estimatedPixels() {
        return Math.max(1L, Math.round(bounds.width() * framebufferScale))
                * Math.max(1L, Math.round(bounds.height() * framebufferScale));
    }

    public enum MaskMode { NONE, CLIP, ROUNDED }
    public enum Effect {
        DROP_SHADOW, INNER_SHADOW, BLUR, BACKDROP_BLUR, GLOW, COLOR_TRANSFORM, TINT
    }
    public enum CachePolicy { NONE, WHEN_CLEAN, ALWAYS }

    public static final class Builder {
        private final String id;
        private final UiScreenRect bounds;
        private String parentId = "";
        private float framebufferScale = 1.0f;
        private float opacity = 1.0f;
        private UiBlendMode blendMode = UiBlendMode.PREMULTIPLIED_ALPHA;
        private MaskMode maskMode = MaskMode.NONE;
        private final EnumSet<Effect> effects = EnumSet.noneOf(Effect.class);
        private boolean backdropRequired;
        private int zOrder;
        private CachePolicy cachePolicy = CachePolicy.WHEN_CLEAN;
        private long dirtyRevision;
        private float blurRadius;
        private int blurDownsample = 1;
        private UiColor tint = UiColor.TRANSPARENT;
        private float brightness = 1.0f;
        private float contrast = 1.0f;
        private float saturation = 1.0f;

        private Builder(String id, UiScreenRect bounds) {
            this.id = id;
            this.bounds = bounds;
        }

        public Builder parent(String value) { parentId = value; return this; }
        public Builder framebufferScale(float value) { framebufferScale = value; return this; }
        public Builder opacity(float value) { opacity = value; return this; }
        public Builder blendMode(UiBlendMode value) { blendMode = value; return this; }
        public Builder mask(MaskMode value) { maskMode = value; return this; }
        public Builder effect(Effect value) { effects.add(Objects.requireNonNull(value)); return this; }
        public Builder backdropRequired(boolean value) { backdropRequired = value; return this; }
        public Builder zOrder(int value) { zOrder = value; return this; }
        public Builder cachePolicy(CachePolicy value) { cachePolicy = value; return this; }
        public Builder dirtyRevision(long value) { dirtyRevision = value; return this; }
        public Builder blur(float radius, int downsample) {
            blurRadius = radius;
            blurDownsample = downsample;
            if (radius > 0.0f) effects.add(Effect.BLUR);
            return this;
        }
        public Builder colorTransform(float brightness, float contrast, float saturation) {
            this.brightness = brightness;
            this.contrast = contrast;
            this.saturation = saturation;
            effects.add(Effect.COLOR_TRANSFORM);
            return this;
        }
        public Builder tint(UiColor value) {
            tint = value;
            effects.add(Effect.TINT);
            return this;
        }
        public UiLayerDescription build() { return new UiLayerDescription(this); }
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(name + " must be a stable identifier");
        }
        return value;
    }

    private static float clamp(float value, float minimum, float maximum, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float requireFinite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        return value;
    }

    private static int requireDownsample(int value) {
        if (value != 1 && value != 2 && value != 4) {
            throw new IllegalArgumentException("blurDownsample must be 1, 2, or 4");
        }
        return value;
    }
}
