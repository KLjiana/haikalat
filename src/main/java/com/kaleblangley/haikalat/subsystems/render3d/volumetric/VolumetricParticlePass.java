package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_ZERO;
import static org.lwjgl.opengl.GL14.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;

/**
 * Producer of a bounded local analytic particle medium over an HDR colour target.
 *
 * <p>The pass owns one full-size single-sample RGBA16F target, one screen quad
 * and one shader pair. It borrows the caller's colour source and one
 * single-sample opaque depth texture; it never owns, resizes or reads them back.
 * A record copies the source colour into the pass target (so the source is not
 * written while it is sampled) and draws a full-screen triangle pair clipped to
 * the projected bounding box of the local body. The fragment shader reconstructs
 * the view ray from the inverse projection/view, intersects the ellipsoidal body
 * and marches it with a fixed target step and a hard per-pixel step budget,
 * truncated by the opaque scene depth. Fragment output is premultiplied
 * in-scatter plus coverage; the blend state composites it over the copied colour:
 * {@code OCCLUDING} uses {@code ONE, ONE_MINUS_SRC_ALPHA} (destination alpha is
 * preserved), {@code ADDITIVE} uses {@code ONE, ONE} and ignores the coverage.</p>
 *
 * <p><b>Composition and de-duplication with the volumetric fog.</b> The pass is
 * recorded <em>after</em> the volumetric-fog opaque colour composite and applies
 * only its own medium to that colour. Its shader declares no fog density/source
 * sampler and does not consume {@link com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings},
 * so the fog medium is integrated exactly once by the volumetric-fog pass and
 * the particle medium exactly once by this pass. {@link VolumetricParticleSettings#fogWeight()}
 * scales the particle optical depth exactly once. See
 * {@code docs/planning/v0.28.1-s4-volumetric-particles.md}.</p>
 *
 * <p><b>Coverage and depth.</b> The draw is scissored to the projected axis-aligned
 * bounding box; pixels outside it are never shaded. If that rectangle would
 * exceed {@link VolumetricParticleSettings#maximumScreenCoverage()}, the record
 * fails before any command is enqueued (fail-closed, no partial frame); an
 * off-screen body records no draw but still copies the source colour, so
 * {@link #colorTarget()} always holds the current frame. Rays whose opaque depth
 * is in front of the body, or whose body interval is fully behind the surface,
 * produce no contribution. A cleared depth is treated as "no occluder".</p>
 *
 * <p>{@link VolumetricParticleSettings#disabled()} is rejected by the
 * constructor: while disabled no pass, target, shader or draw exists.
 * {@link #resize(int, int)} allocates the complete candidate target before
 * publishing it, so a failure keeps the previous target active. {@link #close()}
 * is idempotent and aggregates close failures.</p>
 */
public final class VolumetricParticlePass implements GlResource {
    /** RenderGraph pass name reserved for integration. */
    public static final String PASS_NAME = "VolumetricParticlePass";
    /** RenderGraph colour alias created for the particle-composited HDR output. */
    public static final String COLOR_TEXTURE = "volume.particleColor";
    /** Fragment texture unit of the borrowed single-sample scene depth. */
    public static final int DEPTH_UNIT = 0;
    /** Largest accepted target edge in pixels. */
    public static final int MAXIMUM_EXTENT = 16384;

    private static final String VERTEX_RESOURCE = "/shaders/render3d/volumetric/particle.vert";
    private static final String FRAGMENT_RESOURCE = "/shaders/render3d/volumetric/particle.frag";
    private static final float BEHIND_EYE_EPSILON = 1.0e-6f;
    private static final float COVERAGE_EPSILON = 1.0e-5f;

    private final VolumetricParticleSettings settings;
    private final ShaderProgram shader;
    private final ScreenQuad screen;
    private final Matrix4f viewScratch = new Matrix4f();
    private final Matrix4f projectionScratch = new Matrix4f();
    private final Matrix4f inverseViewScratch = new Matrix4f();
    private final Matrix4f inverseProjectionScratch = new Matrix4f();
    private final Matrix4f viewProjectionScratch = new Matrix4f();
    private final Vector4f cornerScratch = new Vector4f();
    private Framebuffer target;
    private int width;
    private int height;
    private boolean closed;

