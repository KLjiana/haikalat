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
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL14.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;

/**
 * Stochastic transparency producer and temporal accumulator (S4 #37).
 *
 * <p>The pass consumes the same bounded world-space quad collection as
 * {@link WeightedOitPass} ({@link OitSurface}), so a host can move a selected
 * particle/smoke set between sorted alpha, weighted OIT and stochastic
 * transparency without changing the collection representation.  Every frame the
 * pass rasterises the selected surfaces {@code samplesPerFrame} times.  A
 * fragment is accepted with probability {@code p(alpha)} (see
 * {@link StochasticTransparencySettings#acceptanceProbability(double)}) and
 * writes full opacity into a private RGBA16F sample target that owns a private
 * depth attachment.  The sample is rasterised with depth test/write and
 * <em>no</em> blending, so the nearest accepted surface replaces every farther
 * one and the sample is draw-order independent.  A full-screen pass then adds
 * that sample to a separate RGBA32F frame summation (colour sum + hit count).
 * The temporal resolve divides by the sample count, optionally reprojects and
 * blends bounded history, and writes premultiplied colour + coverage plus
 * coverage moments.  A final full-screen composite blends the premultiplied
 * estimate over the caller's bound HDR target with
 * {@code GL_ONE / GL_ONE_MINUS_SRC_ALPHA}.</p>
 *
 * <h2>Frame contract</h2>
 *
 * <ol>
 *   <li>{@link #record} binds and clears the private sample framebuffer, draws
 *       the stochastic samples and resolves into the private write history slot.
 *       It leaves that private framebuffer bound and never binds or writes the
 *       caller target or the shared scene depth.</li>
 *   <li>The caller binds its HDR target and viewport, then calls
 *       {@link #resolve} to composite the result over it.</li>
 *   <li>The caller executes the recorded command buffer(s) and then calls
 *       {@link #frameSucceeded()} or {@link #frameFailed()}.  Only a successful
 *       commit advances the reprojection pose and the history length; a failed
 *       frame keeps the previously committed history slot.</li>
 * </ol>
 *
 * <p>{@link #invalidate()} is the explicit camera-cut/discontinuity entry point;
 * a sufficiently large camera change detected in {@link #record} also triggers
 * the same conservative reset.  {@code record} and {@code resolve} are exact
 * no-ops while {@link StochasticTransparencySettings#active()} is false, so a
 * disabled feature records zero GPU work and never advances history.</p>
 *
 * <h2>Attachments</h2>
 *
 * <p>Sample framebuffer: RGBA16F colour + DEPTH_COMPONENT24 private depth.
 * Frame summation: RGBA32F (rgb = summed winner colour, a = accepted sample
 * count).  Each history slot: RGBA16F resolved colour (premultiplied rgb,
 * coverage a) and RGBA32F state ({@code r = coverage mean}, {@code g = coverage
 * second moment}, {@code b = positive view depth}, {@code a = valid flag}).
 * There is no shared-depth attachment; the borrowed scene depth is only sampled
 * by the resolve program for reprojection and depth rejection.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Construction requires active settings and rejects a disabled value before
 * creating any GL resource.  {@link #resize(int, int)} prepares a complete
 * candidate allocation before committing it and invalidates history; a creation
 * failure leaves the active extent, attachments and history untouched, while a
 * retirement failure keeps the new allocation and reports the close failure.
 * {@link #close()} is idempotent and release every owned program, framebuffer,
 * mesh, buffer and sampler.</p>
 */
public final class StochasticTransparencyPass implements GlResource {
    /** RenderGraph pass name; consumers depend on this producer for the composited colour. */
    public static final String PASS_NAME = "StochasticTransparencyPass";

