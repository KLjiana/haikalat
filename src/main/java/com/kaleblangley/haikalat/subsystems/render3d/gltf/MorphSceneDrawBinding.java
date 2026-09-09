package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.animation.MorphWeightBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.SceneDrawBinding;
import com.kaleblangley.haikalat.subsystems.render3d.TemporalDrawBinding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;

/** Instance-owned morph weights combined with an optional skin palette binding. */
final class MorphSceneDrawBinding implements SceneDrawBinding, TemporalDrawBinding, AutoCloseable {
    private final GltfMorphTargetBuffer targets;
    private final MorphWeightBuffer weights;
    private final SceneDrawBinding skin;
    private final GlBuffer weightBuffer;
    private final GlBuffer previousWeightBuffer;
    private final float[] packed;
    private final float[] previousPacked;
    private final float[] pendingPacked;
    private final ByteBuffer bytes;
    private long uploadedRevision = Long.MIN_VALUE;
    private boolean previousValid;
    private boolean pendingValid;
    private boolean previousUploaded;
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
        previousPacked = new float[weights.targetCount()];
        pendingPacked = new float[weights.targetCount()];
        bytes = ByteBuffer.allocateDirect(packed.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        weightBuffer = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                .allocate(bytes.capacity());
        previousWeightBuffer = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
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

    @Override
    public long boundsRevision() {
        return 31L * skin.boundsRevision() + weights.revision();
    }

    @Override
    public TemporalDrawBinding temporalBinding() {
        return this;
    }

    @Override
    public boolean previousDeformationAvailable() {
        TemporalDrawBinding skinTemporal = skinTemporal();
        return previousValid && (skinTemporal == null || skinTemporal.previousDeformationAvailable());
    }

    @Override
    public void prepareTemporalFrame() {
        ensureOpen();
        weights.copyTo(pendingPacked, 0);
        pendingValid = true;
        TemporalDrawBinding skinTemporal = skinTemporal();
        if (skinTemporal != null) skinTemporal.prepareTemporalFrame();
    }

    @Override
    public void recordPrevious(CommandBuffer commands, ShaderProgram shader) {
        ensureOpen();
        if (!previousValid) {
            throw new IllegalStateException("morph binding has no committed previous weights");
        }
        if (!previousUploaded) {
            bytes.clear();
            bytes.asFloatBuffer().put(previousPacked);
            bytes.limit(previousPacked.length * Float.BYTES);
            commands.uploadBufferRegion(previousWeightBuffer, 0L, bytes);
            previousUploaded = true;
        }
        commands.bindStorageBuffer(PREVIOUS_MORPH_WEIGHT_BINDING, previousWeightBuffer, 0L,
                bytes.capacity());
        TemporalDrawBinding skinTemporal = skinTemporal();
        if (skinTemporal != null) skinTemporal.recordPrevious(commands, shader);
    }

    @Override
    public void commitTemporalFrame() {
        if (pendingValid) {
            System.arraycopy(pendingPacked, 0, previousPacked, 0, previousPacked.length);
            previousValid = true;
            previousUploaded = false;
            pendingValid = false;
        }
        TemporalDrawBinding skinTemporal = skinTemporal();
        if (skinTemporal != null) skinTemporal.commitTemporalFrame();
    }

    @Override
    public void discardTemporalFrame() {
        pendingValid = false;
        // A failed frame's uploads never executed; invalidate both caches so the
        // next successful frame re-uploads current weights and previous data.
        uploadedRevision = Long.MIN_VALUE;
        previousUploaded = false;
        TemporalDrawBinding skinTemporal = skinTemporal();
        if (skinTemporal != null) skinTemporal.discardTemporalFrame();
    }

    @Override
    public void invalidatePrevious() {
        previousValid = false;
        pendingValid = false;
        previousUploaded = false;
        uploadedRevision = Long.MIN_VALUE;
        TemporalDrawBinding skinTemporal = skinTemporal();
        if (skinTemporal != null) skinTemporal.invalidatePrevious();
    }

    long weightByteSize() {
        return bytes.capacity();
    }

    private TemporalDrawBinding skinTemporal() {
        return skin.temporalBinding();
    }

    @Override
    public void close() {
        if (closed) return;
        RuntimeException failure = null;
        try {
            previousWeightBuffer.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            weightBuffer.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        closed = true;
        if (failure != null) throw failure;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("morph draw binding is closed");
    }
}
