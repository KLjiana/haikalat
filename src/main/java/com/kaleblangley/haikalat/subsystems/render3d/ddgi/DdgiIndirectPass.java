package com.kaleblangley.haikalat.subsystems.render3d.ddgi;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalFrameState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

import static org.lwjgl.opengl.GL30.GL_RGBA16F;

/**
 * Reusable producer of the bounded screen-space DDGI indirect diffuse
 * contribution, and the real production consumer of the
 * {@link DdgiProbeVolume} query.
 *
 * <p>The pass owns one full-size single-sample RGBA16F colour target, one
 * shader pair and one screen mesh; it borrows the probe volume, the caller's
 * scene colour, the shared scene depth and the shared octahedral world-normal
 * texture. Recording first copies the caller's colour into the pass target,
 * then adds the probe contribution with {@code GL_ONE/GL_ONE}. It never samples
 * a target it is writing and never writes the shared depth.</p>
 *
 * <p>The probe query shares trilinear probe weights, a normal-side gate and
 * the grid border fade with the relight stage. The camera resolve filters four
 * visibility comparisons per contributing probe using the receiver tangent
 * plane. Receivers outside the grid or occluded in all contributing taps
 * receive zero indirect light.</p>
 *
 * <p>Disabled DDGI settings cannot create a volume, so this pass has no
 * disabled instance and no no-op draw. The constructor prepares the complete
 * candidate before publishing it and closes candidate resources on failure.
 * {@link #resize(int, int)} allocates the complete candidate target before
 * publishing it, so an allocation failure keeps the previous target active.
 * {@link #close()} is idempotent, aggregates close failures and never closes
 * the borrowed volume.</p>
 */
public final class DdgiIndirectPass implements GlResource {
    /** RenderGraph pass name used by integration. */
    public static final String PASS_NAME = "DdgiIndirectPass";
    /** Full-size colour target holding scene colour plus indirect light. */
    public static final String COLOR = "ddgiIndirectColor";
    /** Fragment texture unit of the shared scene depth. */
    public static final int DEPTH_UNIT = 0;
    /** Fragment texture unit of the shared octahedral world normal. */
    public static final int NORMAL_UNIT = 1;
    /** Fragment texture unit of the current probe irradiance atlas. */
    public static final int IRRADIANCE_UNIT = 2;
    /** Fragment texture unit of the probe depth atlas (visibility gate). */
    public static final int PROBE_DEPTH_UNIT = 3;
    /** Largest accepted colour target edge in pixels. */
    public static final int MAXIMUM_EXTENT = 16384;

    private final DdgiProbeVolume volume;
    private final DdgiSettings settings;
    private final ShaderProgram shader;
    private final Mesh screen;
    private final Matrix4f inverseView = new Matrix4f();
    private final Vector3f gridOrigin = new Vector3f();

    private Framebuffer target;
    private int width;
    private int height;
    private boolean closed;

    /**
     * Creates an active indirect pass and allocates its colour target.
     *
     * @param volume borrowed probe volume, must outlive this pass
     * @param width  initial colour target width in pixels
     * @param height initial colour target height in pixels
     * @throws IllegalArgumentException when extents are invalid
     */
    public DdgiIndirectPass(DdgiProbeVolume volume, int width, int height) {
        this.volume = Objects.requireNonNull(volume, "volume");
        volume.ensureOpen();
        this.settings = volume.settings();
        if (!settings.active()) {
            throw new IllegalArgumentException("DDGI is disabled");
        }
        validateExtent(width, height);
        ShaderProgram createdShader = null;
        Mesh createdScreen = null;
        Framebuffer createdTarget = null;
        try {
            createdShader = ShaderProgram.builder()
                    .resource(DdgiIndirectPass.class, ShaderStage.VERTEX,
                            DdgiShaderSource.ROOT + "ddgi-fullscreen.vert")
                    .stage(ShaderStage.FRAGMENT,
                            DdgiShaderSource.fragmentSource("ddgi-indirect.frag"),
                            "ddgi-indirect.frag")
                    .link();
            createdScreen = screenMesh();
            createdTarget = createTarget(width, height);
            shader = createdShader;
            screen = createdScreen;
            target = createdTarget;
            this.width = width;
            this.height = height;
        } catch (RuntimeException | Error failure) {
            closeSuppressed(createdTarget, failure);
            closeSuppressed(createdScreen, failure);
            closeSuppressed(createdShader, failure);
            throw failure;
        }
    }

