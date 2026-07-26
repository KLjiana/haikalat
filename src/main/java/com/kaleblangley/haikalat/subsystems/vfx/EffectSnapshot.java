package com.kaleblangley.haikalat.subsystems.vfx;

import com.kaleblangley.haikalat.core.mesh.MeshData;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;
import java.util.Objects;

/** 单帧不可变 VFX 值快照，不包含 GPU 句柄或渲染命令。 */
public record EffectSnapshot(
        List<ParticleSprite> particles,
        List<RibbonSegment> ribbonSegments,
        List<DecalInstance> decals,
        List<MeshInstance> meshes,
        List<Primitive> transparentDrawOrder,
        VfxVisualSet visuals
) {
    public EffectSnapshot {
        particles = List.copyOf(Objects.requireNonNull(particles, "particles"));
        ribbonSegments = List.copyOf(Objects.requireNonNull(ribbonSegments, "ribbonSegments"));
        decals = List.copyOf(Objects.requireNonNull(decals, "decals"));
        meshes = List.copyOf(Objects.requireNonNull(meshes, "meshes"));
        transparentDrawOrder = List.copyOf(
                Objects.requireNonNull(transparentDrawOrder, "transparentDrawOrder"));
        visuals = Objects.requireNonNull(visuals, "visuals");
        int expected = Math.addExact(Math.addExact(
                Math.addExact(particles.size(), ribbonSegments.size()), decals.size()), meshes.size());
        if (transparentDrawOrder.size() != expected) {
            throw new IllegalArgumentException("transparentDrawOrder must contain every primitive exactly once");
        }
    }

    public EffectSnapshot(List<ParticleSprite> particles,
                          List<RibbonSegment> ribbonSegments,
                          List<DecalInstance> decals,
                          List<Primitive> transparentDrawOrder,
                          VfxVisualSet visuals) {
        this(particles, ribbonSegments, decals, List.of(), transparentDrawOrder, visuals);
    }

    public EffectSnapshot(List<ParticleSprite> particles,
                          List<RibbonSegment> ribbonSegments,
                          List<DecalInstance> decals,
                          List<Primitive> transparentDrawOrder) {
        this(particles, ribbonSegments, decals, List.of(), transparentDrawOrder,
                VfxVisualSet.legacy());
    }

    public int primitiveCount() {
        return transparentDrawOrder.size();
    }

    public enum Kind {
        PARTICLE,
        RIBBON,
        DECAL,
        MESH
    }

    public sealed interface Primitive permits ParticleSprite, RibbonSegment, DecalInstance,
            MeshInstance {
        long sequence();

        Vector3f center();

        Vector4f color();

        float distanceSquared();

        Kind kind();
    }

    public record ParticleSprite(long sequence, Vector3f center, float size,
                                 float rotationRadians, Vector4f color,
                                 float distanceSquared, Vector3f velocity,
                                 float frameProgress, float ageSeconds,
                                 int flipbookStartFrame) implements Primitive {
        public ParticleSprite {
            center = new Vector3f(Objects.requireNonNull(center, "center"));
            color = new Vector4f(Objects.requireNonNull(color, "color"));
            velocity = new Vector3f(Objects.requireNonNull(velocity, "velocity"));
            requireFinite(center, "center");
            requirePositive(size, "size");
            requireFinite(rotationRadians, "rotationRadians");
            requireDistance(distanceSquared);
            requireFinite(velocity, "velocity");
            if (!Float.isFinite(frameProgress) || frameProgress < 0.0f || frameProgress > 1.0f) {
                throw new IllegalArgumentException("frameProgress must be finite and in [0, 1]");
            }
            if (!Float.isFinite(ageSeconds) || ageSeconds < 0.0f) {
                throw new IllegalArgumentException("ageSeconds must be finite and non-negative");
            }
            if (flipbookStartFrame < 0) {
                throw new IllegalArgumentException("flipbookStartFrame must be non-negative");
            }
            ParticleEmitter.requireColor(color, "color");
        }

        public ParticleSprite(long sequence, Vector3f center, float size,
                              float rotationRadians, Vector4f color,
                              float distanceSquared, Vector3f velocity,
                              float frameProgress) {
            this(sequence, center, size, rotationRadians, color, distanceSquared,
                    velocity, frameProgress, 0.0f, 0);
        }

        public ParticleSprite(long sequence, Vector3f center, float size,
                              float rotationRadians, Vector4f color, float distanceSquared) {
            this(sequence, center, size, rotationRadians, color, distanceSquared,
                    new Vector3f(), 0.0f, 0.0f, 0);
        }

        @Override public Vector3f center() { return new Vector3f(center); }
        @Override public Vector4f color() { return new Vector4f(color); }
        @Override public Vector3f velocity() { return new Vector3f(velocity); }
        @Override public Kind kind() { return Kind.PARTICLE; }
    }

    public record RibbonSegment(long sequence, Vector3f start, Vector3f end,
                                float startWidth, float endWidth, Vector4f color,
                                float distanceSquared) implements Primitive {
        public RibbonSegment {
            start = new Vector3f(Objects.requireNonNull(start, "start"));
            end = new Vector3f(Objects.requireNonNull(end, "end"));
            color = new Vector4f(Objects.requireNonNull(color, "color"));
            requireFinite(start, "start");
            requireFinite(end, "end");
            if (start.distanceSquared(end) <= 1.0e-12f) {
                throw new IllegalArgumentException("Ribbon segment endpoints must differ");
            }
            requirePositive(startWidth, "startWidth");
            requirePositive(endWidth, "endWidth");
            requireDistance(distanceSquared);
            ParticleEmitter.requireColor(color, "color");
        }

        @Override public Vector3f start() { return new Vector3f(start); }
        @Override public Vector3f end() { return new Vector3f(end); }
        @Override public Vector3f center() { return new Vector3f(start).add(end).mul(0.5f); }
        @Override public Vector4f color() { return new Vector4f(color); }
        @Override public Kind kind() { return Kind.RIBBON; }
    }

    public record DecalInstance(long sequence, Vector3f center, Vector3f normal,
                                Vector2f size, float rotationRadians, Vector4f color,
                                float distanceSquared) implements Primitive {
        public DecalInstance {
            center = new Vector3f(Objects.requireNonNull(center, "center"));
            normal = new Vector3f(Objects.requireNonNull(normal, "normal"));
            size = new Vector2f(Objects.requireNonNull(size, "size"));
            color = new Vector4f(Objects.requireNonNull(color, "color"));
            requireFinite(center, "center");
            requireFinite(normal, "normal");
            if (normal.lengthSquared() == 0.0f) {
                throw new IllegalArgumentException("normal must be non-zero");
            }
            if (!Float.isFinite(size.x) || !Float.isFinite(size.y)
                    || size.x <= 0.0f || size.y <= 0.0f) {
                throw new IllegalArgumentException("size must contain finite positive values");
            }
            requireFinite(rotationRadians, "rotationRadians");
            requireDistance(distanceSquared);
            ParticleEmitter.requireColor(color, "color");
        }

        @Override public Vector3f center() { return new Vector3f(center); }
        @Override public Vector3f normal() { return new Vector3f(normal); }
        @Override public Vector2f size() { return new Vector2f(size); }
        @Override public Vector4f color() { return new Vector4f(color); }
        @Override public Kind kind() { return Kind.DECAL; }
    }

    public record MeshInstance(long sequence, MeshData meshData, Matrix4f model,
                               Vector4f color, float distanceSquared) implements Primitive {
        public MeshInstance {
            meshData = Objects.requireNonNull(meshData, "meshData");
            model = new Matrix4f(Objects.requireNonNull(model, "model"));
            color = new Vector4f(Objects.requireNonNull(color, "color"));
            requireFinite(model, "model");
            requireDistance(distanceSquared);
            ParticleEmitter.requireColor(color, "color");
        }

        @Override public Matrix4f model() { return new Matrix4f(model); }
        @Override public Vector3f center() { return model.getTranslation(new Vector3f()); }
        @Override public Vector4f color() { return new Vector4f(color); }
        @Override public Kind kind() { return Kind.MESH; }
    }

    private static void requireFinite(Vector3f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
    }

    private static void requireFinite(Matrix4f value, String name) {
        float[] elements = value.get(new float[16]);
        for (float element : elements) {
            if (!Float.isFinite(element)) {
                throw new IllegalArgumentException(name + " must contain finite values");
            }
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireDistance(float value) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException("distanceSquared must be finite and non-negative");
        }
    }
}
