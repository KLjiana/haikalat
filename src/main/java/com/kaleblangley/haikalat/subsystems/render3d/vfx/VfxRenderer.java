package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Objects;

/** 将纯 CPU {@link EffectSnapshot} 转换为受控 OpenGL 命令的 render3d 适配器。 */
public final class VfxRenderer implements AutoCloseable {
    private static final int MAT4_BYTES = 16 * Float.BYTES;
    private static final int COLOR_BYTES = 4 * Float.BYTES;

    private final ShaderProgram shader;
    private final Mesh quad;
    private final Mesh cube;
    private Statistics lastStatistics = Statistics.empty();
    private boolean closed;

    public VfxRenderer() {
        ShaderProgram createdShader = null;
        Mesh createdQuad = null;
        Mesh createdCube = null;
        try {
            createdShader = ShaderProgram.fromResource(VfxRenderer.class,
                    "/render3d/vfx/vfx.vert", "/render3d/vfx/vfx.frag");
            createdQuad = Mesh.from(BuiltinMeshData.coloredQuad("vfx-quad"));
            createdCube = Mesh.from(BuiltinMeshData.coloredCube("vfx-ribbon"));
        } catch (RuntimeException | Error failure) {
            closeSuppressing(createdCube, failure);
            closeSuppressing(createdQuad, failure);
            closeSuppressing(createdShader, failure);
            throw failure;
        }
        shader = createdShader;
        quad = createdQuad;
        cube = createdCube;
    }

    /**
     * 按快照给出的远到近顺序记录 alpha primitive。
     * 调用方负责 framebuffer、viewport、clear 和最终 present。
     */
    public Statistics record(CommandBuffer commands, EffectSnapshot snapshot,
                             Matrix4fc projection, Matrix4fc view) {
        ensureOpen();
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(view, "view");

        Matrix4f projectionView = new Matrix4f(projection).mul(view);
        Matrix4f inverseViewRotation = new Matrix4f(view).invert();
        inverseViewRotation.m30(0.0f).m31(0.0f).m32(0.0f).m33(1.0f);
        Matrix4f model = new Matrix4f();
        Matrix4f mvp = new Matrix4f();
        Mesh boundMesh = null;
        int particles = 0;
        int ribbons = 0;
        int decals = 0;

        commands.bindShader(shader)
                .materialState(BlendMode.ALPHA, true)
                .enableCullFace(false);
        for (EffectSnapshot.Primitive primitive : snapshot.transparentDrawOrder()) {
            Mesh mesh;
            switch (primitive) {
                case EffectSnapshot.ParticleSprite particle -> {
                    particleModel(particle, inverseViewRotation, model);
                    mesh = quad;
                    particles++;
                }
                case EffectSnapshot.RibbonSegment ribbon -> {
                    ribbonModel(ribbon, model);
                    mesh = cube;
                    ribbons++;
                }
                case EffectSnapshot.DecalInstance decal -> {
                    decalModel(decal, model);
                    mesh = quad;
                    decals++;
                }
            }
            if (mesh != boundMesh) {
                commands.bindMesh(mesh);
                boundMesh = mesh;
            }
            projectionView.mul(model, mvp);
            commands.setUniformMat4(shader, "uMvp", mvp)
                    .setUniformVec4(shader, "uColor", primitive.color())
                    .drawMesh(mesh);
        }
        lastStatistics = new Statistics(snapshot.primitiveCount(), particles, ribbons, decals,
                (long) snapshot.primitiveCount() * (MAT4_BYTES + COLOR_BYTES));
        return lastStatistics;
    }

    public Statistics statistics() {
        return lastStatistics;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        RuntimeException failure = null;
        failure = closeCollecting(cube, failure);
        failure = closeCollecting(quad, failure);
        failure = closeCollecting(shader, failure);
        closed = true;
        if (failure != null) throw failure;
    }

    private static void particleModel(EffectSnapshot.ParticleSprite particle,
                                      Matrix4f inverseViewRotation, Matrix4f out) {
        out.identity().translation(particle.center())
                .mul(inverseViewRotation)
                .rotateZ(particle.rotationRadians())
                .scale(particle.size());
    }

    private static void ribbonModel(EffectSnapshot.RibbonSegment ribbon, Matrix4f out) {
        Vector3f start = ribbon.start();
        Vector3f end = ribbon.end();
        Vector3f direction = end.sub(start, new Vector3f());
        float length = direction.length();
        if (length <= 1.0e-6f) {
            out.zero();
            return;
        }
        direction.div(length);
        Quaternionf orientation = new Quaternionf().rotationTo(
                new Vector3f(1.0f, 0.0f, 0.0f), direction);
        float width = Math.max(1.0e-4f, (ribbon.startWidth() + ribbon.endWidth()) * 0.5f);
        out.identity().translation(ribbon.center())
                .rotate(orientation)
                .scale(length, width, Math.max(0.01f, width * 0.15f));
    }

    private static void decalModel(EffectSnapshot.DecalInstance decal, Matrix4f out) {
        Vector3f normal = decal.normal();
        Quaternionf orientation = new Quaternionf().rotationTo(
                new Vector3f(0.0f, 0.0f, 1.0f), normal);
        out.identity().translation(new Vector3f(decal.center()).fma(0.004f, normal))
                .rotate(orientation)
                .rotateZ(decal.rotationRadians())
                .scale(decal.size().x, decal.size().y, 1.0f);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("VfxRenderer is closed");
    }

    private static void closeSuppressing(AutoCloseable value, Throwable failure) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static RuntimeException closeCollecting(AutoCloseable value, RuntimeException failure) {
        try {
            value.close();
        } catch (Exception closeFailure) {
            RuntimeException wrapped = closeFailure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("Failed to close VFX render resource", closeFailure);
            if (failure == null) return wrapped;
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    public record Statistics(int drawCalls, int particles, int ribbonSegments,
                             int decals, long uniformPayloadBytes) {
        private static Statistics empty() {
            return new Statistics(0, 0, 0, 0, 0L);
        }
    }
}
