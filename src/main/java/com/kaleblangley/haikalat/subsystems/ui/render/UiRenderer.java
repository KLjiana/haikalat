package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.ui.UiImageId;
import com.kaleblangley.haikalat.subsystems.ui.text.GlyphUploadRequest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;

/**
 * UI display list 的 OpenGL 4.6 renderer。
 *
 * <p>GL 资源在首次非空 draw 时延迟创建。顶点写入三槽 persistent mapped ring，EBO 和 VAO
 * 高水位池持续复用；所有 draw、资源绑定与 pipeline state 都通过 typed {@link CommandBuffer}
 * 录制，不使用 custom escape hatch。</p>
 */
public final class UiRenderer implements AutoCloseable {
    /** uint16 索引能覆盖的最大四边形数：最大顶点索引为 65535。 */
    public static final int MAX_QUADS_PER_DRAW = 16_384;
    public static final int DEFAULT_MAXIMUM_QUADS = 65_536;
    public static final int DEFAULT_GLYPH_ATLAS_WIDTH = 1_024;
    public static final int DEFAULT_GLYPH_ATLAS_HEIGHT = 1_024;
    public static final int DEFAULT_MAXIMUM_GLYPH_ATLAS_PAGES = 8;
    private static final int TEXTURE_UNIT = 0;

    private final int maximumQuads;
    private final UiGlyphAtlasGpu glyphAtlas;
    private final UiImageResolver imageResolver;
    private final List<VertexArray> vertexArrays = new ArrayList<>();
    private ShaderProgram shader;
    private Sampler sampler;
    private GlBuffer indexBuffer;
    private UiVertexRing vertexRing;
    private Thread renderThread;
    private int lastDrawCalls;
    private int lastQuadCount;
    private boolean initialized;
    private boolean closed;

    public UiRenderer() {
        this(DEFAULT_MAXIMUM_QUADS);
    }

    public UiRenderer(int maximumQuads) {
        this(maximumQuads, DEFAULT_GLYPH_ATLAS_WIDTH, DEFAULT_GLYPH_ATLAS_HEIGHT,
                DEFAULT_MAXIMUM_GLYPH_ATLAS_PAGES);
    }

    /**
     * 创建具有明确 glyph atlas 容量的 UI renderer。
     *
     * @param maximumQuads UI quad ring 容量
     * @param glyphAtlasWidth 单个 R8 glyph page 宽度
     * @param glyphAtlasHeight 单个 R8 glyph page 高度
     * @param maximumGlyphAtlasPages 最大 glyph page 数
     */
    public UiRenderer(int maximumQuads, int glyphAtlasWidth, int glyphAtlasHeight,
                      int maximumGlyphAtlasPages) {
        this(maximumQuads, glyphAtlasWidth, glyphAtlasHeight,
                maximumGlyphAtlasPages, UiImageResolver.empty());
    }

    /** 创建带 render-record 时逻辑图片重解析能力的 renderer。 */
    public UiRenderer(int maximumQuads, int glyphAtlasWidth, int glyphAtlasHeight,
                      int maximumGlyphAtlasPages, UiImageResolver imageResolver) {
        if (maximumQuads <= 0) throw new IllegalArgumentException("maximumQuads must be positive");
        this.maximumQuads = maximumQuads;
        this.imageResolver = Objects.requireNonNull(imageResolver, "imageResolver");
        glyphAtlas = new UiGlyphAtlasGpu(
                glyphAtlasWidth, glyphAtlasHeight, maximumGlyphAtlasPages);
    }

