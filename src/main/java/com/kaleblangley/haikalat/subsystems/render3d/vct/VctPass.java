package com.kaleblangley.haikalat.subsystems.render3d.vct;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.ImageAccess;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture3D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_ZERO;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL14.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.GL_TEXTURE_FETCH_BARRIER_BIT;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL44.GL_BUFFER_UPDATE_BARRIER_BIT;

/**
 * Exclusive owner and producer of one bounded voxel-cone-tracing volume.
 *
 * <p><b>Representation.</b> A closed world cube with a {@code 32/64/128}³ base
 * level and a complete RGBA16F mip chain. RGB stores surface radiance and alpha
 * stores coverage; every level is produced by a compute downsample that averages
 * radiance weighted by coverage and keeps the maximum coverage. The volume is
 * voxelized as a one-voxel shell by a conservative compute rasterizer (no
 * hardware ray tracing).</p>
 *
 * <p><b>Consumers.</b> {@link #recordVoxelize} records dirty-region clears,
 * triangle voxelization and affected mip updates. {@link #recordProbeTrace}
 * traces explicit probes into an owned SSBO. {@link #recordComposite} adds
 * per-pixel diffuse and specular indirect light to an HDR target using scene
 * depth. The pass owns the volume, programs, buffers, sampler and screen quad;
 * the colour target and scene depth are borrowed and never resized.</p>
 *
 * <p><b>Lifecycle.</b> {@link VctSettings#disabled()} allocates no GL resource
 * and every record entry point is a zero-command no-op. {@link #reconfigure}
 * first builds a complete candidate runtime, then publishes it and retires the
 * previous generation; a failed candidate leaves the published generation
 * active. {@link #close()} is idempotent and aggregates close failures.</p>
 */
public final class VctPass implements AutoCloseable {
    /** RenderGraph pass name reserved for integration. */
    public static final String PASS_NAME = "VctPass";
    /** Image unit used by the voxelizer/mip chain. */
    public static final int IMAGE_UNIT_SOURCE = 0;
    /** Second image unit used by the mip downsample. */
    public static final int IMAGE_UNIT_DESTINATION = 1;
    /** Base-level image unit used by the volume owner. */
    public static final int VOXEL_IMAGE_UNIT = 0;
    /** Storage binding of the triangle snapshot in the voxelizer. */
    public static final int TRIANGLE_BINDING = 0;
    /** Storage binding of probe input/output in the cone trace. */
    public static final int PROBE_INPUT_BINDING = 0;
    public static final int PROBE_RESULT_BINDING = 1;
    /** Texture unit of the borrowed scene depth in the composite. */
    public static final int DEPTH_UNIT = 0;
    /** Texture unit of the voxel volume in the composite. */
    public static final int VOLUME_UNIT = 1;
    /** Texture unit of the voxel volume in the probe trace. */
    public static final int TRACE_VOLUME_UNIT = 0;

    private static final String CLEAR_SHADER = "/shaders/render3d/vct/vct-clear.comp";
    private static final String VOXELIZE_SHADER = "/shaders/render3d/vct/vct-voxelize.comp";
    private static final String MIP_SHADER = "/shaders/render3d/vct/vct-mip.comp";
    private static final String TRACE_SHADER = "/shaders/render3d/vct/vct-trace.comp";
    private static final String COMPOSITE_VERTEX = "/shaders/render3d/vct/vct-composite.vert";
    private static final String COMPOSITE_FRAGMENT = "/shaders/render3d/vct/vct-composite.frag";
    private static final int IMAGE_WORKGROUP = 4;
    private static final int VOXELIZE_WORKGROUP = 64;
    private static final int TRACE_WORKGROUP = 8;

    private VctSettings settings;
    private Runtime runtime;
    private UpdateStats lastUpdateStats = UpdateStats.NONE;
    private long revision;
    private boolean closed;