    /** @return the borrowed probe volume */
    public DdgiProbeVolume volume() {
        return volume;
    }

    public DdgiSettings settings() {
        return settings;
    }

    /** @return current colour target width in pixels, or 0 after close */
    public int width() {
        return closed ? 0 : width;
    }

    /** @return current colour target height in pixels, or 0 after close */
    public int height() {
        return closed ? 0 : height;
    }

    /** @return sampled RGBA16F colour texture id, or 0 after close */
    public int colorTexture() {
        return closed || target == null ? 0 : target.colorAttachment();
    }

    /**
     * Records the scene-colour copy and the additive indirect-light draw into
     * this pass's own target.
     *
     * @param cmd              GPU-owner command buffer
     * @param colorSource      framebuffer holding the resolved scene colour
     * @param sceneDepthTexture single-sample shared scene depth
     * @param sceneNormalTexture shared world normal in signed octahedral coordinates
     * @param frame            current frame matrices and extent
     */
    public void record(CommandBuffer cmd, Framebuffer colorSource, int sceneDepthTexture,
                       int sceneNormalTexture, TemporalFrameState.FrameParameters frame) {
        ensureOpen();
        volume.ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(colorSource, "colorSource");
        Objects.requireNonNull(frame, "frame");
        colorSource.ensureOpen();
        if (colorSource == target) {
            throw new IllegalArgumentException(
                    "DDGI indirect colour source must differ from the pass target");
        }
        if (colorSource.width() != width || colorSource.height() != height) {
            throw new IllegalArgumentException("DDGI indirect colour source extent must match the pass target");
        }
        if (frame.width() != width || frame.height() != height) {
            throw new IllegalArgumentException("DDGI indirect frame extent must match the pass target");
        }
        if (sceneDepthTexture <= 0) {
            throw new IllegalArgumentException("DDGI indirect pass requires the shared scene depth texture");
        }
        if (sceneNormalTexture <= 0) {
            throw new IllegalArgumentException("DDGI indirect pass requires the shared scene normal texture");
        }
        int irradiance = volume.irradianceTexture();
        int probeDepth = volume.probeDepthTexture();
        if (irradiance <= 0 || probeDepth <= 0) {
            throw new GlException("DDGI indirect pass requires an open probe volume");
        }

        cmd.blitColor(colorSource, target)
                .bindFramebuffer(target)
                .viewport(0, 0, width, height)
                .bindShader(shader)
                .bindTexture(DEPTH_UNIT, sceneDepthTexture)
                .bindTexture(NORMAL_UNIT, sceneNormalTexture)
                .bindTexture(IRRADIANCE_UNIT, irradiance)
                .bindTexture(PROBE_DEPTH_UNIT, probeDepth)
                .setUniformInt(shader, "uSceneDepth", DEPTH_UNIT)
                .setUniformInt(shader, "uSceneNormal", NORMAL_UNIT)
                .setUniformInt(shader, "uIrradianceAtlas", IRRADIANCE_UNIT)
                .setUniformInt(shader, "uProbeDepthAtlas", PROBE_DEPTH_UNIT)
                .setUniformMat4(shader, "uInverseProjection",
                        frame.inverseJitteredProjection())
                .setUniformMat4(shader, "uInverseView", inverseView.set(frame.view()).invert())
                .setUniformVec2(shader, "uViewportSize", width, height)
                .setUniformFloat(shader, "uIntensity", settings.intensity())
                .setUniformFloat(shader, "uSurfaceAlbedo", settings.surfaceAlbedo())
                .setUniformInt(shader, "uProbesX", settings.probesX())
                .setUniformInt(shader, "uProbesY", settings.probesY())
                .setUniformInt(shader, "uProbesZ", settings.probesZ())
                .setUniformVec3(shader, "uGridOrigin",
                        gridOrigin.set(settings.gridOriginX(), settings.gridOriginY(),
                                settings.gridOriginZ()))
                .setUniformFloat(shader, "uSpacing", settings.spacingMetres())
                .setUniformFloat(shader, "uBorderFadeMetres", settings.borderFadeMetres())
                .setUniformInt(shader, "uDepthFaceResolution",
                        settings.depthFaceResolution())
                .setUniformFloat(shader, "uDepthNear", volume.probeDepthNear())
                .setUniformFloat(shader, "uDepthFar", volume.probeDepthFar())
                .setUniformInt(shader, "uIrradianceFaceResolution",
                        DdgiSettings.IRRADIANCE_FACE_RESOLUTION)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                // A caller-left scissor must not clip the full-screen indirect add.
                .enableScissor(false)
                .enableBlend(true)
                .blendEquation(org.lwjgl.opengl.GL14.GL_FUNC_ADD)
                .blendFunc(org.lwjgl.opengl.GL11.GL_ONE, org.lwjgl.opengl.GL11.GL_ONE)
                .bindMesh(screen)
                .drawMesh(screen);
        cmd.enableBlend(false)
                .depthMask(true)
                .enableDepthTest(true)
                .enableCullFace(true)
                .enableScissor(false);
    }

