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
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.UniformKey;
import com.kaleblangley.haikalat.core.material.UniformValue;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneDeformationDefaults;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneDrawBinding;
import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderQueueClass;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneFrame;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import java.util.Objects;

import static org.lwjgl.opengl.GL30.GL_RGBA16F;

/**
 * Owner and producer of the dynamic DDGI probe grid.
 *
 * <p><b>Ray mechanism.</b> OpenGL 4.6 exposes no hardware ray-tracing API in
 * this codebase, and the engine's framebuffer abstraction has no cubemap render
 * target. The pass therefore rasterizes six 90-degree depth faces per scheduled
 * probe into one 2D depth atlas (probe p and face f occupy the pixel columns
 * {@code [p * 6 * R + f * R, p * 6 * R + (f + 1) * R)} at rows {@code 0..R-1}).
 * The relight stage then casts one world-space ray per irradiance texel and
 * marches it through that rasterized depth. This is a real scene ray with
 * world-space occlusion; it is not a static bake, not screen-space occlusion
 * and not a hardware RT path.</p>
 *
 * <p><b>Update contract.</b> Each call to {@link #recordUpdate} refreshes at
 * most {@link DdgiSettings#updateBudget()} probes in deterministic round-robin
 * order. The complete probe depth atlas is cleared and only scheduled probes
 * are rasterized, so a probe that is not scheduled keeps its previous
 * irradiance. Radiance is a bounded analytic estimate: the probe's rasterized
 * surface depth gives the hit position and a depth-derived normal, one main
 * directional light gives the direct term, the previous irradiance atlas gives
 * one bounce, and a constant sky term covers rays with no recorded hit. The new
 * value is blended with the previous atlas value using
 * {@link DdgiSettings#hysteresis()} and clamped to
 * {@link DdgiSettings#maximumIrradiance()}.</p>
 *
 * <p><b>Ownership.</b> The pass owns the depth atlas, both irradiance atlas
 * textures (double buffered for the temporal blend), the three shader programs,
 * the screen mesh and the deformation defaults. It borrows nothing. All
 * resources are created before the constructor publishes the instance; a
 * construction failure closes the candidate resources and rethrows. {@link
 * #close()} is idempotent and aggregates close failures. Disabled settings are
 * rejected before any GL allocation, which is the exact no-op contract.</p>
 *
 * <p><b>Known limitations.</b> Instanced renderers are not captured; the main
 * light is the only direct-light source; materials, emissive surfaces, shadow
 * maps and the clustered light table are not consumed; the depth-derived normal
 * can be wrong on thin or grazing geometry; one ray per irradiance texel and a
 * fixed 4x4 irradiance face are the bounded quality of this first version. All
 * of these are documented in {@code docs/planning/v0.28.1-s5-ddgi.md}.</p>
 */
public final class DdgiProbeVolume implements GlResource {
    /** RenderGraph pass name used by integration. */
    public static final String PASS_NAME = "DdgiProbePass";
    /** Texture unit of the probe depth atlas during the relight stage. */
    public static final int DEPTH_ATLAS_UNIT = 0;
    /** Texture unit of the previous/current irradiance atlas during relight. */
    public static final int IRRADIANCE_ATLAS_UNIT = 1;

    /** Smallest accepted probe depth near plane in world metres. */
    public static final float MINIMUM_DEPTH_NEAR = 0.02f;
    /** Largest accepted probe depth near plane in world metres. */
    public static final float MAXIMUM_DEPTH_NEAR = 1.0f;
    /** Relative spacing factor used to derive the probe depth near plane. */
    public static final float DEPTH_NEAR_SPACING_FACTOR = 0.01f;

    private final DdgiSettings settings;
    private final int probeCount;
    private final float depthNear;
    private final float depthFar;
    private final ShaderProgram probeDepthProgram;
    private final ShaderProgram probeMaskedDepthProgram;
    private final ShaderProgram relightProgram;
    private final Mesh screen;
    private final SceneDeformationDefaults deformationDefaults;
    private final Framebuffer probeDepthTarget;
    private final Framebuffer irradianceA;
    private final Framebuffer irradianceB;
    private final Matrix4f faceProjection = new Matrix4f();
    private final Matrix4f faceView = new Matrix4f();
    private final Matrix4f faceViewProjection = new Matrix4f();
    private final Matrix4f model = new Matrix4f();
    private final Vector3f probePosition = new Vector3f();
    private final Vector3f faceForward = new Vector3f();
    private final Vector3f faceUpHint = new Vector3f();
    private final Vector3f light = new Vector3f();
    private final Vector3f radiance = new Vector3f();
    private final Vector3f sky = new Vector3f();
    private final Vector3f gridOrigin = new Vector3f();
    private final int[] scheduled;

