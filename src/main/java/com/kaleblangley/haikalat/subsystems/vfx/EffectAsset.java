package com.kaleblangley.haikalat.subsystems.vfx;

import com.kaleblangley.haikalat.core.curve.ColorGradient;
import com.kaleblangley.haikalat.core.curve.FloatTrack;

import java.util.Objects;
import java.util.Optional;

/** 可复用效果定义；关闭前必须先关闭全部 {@link EffectInstance}。 */
public final class EffectAsset implements AutoCloseable {
    private final String name;
    private final ParticleEmitter particleEmitter;
    private final RibbonEmitter ribbonEmitter;
    private final Decal decal;
    private final MeshVfx meshVfx;
    private final FloatTrack particleSizeOverLife;
    private final ColorGradient particleColorOverLife;
    private final FloatTrack particleRotationOverLife;
    private final FloatTrack ribbonWidthOverLife;
    private final ColorGradient ribbonColorOverLife;
    private final FloatTrack decalScaleOverLife;
    private final ColorGradient decalColorOverLife;
    private final FloatTrack meshScaleOverLife;
    private final ColorGradient meshColorOverLife;
    private final FloatTrack meshRotationOverLife;
    private final VfxVisualSet visuals;
    private int activeInstances;
    private boolean closed;

    private EffectAsset(Builder builder) {
        name = builder.name;
        particleEmitter = builder.particleEmitter;
        ribbonEmitter = builder.ribbonEmitter;
        decal = builder.decal;
        meshVfx = builder.meshVfx;
        particleSizeOverLife = builder.particleSizeOverLife;
        particleColorOverLife = builder.particleColorOverLife;
        particleRotationOverLife = builder.particleRotationOverLife;
        ribbonWidthOverLife = builder.ribbonWidthOverLife;
        ribbonColorOverLife = builder.ribbonColorOverLife;
        decalScaleOverLife = builder.decalScaleOverLife;
        decalColorOverLife = builder.decalColorOverLife;
        meshScaleOverLife = builder.meshScaleOverLife;
        meshColorOverLife = builder.meshColorOverLife;
        meshRotationOverLife = builder.meshRotationOverLife;
        visuals = new VfxVisualSet(builder.particleMaterial, builder.ribbonMaterial,
                builder.decalMaterial, builder.meshMaterial);
        if (particleEmitter == null && ribbonEmitter == null && decal == null && meshVfx == null) {
            throw new IllegalArgumentException(
                    "EffectAsset requires at least one emitter, decal or mesh definition");
        }
        if (particleEmitter == null && (particleSizeOverLife != null
                || particleColorOverLife != null || particleRotationOverLife != null)) {
            throw new IllegalArgumentException("particle curves require a particle emitter");
        }
        if (ribbonEmitter == null && (ribbonWidthOverLife != null
                || ribbonColorOverLife != null)) {
            throw new IllegalArgumentException("ribbon curves require a ribbon emitter");
        }
        if (decal == null && (decalScaleOverLife != null || decalColorOverLife != null)) {
            throw new IllegalArgumentException("decal curves require a decal definition");
        }
        if (meshVfx == null && (meshScaleOverLife != null
                || meshColorOverLife != null || meshRotationOverLife != null)) {
            throw new IllegalArgumentException("mesh curves require a mesh definition");
        }
        if (builder.ribbonMaterial.flipbook().isPresent()
                || builder.decalMaterial.flipbook().isPresent()
                || builder.meshMaterial.flipbook().isPresent()) {
            throw new IllegalArgumentException("flipbook animation is only supported by particles");
        }
        if (meshVfx != null && builder.meshMaterial.maskMode() != VfxMaskMode.WHITE
                && !meshVfx.hasTextureCoordinates()) {
            throw new IllegalArgumentException(
                    "textured MeshVfx requires TEXCOORD_0 at vertex location 1");
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public synchronized EffectInstance instantiate(long seed) {
        ensureOpen();
        activeInstances = Math.incrementExact(activeInstances);
        try {
            return new EffectInstance(this, seed);
        } catch (RuntimeException | Error failure) {
            activeInstances--;
            throw failure;
        }
    }

    public String name() {
        return name;
    }

    public Optional<ParticleEmitter> particleEmitter() {
        return Optional.ofNullable(particleEmitter);
    }

    public Optional<RibbonEmitter> ribbonEmitter() {
        return Optional.ofNullable(ribbonEmitter);
    }

    public Optional<Decal> decal() {
        return Optional.ofNullable(decal);
    }

    public Optional<MeshVfx> meshVfx() {
        return Optional.ofNullable(meshVfx);
    }

    public Optional<FloatTrack> particleSizeOverLife() {
        return Optional.ofNullable(particleSizeOverLife);
    }

    public Optional<ColorGradient> particleColorOverLife() {
        return Optional.ofNullable(particleColorOverLife);
    }

    public Optional<FloatTrack> particleRotationOverLife() {
        return Optional.ofNullable(particleRotationOverLife);
    }

    public Optional<FloatTrack> ribbonWidthOverLife() {
        return Optional.ofNullable(ribbonWidthOverLife);
    }

    public Optional<ColorGradient> ribbonColorOverLife() {
        return Optional.ofNullable(ribbonColorOverLife);
    }

    public Optional<FloatTrack> decalScaleOverLife() {
        return Optional.ofNullable(decalScaleOverLife);
    }

    public Optional<ColorGradient> decalColorOverLife() {
        return Optional.ofNullable(decalColorOverLife);
    }

    public Optional<FloatTrack> meshScaleOverLife() {
        return Optional.ofNullable(meshScaleOverLife);
    }

    public Optional<ColorGradient> meshColorOverLife() {
        return Optional.ofNullable(meshColorOverLife);
    }

    public Optional<FloatTrack> meshRotationOverLife() {
        return Optional.ofNullable(meshRotationOverLife);
    }

    public VfxVisualSet visuals() {
        return visuals;
    }

    public synchronized int activeInstances() {
        return activeInstances;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (activeInstances != 0) {
            throw new IllegalStateException("EffectAsset '" + name + "' still has "
                    + activeInstances + " active instance(s)");
        }
        closed = true;
    }

    synchronized void releaseInstance() {
        if (activeInstances <= 0) {
            throw new IllegalStateException("EffectAsset instance count underflow");
        }
        activeInstances--;
    }

    FloatTrack particleSizeTrack() { return particleSizeOverLife; }
    ColorGradient particleColorGradient() { return particleColorOverLife; }
    FloatTrack particleRotationTrack() { return particleRotationOverLife; }
    FloatTrack ribbonWidthTrack() { return ribbonWidthOverLife; }
    ColorGradient ribbonColorGradient() { return ribbonColorOverLife; }
    FloatTrack decalScaleTrack() { return decalScaleOverLife; }
    ColorGradient decalColorGradient() { return decalColorOverLife; }
    FloatTrack meshScaleTrack() { return meshScaleOverLife; }
    ColorGradient meshColorGradient() { return meshColorOverLife; }
    FloatTrack meshRotationTrack() { return meshRotationOverLife; }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("EffectAsset '" + name + "' is closed");
    }

    public static final class Builder {
        private final String name;
        private ParticleEmitter particleEmitter;
        private RibbonEmitter ribbonEmitter;
        private Decal decal;
        private MeshVfx meshVfx;
        private FloatTrack particleSizeOverLife;
        private ColorGradient particleColorOverLife;
        private FloatTrack particleRotationOverLife;
        private FloatTrack ribbonWidthOverLife;
        private ColorGradient ribbonColorOverLife;
        private FloatTrack decalScaleOverLife;
        private ColorGradient decalColorOverLife;
        private FloatTrack meshScaleOverLife;
        private ColorGradient meshColorOverLife;
        private FloatTrack meshRotationOverLife;
        private VfxMaterial particleMaterial = VfxMaterial.legacyAlpha();
        private VfxMaterial ribbonMaterial = VfxMaterial.legacyAlpha();
        private VfxMaterial decalMaterial = VfxMaterial.legacyAlpha();
        private VfxMaterial meshMaterial = VfxMaterial.legacyAlpha();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name").trim();
            if (this.name.isEmpty()) throw new IllegalArgumentException("name must not be blank");
        }

        public Builder particles(ParticleEmitter value) {
            particleEmitter = Objects.requireNonNull(value, "particleEmitter");
            return this;
        }

        public Builder ribbon(RibbonEmitter value) {
            ribbonEmitter = Objects.requireNonNull(value, "ribbonEmitter");
            return this;
        }

        public Builder decals(Decal value) {
            decal = Objects.requireNonNull(value, "decal");
            return this;
        }

        public Builder meshes(MeshVfx value) {
            meshVfx = Objects.requireNonNull(value, "meshVfx");
            return this;
        }

        public Builder particleSizeOverLife(FloatTrack value) {
            particleSizeOverLife = Objects.requireNonNull(value, "particleSizeOverLife");
            return this;
        }

        public Builder particleColorOverLife(ColorGradient value) {
            particleColorOverLife = Objects.requireNonNull(value, "particleColorOverLife");
            return this;
        }

        public Builder particleRotationOverLife(FloatTrack value) {
            particleRotationOverLife = Objects.requireNonNull(value, "particleRotationOverLife");
            return this;
        }

        public Builder ribbonWidthOverLife(FloatTrack value) {
            ribbonWidthOverLife = Objects.requireNonNull(value, "ribbonWidthOverLife");
            return this;
        }

        public Builder ribbonColorOverLife(ColorGradient value) {
            ribbonColorOverLife = Objects.requireNonNull(value, "ribbonColorOverLife");
            return this;
        }

        public Builder decalScaleOverLife(FloatTrack value) {
            decalScaleOverLife = Objects.requireNonNull(value, "decalScaleOverLife");
            return this;
        }

        public Builder decalColorOverLife(ColorGradient value) {
            decalColorOverLife = Objects.requireNonNull(value, "decalColorOverLife");
            return this;
        }

        public Builder meshScaleOverLife(FloatTrack value) {
            meshScaleOverLife = Objects.requireNonNull(value, "meshScaleOverLife");
            return this;
        }

        public Builder meshColorOverLife(ColorGradient value) {
            meshColorOverLife = Objects.requireNonNull(value, "meshColorOverLife");
            return this;
        }

        public Builder meshRotationOverLife(FloatTrack value) {
            meshRotationOverLife = Objects.requireNonNull(value, "meshRotationOverLife");
            return this;
        }

        public Builder particleMaterial(VfxMaterial value) {
            particleMaterial = Objects.requireNonNull(value, "particleMaterial");
            return this;
        }

        public Builder ribbonMaterial(VfxMaterial value) {
            ribbonMaterial = Objects.requireNonNull(value, "ribbonMaterial");
            return this;
        }

        public Builder decalMaterial(VfxMaterial value) {
            decalMaterial = Objects.requireNonNull(value, "decalMaterial");
            return this;
        }

        public Builder meshMaterial(VfxMaterial value) {
            meshMaterial = Objects.requireNonNull(value, "meshMaterial");
            return this;
        }

        public EffectAsset build() {
            return new EffectAsset(this);
        }
    }
}