    /**
     * Explicitly resizes the colour target. The candidate is fully allocated
     * before the old target is closed; an allocation failure keeps the previous
     * target active.
     */
    public void resize(int newWidth, int newHeight) {
        ensureOpen();
        validateExtent(newWidth, newHeight);
        if (newWidth == width && newHeight == height) {
            return;
        }
        Framebuffer candidate = createTarget(newWidth, newHeight);
        Framebuffer previous = target;
        target = candidate;
        width = newWidth;
        height = newHeight;
        if (previous != null) {
            previous.close();
        }
    }

    /**
     * Borrowed view of the pass-owned colour target intended for the next
     * colour consumer. The caller must not close it or mutate its attachments.
     *
     * @throws GlException when the pass is closed
     */
    public Framebuffer colorTarget() {
        ensureOpen();
        return target;
    }

    @Override
    public int id() {
        return colorTexture();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public void ensureOpen() {
        if (closed) throw new GlException("DDGI indirect pass is closed");
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Framebuffer previous = target;
        target = null;
        width = 0;
        height = 0;
        RuntimeException failure = null;
        failure = close(previous, failure);
        failure = close(screen, failure);
        failure = close(shader, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static Framebuffer createTarget(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(GL_RGBA16F)
                .build());
    }

    private static Mesh screenMesh() {
        VertexAttribute position = VertexAttribute.builder()
                .index(0).size(3).offsetBytes(0L)
                .semantic(VertexSemantic.POSITION).build();
        return Mesh.builder()
                .vertices(SCREEN_POSITIONS, 3 * Float.BYTES, position)
                .indices(SCREEN_INDICES)
                .build();
    }

    private static void validateExtent(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAXIMUM_EXTENT || height > MAXIMUM_EXTENT) {
            throw new IllegalArgumentException("DDGI indirect target extent must be in [1, "
                    + MAXIMUM_EXTENT + "]");
        }
    }

    private static RuntimeException close(GlResource resource, RuntimeException failure) {
        if (resource == null) return failure;
        try {
            resource.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) return closeFailure;
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private static void closeSuppressed(GlResource resource, Throwable primary) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (RuntimeException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    /** Full-screen quad in clip space; the vertex shader only reads position. */
    private static final float[] SCREEN_POSITIONS = {
            -1.0f, -1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            1.0f, 1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f
    };

    private static final int[] SCREEN_INDICES = {0, 1, 2, 0, 2, 3};
}
