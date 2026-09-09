package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.UniformKey;
import com.kaleblangley.haikalat.core.material.UniformValue;
import com.kaleblangley.haikalat.core.mesh.Mesh;

import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * Conservative reactive-mask producer.  It re-renders only materials that
 * declare a temporal distrust value and accumulates them additively; over
 * marking an occluded transparent pixel only reduces history reuse, it cannot
 * corrupt color.  The pass owns the shared {@code sceneReactive} texture.
 */
final class SceneReactivePass implements AutoCloseable {
    private final ShaderProgram program;
    private boolean closed;

    SceneReactivePass() {
        this.program = ShaderProgram.fromResource(SceneReactivePass.class,
                "/shaders/render3d/surface/scene-surface.vert",
                "/shaders/render3d/surface/scene-reactive.frag");
    }

    /** @return number of reactive-declaring renderers submitted */
    int record(CommandBuffer cmd, SceneFrame frame, CameraUniforms cameraUniforms) {
        if (closed) throw new GlException("scene reactive pass is closed");
        cameraUniforms.bind(program);
        cmd.bindShader(program)
                .enableBlend(true)
                .blendFunc(GL_ONE, GL_ONE)
                .enableDepthTest(false)
                .enableCullFace(false)
                .depthMask(false);
        Mesh boundMesh = null;
        int draws = 0;
        for (int queueIndex = 0; queueIndex < frame.forwardCount; queueIndex++) {
            int entry = frame.forwardEntry(queueIndex);
            MeshRenderer renderer = frame.renderer(entry);
            float reactive = reactiveOf(renderer.material());
            if (reactive <= 0.0f) continue;
            cmd.setUniformMat4(program, "uModel", frame.model(entry))
                    .setUniformFloat(program, "uTemporalReactive", reactive);
            SceneDrawBinding binding = renderer.drawBinding();
            cmd.trySetUniformInt(program, "uSkinningEnabled", binding.skinningEnabled() ? 1 : 0)
                    .trySetUniformInt(program, "uMorphTargetCount", binding.morphTargetCount());
            if (binding != SceneDrawBinding.NONE) {
                binding.record(cmd, program, (int) frame.frameIndex, SceneDrawBinding.Pass.SURFACE);
            }
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
            draws++;
        }
        cmd.enableBlend(false).enableDepthTest(true).depthMask(true);
        return draws;
    }

    /** @return declared temporal distrust in [0, 1], or 0 when absent */
    static float reactiveOf(MaterialInstance instance) {
        if (instance == null) return 0.0f;
        UniformKey<UniformValue.FloatVal> key = UniformKey.float1(
                Material.TEMPORAL_REACTIVE_UNIFORM);
        UniformValue value = instance.uniformOverrides().get(key);
        if (value == null) {
            value = instance.material().defaultUniforms().get(key);
        }
        if (value instanceof UniformValue.FloatVal floatValue) {
            float declared = floatValue.value();
            return Float.isFinite(declared) ? Math.clamp(declared, 0.0f, 1.0f) : 0.0f;
        }
        return 0.0f;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        program.close();
    }
}
