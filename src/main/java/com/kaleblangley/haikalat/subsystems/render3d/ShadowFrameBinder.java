package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;

/**
 * Frame-bound CSM and shadow-mapping uniforms.
 *
 * <p>This replaces the old {@code LightingBinder} responsibility of uploading
 * cascade matrices and a directional shadow selector.  Shadow light selection
 * now travels through the unified light table as a frameLightIndex instead of
 * a per-type array position.</p>
 */
final class ShadowFrameBinder {
    void bind(CommandBuffer cmd, ShaderProgram shader, Camera camera,
              Matrix4f directionalLightSpace, List<Matrix4f> cascadeMatrices,
              float[] cascadeSplits, DirectionalCascadeSettings cascadeSettings,
              ShadowFramePlan plan) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(directionalLightSpace, "directionalLightSpace");
        Objects.requireNonNull(cascadeMatrices, "cascadeMatrices");
        Objects.requireNonNull(cascadeSplits, "cascadeSplits");
        Objects.requireNonNull(cascadeSettings, "cascadeSettings");
        Objects.requireNonNull(plan, "plan");

        cmd.trySetUniformVec3(shader, "uCameraPosition", camera.position());
        cmd.trySetUniformMat4(shader, "uDirectionalLightSpace", directionalLightSpace);
        int cascadeCount = Math.max(1, cascadeMatrices.size());
        cmd.trySetUniformInt(shader, "uDirectionalCascadeCount", cascadeCount)
                .trySetUniformFloat(shader, "uDirectionalCascadeBlendRange",
                        cascadeSettings.blendRange());
        for (int index = 0; index < 4; index++) {
            Matrix4f matrix = cascadeMatrices.isEmpty() ? directionalLightSpace
                    : cascadeMatrices.get(Math.min(index, cascadeMatrices.size() - 1));
            float split = cascadeSplits.length == 0 ? CameraProjection.FAR_PLANE
                    : cascadeSplits[Math.min(index, cascadeSplits.length - 1)];
            cmd.trySetUniformMat4(shader, "uDirectionalCascadeMatrices[" + index + "]", matrix)
                    .trySetUniformFloat(shader, "uDirectionalCascadeSplits[" + index + "]", split);
        }
        cmd.trySetUniformInt(shader, "uDirectionalShadowFrameLightIndex",
                plan.directional().map(ShadowFramePlan.DirectionalPlan::frameLightIndex)
                        .orElse(-1));
    }
}
