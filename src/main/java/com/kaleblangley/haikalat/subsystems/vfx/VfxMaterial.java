package com.kaleblangley.haikalat.subsystems.vfx;

import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.util.Objects;
import java.util.Optional;

/** GL-free immutable visual material used by VFX snapshots. */
public final class VfxMaterial {
    private static final VfxMaterial LEGACY_ALPHA = builder("legacy-alpha").build();

    private final String name;
    private final AssetRef texture;
    private final VfxMaskMode maskMode;
    private final BlendMode blendMode;
    private final float emissiveIntensity;
    private final VfxBillboardMode billboardMode;
    private final float softParticleDistance;
    private final float alphaCutoff;
    private final float velocityStretch;
    private final float maximumStretch;
    private final float fogInfluence;
    private final VfxUvRegion uvRegion;
    private final FlipbookConfig flipbook;

    private VfxMaterial(Builder builder) {
        name = builder.name;
        texture = builder.texture;
        maskMode = builder.maskMode;
        blendMode = builder.blendMode;
        emissiveIntensity = builder.emissiveIntensity;
        billboardMode = builder.billboardMode;
        softParticleDistance = builder.softParticleDistance;
        alphaCutoff = builder.alphaCutoff;
        velocityStretch = builder.velocityStretch;
        maximumStretch = builder.maximumStretch;
        fogInfluence = builder.fogInfluence;
        uvRegion = builder.uvRegion;
        flipbook = builder.flipbook;
        if (maskMode != VfxMaskMode.WHITE && texture == null) {
            throw new IllegalArgumentException("A non-WHITE mask requires a texture");
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static VfxMaterial legacyAlpha() {
        return LEGACY_ALPHA;
    }

    public String name() { return name; }
    public Optional<AssetRef> texture() { return Optional.ofNullable(texture); }
    public VfxMaskMode maskMode() { return maskMode; }
    public BlendMode blendMode() { return blendMode; }
    public float emissiveIntensity() { return emissiveIntensity; }
    public VfxBillboardMode billboardMode() { return billboardMode; }
    public float softParticleDistance() { return softParticleDistance; }
    public float alphaCutoff() { return alphaCutoff; }
    public float velocityStretch() { return velocityStretch; }
    public float maximumStretch() { return maximumStretch; }
    public float fogInfluence() { return fogInfluence; }
    public VfxUvRegion uvRegion() { return uvRegion; }
    public Optional<FlipbookConfig> flipbook() { return Optional.ofNullable(flipbook); }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof VfxMaterial other)) return false;
        return Float.compare(emissiveIntensity, other.emissiveIntensity) == 0
                && Float.compare(softParticleDistance, other.softParticleDistance) == 0
                && Float.compare(alphaCutoff, other.alphaCutoff) == 0
                && Float.compare(velocityStretch, other.velocityStretch) == 0
                && Float.compare(maximumStretch, other.maximumStretch) == 0
                && Float.compare(fogInfluence, other.fogInfluence) == 0
                && name.equals(other.name) && Objects.equals(texture, other.texture)
                && maskMode == other.maskMode && blendMode == other.blendMode
                && billboardMode == other.billboardMode && uvRegion.equals(other.uvRegion)
                && Objects.equals(flipbook, other.flipbook);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, texture, maskMode, blendMode, emissiveIntensity,
                billboardMode, softParticleDistance, alphaCutoff, velocityStretch,
                maximumStretch, fogInfluence, uvRegion, flipbook);
    }

    @Override
    public String toString() {
        return "VfxMaterial[" + name + ", " + maskMode + ", " + blendMode + "]";
    }

    public static final class Builder {
        private final String name;
        private AssetRef texture;
        private VfxMaskMode maskMode = VfxMaskMode.WHITE;
        private BlendMode blendMode = BlendMode.ALPHA;
        private float emissiveIntensity = 1.0f;
        private VfxBillboardMode billboardMode = VfxBillboardMode.CAMERA_FACING;
        private float softParticleDistance;
        private float alphaCutoff;
        private float velocityStretch;
        private float maximumStretch = 1.0f;
        private float fogInfluence;
        private VfxUvRegion uvRegion = VfxUvRegion.FULL;
        private FlipbookConfig flipbook;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name").trim();
            if (this.name.isEmpty()) throw new IllegalArgumentException("name must not be blank");
        }

        public Builder texture(AssetRef value) { texture = Objects.requireNonNull(value, "texture"); return this; }
        public Builder maskMode(VfxMaskMode value) { maskMode = Objects.requireNonNull(value, "maskMode"); return this; }
        public Builder blendMode(BlendMode value) {
            value = Objects.requireNonNull(value, "blendMode");
            if (value != BlendMode.ALPHA && value != BlendMode.ADDITIVE) {
                throw new IllegalArgumentException("VFX blend mode must be ALPHA or ADDITIVE");
            }
            blendMode = value;
            return this;
        }
        public Builder emissiveIntensity(float value) { emissiveIntensity = nonNegative(value, "emissiveIntensity"); return this; }
        public Builder billboardMode(VfxBillboardMode value) { billboardMode = Objects.requireNonNull(value, "billboardMode"); return this; }
        public Builder softParticleDistance(float value) { softParticleDistance = nonNegative(value, "softParticleDistance"); return this; }
        public Builder alphaCutoff(float value) { alphaCutoff = unit(value, "alphaCutoff"); return this; }
        public Builder velocityStretch(float value) { velocityStretch = nonNegative(value, "velocityStretch"); return this; }
        public Builder maximumStretch(float value) {
            if (!Float.isFinite(value) || value < 1.0f) {
                throw new IllegalArgumentException("maximumStretch must be finite and at least 1");
            }
            maximumStretch = value;
            return this;
        }
        public Builder fogInfluence(float value) { fogInfluence = unit(value, "fogInfluence"); return this; }
        public Builder uvRegion(VfxUvRegion value) { uvRegion = Objects.requireNonNull(value, "uvRegion"); return this; }
        public Builder flipbook(FlipbookConfig value) {
            flipbook = Objects.requireNonNull(value, "flipbook");
            return this;
        }
        public VfxMaterial build() { return new VfxMaterial(this); }

        private static float nonNegative(float value, String name) {
            if (!Float.isFinite(value) || value < 0.0f) {
                throw new IllegalArgumentException(name + " must be finite and non-negative");
            }
            return value;
        }

        private static float unit(float value, String name) {
            if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
                throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
            }
            return value;
        }
    }
}
