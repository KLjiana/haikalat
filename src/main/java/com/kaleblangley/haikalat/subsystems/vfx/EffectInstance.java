package com.kaleblangley.haikalat.subsystems.vfx;

import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 单个效果的有界、确定性 CPU 模拟状态。 */
public final class EffectInstance implements AutoCloseable {
    private final EffectAsset asset;
    private final DeterministicRandom random;
    private final List<ParticleState> particles = new ArrayList<>();
    private final List<RibbonPoint> ribbonPoints = new ArrayList<>();
    private final List<DecalState> decals = new ArrayList<>();
    private final Vector3f lastOrigin = new Vector3f();
    private double spawnAccumulator;
    private long nextSequence;
    private long totalSpawnedParticles;
    private long expiredParticles;
    private long droppedParticles;
    private long evictedDecals;
    private boolean hasOrigin;
    private boolean emitting = true;
    private boolean closed;

    EffectInstance(EffectAsset asset, long seed) {
        this.asset = Objects.requireNonNull(asset, "asset");
        random = new DeterministicRandom(seed);
    }

    /** 推进全部 emitter，并以世界空间 origin 采样新粒子和 Ribbon 控制点。 */
    public EffectInstance update(float deltaSeconds, Vector3fc origin) {
        ensureOpen();
        requireFiniteNonNegative(deltaSeconds, "deltaSeconds");
        Vector3f nextOrigin = finiteVector(origin, "origin");
        updateParticles(deltaSeconds, nextOrigin);
        updateRibbon(deltaSeconds, nextOrigin);
        updateDecals(deltaSeconds);
        lastOrigin.set(nextOrigin);
        hasOrigin = true;
        return this;
    }

    /** 添加一个表面平面贴花；达到上限时确定性淘汰最旧实例。 */
    public EffectInstance spawnDecal(Vector3fc position, Vector3fc normal,
                                     Vector2fc size, float rotationRadians) {
        ensureOpen();
        Decal definition = asset.decal().orElseThrow(() ->
                new IllegalStateException("EffectAsset has no decal definition"));
        Vector3f copiedPosition = finiteVector(position, "position");
        Vector3f copiedNormal = finiteVector(normal, "normal");
        if (copiedNormal.lengthSquared() == 0.0f) {
            throw new IllegalArgumentException("normal must be non-zero");
        }
        copiedNormal.normalize();
        Objects.requireNonNull(size, "size");
        if (!Float.isFinite(size.x()) || !Float.isFinite(size.y())
                || size.x() <= 0.0f || size.y() <= 0.0f) {
            throw new IllegalArgumentException("size must contain finite positive values");
        }
        if (!Float.isFinite(rotationRadians)) {
            throw new IllegalArgumentException("rotationRadians must be finite");
        }
        if (decals.size() == definition.maxDecals()) {
            decals.remove(0);
            evictedDecals++;
        }
        decals.add(new DecalState(nextSequence++, copiedPosition, copiedNormal,
                new Vector2f(size), rotationRadians));
        return this;
    }

    /** 停止或恢复生成新粒子与 Ribbon 点；已有 primitive 继续老化。 */
    public EffectInstance emitting(boolean value) {
        ensureOpen();
        emitting = value;
        return this;
    }

    public boolean emitting() {
        ensureOpen();
        return emitting;
    }

    /**
     * 创建按相机距离从远到近稳定排序的值快照。
     * 相同距离保持 primitive sequence，从而避免跨帧闪烁。
     */
    public EffectSnapshot snapshot(Vector3fc cameraPosition) {
        ensureOpen();
        Vector3f camera = finiteVector(cameraPosition, "cameraPosition");
        List<EffectSnapshot.ParticleSprite> particleSprites = particleSnapshot(camera);
        List<EffectSnapshot.RibbonSegment> ribbonSegments = ribbonSnapshot(camera);
        List<EffectSnapshot.DecalInstance> decalInstances = decalSnapshot(camera);
        List<EffectSnapshot.Primitive> order = new ArrayList<>(
                particleSprites.size() + ribbonSegments.size() + decalInstances.size());
        order.addAll(particleSprites);
        order.addAll(ribbonSegments);
        order.addAll(decalInstances);
        order.sort(Comparator.comparingDouble(EffectSnapshot.Primitive::distanceSquared)
                .reversed()
                .thenComparingLong(EffectSnapshot.Primitive::sequence)
                .thenComparing(EffectSnapshot.Primitive::kind));
        return new EffectSnapshot(particleSprites, ribbonSegments, decalInstances, order);
    }

    public Statistics statistics() {
        ensureOpen();
        return new Statistics(particles.size(), Math.max(0, ribbonPoints.size() - 1), decals.size(),
                totalSpawnedParticles, expiredParticles, droppedParticles, evictedDecals);
    }

