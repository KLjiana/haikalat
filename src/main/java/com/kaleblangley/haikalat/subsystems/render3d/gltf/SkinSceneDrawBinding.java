package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.animation.JointPalette;
import com.kaleblangley.haikalat.subsystems.render3d.SceneDrawBinding;
import com.kaleblangley.haikalat.subsystems.render3d.TemporalDrawBinding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;

/** Render-thread adapter from a CPU joint palette to an SSBO draw binding. */
final class SkinSceneDrawBinding implements SceneDrawBinding, TemporalDrawBinding, AutoCloseable {
    static final int STORAGE_BINDING = 7;

    private final JointPalette palette;
    private final GlBuffer buffer;
    private final GlBuffer previousBuffer;
    private final float[] packed;
    private final float[] previousPacked;
    private final float[] pendingPacked;
    private final ByteBuffer bytes;
    private int lastFrameIndex;
    private Pass lastPass;
    private long lastRevision = Long.MIN_VALUE;
    private boolean hasRecordedUpload;
    private boolean previousValid;
    private boolean pendingValid;
    private boolean previousUploaded;
    private boolean closed;

    SkinSceneDrawBinding(JointPalette palette) {
        this.palette = Objects.requireNonNull(palette, "palette");
        buffer = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(palette.byteSize());
        previousBuffer = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(palette.byteSize());
        packed = new float[palette.floatCount()];
        previousPacked = new float[palette.floatCount()];
        pendingPacked = new float[palette.floatCount()];
        bytes = ByteBuffer.allocateDirect(palette.byteSize()).order(ByteOrder.nativeOrder());
    }

    @Override
    public void record(CommandBuffer commands, ShaderProgram shader,
                       int frameIndex, Pass pass) {
        ensureOpen();
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(pass, "pass");
        if (!hasRecordedUpload || lastFrameIndex != frameIndex || lastPass != pass
                || lastRevision != palette.revision()) {
            palette.copyTo(packed, 0);
            bytes.clear();
            bytes.asFloatBuffer().put(packed);
            bytes.limit(palette.byteSize());
            commands.uploadBufferRegion(buffer, 0L, bytes);
            lastFrameIndex = frameIndex;
            lastPass = pass;
            lastRevision = palette.revision();
            hasRecordedUpload = true;
        }
        commands.bindStorageBuffer(STORAGE_BINDING, buffer, 0L, palette.byteSize());
    }

    @Override
    public boolean skinningEnabled() {
        return true;
    }

    @Override
    public long boundsRevision() {
        return palette.revision();
    }

    @Override
    public TemporalDrawBinding temporalBinding() {
        return this;
    }

    @Override
    public boolean previousDeformationAvailable() {
        return previousValid;
    }

    @Override
    public void prepareTemporalFrame() {
        ensureOpen();
        palette.copyTo(pendingPacked, 0);
        pendingValid = true;
    }

    @Override
    public void recordPrevious(CommandBuffer commands, ShaderProgram shader) {
        ensureOpen();
        if (!previousValid) {
            throw new IllegalStateException("skin binding has no committed previous palette");
        }
        if (!previousUploaded) {
            bytes.clear();
            bytes.asFloatBuffer().put(previousPacked);
            bytes.limit(palette.byteSize());
            commands.uploadBufferRegion(previousBuffer, 0L, bytes);
            previousUploaded = true;
        }
        commands.bindStorageBuffer(PREVIOUS_JOINT_PALETTE_BINDING, previousBuffer, 0L,
                palette.byteSize());
    }

    @Override
    public void commitTemporalFrame() {
        if (!pendingValid) return;
        System.arraycopy(pendingPacked, 0, previousPacked, 0, previousPacked.length);
        previousValid = true;
        previousUploaded = false;
        pendingValid = false;
    }

    @Override
    public void discardTemporalFrame() {
        pendingValid = false;
        // Commands recorded by a failed frame never reached the GPU; force the
        // committed previous palette to be re-uploaded next time.
        previousUploaded = false;
        hasRecordedUpload = false;
    }

    @Override
    public void invalidatePrevious() {
        previousValid = false;
        pendingValid = false;
        previousUploaded = false;
        hasRecordedUpload = false;
    }

    JointPalette palette() {
        return palette;
    }

    @Override
    public void close() {
        if (closed) return;
        RuntimeException failure = null;
        try {
            previousBuffer.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            buffer.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        closed = true;
        if (failure != null) throw failure;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("skin draw binding is closed");
    }
}
