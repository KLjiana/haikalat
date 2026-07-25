package com.kaleblangley.haikalat.subsystems.vfx;

import java.util.Objects;
import java.util.Optional;

/** 可复用效果定义；关闭前必须先关闭全部 {@link EffectInstance}。 */
public final class EffectAsset implements AutoCloseable {
    private final String name;
    private final ParticleEmitter particleEmitter;
    private final RibbonEmitter ribbonEmitter;
    private final Decal decal;
    private int activeInstances;
    private boolean closed;

    private EffectAsset(Builder builder) {
        name = builder.name;
        particleEmitter = builder.particleEmitter;
        ribbonEmitter = builder.ribbonEmitter;
        decal = builder.decal;
        if (particleEmitter == null && ribbonEmitter == null && decal == null) {
            throw new IllegalArgumentException("EffectAsset requires at least one emitter or decal definition");
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

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("EffectAsset '" + name + "' is closed");
    }

    public static final class Builder {
        private final String name;
        private ParticleEmitter particleEmitter;
        private RibbonEmitter ribbonEmitter;
        private Decal decal;

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

        public EffectAsset build() {
            return new EffectAsset(this);
        }
    }
}
