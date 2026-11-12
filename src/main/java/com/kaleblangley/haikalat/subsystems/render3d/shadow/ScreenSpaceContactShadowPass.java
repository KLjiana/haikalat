package com.kaleblangley.haikalat.subsystems.render3d.shadow;

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

import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_COLOR;
import static org.lwjgl.opengl.GL11.GL_ZERO;
import static org.lwjgl.opengl.GL14.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;

/**
 * Production screen-space consumer of {@link ContactShadowSettings}.
 *
 * <p>The pass owns one full-size single-sample RGBA16F colour target, one
 * shader pair and one full-screen mesh. It borrows the caller's scene colour
 * and the shared single-sample scene depth; it never writes that depth and
 * never samples a target it is writing. Recording first resolves the caller's
 * colour into the pass target, then draws a multiplicative darkening
 * ({@code target *= 1 - strength * occlusion}) over it. The caller blits the
 * result back into the shared colour chain. The draw disables depth test and
 * depth writes, so the depth attachment (if any) is untouched.</p>
 *
 * <p>Each fragment reconstructs its view-space position from the camera depth
 * with {@code uInverseProjection}, derives a geometric normal with
 * {@code dFdx/dFdy}, rejects receivers facing away from the light, and marches
 * toward the light in view space with at most {@link ContactShadowSettings#MAX_STEPS}
 * taps. A tap is skipped when it is outside the raster; a receiver whose whole
 * ray leaves the screen therefore receives no occlusion (the explicit
 * off-screen fallback). An in-raster tap occludes when the sampled visible
 * surface is in front of the ray point by more than a small bias and no farther
 * than {@code thickness}. This is an artistic screen-space approximation: it
 * cannot see an occluder that is hidden from the camera depth buffer.</p>
 *
 * <p>Only a single-sample scene target is supported; the constructor rejects
 * {@code samples > 1} instead of silently producing a mismatched ray. Inactive
 * settings are rejected as well, matching {@code CausticsPass}: while disabled
 * no pass, target, shader, mesh or draw exists.</p>
 *
 * <p>The existing material-level term in {@code pbr-forward.frag} is kept as a
 * forward-material option; it consumes the directional shadow map, not the
 * camera depth. This pass is the screen-space consumer wired by
 * {@code RenderPipeline} and is the production path for #9.</p>
 */
public final class ScreenSpaceContactShadowPass implements GlResource {
    /** RenderGraph pass name used by pipeline integration. */
    public static final String PASS_NAME = "ScreenSpaceContactShadowPass";
    /** Pass-owned RGBA16F colour target holding scene colour after darkening. */
    public static final String COLOR = "screenSpaceContactShadowColor";
    /** Fragment texture unit for the shared single-sample scene depth. */
    public static final int DEPTH_UNIT = 0;
    /** Largest accepted colour target edge in pixels. */
    public static final int MAXIMUM_EXTENT = 16384;

    private final ContactShadowSettings settings;
    private final int samples;
    private final ShaderProgram shader;
    private final Mesh screen;
    private final Matrix4f view = new Matrix4f();
    private final Vector3f light = new Vector3f();

    private Framebuffer target;
    private int width;
    private int height;
    private boolean closed;