    /** Resolve input unit for the RGBA32F frame summation. */
    public static final int FRAME_SUMMATION_TEXTURE_UNIT = 0;
    /** Resolve input unit for the borrowed scene depth. */
    public static final int SCENE_DEPTH_TEXTURE_UNIT = 1;
    /** Resolve input unit for the previous accumulated colour. */
    public static final int HISTORY_COLOR_TEXTURE_UNIT = 2;
    /** Resolve input unit for the previous coverage/depth state. */
    public static final int HISTORY_STATE_TEXTURE_UNIT = 3;
    /** Composite input unit for the resolved colour. */
    public static final int RESOLVED_COLOR_TEXTURE_UNIT = 0;
    /** Accumulate input unit for one depth-tested sample colour. */
    public static final int SAMPLE_COLOR_TEXTURE_UNIT = 0;

    static final int ACCUMULATION_BLEND_SOURCE = GL_ONE;
    static final int ACCUMULATION_BLEND_DESTINATION = GL_ONE;
    static final int COMPOSITE_BLEND_SOURCE = GL_ONE;
    static final int COMPOSITE_BLEND_DESTINATION = GL_ONE_MINUS_SRC_ALPHA;
    static final int SAMPLE_DEPTH_FUNCTION = GL_LESS;

    static final float CAMERA_CUT_POSITION_DISTANCE_SQUARED = 16.0f;
    static final float CAMERA_CUT_FORWARD_DOT = 0.8f;

    private static final String FAIL_RESIZE_PROPERTY =
            "haikalat.test.failStochasticTransparencyResizeAllocation";

    private static final String SHADER_ROOT = "/shaders/render3d/transparency/";
    private static final String SURFACE_VERTEX = SHADER_ROOT + "stochastic-transparency-surface.vert";
    private static final String SURFACE_FRAGMENT = SHADER_ROOT + "stochastic-transparency-surface.frag";
    private static final String ACCUMULATE_FRAGMENT = SHADER_ROOT + "stochastic-transparency-accumulate.frag";
    private static final String RESOLVE_FRAGMENT = SHADER_ROOT + "stochastic-transparency-resolve.frag";
    private static final String COMPOSITE_FRAGMENT = SHADER_ROOT + "stochastic-transparency-composite.frag";
    private static final String SCREEN_QUAD_VERTEX = "/shaders/postprocess/screen-quad.vert";

    private static final int INSTANCE_FLOATS = 13;
    private static final int INSTANCE_STRIDE_BYTES = INSTANCE_FLOATS * Float.BYTES;
    private static final int FULLSCREEN_VERTEX_COUNT = 6;

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
    private final ShaderProgram accumulateProgram;
    private final ShaderProgram resolveProgram;
    private final ShaderProgram compositeProgram;
    private final ScreenQuad fullscreenQuad;
    private final Sampler nearestSampler;
    private final Mesh unitQuad;
    private final GlBuffer instanceBuffer;
    private final ByteBuffer instanceData;
    private final int maximumSurfaces;

    private Allocation allocation;
    private int width;
    private int height;
    private int historyReadIndex;
    private int historyLength;
    private int frameSequence;
    private boolean historyValid;
    private boolean pending;
    private boolean pendingTemporal;
    private Matrix4f previousView;
    private Matrix4f previousViewProjection;
    private Matrix4f pendingView;
    private Matrix4f pendingProjection;
    private boolean closed;