    /**
     * Creates an active particle pass and allocates its colour target.
     *
     * @param settings active bounded configuration
     * @param width    initial target width in pixels
     * @param height   initial target height in pixels
     * @throws IllegalArgumentException when disabled or the extent is invalid
     */
    public VolumetricParticlePass(VolumetricParticleSettings settings, int width, int height) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.enabled()) {
            throw new IllegalArgumentException("volumetric particles are disabled");
        }
        validateExtent(width, height);
        ShaderProgram createdShader = null;
        ScreenQuad createdScreen = null;
        Framebuffer createdTarget = null;
        try {
            createdShader = ShaderProgram.fromSources(vertexSource(), fragmentSource());
            createdScreen = new ScreenQuad();
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

    public VolumetricParticleSettings settings() {
        return settings;
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
     * Borrowed view of the pass-owned colour target for the next colour
     * consumer. The caller must not close, resize or render into it with an
     * unrelated renderer; ownership stays with this pass.
     *
     * @throws GlException when the pass is closed
     */
    public Framebuffer colorTarget() {
        ensureOpen();
        return target;
    }

    /**
     * Copies the source colour into this pass's target and composites the local
     * particle medium over it.
     *
     * @param cmd               GPU-owner command buffer
     * @param colorSource       fog-composited (or plain scene) HDR colour and depth framebuffer
     * @param sceneDepthTexture single-sample opaque scene depth matching the camera
     * @param camera            frozen frame camera used by the colour source
     * @throws IllegalArgumentException on a mismatched extent, missing depth or unusable camera
     * @throws IllegalStateException    when the projected coverage exceeds the configured cap
     */
    public void record(CommandBuffer cmd, Framebuffer colorSource, int sceneDepthTexture,
                       ExternalCamera camera) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(colorSource, "colorSource");
        Objects.requireNonNull(camera, "camera");
        colorSource.ensureOpen();
        if (colorSource == target) {
            throw new IllegalArgumentException(
                    "volumetric particles require a colour source other than the pass target");
        }
        if (colorSource.width() != width || colorSource.height() != height) {
            throw new IllegalArgumentException(
                    "volumetric particle colour source extent must match the pass target");
        }
        if (colorSource.multisampled()) {
            throw new IllegalArgumentException(
                    "volumetric particles require a resolved single-sample colour source");
        }
        if (sceneDepthTexture <= 0) {
            throw new IllegalArgumentException(
                    "volumetric particles require the single-sample opaque scene depth");
        }
        prepareMatrices(camera);
        Projection projection = project(settings, viewProjectionScratch, width, height);
        if (projection.visible()
                && projection.coverage() > settings.maximumScreenCoverage() + COVERAGE_EPSILON) {
            throw new IllegalStateException("volumetric particle coverage "
                    + projection.coverage() + " exceeds the configured cap "
                    + settings.maximumScreenCoverage());
        }
        // The copy must not inherit a neighbour's scissor rectangle.
        cmd.enableScissor(false);
        if (!projection.visible()) {
            // No draw exists, but the borrowed view stays valid for the caller:
            // the target always holds the current frame's colour.
            cmd.blitColor(colorSource, target);
            return;
        }

        cmd.blitColor(colorSource, target)
                .bindFramebuffer(target)
                .viewport(0, 0, width, height)
                .enableScissor(true)
                .scissor(projection.x(), projection.y(), projection.width(), projection.height())
                .enableBlend(true)
                .blendEquation(GL_FUNC_ADD);
        if (settings.fogBlend() == VolumetricParticleSettings.FogBlend.OCCLUDING) {
            cmd.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
        } else {
            cmd.blendFunc(GL_ONE, GL_ONE);
        }
        cmd.bindShader(shader)
                .bindTexture(DEPTH_UNIT, sceneDepthTexture)
                .setUniformInt(shader, "uSceneDepth", DEPTH_UNIT)
                .setUniformVec2(shader, "uViewportSize", width, height)
                .setUniformMat4(shader, "uInverseProjection", inverseProjectionScratch)
                .setUniformMat4(shader, "uInverseView", inverseViewScratch)
                .setUniformVec3(shader, "uBoundsCenter", settings.boundsCenterInternal())
                .setUniformVec3(shader, "uBoundsHalfExtent", settings.boundsHalfExtentInternal())
                .setUniformVec3(shader, "uLightDirection", settings.lightDirectionInternal())
                .setUniformVec3(shader, "uLightColor", settings.lightColorInternal())
                .setUniformFloat(shader, "uDensity", settings.density())
                .setUniformFloat(shader, "uAbsorption", settings.absorption())
                .setUniformFloat(shader, "uScattering", settings.scattering())
                .setUniformFloat(shader, "uAnisotropy", settings.anisotropy())
                .setUniformFloat(shader, "uOpticalDepthScale", settings.fogWeight())
                .setUniformFloat(shader, "uStepSizeMetres", settings.stepSizeMetres())
                .setUniformInt(shader, "uMaximumSteps", settings.maximumSteps())
                .setUniformInt(shader, "uOccluding",
                        settings.fogBlend() == VolumetricParticleSettings.FogBlend.OCCLUDING ? 1 : 0)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindVertexArray(screen.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableScissor(false)
                .enableBlend(false)
                .depthMask(true)
                .enableDepthTest(true);
    }

    /**
     * Explicitly resizes the colour target. The candidate is fully allocated
     * before the old target is published; an allocation failure keeps the
     * previous target active. Callers must resize before recording at a new
     * extent.
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
        previous.close();
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

    /**
     * Projects the eight corners of the local body's bounding box.
     *
     * <p>Package-private and GL-free so the projection/coverage contract can be
     * checked by a JVM test. A body that straddles the eye plane is reported as
     * full-target because at least one corner has no stable screen projection;
     * that is conservative for both the scissor and the coverage cap.</p>
     */
    static Projection project(VolumetricParticleSettings settings, Matrix4fc viewProjection,
                              int width, int height) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(viewProjection, "viewProjection");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("particle coverage extent must be positive");
        }
        float centerX = settings.boundsCenterInternal().x;
        float centerY = settings.boundsCenterInternal().y;
        float centerZ = settings.boundsCenterInternal().z;
        float extentX = settings.boundsHalfExtentInternal().x;
        float extentY = settings.boundsHalfExtentInternal().y;
        float extentZ = settings.boundsHalfExtentInternal().z;
        Vector4f corner = new Vector4f();
        float minimumX = Float.POSITIVE_INFINITY;
        float minimumY = Float.POSITIVE_INFINITY;
        float maximumX = Float.NEGATIVE_INFINITY;
        float maximumY = Float.NEGATIVE_INFINITY;
        boolean behind = false;
        boolean front = false;
        for (int mask = 0; mask < 8; mask++) {
            corner.set(centerX + (((mask & 1) == 0) ? -extentX : extentX),
                    centerY + (((mask & 2) == 0) ? -extentY : extentY),
                    centerZ + (((mask & 4) == 0) ? -extentZ : extentZ),
                    1.0f);
            corner.mul(viewProjection);
            if (!(corner.w > BEHIND_EYE_EPSILON)) {
                behind = true;
                continue;
            }
            front = true;
            float pixelX = (corner.x / corner.w * 0.5f + 0.5f) * width;
            float pixelY = (corner.y / corner.w * 0.5f + 0.5f) * height;
            if (!Float.isFinite(pixelX) || !Float.isFinite(pixelY)) {
                behind = true;
                continue;
            }
            minimumX = Math.min(minimumX, pixelX);
            minimumY = Math.min(minimumY, pixelY);
            maximumX = Math.max(maximumX, pixelX);
            maximumY = Math.max(maximumY, pixelY);
        }
        if (!front) {
            return Projection.EMPTY;
        }
        int x0;
        int y0;
        int x1;
        int y1;
        if (behind) {
            x0 = 0;
            y0 = 0;
            x1 = width;
            y1 = height;
        } else {
            x0 = clamp((int) Math.floor(minimumX), 0, width);
            y0 = clamp((int) Math.floor(minimumY), 0, height);
            x1 = clamp((int) Math.ceil(maximumX), 0, width);
            y1 = clamp((int) Math.ceil(maximumY), 0, height);
        }
        if (x1 <= x0 || y1 <= y0) {
            return Projection.EMPTY;
        }
        float coverage = (float) ((long) (x1 - x0) * (y1 - y0))
                / (float) ((long) width * height);
        return new Projection(x0, y0, x1 - x0, y1 - y0, coverage, true);
    }

    /** Projected scissor rectangle and its fraction of the target pixels. */
    record Projection(int x, int y, int width, int height, float coverage, boolean visible) {
        static final Projection EMPTY = new Projection(0, 0, 0, 0, 0.0f, false);
    }

    private void prepareMatrices(ExternalCamera camera) {
        camera.getViewMatrix(viewScratch);
        camera.projection(projectionScratch);
        inverseProjectionScratch.set(projectionScratch).invert();
        inverseViewScratch.set(viewScratch).invert();
        viewProjectionScratch.set(projectionScratch).mul(viewScratch);
        if (!inverseProjectionScratch.isFinite() || !inverseViewScratch.isFinite()
                || !viewProjectionScratch.isFinite()
                || Math.abs(inverseProjectionScratch.determinant()) <= 1.0e-8f
                || Math.abs(inverseViewScratch.determinant()) <= 1.0e-8f) {
            throw new IllegalArgumentException("volumetric particles require finite invertible camera matrices");
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Framebuffer createTarget(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(GL_RGBA16F)
                .build());
    }

    private static void validateExtent(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAXIMUM_EXTENT || height > MAXIMUM_EXTENT) {
            throw new IllegalArgumentException("volumetric particle target extent must be in [1, "
                    + MAXIMUM_EXTENT + "]");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("volumetric particle pass is closed");
        }
    }

    /** @return the packaged vertex stage; no variant is generated for this pass */
    static String vertexSource() {
        return resource(VERTEX_RESOURCE);
    }

    /** @return the packaged fragment stage; see the source for the medium contract */
    static String fragmentSource() {
        return resource(FRAGMENT_RESOURCE);
    }

    static String resource(String path) {
        try (var stream = VolumetricParticlePass.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing volumetric particle shader " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read volumetric particle shader " + path, failure);
        }
    }

    private static RuntimeException close(GlResource resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private static void closeSuppressed(GlResource resource, RuntimeException primary) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (RuntimeException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
