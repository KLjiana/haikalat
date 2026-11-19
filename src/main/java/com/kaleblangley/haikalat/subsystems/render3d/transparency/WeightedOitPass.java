package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_ZERO;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;

/**
 * Weighted blended order-independent transparency producer (S3 #28).
 *
 * <p>The pass owns one private MRT framebuffer, two shader programs, the unit
 * quad used for the surface instances, the full-screen resolve quad, one
 * nearest-clamp sampler and one dynamic instance buffer. It never owns, binds
 * as a target, samples or writes the shared scene depth, and it never touches
 * the main transparent queue's framebuffer. Callers either feed a selected
 * collection here or keep it on the sorted alpha path; recording both is a
 * caller error that silently double-composites the same surfaces.</p>
 *
 * <h2>Attachment contract</h2>
 *
 * <p>Attachment 0 is {@code RGBA16F}:
 * {@code rgb = sum(colour * alpha * weight)} and
 * {@code a = product(1 - alpha)} (the revealage/transmittance). Attachment 1 is
 * {@code R16F} and stores {@code sum(alpha * weight)}. Folding the revealage
 * into {@code accum.a} is what allows one {@code glBlendFuncSeparate} call to
 * serve both attachments:</p>
 *
 * <pre>{@code
 * glBlendFuncSeparate(GL_ONE, GL_ONE, GL_ZERO, GL_ONE_MINUS_SRC_ALPHA)
 * }</pre>
 *
 * <p>RGB (both attachments) is summed; the alpha channel of attachment 0 is
 * multiplied by {@code 1 - srcAlpha}. The clear colour {@code (0,0,0,1)}
 * initialises the weight sum to zero and the transmittance to one. The resolve
 * then writes {@code (sum(c*a*w) / max(sum(a*w), eps), 1 - transmittance)} into
 * the caller's HDR target with {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}, i.e.
 * the classic "over" composite of the weighted average colour.</p>
 *
 * <h2>Frame contract</h2>
 *
 * <ol>
 *   <li>{@link #record} binds and clears the private framebuffer, draws every
 *       supplied quad and leaves the pass framebuffer bound. It writes no depth
 *       and performs no scene-depth rejection; the caller must supply the
 *       visible, already culled collection.</li>
 *   <li>The caller binds its HDR target and viewport.</li>
 *   <li>{@link #resolve} draws the full-screen resolve over that target. It
 *       only records a draw; the target remains caller-owned.</li>
 * </ol>
 *
 * <p>Both calls must be submitted in this order; they may live in different
 * command buffers as long as the render graph executes the accumulation pass
 * before the resolve pass. Resolving without a preceding record is rejected
 * instead of silently compositing stale or undefined attachment content.
 * {@code record} and {@code resolve} are exact no-ops while
 * {@link WeightedOitSettings#active()} is false, so toggling the feature off at
 * a frame boundary records zero GPU work and leaves the target untouched.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Construction requires active settings; a disabled value is rejected before
 * any GL resource is created. {@link #resize(int, int)} prepares a complete
 * candidate framebuffer before retiring the old one, so a creation failure
 * leaves the active extent and attachments untouched. {@link #close()} is
 * idempotent and closes only resources owned by this pass.</p>
 */
public final class WeightedOitPass implements GlResource {
    /** RenderGraph pass name; consumers depend on this producer for the resolved colour. */
    public static final String PASS_NAME = "WeightedOitPass";
    /** Resolve texture unit for attachment 0 (weighted colour + revealage). */
    public static final int ACCUMULATION_TEXTURE_UNIT = 0;
    /** Resolve texture unit for attachment 1 (weight sum). */
    public static final int WEIGHT_TEXTURE_UNIT = 1;

    static final int ACCUMULATION_BLEND_SOURCE_RGB = GL_ONE;
    static final int ACCUMULATION_BLEND_DESTINATION_RGB = GL_ONE;
    static final int REVEALAGE_BLEND_SOURCE_ALPHA = GL_ZERO;
    static final int REVEALAGE_BLEND_DESTINATION_ALPHA = GL_ONE_MINUS_SRC_ALPHA;
    static final int RESOLVE_BLEND_SOURCE = GL_SRC_ALPHA;
    static final int RESOLVE_BLEND_DESTINATION = GL_ONE_MINUS_SRC_ALPHA;

    private static final String SHADER_ROOT = "/shaders/render3d/transparency/";
    private static final String SURFACE_VERTEX = SHADER_ROOT + "weighted-oit-surface.vert";
    private static final String SURFACE_FRAGMENT = SHADER_ROOT + "weighted-oit-surface.frag";
    private static final String RESOLVE_FRAGMENT = SHADER_ROOT + "weighted-oit-resolve.frag";
    private static final String SCREEN_QUAD_VERTEX = "/shaders/postprocess/screen-quad.vert";

