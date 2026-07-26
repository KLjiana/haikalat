package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.animation.MorphWeightBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.SceneDrawBinding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;

/** Instance-owned morph weights combined with an optional skin palette binding. */
final class MorphSceneDrawBinding implements SceneDrawBinding, AutoCloseable {
    private final GltfMorphTargetBuffer targets;
    private final MorphWeightBuffer weights;
    private final SceneDrawBinding skin;
    private final GlBuffer weightBuffer;
    private final float[] packed;
    private final ByteBuffer bytes;
    private long uploadedRevision = Long.MIN_VALUE;
    private boolean closed;

    MorphSceneDrawBinding(GltfMorphTargetBuffer targets, MorphWeightBuffer weights,
                          SceneDrawBinding skin) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.weights = Objects.requireNonNull(weights, "weights");
        this.skin = Objects.requireNonNull(skin, "skin");
        if (targets.targetCount() != weights.targetCount()) {
            throw new IllegalArgumentException("morph target and weight counts must match");
        }
        packed = new float[weights.targetCount()];
        bytes = ByteBuffer.allocateDirect(packed.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        weightBuffer = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                .allocate(bytes.capacity());
    }

    @Override
    public void record(CommandBuffer commands, ShaderProgram shader,
                       int frameIndex, Pass pass) {
        ensureOpen();
        skin.record(commands, shader, frameIndex, pass);
        if (uploadedRevision != weights.revision()) {
            weights.copyTo(packed, 0);
            bytes.clear();
            bytes.asFloatBuffer().put(packed);
            bytes.limit(packed.length * Float.BYTES);
            commands.uploadBufferRegion(weightBuffer, 0L, bytes);
            uploadedRevision = weights.revision();
        }
        commands.bindStorageBuffer(GltfMorphTargetBuffer.DELTA_STORAGE_BINDING,
                        targets.buffer(), 0L, targets.byteSize())
                .bindStorageBuffer(GltfMorphTargetBuffer.WEIGHT_STORAGE_BINDING,
                        weightBuffer, 0L, bytes.capacity())
                .trySetUniformInt(shader, "uMorphVertexCount", targets.vertexCount());
    }

    @Override
    public boolean skinningEnabled() {
        return skin.skinningEnabled();
    }

    @Override
    public int morphTargetCount() {
        return targets.targetCount();
    }

    long weightByteSize() {
        return bytes.capacity();
    }

    @Override
    public void close() {
        if (closed) return;
        weightBuffer.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("morph draw binding is closed");
    }
}