    /**
     * Creates an active screen-space contact-shadow pass and allocates its
     * colour target.
     *
     * @param settings active bounded configuration
     * @param samples  scene raster samples; only {@code 1} is supported
     * @param width    initial colour target width in pixels
     * @param height   initial colour target height in pixels
     * @throws IllegalArgumentException when settings are inactive, the sample
     *                                  count is multisampled or extents are invalid
     */
    public ScreenSpaceContactShadowPass(ContactShadowSettings settings, int samples,
                                        int width, int height) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("screen-space contact shadows are disabled");
        }
        if (samples < 1) {
            throw new IllegalArgumentException(
                    "screen-space contact-shadow sample count must be positive");
        }
        if (samples > 1) {
            throw new IllegalArgumentException(
                    "screen-space contact shadows require a single-sample scene target; "
                            + "samples=" + samples + " is unsupported");
        }
        validateExtent(width, height);
        this.samples = samples;
        ShaderProgram createdShader = null;
        Mesh createdScreen = null;
        Framebuffer createdTarget = null;
        try {
            createdShader = ShaderProgram.builder()
                    .resource(ScreenSpaceContactShadowPass.class, ShaderStage.VERTEX,
                            "/shaders/render3d/shadow/screen-space-contact-shadow.vert")
                    .resource(ScreenSpaceContactShadowPass.class, ShaderStage.FRAGMENT,
                            "/shaders/render3d/shadow/screen-space-contact-shadow.frag")
                    .link();
            createdScreen = screenMesh();
            createdTarget = createTarget(width, height);
            shader = createdShader;
            screen = createdScreen;
            target = createdTarget;
            this.width = width;
            this.height = height;
        } catch (RuntimeException failure) {
            closeSuppressed(createdTarget, failure);
            closeSuppressed(createdScreen, failure);
            closeSuppressed(createdShader, failure);
            throw failure;
        }
    }

    public ContactShadowSettings settings() {
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
     * Records the scene-colour resolve and the multiplicative contact-shadow
     * draw into the pass-owned target.
     *
     * <p>{@code target} must be exactly {@link #colorTarget()}; passing a
     * foreign framebuffer is rejected so the pass cannot silently write into a
     * target it does not own. The pipeline copies the result back into the
     * shared colour chain after this call.</p>
     *
     * @param cmd              GPU-owner command buffer
     * @param sceneColorSource framebuffer holding the current scene colour
     * @param target           the pass-owned target returned by {@link #colorTarget()}
     * @param sceneDepthTexture shared single-sample scene depth
     * @param frame            current frame matrices and extent
     * @param settings         active configuration used for this draw
     * @param lightDirectionWorld direction the main light travels (world space, non-zero)
     */
    public void record(CommandBuffer cmd, Framebuffer sceneColorSource, Framebuffer target,
                       int sceneDepthTexture, TemporalFrameState.FrameParameters frame,
                       ContactShadowSettings settings, Vector3f lightDirectionWorld) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(sceneColorSource, "sceneColorSource");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(lightDirectionWorld, "lightDirectionWorld");
        sceneColorSource.ensureOpen();
        target.ensureOpen();
        if (target != this.target) {
            throw new IllegalArgumentException(
                    "screen-space contact shadows must record into their pass-owned target");
        }
        if (!settings.active()) {
            throw new IllegalArgumentException("screen-space contact shadows are disabled");
        }
        if (sceneColorSource == target) {
            throw new IllegalArgumentException(
                    "contact-shadow colour source must differ from the pass target");
        }
        if (sceneColorSource.width() != width || sceneColorSource.height() != height) {
            throw new IllegalArgumentException(
                    "contact-shadow colour source extent must match the pass target");
        }
        if (frame.width() != width || frame.height() != height) {
            throw new IllegalArgumentException(
                    "contact-shadow frame extent must match the pass target");
        }
        if (sceneDepthTexture <= 0) {
            throw new IllegalArgumentException(
                    "screen-space contact shadows require the shared scene depth texture");
        }
        transformLightToView(frame, lightDirectionWorld);

        cmd.blitColor(sceneColorSource, target)
                .bindFramebuffer(target)
                .viewport(0, 0, width, height);

        cmd.bindShader(shader)
                .setUniformInt(shader, "uSceneDepth", DEPTH_UNIT)
                .setUniformVec2(shader, "uViewportSize", width, height)
                .setUniformMat4(shader, "uInverseProjection",
                        frame.inverseJitteredProjection())
                .setUniformMat4(shader, "uProjection", frame.jitteredProjection())
                .setUniformVec3(shader, "uLightDirection", light)
                .setUniformFloat(shader, "uDistance", settings.distance())
                .setUniformFloat(shader, "uThickness", settings.thickness())
                .setUniformFloat(shader, "uStrength", settings.strength())
                .setUniformInt(shader, "uSteps", settings.steps())
                .bindTexture(DEPTH_UNIT, sceneDepthTexture)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .enableBlend(true)
                .blendEquation(GL_FUNC_ADD)
                // dst' = dst * (1 - rgb); the fragment alpha stays 0, so the
                // copied source alpha is preserved exactly.
                .blendFunc(GL_ZERO, GL_ONE_MINUS_SRC_COLOR)
                .bindMesh(screen)
                .drawMesh(screen);
        cmd.enableBlend(false).depthMask(true).enableDepthTest(true);
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
     * Borrowed view of the pass-owned colour target, intended as the record
     * destination and as the source of the copy back into the shared colour
     * chain. The caller must not close it, resize it, bind it as a render
     * target for an unrelated draw, or mutate its attachments; ownership stays
     * with this pass and is released by {@link #close()}.
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
        failure = close(screen, failure);
        failure = close(shader, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private void transformLightToView(TemporalFrameState.FrameParameters frame,
                                      Vector3f direction) {
        float worldLengthSquared = direction.lengthSquared();
        if (!Float.isFinite(worldLengthSquared) || !(worldLengthSquared > 1.0e-12f)) {
            throw new IllegalArgumentException(
                    "contact-shadow light direction must be finite and non-zero");
        }
        view.set(frame.view());
        light.set(direction).mul(1.0f / (float) Math.sqrt(worldLengthSquared));
        view.transformDirection(light);
        float lengthSquared = light.lengthSquared();
        if (!Float.isFinite(lengthSquared) || !(lengthSquared > 1.0e-12f)) {
            throw new IllegalArgumentException(
                    "contact-shadow view-space light direction is degenerate");
        }
        light.mul(1.0f / (float) Math.sqrt(lengthSquared));
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
            throw new IllegalArgumentException("contact-shadow target extent must be in [1, "
                    + MAXIMUM_EXTENT + "]");
        }
    }

    private void ensureOpen() {
        if (closed) throw new GlException("screen-space contact-shadow pass is closed");
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
