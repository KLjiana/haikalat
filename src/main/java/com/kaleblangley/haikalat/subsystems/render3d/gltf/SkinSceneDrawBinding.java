package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.animation.JointPalette;
import com.kaleblangley.haikalat.subsystems.render3d.SceneDrawBinding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;

/** Render-thread adapter from a CPU joint palette to an SSBO draw binding. */
final class SkinSceneDrawBinding implements SceneDrawBinding, AutoCloseable {
    static final int STORAGE_BINDING = 7;

    private final JointPalette palette;
    private final GlBuffer buffer;
    private final float[] packed;
    private final ByteBuffer bytes;
    private int lastFrameIndex;
    private Pass lastPass;
    private long lastRevision = Long.MIN_VALUE;
    private boolean hasRecordedUpload;
    private boolean closed;

    SkinSceneDrawBinding(JointPalette palette) {
        this.palette = Objects.requireNonNull(palette, "palette");
        buffer = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(palette.byteSize());
        packed = new float[palette.floatCount()];
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

    JointPalette palette() {
        return palette;
    }

    @Override
    public void close() {
        if (closed) return;
        buffer.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("skin draw binding is closed");
    }
}
