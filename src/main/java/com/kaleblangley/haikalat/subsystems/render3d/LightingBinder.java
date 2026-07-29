package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;

import java.util.Optional;

final class LightingBinder {
    static final int MAX_DIRECTIONAL_LIGHTS = 2;
    static final int MAX_POINT_LIGHTS = 8;
    static final int MAX_SPOT_LIGHTS = 4;
    private final Scene scene;

    LightingBinder(Scene scene) {
        this.scene = scene;
    }

    void bind(ShaderProgram shader, CommandBuffer cmd, Matrix4f directionalLightSpace) {
        bind(shader, cmd, directionalLightSpace, scene.camera());
    }

    void bind(ShaderProgram shader, CommandBuffer cmd, Matrix4f directionalLightSpace,
              Camera camera) {
        LightCounts counts = count(scene);
        int directionalCount = 0;
        int pointCount = 0;
        int spotCount = 0;
        for (SceneLight light : scene.lights()) {
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
        cmd.trySetUniformInt(shader, "uDirectionalShadowLightIndex",
                shadowDirectionalLight(scene).map(ShadowDirectionalLight::shaderIndex).orElse(-1));
        cmd.trySetUniformInt(shader, "uPointShadowLightIndex",
                shadowPointLight(scene).map(ShadowPointLight::shaderIndex).orElse(-1));
        cmd.trySetUniformInt(shader, "uSpotShadowLightIndex",
                shadowSpotLight(scene).map(ShadowSpotLight::shaderIndex).orElse(-1));
    }

    static LightCounts count(Scene scene) {
        int directional = 0;
        int point = 0;
        int spot = 0;
        for (SceneLight light : scene.lights()) {
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
        int directionalIndex = 0;
        for (SceneLight light : scene.lights()) {
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
        int pointIndex = 0;
        for (SceneLight light : scene.lights()) {
            if (light.type() != LightType.POINT) continue;
            if (pointIndex >= MAX_POINT_LIGHTS) break;
            if (light.castShadows()) return Optional.of(new ShadowPointLight(light, pointIndex));
            pointIndex++;
        }
        return Optional.empty();
    }

    static Optional<ShadowSpotLight> shadowSpotLight(Scene scene) {
        int spotIndex = 0;
        for (SceneLight light : scene.lights()) {
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
