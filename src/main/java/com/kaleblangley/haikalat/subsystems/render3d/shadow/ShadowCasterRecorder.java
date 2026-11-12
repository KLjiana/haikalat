package com.kaleblangley.haikalat.subsystems.render3d.shadow;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.UniformKey;
import com.kaleblangley.haikalat.core.material.UniformValue;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.InstancedRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneDeformationDefaults;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneDrawBinding;
import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderQueueClass;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneFrame;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricFogView;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.List;
import java.util.Objects;

/**
 * Records the shadow caster command streams for all three shadow families.
 *
 * <p>Borrows the shadow programs, the deformation binding and the atlas/cache
 * inputs needed to clear and draw dirty tiles. Scheduling (which lights
 * are dirty, caster plans, cache hits) stays in {@link ShadowLightScheduler},
 * {@link ShadowCasterPlanner} and {@link ShadowCacheState}; the caller passes
 * those snapshots in, so this class never reads a pipeline generation or a
 * central render pipeline.</p>
 */
public final class ShadowCasterRecorder {
    /**
     * Late caster draw executed inside one dirty directional cascade tile.
     *
     * <p>The hook owns its program/VAO and must not change the atlas, viewport,
     * scissor or the {@code uLightSpace * uModel} convention. The recorder
     * re-binds its own program for the next cascade.</p>
     */
    @FunctionalInterface
    public interface DirectionalTileHook {
        void record(CommandBuffer cmd, Matrix4f lightSpace, int cascade);
    }

    private static final int POINT_FACE_COUNT = PointShadowAtlas.FACE_COUNT;

    private final Matrix4f modelScratch = new Matrix4f();
    private final ShaderProgram shadowShader;
    private final ShaderProgram maskedShadowShader;
    private final ShaderProgram instancedShadowShader;
    private final SceneDeformationDefaults deformationDefaults;
    private final ShadowCacheState cache;
    private final DirectionalShadowMap directionalMap;
    private final PointShadowAtlas pointAtlas;
    private final SpotShadowAtlas spotAtlas;
    private final DirectionalCascadeSettings cascadeSettings;
    private final LocalShadowPipelineSettings localSettings;
    private final InstancedRenderer instanced;

    public ShadowCasterRecorder(ShadowPrograms programs,
                                SceneDeformationDefaults deformationDefaults,
                                ShadowCacheState cache, DirectionalShadowMap directionalMap,
                                PointShadowAtlas pointAtlas, SpotShadowAtlas spotAtlas,
                                DirectionalCascadeSettings cascadeSettings,
                                LocalShadowPipelineSettings localSettings,
                                InstancedRenderer instanced) {
        shadowShader = programs == null ? null : programs.opaque();
        maskedShadowShader = programs == null ? null : programs.masked();
        instancedShadowShader = programs == null ? null : programs.instanced();
        this.deformationDefaults = Objects.requireNonNull(deformationDefaults, "deformationDefaults");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.directionalMap = Objects.requireNonNull(directionalMap, "directionalMap");
        this.pointAtlas = Objects.requireNonNull(pointAtlas, "pointAtlas");
        this.spotAtlas = Objects.requireNonNull(spotAtlas, "spotAtlas");
        this.cascadeSettings = Objects.requireNonNull(cascadeSettings, "cascadeSettings");
        this.localSettings = Objects.requireNonNull(localSettings, "localSettings");
        this.instanced = instanced;
    }

    /** Records dirty directional cascades; returns the number of drawn casters. */
    public int recordDirectional(CommandBuffer cmd, SceneFrame frame,
                                 ShadowFramePlan.DirectionalPlan plan,
                                 ShadowCasterPlanner.ShadowCasterPlan culling) {
        return recordDirectional(cmd, frame, plan, culling, null);
    }

