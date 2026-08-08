package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.text.GlyphUploadRequest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;

/**
 * Render-thread owner for all GL resources used by {@link UiRenderer}.
 *
 * <p>Initialization is candidate-first: local resources are closed in reverse order on
 * failure and fields become active only after the full candidate succeeds.</p>
 */
final class UiRenderResourceOwner implements AutoCloseable {
    private final int maximumQuads;
    private final UiGlyphAtlasGpu glyphAtlas;
    private final List<VertexArray> vertexArrays = new ArrayList<>();
    private ShaderProgram shader;
    private Sampler sampler;
    private GlBuffer indexBuffer;
    private UiVertexRing vertexRing;
    private Thread renderThread;
    private boolean initialized;
    private boolean closed;

    UiRenderResourceOwner(int maximumQuads, int glyphAtlasWidth, int glyphAtlasHeight,
                          int maximumGlyphAtlasPages) {
        this.maximumQuads = maximumQuads;
        glyphAtlas = new UiGlyphAtlasGpu(
                glyphAtlasWidth, glyphAtlasHeight, maximumGlyphAtlasPages);
    }

    void claimOrAssertRenderThread() {
        if (renderThread == null) renderThread = Thread.currentThread();
        else assertRenderThread();
    }

    void assertRenderThread() {
        if (renderThread != Thread.currentThread()) {
            throw new IllegalStateException("UiRenderer is owned by render thread "
                    + renderThread.getName() + " but accessed from "
                    + Thread.currentThread().getName());
        }
    }

    void ensureInitialized() {
        ensureOpen();
        if (initialized) return;
        claimOrAssertRenderThread();
        ShaderProgram createdShader = null;
        Sampler createdSampler = null;
        GlBuffer createdIndices = null;
        UiVertexRing createdRing = null;
        try {
            createdShader = ShaderProgram.fromResource(UiRenderer.class,
                    "/shaders/ui/ui.vert", "/shaders/ui/ui.frag");
            createdSampler = Sampler.create(new Sampler.Descriptor(
                    GL_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
            createdIndices = GlBuffer.elementArrayBuffer(GL_STATIC_DRAW)
                    .upload(buildIndices());
            createdRing = new UiVertexRing(maximumQuads);
            shader = createdShader;
            sampler = createdSampler;
            indexBuffer = createdIndices;
            vertexRing = createdRing;
            initialized = true;
        } catch (RuntimeException | Error failure) {
            closeQuietly(createdRing, failure);
            closeQuietly(createdIndices, failure);
            closeQuietly(createdSampler, failure);
            closeQuietly(createdShader, failure);
            renderThread = null;
            throw failure;
        }
    }

    void ensureVertexArrayCount(int required) {
        while (vertexArrays.size() < required) {
            VertexArray vao = new VertexArray();
            try {
                vao.bindElementBuffer(indexBuffer);
                configureVertexFormat(vao);
                vertexArrays.add(vao);
            } catch (RuntimeException | Error failure) {
                vao.close();
                throw failure;
            }
        }
    }

    void pollGlyphGpuCompletions() {
        glyphAtlas.pollGpuCompletions();
    }

    UiGlyphAtlasGpu.UploadSubmission recordGlyphUploads(
            Iterable<GlyphUploadRequest> requests, CommandBuffer commands) {
        ensureOpen();
        claimOrAssertRenderThread();
        return glyphAtlas.recordUploads(requests, commands);
    }

    Optional<UiGlyphUploadResult> pollCompletedGlyphUpload() {
        return glyphAtlas.pollCompletedResult();
    }

    List<UiGlyphUploadResult> drainCompletedGlyphUploads() {
        return glyphAtlas.drainCompletedResults();
    }

    int glyphTextureId(int page) {
        return glyphAtlas.renderTextureId(page);
    }

    ShaderProgram shader() {
        return shader;
    }

    Sampler sampler() {
        return sampler;
    }

    UiVertexRing vertexRing() {
        return vertexRing;
    }

    VertexArray vertexArray(int index) {
        return vertexArrays.get(index);
    }

    boolean isInitialized() {
        return initialized;
    }

    boolean isClosed() {
        return closed;
    }

    long ringWaitNanos() {
        return vertexRing == null ? 0L : vertexRing.waitNanos();
    }

    @Override
    public void close() {
        if (closed) return;
        if (renderThread != null) assertRenderThread();
        RuntimeException failure = null;
        for (int index = vertexArrays.size() - 1; index >= 0; index--) {
            try {
                vertexArrays.get(index).close();
            } catch (RuntimeException exception) {
                failure = append(failure, exception);
            }
        }
        vertexArrays.clear();
        failure = close(indexBuffer, failure);
        failure = close(vertexRing, failure);
        failure = close(glyphAtlas, failure);
        failure = close(sampler, failure);
        failure = close(shader, failure);
        indexBuffer = null;
        vertexRing = null;
        sampler = null;
        shader = null;
        initialized = false;
        closed = true;
        if (failure != null) throw failure;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UiRenderer is closed");
    }

    private static void configureVertexFormat(VertexArray vao) {
        glEnableVertexArrayAttrib(vao.id(), 0);
        glVertexArrayAttribFormat(vao.id(), 0, 2, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao.id(), 0, 0);
        glEnableVertexArrayAttrib(vao.id(), 1);
        glVertexArrayAttribFormat(vao.id(), 1, 2, GL_FLOAT, false, 8);
        glVertexArrayAttribBinding(vao.id(), 1, 0);
        glEnableVertexArrayAttrib(vao.id(), 2);
        glVertexArrayAttribFormat(vao.id(), 2, 4, GL_UNSIGNED_BYTE, true, 16);
        glVertexArrayAttribBinding(vao.id(), 2, 0);
        glEnableVertexArrayAttrib(vao.id(), 3);
        glVertexArrayAttribFormat(vao.id(), 3, 2, GL_FLOAT, false, 20);
        glVertexArrayAttribBinding(vao.id(), 3, 0);
    }

    private static ByteBuffer buildIndices() {
        ByteBuffer indices = ByteBuffer.allocateDirect(
                UiRenderer.MAX_QUADS_PER_DRAW * 6 * Short.BYTES).order(ByteOrder.nativeOrder());
        for (int quad = 0; quad < UiRenderer.MAX_QUADS_PER_DRAW; quad++) {
            int vertex = quad * 4;
            indices.putShort((short) vertex);
            indices.putShort((short) (vertex + 1));
            indices.putShort((short) (vertex + 2));
            indices.putShort((short) (vertex + 2));
            indices.putShort((short) (vertex + 3));
            indices.putShort((short) vertex);
        }
        return indices.flip();
    }

    private static RuntimeException close(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) return failure;
        try {
            resource.close();
            return failure;
        } catch (RuntimeException exception) {
            return append(failure, exception);
        } catch (Exception exception) {
            return append(failure,
                    new IllegalStateException("Failed to close UI GL resource", exception));
        }
    }

    private static void closeQuietly(AutoCloseable resource, Throwable failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Throwable cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static RuntimeException append(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }
}