    public boolean isAlive() {
        ensureOpen();
        return emitting || !particles.isEmpty() || ribbonPoints.size() > 1 || !decals.isEmpty();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        particles.clear();
        ribbonPoints.clear();
        decals.clear();
        closed = true;
        asset.releaseInstance();
    }

    private void updateParticles(float deltaSeconds, Vector3f origin) {
        ParticleEmitter emitter = asset.particleEmitter().orElse(null);
        if (emitter == null) return;
        Vector3f acceleration = emitter.acceleration();
        float damping = (float) Math.exp(-emitter.drag() * deltaSeconds);
        for (int index = particles.size() - 1; index >= 0; index--) {
            ParticleState particle = particles.get(index);
            particle.age += deltaSeconds;
            if (particle.age >= emitter.lifetimeSeconds()) {
                particles.remove(index);
                expiredParticles++;
                continue;
            }
            particle.velocity.fma(deltaSeconds, acceleration).mul(damping);
            particle.position.fma(deltaSeconds, particle.velocity);
        }
        if (!emitting || emitter.spawnRate() == 0.0f) return;

        spawnAccumulator += (double) emitter.spawnRate() * deltaSeconds;
        long requested = (long) Math.floor(spawnAccumulator);
        if (requested == 0L) return;
        spawnAccumulator -= requested;
        int available = emitter.maxParticles() - particles.size();
        int count = (int) Math.min(requested, available);
        droppedParticles = saturatingAdd(droppedParticles, requested - count);
        for (int index = 0; index < count; index++) {
            particles.add(spawnParticle(emitter, origin));
        }
        totalSpawnedParticles = saturatingAdd(totalSpawnedParticles, count);
    }

    private ParticleState spawnParticle(ParticleEmitter emitter, Vector3f origin) {
        Vector3f direction = randomDirectionInCone(emitter.direction(), emitter.coneRadians());
        float speed = lerp(emitter.minimumSpeed(), emitter.maximumSpeed(), random.nextFloat());
        return new ParticleState(nextSequence++, new Vector3f(origin), direction.mul(speed),
                random.nextFloat() * (float) (Math.PI * 2.0));
    }

    private Vector3f randomDirectionInCone(Vector3f axis, float coneRadians) {
        if (coneRadians == 0.0f) return axis;
        float minimumCosine = (float) Math.cos(coneRadians);
        float cosine = lerp(minimumCosine, 1.0f, random.nextFloat());
        float sine = (float) Math.sqrt(Math.max(0.0f, 1.0f - cosine * cosine));
        float azimuth = random.nextFloat() * (float) (Math.PI * 2.0);
        Vector3f reference = Math.abs(axis.y) < 0.99f
                ? new Vector3f(0.0f, 1.0f, 0.0f) : new Vector3f(1.0f, 0.0f, 0.0f);
        Vector3f tangent = new Vector3f(axis).cross(reference).normalize();
        Vector3f bitangent = new Vector3f(axis).cross(tangent).normalize();
        return new Vector3f(axis).mul(cosine)
                .fma(sine * (float) Math.cos(azimuth), tangent)
                .fma(sine * (float) Math.sin(azimuth), bitangent)
                .normalize();
    }

    private void updateRibbon(float deltaSeconds, Vector3f origin) {
        RibbonEmitter emitter = asset.ribbonEmitter().orElse(null);
        if (emitter == null) return;
        for (int index = ribbonPoints.size() - 1; index >= 0; index--) {
            RibbonPoint point = ribbonPoints.get(index);
            point.age += deltaSeconds;
            if (point.age >= emitter.pointLifetimeSeconds()) ribbonPoints.remove(index);
        }
        if (!emitting) return;
        boolean add = ribbonPoints.isEmpty();
        if (!add) {
            RibbonPoint latest = ribbonPoints.get(ribbonPoints.size() - 1);
            float minimumDistanceSquared = Math.max(1.0e-12f,
                    emitter.minimumPointDistance() * emitter.minimumPointDistance());
            add = latest.position.distanceSquared(origin) >= minimumDistanceSquared;
        }
        if (!add) return;
        if (ribbonPoints.size() == emitter.maxPoints()) ribbonPoints.remove(0);
        ribbonPoints.add(new RibbonPoint(nextSequence++, new Vector3f(origin)));
    }

    private void updateDecals(float deltaSeconds) {
        Decal definition = asset.decal().orElse(null);
        if (definition == null) return;
        for (int index = decals.size() - 1; index >= 0; index--) {
            DecalState decal = decals.get(index);
            decal.age += deltaSeconds;
            if (decal.age >= definition.lifetimeSeconds()) decals.remove(index);
        }
    }