    private static final int INSTANCE_FLOATS = 13;
    private static final int INSTANCE_STRIDE_BYTES = INSTANCE_FLOATS * Float.BYTES;
    private static final int RESOLVE_VERTEX_COUNT = 6;
    private static final float RESOLVE_WEIGHT_EPSILON = 1.0e-6f;

    private static final VertexLayout INSTANCE_LAYOUT = VertexLayout.interleaved(
            INSTANCE_STRIDE_BYTES,
            VertexAttribute.builder().index(1).size(3).type(GL_FLOAT)
                    .offsetBytes(0L).divisor(1).build(),
            VertexAttribute.builder().index(2).size(3).type(GL_FLOAT)
                    .offsetBytes(3L * Float.BYTES).divisor(1).build(),
            VertexAttribute.builder().index(3).size(3).type(GL_FLOAT)
                    .offsetBytes(6L * Float.BYTES).divisor(1).build(),
            VertexAttribute.builder().index(4).size(4).type(GL_FLOAT)
                    .offsetBytes(9L * Float.BYTES).divisor(1).build());

    private final ShaderProgram surfaceProgram;
    private final ShaderProgram resolveProgram;
    private final ScreenQuad resolveQuad;
    private final Sampler nearestSampler;
    private final Mesh unitQuad;
    private final GlBuffer instanceBuffer;
    private final ByteBuffer instanceData;
    private final int maximumSurfaces;

    private Framebuffer framebuffer;
    private int width;
    private int height;
    private boolean accumulated;
    private boolean closed;

