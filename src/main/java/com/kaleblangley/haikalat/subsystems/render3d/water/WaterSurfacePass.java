package com.kaleblangley.haikalat.subsystems.render3d.water;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalFrameState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Producer of the finite water surface colour.
 *
 * <p>The pass owns one unit rectangle mesh, one base shader variant, an optional
 * screen-space refraction variant and one cubemap sampler; it borrows the scene
 * colour/depth framebuffers and the IBL environment and never closes them.  It
 * copies the opaque scene colour into its own full-size target, then draws the
 * finite world-space rectangle at {@code y == level}.  Fragments compare their
 * plane depth against the shared scene depth (no depth attachment: the pass
 * samples the only copy), derive the vertical water thickness, and alpha-blend
 * absorption, shoreline fade and a Fresnel-weighted reflection over the copied
 * background.</p>
 *
 * <p>Screen-space refraction is opt-in through {@link WaterRefractionSettings}.
 * The water shader then samples a separate single-sample background copy at a
 * normal/view/thickness-derived screen offset; it never samples the colour
 * attachment it writes.  Shared opaque depth at the shifted pixel rejects
 * foreground lookups, off-screen lookups use the configured fallback, and the
 * offset is clamped to the configured UV bound.  When refraction is disabled
 * the base shader variant is byte-for-byte the pre-refraction shader and the
 * output is identical to the original water pass.</p>
 *
 * <p>Limitations are deliberate: no underwater volume, no chromatic dispersion,
 * no screen-space reflection, no fluid simulation, no multiple water layers and
 * no depth write.  A camera looking exactly along the plane is rejected per
 * fragment and keeps the background instead of extrapolating an unstable
 * intersection.  Glass/shield refraction remains a separate material path.</p>
 */
public final class WaterSurfacePass implements AutoCloseable {
    /** RenderGraph pass name; consumers must depend on this producer for the water colour. */
    public static final String PASS_NAME = "WaterSurfacePass";
    /** Full-size colour target holding the scene colour with the water surface composited. */
    public static final String COLOR = "waterSurfaceColor";
    /**
     * Single-sample background copy produced by the companion copy pass when
     * refraction is enabled.  It must not be the water pass colour attachment.
     */
    public static final String REFRACTION_BACKGROUND = "waterSurfaceRefractionBackground";
    /** Companion pass that blits/resolves the opaque scene colour into the refraction copy. */
    public static final String REFRACTION_BACKGROUND_PASS = "WaterSurfaceRefractionCopy";
    /** Fragment texture unit for the shared scene depth. */
    public static final int DEPTH_UNIT = 0;
    /** Fragment texture unit for the borrowed prefiltered environment cubemap. */
    public static final int ENVIRONMENT_UNIT = 1;
    /** Fragment texture unit for the single-sample refraction background copy. */
    public static final int REFRACTION_SOURCE_UNIT = 2;
    private static final String VERTEX_SHADER = "/shaders/render3d/water/water-surface.vert";
    private static final String FRAGMENT_SHADER = "/shaders/render3d/water/water-surface.frag";

    private final ShaderProgram shader;
    private final ShaderProgram refractionShader;
    private final Mesh surface;
    private final Sampler cubeSampler;
    private final int samples;
    private final Matrix4f model = new Matrix4f();
    private final Matrix4f inverseView = new Matrix4f();
    private final Vector3f cameraPosition = new Vector3f();
    private final Vector3f waterColor = new Vector3f();
    private final Vector3f reflectionFallback = new Vector3f();
    private final Vector3f refractionFallback = new Vector3f();
    private boolean closed;

    /**
     * Creates the pre-refraction pass only.
     *
     * @param samples geometry sample count; {@code > 1} selects the sampler2DMS variant
     */
    public WaterSurfacePass(int samples) {
        this(samples, false);
    }

