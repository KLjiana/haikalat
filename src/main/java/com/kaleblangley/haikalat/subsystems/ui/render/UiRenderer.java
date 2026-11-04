package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.subsystems.ui.UiImageId;
import com.kaleblangley.haikalat.subsystems.text.GlyphUploadRequest;
import org.joml.Vector4f;

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
    private final Vector4f textEffectColor1 = new Vector4f();
    private final Vector4f textEffectColor2 = new Vector4f();
    private int lastDrawCalls;
    private int lastQuadCount;
    private boolean closed;
    private long glyphGeneration;

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
        Objects.requireNonNull(commands, "commands");
        int width=target==null?snapshot.framebufferWidth():target.width();
        int height=target==null?snapshot.framebufferHeight():target.height();
        if (width==0||height==0||snapshot.windowWidth()==0||snapshot.windowHeight()==0) {
            lastDrawCalls=0;lastQuadCount=0;return;
        }
        PreparedDraw prepared=prepare(snapshot);
        try {
            var operations=new java.util.ArrayList<UiLayerFrame.Operation>();
            for(int i=0;i<snapshot.batches().size();i++)operations.add(UiLayerFrame.Operation.draw(i));
            recordSegment(prepared, commands, target==null?0:target.drawFramebufferId(), width,height,
                    target==null||target.colorFormat()==RenderFormat.SRGB8_ALPHA8,
                    new UiScreenRect(0,0,snapshot.windowWidth(),snapshot.windowHeight()),operations,
                    child->{throw new IllegalStateException("unexpected child in direct UI");});
            finish(prepared,commands);
        } catch(RuntimeException|Error failure) {abort(prepared);throw failure;}
    }

    /** One persistent-ring write shared by every offscreen and direct consumer of this snapshot. */
    final class PreparedDraw {
        final UiRenderSnapshot snapshot;
        final UiVertexRing.WriteSlice slice;
        int drawIndex;
        boolean finished;
        PreparedDraw(UiRenderSnapshot snapshot,UiVertexRing.WriteSlice slice) {this.snapshot=snapshot;this.slice=slice;}
    }

    PreparedDraw prepare(UiRenderSnapshot snapshot) {
        return prepare(snapshot,true);
    }
    PreparedDraw prepare(UiRenderSnapshot snapshot,boolean needsVertices) {
        ensureOpen();Objects.requireNonNull(snapshot,"snapshot");
        resources.claimOrAssertRenderThread();resources.pollGlyphGpuCompletions();
        lastDrawCalls=0;lastQuadCount=0;
        if(snapshot.displayList().quadCount()==0||!needsVertices)return new PreparedDraw(snapshot,null);
        if(snapshot.displayList().quadCount()>maximumQuads)throw new IllegalArgumentException("UI snapshot exceeds ring capacity");
        int count=validateAndCountDraws(snapshot.batches());
        resources.ensureInitialized();resources.ensureVertexArrayCount(count);
        UiVertexRing.WriteSlice slice=resources.vertexRing().beginWrite(snapshot.displayList().quadCount());
        try {writeVertices(snapshot.displayList(),slice.bytes());return new PreparedDraw(snapshot,slice);}
        catch(RuntimeException|Error failure){resources.vertexRing().abortWrite();throw failure;}
    }

    void recordSegment(PreparedDraw prepared, CommandBuffer commands,int framebuffer,int width,int height,
                       boolean srgb,UiScreenRect view,List<UiLayerFrame.Operation> operations,
                       java.util.function.Consumer<String> composite) {
        commands.bindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,framebuffer).viewport(0,0,width,height)
                .enableFramebufferSrgb(srgb).enableBlend(true).blendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA)
                .enableDepthTest(false).depthMask(false).enableCullFace(false).enableScissor(false);
        var snapshot=prepared.snapshot;var batches=snapshot.batches();
        for(var operation:operations) {
            if(operation.child()!=null){composite.accept(operation.child());continue;}
            if(prepared.slice==null)continue;
            commands.bindShader(resources.shader()).setUniformVec2(resources.shader(),"uViewport",(float)view.width(),(float)view.height())
                    .setUniformVec2(resources.shader(),"uOrigin",(float)view.x(),(float)view.y())
                    .setUniformInt(resources.shader(),"uTexture",TEXTURE_UNIT);
            int batch=operation.batch();
            if(!configureBatchState(commands,batches,batch,snapshot,width,height,view))continue;
            int first=batches.firstQuad(batch),remaining=batches.quadCount(batch);lastQuadCount+=remaining;
            while(remaining>0) {
                int part=Math.min(remaining,MAX_QUADS_PER_DRAW);
                VertexArray vao=resources.vertexArray(prepared.drawIndex++);
                long offset=(long)prepared.slice.slotOffsetBytes()+(long)first*UiVertexRing.VERTICES_PER_QUAD*UiVertexRing.VERTEX_STRIDE_BYTES;
                glVertexArrayVertexBuffer(vao.id(),0,resources.vertexRing().buffer().id(),offset,UiVertexRing.VERTEX_STRIDE_BYTES);
                commands.bindVertexArray(vao.id()).drawElements(GL_TRIANGLES,part*6,GL_UNSIGNED_SHORT);
                first+=part;remaining-=part;lastDrawCalls++;
            }
        }
        commands.enableScissor(false);
    }

    void finish(PreparedDraw prepared,CommandBuffer commands) {
        if(prepared.finished)throw new IllegalStateException("UI snapshot already submitted");
        if(prepared.slice!=null){resources.vertexRing().markSubmitted();commands.insertGpuFence(resources.vertexRing());}
        prepared.finished=true;
    }
    void abort(PreparedDraw prepared) {
        if(prepared!=null&&prepared.slice!=null&&!prepared.finished)resources.vertexRing().abortWrite();
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
        glyphGeneration++;
        return resources.recordGlyphUploads(requests, commands);
    }

    long glyphGeneration() { return glyphGeneration; }
    long imageGeneration(long id) { return imageResolver.generation(new UiImageId(id)); }
    UiImageRegion imageRegion(long id) { return imageResolver.resolve(new UiImageId(id)).orElse(null); }

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
                                        int framebufferWidth, int framebufferHeight,UiScreenRect view) {
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
            UiScreenRect clip=batches.clip(batch).intersect(view);
            UiScreenRect local=new UiScreenRect(clip.x()-view.x(),clip.y()-view.y(),clip.width(),clip.height());
            GlScissorRect scissor=local.toGlScissor(framebufferWidth/view.width(),framebufferHeight/view.height(),framebufferWidth,framebufferHeight);
            commands.scissor(scissor.x(),scissor.y(),scissor.width(),scissor.height()).enableScissor(true);
        } else commands.enableScissor(false);
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
        commands.setUniformInt(resources.shader(), "uTextEffectType", effectType);
        if (effectType == 0) {
            // Plain text only has to reset the discriminator. It must not pay for
            // effect colors, geometry parameters, or temporary vector allocation.
            return;
        }
        int color1 = displayList.textEffectColor1(primitiveIndex);
        int color2 = displayList.textEffectColor2(primitiveIndex);
        float thickness = displayList.textEffectThickness(primitiveIndex);
        float offsetX = displayList.textEffectOffsetX(primitiveIndex);
        float offsetY = displayList.textEffectOffsetY(primitiveIndex);
        float blur = displayList.textEffectBlur(primitiveIndex);
        // TextEffect exposes user-facing degrees; GLSL trigonometry uses radians.
        float angle = (float) Math.toRadians(displayList.textEffectAngle(primitiveIndex));

        unpackColor(color1, textEffectColor1);
        unpackColor(color2, textEffectColor2);
        commands.setUniformVec4(resources.shader(), "uTextEffectColor1", textEffectColor1)
                .setUniformVec4(resources.shader(), "uTextEffectColor2", textEffectColor2)
                .setUniformFloat(resources.shader(), "uTextEffectThickness", thickness)
                .setUniformVec2(resources.shader(), "uTextEffectOffset", offsetX, offsetY)
                .setUniformFloat(resources.shader(), "uTextEffectBlur", blur)
                .setUniformFloat(resources.shader(), "uTextEffectAngle", angle);
    }

    private static void unpackColor(int packedRgba8, Vector4f target) {
        target.set(((packedRgba8 >>> 24) & 0xFF) / 255.0f,
                ((packedRgba8 >>> 16) & 0xFF) / 255.0f,
                ((packedRgba8 >>> 8) & 0xFF) / 255.0f,
                (packedRgba8 & 0xFF) / 255.0f);
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
