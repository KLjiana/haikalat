package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.ImageAccess;
import com.kaleblangley.haikalat.subsystems.render3d.StylizedSkySettings;
import com.kaleblangley.haikalat.core.device.RenderDevice;

import java.util.Objects;

/** 在当前 render thread/context 中解码 HDR 并执行一次 GPU IBL 预计算。 */
public final class PbrEnvironmentLoader {
    private PbrEnvironmentLoader() {
    }

    /** Builds caller-owned IBL from the displayed sky gradient, excluding the punctual sun disc. */
    public static PbrEnvironment fromSky(RenderDevice device, StylizedSkySettings sky,
                                         PbrEnvironmentSettings settings) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(sky, "sky");
        Objects.requireNonNull(settings, "settings");
        try (Texture2D source = Texture2D.createEmpty(256, 128, org.lwjgl.opengl.GL30.GL_RGBA16F);
             ShaderProgram shader = ShaderProgram.fromComputeResource(PbrEnvironmentLoader.class,
                     "/shaders/render3d/pbr/stylized-sky-ibl.comp")) {
            var commands = device.createCommandBuffer();
            commands.bindShader(shader)
                    .setUniformVec3(shader, "uZenithColor", sky.zenithColor())
                    .setUniformVec3(shader, "uHorizonColor", sky.horizonColor())
                    .setUniformVec3(shader, "uNadirColor", sky.nadirColor())
                    .bindImage(source, 0, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                    .dispatchCompute(32, 16, 1)
                    .memoryBarrier(org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                            | org.lwjgl.opengl.GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            device.execute(commands);
            PbrEnvironment environment = EnvironmentPreprocessor.preprocess(device, source, settings);
            environment.intensity(sky.environmentIntensity());
            return environment;
        } finally {
            device.invalidateState();
        }
    }

    /**
     * 使用将继续负责该 context 绘制的同一设备执行预计算。
     * 预计算会在清理临时 GL 资源后失效该设备的状态缓存。
     */
    public static PbrEnvironment load(RenderDevice device, Class<?> anchor, String hdrResource,
                                      PbrEnvironmentSettings settings) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(hdrResource, "hdrResource");
        Objects.requireNonNull(settings, "settings");
        Texture2D source = Texture2D.fromHdrResource(anchor, hdrResource, false);
        Throwable primaryFailure = null;
        try {
            return EnvironmentPreprocessor.preprocess(device, source, settings);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                source.close();
            } catch (RuntimeException cleanupFailure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }
}
