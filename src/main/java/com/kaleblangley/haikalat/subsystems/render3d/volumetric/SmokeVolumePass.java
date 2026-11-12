package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture3D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
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
 * Volume-rendering consumer of the {@link SmokeSimulation} density field.
 *
 * <p>The pass owns one full-size single-sample {@code RGBA16F} target, one
 * screen quad and one shader pair. It borrows the caller's colour source, one
 * resolved opaque depth texture and the simulation's current density/temperature
 * 3D texture; it never owns or resizes those borrows. A record copies the
 * source colour into this pass's target and draws a full-screen triangle pair
 * clipped to the projected bounding box of the simulation grid. The fragment
 * stage intersects the world ray with that axis-aligned box, truncates the
 * interval at the opaque surface and marches it with a fixed target step and a
 * hard per-pixel budget. Fragment output is premultiplied in-scatter plus
 * coverage; the blend state composites it over the copied colour:
 * {@code OCCLUDING} uses {@code ONE, ONE_MINUS_SRC_ALPHA} (destination alpha is
 * preserved), {@code ADDITIVE} uses {@code ONE, ONE} and ignores the coverage.</p>
 *
 * <p><b>Single-medium composition.</b> The shader declares exactly one 3D
 * density sampler and no fog density/source sampler, so the volumetric-fog,
 * volumetric-particle and smoke media are each integrated once by their own
 * owners. Record this pass after the fog colour composite (and after the
 * particle pass when it is active) so every medium applies once. See
 * {@code docs/planning/v0.28.1-s5-smoke.md}.</p>
 *
 * <p><b>Coverage and depth.</b> The draw is scissored to the projected AABB of
 * the grid; pixels outside it are never shaded. If that rectangle would exceed
 * {@link SmokeRenderSettings#maximumScreenCoverage()}, the record fails before
 * any command is enqueued (fail-closed, no partial frame); an off-screen grid
 * records no draw but still copies the source colour, so
 * {@link #colorTarget()} always holds the current frame. Rays whose opaque
 * depth is in front of the grid, or whose grid interval is fully behind the
 * surface, produce no contribution. A cleared depth is treated as no occluder.</p>
 *
 * <p>{@link SmokeRenderSettings#disabled()} is rejected by the constructor:
 * while disabled no pass, target, shader or draw exists. {@link #resize(int,
 * int)} allocates the complete candidate target before publishing it, so a
 * failure keeps the previous target active. {@link #close()} is idempotent and
 * aggregates close failures.</p>
 */
public final class SmokeVolumePass implements GlResource {
    /** RenderGraph pass name reserved for integration. */
    public static final String PASS_NAME = "SmokeVolumePass";
    /**
     * Graph colour attachment declared by the pipeline bridge. The pass-owned
     * framebuffer is copied into this target; downstream consumers read it
     * through the {@link #PASS_NAME} producer.
     */
    public static final String COLOR_TEXTURE = "volume.smokeColor";
    /** Texture unit of the borrowed smoke 3D field. */
    public static final int FIELD_UNIT = 0;
    /** Fragment texture unit of the borrowed resolved opaque scene depth. */
    public static final int DEPTH_UNIT = 1;
    /** Largest accepted target edge in pixels. */
    public static final int MAXIMUM_EXTENT = 16384;

    private static final String VERTEX_RESOURCE = "/shaders/render3d/volumetric/smoke-volume.vert";
    private static final String FRAGMENT_RESOURCE = "/shaders/render3d/volumetric/smoke-volume.frag";
    private static final float BEHIND_EYE_EPSILON = 1.0e-6f;
    private static final float COVERAGE_EPSILON = 1.0e-5f;

    private final SmokeRenderSettings settings;
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
     * Creates an active volume pass and allocates its colour target.
     *
     * @param settings active bounded configuration
     * @param width    initial target width in pixels
     * @param height   initial target height in pixels
     */
    public SmokeVolumePass(SmokeRenderSettings settings, int width, int height) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.enabled()) {
            throw new IllegalArgumentException("smoke volume rendering is disabled");
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

    public SmokeRenderSettings settings() {
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
     * consumer. Ownership stays with this pass.
     */
    public Framebuffer colorTarget() {
        ensureOpen();
        return target;
    }

    /**
     * Copies the source colour into this pass's target and composites the smoke
     * volume over it.
     *
     * @param cmd               GPU-owner command buffer
     * @param colorSource       HDR colour and depth framebuffer to copy
     * @param sceneDepthTexture resolved single-sample opaque scene depth
     * @param camera            frozen frame camera used by the colour source
     * @param simulation        open simulation whose field is consumed
     * @throws IllegalArgumentException on a mismatched extent, missing depth or unusable camera
     * @throws IllegalStateException    when the projected coverage exceeds the configured cap
     */
    public void record(CommandBuffer cmd, Framebuffer colorSource, int sceneDepthTexture,
                       ExternalCamera camera, SmokeSimulation simulation) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(colorSource, "colorSource");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(simulation, "simulation");
        colorSource.ensureOpen();
        Texture3D field = simulation.densityTexture();
        SmokeSimulationSettings simulationSettings = simulation.settings();
        if (colorSource == target) {
            throw new IllegalArgumentException(
                    "smoke volume requires a colour source other than the pass target");
        }
        if (colorSource.width() != width || colorSource.height() != height) {
            throw new IllegalArgumentException(
                    "smoke volume colour source extent must match the pass target");
        }
        if (colorSource.multisampled()) {
            throw new IllegalArgumentException(
                    "smoke volume requires a resolved single-sample colour source");
        }
        if (sceneDepthTexture <= 0) {
            throw new IllegalArgumentException(
                    "smoke volume requires the resolved single-sample opaque scene depth");
        }
        prepareMatrices(camera);
        Vector3f center = simulationSettings.boundsCenterInternal();
        Vector3f halfExtent = simulationSettings.boundsHalfExtentInternal();
        Projection projection = project(center, halfExtent, viewProjectionScratch, width, height);
        if (projection.visible()
                && projection.coverage() > settings.maximumScreenCoverage() + COVERAGE_EPSILON) {
            throw new IllegalStateException("smoke volume coverage "
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
        if (settings.blend() == SmokeRenderSettings.Blend.OCCLUDING) {
            cmd.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
        } else {
            cmd.blendFunc(GL_ONE, GL_ONE);
        }
        cmd.bindShader(shader)
                .bindTexture3D(FIELD_UNIT, field)
                .bindTexture(DEPTH_UNIT, sceneDepthTexture)
                .setUniformInt(shader, "uSmokeField", FIELD_UNIT)
                .setUniformInt(shader, "uSceneDepth", DEPTH_UNIT)
                .setUniformVec2(shader, "uViewportSize", width, height)
                .setUniformMat4(shader, "uInverseProjection", inverseProjectionScratch)
                .setUniformMat4(shader, "uInverseView", inverseViewScratch)
                .setUniformVec3(shader, "uBoundsCenter", center)
                .setUniformVec3(shader, "uBoundsHalfExtent", halfExtent)
                .setUniformVec3(shader, "uLightDirection", settings.lightDirectionInternal())
                .setUniformVec3(shader, "uLightColor", settings.lightColorInternal())
                .setUniformFloat(shader, "uDensityScale", settings.densityScale())
                .setUniformFloat(shader, "uAbsorption", settings.absorption())
                .setUniformFloat(shader, "uScattering", settings.scattering())
                .setUniformFloat(shader, "uAnisotropy", settings.anisotropy())
                .setUniformFloat(shader, "uOpticalDepthScale", settings.contributionWeight())
                .setUniformFloat(shader, "uStepSizeMetres", settings.stepSizeMetres())
                .setUniformInt(shader, "uMaximumSteps", settings.maximumSteps())
                .setUniformInt(shader, "uOccluding",
                        settings.blend() == SmokeRenderSettings.Blend.OCCLUDING ? 1 : 0)
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
     * previous target active.
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
     * Projects the eight corners of the grid AABB.
     *
     * <p>Package-private and GL-free so the projection/coverage contract can be
     * checked by a JVM test. A box that straddles the eye plane is reported as
     * full-target because at least one corner has no stable screen projection;
     * that is conservative for both the scissor and the coverage cap.</p>
     */
    static Projection project(Vector3f center, Vector3f halfExtent,
                              Matrix4fc viewProjection, int width, int height) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(halfExtent, "halfExtent");
        Objects.requireNonNull(viewProjection, "viewProjection");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("smoke coverage extent must be positive");
        }
        float centerX = center.x;
        float centerY = center.y;
        float centerZ = center.z;
        float extentX = halfExtent.x;
        float extentY = halfExtent.y;
        float extentZ = halfExtent.z;
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
            throw new IllegalArgumentException(
                    "smoke volume requires finite invertible camera matrices");
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
            throw new IllegalArgumentException("smoke volume target extent must be in [1, "
                    + MAXIMUM_EXTENT + "]");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("smoke volume pass is closed");
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
        try (var stream = SmokeVolumePass.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing smoke volume shader " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read smoke volume shader " + path, failure);
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
