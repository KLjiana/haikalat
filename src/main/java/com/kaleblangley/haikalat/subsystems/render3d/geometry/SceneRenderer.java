package com.kaleblangley.haikalat.subsystems.render3d.geometry;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.FrontFace;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneFrame;
import org.joml.Matrix4f;

import java.util.Objects;

/**
 * Records the forward, transparent and instanced draw streams of one scene
 * frame.
 *
 * <p>The renderer owns the state-tracking loop and the draw order; the host
 * supplies the frame snapshot, material/frame bindings and the optional
 * background/transparent-variant collaborators.  It never reads the central
 * render pipeline, pipeline generation or effect owners directly.</p>
 */
public final class SceneRenderer {
    /** Per-generation collaborators supplied by the assembly layer. */
    public interface Host {
        SceneFrame frame();

        Camera camera();

        int width();

        int height();

        int frameIndex();

        boolean gtaoEnabled();

        boolean sharedDepth();

        InstancedRenderer instanced();

        SceneDeformationDefaults deformationDefaults();

        void updateCameraUniforms(CommandBuffer cmd);

        void renderBackground(CommandBuffer cmd, Camera camera, int width, int height, int segment);

        ShaderProgram transparentShader(Material template, ShaderProgram fallback);

        void bindVolumeQuery(CommandBuffer cmd, ShaderProgram shader);

        void bindFrameState(ShaderProgram shader, CommandBuffer cmd, int shadowTexture,
                            int pointShadowTexture, int spotShadowTexture, int gtaoTexture);

        boolean invalidatesFrameState(MaterialInstance material);

        void bindPbrMaterial(ShaderProgram shader, CommandBuffer cmd);
    }

