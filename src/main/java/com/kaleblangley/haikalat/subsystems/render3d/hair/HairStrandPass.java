package com.kaleblangley.haikalat.subsystems.render3d.hair;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * Production owner of a bounded deterministic hair-strand set (roadmap #46).
 *
 * <p>Owns three LOD meshes (high/medium/low) generated procedurally by
 * {@link HairStrandGeometry}, one forward program, one shadow-caster program
 * and one velocity-only program. It never owns the caller's colour target or
 * shadow atlas: the forward record draws into the currently bound colour
 * target, the velocity record draws into the currently bound velocity
 * attachment, and the shadow record draws into the currently bound shadow depth
 * tile, so the existing pipeline keeps ownership of its graph attachments and
 * shadow maps.</p>
 *
 * <h2>Forward contract</h2>
 *
 * <ul>
 *   <li>Inputs: the exact frame {@link ExternalCamera}, a finite model matrix,
 *       the validated settings, one {@link HairStrandLight}, and optionally the
 *       existing directional shadow texture plus its light-space matrix and
 *       bias.</li>
 *   <li>LOD is selected per record from the camera distance and the projected
 *       lock radius in pixels. Geometry is generated once per level at
 *       construction; a record only switches the bound mesh and draw range.</li>
 *   <li>{@link HairStrandSettings.Transparency#CUTOUT} writes depth and ignores
 *       blending. {@link HairStrandSettings.Transparency#SORTED_ALPHA} blends
 *       straight alpha, keeps depth testing, disables depth writes and records
 *       one draw per strand in back-to-front view depth order with a stable
 *       strand-index tie break.</li>
 *   <li>{@code record(..., settings)} with a disabled value records no command
 *       at all and leaves every bound target byte-identical.</li>
 * </ul>
 *
 * <h2>Shadow contract</h2>
 *
 * <p>{@link #recordShadow} records the high-detail mesh into the currently bound
 * depth target with the existing {@code uLightSpace * uModel} convention and an
 * alpha-tested fragment stage. The caster expands the centreline along a
 * light-facing side vector by {@link HairStrandSettings#strandRadiusMetres()},
 * so it casts a real world-space strand silhouette instead of the zero-area
 * forward ribbon. It is designed to be called inside the existing
 * {@code ShadowCasterRecorder} dirty-tile loop rather than to allocate a second
 * shadow map. The forward fragment stage samples the same directional shadow
 * map with a bounded 3x3 PCF so the strands are also receivers; see the S4 #46
 * technical card for the exact bias convention.</p>
 *
 * <h2>Velocity contract</h2>
 *
 * <p>{@link #recordVelocity} records the same ribbons into a caller-bound
 * single-sample RG16F velocity attachment. The vertex stage duplicates the
 * forward screen-space expansion so the rasterized coverage is identical, while
 * the written vector is the unexpanded centreline's stable reprojection
 * difference {@code previousUv - currentUv}. A static lock and camera therefore
 * encode exactly zero. The pass owns no target and no history: the caller
 * supplies the previous camera/model pair and skips the draw when that pair is
 * not usable. CUTOUT strands keep the forward alpha cutoff, so pixels the
 * forward pass discarded retain the opaque receiver's motion vector.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Construction requires active settings and creates every GPU resource
 * before publishing them; a failure closes the partial candidate and rethrows.
 * {@link #resize(int, int)} validates the complete candidate extent before
 * publishing it, so a rejected resize keeps the previous extent. The pass owns
 * no render target and therefore no target is rebuilt on resize. {@link #close}
 * is idempotent, aggregates close failures and releases only pass-owned
 * resources.</p>
 */
public final class HairStrandPass implements GlResource {
    /** RenderGraph pass name reserved for integration. */
    public static final String PASS_NAME = "HairStrandPass";
    /**
     * RenderGraph pass name of the optional velocity-only contribution. It is
     * internal integration, not a public extension point: the pass borrows the
     * shared {@code sceneVelocity} attachment and never owns a target.
     */
    public static final String VELOCITY_PASS_NAME = "HairStrandVelocityPass";
    /** Largest accepted logical viewport edge in pixels. */
    public static final int MAXIMUM_EXTENT = 16_384;
    /** Texture unit of the borrowed directional shadow map (after material units). */
    public static final int DIRECTIONAL_SHADOW_UNIT = 13;

    private static final String SHADER_ROOT = "/shaders/render3d/hair/";
    private static final String FORWARD_VERTEX = SHADER_ROOT + "hair-forward.vert";
    private static final String FORWARD_FRAGMENT = SHADER_ROOT + "hair-forward.frag";
    private static final String SHADOW_VERTEX = SHADER_ROOT + "hair-shadow.vert";
    private static final String SHADOW_FRAGMENT = SHADER_ROOT + "hair-shadow.frag";
    private static final String VELOCITY_VERTEX = SHADER_ROOT + "hair-velocity.vert";
    private static final String VELOCITY_FRAGMENT = SHADER_ROOT + "hair-velocity.frag";
    private static final float MINIMUM_RADIUS = 1.0e-4f;
    private static final float BEHIND_EYE_EPSILON = 1.0e-6f;

    private final HairStrandSettings settings;
    private final HairStrandGeometry.StrandMesh[] levels = new HairStrandGeometry.StrandMesh[3];
    private final Mesh[] meshes = new Mesh[3];
    private final ShaderProgram forwardProgram;
    private final ShaderProgram shadowProgram;
    private final ShaderProgram velocityProgram;
    private final int[] drawOrder;
    private final float[] strandDepths;
    private final Matrix4f viewScratch = new Matrix4f();
    private final Matrix4f projectionScratch = new Matrix4f();
    private final Matrix4f viewProjectionScratch = new Matrix4f();
    private final Matrix4f previousViewScratch = new Matrix4f();
    private final Matrix4f previousProjectionScratch = new Matrix4f();
    private final Matrix4f previousViewProjectionScratch = new Matrix4f();
    private final Matrix4f lightSpaceScratch = new Matrix4f();
    private final Vector3f centerScratch = new Vector3f();
    private final Vector3f cameraScratch = new Vector3f();
    private final Vector3f axisScratch = new Vector3f();
    private final Vector3f worldScratch = new Vector3f();
    private final Matrix4f uniformMatrixScratch = new Matrix4f();
    private int width;
    private int height;
    private HairStrandSettings.Lod lastActiveLod = HairStrandSettings.Lod.HIGH;
    private int lastStrandCount;
    private float lastWidthScale = 1.0f;
    private boolean lastDrawOrderBackToFront;
    private boolean closed;

    /**
     * Creates all three LOD meshes and both shader programs.
     *
     * @param settings active bounded configuration
     * @param width    logical forward viewport width used by the width expansion
     * @param height   logical forward viewport height used by the width expansion
     * @throws IllegalArgumentException when disabled or the extent is invalid
     */
    public HairStrandPass(HairStrandSettings settings, int width, int height) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException(
                    "disabled HairStrandSettings must not create a hair pass or GPU resources");
        }
        validateExtent(width, height);
        drawOrder = new int[settings.strandCountFor(HairStrandSettings.Lod.HIGH)];
        strandDepths = new float[drawOrder.length];
        ShaderProgram createdForward = null;
        ShaderProgram createdShadow = null;
        ShaderProgram createdVelocity = null;
        Mesh[] createdMeshes = new Mesh[3];
        try {
            createdForward = ShaderProgram.fromResource(HairStrandPass.class,
                    FORWARD_VERTEX, FORWARD_FRAGMENT);
            createdShadow = ShaderProgram.fromResource(HairStrandPass.class,
                    SHADOW_VERTEX, SHADOW_FRAGMENT);
            createdVelocity = ShaderProgram.fromResource(HairStrandPass.class,
                    VELOCITY_VERTEX, VELOCITY_FRAGMENT);
            for (HairStrandSettings.Lod lod : HairStrandSettings.Lod.values()) {
                HairStrandGeometry.StrandMesh level = HairStrandGeometry.build(settings, lod);
                levels[lod.ordinal()] = level;
                createdMeshes[lod.ordinal()] = Mesh.from(level.meshData());
            }
            forwardProgram = createdForward;
            shadowProgram = createdShadow;
            velocityProgram = createdVelocity;
            System.arraycopy(createdMeshes, 0, meshes, 0, createdMeshes.length);
            this.width = width;
            this.height = height;
        } catch (RuntimeException | Error failure) {
            for (Mesh mesh : createdMeshes) {
                closeSuppressed(mesh, failure);
            }
            closeSuppressed(createdVelocity, failure);
            closeSuppressed(createdShadow, failure);
            closeSuppressed(createdForward, failure);
            throw failure;
        }
    }

    public HairStrandSettings settings() {
        return settings;
    }

    /** @return current logical viewport width, or 0 after close */
    public int width() {
        return closed ? 0 : width;
    }

    /** @return current logical viewport height, or 0 after close */
    public int height() {
        return closed ? 0 : height;
    }

    /** @return the level selected by the last successful record, HIGH before the first */
    public HairStrandSettings.Lod activeLod() {
        return lastActiveLod;
    }

    /** @return strand count of the last selected level */
    public int activeStrandCount() {
        return lastStrandCount > 0 ? lastStrandCount
                : settings.strandCountFor(lastActiveLod);
    }

    /** @return pixel-width scale used by the last record */
    public float activeWidthScale() {
        return lastWidthScale;
    }

    /**
     * Records the forward strand draws into the currently bound colour target.
     *
     * @param cmd                    GPU-owner command buffer
     * @param camera                 frozen frame camera
     * @param model                  finite model matrix of the lock
     * @param requested              per-frame settings; a disabled value is an exact no-op
     * @param light                  bounded directional lighting
     * @param directionalShadowTexture existing shadow depth texture, or 0
     * @param directionalLightSpace  existing light-space matrix when the texture is non-zero
     * @param shadowBias             existing normalized depth bias
     * @throws IllegalArgumentException on mismatched settings, non-finite matrices or
     *                                  inconsistent shadow inputs
     */
    public void record(CommandBuffer cmd, ExternalCamera camera, Matrix4fc model,
                       HairStrandSettings requested, HairStrandLight light,
                       int directionalShadowTexture, Matrix4fc directionalLightSpace,
                       float shadowBias) {
        record(cmd, camera, model, requested, light, directionalShadowTexture,
                directionalLightSpace, shadowBias, 1.0f / 2048.0f);
    }

    /**
     * Records the forward strand draws with an explicit shadow-map texel size.
     *
     * <p>Integration should pass the reciprocal of the directional shadow
     * atlas edge (the default overload assumes the project's 2048-pixel
     * directional map). The value is only consumed by the bounded 3x3 PCF when
     * a shadow texture is supplied.</p>
     */
    public void record(CommandBuffer cmd, ExternalCamera camera, Matrix4fc model,
                       HairStrandSettings requested, HairStrandLight light,
                       int directionalShadowTexture, Matrix4fc directionalLightSpace,
                       float shadowBias, float shadowTexelSize) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(light, "light");
        if (!requested.active()) {
            return;
        }
        if (!requested.equals(settings)) {
            throw new IllegalArgumentException(
                    "hair geometry is fixed per pass; create a new HairStrandPass for new settings");
        }
        if (!model.isFinite()) {
            throw new IllegalArgumentException("hair model matrix must be finite");
        }
        if (!Float.isFinite(shadowBias) || shadowBias < 0.0f) {
            throw new IllegalArgumentException("hair shadowBias must be finite and non-negative");
        }
        if (!Float.isFinite(shadowTexelSize) || shadowTexelSize <= 0.0f
                || shadowTexelSize > 1.0f) {
            throw new IllegalArgumentException("hair shadowTexelSize must be finite and in (0, 1]");
        }
        if (directionalShadowTexture != 0
                && (directionalLightSpace == null || !directionalLightSpace.isFinite())) {
            throw new IllegalArgumentException(
                    "a directional shadow texture requires a finite light-space matrix");
        }

        camera.getViewMatrix(viewScratch);
        camera.projection(projectionScratch);
        viewProjectionScratch.set(projectionScratch).mul(viewScratch);
        cameraScratch.set(camera.position());
        HairStrandSettings.Lod lod = selectLod(model);
        float widthScale = (float) settings.widthScaleFor(distanceToLock(model));
        HairStrandGeometry.StrandMesh level = levels[lod.ordinal()];
        Mesh mesh = meshes[lod.ordinal()];
        lastActiveLod = lod;
        lastStrandCount = level.strandCount();
        lastWidthScale = widthScale;
        lastDrawOrderBackToFront = sortBackToFront(level, model);

        cmd.bindShader(forwardProgram)
                .enableScissor(false)
                .enableDepthTest(true)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .setUniformMat4(forwardProgram, "uView", viewScratch)
                .setUniformMat4(forwardProgram, "uProjection", projectionScratch)
                .setUniformMat4(forwardProgram, "uModel", uniformMatrixScratch.set(model))
                .setUniformVec3(forwardProgram, "uCameraPosition", cameraScratch)
                .setUniformVec2(forwardProgram, "uViewportSize", width, height)
                .setUniformFloat(forwardProgram, "uWidthPixels", settings.widthPixels())
                .setUniformFloat(forwardProgram, "uMinimumWidthPixels",
                        settings.minimumWidthPixels())
                .setUniformFloat(forwardProgram, "uMaximumWidthPixels",
                        settings.maximumWidthPixels())
                .setUniformFloat(forwardProgram, "uWidthScale", widthScale)
                .setUniformFloat(forwardProgram, "uRootRadiusMetres",
                        Math.max(MINIMUM_RADIUS, settings.rootSpreadMetres() * 0.5f))
                .setUniformInt(forwardProgram, "uTransparencyMode",
                        settings.transparency() == HairStrandSettings.Transparency.CUTOUT ? 0 : 1)
                .setUniformFloat(forwardProgram, "uAlpha", settings.alpha())
                .setUniformFloat(forwardProgram, "uAlphaCutoff", settings.alphaCutoff())
                .setUniformVec3(forwardProgram, "uRootColor", settings.rootColorInternal())
                .setUniformVec3(forwardProgram, "uTipColor", settings.tipColorInternal())
                .setUniformFloat(forwardProgram, "uDiffuseStrength", settings.diffuseStrength())
                .setUniformFloat(forwardProgram, "uSpecularStrength", settings.specularStrength())
                .setUniformFloat(forwardProgram, "uPrimaryExponent", settings.primaryExponent())
                .setUniformFloat(forwardProgram, "uSecondaryExponent", settings.secondaryExponent())
                .setUniformFloat(forwardProgram, "uSecondaryWeight", settings.secondaryWeight())
                .setUniformFloat(forwardProgram, "uColorVariation", settings.colorVariation())
                .setUniformVec3(forwardProgram, "uLightTravelDirection", light.travelDirectionInternal())
                .setUniformVec3(forwardProgram, "uLightColor", light.colorInternal())
                .setUniformFloat(forwardProgram, "uLightIntensity", light.intensity())
                .setUniformVec3(forwardProgram, "uAmbientColor", light.ambientColorInternal())
                .setUniformInt(forwardProgram, "uHasDirectionalShadow",
                        directionalShadowTexture != 0 ? 1 : 0)
                .setUniformInt(forwardProgram, "uDirectionalShadow", DIRECTIONAL_SHADOW_UNIT)
                .setUniformFloat(forwardProgram, "uShadowBias", shadowBias)
                .setUniformFloat(forwardProgram, "uShadowTexelSize",
                        directionalShadowTexture == 0 ? 0.0f : shadowTexelSize)
                .setUniformMat4(forwardProgram, "uDirectionalLightSpace",
                        uniformMatrixScratch.set(directionalLightSpace == null
                                ? lightSpaceScratch.identity() : directionalLightSpace));
        if (directionalShadowTexture != 0) {
            cmd.bindTexture(DIRECTIONAL_SHADOW_UNIT, directionalShadowTexture);
        }
        if (settings.transparency() == HairStrandSettings.Transparency.CUTOUT) {
            cmd.enableBlend(false).depthMask(true);
        } else {
            cmd.enableBlend(true)
                    .blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
                    .depthMask(false);
        }
        cmd.bindMesh(mesh);
        for (int index = 0; index < level.strandCount(); index++) {
            int strand = drawOrder[index];
            cmd.drawArrays(GL_TRIANGLES, strand * level.verticesPerStrand(),
                    level.verticesPerStrand());
        }
        cmd.enableBlend(false).depthMask(true);
    }

    /**
     * Records the velocity-only strand draws into the currently bound velocity
     * attachment.
     *
     * <p>The caller owns the framebuffer, viewport and the shared
     * {@code sceneVelocity} attachment; this method only binds its own program
     * and mesh and draws every strand once. The vertex stage duplicates the
     * forward ribbon expansion so the rasterized coverage matches the forward
     * draw; the fragment stage encodes {@code previousUv - currentUv} for the
     * strand centreline with the same stable view-projections, {@code w > 0}
     * gate and {@code |velocity| < 4} bound as the shared surface pass.</p>
     *
     * <p>A disabled requested value or an invalid previous frame is an exact
     * no-op: no command is enqueued, so the receiver's velocity is never
     * overwritten with a zero that does not describe the previous visibility.
     * The method never allocates, reads back or waits.</p>
     *
     * @param cmd           GPU-owner command buffer
     * @param current       frozen camera of the frame being recorded
     * @param previous      frozen camera of the last successful frame
     * @param model         finite current model matrix of the lock
     * @param previousModel finite model matrix committed by the last successful frame
     * @param previousValid whether the previous frame's lock state is usable
     * @param requested     per-frame settings; a disabled value is an exact no-op
     * @throws IllegalArgumentException on mismatched settings or non-finite matrices
     */
    public void recordVelocity(CommandBuffer cmd, ExternalCamera current, ExternalCamera previous,
                               Matrix4fc model, Matrix4fc previousModel, boolean previousValid,
                               HairStrandSettings requested) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(previousModel, "previousModel");
        Objects.requireNonNull(requested, "requested");
        if (!requested.active() || !previousValid) {
            return;
        }
        if (!requested.equals(settings)) {
            throw new IllegalArgumentException(
                    "hair geometry is fixed per pass; create a new HairStrandPass for new settings");
        }
        if (!model.isFinite() || !previousModel.isFinite()) {
            throw new IllegalArgumentException("hair velocity model matrices must be finite");
        }

        current.getViewMatrix(viewScratch);
        CameraProjection.stable(current, width, height, projectionScratch);
        viewProjectionScratch.set(projectionScratch).mul(viewScratch);
        previous.getViewMatrix(previousViewScratch);
        CameraProjection.stable(previous, width, height, previousProjectionScratch);
        previousViewProjectionScratch.set(previousProjectionScratch).mul(previousViewScratch);
        cameraScratch.set(current.position());
        HairStrandSettings.Lod lod = selectLod(model);
        float widthScale = (float) settings.widthScaleFor(distanceToLock(model));
        HairStrandGeometry.StrandMesh level = levels[lod.ordinal()];
        Mesh mesh = meshes[lod.ordinal()];

        cmd.bindShader(velocityProgram)
                .enableScissor(false)
                .enableDepthTest(true)
                .depthFunc(GL_LEQUAL)
                .depthMask(false)
                .enableBlend(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .setUniformMat4(velocityProgram, "uCurrentStableViewProjection",
                        viewProjectionScratch)
                .setUniformMat4(velocityProgram, "uPreviousStableViewProjection",
                        previousViewProjectionScratch)
                .setUniformMat4(velocityProgram, "uModel", uniformMatrixScratch.set(model))
                .setUniformMat4(velocityProgram, "uPreviousModel",
                        uniformMatrixScratch.set(previousModel))
                .setUniformVec3(velocityProgram, "uCameraPosition", cameraScratch)
                .setUniformVec2(velocityProgram, "uViewportSize", width, height)
                .setUniformFloat(velocityProgram, "uWidthPixels", settings.widthPixels())
                .setUniformFloat(velocityProgram, "uMinimumWidthPixels",
                        settings.minimumWidthPixels())
                .setUniformFloat(velocityProgram, "uMaximumWidthPixels",
                        settings.maximumWidthPixels())
                .setUniformFloat(velocityProgram, "uWidthScale", widthScale)
                .setUniformFloat(velocityProgram, "uRootRadiusMetres",
                        Math.max(MINIMUM_RADIUS, settings.rootSpreadMetres() * 0.5f))
                .setUniformInt(velocityProgram, "uTransparencyMode",
                        settings.transparency() == HairStrandSettings.Transparency.CUTOUT ? 0 : 1)
                .setUniformFloat(velocityProgram, "uAlpha", settings.alpha())
                .setUniformFloat(velocityProgram, "uAlphaCutoff", settings.alphaCutoff());
        cmd.bindMesh(mesh);
        cmd.drawArrays(GL_TRIANGLES, 0, level.strandCount() * level.verticesPerStrand());
        // Restore the pipeline-wide default so later depth-writing passes do not
        // inherit the borrowed-depth comparison.
        cmd.depthMask(true).depthFunc(GL_LESS);
    }

    /**
     * Records the alpha-tested shadow-caster draw into the currently bound
     * shadow depth target.
     *
     * <p>The caller owns the atlas framebuffer, viewport/scissor and dirty-tile
     * selection; this method only binds its own program, uploads the existing
     * {@code uLightSpace * uModel} convention and draws the high-detail mesh.
     * A disabled settings value records nothing.</p>
     *
     * @param cmd       GPU-owner command buffer
     * @param model     finite model matrix of the lock
     * @param lightSpace finite existing shadow light-space matrix
     * @param requested per-frame settings; a disabled value is an exact no-op
     * @param lightTravelDirection normalized light-travel direction used by the
     *                              world-space ribbon expansion
     */
    public void recordShadow(CommandBuffer cmd, Matrix4fc model, Matrix4fc lightSpace,
                             HairStrandSettings requested,
                             Vector3fc lightTravelDirection) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(lightSpace, "lightSpace");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(lightTravelDirection, "lightTravelDirection");
        if (!requested.active() || !requested.castsShadow()) {
            return;
        }
        if (!requested.equals(settings)) {
            throw new IllegalArgumentException(
                    "hair geometry is fixed per pass; create a new HairStrandPass for new settings");
        }
        if (!model.isFinite() || !lightSpace.isFinite()) {
            throw new IllegalArgumentException("hair shadow matrices must be finite");
        }
        if (!lightTravelDirection.isFinite()
                || lightTravelDirection.lengthSquared() < 1.0e-12f) {
            throw new IllegalArgumentException(
                    "hair shadow lightTravelDirection must be finite and non-zero");
        }
        HairStrandGeometry.StrandMesh level = levels[HairStrandSettings.Lod.HIGH.ordinal()];
        Mesh mesh = meshes[HairStrandSettings.Lod.HIGH.ordinal()];
        cmd.bindShader(shadowProgram)
                .enableDepthTest(true)
                .depthMask(true)
                .enableBlend(false)
                .enableCullFace(false)
                .setUniformMat4(shadowProgram, "uLightSpace", lightSpaceScratch.set(lightSpace))
                .setUniformMat4(shadowProgram, "uModel", uniformMatrixScratch.set(model))
                .setUniformVec3(shadowProgram, "uLightTravelDirection",
                        new Vector3f(lightTravelDirection).normalize())
                .setUniformFloat(shadowProgram, "uStrandRadiusMetres",
                        settings.strandRadiusMetres())
                .setUniformFloat(shadowProgram, "uAlpha", settings.alpha())
                .setUniformFloat(shadowProgram, "uAlphaCutoff", settings.alphaCutoff())
                .bindMesh(mesh);
        cmd.drawArrays(GL_TRIANGLES, 0, level.strandCount() * level.verticesPerStrand());
    }

    /**
     * Publishes a new logical viewport extent. The candidate extent is fully
     * validated before the previous extent is replaced, so a rejected candidate
     * leaves the active value untouched. The pass owns no framebuffer; callers
     * that render into a resized target must call this before recording at the
     * new extent.
     */
    public void resize(int newWidth, int newHeight) {
        ensureOpen();
        validateExtent(newWidth, newHeight);
        if (newWidth == width && newHeight == height) {
            return;
        }
        width = newWidth;
        height = newHeight;
    }

    /** Package-private: true when the last record drew strands far to near. */
    boolean lastDrawOrderBackToFront() {
        return lastDrawOrderBackToFront;
    }

    @Override
    public int id() {
        return closed ? 0 : forwardProgram.id();
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
        RuntimeException failure = null;
        for (Mesh mesh : meshes) {
            failure = close(mesh, failure);
        }
        failure = close(shadowProgram, failure);
        failure = close(velocityProgram, failure);
        failure = close(forwardProgram, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private HairStrandSettings.Lod selectLod(Matrix4fc model) {
        HairStrandGeometry.StrandMesh level = levels[HairStrandSettings.Lod.HIGH.ordinal()];
        Bounds3f bounds = level.bounds();
        float centerX = (bounds.minX() + bounds.maxX()) * 0.5f;
        float centerY = (bounds.minY() + bounds.maxY()) * 0.5f;
        float centerZ = (bounds.minZ() + bounds.maxZ()) * 0.5f;
        centerScratch.set(centerX, centerY, centerZ);
        model.transformPosition(centerScratch);
        double distance = cameraScratch.distance(centerScratch);
        double radius = Math.max(MINIMUM_RADIUS,
                Math.max(bounds.maxX() - bounds.minX(),
                        Math.max(bounds.maxY() - bounds.minY(), bounds.maxZ() - bounds.minZ()))
                        * 0.5f);
        double projected = projectedRadiusPixels(centerScratch, radius);
        return settings.selectLod(distance, projected);
    }

    private double distanceToLock(Matrix4fc model) {
        HairStrandGeometry.StrandMesh level = levels[HairStrandSettings.Lod.HIGH.ordinal()];
        Bounds3f bounds = level.bounds();
        centerScratch.set((bounds.minX() + bounds.maxX()) * 0.5f,
                (bounds.minY() + bounds.maxY()) * 0.5f,
                (bounds.minZ() + bounds.maxZ()) * 0.5f);
        model.transformPosition(centerScratch);
        return cameraScratch.distance(centerScratch);
    }

    private double projectedRadiusPixels(Vector3fc center, double radius) {
        Matrix4f viewProjection = viewProjectionScratch;
        if (!project(center, viewProjection)) {
            return 0.0;
        }
        double centerNdcX = projectedNdcX;
        double centerNdcY = projectedNdcY;
        axisScratch.set(center).sub(cameraScratch);
        if (axisScratch.lengthSquared() < 1.0e-12f) {
            return 0.0;
        }
        axisScratch.normalize();
        // Any stable perpendicular is enough; the basis only defines the probe
        // point whose screen offset measures pixels per world unit.
        worldScratch.set(0.0f, 1.0f, 0.0f);
        if (Math.abs(axisScratch.dot(worldScratch)) > 0.99f) {
            worldScratch.set(1.0f, 0.0f, 0.0f);
        }
        worldScratch.cross(axisScratch).normalize().mul((float) radius).add(center);
        if (!project(worldScratch, viewProjection)) {
            return 0.0;
        }
        double deltaX = (projectedNdcX - centerNdcX) * 0.5 * width;
        double deltaY = (projectedNdcY - centerNdcY) * 0.5 * height;
        return Math.sqrt(deltaX * deltaX + deltaY * deltaY);
    }

    private boolean project(Vector3fc point, Matrix4fc viewProjection) {
        // JOML matrices are column-major: mXY is column X, row Y.
        double x = viewProjection.m00() * point.x() + viewProjection.m10() * point.y()
                + viewProjection.m20() * point.z() + viewProjection.m30();
        double y = viewProjection.m01() * point.x() + viewProjection.m11() * point.y()
                + viewProjection.m21() * point.z() + viewProjection.m31();
        double w = viewProjection.m03() * point.x() + viewProjection.m13() * point.y()
                + viewProjection.m23() * point.z() + viewProjection.m33();
        if (!(w > BEHIND_EYE_EPSILON)) {
            return false;
        }
        projectedNdcX = x / w;
        projectedNdcY = y / w;
        return Double.isFinite(projectedNdcX) && Double.isFinite(projectedNdcY);
    }

    private double projectedNdcX;
    private double projectedNdcY;

    private boolean sortBackToFront(HairStrandGeometry.StrandMesh level, Matrix4fc model) {
        int count = level.strandCount();
        float[] centers = level.strandCenters();
        for (int index = 0; index < count; index++) {
            centerScratch.set(centers[index * 3], centers[index * 3 + 1],
                    centers[index * 3 + 2]);
            model.transformPosition(centerScratch);
            viewScratch.transformPosition(centerScratch);
            strandDepths[index] = centerScratch.z;
            drawOrder[index] = index;
        }
        for (int index = 1; index < count; index++) {
            int key = drawOrder[index];
            float keyDepth = strandDepths[key];
            int position = index - 1;
            while (position >= 0 && strandDepths[drawOrder[position]] > keyDepth) {
                drawOrder[position + 1] = drawOrder[position];
                position--;
            }
            drawOrder[position + 1] = key;
        }
        boolean sorted = true;
        for (int index = 1; index < count; index++) {
            if (strandDepths[drawOrder[index - 1]] > strandDepths[drawOrder[index]]) {
                sorted = false;
                break;
            }
        }
        return sorted;
    }

    private static void validateExtent(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAXIMUM_EXTENT || height > MAXIMUM_EXTENT) {
            throw new IllegalArgumentException("hair viewport extent must be in [1, "
                    + MAXIMUM_EXTENT + "]");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("hair strand pass is closed");
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

    private static void closeSuppressed(GlResource resource, Throwable primary) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (RuntimeException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    /**
     * Bounded directional lighting input for the strand shader.
     *
     * <p>{@code travelDirection} is the light-travel direction (the direction a
     * photon travels, matching {@code SceneLight.direction()}); the shader
     * negates it for surface lighting. {@code ambientColor} is linear radiance
     * added once to the diffuse term. All components are copied and validated.</p>
     */
    public record HairStrandLight(Vector3f travelDirection, Vector3f color, float intensity,
                                  Vector3f ambientColor) {
        /** Largest accepted linear light or ambient component. */
        public static final float MAXIMUM_COMPONENT = 16.0f;

        public HairStrandLight {
            travelDirection = direction(travelDirection, "travelDirection");
            color = component(color, "color");
            ambientColor = component(ambientColor, "ambientColor");
            if (!Float.isFinite(intensity) || intensity < 0.0f || intensity > MAXIMUM_COMPONENT) {
                throw new IllegalArgumentException("intensity must be finite and in [0, "
                        + MAXIMUM_COMPONENT + "]");
            }
        }

        @Override
        public Vector3f travelDirection() {
            return new Vector3f(travelDirection);
        }

        @Override
        public Vector3f color() {
            return new Vector3f(color);
        }

        @Override
        public Vector3f ambientColor() {
            return new Vector3f(ambientColor);
        }

        Vector3f travelDirectionInternal() {
            return travelDirection;
        }

        Vector3f colorInternal() {
            return color;
        }

        Vector3f ambientColorInternal() {
            return ambientColor;
        }

        private static Vector3f direction(Vector3f value, String name) {
            Objects.requireNonNull(value, name);
            float lengthSquared = value.lengthSquared();
            if (!Float.isFinite(lengthSquared) || lengthSquared < 1.0e-12f) {
                throw new IllegalArgumentException(name + " must be finite and non-zero");
            }
            return new Vector3f(value).mul(1.0f / (float) Math.sqrt(lengthSquared));
        }

        private static Vector3f component(Vector3f value, String name) {
            Objects.requireNonNull(value, name);
            if (!value.isFinite() || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                    || value.x > MAXIMUM_COMPONENT || value.y > MAXIMUM_COMPONENT
                    || value.z > MAXIMUM_COMPONENT) {
                throw new IllegalArgumentException(name + " components must be in [0, "
                        + MAXIMUM_COMPONENT + "]");
            }
            return new Vector3f(value);
        }
    }
}
