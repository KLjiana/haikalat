package com.kaleblangley.haikalat.subsystems.render3d.caustics;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalFrameState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL14.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;

/**
 * Producer of the first bounded water-caustics colour contribution.
 *
 * <p>The pass owns one full-size single-sample RGBA16F colour target, one shader
 * pair, one screen mesh and, optionally, one pattern texture. It borrows the
 * caller's scene colour and the shared scene depth; it never writes the shared
 * depth and never samples a target it is writing. Recording first resolves the
 * caller's colour into the pass target, then draws a full-screen additive
 * caustics contribution. The draw disables depth test and depth writes, so the
 * depth attachment (if the caller's chain has one) is untouched.</p>
 *
 * <p>Each fragment reconstructs its world position from the shared scene depth
 * and lights it only when it is a valid receiver: just below the water plane,
 * inside the finite water rectangle projected along the main light, inside the
 * configured receiver height band, within the maximum light-path distance and
 * facing up enough to be a floor rather than a wall. This is a deliberate
 * screen-space artistic approximation; it does not perform photon transport and
 * cannot detect an occluder that exists only between the water and the receiver
 * but is hidden from the camera depth buffer.</p>
 *
 * <p>{@link CausticsSettings#disabled()} is rejected by the constructor: while
 * disabled no pass, target, shader, texture or draw exists. {@link #resize(int,
 * int)} allocates the complete candidate target before publishing it, so an
 * allocation failure keeps the previous target active. {@link #close()} is
 * idempotent and aggregates close failures.</p>
 */
public final class CausticsPass implements GlResource {
    /** RenderGraph pass name used by integration. */
    public static final String PASS_NAME = "CausticsPass";
    /** Full-size colour target holding scene colour plus the caustics contribution. */
    public static final String COLOR = "causticsColor";
    /** Fragment texture unit for the shared scene depth. */
    public static final int DEPTH_UNIT = 0;
    /** Fragment texture unit for the optional pattern texture. */
    public static final int PATTERN_UNIT = 1;
    /** Largest accepted colour target edge in pixels. */
    public static final int MAXIMUM_EXTENT = 16384;
    /** Smallest accepted absolute light direction Y against the receiver. */
    public static final float MINIMUM_LIGHT_DOWNWARD_Y = 1.0e-3f;

    private final CausticsSettings settings;
    private final int samples;
    private final ShaderProgram shader;
    private final Mesh screen;
    private final Texture2D patternTexture;
    private final Matrix4f inverseView = new Matrix4f();
    private final Vector3f light = new Vector3f();
    private final Vector3f color = new Vector3f();

    private Framebuffer target;
    private int width;
    private int height;
    private boolean closed;