    /**
     * Creates the pass and its private attachments.
     *
     * @param width    initial attachment width in pixels
     * @param height   initial attachment height in pixels
     * @param settings active settings; the quads capacity is fixed for the pass
     *                 lifetime and a capacity change requires a new pass
     */
    public StochasticTransparencyPass(int width, int height,
                                      StochasticTransparencySettings settings) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("stochastic transparency extent must be positive");
        }
        Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException(
                    "disabled StochasticTransparencySettings must not create a pass or attachments");
        }
        maximumSurfaces = settings.maximumSurfaces();
        instanceData = ByteBuffer.allocateDirect(maximumSurfaces * INSTANCE_STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());

        ShaderProgram createdSurface = null;
        ShaderProgram createdAccumulate = null;
        ShaderProgram createdResolve = null;
        ShaderProgram createdComposite = null;
        ScreenQuad createdQuad = null;
        Sampler createdSampler = null;
        Mesh createdUnitQuad = null;
        GlBuffer createdInstanceBuffer = null;
        Allocation createdAllocation = null;
        try {
            createdSurface = ShaderProgram.builder()
                    .resource(StochasticTransparencyPass.class, ShaderStage.VERTEX, SURFACE_VERTEX)
                    .resource(StochasticTransparencyPass.class, ShaderStage.FRAGMENT, SURFACE_FRAGMENT)
                    .link();
            createdAccumulate = ShaderProgram.builder()
                    .resource(StochasticTransparencyPass.class, ShaderStage.VERTEX, SCREEN_QUAD_VERTEX)
                    .resource(StochasticTransparencyPass.class, ShaderStage.FRAGMENT, ACCUMULATE_FRAGMENT)
                    .link();
            createdResolve = ShaderProgram.builder()
                    .resource(StochasticTransparencyPass.class, ShaderStage.VERTEX, SCREEN_QUAD_VERTEX)
                    .resource(StochasticTransparencyPass.class, ShaderStage.FRAGMENT, RESOLVE_FRAGMENT)
                    .link();
            createdComposite = ShaderProgram.builder()
                    .resource(StochasticTransparencyPass.class, ShaderStage.VERTEX, SCREEN_QUAD_VERTEX)
                    .resource(StochasticTransparencyPass.class, ShaderStage.FRAGMENT, COMPOSITE_FRAGMENT)
                    .link();
            createdQuad = new ScreenQuad();
            createdSampler = Sampler.create(new Sampler.Descriptor(GL_NEAREST, GL_NEAREST,
                    GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
            createdUnitQuad = createUnitQuad();
            createdInstanceBuffer = GlBuffer.arrayBuffer(GL_DYNAMIC_DRAW)
                    .allocate((long) maximumSurfaces * INSTANCE_STRIDE_BYTES);
            createdUnitQuad.bind();
            createdInstanceBuffer.bind();
            INSTANCE_LAYOUT.apply();
            createdInstanceBuffer.unbind();
            createdUnitQuad.unbind();
            createdAllocation = Allocation.create(width, height);

            surfaceProgram = createdSurface;
            accumulateProgram = createdAccumulate;
            resolveProgram = createdResolve;
            compositeProgram = createdComposite;
            fullscreenQuad = createdQuad;
            nearestSampler = createdSampler;
            unitQuad = createdUnitQuad;
            instanceBuffer = createdInstanceBuffer;
            allocation = createdAllocation;
            this.width = width;
            this.height = height;
        } catch (RuntimeException | Error failure) {
            closeSuppressing(createdAllocation, failure);
            closeSuppressing(createdInstanceBuffer, failure);
            closeSuppressing(createdUnitQuad, failure);
            closeSuppressing(createdSampler, failure);
            closeSuppressing(createdQuad, failure);
            closeSuppressing(createdComposite, failure);
            closeSuppressing(createdResolve, failure);
            closeSuppressing(createdAccumulate, failure);
            closeSuppressing(createdSurface, failure);
            throw failure;
        }
    }

    /**
     * Records one stochastic frame into the private sample/history targets.
     *
     * <p>The command buffer is executed later on the GPU owner thread.  A
     * second {@code record} before {@link #frameSucceeded()} or
     * {@link #frameFailed()} is rejected instead of silently overwriting the
     * pending candidate.</p>
     *
     * @param cmd      command buffer that will execute on the GPU owner thread
     * @param surfaces selected transparent quads in any order; at most
     *                 {@link StochasticTransparencySettings#maximumSurfaces()}
     * @param inputs   stable-raster view/projection, inverses and borrowed depth
     * @param settings current settings; disabled values record nothing
     */
    public void record(CommandBuffer cmd, List<OitSurface> surfaces,
                       StochasticTransparencyInputs inputs,
                       StochasticTransparencySettings settings) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(surfaces, "surfaces");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            pending = false;
            pendingTemporal = false;
            return;
        }
        if (pending) {
            throw new IllegalStateException(
                    "a stochastic transparency frame is already recorded");
        }
        if (settings.maximumSurfaces() != maximumSurfaces) {
            throw new IllegalArgumentException(
                    "maximumSurfaces changed; create a new StochasticTransparencyPass");
        }
        if (settings.temporalAccumulation() && !inputs.hasSceneDepth()) {
            throw new IllegalArgumentException(
                    "temporal accumulation requires a borrowed scene depth texture");
        }
        if (surfaces.size() > maximumSurfaces) {
            throw new IllegalArgumentException("surface count " + surfaces.size()
                    + " exceeds the pass capacity " + maximumSurfaces);
        }
        for (OitSurface surface : surfaces) {
            Objects.requireNonNull(surface, "surface");
        }

        if (settings.temporalAccumulation()
                && looksLikeCameraCut(previousView, inputs.view())) {
            invalidateInternal();
        }

        boolean useHistory = settings.temporalAccumulation() && historyValid
                && previousViewProjection != null;
        float historyWeight = useHistory
                ? (float) settings.historyWeightForLength(historyLength) : 0.0f;

        recordSamples(cmd, surfaces, inputs, settings);
        recordResolve(cmd, inputs, settings, useHistory, historyWeight);

        pending = true;
        pendingTemporal = settings.temporalAccumulation();
        pendingView = new Matrix4f(inputs.view());
        pendingProjection = new Matrix4f(inputs.projection());
        frameSequence = frameSequence == Integer.MAX_VALUE ? 0 : frameSequence + 1;
    }

    /**
     * Records the premultiplied composite into the currently bound caller target.
     *
     * <p>The caller owns the target, viewport and command buffer execution order.
     * This method never binds a framebuffer.  It must follow a {@link #record}
     * in submission order; resolving without a recorded frame is rejected.</p>
     *
     * @param cmd      command buffer that records the composite draw
     * @param settings current settings; disabled values record nothing
     */
    public void resolve(CommandBuffer cmd, StochasticTransparencySettings settings) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            pending = false;
            pendingTemporal = false;
            return;
        }
        if (!pending) {
            throw new GlException(
                    "stochastic transparency resolve requires a preceding record in submission order");
        }
        Framebuffer write = writeFramebuffer();
        cmd.bindShader(compositeProgram)
                .bindTexture(RESOLVED_COLOR_TEXTURE_UNIT, write.colorAttachment(0))
                .bindSampler(RESOLVED_COLOR_TEXTURE_UNIT, nearestSampler)
                .setUniformInt(compositeProgram, "uResolvedColor", RESOLVED_COLOR_TEXTURE_UNIT)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .blendEquation(GL_FUNC_ADD)
                .blendFunc(COMPOSITE_BLEND_SOURCE, COMPOSITE_BLEND_DESTINATION)
                .enableBlend(true)
                .bindVertexArray(fullscreenQuad.id())
                .drawArrays(GL_TRIANGLES, 0, FULLSCREEN_VERTEX_COUNT)
                .enableBlend(false)
                .depthMask(true)
                .enableDepthTest(true);
    }

    /**
     * Commits the recorded candidate as the new history read slot and advances
     * the reprojection pose.  A no-op when no frame was recorded.
     */
    public void frameSucceeded() {
        ensureOpen();
        if (!pending) {
            return;
        }
        historyReadIndex = 1 - historyReadIndex;
        if (pendingTemporal) {
            historyValid = true;
            historyLength = Math.min(historyLength + 1,
                    StochasticTransparencySettings.MAXIMUM_HISTORY_LENGTH);
            previousView = pendingView;
            previousViewProjection = new Matrix4f(pendingView).mul(pendingProjection);
        } else {
            historyValid = false;
            historyLength = 0;
            previousView = null;
            previousViewProjection = null;
        }
        pending = false;
        pendingTemporal = false;
        pendingView = null;
        pendingProjection = null;
    }

    /** Discards the recorded candidate and keeps the previously committed history. */
    public void frameFailed() {
        ensureOpen();
        pending = false;
        pendingTemporal = false;
        pendingView = null;
        pendingProjection = null;
    }

    /** Stops trusting history, for example after a camera cut or scene change. */
    public void invalidate() {
        ensureOpen();
        invalidateInternal();
    }

    /**
     * @return true when a temporal frame has committed and its history slot is
     *         available for reprojection
     */
    public boolean historyValid() {
        return !closed && historyValid;
    }

    /** @return number of committed temporal frames since the last invalidation */
    public int historyLength() {
        return historyLength;
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

    /**
     * Reallocates the sample and history attachments for a new extent at a frame
     * boundary.
     *
     * <p>A complete candidate allocation is created and checked before the old
     * one is retired.  Creation failure leaves the active extent, attachments
     * and history untouched.  A committed resize invalidates history and resets
     * the deterministic frame sequence.  Retirement failure keeps the new
     * allocation active and reports the close failure instead of pretending to
     * roll back.</p>
     */
    public void resize(int newWidth, int newHeight) {
        ensureOpen();
        if (newWidth <= 0 || newHeight <= 0) {
            throw new IllegalArgumentException("stochastic transparency extent must be positive");
        }
        if (newWidth == width && newHeight == height) {
            return;
        }
        if (pending) {
            throw new IllegalStateException(
                    "resize requires a committed or failed frame, not a pending record");
        }
        if (Boolean.getBoolean(FAIL_RESIZE_PROPERTY)) {
            System.clearProperty(FAIL_RESIZE_PROPERTY);
            throw new IllegalStateException("injected stochastic transparency resize failure");
        }
        Allocation candidate = Allocation.create(newWidth, newHeight);
        Allocation retired = allocation;
        allocation = candidate;
        width = newWidth;
        height = newHeight;
        invalidateInternal();

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
        pending = false;
        pendingTemporal = false;
        historyValid = false;
        Allocation ownedAllocation = allocation;
        allocation = null;
        RuntimeException failure = null;
        failure = closeSuppressed(ownedAllocation, failure);
        failure = closeSuppressed(instanceBuffer, failure);
        failure = closeSuppressed(unitQuad, failure);
        failure = closeSuppressed(nearestSampler, failure);
        failure = closeSuppressed(fullscreenQuad, failure);
        failure = closeSuppressed(compositeProgram, failure);
        failure = closeSuppressed(resolveProgram, failure);
        failure = closeSuppressed(accumulateProgram, failure);
        failure = closeSuppressed(surfaceProgram, failure);
        if (failure != null) {
            throw failure;
        }
    }

    /** Package-private framebuffer id for the real-GL readback contract. */
    int sampleFramebufferId() {
        ensureOpen();
        return allocation.sampleFramebuffer.id();
    }

    /** Package-private sample colour texture id (RGBA16F) for the real-GL contract. */
    int sampleColorTextureId() {
        ensureOpen();
        return allocation.sampleFramebuffer.colorAttachment(0);
    }

    /** Package-private frame-summation framebuffer id (RGBA32F). */
    int accumulationFramebufferId() {
        ensureOpen();
        return allocation.accumulationFramebuffer.id();
    }

    /** Package-private frame-summation texture id (RGBA32F: colour sum + hit count). */
    int accumulationTextureId() {
        ensureOpen();
        return allocation.accumulationFramebuffer.colorAttachment(0);
    }

    /** Package-private sample depth texture id for the real-GL contract. */
    int sampleDepthTextureId() {
        ensureOpen();
        return allocation.sampleFramebuffer.depthAttachment();
    }

    /** Package-private write-slot framebuffer id (this frame's resolved result). */
    int writeResolvedFramebufferId() {
        ensureOpen();
        return writeFramebuffer().id();
    }

    /** Package-private write-slot resolved colour texture id. */
    int writeResolvedColorTextureId() {
        ensureOpen();
        return writeFramebuffer().colorAttachment(0);
    }

    /** Package-private write-slot coverage/depth state texture id. */
    int writeStateTextureId() {
        ensureOpen();
        return writeFramebuffer().colorAttachment(1);
    }

    /** Package-private committed read-slot framebuffer id. */
    int readResolvedFramebufferId() {
        ensureOpen();
        return readFramebuffer().id();
    }

    /** Package-private committed read-slot resolved colour texture id. */
    int readResolvedColorTextureId() {
        ensureOpen();
        return readFramebuffer().colorAttachment(0);
    }

    /** Package-private committed read-slot coverage/depth state texture id. */
    int readStateTextureId() {
        ensureOpen();
        return readFramebuffer().colorAttachment(1);
    }

    private void recordSamples(CommandBuffer cmd, List<OitSurface> surfaces,
                               StochasticTransparencyInputs inputs,
                               StochasticTransparencySettings settings) {
        // Start the frame summation at zero.  It is a separate RGBA32F target:
        // each sample is rendered depth-tested and unblended, then added here.
        cmd.bindFramebuffer(allocation.accumulationFramebuffer)
                .viewport(0, 0, width, height)
                .enableBlend(false)
                .enableDepthTest(false)
                .depthMask(false)
                .clearColor(0.0f, 0.0f, 0.0f, 0.0f)
                .clear(true, false);
        if (surfaces.isEmpty()) {
            cmd.depthMask(true).enableDepthTest(true);
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
        cmd.uploadBufferRegion(instanceBuffer, 0L, instanceData);

        int samples = settings.samplesPerFrame();
        for (int sample = 0; sample < samples; sample++) {
            // One sample: clear the private depth and colour, then rasterise the
            // collection as opaque with depth test/write and no blending.  The
            // nearest accepted fragment replaces any farther fragment, so draw
            // order cannot affect the sample.
            cmd.bindFramebuffer(allocation.sampleFramebuffer)
                    .viewport(0, 0, width, height)
                    .clearColor(0.0f, 0.0f, 0.0f, 0.0f)
                    .depthMask(true)
                    .clear(true, true)
                    .bindShader(surfaceProgram)
                    .setUniformMat4(surfaceProgram, "uView", inputs.view())
                    .setUniformMat4(surfaceProgram, "uProjection", inputs.projection())
                    .setUniformFloat(surfaceProgram, "uAlphaThreshold",
                            settings.alphaThreshold())
                    .setUniformFloat(surfaceProgram, "uCoverageGamma",
                            settings.coverageGamma())
                    .setUniformInt(surfaceProgram, "uJitterMode",
                            settings.jitterMode().glCode())
                    .setUniformInt(surfaceProgram, "uSeed", settings.seed())
                    .setUniformInt(surfaceProgram, "uFrameIndex", frameSequence)
                    .setUniformInt(surfaceProgram, "uSampleIndex", sample)
                    .enableDepthTest(true)
                    .depthFunc(SAMPLE_DEPTH_FUNCTION)
                    .depthMask(true)
                    .enableCullFace(false)
                    .enableFramebufferSrgb(false)
                    .enableBlend(false)
                    .bindMesh(unitQuad)
                    .drawMeshInstanced(unitQuad, surfaces.size());

            // Add this sample's winner colour and hit flag to the frame sum.
            cmd.bindFramebuffer(allocation.accumulationFramebuffer)
                    .viewport(0, 0, width, height)
                    .enableDepthTest(false)
                    .depthMask(false)
                    .bindShader(accumulateProgram)
                    .bindTexture(SAMPLE_COLOR_TEXTURE_UNIT,
                            allocation.sampleFramebuffer.colorAttachment(0))
                    .bindSampler(SAMPLE_COLOR_TEXTURE_UNIT, nearestSampler)
                    .setUniformInt(accumulateProgram, "uSampleColor",
                            SAMPLE_COLOR_TEXTURE_UNIT)
                    .enableCullFace(false)
                    .enableFramebufferSrgb(false)
                    .blendEquation(GL_FUNC_ADD)
                    .blendFunc(ACCUMULATION_BLEND_SOURCE,
                            ACCUMULATION_BLEND_DESTINATION)
                    .enableBlend(true)
                    .bindVertexArray(fullscreenQuad.id())
                    .drawArrays(GL_TRIANGLES, 0, FULLSCREEN_VERTEX_COUNT)
                    .enableBlend(false);
        }
        cmd.depthMask(true).enableDepthTest(true).depthFunc(GL_LESS);
    }

    private void recordResolve(CommandBuffer cmd, StochasticTransparencyInputs inputs,
                               StochasticTransparencySettings settings, boolean useHistory,
                               float historyWeight) {
        Framebuffer write = writeFramebuffer();
        int depthTexture = inputs.hasSceneDepth()
                ? inputs.sceneDepthTexture()
                : allocation.sampleFramebuffer.depthAttachment();
        Matrix4f previousViewProjectionForShader = useHistory
                ? previousViewProjection
                : new Matrix4f(inputs.view()).mul(inputs.projection());
        cmd.bindFramebuffer(write)
                .viewport(0, 0, width, height)
                .enableBlend(false)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(resolveProgram)
                .bindTexture(FRAME_SUMMATION_TEXTURE_UNIT,
                        allocation.accumulationFramebuffer.colorAttachment(0))
                .bindTexture(SCENE_DEPTH_TEXTURE_UNIT, depthTexture)
                .bindTexture(HISTORY_COLOR_TEXTURE_UNIT, readFramebuffer().colorAttachment(0))
                .bindTexture(HISTORY_STATE_TEXTURE_UNIT, readFramebuffer().colorAttachment(1))
                .bindSampler(FRAME_SUMMATION_TEXTURE_UNIT, nearestSampler)
                .bindSampler(SCENE_DEPTH_TEXTURE_UNIT, nearestSampler)
                .bindSampler(HISTORY_COLOR_TEXTURE_UNIT, nearestSampler)
                .bindSampler(HISTORY_STATE_TEXTURE_UNIT, nearestSampler)
                .setUniformInt(resolveProgram, "uFrameSummation", FRAME_SUMMATION_TEXTURE_UNIT)
                .setUniformInt(resolveProgram, "uSceneDepth", SCENE_DEPTH_TEXTURE_UNIT)
                .setUniformInt(resolveProgram, "uHistoryColor", HISTORY_COLOR_TEXTURE_UNIT)
                .setUniformInt(resolveProgram, "uHistoryState", HISTORY_STATE_TEXTURE_UNIT)
                .setUniformVec2(resolveProgram, "uExtent", width, height)
                .setUniformInt(resolveProgram, "uSamplesPerFrame",
                        settings.samplesPerFrame())
                .setUniformInt(resolveProgram, "uHistoryValid", useHistory ? 1 : 0)
                .setUniformFloat(resolveProgram, "uHistoryWeight", historyWeight)
                .setUniformMat4(resolveProgram, "uInverseProjection",
                        inputs.inverseProjection())
                .setUniformMat4(resolveProgram, "uInverseView", inputs.inverseView())
                .setUniformMat4(resolveProgram, "uPreviousViewProjection",
                        previousViewProjectionForShader)
                .setUniformInt(resolveProgram, "uNeighborhoodRadius",
                        settings.neighborhoodClampRadius())
                .setUniformFloat(resolveProgram, "uReactiveThreshold",
                        settings.reactiveThreshold())
                .setUniformFloat(resolveProgram, "uDepthRejectionThreshold",
                        settings.depthRejectionThreshold())
                .setUniformFloat(resolveProgram, "uVelocityRejectionPixels",
                        settings.velocityRejectionPixels())
                .bindVertexArray(fullscreenQuad.id())
                .drawArrays(GL_TRIANGLES, 0, FULLSCREEN_VERTEX_COUNT)
                .depthMask(true)
                .enableDepthTest(true);
    }

    private Framebuffer readFramebuffer() {
        return allocation.history[historyReadIndex];
    }

    private Framebuffer writeFramebuffer() {
        return allocation.history[1 - historyReadIndex];
    }

    private void invalidateInternal() {
        historyValid = false;
        historyLength = 0;
        previousView = null;
        previousViewProjection = null;
        pending = false;
        pendingTemporal = false;
        pendingView = null;
        pendingProjection = null;
        frameSequence = 0;
    }

    private static boolean looksLikeCameraCut(Matrix4fc previousView, Matrix4fc currentView) {
        if (previousView == null) {
            return false;
        }
        Matrix4f previousInverse = new Matrix4f(previousView).invert();
        Matrix4f currentInverse = new Matrix4f(currentView).invert();
        if (!previousInverse.isFinite() || !currentInverse.isFinite()) {
            return true;
        }
        Vector3f previousPosition = previousInverse.getTranslation(new Vector3f());
        Vector3f currentPosition = currentInverse.getTranslation(new Vector3f());
        if (previousPosition.distanceSquared(currentPosition)
                > CAMERA_CUT_POSITION_DISTANCE_SQUARED) {
            return true;
        }
        Vector3f previousForward =
                previousInverse.transformDirection(new Vector3f(0.0f, 0.0f, -1.0f));
        Vector3f currentForward =
                currentInverse.transformDirection(new Vector3f(0.0f, 0.0f, -1.0f));
        return previousForward.dot(currentForward) < CAMERA_CUT_FORWARD_DOT;
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
            throw new GlException("stochastic transparency pass is closed");
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

    /**
     * Owned attachment set: one sample target (RGBA16F + private D24), one
     * RGBA32F frame summation and two history slots (RGBA16F colour + RGBA32F
     * state).  Creation is candidate first; a partial failure closes everything
     * created so far.
     */
    private static final class Allocation implements AutoCloseable {
        private final Framebuffer sampleFramebuffer;
        private final Framebuffer accumulationFramebuffer;
        private final Framebuffer[] history;

        private Allocation(Framebuffer sampleFramebuffer, Framebuffer accumulationFramebuffer,
                           Framebuffer[] history) {
            this.sampleFramebuffer = sampleFramebuffer;
            this.accumulationFramebuffer = accumulationFramebuffer;
            this.history = history;
        }

        private static Allocation create(int width, int height) {
            Framebuffer sample = null;
            Framebuffer accumulation = null;
            Framebuffer first = null;
            Framebuffer second = null;
            try {
                sample = Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                        .colorTexture(RenderFormat.RGBA16F)
                        .depthTexture()
                        .build());
                accumulation = Framebuffer.fromDescriptor(
                        FramebufferDescriptor.builder(width, height)
                                .colorTexture(RenderFormat.RGBA32F)
                                .build());
                first = createHistorySlot(width, height);
                second = createHistorySlot(width, height);
                return new Allocation(sample, accumulation,
                        new Framebuffer[]{first, second});
            } catch (RuntimeException | Error failure) {
                closeSuppressing(second, failure);
                closeSuppressing(first, failure);
                closeSuppressing(accumulation, failure);
                closeSuppressing(sample, failure);
                throw failure;
            }
        }

        private static Framebuffer createHistorySlot(int width, int height) {
            return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                    .colorTexture(RenderFormat.RGBA16F)
                    .colorTexture(RenderFormat.RGBA32F)
                    .build());
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            failure = closeSuppressed(history[1], failure);
            failure = closeSuppressed(history[0], failure);
            failure = closeSuppressed(accumulationFramebuffer, failure);
            failure = closeSuppressed(sampleFramebuffer, failure);
            if (failure != null) {
                throw failure;
            }
        }
    }
}