    /**
     * Creates the pass. An enabled configuration allocates the complete runtime
     * on the current GPU owner thread; a disabled configuration allocates
     * nothing and remains a no-op until {@link #reconfigure} enables it.
     */
    public VctPass(VctSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (settings.enabled()) {
            this.runtime = new Runtime(settings);
        }
    }

    /** @return the currently published settings (the last accepted configuration) */
    public VctSettings settings() {
        return settings;
    }

    /** @return whether an enabled runtime is currently published */
    public boolean active() {
        return !closed && runtime != null;
    }

    /** @return successful configuration publications, starting at zero */
    public long revision() {
        return revision;
    }

    /** @return the latest update result; {@link UpdateStats#NONE} before the first update */
    public UpdateStats lastUpdateStats() {
        return lastUpdateStats;
    }

    /** @return the owned base-level volume texture, or {@code null} while disabled/closed */
    public Texture3D volumeTexture() {
        return closed || runtime == null ? null : runtime.volume.texture;
    }

    /** @return owned volume plus upload/readback buffer bytes, or 0 while disabled/closed */
    public long volumeBytes() {
        return closed || runtime == null ? 0L : runtime.volume.bytes;
    }

    /** @return whether the published volume has completed at least one update */
    public boolean volumeValid() {
        return !closed && runtime != null && runtime.volumeValid;
    }

    /**
     * @return the owned probe result buffer (two RGBA rows per probe)
     * @throws IllegalStateException while disabled or closed
     */
    public GlBuffer probeResults() {
        ensureOpen();
        if (runtime == null) throw new IllegalStateException("voxel cone tracing is disabled");
        return runtime.volume.probeOutput;
    }

    /**
     * Publishes a new configuration transactionally.
     *
     * <p>Resource-compatible changes update trace/update parameters in place.
     * A resolution or memory-budget change builds a complete candidate runtime
     * first, then swaps and retires the old one. Disabling closes all dedicated
     * resources. A failed candidate leaves the previous runtime and settings
     * active; a post-publish retirement failure reports both facts.</p>
     */
    public void reconfigure(VctSettings next) {
        ensureOpen();
        Objects.requireNonNull(next, "next");
        if (next.equals(settings)) return;
        Runtime previous = runtime;
        if (!next.enabled()) {
            runtime = null;
            settings = next;
            revision++;
            lastUpdateStats = UpdateStats.NONE;
            if (previous != null) previous.close();
            return;
        }
        boolean resourcesCompatible = previous != null
                && previous.settings.resolution() == next.resolution()
                && previous.settings.memoryBudgetBytes() == next.memoryBudgetBytes();
        if (resourcesCompatible) {
            boolean boundsChanged = !previous.settings.center().equals(next.center())
                    || previous.settings.halfExtent() != next.halfExtent();
            boolean shellChanged = previous.settings.thinWallDilation()
                    != next.thinWallDilation();
            previous.settings = next;
            previous.boundsMinimum = VctVoxelMath.boundsMinimum(next);
            previous.voxelSize = VctVoxelMath.voxelSize(next);
            previous.maxDistance = Math.max(previous.voxelSize, next.halfExtent() * 3.0f);
            previous.traceMaxMip = traceMaxMip(next, previous.voxelSize,
                    previous.maxDistance);
            if (boundsChanged || shellChanged) {
                previous.volumeValid = false;
                lastUpdateStats = UpdateStats.NONE;
            }
            settings = next;
            revision++;
            return;
        }
        Runtime candidate = new Runtime(next);
        runtime = candidate;
        settings = next;
        revision++;
        lastUpdateStats = UpdateStats.NONE;
        if (previous != null) {
            try {
                previous.close();
            } catch (RuntimeException retirementFailure) {
                throw new IllegalStateException(
                        "new voxel runtime is active but the previous generation failed to retire",
                        retirementFailure);
            }
        }
    }

