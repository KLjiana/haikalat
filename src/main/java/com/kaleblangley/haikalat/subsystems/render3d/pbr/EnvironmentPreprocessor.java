package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.ImageAccess;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureCube;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.RenderDevice;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_RG16F;
import static org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.GL_TEXTURE_FETCH_BARRIER_BIT;

/** 固定的 GPU environment preprocessing 实现，不参与逐帧 RenderGraph。 */
final class EnvironmentPreprocessor {
    static final String FAILURE_POINT_PROPERTY = "haikalat.pbr.testFailurePoint";
    private static final String SHADER_ROOT = "/render3d/pbr/";
    private static final int VISIBILITY_BARRIER =
            GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT;

    private EnvironmentPreprocessor() {
    }

    static PbrEnvironment preprocess(RenderDevice device, Texture2D source,
                                     PbrEnvironmentSettings settings) {
        return preprocess(device, source, settings, configuredFailurePoint(), LifecycleObserver.NOOP);
    }

    static PbrEnvironment preprocess(RenderDevice device, Texture2D source,
                                     PbrEnvironmentSettings settings, FailurePoint failurePoint,
                                     LifecycleObserver observer) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(failurePoint, "failurePoint");
        Objects.requireNonNull(observer, "observer");
        TextureCube environment = null;
        TextureCube irradiance = null;
        TextureCube prefiltered = null;
        Texture2D brdfLut = null;
        Throwable primaryFailure = null;
        try (ShaderProgram equirect = compute("equirect_to_cube.comp");
             ShaderProgram irradianceProgram = compute("irradiance.comp");
             ShaderProgram prefilterProgram = compute("prefilter.comp");
             ShaderProgram brdfProgram = compute("brdf_lut.comp");
             Sampler sourceSampler = sampler(GL_LINEAR, GL_LINEAR);
             Sampler cubeSampler = sampler(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR)) {
            environment = TextureCube.create(settings.environmentSize(),
                    TextureCube.maximumMipLevels(settings.environmentSize()), RenderFormat.RGBA16F);
            observer.created("radiance", environment.id());
            failIf(failurePoint, FailurePoint.AFTER_ENVIRONMENT_CUBE);
            irradiance = TextureCube.create(settings.irradianceSize(), 1, RenderFormat.RGBA16F);
            observer.created("irradiance", irradiance.id());
            prefiltered = TextureCube.create(settings.prefilteredSize(),
                    TextureCube.maximumMipLevels(settings.prefilteredSize()), RenderFormat.RGBA16F);
            observer.created("prefiltered", prefiltered.id());
            brdfLut = Texture2D.createEmpty(settings.brdfLutSize(), settings.brdfLutSize(), GL_RG16F);
            observer.created("brdfLut", brdfLut.id());

            runEquirect(device, source, sourceSampler, environment, equirect);
            runIrradiance(environment, cubeSampler, irradiance, irradianceProgram,
                    settings.irradianceSamples(), device);
            runPrefilter(environment, cubeSampler, prefiltered, prefilterProgram,
                    settings.prefilterSamples(), device, failurePoint);
            runBrdf(brdfLut, brdfProgram, settings.brdfSamples(), device);
            failIf(failurePoint, FailurePoint.AFTER_BRDF_LUT);

            PbrEnvironment result = new PbrEnvironment(environment, irradiance, prefiltered,
                    brdfLut, 1.0f, 0.0f);
            environment = null;
            irradiance = null;
            prefiltered = null;
            brdfLut = null;
            return result;
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = null;
            cleanupFailure = close("brdfLut", brdfLut, observer, cleanupFailure);
            cleanupFailure = close("prefiltered", prefiltered, observer, cleanupFailure);
            cleanupFailure = close("irradiance", irradiance, observer, cleanupFailure);
            cleanupFailure = close("radiance", environment, observer, cleanupFailure);
            try {
                // try-with-resources 同时删除 transient shader/sampler；缓存中的名称不可复用。
                device.invalidateState();
            } catch (RuntimeException failure) {
                cleanupFailure = accumulate(cleanupFailure, failure);
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static void runEquirect(RenderDevice device, Texture2D source, Sampler sampler,
                                    TextureCube output, ShaderProgram shader) {
        CommandBuffer cmd = device.createCommandBuffer();
        cmd.bindShader(shader)
                .bindTexture(0, source, sampler)
                .setUniformInt(shader, "uEquirectangular", 0)
                .bindImage(output, 0, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                .dispatchCompute(groups(output.size()), groups(output.size()), 6)
                .memoryBarrier(VISIBILITY_BARRIER)
                .generateMipmaps(output)
                .memoryBarrier(VISIBILITY_BARRIER);
        device.execute(cmd);
    }

    private static void runIrradiance(TextureCube source, Sampler sampler, TextureCube output,
                                      ShaderProgram shader, int samples, RenderDevice device) {
        CommandBuffer cmd = device.createCommandBuffer();
        cmd.bindShader(shader)
                .bindTextureCube(0, source, sampler)
                .setUniformInt(shader, "uEnvironment", 0)
                .setUniformInt(shader, "uSampleCount", samples)
                .bindImage(output, 0, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                .dispatchCompute(groups(output.size()), groups(output.size()), 6)
                .memoryBarrier(VISIBILITY_BARRIER);
        device.execute(cmd);
    }

    private static void runPrefilter(TextureCube source, Sampler sampler, TextureCube output,
                                     ShaderProgram shader, int samples, RenderDevice device,
                                     FailurePoint failurePoint) {
        for (int mip = 0; mip < output.mipLevels(); mip++) {
            if (mip == Math.min(1, output.mipLevels() - 1)) {
                failIf(failurePoint, FailurePoint.BEFORE_PREFILTER_MIP);
            }
            int size = Math.max(1, output.size() >> mip);
            float roughness = output.mipLevels() == 1 ? 0.0f
                    : (float) mip / (output.mipLevels() - 1);
            CommandBuffer cmd = device.createCommandBuffer();
            cmd.bindShader(shader)
                    .bindTextureCube(0, source, sampler)
                    .setUniformInt(shader, "uEnvironment", 0)
                    .setUniformInt(shader, "uSampleCount", samples)
                    .setUniformFloat(shader, "uRoughness", roughness)
                    .bindImage(output, 0, mip, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                    .dispatchCompute(groups(size), groups(size), 6)
                    .memoryBarrier(VISIBILITY_BARRIER);
            device.execute(cmd);
        }
    }

    private static void runBrdf(Texture2D output, ShaderProgram shader,
                                int samples, RenderDevice device) {
        CommandBuffer cmd = device.createCommandBuffer();
        cmd.bindShader(shader)
                .setUniformInt(shader, "uSampleCount", samples)
                .bindImage(output, 0, 0, ImageAccess.WRITE_ONLY, RenderFormat.RG16F)
                .dispatchCompute(groups(output.width()), groups(output.height()), 1)
                .memoryBarrier(VISIBILITY_BARRIER);
        device.execute(cmd);
    }

    private static ShaderProgram compute(String name) {
        return ShaderProgram.fromComputeResource(EnvironmentPreprocessor.class, SHADER_ROOT + name);
    }

    private static Sampler sampler(int min, int mag) {
        return Sampler.create(new Sampler.Descriptor(min, mag,
                GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
    }

    private static int groups(int size) {
        return (size + 7) / 8;
    }

    private static RuntimeException close(String kind, AutoCloseable resource,
                                          LifecycleObserver observer,
                                          RuntimeException failure) {
        if (resource == null) return failure;
        int id = resource instanceof TextureCube cube ? cube.id()
                : resource instanceof Texture2D texture ? texture.id() : 0;
        try {
            resource.close();
            observer.closed(kind, id);
        } catch (Exception error) {
            RuntimeException wrapped = error instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Failed to release partial PBR environment", error);
            failure = accumulate(failure, wrapped);
        }
        return failure;
    }

    private static RuntimeException accumulate(RuntimeException failure, RuntimeException next) {
        if (failure == null) return next;
        failure.addSuppressed(next);
        return failure;
    }

    private static FailurePoint configuredFailurePoint() {
        String configured = System.getProperty(FAILURE_POINT_PROPERTY);
        if (configured == null || configured.isBlank()) return FailurePoint.NONE;
        try {
            return FailurePoint.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown PBR preprocessing failure point: " + configured,
                    invalid);
        }
    }

    private static void failIf(FailurePoint configured, FailurePoint current) {
        if (configured == current) {
            throw new IllegalStateException("Injected PBR preprocessing failure at " + current);
        }
    }

    enum FailurePoint {
        NONE,
        AFTER_ENVIRONMENT_CUBE,
        BEFORE_PREFILTER_MIP,
        AFTER_BRDF_LUT
    }

    interface LifecycleObserver {
        LifecycleObserver NOOP = new LifecycleObserver() { };

        default void created(String kind, int id) { }

        default void closed(String kind, int id) { }
    }
}