    public void render(CommandBuffer cmd, int shadowTexture, int pointShadowTexture,
                       int spotShadowTexture, int gtaoTexture, int segment) {
        Objects.requireNonNull(cmd, "cmd");
        Host host = this.host;
        SceneFrame frame = host.frame();
        Camera camera = host.camera();
        int width = host.width();
        int height = host.height();
        boolean gtaoEnabled = host.gtaoEnabled();
        if (host.deformationDefaults() != null) {
            host.deformationDefaults().record(cmd);
        }
        if (host.sharedDepth()) {
            // The surface pass produced the shared depth; only fragments that
            // match the same surface may contribute color.
            cmd.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
        }
        // The GTAO depth prepass or the shared surface pass is the first graph
        // pass that uploads the camera block.  Reusing it avoids a second
        // per-frame UBO update on the enabled paths.
        if (!gtaoEnabled && !host.sharedDepth()) {
            host.updateCameraUniforms(cmd);
        }
        if (segment < 2) {
            host.renderBackground(cmd, camera, width, height, segment);
        }

        ShaderProgram boundShader = null;
        MaterialInstance boundMaterial = null;
        Material boundMaterialTemplate = null;
        boolean boundMaterialHasOverrides = false;
        Mesh boundMesh = null;
        boolean frontFaceBound = false;
        boolean boundMirrored = false;
        int boundGtaoTexture = -1;
        int boundSkinningEnabled = -1;
        int boundMorphTargetCount = -1;
        for (int queueIndex = 0; queueIndex < frame.forwardCount(); queueIndex++) {
            int entry = frame.forwardEntry(queueIndex);
            if (segment == 1 && !frame.castsOpaqueShadow(entry)
                    || segment >= 2 && frame.castsOpaqueShadow(entry)) continue;
            MeshRenderer renderer = frame.renderer(entry);
            Matrix4f model = frame.copyModel(entry, modelScratch);
            MaterialInstance material = renderer.material();
            Material materialTemplate = material.material();
            if (segment == 3 && materialTemplate.volumetricFogOptOut()) continue;
            ShaderProgram shader = segment >= 2
                    ? host.transparentShader(materialTemplate, materialTemplate.shader())
                    : materialTemplate.shader();
            boolean castsOpaque = gtaoEnabled && frame.castsOpaqueShadow(entry);
            boolean materialHasOverrides = material.hasOverrides();
            boolean mirrored = frame.mirrored(entry);
            if (!frontFaceBound || mirrored != boundMirrored) {
                cmd.frontFace(mirrored ? FrontFace.CW : FrontFace.CCW);
                frontFaceBound = true;
                boundMirrored = mirrored;
            }
            boolean materialChanged = material != boundMaterial;
            boolean materialBindingChanged = materialChanged
                    && (boundMaterialTemplate != materialTemplate
                    || boundMaterialHasOverrides || materialHasOverrides);
            if (materialBindingChanged) {
                material.bind(cmd, shader);
                if (segment >= 2 && !materialTemplate.volumetricFogOptOut()) {
                    cmd.blendFunc(org.lwjgl.opengl.GL11.GL_ONE,
                                    materialTemplate.blendMode() == BlendMode.ADDITIVE
                                            ? org.lwjgl.opengl.GL11.GL_ONE
                                            : org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA)
                            .setUniformInt(shader, "uVolumeAdditive",
                                    materialTemplate.blendMode() == BlendMode.ADDITIVE ? 1 : 0);
                }
            }
            boundMaterial = material;
            boundMaterialTemplate = materialTemplate;
            boundMaterialHasOverrides = materialHasOverrides;
            int materialGtaoTexture = castsOpaque ? gtaoTexture : 0;
            boolean gtaoBindingChanged = gtaoEnabled && materialGtaoTexture != boundGtaoTexture;
            if (shader != boundShader
                    || materialBindingChanged && host.invalidatesFrameState(material)
                    || gtaoBindingChanged) {
                host.bindFrameState(shader, cmd, shadowTexture, pointShadowTexture,
                        spotShadowTexture, materialGtaoTexture);
                if (material.material().model() == MaterialModel.METALLIC_ROUGHNESS) {
                    host.bindPbrMaterial(shader, cmd);
                }
                boundShader = shader;
                if (gtaoEnabled) boundGtaoTexture = materialGtaoTexture;
                boundSkinningEnabled = -1;
                boundMorphTargetCount = -1;
                if (segment >= 2 && !materialTemplate.volumetricFogOptOut()) {
                    host.bindVolumeQuery(cmd, shader);
                }
            }
            if (segment == 3) {
                cmd.setUniformInt(shader, "uVolumeReactiveOnly", 1).enableBlend(true)
                        .blendEquation(org.lwjgl.opengl.GL14.GL_MAX)
                        .enableDepthTest(false).depthMask(false);
            }
            cmd.setUniformMat4(shader, "uModel", model);
            SceneDrawBinding drawBinding = renderer.drawBinding();
            int skinningEnabled = drawBinding.skinningEnabled() ? 1 : 0;
            int morphTargetCount = drawBinding.morphTargetCount();
            if (skinningEnabled != boundSkinningEnabled) {
                cmd.trySetUniformInt(shader, "uSkinningEnabled", skinningEnabled);
                boundSkinningEnabled = skinningEnabled;
            }
            if (morphTargetCount != boundMorphTargetCount) {
                cmd.trySetUniformInt(shader, "uMorphTargetCount", morphTargetCount);
                boundMorphTargetCount = morphTargetCount;
            }
            if (drawBinding != SceneDrawBinding.NONE) {
                drawBinding.record(cmd, shader, (int) frame.frameIndex(),
                        SceneDrawBinding.Pass.FORWARD);
            }
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
        }

        InstancedRenderer instanced = host.instanced();
        if (segment < 2 && instanced != null) {
            cmd.bindShader(instanced.shader());
            cmd.enableBlend(false).depthMask(true).enableDepthTest(true);
            host.bindFrameState(instanced.shader(), cmd, shadowTexture, pointShadowTexture,
                    spotShadowTexture, gtaoEnabled ? gtaoTexture : 0);
            instanced.render(cmd);
        }
    }

    private final Matrix4f modelScratch = new Matrix4f();
    private final Host host;

    public SceneRenderer(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }
}