    /**
     * Creates the pass and its private attachments.
     *
     * @param width    initial attachment width in pixels
     * @param height   initial attachment height in pixels
     * @param settings active settings; the quad capacity is fixed for the pass
     *                 lifetime and a capacity change requires a new pass
     */
    public WeightedOitPass(int width, int height, WeightedOitSettings settings) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("weighted OIT extent must be positive");
        }
        Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException(
                    "disabled WeightedOitSettings must not create a pass or attachments");
        }
        maximumSurfaces = settings.maximumSurfaces();
        instanceData = ByteBuffer.allocateDirect(maximumSurfaces * INSTANCE_STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());

        ShaderProgram createdSurface = null;
        ShaderProgram createdResolve = null;
        ScreenQuad createdResolveQuad = null;
        Sampler createdSampler = null;
        Mesh createdQuad = null;
        GlBuffer createdInstanceBuffer = null;
        Framebuffer createdFramebuffer = null;
        try {
            createdSurface = ShaderProgram.builder()
                    .resource(WeightedOitPass.class, ShaderStage.VERTEX, SURFACE_VERTEX)
                    .resource(WeightedOitPass.class, ShaderStage.FRAGMENT, SURFACE_FRAGMENT)
                    .link();
            createdResolve = ShaderProgram.builder()
                    .resource(WeightedOitPass.class, ShaderStage.VERTEX, SCREEN_QUAD_VERTEX)
                    .resource(WeightedOitPass.class, ShaderStage.FRAGMENT, RESOLVE_FRAGMENT)
                    .link();
            createdResolveQuad = new ScreenQuad();
            createdSampler = Sampler.create(new Sampler.Descriptor(GL_NEAREST, GL_NEAREST,
                    GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
            createdQuad = createUnitQuad();
            createdInstanceBuffer = GlBuffer.arrayBuffer(GL_DYNAMIC_DRAW)
                    .allocate((long) maximumSurfaces * INSTANCE_STRIDE_BYTES);
            createdQuad.bind();
            createdInstanceBuffer.bind();
            INSTANCE_LAYOUT.apply();
            createdInstanceBuffer.unbind();
            createdQuad.unbind();
            createdFramebuffer = createFramebuffer(width, height);

            surfaceProgram = createdSurface;
            resolveProgram = createdResolve;
            resolveQuad = createdResolveQuad;
            nearestSampler = createdSampler;
            unitQuad = createdQuad;
            instanceBuffer = createdInstanceBuffer;
            framebuffer = createdFramebuffer;
            this.width = width;
            this.height = height;
        } catch (RuntimeException | Error failure) {
            closeSuppressing(createdFramebuffer, failure);
            closeSuppressing(createdInstanceBuffer, failure);
            closeSuppressing(createdQuad, failure);
            closeSuppressing(createdSampler, failure);
            closeSuppressing(createdResolveQuad, failure);
            closeSuppressing(createdResolve, failure);
            closeSuppressing(createdSurface, failure);
            throw failure;
        }
    }

    /**
     * Records the per-frame clear, instance upload and weighted draw.
     *
     * @param cmd        command buffer that will execute on the GPU owner thread
     * @param surfaces   visible transparent quads in any order; at most
     *                   {@link WeightedOitSettings#maximumSurfaces()}
     * @param view       view matrix that produced the caller's projection
     * @param projection projection used for the target viewport
     * @param settings   current settings; disabled values record nothing
     */
    public void record(CommandBuffer cmd, List<OitSurface> surfaces, Matrix4fc view,
                       Matrix4fc projection, WeightedOitSettings settings) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(surfaces, "surfaces");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            accumulated = false;
            return;
        }
        if (!view.isFinite() || !projection.isFinite()) {
            throw new IllegalArgumentException("weighted OIT view/projection must be finite");
        }
        if (settings.maximumSurfaces() != maximumSurfaces) {
            throw new IllegalArgumentException(
                    "maximumSurfaces changed; create a new WeightedOitPass");
        }
        if (surfaces.size() > maximumSurfaces) {
            throw new IllegalArgumentException("surface count " + surfaces.size()
                    + " exceeds the pass capacity " + maximumSurfaces);
        }
        for (OitSurface surface : surfaces) {
            Objects.requireNonNull(surface, "surface");
        }

        cmd.bindFramebuffer(framebuffer)
                .viewport(0, 0, width, height)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .clear(true, false);
        if (surfaces.isEmpty()) {
            accumulated = true;
            return;
        }

        instanceData.clear();
        for (OitSurface surface : surfaces) {
            instanceData.putFloat(surface.centerX()).putFloat(surface.centerY())
                    .putFloat(surface.centerZ())
                    .putFloat(surface.halfRightX()).putFloat(surface.halfRightY())
                    .putFloat(surface.halfRightZ())
                    .putFloat(surface.halfUpX()).putFloat(surface.halfUpY())
                    .putFloat(surface.halfUpZ())
                    .putFloat(surface.red()).putFloat(surface.green())
                    .putFloat(surface.blue()).putFloat(surface.alpha());
        }
        instanceData.flip();

        cmd.uploadBufferRegion(instanceBuffer, 0L, instanceData)
                .bindShader(surfaceProgram)
                .setUniformMat4(surfaceProgram, "uView", new Matrix4f(view))
                .setUniformMat4(surfaceProgram, "uProjection", new Matrix4f(projection))
                .setUniformInt(surfaceProgram, "uDepthWeight", settings.depthWeighting() ? 1 : 0)
                .setUniformFloat(surfaceProgram, "uDepthScale", settings.depthScale())
                .setUniformFloat(surfaceProgram, "uReferenceDepth", settings.referenceDepth())
                .setUniformFloat(surfaceProgram, "uMinimumWeight", settings.minimumWeight())
                .setUniformFloat(surfaceProgram, "uMaximumWeight", settings.maximumWeight())
                .setUniformFloat(surfaceProgram, "uAlphaThreshold", settings.alphaThreshold())
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .blendEquation(org.lwjgl.opengl.GL14.GL_FUNC_ADD)
                .blendFuncSeparate(ACCUMULATION_BLEND_SOURCE_RGB,
                        ACCUMULATION_BLEND_DESTINATION_RGB,
                        REVEALAGE_BLEND_SOURCE_ALPHA,
                        REVEALAGE_BLEND_DESTINATION_ALPHA)
                .enableBlend(true)
                .bindMesh(unitQuad)
                .drawMeshInstanced(unitQuad, surfaces.size())
                .enableBlend(false)
                .depthMask(true)
                .enableDepthTest(true);

        accumulated = true;
    }

    /**
     * Records the full-screen resolve into the currently bound caller target.
     *
     * <p>The caller owns the target and its viewport; this method does not bind
     * a framebuffer. The resolve blends the weighted average over the existing
     * target colour and leaves blending disabled afterwards. It may be recorded
     * into a different command buffer than {@link #record} as long as the graph
     * submits the accumulation first; a resolve without a preceding record is
     * rejected.</p>
     *
     * @param cmd      command buffer that records the resolve draw
     * @param settings current settings; disabled values record nothing
     */
    public void resolve(CommandBuffer cmd, WeightedOitSettings settings) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            accumulated = false;
            return;
        }
        if (!accumulated) {
            throw new GlException(
                    "weighted OIT resolve requires a preceding record in submission order");
        }

        cmd.bindShader(resolveProgram)
                .bindTexture(ACCUMULATION_TEXTURE_UNIT, framebuffer.colorAttachment(0))
                .bindSampler(ACCUMULATION_TEXTURE_UNIT, nearestSampler)
                .bindTexture(WEIGHT_TEXTURE_UNIT, framebuffer.colorAttachment(1))
                .bindSampler(WEIGHT_TEXTURE_UNIT, nearestSampler)
                .setUniformInt(resolveProgram, "uAccumulation", ACCUMULATION_TEXTURE_UNIT)
                .setUniformInt(resolveProgram, "uWeight", WEIGHT_TEXTURE_UNIT)
                .setUniformFloat(resolveProgram, "uWeightEpsilon", RESOLVE_WEIGHT_EPSILON)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .blendEquation(org.lwjgl.opengl.GL14.GL_FUNC_ADD)
                .blendFunc(RESOLVE_BLEND_SOURCE, RESOLVE_BLEND_DESTINATION)
                .enableBlend(true)
                .bindVertexArray(resolveQuad.id())
                .drawArrays(GL_TRIANGLES, 0, RESOLVE_VERTEX_COUNT)
                .enableBlend(false)
                .depthMask(true)
                .enableDepthTest(true);

        accumulated = false;
    }

    /**
     * Reallocates both attachments for a new extent at a frame boundary.
     *
     * <p>The candidate framebuffer is fully created and checked before the old
     * one is retired. A creation failure leaves the active framebuffer, extent
     * and pending record state untouched. A failure while closing the retired
     * framebuffer keeps the new extent active and is reported to the caller;
     * this is a retirement diagnostic, not a rollback.</p>
     */
    public void resize(int newWidth, int newHeight) {
        ensureOpen();
        if (newWidth <= 0 || newHeight <= 0) {
            throw new IllegalArgumentException("weighted OIT extent must be positive");
        }
        if (newWidth == width && newHeight == height) {
            return;
        }
        Framebuffer candidate = createFramebuffer(newWidth, newHeight);
        Framebuffer retired = framebuffer;
        framebuffer = candidate;
        width = newWidth;
        height = newHeight;
        // A recorded but unresolved frame cannot be paired with the new
        // attachments; the next resolve must fail instead of sampling them.
        accumulated = false;

        RuntimeException retirementFailure = null;
        try {
            retired.close();
        } catch (RuntimeException failure) {
            retirementFailure = failure;
        }
        if (retirementFailure != null) {
            throw retirementFailure;
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int maximumSurfaces() {
        return maximumSurfaces;
    }

    @Override
    public int id() {
        return surfaceProgram.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        accumulated = false;
        RuntimeException failure = null;
        failure = closeSuppressed(framebuffer, failure);
        failure = closeSuppressed(instanceBuffer, failure);
        failure = closeSuppressed(unitQuad, failure);
        failure = closeSuppressed(nearestSampler, failure);
        failure = closeSuppressed(resolveQuad, failure);
        failure = closeSuppressed(resolveProgram, failure);
        failure = closeSuppressed(surfaceProgram, failure);
        if (failure != null) {
            throw failure;
        }
    }

    /** Package-private framebuffer id for the real-GL readback contract. */
    int framebufferId() {
        ensureOpen();
        return framebuffer.id();
    }

    /** Package-private attachment 0 id for the real-GL readback contract. */
    int accumulationTextureId() {
        ensureOpen();
        return framebuffer.colorAttachment(0);
    }

    /** Package-private attachment 1 id for the real-GL readback contract. */
    int weightTextureId() {
        ensureOpen();
        return framebuffer.colorAttachment(1);
    }

    private static Framebuffer createFramebuffer(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(RenderFormat.RGBA16F)
                .colorTexture(RenderFormat.R16F)
                .build());
    }

    private static Mesh createUnitQuad() {
        VertexAttribute corner = VertexAttribute.builder()
                .index(0).size(2).type(GL_FLOAT).offsetBytes(0L)
                .semantic(VertexSemantic.POSITION).build();
        return Mesh.builder()
                .vertices(new float[]{
                        -1.0f, -1.0f,
                        1.0f, -1.0f,
                        1.0f, 1.0f,
                        -1.0f, 1.0f
                }, 2 * Float.BYTES, corner)
                .indices(new int[]{0, 1, 2, 0, 2, 3})
                .build();
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("weighted OIT pass is closed");
        }
    }

    private static RuntimeException closeSuppressed(AutoCloseable resource, RuntimeException primary) {
        if (resource == null) {
            return primary;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            if (primary == null) {
                return closeFailure instanceof RuntimeException runtimeFailure
                        ? runtimeFailure : new GlException("resource close failed", closeFailure);
            }
            primary.addSuppressed(closeFailure);
        }
        return primary;
    }

    private static void closeSuppressing(AutoCloseable resource, Throwable primary) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
