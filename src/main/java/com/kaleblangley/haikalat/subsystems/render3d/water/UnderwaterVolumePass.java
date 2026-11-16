package com.kaleblangley.haikalat.subsystems.render3d.water;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalFrameState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Producer of the bounded underwater Beer-Lambert volume tint.
 *
 * <p>The pass is one full-screen triangle rendered into a pass-owned colour
 * target.  It samples the current scene-colour producer (single-sample chain)
 * or the pass-owned single-sample colour copy (MSAA chain) and a single-sample
 * scene-depth texture.  The graph owns the targets; this pass owns only its
 * program and screen mesh.  With a camera strictly below the water plane the
 * fragment reconstructs the world position from depth and inverse
 * view-projection and writes
 * {@code scene * exp(-density * path) + scatter * (1 - exp(-density * path))},
 * with {@code path = min(|world - camera|, maximumDistance)}.  With a camera at
 * or above the plane the executor copies the producer into the target
 * unchanged, so the frame stays byte-identical while the graph topology stays
 * stable.</p>
 *
 * <p>The shader guards every division, clamps the density and the path, and
 * bounds the exponent, so the result is finite for every finite input.  This is
 * a screen-space approximation: it cannot attenuate anything not represented
 * by the visible depth buffer, it has no dispersion, no multi-layer water and
 * no wave displacement of the entry plane, and it never writes depth.</p>
 */
public final class UnderwaterVolumePass implements AutoCloseable {
    /** RenderGraph pass name; consumers must depend on this producer for the tinted colour. */
    public static final String PASS_NAME = "UnderwaterVolumePass";
    /** Full-size colour target holding the scene colour with the water path applied. */
    public static final String COLOR = "underwaterVolumeColor";
    /** Pass that resolves the current scene colour into the single-sample MSAA source copy. */
    public static final String COLOR_COPY_PASS = "UnderwaterVolumeColorCopy";
    /** Single-sample scene-colour copy sampled by the MSAA variant. */
    public static final String SOURCE_COLOR = "underwaterVolumeSourceColor";
    /** Pass that resolves the raster depth into the pass-owned single-sample depth texture. */
    public static final String DEPTH_RESOLVE_PASS = "UnderwaterVolumeDepthResolve";
    /** Single-sample scene-depth texture sampled by the volume shader. */
    public static final String DEPTH_TEXTURE = "underwaterVolume.depth";
    /** Fragment texture unit for the sampled scene colour. */
    public static final int COLOR_UNIT = 0;
    /** Fragment texture unit for the sampled single-sample scene depth. */
    public static final int DEPTH_UNIT = 1;
    private static final String VERTEX_SHADER = "/shaders/render3d/water/underwater-volume.vert";
    private static final String FRAGMENT_SHADER = "/shaders/render3d/water/underwater-volume.frag";

    private final ShaderProgram shader;
    private final Mesh screen;
    private final Matrix4f inverseViewProjection = new Matrix4f();
    private final Vector3f cameraPosition = new Vector3f();
    private final Vector3f scatterColor = new Vector3f();
    private boolean closed;

    /** Compiles the single shader variant shared by the single-sample and MSAA chains. */
    public UnderwaterVolumePass() {
        ShaderProgram createdShader = null;
        Mesh createdScreen = null;
        try {
            createdShader = ShaderProgram.builder()
                    .resource(UnderwaterVolumePass.class, ShaderStage.VERTEX, VERTEX_SHADER)
                    .resource(UnderwaterVolumePass.class, ShaderStage.FRAGMENT, FRAGMENT_SHADER)
                    .link();
            createdScreen = screenMesh();
            shader = createdShader;
            screen = createdScreen;
        } catch (RuntimeException failure) {
            if (createdScreen != null) createdScreen.close();
            if (createdShader != null) createdShader.close();
            throw failure;
        }
    }