    private Framebuffer irradianceRead;
    private Framebuffer irradianceWrite;
    private boolean initialized;
    private int cursor = -1;
    private int updatedCount;
    private boolean closed;

    /**
     * Creates the complete active probe owner.
     *
     * @param settings active bounded configuration
     * @throws IllegalArgumentException when settings are disabled or invalid
     */
    public DdgiProbeVolume(DdgiSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("DDGI is disabled");
        }
        this.probeCount = settings.probeCount();
        this.scheduled = new int[probeCount];
        this.depthNear = Math.min(MAXIMUM_DEPTH_NEAR,
                Math.max(MINIMUM_DEPTH_NEAR,
                        settings.spacingMetres() * DEPTH_NEAR_SPACING_FACTOR));
        this.depthFar = settings.maximumRayDistance() + settings.spacingMetres();
        faceProjection.perspective((float) Math.toRadians(90.0), 1.0f, depthNear, depthFar);
        ShaderProgram createdDepthProgram = null;
        ShaderProgram createdMaskedDepthProgram = null;
        ShaderProgram createdRelightProgram = null;
        Mesh createdScreen = null;
        SceneDeformationDefaults createdDefaults = null;
        Framebuffer createdDepthTarget = null;
        Framebuffer createdIrradianceA = null;
        Framebuffer createdIrradianceB = null;
        try {
            createdDepthProgram = ShaderProgram.builder()
                    .resource(DdgiProbeVolume.class, ShaderStage.VERTEX,
                            "/shaders/shadows/directional-depth.vert")
                    .resource(DdgiProbeVolume.class, ShaderStage.FRAGMENT,
                            "/shaders/shadows/directional-depth.frag")
                    .link();
            createdMaskedDepthProgram = ShaderProgram.builder()
                    .resource(DdgiProbeVolume.class, ShaderStage.VERTEX,
                            "/shaders/shadows/masked-directional-depth.vert")
                    .resource(DdgiProbeVolume.class, ShaderStage.FRAGMENT,
                            "/shaders/shadows/masked-directional-depth.frag")
                    .link();
            createdRelightProgram = ShaderProgram.builder()
                    .resource(DdgiProbeVolume.class, ShaderStage.VERTEX,
                            DdgiShaderSource.ROOT + "ddgi-fullscreen.vert")
                    .stage(ShaderStage.FRAGMENT, DdgiShaderSource.fragmentSource("ddgi-relight.frag"),
                            "ddgi-relight.frag")
                    .link();
            createdScreen = screenMesh();
            createdDefaults = new SceneDeformationDefaults();
            createdDepthTarget = Framebuffer.fromDescriptor(FramebufferDescriptor
                    .builder(settings.probeDepthAtlasWidth(), settings.probeDepthAtlasHeight())
                    .depthTexture()
                    .build());
            createdIrradianceA = irradianceTarget(settings);
            createdIrradianceB = irradianceTarget(settings);
            probeDepthProgram = createdDepthProgram;
            probeMaskedDepthProgram = createdMaskedDepthProgram;
            relightProgram = createdRelightProgram;
            screen = createdScreen;
            deformationDefaults = createdDefaults;
            probeDepthTarget = createdDepthTarget;
            irradianceA = createdIrradianceA;
            irradianceB = createdIrradianceB;
            irradianceRead = createdIrradianceA;
            irradianceWrite = createdIrradianceB;
        } catch (RuntimeException | Error failure) {
            closeSuppressed(createdIrradianceB, failure);
            closeSuppressed(createdIrradianceA, failure);
            closeSuppressed(createdDepthTarget, failure);
            closeSuppressed(createdDefaults, failure);
            closeSuppressed(createdScreen, failure);
            closeSuppressed(createdRelightProgram, failure);
            closeSuppressed(createdMaskedDepthProgram, failure);
            closeSuppressed(createdDepthProgram, failure);
            throw failure;
        }
    }

    public DdgiSettings settings() {
        return settings;
    }

    /** @return total probe count in the grid */
    public int probeCount() {
        return probeCount;
    }

    /** @return probe depth near plane in world metres */
    public float probeDepthNear() {
        return depthNear;
    }

    /** @return probe depth far plane in world metres */
    public float probeDepthFar() {
        return depthFar;
    }

    /** @return probe depth atlas width in texels */
    public int probeDepthAtlasWidth() {
        return settings.probeDepthAtlasWidth();
    }

    /** @return probe depth atlas height in texels */
    public int probeDepthAtlasHeight() {
        return settings.probeDepthAtlasHeight();
    }

    /** @return irradiance atlas width in texels */
    public int irradianceAtlasWidth() {
        return settings.irradianceAtlasWidth();
    }

    /** @return irradiance atlas height in texels */
    public int irradianceAtlasHeight() {
        return DdgiSettings.IRRADIANCE_TILE_HEIGHT;
    }

    /** @return number of probes refreshed by the most recent update */
    public int updatedProbeCount() {
        return closed ? 0 : updatedCount;
    }

    /**
     * @param index position in {@code [0, updatedProbeCount())}
     * @return probe index refreshed by the most recent update at that position
     */
    public int updatedProbeIndex(int index) {
        ensureOpen();
        if (index < 0 || index >= updatedCount) {
            throw new IndexOutOfBoundsException("updated probe index out of range: " + index);
        }
        return scheduled[index];
    }

    /** @return current probe depth atlas texture id, or 0 after close */
    public int probeDepthTexture() {
        return closed || probeDepthTarget == null ? 0 : probeDepthTarget.depthAttachment();
    }

    /** @return current irradiance atlas texture id, or 0 after close */
    public int irradianceTexture() {
        return closed || irradianceRead == null ? 0 : irradianceRead.colorAttachment();
    }

    /**
     * Borrowed view of the probe depth target for diagnostics and readback.
     * The caller must not close it or mutate its attachments; ownership stays
     * with this pass.
     */
    public Framebuffer probeDepthTarget() {
        ensureOpen();
        return probeDepthTarget;
    }

    /**
     * Borrowed view of the current irradiance atlas target for diagnostics and
     * readback. The identity changes after every update because of the
     * double-buffered temporal blend; snapshot it again after each update.
     */
    public Framebuffer irradianceTarget() {
        ensureOpen();
        return irradianceRead;
    }

    /**
     * Rasterizes the probe depth faces for the scheduled probes and relights
     * their irradiance tiles.
     *
     * @param cmd           GPU-owner command buffer
     * @param frame         current read-only scene draw arena
     * @param lightDirection direction the main light travels (world space, non-zero)
     * @param lightRadiance  incident main-light radiance (finite, non-negative)
     * @return the number of probes refreshed by this call
     */
    public int recordUpdate(CommandBuffer cmd, SceneFrame frame, Vector3fc lightDirection,
                            Vector3fc lightRadiance) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(frame, "frame");
        requireLight(lightDirection, lightRadiance);
        int count = DdgiProbeBudget.writeSchedule(probeCount, settings.updateBudget(),
                cursor, scheduled);
        cursor = scheduled[count - 1];
        updatedCount = count;

        recordProbeDepths(cmd, frame);
        cmd.blitColor(irradianceRead, irradianceWrite);
        recordRelight(cmd);

        Framebuffer previousRead = irradianceRead;
        irradianceRead = irradianceWrite;
        irradianceWrite = previousRead;
        cmd.enableBlend(false)
                .enableDepthTest(true)
                .depthMask(true)
                .enableCullFace(true)
                .enableScissor(false);
        return count;
    }

    private void recordProbeDepths(CommandBuffer cmd, SceneFrame frame) {
        int faceResolution = settings.depthFaceResolution();
        int probeTileWidth = DdgiSettings.FACE_COUNT * faceResolution;
        cmd.bindFramebuffer(probeDepthTarget)
                .viewport(0, 0, probeDepthAtlasWidth(), probeDepthAtlasHeight())
                .enableScissor(false)
                .enableBlend(false)
                .enableDepthTest(true)
                .depthMask(true)
                .enableCullFace(false);
        if (!initialized) {
            // First use: every unscheduled probe must read "no surface" instead
            // of the texture's zero-initialized near depth.
            cmd.clear(false, true);
            clearIrradiance(cmd, irradianceA);
            clearIrradiance(cmd, irradianceB);
            cmd.bindFramebuffer(probeDepthTarget)
                    .viewport(0, 0, probeDepthAtlasWidth(), probeDepthAtlasHeight())
                    .enableScissor(false)
                    .enableDepthTest(true)
                    .depthMask(true);
            initialized = true;
        }
        deformationDefaults.record(cmd);
        ShaderProgram boundShader = null;
        int boundSkinningEnabled = -1;
        int boundMorphTargetCount = -1;
        for (int index = 0; index < updatedCount; index++) {
            int probe = scheduled[index];
            probePosition(probe);
            // Preserve every unscheduled probe's previous visibility so the
            // indirect query cannot lose its occlusion gate between updates.
            cmd.enableScissor(true)
                    .scissor(probe * probeTileWidth, 0, probeTileWidth, faceResolution)
                    .clear(false, true)
                    .enableScissor(false);
            for (int face = 0; face < DdgiSettings.FACE_COUNT; face++) {
                buildFaceViewProjection(face);
                int faceX = (probe * DdgiSettings.FACE_COUNT + face) * faceResolution;
                cmd.viewport(faceX, 0, faceResolution, faceResolution);
                Mesh boundMesh = null;
                for (int entry = 0; entry < frame.rendererCount(); entry++) {
                    if (!frame.castsOpaqueShadow(entry)) {
                        continue;
                    }
                    var renderer = frame.renderer(entry);
                    boolean masked = RenderQueueClass.classify(renderer.material())
                            == RenderQueueClass.MASKED;
                    ShaderProgram shader = masked ? probeMaskedDepthProgram : probeDepthProgram;
                    if (shader != boundShader) {
                        cmd.bindShader(shader);
                        boundShader = shader;
                        boundSkinningEnabled = -1;
                        boundMorphTargetCount = -1;
                    }
                    cmd.setUniformMat4(shader, "uLightSpace", faceViewProjection);
                    if (masked) {
                        bindMaskedMaterial(cmd, shader, renderer.material());
                    }
                    frame.copyModel(entry, model);
                    cmd.setUniformMat4(shader, "uModel", model);
                    SceneDrawBinding binding = renderer.drawBinding();
                    binding.recordVertexDisplacement(cmd, shader, SceneDrawBinding.Pass.SHADOW);
                    int skinningEnabled = binding.skinningEnabled() ? 1 : 0;
                    int morphTargetCount = binding.morphTargetCount();
                    if (skinningEnabled != boundSkinningEnabled) {
                        cmd.trySetUniformInt(shader, "uSkinningEnabled", skinningEnabled);
                        boundSkinningEnabled = skinningEnabled;
                    }
                    if (morphTargetCount != boundMorphTargetCount) {
                        cmd.trySetUniformInt(shader, "uMorphTargetCount", morphTargetCount);
                        boundMorphTargetCount = morphTargetCount;
                    }
                    if (binding != SceneDrawBinding.NONE) {
                        binding.record(cmd, shader, (int) frame.frameIndex(),
                                SceneDrawBinding.Pass.SHADOW);
                    }
                    if (renderer.mesh() != boundMesh) {
                        cmd.bindMesh(renderer.mesh());
                        boundMesh = renderer.mesh();
                    }
                    cmd.drawMesh(renderer.mesh());
                }
            }
        }
    }

    private void recordRelight(CommandBuffer cmd) {
        int faceResolution = DdgiSettings.IRRADIANCE_FACE_RESOLUTION;
        int tileWidth = DdgiSettings.IRRADIANCE_TILE_WIDTH;
        int tileHeight = DdgiSettings.IRRADIANCE_TILE_HEIGHT;
        cmd.bindFramebuffer(irradianceWrite)
                .viewport(0, 0, irradianceAtlasWidth(), irradianceAtlasHeight())
                .enableScissor(false)
                .enableBlend(false)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .bindShader(relightProgram)
                .bindTexture(DEPTH_ATLAS_UNIT, probeDepthTexture())
                .bindTexture(IRRADIANCE_ATLAS_UNIT, irradianceRead.colorAttachment())
                .setUniformInt(relightProgram, "uProbeDepthAtlas", DEPTH_ATLAS_UNIT)
                .setUniformInt(relightProgram, "uIrradianceAtlas", IRRADIANCE_ATLAS_UNIT)
                .setUniformInt(relightProgram, "uProbesX", settings.probesX())
                .setUniformInt(relightProgram, "uProbesY", settings.probesY())
                .setUniformInt(relightProgram, "uProbesZ", settings.probesZ())
                .setUniformVec3(relightProgram, "uGridOrigin",
                        gridOrigin.set(settings.gridOriginX(), settings.gridOriginY(),
                                settings.gridOriginZ()))
                .setUniformFloat(relightProgram, "uSpacing", settings.spacingMetres())
                .setUniformFloat(relightProgram, "uBorderFadeMetres",
                        settings.borderFadeMetres())
                .setUniformInt(relightProgram, "uDepthFaceResolution",
                        settings.depthFaceResolution())
                .setUniformFloat(relightProgram, "uDepthNear", depthNear)
                .setUniformFloat(relightProgram, "uDepthFar", depthFar)
                .setUniformInt(relightProgram, "uIrradianceFaceResolution", faceResolution)
                .setUniformInt(relightProgram, "uRaySteps", settings.raySteps())
                .setUniformFloat(relightProgram, "uMaximumRayDistance",
                        settings.maximumRayDistance())
                .setUniformFloat(relightProgram, "uHysteresis", settings.hysteresis())
                .setUniformVec3(relightProgram, "uSkyRadiance", sky)
                .setUniformVec3(relightProgram, "uLightDirection", light)
                .setUniformVec3(relightProgram, "uLightRadiance", radiance)
                .setUniformFloat(relightProgram, "uSurfaceAlbedo", settings.surfaceAlbedo())
                .setUniformFloat(relightProgram, "uMaximumIrradiance",
                        settings.maximumIrradiance())
                .bindMesh(screen);
        for (int index = 0; index < updatedCount; index++) {
            int probe = scheduled[index];
            for (int face = 0; face < DdgiSettings.FACE_COUNT; face++) {
                int faceX = face % DdgiSettings.IRRADIANCE_TILE_COLUMNS;
                int faceY = face / DdgiSettings.IRRADIANCE_TILE_COLUMNS;
                int tileX = probe * tileWidth + faceX * faceResolution;
                int tileY = faceY * faceResolution;
                cmd.viewport(tileX, tileY, faceResolution, faceResolution)
                        .setUniformInt(relightProgram, "uProbeIndex", probe)
                        .setUniformInt(relightProgram, "uFaceIndex", face)
                        .setUniformVec2(relightProgram, "uTileOrigin", tileX, tileY)
                        .drawMesh(screen);
            }
        }
        cmd.enableDepthTest(true);
    }

    private void clearIrradiance(CommandBuffer cmd, Framebuffer target) {
        cmd.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableScissor(false)
                .enableBlend(false)
                .enableDepthTest(false)
                .depthMask(false)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .clear(true, false);
    }

    private void buildFaceViewProjection(int face) {
        faceForward(face, faceForward);
        faceUpHint(face, faceUpHint);
        faceView.setLookAt(probePosition.x, probePosition.y, probePosition.z,
                probePosition.x + faceForward.x,
                probePosition.y + faceForward.y,
                probePosition.z + faceForward.z,
                faceUpHint.x, faceUpHint.y, faceUpHint.z);
        faceViewProjection.set(faceProjection).mul(faceView);
    }

    private void probePosition(int index) {
        int slice = settings.probesX() * settings.probesY();
        int z = index / slice;
        int remainder = index - z * slice;
        int y = remainder / settings.probesX();
        int x = remainder - y * settings.probesX();
        probePosition.set(
                settings.gridOriginX() + (x + 0.5f) * settings.spacingMetres(),
                settings.gridOriginY() + (y + 0.5f) * settings.spacingMetres(),
                settings.gridOriginZ() + (z + 0.5f) * settings.spacingMetres());
    }

    private static void faceForward(int face, Vector3f destination) {
        switch (face) {
            case 0 -> destination.set(1.0f, 0.0f, 0.0f);
            case 1 -> destination.set(-1.0f, 0.0f, 0.0f);
            case 2 -> destination.set(0.0f, 1.0f, 0.0f);
            case 3 -> destination.set(0.0f, -1.0f, 0.0f);
            case 4 -> destination.set(0.0f, 0.0f, 1.0f);
            default -> destination.set(0.0f, 0.0f, -1.0f);
        }
    }

    private static void faceUpHint(int face, Vector3f destination) {
        if (face == 2) {
            destination.set(0.0f, 0.0f, 1.0f);
        } else if (face == 3) {
            destination.set(0.0f, 0.0f, -1.0f);
        } else {
            destination.set(0.0f, -1.0f, 0.0f);
        }
    }

    private void requireLight(Vector3fc direction, Vector3fc lightRadiance) {
        Objects.requireNonNull(direction, "lightDirection");
        Objects.requireNonNull(lightRadiance, "lightRadiance");
        float lengthSquared = direction.x() * direction.x() + direction.y() * direction.y()
                + direction.z() * direction.z();
        if (!Float.isFinite(lengthSquared) || !(lengthSquared > 1.0e-12f)) {
            throw new IllegalArgumentException("lightDirection must be finite and non-zero");
        }
        float inverseLength = 1.0f / (float) Math.sqrt(lengthSquared);
        light.set(direction.x() * inverseLength, direction.y() * inverseLength,
                direction.z() * inverseLength);
        radiance.set(lightRadiance);
        if (!Float.isFinite(radiance.x) || !Float.isFinite(radiance.y)
                || !Float.isFinite(radiance.z)
                || radiance.x < 0.0f || radiance.y < 0.0f || radiance.z < 0.0f
                || radiance.x > DdgiSettings.MAXIMUM_LIGHT_RADIANCE_COMPONENT
                || radiance.y > DdgiSettings.MAXIMUM_LIGHT_RADIANCE_COMPONENT
                || radiance.z > DdgiSettings.MAXIMUM_LIGHT_RADIANCE_COMPONENT) {
            throw new IllegalArgumentException("lightRadiance must be finite and in [0, "
                    + DdgiSettings.MAXIMUM_LIGHT_RADIANCE_COMPONENT + "]");
        }
        settings.skyRadianceTo(sky);
    }

    private static void bindMaskedMaterial(CommandBuffer cmd, ShaderProgram shader,
                                           MaterialInstance instance) {
        Material material = instance.material();
        Material.TextureBinding baseColor = instance.textureOverrides().get(0);
        if (baseColor == null) {
            for (Material.TextureBinding binding : material.defaultTextures()) {
                if (binding.unit() == 0) {
                    baseColor = binding;
                    break;
                }
            }
        }
        if (baseColor != null) {
            cmd.bindTexture(0, baseColor.texture(), baseColor.sampler());
        }
        UniformValue cutoff = instance.uniformOverrides().get(UniformKey.float1("uAlphaCutoff"));
        if (cutoff == null) {
            cutoff = material.defaultUniforms().get(UniformKey.float1("uAlphaCutoff"));
        }
        UniformValue factor = instance.uniformOverrides().get(UniformKey.vec4("uBaseColorFactor"));
        if (factor == null) {
            factor = material.defaultUniforms().get(UniformKey.vec4("uBaseColorFactor"));
        }
        float cutoffValue = cutoff instanceof UniformValue.FloatVal value ? value.value() : 0.5f;
        Vector4f factorValue = factor instanceof UniformValue.Vec4Val value
                ? value.value() : new Vector4f(1.0f);
        cmd.trySetUniformInt(shader, "uBaseColorMap", 0)
                .trySetUniformFloat(shader, "uAlphaCutoff", cutoffValue)
                .trySetUniformVec4(shader, "uBaseColorFactor", factorValue);
    }

    private static Framebuffer irradianceTarget(DdgiSettings settings) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor
                .builder(settings.irradianceAtlasWidth(),
                        DdgiSettings.IRRADIANCE_TILE_HEIGHT)
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

    @Override
    public int id() {
        return irradianceTexture();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public void ensureOpen() {
        if (closed) throw new GlException("DDGI probe volume is closed");
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = close(irradianceWrite, failure);
        irradianceWrite = null;
        failure = close(irradianceRead, failure);
        irradianceRead = null;
        failure = close(probeDepthTarget, failure);
        failure = close(deformationDefaults, failure);
        failure = close(screen, failure);
        failure = close(relightProgram, failure);
        failure = close(probeMaskedDepthProgram, failure);
        failure = close(probeDepthProgram, failure);
        updatedCount = 0;
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException close(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) return failure;
        try {
            resource.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) return closeFailure;
            failure.addSuppressed(closeFailure);
        } catch (Exception closeFailure) {
            if (failure == null) return new GlException("DDGI close failed", closeFailure);
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private static void closeSuppressed(AutoCloseable resource, Throwable primary) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (RuntimeException | Error closeFailure) {
            primary.addSuppressed(closeFailure);
        } catch (Exception closeFailure) {
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
