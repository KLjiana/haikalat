package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.subsystems.ui.UiImageId;
import com.kaleblangley.haikalat.subsystems.text.GlyphUploadRequest;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_SHORT;
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
    private final UiRenderResourceOwner resources;
    private final UiImageResolver imageResolver;
    private final UiSdfRenderer sdfRenderer = new UiSdfRenderer();
    private int lastDrawCalls;
    private int lastQuadCount;
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
        resources = new UiRenderResourceOwner(maximumQuads,
                glyphAtlasWidth, glyphAtlasHeight, maximumGlyphAtlasPages);
    }

    /**
     * 把一个不可变 UI snapshot 录制到调用方命令缓冲。
     *
     * <p>record 会在最后一个 UI draw 后追加正式 typed GPU fence 命令；ring slot 只有在
     * CommandExecutor 到达该命令后才进入 GPU 所有权状态，不依赖 pass callback 的执行时机。</p>
     */
    public void record(UiRenderSnapshot snapshot, CommandBuffer commands) {
        record(snapshot, commands, null);
    }

    /**
     * Records UI into an explicit host target without presenting it.
     * Target dimensions, rather than GLFW dimensions, drive viewport and scissor conversion.
     */
    public void record(UiRenderSnapshot snapshot, CommandBuffer commands,
                       PresentationTarget target) {
        ensureOpen();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(commands, "commands");
        int framebufferWidth = target == null
                ? snapshot.framebufferWidth() : target.width();
        int framebufferHeight = target == null
                ? snapshot.framebufferHeight() : target.height();
        if (target != null && !target.isRenderable()) {
            lastDrawCalls = 0;
            lastQuadCount = 0;
            return;
        }
        resources.claimOrAssertRenderThread();
        resources.pollGlyphGpuCompletions();
        lastDrawCalls = 0;
        lastQuadCount = 0;
        recordPassState(commands, target, framebufferWidth, framebufferHeight);

        UiDisplayList displayList = snapshot.displayList();
        UiBatcher.Result batches = snapshot.batches();
        if (displayList.quadCount() == 0 || batches.size() == 0
                || framebufferWidth == 0 || framebufferHeight == 0
                || snapshot.windowWidth() == 0 || snapshot.windowHeight() == 0) {
            commands.enableScissor(false);
            return;
        }
        if (displayList.quadCount() > maximumQuads) {
            throw new IllegalArgumentException("UI snapshot contains " + displayList.quadCount()
                    + " quads, renderer capacity is " + maximumQuads);
        }
        int drawCount = validateAndCountDraws(batches);
        resources.ensureInitialized();
        resources.assertRenderThread();

        UiVertexRing.WriteSlice slice = resources.vertexRing().beginWrite(displayList.quadCount());
        boolean submitted = false;
        try {
            writeVertices(displayList, slice.bytes());
            resources.ensureVertexArrayCount(drawCount);

            commands.bindShader(resources.shader())
                    .setUniformVec2(resources.shader(), "uViewport",
                            snapshot.windowWidth(), snapshot.windowHeight())
                    .setUniformInt(resources.shader(), "uTexture", TEXTURE_UNIT);

            int drawIndex = 0;
            int drawnQuads = 0;
            for (int batch = 0; batch < batches.size(); batch++) {
                if (!configureBatchState(commands, batches, batch, snapshot,
                        framebufferWidth, framebufferHeight)) continue;
                int firstQuad = batches.firstQuad(batch);
                int remaining = batches.quadCount(batch);
                drawnQuads += remaining;
                while (remaining > 0) {
                    int part = Math.min(remaining, MAX_QUADS_PER_DRAW);
                    VertexArray vao = resources.vertexArray(drawIndex++);
                    long vertexOffset = (long) slice.slotOffsetBytes()
                            + (long) firstQuad * UiVertexRing.VERTICES_PER_QUAD
                            * UiVertexRing.VERTEX_STRIDE_BYTES;
                    glVertexArrayVertexBuffer(vao.id(), 0, resources.vertexRing().buffer().id(),
                            vertexOffset, UiVertexRing.VERTEX_STRIDE_BYTES);
                    commands.bindVertexArray(vao.id())
                            .drawElements(GL_TRIANGLES, part * 6, GL_UNSIGNED_SHORT);
                    firstQuad += part;
                    remaining -= part;
                }
            }
            commands.enableScissor(false);
            resources.vertexRing().markSubmitted();
            commands.insertGpuFence(resources.vertexRing());
            submitted = true;
            lastDrawCalls = drawIndex;
            lastQuadCount = drawnQuads;
        } finally {
            if (!submitted) resources.vertexRing().abortWrite();
        }
    }

    public boolean isInitialized() {
        return resources.isInitialized();
    }

    /** 返回 renderer 是否已完成幂等关闭。 */
    public boolean isClosed() {
        return closed || resources.isClosed();
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
        return resources.ringWaitNanos();
    }

    /**
     * 在 render thread 把稳定 glyph upload request 录制到当前命令流。
     *
     * @see UiGlyphAtlasGpu#recordUploads(Iterable, CommandBuffer)
     */
    public UiGlyphAtlasGpu.UploadSubmission recordGlyphUploads(
            Iterable<GlyphUploadRequest> requests, CommandBuffer commands) {
        ensureOpen();
        return resources.recordGlyphUploads(requests, commands);
    }

    /** 由 UI/update 线程安全获取一个完整 glyph 上传结果。 */
    public Optional<UiGlyphUploadResult> pollCompletedGlyphUpload() {
        return resources.pollCompletedGlyphUpload();
    }

    /** 由 UI/update 线程安全获取当前全部完整 glyph 上传结果。 */
    public List<UiGlyphUploadResult> drainCompletedGlyphUploads() {
        return resources.drainCompletedGlyphUploads();
    }

    @Override
    public void close() {
        if (closed) return;
        resources.close();
        closed = true;
    }

    private boolean configureBatchState(CommandBuffer commands, UiBatcher.Result batches,
                                        int batch, UiRenderSnapshot snapshot,
                                        int framebufferWidth, int framebufferHeight) {
        int texture = batches.texture(batch);
        if (batches.shader(batch) == UiShaderVariant.TEXTURED && batches.imageId(batch) >= 0L) {
            UiImageRegion resolved = imageResolver.resolve(new UiImageId(batches.imageId(batch)))
                    .orElse(null);
            if (resolved == null) return false;
            texture = resolved.textureId();
        }
        commands.setUniformInt(resources.shader(), "uMode", shaderMode(batches.shader(batch)));
        if (batches.shader(batch) == UiShaderVariant.SDF) {
            sdfRenderer.recordUniforms(resources.shader(), commands,
                    snapshot.displayList(), batches, batch);
        }
        if (batches.shader(batch) == UiShaderVariant.GLYPH) {
            // Set text effect uniforms for glyph rendering
            recordTextEffectUniforms(commands, snapshot.displayList(), batches, batch);
        }
        if (batches.hasClip(batch)) {
            double scaleX = snapshot.windowWidth() == 0 ? 1.0
                    : (double) framebufferWidth / snapshot.windowWidth();
            double scaleY = snapshot.windowHeight() == 0 ? 1.0
                    : (double) framebufferHeight / snapshot.windowHeight();
            GlScissorRect scissor = batches.glScissor(batch,
                    scaleX, scaleY, framebufferWidth, framebufferHeight);
            commands.scissor(scissor.x(), scissor.y(), scissor.width(), scissor.height())
                    .enableScissor(true);
        } else {
            commands.enableScissor(false);
        }
        if (batches.shader(batch) == UiShaderVariant.TEXTURED
                || batches.shader(batch) == UiShaderVariant.GLYPH) {
            texture = batches.shader(batch) == UiShaderVariant.GLYPH
                    ? resources.glyphTextureId(batches.texture(batch))
                    : texture;
            commands.bindTexture(TEXTURE_UNIT, texture)
                    .bindSampler(TEXTURE_UNIT, resources.sampler());
        }
        return true;
    }

    private static void recordPassState(CommandBuffer commands, PresentationTarget target,
                                        int width, int height) {
        if (target == null) {
            commands.bindDefaultFramebuffer();
        } else {
            commands.bindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                    target.drawFramebufferId());
        }
        commands
                .viewport(0, 0, width, height)
                .enableFramebufferSrgb(target == null
                        || target.colorFormat() == RenderFormat.SRGB8_ALPHA8)
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
            case SDF -> 4;
        };
    }

    private void recordTextEffectUniforms(CommandBuffer commands, UiDisplayList displayList,
                                         UiBatcher.Result batches, int batch) {
        int primitiveIndex = batches.firstPrimitive(batch);
        byte effectType = displayList.textEffectType(primitiveIndex);
        int color1 = displayList.textEffectColor1(primitiveIndex);
        int color2 = displayList.textEffectColor2(primitiveIndex);
        float thickness = displayList.textEffectThickness(primitiveIndex);
        float offsetX = displayList.textEffectOffsetX(primitiveIndex);
        float offsetY = displayList.textEffectOffsetY(primitiveIndex);
        float blur = displayList.textEffectBlur(primitiveIndex);
        // TextEffect exposes user-facing degrees; GLSL trigonometry uses radians.
        float angle = (float) Math.toRadians(displayList.textEffectAngle(primitiveIndex));

        commands.setUniformInt(resources.shader(), "uTextEffectType", effectType)
                .setUniformVec4(resources.shader(), "uTextEffectColor1",
                        new org.joml.Vector4f(
                                ((color1 >>> 24) & 0xFF) / 255.0f,
                                ((color1 >>> 16) & 0xFF) / 255.0f,
                                ((color1 >>> 8) & 0xFF) / 255.0f,
                                (color1 & 0xFF) / 255.0f))
                .setUniformVec4(resources.shader(), "uTextEffectColor2",
                        new org.joml.Vector4f(
                                ((color2 >>> 24) & 0xFF) / 255.0f,
                                ((color2 >>> 16) & 0xFF) / 255.0f,
                                ((color2 >>> 8) & 0xFF) / 255.0f,
                                (color2 & 0xFF) / 255.0f))
                .setUniformFloat(resources.shader(), "uTextEffectThickness", thickness)
                .setUniformVec2(resources.shader(), "uTextEffectOffset", offsetX, offsetY)
                .setUniformFloat(resources.shader(), "uTextEffectBlur", blur)
                .setUniformFloat(resources.shader(), "uTextEffectAngle", angle);
    }

    private static void writeVertices(UiDisplayList displayList, ByteBuffer target) {
        for (int quad = 0; quad < displayList.quadCount(); quad++) {
            double left = displayList.quadX(quad);
            double top = displayList.quadY(quad);
            double right = left + displayList.quadWidth(quad);
            double bottom = top + displayList.quadHeight(quad);
            double m00 = displayList.quadTransformM00(quad);
            double m01 = displayList.quadTransformM01(quad);
            double m10 = displayList.quadTransformM10(quad);
            double m11 = displayList.quadTransformM11(quad);
            double tx = displayList.quadTransformX(quad);
            double ty = displayList.quadTransformY(quad);
            float u0 = displayList.quadU0(quad);
            float v0 = displayList.quadV0(quad);
            float u1 = displayList.quadU1(quad);
            float v1 = displayList.quadV1(quad);
            int color = displayList.quadColor(quad);
            putTransformedVertex(target, left, top, m00, m01, m10, m11, tx, ty,
                    u0, v0, 0.0f, 0.0f, color);
            putTransformedVertex(target, right, top, m00, m01, m10, m11, tx, ty,
                    u1, v0, 1.0f, 0.0f, color);
            putTransformedVertex(target, right, bottom, m00, m01, m10, m11, tx, ty,
                    u1, v1, 1.0f, 1.0f, color);
            putTransformedVertex(target, left, bottom, m00, m01, m10, m11, tx, ty,
                    u0, v1, 0.0f, 1.0f, color);
        }
        if (target.hasRemaining()) {
            throw new IllegalStateException("UI vertex ring write size did not match snapshot quad count");
        }
    }

    private static void putTransformedVertex(ByteBuffer target, double x, double y,
                                             double m00, double m01,
                                              double m10, double m11,
                                              double tx, double ty,
                                              float u, float v,
                                              float localU, float localV, int rgba) {
        putVertex(target, (float) (m00 * x + m01 * y + tx),
                (float) (m10 * x + m11 * y + ty), u, v, localU, localV, rgba);
    }

    private static void putVertex(ByteBuffer target, float x, float y,
                                  float u, float v, float localU, float localV, int rgba) {
        target.putFloat(x).putFloat(y).putFloat(u).putFloat(v)
                .put((byte) (rgba >>> 24))
                .put((byte) (rgba >>> 16))
                .put((byte) (rgba >>> 8))
                .put((byte) rgba)
                .putFloat(localU).putFloat(localV);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UiRenderer is closed");
    }
}