    /**
     * @param samples           geometry sample count; {@code > 1} selects the sampler2DMS variant
     * @param refractionSupport compile the refraction fragment variant used by
     *                          {@link #record(CommandBuffer, Framebuffer, Framebuffer, Framebuffer,
     *                          int, TemporalFrameState.FrameParameters, WaterSurfaceSettings,
     *                          WaterRefractionSettings, PbrEnvironment, float, float, float)}
     */
    public WaterSurfacePass(int samples, boolean refractionSupport) {
        if (samples < 1) throw new IllegalArgumentException("water surface samples must be positive");
        this.samples = samples;
        ShaderProgram created = null;
        ShaderProgram createdRefraction = null;
        Mesh createdMesh = null;
        Sampler createdSampler = null;
        try {
            created = buildShader(samples, false);
            if (refractionSupport) {
                createdRefraction = buildShader(samples, true);
            }
            VertexAttribute position = VertexAttribute.builder()
                    .index(0).size(3).offsetBytes(0L)
                    .semantic(VertexSemantic.POSITION).build();
            createdMesh = Mesh.builder()
                    .vertices(UNIT_QUAD_POSITIONS, 3 * Float.BYTES, position)
                    .indices(UNIT_QUAD_INDICES)
                    .build();
            createdSampler = Sampler.create(new Sampler.Descriptor(
                    GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                    GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
            shader = created;
            refractionShader = createdRefraction;
            surface = createdMesh;
            cubeSampler = createdSampler;
        } catch (RuntimeException failure) {
            if (createdSampler != null) createdSampler.close();
            if (createdMesh != null) createdMesh.close();
            if (createdRefraction != null) createdRefraction.close();
            if (created != null) created.close();
            throw failure;
        }
    }

    public int samples() {
        return samples;
    }

    /** @return true when this pass compiled the screen-space refraction variant */
    public boolean refractionSupported() {
        return refractionShader != null;
    }

    /**
     * Records the copy and the finite water draw into this pass's render target.
     *
     * @param sceneColorSource framebuffer whose colour attachment is the opaque scene colour
     * @param target           framebuffer declared by this pass (must differ from the source)
     * @param sceneDepthTexture texture id of the shared scene depth matching {@link #samples()}
     * @param environment      borrowed prefiltered environment, or {@code null} for the fallback tint
     */
    public void record(CommandBuffer cmd, Framebuffer sceneColorSource, Framebuffer target,
                       int sceneDepthTexture,
                       TemporalFrameState.FrameParameters frame, WaterSurfaceSettings settings,
                       PbrEnvironment environment, float environmentIntensity,
                       float environmentRotationRadians, float timeSeconds) {
        recordInternal(cmd, sceneColorSource, null, target, sceneDepthTexture, frame,
                settings, WaterRefractionSettings.disabled(), environment, environmentIntensity,
                environmentRotationRadians, timeSeconds, false);
    }

    /**
     * Records the water draw with opt-in screen-space refraction.
     *
     * <p>{@code backgroundCopy} is the single-sample copy produced by the
     * companion {@link #REFRACTION_BACKGROUND_PASS} pass; it must be a distinct
     * colour attachment from {@code target}.  When
     * {@code refraction.active()} is false the copy is ignored and the base
     * pre-refraction shader fills this pass's target exactly as before.</p>
     */
    public void record(CommandBuffer cmd, Framebuffer sceneColorSource, Framebuffer backgroundCopy,
                       Framebuffer target, int sceneDepthTexture,
                       TemporalFrameState.FrameParameters frame, WaterSurfaceSettings settings,
                       WaterRefractionSettings refraction, PbrEnvironment environment,
                       float environmentIntensity, float environmentRotationRadians,
                       float timeSeconds) {
        recordInternal(cmd, sceneColorSource, backgroundCopy, target, sceneDepthTexture, frame,
                settings, Objects.requireNonNull(refraction, "refraction"), environment,
                environmentIntensity, environmentRotationRadians, timeSeconds, false);
    }

    /**
     * Diagnostic seam for the real-GL shader contract: writes {@code N * 0.5 + 0.5}
     * instead of shaded water so the analytic normals can be compared with an
     * independent CPU reference.  Production recording never calls it.
     */
    public void recordNormalDebug(CommandBuffer cmd, Framebuffer sceneColorSource,
                                  Framebuffer target, int sceneDepthTexture,
                                  TemporalFrameState.FrameParameters frame,
                                  WaterSurfaceSettings settings, float timeSeconds) {
        recordInternal(cmd, sceneColorSource, null, target, sceneDepthTexture, frame,
                settings, WaterRefractionSettings.disabled(), null, 0.0f, 0.0f, timeSeconds, true);
    }

    /**
     * Copies/resolves the opaque scene colour into the single-sample refraction
     * background attachment.
     *
     * <p>The caller declares {@link #REFRACTION_BACKGROUND_PASS} with
     * {@link #REFRACTION_BACKGROUND} and must make the water pass depend on it.
     * Source and target extents must match; a multisampled source is resolved by
     * the blit.</p>
     */
    public static void recordBackgroundCopy(CommandBuffer cmd, Framebuffer source, Framebuffer target) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source.width() != target.width() || source.height() != target.height()) {
            throw new IllegalArgumentException(
                    "water refraction background copy extent must match its source");
        }
        if (source == target) {
            throw new IllegalArgumentException(
                    "water refraction background copy must differ from its source");
        }
        if (target.samples() != 1 || !target.colorAttachmentIsTexture(0)) {
            throw new IllegalArgumentException(
                    "water refraction background copy must be a single-sample colour texture");
        }
        cmd.blitColor(source, target)
                .bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height());
    }

    private void recordInternal(CommandBuffer cmd, Framebuffer sceneColorSource,
                                Framebuffer backgroundCopy, Framebuffer target,
                                int sceneDepthTexture,
                                TemporalFrameState.FrameParameters frame,
                                WaterSurfaceSettings settings, WaterRefractionSettings refraction,
                                PbrEnvironment environment, float environmentIntensity,
                                float environmentRotationRadians, float timeSeconds,
                                boolean debugNormals) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(sceneColorSource, "sceneColorSource");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(settings, "settings");
        if (sceneColorSource == target) {
            throw new IllegalArgumentException(
                    "water surface target must differ from its scene colour source");
        }
        if (sceneDepthTexture <= 0) {
            throw new IllegalArgumentException(
                    "water surface requires the shared scene depth texture");
        }
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            throw new IllegalArgumentException("water wave time must be finite and non-negative");
        }
        boolean useRefraction = !debugNormals && refraction.active();
        if (useRefraction) {
            if (refractionShader == null) {
                throw new IllegalStateException(
                        "water refraction requires a WaterSurfacePass created with refraction support");
            }
            validateBackgroundCopy(backgroundCopy, target);
        }

        cmd.blitColor(sceneColorSource, target)
                .bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height());

        ShaderProgram activeShader = useRefraction ? refractionShader : shader;
        cmd.bindShader(activeShader);
        model.identity()
                .translate((settings.minX() + settings.maxX()) * 0.5f, settings.level(),
                        (settings.minZ() + settings.maxZ()) * 0.5f)
                .scale(settings.width(), 1.0f, settings.depth());
        cameraPosition.set(frame.cameraPositionX(), frame.cameraPositionY(),
                frame.cameraPositionZ());
        settings.waterColorTo(waterColor);
        settings.reflectionFallbackTo(reflectionFallback);

        cmd.setUniformMat4(activeShader, "uProjection", frame.jitteredProjection())
                .setUniformMat4(activeShader, "uView", frame.view())
                .setUniformMat4(activeShader, "uModel", model)
                .setUniformMat4(activeShader, "uInverseProjection", frame.inverseJitteredProjection())
                .setUniformMat4(activeShader, "uInverseView",
                        inverseView.set(frame.view()).invert())
                .setUniformVec3(activeShader, "uCameraPosition", cameraPosition)
                .setUniformFloat(activeShader, "uFarPlane", frame.farPlane())
                .setUniformVec3(activeShader, "uWaterColor", waterColor)
                .setUniformVec3(activeShader, "uReflectionFallback", reflectionFallback)
                .setUniformFloat(activeShader, "uAbsorptionDensity", settings.absorptionDensity())
                .setUniformFloat(activeShader, "uShoreFadeDistance", settings.shoreFadeDistance())
                .setUniformFloat(activeShader, "uWaterLevel", settings.level())
                .setUniformFloat(activeShader, "uWaveWavelength", settings.waveWavelength())
                .setUniformFloat(activeShader, "uWaveSpeed", settings.waveSpeed())
                .setUniformFloat(activeShader, "uWaveAmplitude", settings.waveAmplitude())
                .setUniformFloat(activeShader, "uWaveDirection", settings.waveDirectionRadians())
                .setUniformFloat(activeShader, "uTimeSeconds", timeSeconds)
                .setUniformInt(activeShader, "uDebugMode", debugNormals ? 1 : 0)
                .setUniformInt(activeShader, "uSceneDepth", DEPTH_UNIT);

        if (useRefraction) {
            refraction.offscreenFallbackColorTo(refractionFallback);
            cmd.setUniformInt(activeShader, "uSceneColor", REFRACTION_SOURCE_UNIT)
                    .setUniformFloat(activeShader, "uRefractionIor", refraction.ior())
                    .setUniformFloat(activeShader, "uRefractionStrength", refraction.strength())
                    .setUniformFloat(activeShader, "uRefractionMaxOffset",
                            refraction.maximumScreenOffset())
                    .setUniformFloat(activeShader, "uRefractionThicknessScale",
                            refraction.thicknessScale())
                    .setUniformFloat(activeShader, "uRefractionForegroundBias",
                            refraction.foregroundRejectionBias())
                    .setUniformVec3(activeShader, "uRefractionFallbackColor", refractionFallback)
                    .setUniformInt(activeShader, "uRefractionFallbackMode",
                            refraction.offscreenFallback().shaderValue())
                    .bindTexture(REFRACTION_SOURCE_UNIT, backgroundCopy.colorAttachment());
        }

        if (samples > 1) {
            cmd.bindTextureMultisample(DEPTH_UNIT, sceneDepthTexture);
        } else {
            cmd.bindTexture(DEPTH_UNIT, sceneDepthTexture);
        }

        if (environment != null) {
            cmd.setUniformInt(activeShader, "uHasEnvironment", 1)
                    .setUniformFloat(activeShader, "uEnvironmentIntensity", environmentIntensity)
                    .setUniformFloat(activeShader, "uEnvironmentRotation", environmentRotationRadians)
                    .bindTextureCube(ENVIRONMENT_UNIT, environment.prefilteredSpecular(),
                            cubeSampler)
                    .setUniformInt(activeShader, "uEnvironment", ENVIRONMENT_UNIT);
        } else {
            cmd.setUniformInt(activeShader, "uHasEnvironment", 0);
        }

        cmd.enableDepthTest(false)
                .depthMask(debugNormals)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .enableBlend(!debugNormals);
        if (!debugNormals) {
            cmd.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        cmd.bindMesh(surface).drawMesh(surface);
        cmd.enableBlend(false).depthMask(true).enableDepthTest(true);
    }

    private static void validateBackgroundCopy(Framebuffer backgroundCopy, Framebuffer target) {
        if (backgroundCopy == null) {
            throw new IllegalArgumentException(
                    "water refraction requires the single-sample background copy");
        }
        if (backgroundCopy == target) {
            throw new IllegalArgumentException(
                    "water refraction background copy must differ from the water target");
        }
        if (backgroundCopy.width() != target.width()
                || backgroundCopy.height() != target.height()) {
            throw new IllegalArgumentException(
                    "water refraction background copy extent must match the water target");
        }
        if (backgroundCopy.samples() != 1 || !backgroundCopy.colorAttachmentIsTexture(0)
                || backgroundCopy.colorAttachment() <= 0) {
            throw new IllegalArgumentException(
                    "water refraction background copy must be a single-sample colour texture");
        }
    }

    static String fragmentSource(boolean multisample) {
        return fragmentSource(multisample, false);
    }

    static String fragmentSource(boolean multisample, boolean refraction) {
        try (var input = WaterSurfacePass.class.getResourceAsStream(FRAGMENT_SHADER)) {
            if (input == null) throw new GlException("missing water surface shader");
            String source = new String(input.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!source.startsWith("#version")) {
                throw new GlException("missing GLSL version in water surface shader");
            }
            if (!multisample && !refraction) return source;
            int newline = source.indexOf('\n');
            StringBuilder defines = new StringBuilder();
            if (multisample) defines.append("#define WATER_MSAA\n");
            if (refraction) defines.append("#define WATER_REFRACTION\n");
            return source.substring(0, newline + 1) + defines
                    + source.substring(newline + 1);
        } catch (java.io.IOException failure) {
            throw new GlException("cannot read water surface shader", failure);
        }
    }

    private static ShaderProgram buildShader(int samples, boolean refraction) {
        return ShaderProgram.builder()
                .resource(WaterSurfacePass.class, ShaderStage.VERTEX, VERTEX_SHADER)
                .stage(ShaderStage.FRAGMENT, fragmentSource(samples > 1, refraction),
                        FRAGMENT_SHADER)
                .link();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            cubeSampler.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            surface.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (refractionShader != null) {
            try {
                refractionShader.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        try {
            shader.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("water surface pass is closed");
    }

    /** Unit rectangle in the XZ plane; the per-frame model matrix places and sizes it. */
    private static final float[] UNIT_QUAD_POSITIONS = {
            -0.5f, 0.0f, -0.5f,
            -0.5f, 0.0f, 0.5f,
            0.5f, 0.0f, 0.5f,
            0.5f, 0.0f, -0.5f
    };

    private static final int[] UNIT_QUAD_INDICES = {0, 1, 2, 0, 2, 3};
}