    private List<EffectSnapshot.ParticleSprite> particleSnapshot(Vector3f camera) {
        ParticleEmitter emitter = asset.particleEmitter().orElse(null);
        if (emitter == null || particles.isEmpty()) return List.of();
        List<EffectSnapshot.ParticleSprite> result = new ArrayList<>(particles.size());
        Vector4f start = emitter.startColor();
        Vector4f end = emitter.endColor();
        for (ParticleState particle : particles) {
            float progress = clamp01(particle.age / emitter.lifetimeSeconds());
            Vector4f color = start.lerp(end, progress, new Vector4f());
            float size = lerp(emitter.startSize(), emitter.endSize(), progress);
            result.add(new EffectSnapshot.ParticleSprite(particle.sequence, particle.position,
                    size, particle.rotation, color, particle.position.distanceSquared(camera)));
        }
        return List.copyOf(result);
    }

    private List<EffectSnapshot.RibbonSegment> ribbonSnapshot(Vector3f camera) {
        RibbonEmitter emitter = asset.ribbonEmitter().orElse(null);
        if (emitter == null || ribbonPoints.size() < 2) return List.of();
        List<EffectSnapshot.RibbonSegment> result = new ArrayList<>(ribbonPoints.size() - 1);
        Vector4f startColor = emitter.startColor();
        Vector4f endColor = emitter.endColor();
        for (int index = 1; index < ribbonPoints.size(); index++) {
            RibbonPoint start = ribbonPoints.get(index - 1);
            RibbonPoint end = ribbonPoints.get(index);
            float startProgress = clamp01(start.age / emitter.pointLifetimeSeconds());
            float endProgress = clamp01(end.age / emitter.pointLifetimeSeconds());
            float progress = (startProgress + endProgress) * 0.5f;
            Vector3f center = new Vector3f(start.position).add(end.position).mul(0.5f);
            result.add(new EffectSnapshot.RibbonSegment(start.sequence,
                    start.position, end.position,
                    lerp(emitter.startWidth(), emitter.endWidth(), startProgress),
                    lerp(emitter.startWidth(), emitter.endWidth(), endProgress),
                    startColor.lerp(endColor, progress, new Vector4f()),
                    center.distanceSquared(camera)));
        }
        return List.copyOf(result);
    }

    private List<EffectSnapshot.DecalInstance> decalSnapshot(Vector3f camera) {
        Decal definition = asset.decal().orElse(null);
        if (definition == null || decals.isEmpty()) return List.of();
        List<EffectSnapshot.DecalInstance> result = new ArrayList<>(decals.size());
        Vector4f startColor = definition.startColor();
        Vector4f endColor = definition.endColor();
        for (DecalState decal : decals) {
            float progress = clamp01(decal.age / definition.lifetimeSeconds());
            result.add(new EffectSnapshot.DecalInstance(decal.sequence, decal.position,
                    decal.normal, decal.size, decal.rotation,
                    startColor.lerp(endColor, progress, new Vector4f()),
                    decal.position.distanceSquared(camera)));
        }
        return List.copyOf(result);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("EffectInstance is closed");
    }

    private static Vector3f finiteVector(Vector3fc value, String name) {
        Objects.requireNonNull(value, name);
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
        return new Vector3f(value);
    }

    private static void requireFiniteNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static long saturatingAdd(long value, long increment) {
        if (increment <= 0L) return value;
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    public record Statistics(int activeParticles, int ribbonSegments, int activeDecals,
                             long totalSpawnedParticles, long expiredParticles,
                             long droppedParticles, long evictedDecals) {
    }

    private static final class ParticleState {
        private final long sequence;
        private final Vector3f position;
        private final Vector3f velocity;
        private final float rotation;
        private float age;

        private ParticleState(long sequence, Vector3f position, Vector3f velocity, float rotation) {
            this.sequence = sequence;
            this.position = position;
            this.velocity = velocity;
            this.rotation = rotation;
        }
    }

    private static final class RibbonPoint {
        private final long sequence;
        private final Vector3f position;
        private float age;

        private RibbonPoint(long sequence, Vector3f position) {
            this.sequence = sequence;
            this.position = position;
        }
    }

    private static final class DecalState {
        private final long sequence;
        private final Vector3f position;
        private final Vector3f normal;
        private final Vector2f size;
        private final float rotation;
        private float age;

        private DecalState(long sequence, Vector3f position, Vector3f normal,
                           Vector2f size, float rotation) {
            this.sequence = sequence;
            this.position = position;
            this.normal = normal;
            this.size = size;
            this.rotation = rotation;
        }
    }

    private static final class DeterministicRandom {
        private long state;

        private DeterministicRandom(long seed) {
            state = seed == 0L ? 0x9e3779b97f4a7c15L : seed;
        }

        private float nextFloat() {
            long value = state;
            value ^= value << 13;
            value ^= value >>> 7;
            value ^= value << 17;
            state = value;
            return (float) ((value >>> 40) & 0xffffffL) / 16777216.0f;
        }
    }
}