    /**
     * Records dirty directional cascades and lets an optional late caster hook
     * add its own draw inside the same tile viewport/scissor. The hook is called
     * once per dirty cascade after the ordinary and instanced casters; it keeps
     * the existing atlas/light-space convention and never allocates a second
     * shadow map.
     */
    public int recordDirectional(CommandBuffer cmd, SceneFrame frame,
                                 ShadowFramePlan.DirectionalPlan plan,
                                 ShadowCasterPlanner.ShadowCasterPlan culling,
                                 DirectionalTileHook hook) {
        if (shadowShader == null) {
            return 0;
        }
        int atlasSize = cascadeSettings.enabled()
                ? cascadeSettings.atlasSize() : directionalMap.settings().resolution();
        boolean preserveTiles = localSettings.cacheStaticTiles();
        if (preserveTiles && !cache.directionalAtlasInitialized()) {
            cmd.enableScissor(false).viewport(0, 0, atlasSize, atlasSize)
                    .depthMask(true).clear(false, true);
        }
        cmd.bindShader(shadowShader)
                .enableBlend(false)
                .enableDepthTest(true)
                .depthMask(true)
                // The reference scene contains double-sided planes, so shadow
                // passes explicitly disable culling.
                .enableCullFace(false);
        int count = 0;
        List<Matrix4f> matrices = plan.matrices();
        for (int cascade = 0; cascade < matrices.size(); cascade++) {
            if (!plan.dirtyTiles().get(cascade)) continue;
            ShadowTileRect tile = plan.tiles().get(cascade);
            boolean empty = culling != null && culling.count(cascade) == 0;
            if (preserveTiles || empty) {
                cmd.enableScissor(true)
                        .viewport(tile.x(), tile.y(), tile.width(), tile.height())
                        .scissor(tile.x(), tile.y(), tile.width(), tile.height())
                        .clear(false, true);
            } else {
                cmd.enableScissor(false)
                        .viewport(tile.x(), tile.y(), tile.width(), tile.height());
            }
            count += recordDirectionalCasters(cmd, frame, matrices.get(cascade), cascade, culling);
            renderInstanced(cmd, matrices.get(cascade), cascade, culling);
            if (hook != null) {
                hook.record(cmd, matrices.get(cascade), cascade);
            }
        }
        cmd.enableScissor(false);
        return count;
    }

    /** Records dirty point-light faces; returns the number of drawn casters. */
    public int recordPoints(CommandBuffer cmd, SceneFrame frame, List<PointShadowSlotPlan> points,
                            ShadowCasterPlanner.ShadowCasterPlan culling) {
        if (points.isEmpty() || shadowShader == null) {
            return 0;
        }
        boolean preserveTiles = localSettings.cacheStaticTiles();
        if (preserveTiles && !cache.pointAtlasInitialized()) {
            cmd.enableScissor(false).viewport(0, 0, pointAtlasWidth(), pointAtlasHeight())
                    .depthMask(true).clear(false, true);
        }
        cmd.bindShader(shadowShader)
                .enableBlend(false)
                .enableDepthTest(true)
                .depthMask(true)
                .enableCullFace(false);
        int draws = 0;
        for (PointShadowSlotPlan slot : points) {
            if (!slot.dirty()) continue;
            for (int face = 0; face < POINT_FACE_COUNT; face++) {
                int view = 4 + slot.slot() * POINT_FACE_COUNT + face;
                if (!slot.faceDirty(face)) continue;
                ShadowTileRect tile = slot.faceTiles().get(face);
                Matrix4f matrix = slot.faceMatrices().get(face);
                boolean empty = culling != null && culling.count(view) == 0;
                if (preserveTiles || empty) {
                    cmd.enableScissor(true)
                            .viewport(tile.x(), tile.y(), tile.width(), tile.height())
                            .scissor(tile.x(), tile.y(), tile.width(), tile.height())
                            .clear(false, true);
                } else {
                    cmd.enableScissor(false)
                            .viewport(tile.x(), tile.y(), tile.width(), tile.height());
                }
                cmd.setUniformMat4(shadowShader, "uLightSpace", matrix);
                draws += recordAllCasters(cmd, frame, matrix, view, culling);
                renderInstanced(cmd, matrix, view, culling);
            }
        }
        cmd.enableScissor(false);
        return draws;
    }

    /** Records dirty spot tiles; returns the number of drawn casters. */
    public int recordSpots(CommandBuffer cmd, SceneFrame frame, List<SpotShadowSlotPlan> spots,
                           ShadowCasterPlanner.ShadowCasterPlan culling) {
        if (spots.isEmpty() || shadowShader == null) {
            return 0;
        }
        boolean preserveTiles = localSettings.cacheStaticTiles();
        if (preserveTiles && !cache.spotAtlasInitialized()) {
            cmd.enableScissor(false).viewport(0, 0, spotAtlasWidth(), spotAtlasHeight())
                    .depthMask(true).clear(false, true);
        }
        cmd.bindShader(shadowShader)
                .enableBlend(false)
                .enableDepthTest(true)
                .depthMask(true)
                .enableCullFace(false);
        int draws = 0;
        for (SpotShadowSlotPlan slot : spots) {
            if (!slot.dirty()) continue;
            ShadowTileRect tile = slot.tile();
            int view = 16 + slot.slot();
            boolean empty = culling != null && culling.count(view) == 0;
            if (preserveTiles || empty) {
                cmd.enableScissor(true)
                        .viewport(tile.x(), tile.y(), tile.width(), tile.height())
                        .scissor(tile.x(), tile.y(), tile.width(), tile.height())
                        .clear(false, true);
            } else {
                cmd.enableScissor(false)
                        .viewport(tile.x(), tile.y(), tile.width(), tile.height());
            }
            cmd.setUniformMat4(shadowShader, "uLightSpace", slot.lightSpaceMatrix());
            draws += recordAllCasters(cmd, frame, slot.lightSpaceMatrix(), view, culling);
            renderInstanced(cmd, slot.lightSpaceMatrix(), view, culling);
        }
        cmd.enableScissor(false);
        return draws;
    }