    /**
     * Records one bounded voxel update.
     *
     * <p>{@code dirtyBoxes} must bound every scene change since the last
     * successful update. An empty list with a new scene revision is treated as
     * a full update (the safe choice), while an empty list with an unchanged
     * revision records nothing. A full update is also forced before the first
     * successful update and after a bounds change. The update budget is checked
     * before any command is recorded.</p>
     *
     * @return a description of what this call actually recorded
     */
    public UpdateStats recordVoxelize(CommandBuffer cmd, VctSceneInput scene,
                                      List<VctBox> dirtyBoxes) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(scene, "scene");
        if (runtime == null) {
            lastUpdateStats = UpdateStats.NONE;
            return lastUpdateStats;
        }
        List<VctBox> boxes = dirtyBoxes == null ? List.of() : List.copyOf(dirtyBoxes);
        Runtime active = runtime;
        boolean sceneChanged = active.sceneRevision != scene.revision();
        boolean forceFull = !active.volumeValid || (sceneChanged && boxes.isEmpty());
        VctUpdatePlanner.Plan plan = VctUpdatePlanner.plan(active.settings, boxes, forceFull);
        if (plan.empty()) {
            lastUpdateStats = UpdateStats.NONE;
            return lastUpdateStats;
        }

        int triangles = scene.triangleCount();
        if (triangles > 0) {
            uploadFloats(cmd, active.volume.triangles, scene.packedInternal(),
                    triangles * VctSceneInput.FLOATS_PER_TRIANGLE, active.uploadScratch);
            cmd.memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            cmd.bindStorageBuffer(TRIANGLE_BINDING, active.volume.triangles, 0L,
                    VctVolume.TRIANGLE_BYTES);
        }

        int dispatches = 0;
        for (VctUpdatePlanner.VoxelRegion region : plan.regions()) {
            recordClear(cmd, active, region);
            dispatches++;
            if (triangles > 0) {
                recordVoxelizeRegion(cmd, active, region, triangles);
                dispatches++;
            }
        }
        int mipDispatches = recordMipChain(cmd, active, plan);
        dispatches += mipDispatches;