    /**
     * Creates an active caustics pass and allocates its colour target.
     *
     * @param settings active bounded configuration
     * @param samples  geometry sample count; {@code > 1} selects the sampler2DMS depth variant
     * @param width    initial colour target width in pixels
     * @param height   initial colour target height in pixels
     * @throws IllegalArgumentException when settings are disabled or extents invalid
     */
    public CausticsPass(CausticsSettings settings, int samples, int width, int height) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("caustics are disabled");
        }
        if (samples < 1) {
            throw new IllegalArgumentException("caustics sample count must be positive");
        }
        validateExtent(width, height);
        this.samples = samples;
        ShaderProgram createdShader = null;
        Mesh createdScreen = null;
        Texture2D createdPattern = null;
        Framebuffer createdTarget = null;
        try {
            createdShader = ShaderProgram.builder()
                    .resource(CausticsPass.class, ShaderStage.VERTEX,
                            "/shaders/render3d/caustics/caustics.vert")
                    .stage(ShaderStage.FRAGMENT, fragmentSource(samples > 1),
                            "/shaders/render3d/caustics/caustics.frag")
                    .link();
            createdScreen = screenMesh();
            if (settings.hasPatternTexture()) {
                createdPattern = Texture2D.fromResource(CausticsPass.class,
                        settings.patternTexture(), true, TextureColorSpace.LINEAR);
            }
            createdTarget = createTarget(width, height);
            shader = createdShader;
            screen = createdScreen;
            patternTexture = createdPattern;
            target = createdTarget;
            this.width = width;
            this.height = height;
        } catch (RuntimeException failure) {
            closeSuppressed(createdTarget, failure);
            closeSuppressed(createdPattern, failure);
            closeSuppressed(createdScreen, failure);
            closeSuppressed(createdShader, failure);
            throw failure;
        }
    }

    public CausticsSettings settings() {
        return settings;
    }

    public int samples() {
        return samples;
    }

    /** @return current target width in pixels, or 0 after close */
    public int width() {
        return closed ? 0 : width;
    }

    /** @return current target height in pixels, or 0 after close */
    public int height() {
        return closed ? 0 : height;
    }

    /** @return sampled RGBA16F colour texture id, or 0 after close */
    public int colorTexture() {
        return closed || target == null ? 0 : target.colorAttachment();
    }

    /**
     * Records the scene-colour copy and the additive caustics draw into this
     * pass's own target.
     *
     * @param cmd              GPU-owner command buffer
     * @param colorSource      framebuffer holding the opaque/resolved scene colour
     * @param sceneDepthTexture shared scene depth matching {@link #samples()}
     * @param frame            current frame matrices and extent
     * @param lightDirection   direction the main light travels (world space, non-zero, y negative)
     * @param timeSeconds      accumulated scene time driving the deterministic pattern
     */
    public void record(CommandBuffer cmd, Framebuffer colorSource, int sceneDepthTexture,
                       TemporalFrameState.FrameParameters frame, Vector3f lightDirection,
                       float timeSeconds) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(colorSource, "colorSource");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(lightDirection, "lightDirection");
        colorSource.ensureOpen();
        if (colorSource == target) {
            throw new IllegalArgumentException(
                    "caustics colour source must differ from the caustics target");
        }
        if (colorSource.width() != width || colorSource.height() != height) {
            throw new IllegalArgumentException("caustics colour source extent must match the pass target");
        }
        if (frame.width() != width || frame.height() != height) {
            throw new IllegalArgumentException("caustics frame extent must match the pass target");
        }
        if (sceneDepthTexture <= 0) {
            throw new IllegalArgumentException("caustics require the shared scene depth texture");
        }
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            throw new IllegalArgumentException("caustics time must be finite and non-negative");
        }
        normalizeLight(lightDirection);

        cmd.blitColor(colorSource, target)
                .bindFramebuffer(target)
                .viewport(0, 0, width, height);

        settings.colorTo(color);
        inverseView.set(frame.view()).invert();
        cmd.bindShader(shader)
                .setUniformInt(shader, "uSceneDepth", DEPTH_UNIT)
                .setUniformVec2(shader, "uViewportSize", width, height)
                .setUniformMat4(shader, "uInverseProjection",
                        frame.inverseJitteredProjection())
                .setUniformMat4(shader, "uInverseView", inverseView)
                .setUniformVec3(shader, "uLightDirection", light)
                .setUniformFloat(shader, "uTimeSeconds", timeSeconds)
                .setUniformFloat(shader, "uIntensity", settings.intensity())
                .setUniformVec3(shader, "uColor", color)
                .setUniformFloat(shader, "uWorldScale", settings.worldScaleMetres())
                .setUniformFloat(shader, "uAnimationSpeed", settings.animationSpeed())
                .setUniformFloat(shader, "uWaterLevel", settings.waterLevel())
                .setUniformFloat(shader, "uWaterMinX", settings.waterMinX())
                .setUniformFloat(shader, "uWaterMinZ", settings.waterMinZ())
                .setUniformFloat(shader, "uWaterMaxX", settings.waterMaxX())
                .setUniformFloat(shader, "uWaterMaxZ", settings.waterMaxZ())
                .setUniformFloat(shader, "uReceiverMinimumHeight",
                        settings.receiverMinimumHeight())
                .setUniformFloat(shader, "uReceiverMaximumHeight",
                        settings.receiverMaximumHeight())
                .setUniformFloat(shader, "uDepthBias", settings.depthBias())
                .setUniformFloat(shader, "uMaximumProjectionDistance",
                        settings.maximumProjectionDistance())
                .setUniformFloat(shader, "uDistanceFalloff", settings.distanceFalloff())
                .setUniformFloat(shader, "uEdgeFadeDistance", settings.edgeFadeDistance())
                .setUniformFloat(shader, "uMinimumReceiverUpDot",
                        settings.minimumReceiverUpDot())
                .setUniformInt(shader, "uHasPatternTexture", patternTexture != null ? 1 : 0);

        if (samples > 1) {
            cmd.bindTextureMultisample(DEPTH_UNIT, sceneDepthTexture);
        } else {
            cmd.bindTexture(DEPTH_UNIT, sceneDepthTexture);
        }
        if (patternTexture != null) {
            cmd.bindTexture(PATTERN_UNIT, patternTexture)
                    .setUniformInt(shader, "uPatternTexture", PATTERN_UNIT);
        } else {
            cmd.setUniformInt(shader, "uPatternTexture", 0);
        }

        cmd.enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .enableBlend(true)
                .blendEquation(GL_FUNC_ADD)
                .blendFunc(GL_ONE, GL_ONE)
                .bindMesh(screen)
                .drawMesh(screen);
        // Pending state is applied at the next observable GPU boundary.  Force
        // one so the documented restored depth mask/test and blend state are
        // visible to a caller that records a raw clear or another draw after
        // this pass on the same command buffer.
        cmd.enableBlend(false).depthMask(true).enableDepthTest(true)
                .blendEquation(GL_FUNC_ADD);
    }

    /**
     * Explicitly resizes the colour target. The candidate is fully allocated
     * before the old target is closed; an allocation failure keeps the previous
     * target active. The caller is responsible for invoking this before
     * recording at a new extent.
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
     * Borrowed view of the pass-owned colour target, intended as the scene
     * colour source of the next colour consumer (for example the water surface
     * pass). The caller must not close it, resize it, bind it as a render target
     * for an unrelated draw, or mutate its attachments; ownership stays with
     * this pass and is released by {@link #close()}.
     *
     * @throws GlException when the pass is closed
     */
    public Framebuffer colorTarget() {
        ensureOpen();
        return target;
    }

    @Override
    public int id() {
        return closed || target == null ? 0 : target.id();
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
        Framebuffer previous = target;
        target = null;
        width = 0;
        height = 0;
        RuntimeException failure = null;
        failure = close(previous, failure);
        failure = close(patternTexture, failure);
        failure = close(screen, failure);
        failure = close(shader, failure);
        if (failure != null) {
            throw failure;
        }
    }

    static String fragmentSource(boolean multisample) {
        try (var input = CausticsPass.class.getResourceAsStream(
                "/shaders/render3d/caustics/caustics.frag")) {
            if (input == null) throw new GlException("missing caustics shader");
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (!source.startsWith("#version")) {
                throw new GlException("missing GLSL version in caustics shader");
            }
            if (!multisample) return source;
            int newline = source.indexOf('\n');
            return source.substring(0, newline + 1) + "#define CAUSTICS_MSAA\n"
                    + source.substring(newline + 1);
        } catch (java.io.IOException failure) {
            throw new GlException("cannot read caustics shader", failure);
        }
    }

    private void normalizeLight(Vector3f direction) {
        float lengthSquared = direction.lengthSquared();
        if (!Float.isFinite(lengthSquared) || !(lengthSquared > 1.0e-12f)) {
            throw new IllegalArgumentException("caustics light direction must be finite and non-zero");
        }
        light.set(direction).mul(1.0f / (float) Math.sqrt(lengthSquared));
        if (!(light.y < -MINIMUM_LIGHT_DOWNWARD_Y)) {
            throw new IllegalArgumentException(
                    "caustics require a downward main light direction");
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
            throw new IllegalArgumentException("caustics target extent must be in [1, "
                    + MAXIMUM_EXTENT + "]");
        }
    }

    private void ensureOpen() {
        if (closed) throw new GlException("caustics pass is closed");
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

    private static void closeSuppressed(GlResource resource, RuntimeException primary) {
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