    /**
     * Binds the borrowed shadow maps and sampling block for the volumetric fog
     * consumer.  The frame plan and camera facts are passed in; this method
     * never reads pipeline state.
     */
    public void recordVolumeInputs(PassResources res, CommandBuffer cmd, ShaderProgram shader,
                                   ShadowFramePlan plan, Camera camera,
                                   ShadowSamplingBlock samplingBlock, ShadowFrameBinder binder,
                                   Matrix4f directionalLightSpace,
                                   List<Matrix4f> cascadeMatrices, float[] cascadeSplits) {
        int directional = plan.directional().isPresent()
                ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0;
        int point = plan.points().isEmpty() ? 0 : res.depthAttachment(PointShadowAtlas.TEXTURE_NAME);
        int spot = plan.spots().isEmpty() ? 0 : res.depthAttachment(SpotShadowAtlas.TEXTURE_NAME);
        boolean fineFog = shader.hasStorageBlock("FineLightTableBlock");
        java.util.function.UnaryOperator<String> uniform = fineFog
                ? VolumetricFogView::fineUniform
                : java.util.function.UnaryOperator.identity();
        cmd.bindTexture(7, directional).bindTexture(11, point).bindTexture(12, spot)
                .setUniformInt(shader, uniform.apply("uHasDirectionalShadow"), directional != 0 ? 1 : 0)
                .setUniformInt(shader, uniform.apply("uHasPointShadow"), point != 0 ? 1 : 0)
                .setUniformInt(shader, uniform.apply("uHasSpotShadow"), spot != 0 ? 1 : 0)
                .setUniformFloat(shader, uniform.apply("uVolumeDirectionalBias"),
                        directionalMap.settings().bias())
                .setUniformFloat(shader, uniform.apply("uVolumePointBias"),
                        localSettings.point().bias())
                .setUniformFloat(shader, uniform.apply("uVolumeSpotBias"),
                        localSettings.spot().bias());
        if (samplingBlock != null) {
            samplingBlock.bind(cmd);
        }
        binder.bind(cmd, shader, camera, directionalLightSpace, cascadeMatrices, cascadeSplits,
                cascadeSettings, plan, fineFog);
        for (int index = 0; index < 4; index++) {
            ShadowTileRect rect = plan.directional().isPresent()
                    ? plan.directional().orElseThrow().tiles().get(Math.min(index,
                    plan.directional().orElseThrow().tiles().size() - 1))
                    : new ShadowTileRect(0, 0, 1, 1, 0, 0, 1, 1);
            cmd.setUniformVec4(shader, uniform.apply("uVolumeCascadeRects[" + index + "]"),
                    new Vector4f(rect.minU(), rect.minV(), rect.maxU(), rect.maxV()));
        }
    }