        active.volumeValid = true;
        active.sceneRevision = scene.revision();
        lastUpdateStats = new UpdateStats(plan.full(), plan.regions().size(),
                plan.baseVoxels(), plan.mipVoxels(), dispatches, triangles, false);
        return lastUpdateStats;
    }

    /**
     * Records a GPU cone trace for a bounded probe batch into the owned result
     * buffer. The volume must have completed an update.
     *
     * @return number of compute dispatches recorded, or 0 while disabled
     */
    public int recordProbeTrace(CommandBuffer cmd, VctProbeBatch probes) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(probes, "probes");
        if (runtime == null) return 0;
        Runtime active = runtime;
        if (!active.volumeValid) {
            throw new IllegalStateException("voxelize before tracing cones");
        }
        if (probes.count() == 0) return 0;
        uploadFloats(cmd, active.volume.probeInput, probes.packedInternal(),
                probes.count() * VctProbeBatch.FLOATS_PER_PROBE, active.uploadScratch);
        cmd.memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        ShaderProgram trace = active.traceProgram;
        cmd.bindStorageBuffer(PROBE_INPUT_BINDING, active.volume.probeInput, 0L,
                        VctVolume.PROBE_INPUT_BYTES)
                .bindStorageBuffer(PROBE_RESULT_BINDING, active.volume.probeOutput, 0L,
                        VctVolume.PROBE_OUTPUT_BYTES)
                .bindShader(trace)
                .bindTexture3D(TRACE_VOLUME_UNIT, active.volume.texture, active.volumeSampler)
                .setUniformInt(trace, "uVoxels", TRACE_VOLUME_UNIT)
                .setUniformInt(trace, "uProbeCount", probes.count())
                .setUniformVec3(trace, "uBoundsMinimum", active.boundsMinimum)
                .setUniformFloat(trace, "uHalfExtent", active.settings.halfExtent())
                .setUniformInt(trace, "uResolution", active.settings.resolution())
                .setUniformInt(trace, "uMaxMip", active.traceMaxMip)
                .setUniformInt(trace, "uDiffuseCones", active.settings.diffuseCones())
                .setUniformInt(trace, "uSpecularCones", active.settings.specularCones())
                .setUniformFloat(trace, "uDiffuseAperture", active.settings.diffuseAperture())
                .setUniformFloat(trace, "uSpecularApertureScale",
                        active.settings.specularApertureScale())
                .setUniformFloat(trace, "uMaxDistance", active.maxDistance)
                .setUniformInt(trace, "uMaxSteps", active.settings.maxTraceSteps())
                .setUniformFloat(trace, "uOpacityThreshold", active.settings.opacityThreshold())
                .setUniformFloat(trace, "uNormalBiasVoxels", active.settings.normalBiasVoxels())
                .dispatchCompute(groups(probes.count(), TRACE_WORKGROUP), 1, 1)
                .memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
        return 1;
    }

    /**
     * Adds per-pixel voxel indirect light to an HDR colour target with additive
     * blending. The target and depth texture are borrowed; the caller keeps
     * ownership and must supply a single-sample target and matching depth.
     *
     * @param cmd               GPU-owner command buffer
     * @param colorTarget       resolved single-sample HDR target
     * @param sceneDepthTexture 2D depth texture matching the camera
     * @param camera            frozen camera for the depth target
     * @param surfaceRoughness  scene-wide roughness used for the specular cone
     * @param surfaceAlbedo     scene-wide diffuse albedo
     */
    public void recordComposite(CommandBuffer cmd, Framebuffer colorTarget,
                                int sceneDepthTexture, ExternalCamera camera,
                                float surfaceRoughness, Vector3f surfaceAlbedo) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        if (runtime == null) return;
        Objects.requireNonNull(colorTarget, "colorTarget");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(surfaceAlbedo, "surfaceAlbedo");
        colorTarget.ensureOpen();
        Runtime active = runtime;
        if (!active.volumeValid) {
            throw new IllegalStateException("voxelize before compositing indirect light");
        }
        if (sceneDepthTexture <= 0) {
            throw new IllegalArgumentException("voxel composite requires a scene depth texture");
        }
        if (colorTarget.multisampled()) {
            throw new IllegalArgumentException(
                    "voxel composite requires a resolved single-sample colour target");
        }
        if (!Float.isFinite(surfaceRoughness) || surfaceRoughness < 0.0f
                || surfaceRoughness > 1.0f) {
            throw new IllegalArgumentException("surfaceRoughness must be in [0,1]");
        }
        if (!surfaceAlbedo.isFinite() || surfaceAlbedo.x < 0.0f
                || surfaceAlbedo.y < 0.0f || surfaceAlbedo.z < 0.0f) {
            throw new IllegalArgumentException("surfaceAlbedo must be finite and non-negative");
        }
        ShaderProgram composite = active.compositeProgram;
        cmd.bindFramebuffer(colorTarget)
                .viewport(0, 0, colorTarget.width(), colorTarget.height())
                .enableScissor(false)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableBlend(true)
                .blendEquation(GL_FUNC_ADD)
                .blendFuncSeparate(GL_ONE, GL_ONE, GL_ZERO, GL_ONE)
                .bindShader(composite)
                .bindTexture(DEPTH_UNIT, sceneDepthTexture)
                .bindSampler(DEPTH_UNIT, null)
                .bindTexture3D(VOLUME_UNIT, active.volume.texture, active.volumeSampler)
                .setUniformInt(composite, "uSceneDepth", DEPTH_UNIT)
                .setUniformInt(composite, "uVoxels", VOLUME_UNIT)
                .setUniformVec3(composite, "uCameraPosition", camera.position())
                .setUniformMat4(composite, "uInverseProjection", camera.inverseProjection())
                .setUniformMat4(composite, "uInverseView", camera.inverseView())
                .setUniformVec3(composite, "uBoundsMinimum", active.boundsMinimum)
                .setUniformFloat(composite, "uHalfExtent", active.settings.halfExtent())
                .setUniformInt(composite, "uResolution", active.settings.resolution())
                .setUniformInt(composite, "uMaxMip", active.traceMaxMip)
                .setUniformInt(composite, "uDiffuseCones", active.settings.diffuseCones())
                .setUniformFloat(composite, "uDiffuseAperture",
                        active.settings.diffuseAperture())
                .setUniformFloat(composite, "uSpecularApertureScale",
                        active.settings.specularApertureScale())
                .setUniformFloat(composite, "uMaxDistance", active.maxDistance)
                .setUniformInt(composite, "uMaxSteps", active.settings.maxTraceSteps())
                .setUniformFloat(composite, "uOpacityThreshold",
                        active.settings.opacityThreshold())
                .setUniformFloat(composite, "uNormalBiasVoxels",
                        active.settings.normalBiasVoxels())
                .setUniformFloat(composite, "uSurfaceRoughness", surfaceRoughness)
                .setUniformVec3(composite, "uSurfaceAlbedo", surfaceAlbedo)
                .setUniformFloat(composite, "uDiffuseIntensity",
                        active.settings.diffuseIntensity())
                .setUniformFloat(composite, "uSpecularIntensity",
                        active.settings.specularIntensity())
                .bindVertexArray(active.screen.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableBlend(false)
                .depthMask(true)
                .enableDepthTest(true)
                .enableCullFace(true);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Runtime previous = runtime;
        runtime = null;
        if (previous != null) previous.close();
    }

    private void recordClear(CommandBuffer cmd, Runtime active,
                             VctUpdatePlanner.VoxelRegion region) {
        ShaderProgram clear = active.clearProgram;
        cmd.bindShader(clear)
                .bindImageTexture(VOXEL_IMAGE_UNIT, active.volume.texture, 0,
                        ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                .setUniformVec3(clear, "uRegionMin", uniformScratch.set(
                        region.minX(), region.minY(), region.minZ()))
                .setUniformVec3(clear, "uRegionSize", uniformScratch.set(
                        region.sizeX(), region.sizeY(), region.sizeZ()))
                .dispatchCompute(groups(region.sizeX(), IMAGE_WORKGROUP),
                        groups(region.sizeY(), IMAGE_WORKGROUP),
                        groups(region.sizeZ(), IMAGE_WORKGROUP))
                .memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                        | GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    private void recordVoxelizeRegion(CommandBuffer cmd, Runtime active,
                                      VctUpdatePlanner.VoxelRegion region, int triangles) {
        ShaderProgram voxelize = active.voxelizeProgram;
        cmd.bindShader(voxelize)
                .bindImageTexture(VOXEL_IMAGE_UNIT, active.volume.texture, 0,
                        ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                .setUniformInt(voxelize, "uTriangleCount", triangles)
                .setUniformInt(voxelize, "uResolution", active.settings.resolution())
                .setUniformVec3(voxelize, "uBoundsMinimum", active.boundsMinimum)
                .setUniformFloat(voxelize, "uVoxelSize", active.voxelSize)
                .setUniformVec3(voxelize, "uRegionMin", uniformScratch.set(
                        region.minX(), region.minY(), region.minZ()))
                .setUniformVec3(voxelize, "uRegionSize", uniformScratch.set(
                        region.sizeX(), region.sizeY(), region.sizeZ()))
                .setUniformInt(voxelize, "uDilation", active.settings.thinWallDilation())
                .setUniformFloat(voxelize, "uOccupancy", 1.0f)
                .dispatchCompute(groups(triangles, VOXELIZE_WORKGROUP), 1, 1)
                .memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                        | GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    private int recordMipChain(CommandBuffer cmd, Runtime active,
                               VctUpdatePlanner.Plan plan) {
        int dispatches = 0;
        int resolution = active.settings.resolution();
        int levels = active.settings.mipLevels();
        ShaderProgram mip = active.mipProgram;
        for (int level = 1; level < levels; level++) {
            for (VctUpdatePlanner.VoxelRegion baseRegion : plan.regions()) {
                VctUpdatePlanner.VoxelRegion region = baseRegion.scaledToLevel(resolution, level);
                int sourceDimension = VctVoxelMath.levelDimension(resolution, level - 1);
                cmd.bindShader(mip)
                        .bindImageTexture(IMAGE_UNIT_SOURCE, active.volume.texture, level - 1,
                                ImageAccess.READ_ONLY, RenderFormat.RGBA16F)
                        .bindImageTexture(IMAGE_UNIT_DESTINATION, active.volume.texture, level,
                                ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                        .setUniformVec3(mip, "uRegionMin", uniformScratch.set(
                                region.minX(), region.minY(), region.minZ()))
                        .setUniformVec3(mip, "uRegionSize", uniformScratch.set(
                                region.sizeX(), region.sizeY(), region.sizeZ()))
                        .setUniformVec3(mip, "uSourceSize", uniformScratch.set(
                                sourceDimension, sourceDimension, sourceDimension))
                        .dispatchCompute(groups(region.sizeX(), IMAGE_WORKGROUP),
                                groups(region.sizeY(), IMAGE_WORKGROUP),
                                groups(region.sizeZ(), IMAGE_WORKGROUP));
                dispatches++;
            }
            cmd.memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GL_TEXTURE_FETCH_BARRIER_BIT);
        }
        return dispatches;
    }

    private static void uploadFloats(CommandBuffer cmd, GlBuffer buffer, float[] data,
                                     int floatCount, ByteBuffer scratch) {
        if (floatCount <= 0) return;
        scratch.clear();
        FloatBuffer floats = scratch.asFloatBuffer();
        floats.put(data, 0, floatCount);
        scratch.position(0);
        scratch.limit(Math.multiplyExact(floatCount, Float.BYTES));
        cmd.uploadBufferRegion(buffer, 0L, scratch);
    }

    /** Scratch only mutated while a GPU-owner thread records commands. */
    private static final Vector3f uniformScratch = new Vector3f();

    private static int groups(int count, int workgroup) {
        return Math.max(1, (count + workgroup - 1) / workgroup);
    }

    /**
     * Highest mip a cone can need at the configured maximum distance and the
     * widest accepted specular aperture. Clamping the shader's {@code uMaxMip}
     * here keeps sampling inside the chain while the extra level covers the
     * worst-case footprint interpolation.
     */
    private static int traceMaxMip(VctSettings settings, float voxelSize, float maxDistance) {
        float widestAperture = VctConeMath.apertureForRoughness(1.0f,
                settings.specularApertureScale());
        float widestRadius = maxDistance * (float) Math.tan(widestAperture);
        int needed = (int) Math.ceil(VctConeMath.mipLevelForCone(widestRadius, voxelSize)) + 1;
        return Math.max(0, Math.min(settings.mipLevels() - 1, needed));
    }

    private void ensureOpen() {
        if (closed) throw new GlException("voxel cone tracing pass is closed");
    }

    /** @return packaged shader source; used by the shader-contract test */
    static String shaderSource(String path) {
        try (InputStream stream = VctPass.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("missing voxel shader " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read voxel shader " + path, failure);
        }
    }

    /** Result of one {@link #recordVoxelize} call. */
    public record UpdateStats(boolean full, int regions, long baseVoxels, long mipVoxels,
                              int dispatches, int triangles, boolean noOp) {
        public static final UpdateStats NONE =
                new UpdateStats(false, 0, 0L, 0L, 0, 0, true);

        public long totalVoxels() {
            return baseVoxels + mipVoxels;
        }
    }

    private static final class Runtime implements AutoCloseable {
        VctSettings settings;
        final VctVolume volume;
        final ShaderProgram clearProgram;
        final ShaderProgram voxelizeProgram;
        final ShaderProgram mipProgram;
        final ShaderProgram traceProgram;
        final ShaderProgram compositeProgram;
        final ScreenQuad screen;
        final Sampler volumeSampler;
        final ByteBuffer uploadScratch;
        Vector3f boundsMinimum;
        float voxelSize;
        float maxDistance;
        int traceMaxMip;
        boolean volumeValid;
        long sceneRevision = Long.MIN_VALUE;

        Runtime(VctSettings settings) {
            this.settings = settings;
            VctVolume createdVolume = null;
            ShaderProgram clear = null;
            ShaderProgram voxelize = null;
            ShaderProgram mip = null;
            ShaderProgram trace = null;
            ShaderProgram composite = null;
            ScreenQuad createdScreen = null;
            Sampler createdSampler = null;
            try {
                createdVolume = new VctVolume(settings);
                clear = ShaderProgram.fromComputeResource(VctPass.class, CLEAR_SHADER);
                voxelize = ShaderProgram.fromComputeResource(VctPass.class, VOXELIZE_SHADER);
                mip = ShaderProgram.fromComputeResource(VctPass.class, MIP_SHADER);
                trace = ShaderProgram.fromComputeResource(VctPass.class, TRACE_SHADER);
                composite = ShaderProgram.fromResource(VctPass.class,
                        COMPOSITE_VERTEX, COMPOSITE_FRAGMENT);
                createdScreen = new ScreenQuad();
                createdSampler = Sampler.create(new Sampler.Descriptor(
                        GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                        GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
                volume = createdVolume;
                clearProgram = clear;
                voxelizeProgram = voxelize;
                mipProgram = mip;
                traceProgram = trace;
                compositeProgram = composite;
                screen = createdScreen;
                volumeSampler = createdSampler;
                uploadScratch = ByteBuffer.allocateDirect(
                        (int) Math.max(VctVolume.TRIANGLE_BYTES, VctVolume.PROBE_INPUT_BYTES))
                        .order(ByteOrder.nativeOrder());
                boundsMinimum = VctVoxelMath.boundsMinimum(settings);
                voxelSize = VctVoxelMath.voxelSize(settings);
                maxDistance = Math.max(voxelSize, settings.halfExtent() * 3.0f);
                traceMaxMip = traceMaxMip(settings, voxelSize, maxDistance);
            } catch (RuntimeException | Error failure) {
                closeQuietly(createdSampler, failure);
                closeQuietly(createdScreen, failure);
                closeQuietly(composite, failure);
                closeQuietly(trace, failure);
                closeQuietly(mip, failure);
                closeQuietly(voxelize, failure);
                closeQuietly(clear, failure);
                closeQuietly(createdVolume, failure);
                throw failure;
            }
        }

        @Override
        public void close() {
            Throwable failure = null;
            failure = closeCollecting(volumeSampler, failure);
            failure = closeCollecting(screen, failure);
            failure = closeCollecting(compositeProgram, failure);
            failure = closeCollecting(traceProgram, failure);
            failure = closeCollecting(mipProgram, failure);
            failure = closeCollecting(voxelizeProgram, failure);
            failure = closeCollecting(clearProgram, failure);
            failure = closeCollecting(volume, failure);
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure != null) throw new IllegalStateException("voxel runtime cleanup failed", failure);
        }

        private static void closeQuietly(AutoCloseable resource, Throwable primary) {
            if (resource == null) return;
            try {
                resource.close();
            } catch (Exception | Error cleanup) {
                primary.addSuppressed(cleanup);
            }
        }

        private static Throwable closeCollecting(AutoCloseable resource, Throwable failure) {
            if (resource == null) return failure;
            try {
                resource.close();
            } catch (Exception | Error cleanup) {
                if (failure == null) return cleanup;
                failure.addSuppressed(cleanup);
            }
            return failure;
        }
    }
}