    /**
     * 把一个不可变 UI snapshot 录制到调用方命令缓冲。
     *
     * <p>record 会在最后一个 UI draw 后追加正式 typed GPU fence 命令；ring slot 只有在
     * CommandExecutor 到达该命令后才进入 GPU 所有权状态，不依赖 pass callback 的执行时机。</p>
     */
    public void record(UiRenderSnapshot snapshot, CommandBuffer commands) {
        ensureOpen();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(commands, "commands");
        claimOrAssertRenderThread();
        glyphAtlas.pollGpuCompletions();
        lastDrawCalls = 0;
        lastQuadCount = 0;
        recordPassState(commands, snapshot.framebufferWidth(), snapshot.framebufferHeight());

        UiDisplayList displayList = snapshot.displayList();
        UiBatcher.Result batches = snapshot.batches();
        if (displayList.quadCount() == 0 || batches.size() == 0
                || snapshot.framebufferWidth() == 0 || snapshot.framebufferHeight() == 0
                || snapshot.windowWidth() == 0 || snapshot.windowHeight() == 0) {
            commands.enableScissor(false);
            return;
        }
        if (displayList.quadCount() > maximumQuads) {
            throw new IllegalArgumentException("UI snapshot contains " + displayList.quadCount()
                    + " quads, renderer capacity is " + maximumQuads);
        }
        int drawCount = validateAndCountDraws(batches);
        ensureInitialized();
        assertRenderThread();

        UiVertexRing.WriteSlice slice = vertexRing.beginWrite(displayList.quadCount());
        boolean submitted = false;
        try {
            writeVertices(displayList, slice.bytes());
            ensureVertexArrayCount(drawCount);

            commands.bindShader(shader)
                    .setUniformVec2(shader, "uViewport",
                            snapshot.windowWidth(), snapshot.windowHeight())
                    .setUniformInt(shader, "uTexture", TEXTURE_UNIT);

            int drawIndex = 0;
            int drawnQuads = 0;
            for (int batch = 0; batch < batches.size(); batch++) {
                if (!configureBatchState(commands, batches, batch, snapshot)) continue;
                int firstQuad = batches.firstQuad(batch);
                int remaining = batches.quadCount(batch);
                drawnQuads += remaining;
                while (remaining > 0) {
                    int part = Math.min(remaining, MAX_QUADS_PER_DRAW);
                    VertexArray vao = vertexArrays.get(drawIndex++);
                    long vertexOffset = (long) slice.slotOffsetBytes()
                            + (long) firstQuad * UiVertexRing.VERTICES_PER_QUAD
                            * UiVertexRing.VERTEX_STRIDE_BYTES;
                    glVertexArrayVertexBuffer(vao.id(), 0, vertexRing.buffer().id(),
                            vertexOffset, UiVertexRing.VERTEX_STRIDE_BYTES);
                    commands.bindVertexArray(vao.id())
                            .drawElements(GL_TRIANGLES, part * 6, GL_UNSIGNED_SHORT);
                    firstQuad += part;
                    remaining -= part;
                }
            }
            commands.enableScissor(false);
            vertexRing.markSubmitted();
            commands.insertGpuFence(vertexRing);
            submitted = true;
            lastDrawCalls = drawIndex;
            lastQuadCount = drawnQuads;
        } finally {
            if (!submitted) vertexRing.abortWrite();
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** 返回 renderer 是否已完成幂等关闭。 */
    public boolean isClosed() {
        return closed;
    }

    public int maximumQuads() {
        return maximumQuads;
    }

    public int lastDrawCalls() {
        return lastDrawCalls;
    }

    public int lastQuadCount() {
        return lastQuadCount;
    }

    /** 返回 persistent vertex ring 等待 GPU slot 的累计纳秒数。 */
    public long ringWaitNanos() {
        UiVertexRing ring = vertexRing;
        return ring == null ? 0L : ring.waitNanos();
    }

    /**
     * 在 render thread 把稳定 glyph upload request 录制到当前命令流。
     *
     * @see UiGlyphAtlasGpu#recordUploads(Iterable, CommandBuffer)
     */
    public UiGlyphAtlasGpu.UploadSubmission recordGlyphUploads(
            Iterable<GlyphUploadRequest> requests, CommandBuffer commands) {
        ensureOpen();
        claimOrAssertRenderThread();
        return glyphAtlas.recordUploads(requests, commands);
    }

    /** 由 UI/update 线程安全获取一个完整 glyph 上传结果。 */
    public Optional<UiGlyphUploadResult> pollCompletedGlyphUpload() {
        return glyphAtlas.pollCompletedResult();
    }

    /** 由 UI/update 线程安全获取当前全部完整 glyph 上传结果。 */
    public List<UiGlyphUploadResult> drainCompletedGlyphUploads() {
        return glyphAtlas.drainCompletedResults();
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

    private void ensureInitialized() {
        if (initialized) return;
        claimOrAssertRenderThread();
        ShaderProgram createdShader = null;
        Sampler createdSampler = null;
        GlBuffer createdIndices = null;
        UiVertexRing createdRing = null;
        try {
            createdShader = ShaderProgram.fromResource(UiRenderer.class,
                    "/ui/ui.vert", "/ui/ui.frag");
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

    private void ensureVertexArrayCount(int required) {
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
    }

    private boolean configureBatchState(CommandBuffer commands, UiBatcher.Result batches,
                                        int batch, UiRenderSnapshot snapshot) {
        int texture = batches.texture(batch);
        if (batches.shader(batch) == UiShaderVariant.TEXTURED && batches.imageId(batch) >= 0L) {
            UiImageRegion resolved = imageResolver.resolve(new UiImageId(batches.imageId(batch)))
                    .orElse(null);
            if (resolved == null) return false;
            texture = resolved.textureId();
        }
        commands.setUniformInt(shader, "uMode", shaderMode(batches.shader(batch)));
        if (batches.hasClip(batch)) {
            GlScissorRect scissor = batches.glScissor(batch,
                    snapshot.framebufferScaleX(), snapshot.framebufferScaleY(),
                    snapshot.framebufferWidth(), snapshot.framebufferHeight());
            commands.scissor(scissor.x(), scissor.y(), scissor.width(), scissor.height())
                    .enableScissor(true);
        } else {
            commands.enableScissor(false);
        }
        if (batches.shader(batch) == UiShaderVariant.TEXTURED
                || batches.shader(batch) == UiShaderVariant.GLYPH) {
            texture = batches.shader(batch) == UiShaderVariant.GLYPH
                    ? glyphAtlas.renderTextureId(batches.texture(batch))
                    : texture;
            commands.bindTexture(TEXTURE_UNIT, texture)
                    .bindSampler(TEXTURE_UNIT, sampler);
        }
        return true;
    }

    private static void recordPassState(CommandBuffer commands, int width, int height) {
        commands.bindDefaultFramebuffer()
                .viewport(0, 0, width, height)
                .enableFramebufferSrgb(true)
                .enableBlend(true)
                .blendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableScissor(false);
    }

    private static int validateAndCountDraws(UiBatcher.Result batches) {
        int draws = 0;
        for (int batch = 0; batch < batches.size(); batch++) {
            if (batches.blend(batch) != UiBlendMode.PREMULTIPLIED_ALPHA) {
                throw new IllegalArgumentException("UI renderer requires premultiplied-alpha batches");
            }
            if (batches.quadCount(batch) <= 0) {
                throw new IllegalArgumentException("UI draw batch must contain at least one quad");
            }
            draws = Math.addExact(draws,
                    (batches.quadCount(batch) + MAX_QUADS_PER_DRAW - 1) / MAX_QUADS_PER_DRAW);
        }
        return draws;
    }

    private static int shaderMode(UiShaderVariant variant) {
        return switch (variant) {
            case SOLID -> 0;
            case TEXTURED -> 1;
            case GLYPH -> 2;
            case DEBUG_OUTLINE -> 3;
        };
    }

    private static void writeVertices(UiDisplayList displayList, ByteBuffer target) {
        for (int quad = 0; quad < displayList.quadCount(); quad++) {
            float left = (float) displayList.quadX(quad);
            float top = (float) displayList.quadY(quad);
            float right = (float) (displayList.quadX(quad) + displayList.quadWidth(quad));
            float bottom = (float) (displayList.quadY(quad) + displayList.quadHeight(quad));
            float u0 = displayList.quadU0(quad);
            float v0 = displayList.quadV0(quad);
            float u1 = displayList.quadU1(quad);
            float v1 = displayList.quadV1(quad);
            int color = displayList.quadColor(quad);
            putVertex(target, left, top, u0, v0, color);
            putVertex(target, right, top, u1, v0, color);
            putVertex(target, right, bottom, u1, v1, color);
            putVertex(target, left, bottom, u0, v1, color);
        }
        if (target.hasRemaining()) {
            throw new IllegalStateException("UI vertex ring write size did not match snapshot quad count");
        }
    }

    private static void putVertex(ByteBuffer target, float x, float y,
                                  float u, float v, int rgba) {
        target.putFloat(x).putFloat(y).putFloat(u).putFloat(v)
                .put((byte) (rgba >>> 24))
                .put((byte) (rgba >>> 16))
                .put((byte) (rgba >>> 8))
                .put((byte) rgba);
    }

    private static ByteBuffer buildIndices() {
        ByteBuffer indices = ByteBuffer.allocateDirect(MAX_QUADS_PER_DRAW * 6 * Short.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int quad = 0; quad < MAX_QUADS_PER_DRAW; quad++) {
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

    private void assertRenderThread() {
        if (renderThread != Thread.currentThread()) {
            throw new IllegalStateException("UiRenderer is owned by render thread "
                    + renderThread.getName() + " but accessed from " + Thread.currentThread().getName());
        }
    }

    private void claimOrAssertRenderThread() {
        if (renderThread == null) {
            renderThread = Thread.currentThread();
        } else {
            assertRenderThread();
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UiRenderer is closed");
    }

    private static RuntimeException close(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) return failure;
        try {
            resource.close();
            return failure;
        } catch (RuntimeException exception) {
            return append(failure, exception);
        } catch (Exception exception) {
            return append(failure, new IllegalStateException("Failed to close UI GL resource", exception));
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
