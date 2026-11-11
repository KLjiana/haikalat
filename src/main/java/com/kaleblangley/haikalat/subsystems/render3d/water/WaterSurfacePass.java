package com.kaleblangley.haikalat.subsystems.render3d.water;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
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
 * <p>The pass owns one unit rectangle mesh, one shader variant and one cubemap
 * sampler; it borrows the scene colour/depth framebuffers and the IBL
 * environment and never closes them.  It copies the opaque scene colour into its
 * own full-size target, then draws the finite world-space rectangle at
 * {@code y == level}.  Fragments compare their plane depth against the shared
 * scene depth (no depth attachment: the pass samples the only copy), derive the
 * vertical water thickness, and alpha-blend absorption, shoreline fade and a
 * Fresnel-weighted reflection over the copied background.</p>
 *
 * <p>Limitations are deliberate: no underwater volume, no refraction, no
 * screen-space reflection, no fluid simulation and no depth write.  A camera
 * looking exactly along the plane is rejected per fragment and keeps the
 * background instead of extrapolating an unstable intersection.</p>
 */
public final class WaterSurfacePass implements AutoCloseable {
    /** RenderGraph pass name; consumers must depend on this producer for the water colour. */
    public static final String PASS_NAME = "WaterSurfacePass";
    /** Full-size colour target holding the scene colour with the water surface composited. */
    public static final String COLOR = "waterSurfaceColor";
    /** Fragment texture unit for the shared scene depth. */
    public static final int DEPTH_UNIT = 0;
    /** Fragment texture unit for the borrowed prefiltered environment cubemap. */
    public static final int ENVIRONMENT_UNIT = 1;

    private final ShaderProgram shader;
    private final Mesh surface;
    private final Sampler cubeSampler;
    private final int samples;
    private final Matrix4f model = new Matrix4f();
    private final Matrix4f inverseView = new Matrix4f();
    private final Vector3f cameraPosition = new Vector3f();
    private final Vector3f waterColor = new Vector3f();
    private final Vector3f reflectionFallback = new Vector3f();
    private boolean closed;

    /** @param samples geometry sample count; {@code > 1} selects the sampler2DMS variant */
    public WaterSurfacePass(int samples) {
        if (samples < 1) throw new IllegalArgumentException("water surface samples must be positive");
        this.samples = samples;
        ShaderProgram created = null;
        Mesh createdMesh = null;
        Sampler createdSampler = null;
        try {
            created = ShaderProgram.builder()
                    .resource(WaterSurfacePass.class, ShaderStage.VERTEX,
                            "/shaders/render3d/water/water-surface.vert")
                    .stage(ShaderStage.FRAGMENT, fragmentSource(samples > 1),
                            "/shaders/render3d/water/water-surface.frag")
                    .link();
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
            surface = createdMesh;
            cubeSampler = createdSampler;
        } catch (RuntimeException failure) {
            if (createdSampler != null) createdSampler.close();
            if (createdMesh != null) createdMesh.close();
            if (created != null) created.close();
            throw failure;
        }
    }

    public int samples() {
        return samples;
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
        recordInternal(cmd, sceneColorSource, target, sceneDepthTexture, frame,
                settings, environment, environmentIntensity, environmentRotationRadians,
                timeSeconds, false);
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
        recordInternal(cmd, sceneColorSource, target, sceneDepthTexture, frame,
                settings, null, 0.0f, 0.0f, timeSeconds, true);
    }

    private void recordInternal(CommandBuffer cmd, Framebuffer sceneColorSource,
                                Framebuffer target, int sceneDepthTexture,
                                TemporalFrameState.FrameParameters frame,
                                WaterSurfaceSettings settings, PbrEnvironment environment,
                                float environmentIntensity, float environmentRotationRadians,
                                float timeSeconds, boolean debugNormals) {
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

        cmd.blitColor(sceneColorSource, target)
                .bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height());

        cmd.bindShader(shader);
        model.identity()
                .translate((settings.minX() + settings.maxX()) * 0.5f, settings.level(),
                        (settings.minZ() + settings.maxZ()) * 0.5f)
                .scale(settings.width(), 1.0f, settings.depth());
        cameraPosition.set(frame.cameraPositionX(), frame.cameraPositionY(),
                frame.cameraPositionZ());
        settings.waterColorTo(waterColor);
        settings.reflectionFallbackTo(reflectionFallback);

        cmd.setUniformMat4(shader, "uProjection", frame.jitteredProjection())
                .setUniformMat4(shader, "uView", frame.view())
                .setUniformMat4(shader, "uModel", model)
                .setUniformMat4(shader, "uInverseProjection", frame.inverseJitteredProjection())
                .setUniformMat4(shader, "uInverseView",
                        inverseView.set(frame.view()).invert())
                .setUniformVec3(shader, "uCameraPosition", cameraPosition)
                .setUniformFloat(shader, "uFarPlane", frame.farPlane())
                .setUniformVec3(shader, "uWaterColor", waterColor)
                .setUniformVec3(shader, "uReflectionFallback", reflectionFallback)
                .setUniformFloat(shader, "uAbsorptionDensity", settings.absorptionDensity())
                .setUniformFloat(shader, "uShoreFadeDistance", settings.shoreFadeDistance())
                .setUniformFloat(shader, "uWaterLevel", settings.level())
                .setUniformFloat(shader, "uWaveWavelength", settings.waveWavelength())
                .setUniformFloat(shader, "uWaveSpeed", settings.waveSpeed())
                .setUniformFloat(shader, "uWaveAmplitude", settings.waveAmplitude())
                .setUniformFloat(shader, "uWaveDirection", settings.waveDirectionRadians())
                .setUniformFloat(shader, "uTimeSeconds", timeSeconds)
                .setUniformInt(shader, "uDebugMode", debugNormals ? 1 : 0)
                .setUniformInt(shader, "uSceneDepth", DEPTH_UNIT);

        if (samples > 1) {
            cmd.bindTextureMultisample(DEPTH_UNIT, sceneDepthTexture);
        } else {
            cmd.bindTexture(DEPTH_UNIT, sceneDepthTexture);
        }

        if (environment != null) {
            cmd.setUniformInt(shader, "uHasEnvironment", 1)
                    .setUniformFloat(shader, "uEnvironmentIntensity", environmentIntensity)
                    .setUniformFloat(shader, "uEnvironmentRotation", environmentRotationRadians)
                    .bindTextureCube(ENVIRONMENT_UNIT, environment.prefilteredSpecular(),
                            cubeSampler)
                    .setUniformInt(shader, "uEnvironment", ENVIRONMENT_UNIT);
        } else {
            cmd.setUniformInt(shader, "uHasEnvironment", 0);
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

    static String fragmentSource(boolean multisample) {
        try (var input = WaterSurfacePass.class.getResourceAsStream(
                "/shaders/render3d/water/water-surface.frag")) {
            if (input == null) throw new GlException("missing water surface shader");
            String source = new String(input.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!source.startsWith("#version")) {
                throw new GlException("missing GLSL version in water surface shader");
            }
            if (!multisample) return source;
            int newline = source.indexOf('\n');
            return source.substring(0, newline + 1) + "#define WATER_MSAA\n"
                    + source.substring(newline + 1);
        } catch (java.io.IOException failure) {
            throw new GlException("cannot read water surface shader", failure);
        }
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
