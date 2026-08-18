package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Optional;

final class LightingBinder {
    static final int MAX_DIRECTIONAL_LIGHTS = 2;
    static final int MAX_POINT_LIGHTS = 8;
    static final int MAX_SPOT_LIGHTS = 4;

    LightingBinder() { }

    void bind(ShaderProgram shader, CommandBuffer cmd, Matrix4f directionalLightSpace,
              List<Matrix4f> cascadeMatrices, float[] cascadeSplits,
              DirectionalCascadeSettings cascadeSettings,
              Camera camera, List<SceneLight> lights) {
        LightCounts counts = count(lights);
        int directionalCount = 0;
        int pointCount = 0;
        int spotCount = 0;
        for (SceneLight light : lights) {
            switch (light.type()) {
                case DIRECTIONAL -> {
                    if (directionalCount >= MAX_DIRECTIONAL_LIGHTS) {
                        continue;
                    }
                    String prefix = "uDirectionalLights[" + directionalCount + "].";
                    cmd.trySetUniformVec3(shader, prefix + "direction", light.direction());
                    cmd.trySetUniformVec3(shader, prefix + "color", light.color());
                    cmd.trySetUniformFloat(shader, prefix + "intensity", light.intensity());
                    directionalCount++;
                }
                case POINT -> {
                    if (pointCount >= MAX_POINT_LIGHTS) {
                        continue;
                    }
                    String prefix = "uPointLights[" + pointCount + "].";
                    cmd.trySetUniformVec3(shader, prefix + "position", light.position());
                    cmd.trySetUniformVec3(shader, prefix + "color", light.color());
                    cmd.trySetUniformFloat(shader, prefix + "intensity", light.intensity());
                    cmd.trySetUniformFloat(shader, prefix + "range", light.range());
                    pointCount++;
                }
                case SPOT -> {
                    if (spotCount >= MAX_SPOT_LIGHTS) {
                        continue;
                    }
                    String prefix = "uSpotLights[" + spotCount + "].";
                    cmd.trySetUniformVec3(shader, prefix + "position", light.position());
                    cmd.trySetUniformVec3(shader, prefix + "direction", light.direction());
                    cmd.trySetUniformVec3(shader, prefix + "color", light.color());
                    cmd.trySetUniformFloat(shader, prefix + "intensity", light.intensity());
                    cmd.trySetUniformFloat(shader, prefix + "range", light.range());
                    cmd.trySetUniformFloat(shader, prefix + "innerCone", light.innerConeRadians());
                    cmd.trySetUniformFloat(shader, prefix + "outerCone", light.outerConeRadians());
                    spotCount++;
                }
            }
        }
        cmd.trySetUniformInt(shader, "uDirectionalLightCount", counts.directional());
        cmd.trySetUniformInt(shader, "uPointLightCount", counts.point());
        cmd.trySetUniformInt(shader, "uSpotLightCount", counts.spot());
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
        cmd.trySetUniformInt(shader, "uDirectionalShadowLightIndex",
                shadowDirectionalLight(lights).map(ShadowDirectionalLight::shaderIndex).orElse(-1));
        cmd.trySetUniformInt(shader, "uPointShadowLightIndex",
                shadowPointLight(lights).map(ShadowPointLight::shaderIndex).orElse(-1));
        cmd.trySetUniformInt(shader, "uSpotShadowLightIndex",
                shadowSpotLight(lights).map(ShadowSpotLight::shaderIndex).orElse(-1));
    }

    static LightCounts count(Scene scene) {
        return count(scene.lights());
    }

    static LightCounts count(List<SceneLight> lights) {
        int directional = 0;
        int point = 0;
        int spot = 0;
        for (SceneLight light : lights) {
            switch (light.type()) {
                case DIRECTIONAL -> directional++;
                case POINT -> point++;
                case SPOT -> spot++;
            }
        }
        return new LightCounts(
                Math.min(directional, MAX_DIRECTIONAL_LIGHTS),
                Math.min(point, MAX_POINT_LIGHTS),
                Math.min(spot, MAX_SPOT_LIGHTS));
    }

    /**
     * 从上传到着色器的同一个有界数组中选择投射阴影的方向光。
     * 超出着色器数组上限的阴影灯光会被两个 pass 一致忽略。
     */
    static Optional<ShadowDirectionalLight> shadowDirectionalLight(Scene scene) {
        return shadowDirectionalLight(scene.lights());
    }

    static Optional<ShadowDirectionalLight> shadowDirectionalLight(List<SceneLight> lights) {
        int directionalIndex = 0;
        for (SceneLight light : lights) {
            if (light.type() != LightType.DIRECTIONAL) {
                continue;
            }
            if (directionalIndex >= MAX_DIRECTIONAL_LIGHTS) {
                break;
            }
            if (light.castShadows()) {
                return Optional.of(new ShadowDirectionalLight(light, directionalIndex));
            }
            directionalIndex++;
        }
        return Optional.empty();
    }

    static Optional<ShadowPointLight> shadowPointLight(Scene scene) {
        return shadowPointLight(scene.lights());
    }

    static Optional<ShadowPointLight> shadowPointLight(List<SceneLight> lights) {
        int pointIndex = 0;
        for (SceneLight light : lights) {
            if (light.type() != LightType.POINT) continue;
            if (pointIndex >= MAX_POINT_LIGHTS) break;
            if (light.castShadows()) return Optional.of(new ShadowPointLight(light, pointIndex));
            pointIndex++;
        }
        return Optional.empty();
    }

    static Optional<ShadowSpotLight> shadowSpotLight(Scene scene) {
        return shadowSpotLight(scene.lights());
    }

    static Optional<ShadowSpotLight> shadowSpotLight(List<SceneLight> lights) {
        int spotIndex = 0;
        for (SceneLight light : lights) {
            if (light.type() != LightType.SPOT) continue;
            if (spotIndex >= MAX_SPOT_LIGHTS) break;
            if (light.castShadows()) return Optional.of(new ShadowSpotLight(light, spotIndex));
            spotIndex++;
        }
        return Optional.empty();
    }

    record LightCounts(int directional, int point, int spot) {
    }

    record ShadowDirectionalLight(SceneLight light, int shaderIndex) {
    }

    record ShadowPointLight(SceneLight light, int shaderIndex) {
    }

    record ShadowSpotLight(SceneLight light, int shaderIndex) {
    }
}