    private int recordDirectionalCasters(CommandBuffer cmd, SceneFrame frame,
                                         Matrix4f lightSpace, int view,
                                         ShadowCasterPlanner.ShadowCasterPlan culling) {
        if (deformationDefaults != null) {
            deformationDefaults.record(cmd);
        }
        ShaderProgram boundShader = shadowShader;
        cmd.bindShader(boundShader).setUniformMat4(boundShader, "uLightSpace", lightSpace);
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        int boundSkinningEnabled = -1;
        int boundMorphTargetCount = -1;
        int count = culling == null ? frame.shadowCount() : culling.count(view);
        int candidateRank = culling == null ? -1 : culling.firstCandidateRank(view);
        for (int queueIndex = 0; queueIndex < count; queueIndex++) {
            int entry = culling == null ? frame.shadowEntry(queueIndex)
                    : culling.casterAtRank(candidateRank);
            if (culling != null) {
                candidateRank = culling.nextCandidateRank(view, candidateRank);
            }
            MeshRenderer renderer = frame.renderer(entry);
            ShaderProgram shader = shadowShaderFor(renderer);
            if (shader != boundShader) {
                cmd.bindShader(shader).setUniformMat4(shader, "uLightSpace", lightSpace);
                boundShader = shader;
                boundSkinningEnabled = -1;
                boundMorphTargetCount = -1;
            }
            bindMaskedShadowMaterial(cmd, shader, renderer.material());
            cmd.setUniformMat4(shader, "uModel", frame.copyModel(entry, modelScratch));
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
                binding.record(cmd, shader, (int) frame.frameIndex(), SceneDrawBinding.Pass.SHADOW);
            }
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
        }
        return count;
    }

    private int recordAllCasters(CommandBuffer cmd, SceneFrame frame, Matrix4f lightSpace,
                                 int view, ShadowCasterPlanner.ShadowCasterPlan culling) {
        if (deformationDefaults != null) {
            deformationDefaults.record(cmd);
        }
        ShaderProgram boundShader = shadowShader;
        // Point faces and spot tiles are recorded back-to-back.  The previous
        // view may have ended with the masked or instanced program bound, so
        // DSA uniform updates alone are not enough to select the ordinary depth
        // shader for the first opaque caster in this view.
        cmd.bindShader(boundShader).setUniformMat4(boundShader, "uLightSpace", lightSpace);
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        int boundSkinningEnabled = -1;
        int boundMorphTargetCount = -1;
        int draws = 0;
        int count = culling == null ? frame.rendererCount() : culling.count(view);
        int candidateRank = culling == null ? -1 : culling.firstCandidateRank(view);
        for (int index = 0; index < count; index++) {
            int entry = culling == null ? index : culling.casterAtRank(candidateRank);
            if (culling != null) {
                candidateRank = culling.nextCandidateRank(view, candidateRank);
            }
            MeshRenderer renderer = frame.renderer(entry);
            if (!renderer.castShadows()
                    || !RenderQueueClass.classify(renderer.material()).castsOpaqueShadow()) {
                continue;
            }
            ShaderProgram entryShader = shadowShaderFor(renderer);
            if (entryShader != boundShader) {
                cmd.bindShader(entryShader).setUniformMat4(entryShader, "uLightSpace", lightSpace);
                boundShader = entryShader;
                boundSkinningEnabled = -1;
                boundMorphTargetCount = -1;
            }
            bindMaskedShadowMaterial(cmd, entryShader, renderer.material());
            cmd.setUniformMat4(entryShader, "uModel", frame.copyModel(entry, modelScratch));
            SceneDrawBinding drawBinding = renderer.drawBinding();
            drawBinding.recordVertexDisplacement(cmd, entryShader, SceneDrawBinding.Pass.SHADOW);
            int skinningEnabled = drawBinding.skinningEnabled() ? 1 : 0;
            int morphTargetCount = drawBinding.morphTargetCount();
            if (skinningEnabled != boundSkinningEnabled) {
                cmd.trySetUniformInt(entryShader, "uSkinningEnabled", skinningEnabled);
                boundSkinningEnabled = skinningEnabled;
            }
            if (morphTargetCount != boundMorphTargetCount) {
                cmd.trySetUniformInt(entryShader, "uMorphTargetCount", morphTargetCount);
                boundMorphTargetCount = morphTargetCount;
            }
            if (drawBinding != SceneDrawBinding.NONE) {
                drawBinding.record(cmd, entryShader, (int) frame.frameIndex(),
                        SceneDrawBinding.Pass.SHADOW);
            }
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
            draws++;
        }
        return draws;
    }

    private void renderInstanced(CommandBuffer cmd, Matrix4f lightSpace, int view,
                                 ShadowCasterPlanner.ShadowCasterPlan culling) {
        if (instanced == null || !instanced.castShadows() || instancedShadowShader == null) {
            return;
        }
        if (culling != null && !culling.instanceVisible(view)) {
            return;
        }
        cmd.bindShader(instancedShadowShader)
                .setUniformMat4(instancedShadowShader, "uLightSpace", lightSpace);
        instanced.renderShadow(cmd);
    }

    private ShaderProgram shadowShaderFor(MeshRenderer renderer) {
        return RenderQueueClass.classify(renderer.material()) == RenderQueueClass.MASKED
                ? maskedShadowShader : shadowShader;
    }

    private void bindMaskedShadowMaterial(CommandBuffer cmd, ShaderProgram shader,
                                          MaterialInstance instance) {
        if (shader != maskedShadowShader) return;
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
        cmd.setUniformInt(shader, "uBaseColorMap", 0)
                .setUniformFloat(shader, "uAlphaCutoff", cutoffValue)
                .setUniformVec4(shader, "uBaseColorFactor", factorValue);
    }

    private int pointAtlasWidth() {
        return pointAtlas.width();
    }

    private int pointAtlasHeight() {
        return pointAtlas.height();
    }

    private int spotAtlasWidth() {
        return spotAtlas.width();
    }

    private int spotAtlasHeight() {
        return spotAtlas.height();
    }
}
