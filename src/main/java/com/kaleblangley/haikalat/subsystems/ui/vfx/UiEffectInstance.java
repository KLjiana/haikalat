package com.kaleblangley.haikalat.subsystems.ui.vfx;

import com.kaleblangley.haikalat.subsystems.ui.UiId;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic CPU simulation for one bounded UI effect. */
public final class UiEffectInstance {
    private final long instanceId;
    private final UiEffectDefinition definition;
    private final UiId target;
    private final long startSequence;
    private final SeedParticle[] seeds;
    private float elapsed;
    private boolean cancelled;
    private boolean completed;

    public UiEffectInstance(long instanceId, UiEffectDefinition definition,
                            UiId target, long startSequence) {
        if (instanceId <= 0L || startSequence <= 0L) {
            throw new IllegalArgumentException("instance and start sequence must be positive");
        }
        this.instanceId = instanceId;
        this.definition = Objects.requireNonNull(definition, "definition");
        this.target = Objects.requireNonNull(target, "target");
        this.startSequence = startSequence;
        seeds = createSeeds(definition, startSequence);
    }

    public long instanceId() { return instanceId; }
    public UiEffectDefinition definition() { return definition; }
    public UiId target() { return target; }
    public long startSequence() { return startSequence; }
    public float elapsedSeconds() { return elapsed; }
    public boolean active() { return !cancelled && !completed; }
    public boolean cancelled() { return cancelled; }
    public boolean completed() { return completed; }

    public void update(float deltaSeconds, boolean visible) {
        if (!active() || !visible) return;
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        elapsed = Math.min(definition.durationSeconds(), elapsed + deltaSeconds);
        completed = elapsed >= definition.durationSeconds();
    }

    public void cancel() {
        if (active()) cancelled = true;
    }

    public UiEffectSnapshot snapshot(UiScreenRect anchorBounds) {
        Objects.requireNonNull(anchorBounds, "anchorBounds");
        float normalized = Math.min(1.0f, elapsed / definition.durationSeconds());
        List<UiEffectSnapshot.Particle> particles =
                seeds.length == 0 ? List.of() : particles(anchorBounds, normalized);
        return new UiEffectSnapshot(instanceId, target, definition,
                resolveBounds(anchorBounds), normalized, particles);
    }

    private UiScreenRect resolveBounds(UiScreenRect anchor) {
        UiScreenRect local = definition.localBounds();
        if (!local.isEmpty()) {
            return new UiScreenRect(anchor.x() + local.x(), anchor.y() + local.y(),
                    local.width(), local.height());
        }
        return switch (definition.anchorMode()) {
            case NODE_BOUNDS, CONTENT_BOUNDS, PATH_PROGRESS -> anchor;
            case EDGE -> new UiScreenRect(anchor.right(), anchor.y(),
                    Math.max(1.0, anchor.width() * 0.05), anchor.height());
            case CORNER -> new UiScreenRect(anchor.right(), anchor.y(),
                    Math.max(1.0, anchor.width() * 0.05),
                    Math.max(1.0, anchor.height() * 0.05));
            case POINTER, EXPLICIT_POINT -> new UiScreenRect(
                    anchor.x() + anchor.width() * 0.5,
                    anchor.y() + anchor.height() * 0.5, 1.0, 1.0);
        };
    }

    private List<UiEffectSnapshot.Particle> particles(UiScreenRect bounds, float time) {
        List<UiEffectSnapshot.Particle> result = new ArrayList<>(seeds.length);
        float alpha = 1.0f - time;
        UiColor color = mix(definition.startColor(), definition.endColor(), time, alpha);
        for (SeedParticle seed : seeds) {
            float gravity = definition.type() == UiEffectDefinition.Type.CONFETTI ? 1.8f : 0.6f;
            float px = (float) (bounds.x() + bounds.width() * seed.x
                    + seed.velocityX * bounds.width() * time);
            float py = (float) (bounds.y() + bounds.height() * seed.y
                    + (seed.velocityY * time + gravity * time * time) * bounds.height());
            float size = Math.max(1.0f, seed.size
                    * (float) Math.max(1.0, Math.min(bounds.width(), bounds.height())));
            result.add(new UiEffectSnapshot.Particle(px, py, size,
                    seed.rotation + seed.spin * time, color));
        }
        return List.copyOf(result);
    }

    private static SeedParticle[] createSeeds(UiEffectDefinition definition, long sequence) {
        int count = definition.maximumParticles();
        if (count == 0) return new SeedParticle[0];
        SeedParticle[] values = new SeedParticle[count];
        Random random = new Random(definition.seed() ^ Long.rotateLeft(sequence, 17));
        for (int index = 0; index < count; index++) {
            float angle = random.nextFloat() * (float) (Math.PI * 2.0);
            float speed = 0.08f + random.nextFloat() * 0.25f;
            values[index] = new SeedParticle(
                    0.35f + random.nextFloat() * 0.30f,
                    0.35f + random.nextFloat() * 0.30f,
                    (float) Math.cos(angle) * speed,
                    (float) Math.sin(angle) * speed - 0.25f,
                    0.008f + random.nextFloat() * 0.018f,
                    random.nextFloat() * (float) (Math.PI * 2.0),
                    (random.nextFloat() - 0.5f) * 8.0f);
        }
        return values;
    }

    private static UiColor mix(UiColor start, UiColor end, float time, float alphaScale) {
        return new UiColor(
                lerp(start.red(), end.red(), time),
                lerp(start.green(), end.green(), time),
                lerp(start.blue(), end.blue(), time),
                Math.max(0.0f, Math.min(1.0f,
                        lerp(start.alpha(), end.alpha(), time) * alphaScale)));
    }

    private static float lerp(float start, float end, float time) {
        return start + (end - start) * time;
    }

    private record SeedParticle(float x, float y, float velocityX, float velocityY,
                                float size, float rotation, float spin) {
    }

    /** SplitMix64-based generator with identical output on every JVM. */
    private static final class Random {
        private long state;
        private Random(long seed) { state = seed; }
        private float nextFloat() {
            long z = (state += 0x9e3779b97f4a7c15L);
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            z ^= z >>> 31;
            return (z >>> 40) * 0x1.0p-24f;
        }
    }
}