    /**
     * Records the bounded underwater volume into this pass's render target.
     *
     * <p>When the camera is at or above {@code waterLevelMetres} the producer
     * framebuffer is blitted into the target and no draw is recorded; that path
     * is byte-identical to the source for every sample count.</p>
     *
     * @param cmd                     GPU-owner command buffer
     * @param sceneColorSource        framebuffer whose colour attachment is the current scene colour
     * @param target                  framebuffer declared by this pass (must differ from the source)
     * @param sceneColorTexture       single-sample scene-colour texture to sample
     * @param singleSampleDepthTexture single-sample scene-depth texture to sample
     * @param frame                   current jittered camera matrices and extent
     * @param settings                active bounded configuration
     * @param waterLevelMetres        active finite water-plane height in world metres
     */
    public void record(CommandBuffer cmd, Framebuffer sceneColorSource, Framebuffer target,
                       int sceneColorTexture, int singleSampleDepthTexture,
                       TemporalFrameState.FrameParameters frame,
                       UnderwaterVolumeSettings settings, float waterLevelMetres) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(sceneColorSource, "sceneColorSource");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("underwater volume settings are disabled");
        }
        if (sceneColorSource == target) {
            throw new IllegalArgumentException(
                    "underwater volume target must differ from its scene colour source");
        }
        if (sceneColorSource.width() != target.width()
                || sceneColorSource.height() != target.height()) {
            throw new IllegalArgumentException(
                    "underwater volume source and target extents must match");
        }
        if (!Float.isFinite(waterLevelMetres)) {
            throw new IllegalArgumentException("underwater water level must be finite");
        }

        boolean cameraBelowWater = frame.cameraPositionY() < waterLevelMetres;
        if (!cameraBelowWater) {
            cmd.blitColor(sceneColorSource, target)
                    .bindFramebuffer(target)
                    .viewport(0, 0, target.width(), target.height());
            return;
        }
        if (sceneColorTexture <= 0) {
            throw new IllegalArgumentException(
                    "underwater volume requires a single-sample scene colour texture");
        }
        if (singleSampleDepthTexture <= 0) {
            throw new IllegalArgumentException(
                    "underwater volume requires a single-sample scene depth texture");
        }

        inverseViewProjection.set(frame.jitteredProjection())
                .mul(frame.view())
                .invert();
        cameraPosition.set(frame.cameraPositionX(), frame.cameraPositionY(),
                frame.cameraPositionZ());
        settings.scatterColorTo(scatterColor);

        cmd.bindShader(shader)
                .setUniformMat4(shader, "uInverseViewProjection", inverseViewProjection)
                .setUniformVec3(shader, "uCameraPosition", cameraPosition)
                .setUniformFloat(shader, "uWaterLevel", waterLevelMetres)
                .setUniformFloat(shader, "uAbsorptionDensity", settings.absorptionDensity())
                .setUniformVec3(shader, "uScatterColor", scatterColor)
                .setUniformFloat(shader, "uMaximumDistance", settings.maximumDistanceMetres())
                .setUniformVec2(shader, "uViewportSize", target.width(), target.height())
                .setUniformInt(shader, "uSceneColor", COLOR_UNIT)
                .setUniformInt(shader, "uSceneDepth", DEPTH_UNIT)
                .bindTexture(COLOR_UNIT, sceneColorTexture)
                .bindTexture(DEPTH_UNIT, singleSampleDepthTexture)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .enableBlend(false)
                .bindMesh(screen)
                .drawMesh(screen);
        cmd.depthMask(true).enableDepthTest(true);
    }

    /**
     * Copies/resolves the current scene colour into the pass-owned single-sample
     * colour copy used by the MSAA variant.  Source and target extents must
     * match; a multisampled source is resolved by the blit.
     */
    public static void recordColorCopy(CommandBuffer cmd, Framebuffer source,
                                       Framebuffer target) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source == target) {
            throw new IllegalArgumentException(
                    "underwater volume colour copy must differ from its source");
        }
        if (source.width() != target.width() || source.height() != target.height()) {
            throw new IllegalArgumentException(
                    "underwater volume colour copy extent must match its source");
        }
        if (target.samples() != 1 || !target.colorAttachmentIsTexture(0)) {
            throw new IllegalArgumentException(
                    "underwater volume colour copy must be a single-sample colour texture");
        }
        cmd.blitColor(source, target)
                .bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height());
    }

    /**
     * Copies/resolves the raster depth into the pass-owned single-sample depth
     * texture.  A multisampled source is resolved by the blit, so a plain
     * sampler never reads a multisampled depth attachment.
     */
    public static void recordDepthResolve(CommandBuffer cmd, Framebuffer source,
                                          Framebuffer target) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source == target) {
            throw new IllegalArgumentException(
                    "underwater volume depth resolve must differ from its source");
        }
        if (source.width() != target.width() || source.height() != target.height()) {
            throw new IllegalArgumentException(
                    "underwater volume depth resolve extent must match its source");
        }
        if (target.samples() != 1 || !target.depthAttachmentIsTexture()) {
            throw new IllegalArgumentException(
                    "underwater volume depth resolve must own a single-sample depth texture");
        }
        if (source.depthAttachment() <= 0) {
            throw new IllegalArgumentException(
                    "underwater volume depth resolve requires a source depth attachment");
        }
        cmd.blitDepth(source, target)
                .bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height());
    }

    static String fragmentSource() {
        try (var input = UnderwaterVolumePass.class.getResourceAsStream(FRAGMENT_SHADER)) {
            if (input == null) throw new GlException("missing underwater volume shader");
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (!source.startsWith("#version")) {
                throw new GlException("missing GLSL version in underwater volume shader");
            }
            return source;
        } catch (java.io.IOException failure) {
            throw new GlException("cannot read underwater volume shader", failure);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            screen.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
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
        if (closed) throw new GlException("underwater volume pass is closed");
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

    /** Full-screen triangle in clip space; the vertex shader only reads position. */
    private static final float[] SCREEN_POSITIONS = {
            -1.0f, -1.0f, 0.0f,
            3.0f, -1.0f, 0.0f,
            -1.0f, 3.0f, 0.0f
    };

    private static final int[] SCREEN_INDICES = {0, 1, 2};
}
