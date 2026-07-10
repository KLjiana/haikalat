package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;

final class LightingBinder {
    static final int MAX_DIRECTIONAL_LIGHTS = 2;
    static final int MAX_POINT_LIGHTS = 8;
    static final int MAX_SPOT_LIGHTS = 4;
    private final Scene scene;

    LightingBinder(Scene scene) {
        this.scene = scene;
    }

    void bind(ShaderProgram shader, CommandBuffer cmd, Matrix4f directionalLightSpace) {
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
        cmd.trySetUniformVec3(shader, "uCameraPosition", scene.camera().position());
        cmd.trySetUniformMat4(shader, "uDirectionalLightSpace", directionalLightSpace);
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

    record LightCounts(int directional, int point, int spot) {
    }
}
