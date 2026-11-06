package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.FrontFace;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.UniformKey;
import com.kaleblangley.haikalat.core.material.UniformValue;
import org.joml.Vector4f;

/** GTAO's optional dedicated depth producer; owns its programs, borrows all scene data. */
final class GtaoDepthPass implements AutoCloseable {
    private ShaderProgram depth, masked, instancedDepth;

    GtaoDepthPass(boolean instances) {
        try {
            depth = ShaderProgram.fromResource(GtaoDepthPass.class,
                    "/shaders/render3d/gtao/gtao-depth.vert", "/shaders/render3d/gtao/gtao-depth.frag");
            masked = ShaderProgram.fromResource(GtaoDepthPass.class,
                    "/shaders/render3d/gtao/gtao-masked-depth.vert", "/shaders/render3d/gtao/gtao-masked-depth.frag");
            if (instances) instancedDepth = ShaderProgram.fromResource(GtaoDepthPass.class,
                    "/shaders/render3d/gtao/gtao-instanced-depth.vert", "/shaders/render3d/gtao/gtao-depth.frag");
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    int record(CommandBuffer cmd, SceneFrame frame, Camera camera, CameraUniforms uniforms,
               InstancedRenderer instanced, AntiAliasingMode aa, int frameIndex, int width, int height) {
        int draws = 0;
        uniforms.update(cmd, camera, width, height,
                aa, frameIndex);
        ShaderProgram depthShader = depth;
        cmd.bindShader(depthShader)
                .enableBlend(false).enableDepthTest(true).depthMask(true)
                // Establish a deterministic raster state, then override it
                // for every regular renderer from its Material.cullMode.
                .frontFace(FrontFace.CCW).enableCullFace(true);
        uniforms.bind(depthShader);
        ShaderProgram boundShader = depthShader;
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        boolean frontFaceBound = false;
        boolean cullBound = false;
        boolean boundMirrored = false;
        boolean boundCull = false;
        int boundSkinningEnabled = -1;
        int boundMorphTargetCount = -1;
        for (int queueIndex = 0; queueIndex < frame.forwardCount; queueIndex++) {
            int entry = frame.forwardEntry(queueIndex);
            MeshRenderer renderer = frame.renderer(entry);
            if (!frame.castsOpaqueShadow(entry)) continue;
            boolean mirrored = frame.mirrored(entry);
            boolean cull = renderer.material().material().cullMode() == CullMode.BACK;
            if (!frontFaceBound || mirrored != boundMirrored) {
                cmd.frontFace(mirrored ? FrontFace.CW : FrontFace.CCW);
                frontFaceBound = true;
                boundMirrored = mirrored;
            }
            if (!cullBound || cull != boundCull) {
                cmd.enableCullFace(cull);
                cullBound = true;
                boundCull = cull;
            }
            ShaderProgram shader = frame.masked(entry)
                    ? masked : depth;
            if (shader != boundShader) {
                cmd.bindShader(shader);
                uniforms.bind(shader);
                boundShader = shader;
                boundSkinningEnabled = -1;
                boundMorphTargetCount = -1;
            }
            if (shader == masked) {
                bindGtaoMaskedMaterial(cmd, shader, renderer.material());
            }
            cmd.setUniformMat4(shader, "uModel", frame.model(entry));
            SceneDrawBinding binding = renderer.drawBinding();
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
                binding.record(cmd, shader, frameIndex, SceneDrawBinding.Pass.DEPTH_PREPASS);
            }
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
            draws++;
        }
        if (instanced != null && instancedDepth != null) {
            // Instanced depth uses its own winding convention; do not inherit
            // a mirrored/cull state from the last regular mesh.
            cmd.enableCullFace(false).frontFace(FrontFace.CCW);
            cmd.bindShader(instancedDepth);
            uniforms.bind(instancedDepth);
            instanced.renderDepth(cmd, instancedDepth);
            draws++;
        }
        return draws;
    }

    private static void bindGtaoMaskedMaterial(CommandBuffer cmd, ShaderProgram shader,
                                        MaterialInstance instance) {
        Material material = instance.material();
        Material.TextureBinding baseColor = instance.textureOverrides().get(0);
        if (baseColor == null) {
            for (Material.TextureBinding binding : material.defaultTextures()) {
                if (binding.unit() == 0) { baseColor = binding; break; }
            }
        }
        if (baseColor != null) cmd.bindTexture(0, baseColor.texture(), baseColor.sampler());
        UniformValue cutoff = instance.uniformOverrides().get(UniformKey.float1("uAlphaCutoff"));
        if (cutoff == null) cutoff = material.defaultUniforms().get(UniformKey.float1("uAlphaCutoff"));
        UniformValue factor = instance.uniformOverrides().get(UniformKey.vec4("uBaseColorFactor"));
        if (factor == null) factor = material.defaultUniforms().get(UniformKey.vec4("uBaseColorFactor"));
        float cutoffValue = cutoff instanceof UniformValue.FloatVal value ? value.value() : 0.5f;
        Vector4f factorValue = factor instanceof UniformValue.Vec4Val value
                ? value.value() : new Vector4f(1.0f);
        UniformValue vertexColor = instance.uniformOverrides().get(UniformKey.int1("uHasVertexColor"));
        if (vertexColor == null) vertexColor = material.defaultUniforms().get(UniformKey.int1("uHasVertexColor"));
        cmd.setUniformInt(shader, "uBaseColorMap", 0)
                .setUniformInt(shader, "uHasVertexColor", vertexColor instanceof UniformValue.IntVal value ? value.value() : 0)
                .setUniformFloat(shader, "uAlphaCutoff", cutoffValue)
                .setUniformVec4(shader, "uBaseColorFactor", factorValue);
    }


    @Override public void close() {
        RuntimeException failure = null;
        for (ShaderProgram program : new ShaderProgram[]{instancedDepth, masked, depth}) {
            if (program == null) continue;
            try { program.close(); } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
